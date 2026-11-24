package com.example.data.gateway

import com.example.data.RenderJobStatus
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import com.example.data.local.CasMediaStore
import com.example.data.local.CasStoredAsset
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.data.local.DEFAULT_MAC_RIG_USB_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 100% Local-First Mac Studio Rig Gateway (`ota_server.py` on `:8443`).
 * Powers AI writing assist (`/v1/ai-assist`), real portrait preview (`/v1/default-avatar`),
 * finished video gallery (`/v1/renders`), and talking-head rendering (`/v1/render-video`).
 * Zero Firebase, zero sign-in, zero silent mocks.
 */
class DirectMacRigGateway(
    private val casStore: CasMediaStore,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) : MacRigRenderGateway {

    private val probeClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(4000, TimeUnit.MILLISECONDS)
            .build()
    }

    override suspend fun resolveAndCheckHealth(configuredUrl: String): MacRigHealthResult =
        withContext(Dispatchers.IO) {
            val candidates = buildCandidateUrls(configuredUrl)
            val errors = mutableListOf<String>()
            for (candidate in candidates) {
                val res = runCatching { checkSingleRigHealth(candidate, probeClient) }
                if (res.isSuccess) {
                    return@withContext res.getOrThrow()
                } else {
                    val reason = res.exceptionOrNull()?.localizedMessage ?: "unreachable"
                    errors += "$candidate ($reason)"
                }
            }
            throw IllegalStateException(
                "Studio server unreachable. Checked: ${errors.joinToString("; ")}. " +
                    "Verify Wi-Fi or USB connection to your Mac."
            )
        }

    private fun buildCandidateUrls(configuredUrl: String): List<String> {
        val trimmed = configuredUrl.trim().trimEnd('/')
        val ordered = linkedSetOf<String>()
        if (trimmed.isNotBlank() && trimmed != DEFAULT_MAC_RIG_LAN_URL && trimmed != DEFAULT_MAC_RIG_USB_URL) {
            ordered += trimmed
        }
        ordered += DEFAULT_MAC_RIG_USB_URL
        ordered += DEFAULT_MAC_RIG_LAN_URL
        if (trimmed.isNotBlank()) {
            ordered += trimmed
        }
        return ordered.toList()
    }

    private fun checkSingleRigHealth(rigBaseUrl: String, client: OkHttpClient): MacRigHealthResult {
        val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
        val req = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .build()
        val rawBody = client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = extractErrorDetail(bodyStr)
                throw IllegalStateException(
                    "HTTP ${resp.code}${if (detail.isNotBlank()) ": $detail" else ""}"
                )
            }
            if (bodyStr.isBlank()) {
                throw IllegalStateException("empty /health response")
            }
            bodyStr
        }
        val json = JSONObject(rawBody)
        return MacRigHealthResult(
            resolvedUrl = baseUrl,
            status = json.optNullableString("status") ?: "unknown",
            loraVersion = json.optInt("lora_version", 0),
            bundleVersion = json.optNullableString("bundle_version"),
            pendingPreferences = json.optInt("pending_preferences", 0),
            renderBridgeAvailable = json.optBoolean("render_bridge", false),
            activeRenderJobs = json.optInt("active_render_jobs", 0)
        )
    }

    override suspend fun listRemoteRenders(rigBaseUrl: String): List<RemoteRenderSummary> =
        withContext(Dispatchers.IO) {
            val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
            val req = Request.Builder()
                .url("$baseUrl/v1/renders")
                .get()
                .build()
            val rawBody = probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Failed listing videos (HTTP ${resp.code}).")
                }
                resp.body?.string().orEmpty()
            }
            val root = JSONObject(rawBody)
            val arr = root.optJSONArray("renders") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optNullableString("id") ?: continue
                    val rawVideo = obj.optNullableString("video_url") ?: continue
                    val rawThumb = obj.optNullableString("thumb_url").orEmpty()
                    add(
                        RemoteRenderSummary(
                            id = id,
                            filename = obj.optNullableString("filename") ?: "$id.mp4",
                            title = obj.optNullableString("title") ?: id,
                            durationMs = obj.optLong("duration_ms", 0L).coerceAtLeast(0L),
                            sizeBytes = obj.optLong("size_bytes", 0L).coerceAtLeast(0L),
                            updatedAtUtc = obj.optNullableString("updated_at_utc").orEmpty(),
                            videoUrl = resolveAbsoluteUrl(baseUrl, rawVideo),
                            thumbUrl = if (rawThumb.isNotBlank()) resolveAbsoluteUrl(baseUrl, rawThumb) else ""
                        )
                    )
                }
            }
        }

    override suspend fun enqueueMacRigRender(
        rigBaseUrl: String,
        project: ScriptProject,
        photoSha: String?,
        resolution: String,
        ratio: String,
        maxDurationSec: Int
    ): EnqueuedRenderResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
        val spokenText = project.activeBeats.joinToString(" ") { it.spokenText.trim() }.trim()

        if (project.voiceSource == VoiceSource.ENROLLED_VOICE_CLONE && spokenText.isBlank()) {
            throw IllegalStateException("Write at least one segment before generating a clip.")
        }

        val audioWavBase64: String? = if (project.voiceSource == VoiceSource.MIC_VOICEOVER) {
            val wavSha = project.masterAudioSha
                ?: throw IllegalStateException("Record your voice or switch to text before generating.")
            val wavFile = casStore.fileForSha(wavSha, "wav")
                ?: throw IllegalStateException("Recorded audio take is missing from local storage.")
            Base64.getEncoder().encodeToString(wavFile.readBytes())
        } else {
            null
        }

        val photoJpegBase64: String? = photoSha
            ?.takeIf { it.isNotBlank() }
            ?.let { sha -> casStore.fileForSha(sha, "jpg") }
            ?.let { file -> Base64.getEncoder().encodeToString(file.readBytes()) }

        val headline = project.stageConfig.chyronHeadline.ifBlank { project.title }.trim()
        val subline = project.stageConfig.chyronSubline.trim()
        val showBug = project.stageConfig.showBrandBug

        val clampedMaxDuration = maxDurationSec.coerceIn(2, 60)
        val canonicalInput = buildString {
            append(baseUrl).append('|')
            append(project.id).append('|')
            append(headline).append('|')
            append(subline).append('|')
            append(spokenText).append('|')
            append(project.voiceSource.name).append('|')
            append(photoSha.orEmpty()).append('|')
            append(project.masterAudioSha.orEmpty()).append('|')
            append(resolution).append('|')
            append(ratio).append('|')
            append(clampedMaxDuration)
        }
        val inputHash = CasMediaStore.computeSha256Hex(canonicalInput.toByteArray(Charsets.UTF_8))

        val payload = JSONObject()
            .put("script_id", project.id)
            .put("title", headline.ifBlank { project.title.ifBlank { "Breaking News" } })
            .put("spoken_text", spokenText)
            .put("voice_source", project.voiceSource.name)
            .put("resolution", resolution)
            .put("ratio", ratio)
            .put("max_duration", clampedMaxDuration)
            .put("chyron_headline", headline)
            .put("chyron_subline", subline)
            .put("show_brand_bug", showBug)
        if (!audioWavBase64.isNullOrBlank()) {
            payload.put("audio_wav_base64", audioWavBase64)
        }
        if (!photoJpegBase64.isNullOrBlank()) {
            payload.put("photo_jpeg_base64", photoJpegBase64)
        }

        val req = Request.Builder()
            .url("$baseUrl/v1/render-video")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val rawBody = okHttpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = extractErrorDetail(bodyStr)
                throw IllegalStateException(
                    "Render request failed (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                )
            }
            if (bodyStr.isBlank()) {
                throw IllegalStateException("Server /v1/render-video returned an empty response.")
            }
            bodyStr
        }

        val json = JSONObject(rawBody)
        val jobId = json.optNullableString("job_id")
            ?: throw IllegalStateException("Server /v1/render-video did not return a job_id.")
        EnqueuedRenderResult(jobId = jobId, clipId = jobId, inputHash = inputHash)
    }

    override suspend fun pollMacRigJob(rigBaseUrl: String, jobId: String): MacRigJobSnapshot =
        withContext(Dispatchers.IO) {
            val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
            val cleanJobId = jobId.trim()
            require(cleanJobId.isNotEmpty()) { "jobId must not be blank." }

            val req = Request.Builder()
                .url("$baseUrl/v1/jobs/$cleanJobId")
                .get()
                .build()

            val rawBody = okHttpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = extractErrorDetail(bodyStr)
                    throw IllegalStateException(
                        "Job status check failed (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                    )
                }
                if (bodyStr.isBlank()) {
                    throw IllegalStateException("Job status returned an empty response.")
                }
                bodyStr
            }

            val json = JSONObject(rawBody)
            val rawVideoUrl = json.optNullableString("video_url")
            val resolvedVideoUrl = rawVideoUrl?.let { resolveAbsoluteUrl(baseUrl, it) }
            val metricsObj = if (!json.isNull("metrics")) json.optJSONObject("metrics") else null

            MacRigJobSnapshot(
                jobId = json.optNullableString("job_id") ?: cleanJobId,
                status = RenderJobStatus.fromRaw(json.optNullableString("status")),
                progressFraction = json.optDouble("progress_fraction", 0.0).toFloat().coerceIn(0f, 1f),
                stageDetail = json.optNullableString("stage_detail").orEmpty(),
                errorMessage = json.optNullableString("error_message"),
                videoUrl = resolvedVideoUrl,
                durationMs = json.optLong("duration_ms", 0L).coerceAtLeast(0L),
                cer = metricsObj?.optNullableDouble("cer"),
                wespeakerCosine = metricsObj?.optNullableDouble("wespeaker_cosine"),
                dnsmosP808 = metricsObj?.optNullableDouble("dnsmos_p808")
            )
        }

    override suspend fun downloadRenderedMp4ToCas(videoUrl: String): CasStoredAsset =
        withContext(Dispatchers.IO) {
            val cleanUrl = videoUrl.trim()
            require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
                "Invalid video URL: $videoUrl"
            }
            val tmpFile = casStore.createTempScratchFile("mac_render_", "mp4")
            try {
                val req = Request.Builder().url(cleanUrl).get().build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val detail = extractErrorDetail(resp.body?.string().orEmpty())
                        throw IllegalStateException(
                            "Failed downloading video (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                        )
                    }
                    val bodyStream = resp.body?.byteStream()
                        ?: throw IllegalStateException("Empty MP4 response.")
                    FileOutputStream(tmpFile).use { out ->
                        bodyStream.copyTo(out, bufferSize = 64 * 1024)
                    }
                }
                if (!tmpFile.exists() || tmpFile.length() == 0L) {
                    throw IllegalStateException("Downloaded MP4 is empty.")
                }
                casStore.putFile(tmpFile, "mp4")
            } finally {
                if (tmpFile.exists()) tmpFile.delete()
            }
        }

    private fun resolveAbsoluteUrl(baseUrl: String, path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> "$baseUrl$path"
        else -> "$baseUrl/$path"
    }

    private fun normalizeMacRigBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "Enter a valid server URL starting with http:// or https:// (e.g. $DEFAULT_MAC_RIG_LAN_URL)."
        }
        return trimmed
    }

    private fun extractErrorDetail(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        return runCatching {
            val obj = JSONObject(rawBody)
            obj.optNullableString("detail")
                ?: obj.optNullableString("error_message")
                ?: obj.optNullableString("error")
                ?: rawBody.take(200)
        }.getOrElse { rawBody.take(200) }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else runCatching { getDouble(key) }.getOrNull()
}
