package com.example.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Resumable, integrity-checked downloader for on-device model weights hosted in DigitalOcean Spaces.
 *
 * Durability guarantees (the "recover failed/partial downloads" requirement):
 *  - Bytes land in `<name>.part`; an interrupted download leaves the partial file and the next
 *    attempt resumes from its length via an HTTP `Range` request instead of restarting.
 *  - Size and SHA-256 come from the issuer manifest and are persisted next to the partial file, so a
 *    resume after process death verifies against the same manifest the download started with.
 *  - The final file is only published (rename + `.ok` marker) after BOTH the byte size and SHA-256
 *    match. A corrupt or truncated file is never handed to the engine.
 *  - If the server ignores `Range` (HTTP 200) or rejects it (416), the partial file is discarded and
 *    the download restarts cleanly. An expired presigned URL (403) is re-issued and resumed.
 *
 * The download URL is a short-lived presigned Spaces URL fetched from a rate-limited issuer, so the
 * Spaces secret never ships in the app and the bucket is not publicly readable.
 */
class ModelDownloadManager(
    context: Context,
    private val okHttpClient: OkHttpClient,
    private val issuerBaseUrl: String = DEFAULT_ISSUER_URL,
) {
    data class ModelSpec(
        val id: String,
        val fileName: String,
        val expectedSize: Long,
        val sha256: String,
    )

    enum class Phase { NOT_DOWNLOADED, DOWNLOADING, PAUSED, VERIFYING, READY, FAILED }

    /** Real on-disk download state. [totalBytes] is 0 until the issuer manifest has been fetched. */
    data class DownloadState(
        val phase: Phase,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val error: String? = null,
    )

    /** A failure that retrying cannot fix (bad manifest, checksum mismatch, unknown model). */
    class PermanentDownloadException(message: String) : Exception(message)

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    private val _state = MutableStateFlow(diskState(GEMMA_4_E2B_FILE))
    /** State of the Gemma fallback weights, derived from disk at startup and updated live. */
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    fun modelFile(fileName: String = GEMMA_4_E2B_FILE): File = File(modelsDir, fileName)

    private fun partFile(fileName: String): File = File(modelsDir, "$fileName.part")
    private fun pendingManifestFile(fileName: String): File = File(modelsDir, "$fileName.manifest.json")
    private fun verifiedMarkerFile(fileName: String): File = File(modelsDir, "$fileName.ok")

    /** True only when a checksum-verified file of the verified size is present. */
    fun isReady(fileName: String = GEMMA_4_E2B_FILE): Boolean {
        val f = modelFile(fileName)
        val verifiedSize = readSpec(verifiedMarkerFile(fileName))?.expectedSize ?: return false
        return f.isFile && f.length() == verifiedSize
    }

    private fun diskState(fileName: String): DownloadState {
        if (isReady(fileName)) {
            val size = modelFile(fileName).length()
            return DownloadState(Phase.READY, size, size)
        }
        val part = partFile(fileName)
        val total = readSpec(pendingManifestFile(fileName))?.expectedSize ?: 0L
        return if (part.isFile && part.length() > 0L) {
            DownloadState(Phase.PAUSED, part.length(), total)
        } else {
            DownloadState(Phase.NOT_DOWNLOADED, 0L, total)
        }
    }

    /** Re-derive [state] from disk (used when a download stops without finishing). */
    fun markStopped(error: String? = null, failed: Boolean = false) {
        val disk = diskState(GEMMA_4_E2B_FILE)
        _state.value = when {
            disk.phase == Phase.READY -> disk
            failed -> disk.copy(phase = Phase.FAILED, error = error)
            else -> disk.copy(error = error)
        }
    }

    /** Delete the weights, any partial download, and its manifest. */
    fun delete(fileName: String = GEMMA_4_E2B_FILE) {
        modelFile(fileName).delete()
        partFile(fileName).delete()
        pendingManifestFile(fileName).delete()
        verifiedMarkerFile(fileName).delete()
        _state.value = diskState(fileName)
    }

    /**
     * Download (or resume) the model [modelId] into a verified file and return it. Throws
     * [IOException] for transient failures (the caller retries; the partial file is kept) and
     * [PermanentDownloadException] for failures a retry cannot fix.
     */
    suspend fun download(modelId: String = GEMMA_4_E2B_ID): File = withContext(Dispatchers.IO) {
        // Resume against the manifest the partial file was started with; otherwise ask the issuer.
        val spec = readSpec(pendingManifestFile(GEMMA_4_E2B_FILE))?.takeIf { it.id == modelId }
            ?: fetchManifest(modelId).also { writeSpec(pendingManifestFile(it.fileName), it) }
        val target = modelFile(spec.fileName)
        val part = partFile(spec.fileName)
        if (isReady(spec.fileName)) {
            _state.value = DownloadState(Phase.READY, target.length(), target.length())
            return@withContext target
        }
        var downloaded = if (part.isFile) part.length() else 0L
        if (downloaded > spec.expectedSize) {
            part.delete()
            downloaded = 0L
        }
        _state.value = DownloadState(Phase.DOWNLOADING, downloaded, spec.expectedSize)

        var urlRefreshes = 0
        while (downloaded < spec.expectedSize) {
            coroutineContext.ensureActive()
            val url = fetchPresignedUrl(modelId)
            val req = Request.Builder().url(url).apply {
                if (downloaded > 0) header("Range", "bytes=$downloaded-")
            }.build()
            okHttpClient.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        // Server ignored Range; restart from zero.
                        part.delete()
                        downloaded = 0L
                    }
                    206 -> Unit // resume: append to the existing partial file
                    416 -> {
                        // Partial file no longer lines up with the object; restart from zero.
                        part.delete()
                        downloaded = 0L
                        return@use
                    }
                    403 -> {
                        // Presigned URL expired or was rejected; issue a fresh one a few times.
                        if (++urlRefreshes > MAX_URL_REFRESHES) {
                            throw IOException("Model storage refused the download (HTTP 403).")
                        }
                        return@use
                    }
                    else -> throw IOException("Model download HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IOException("Empty model download response")
                part.sink(append = resp.code == 206).buffer().use { sink ->
                    body.source().use { source ->
                        val buf = okio.Buffer()
                        var sinceReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = source.read(buf, 64 * 1024)
                            if (read == -1L) break
                            sink.write(buf, read)
                            downloaded += read
                            sinceReport += read
                            if (sinceReport >= PROGRESS_STEP_BYTES) {
                                sinceReport = 0L
                                _state.value = DownloadState(Phase.DOWNLOADING, downloaded, spec.expectedSize)
                            }
                        }
                    }
                }
            }
            downloaded = if (part.isFile) part.length() else 0L
            _state.value = DownloadState(Phase.DOWNLOADING, downloaded, spec.expectedSize)
        }

        _state.value = DownloadState(Phase.VERIFYING, downloaded, spec.expectedSize)
        if (part.length() != spec.expectedSize) {
            val got = part.length()
            part.delete()
            pendingManifestFile(spec.fileName).delete()
            throw PermanentDownloadException("Downloaded size $got does not match ${spec.expectedSize}.")
        }
        val actualSha = sha256(part)
        if (!actualSha.equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            pendingManifestFile(spec.fileName).delete()
            throw PermanentDownloadException("Checksum mismatch — the download was corrupt and was discarded.")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        writeSpec(verifiedMarkerFile(spec.fileName), spec)
        pendingManifestFile(spec.fileName).delete()
        _state.value = DownloadState(Phase.READY, target.length(), target.length())
        target
    }

    /** Fetch size + SHA-256 for [modelId] from the issuer. */
    suspend fun fetchManifest(modelId: String): ModelSpec = withContext(Dispatchers.IO) {
        val json = issuerGet("$issuerBaseUrl/manifest?model=$modelId")
        val sha = json.optString("sha256")
        val size = json.optLong("size", -1L)
        val fileName = json.optString("file_name")
        if (sha.length != 64 || size <= 0L || fileName.isBlank() || fileName.contains('/')) {
            throw PermanentDownloadException("Model manifest is incomplete.")
        }
        ModelSpec(id = modelId, fileName = fileName, expectedSize = size, sha256 = sha)
    }

    /** Fetch a short-lived presigned download URL from the rate-limited issuer. */
    suspend fun fetchPresignedUrl(modelId: String): String = withContext(Dispatchers.IO) {
        issuerGet("$issuerBaseUrl/url?model=$modelId").getString("url")
    }

    private fun issuerGet(url: String): JSONObject {
        val req = Request.Builder().url(url).get().build()
        return okHttpClient.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> throw PermanentDownloadException("Model is not published on the model server.")
                resp.code == 429 -> throw IOException("Model server is rate limiting this network; retrying later.")
                !resp.isSuccessful -> throw IOException("Model server HTTP ${resp.code}")
            }
            JSONObject(resp.body?.string().orEmpty())
        }
    }

    private fun readSpec(file: File): ModelSpec? = runCatching {
        if (!file.isFile) return null
        val j = JSONObject(file.readText())
        ModelSpec(j.getString("id"), j.getString("file_name"), j.getLong("size"), j.getString("sha256"))
    }.getOrNull()

    private fun writeSpec(file: File, spec: ModelSpec) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(
            JSONObject()
                .put("id", spec.id)
                .put("file_name", spec.fileName)
                .put("size", spec.expectedSize)
                .put("sha256", spec.sha256)
                .toString()
        )
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_ISSUER_URL = "https://logs.relay.stare.network/models"
        const val GEMMA_4_E2B_ID = "gemma-4-E2B-it"
        const val GEMMA_4_E2B_FILE = "gemma-4-E2B-it.litertlm"
        private const val MAX_URL_REFRESHES = 3
        private const val PROGRESS_STEP_BYTES = 1L * 1024 * 1024
    }
}
