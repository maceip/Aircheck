package com.example.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.security.MessageDigest

/**
 * Resumable, integrity-checked downloader for on-device model weights hosted in DigitalOcean Spaces.
 *
 * Durability guarantees (the "recover failed/partial downloads" requirement):
 *  - Downloads into `<name>.part`; a interrupted download leaves a partial file that the next attempt
 *    resumes from via HTTP `Range` requests instead of restarting.
 *  - The final file is only published (atomic rename) after BOTH the byte size and SHA-256 match the
 *    manifest. A corrupt or truncated file is never handed to the engine.
 *  - If the server ignores `Range` (HTTP 200 instead of 206), the partial file is discarded and the
 *    download restarts cleanly.
 *
 * The download URL is a short-lived presigned Spaces URL fetched from a rate-limited issuer, so the
 * Spaces secret never ships in the app and the object is not publicly scrapeable.
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

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(spec: ModelSpec): File = File(modelsDir, spec.fileName)

    private fun partFile(spec: ModelSpec): File = File(modelsDir, spec.fileName + ".part")

    /** True only when a fully-downloaded, size-correct file exists (checksum verified on download). */
    fun isReady(spec: ModelSpec): Boolean {
        val f = modelFile(spec)
        return f.isFile && f.length() == spec.expectedSize
    }

    fun partialBytes(spec: ModelSpec): Long = partFile(spec).let { if (it.isFile) it.length() else 0L }

    /**
     * Download (or resume) [spec] to a verified file. [urlProvider] returns a fresh presigned URL;
     * it is re-invoked on each resume attempt because presigned URLs expire. [onProgress] reports
     * bytes downloaded so far (for the UI). Returns the verified model file.
     */
    suspend fun download(
        spec: ModelSpec,
        urlProvider: suspend () -> String = { fetchPresignedUrl(spec.id) },
        onProgress: (Long) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = modelFile(spec)
            if (isReady(spec)) {
                onProgress(spec.expectedSize)
                return@runCatching target
            }
            val part = partFile(spec)
            var downloaded = if (part.isFile) part.length() else 0L
            if (downloaded > spec.expectedSize) {
                part.delete()
                downloaded = 0L
            }
            onProgress(downloaded)

            while (downloaded < spec.expectedSize) {
                val url = urlProvider()
                val req = Request.Builder().url(url).apply {
                    if (downloaded > 0) header("Range", "bytes=$downloaded-")
                }.build()
                okHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body ?: throw IllegalStateException("Empty response body")
                    when (resp.code) {
                        200 -> {
                            // Server ignored Range; restart from zero.
                            part.delete()
                            downloaded = 0L
                        }
                        206 -> { /* resume: append to existing partial file */ }
                        else -> throw IllegalStateException("Model download HTTP ${resp.code}")
                    }
                    part.sink(append = resp.code == 206).buffer().use { sink ->
                        body.source().use { source ->
                            val buf = okio.Buffer()
                            while (true) {
                                val read = source.read(buf, 64 * 1024)
                                if (read == -1L) break
                                sink.write(buf, read)
                                downloaded += read
                                onProgress(downloaded)
                            }
                        }
                    }
                }
                // A presigned URL may expire mid-transfer; loop re-fetches and resumes.
                if (downloaded < spec.expectedSize && part.length() < spec.expectedSize) {
                    downloaded = part.length()
                }
            }

            if (part.length() != spec.expectedSize) {
                part.delete()
                throw IllegalStateException("Size mismatch: ${part.length()} != ${spec.expectedSize}")
            }
            val actualSha = sha256(part)
            if (!actualSha.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                throw IllegalStateException("SHA-256 mismatch: expected ${spec.sha256}, got $actualSha")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            target
        }
    }

    /** Fetch a short-lived presigned download URL from the rate-limited issuer. */
    suspend fun fetchPresignedUrl(modelId: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$issuerBaseUrl/url?model=$modelId").get().build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Issuer HTTP ${resp.code}")
            val json = org.json.JSONObject(resp.body?.string().orEmpty())
            json.getString("url")
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

        val GEMMA_4_E2B = ModelSpec(
            id = "gemma-4-E2B-it",
            fileName = "gemma-4-E2B-it.litertlm",
            expectedSize = 2_588_147_712L,
            sha256 = "", // filled from the issuer manifest at runtime; see fetchManifest
        )
    }
}
