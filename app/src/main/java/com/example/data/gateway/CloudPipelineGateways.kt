package com.example.data.gateway

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.R
import com.example.data.AlignedWord
import com.example.data.ClipRecord
import com.example.data.PersonaProfile
import com.example.data.PoseGateResult
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.Stage4Mode
import com.example.data.StageProviderSettings
import com.example.data.VoiceSource
import com.example.data.local.CasMediaStore
import com.example.data.local.CasStoredAsset
import com.example.data.remote.AircheckAiRepository
import com.example.data.remote.FactCheckResult
import com.example.data.remote.HonedScriptResult
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import com.google.firebase.functions.functions
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

class CloudScriptGateway(
    private val aiRepo: AircheckAiRepository
) : ScriptGateway {
    override suspend fun honeSparkToScript(
        spark: String,
        targetSeconds: Int,
        onStreamChunk: (String) -> Unit
    ): HonedScriptResult = aiRepo.honeSparkToScript(spark, targetSeconds, onStreamChunk)

    override suspend fun streamExpandBeat(
        projectContext: String,
        beatText: String,
        onStreamChunk: (String) -> Unit
    ): String = aiRepo.streamExpandBeat(projectContext, beatText, onStreamChunk)

    override suspend fun proposeShorten20Percent(beats: List<ScriptBeat>): Map<String, String> =
        aiRepo.proposeShorten20Percent(beats)

    override suspend fun factCheckBeat(topic: String, beatText: String): FactCheckResult =
        aiRepo.factCheckBeat(topic, beatText)

    override suspend fun generateBRollPlate(bRollPrompt: String, topicContext: String): Bitmap =
        aiRepo.generateBRollPlate(bRollPrompt, topicContext)
}

class CloudIngestGateway(
    private val okHttpClient: OkHttpClient
) : IngestGateway {
    override suspend fun ingestUrlToScript(
        sourceUrl: String,
        targetSeconds: Int,
        providers: StageProviderSettings
    ): HonedScriptResult = withContext(Dispatchers.IO) {
        val cleanUrl = sourceUrl.trim()
        require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            "Enter a valid http(s) URL to ingest."
        }

        if (providers.lanGatewayUrl.isNotBlank() && providers.useLanHead) {
            // Explicit LAN LibreClip ingest route (fails loudly if unreachable; zero silent fallback)
            val endpoint = providers.lanGatewayUrl.trimEnd('/') + "/v1/stage0/ingest"
            val body = JSONObject()
                .put("sourceUrl", cleanUrl)
                .put("targetSeconds", targetSeconds)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(endpoint).post(body).build()
            val raw = okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("LAN LibreClip ingest failed (HTTP ${resp.code}).")
                }
                resp.body?.string() ?: throw IllegalStateException("Empty LAN ingest response.")
            }
            return@withContext parseHonedScriptJson(JSONObject(raw), cleanUrl)
        }

        val user = Firebase.auth.currentUser
            ?: throw IllegalStateException("Sign in with Google to run Cloud Stage 0 URL ingest.")
        val payload = hashMapOf(
            "uid" to user.uid,
            "sourceUrl" to cleanUrl,
            "targetSeconds" to targetSeconds
        )
        val result = Firebase.functions
            .getHttpsCallable("stage0Ingest")
            .call(payload)
            .await()
        @Suppress("UNCHECKED_CAST")
        val map = result.getData() as? Map<String, Any?>
            ?: throw IllegalStateException("stage0Ingest returned an unexpected response.")
        parseHonedScriptJson(JSONObject(map), cleanUrl)
    }

    private fun parseHonedScriptJson(root: JSONObject, fallbackTitle: String): HonedScriptResult {
        val beatsArr = root.optJSONArray("beats") ?: JSONArray()
        val beats = mutableListOf<ScriptBeat>()
        for (i in 0 until beatsArr.length()) {
            val obj = beatsArr.getJSONObject(i)
            val spoken = obj.optString("spokenText", "").trim()
            if (spoken.isNotBlank()) {
                beats.add(
                    ScriptBeat(
                        index = beats.size,
                        spokenText = spoken,
                        durationSec = obj.optInt("durationSec", 5).coerceAtLeast(3),
                        anchorWord = obj.optString("anchorWord", "").trim(),
                        depthTextTag = obj.optString("depthTextTag", "").trim(),
                        evidenceSource = obj.optString("evidenceSource", "").trim(),
                        bRollPrompt = obj.optString("bRollPrompt", "").trim()
                    )
                )
            }
        }
        if (beats.isEmpty()) {
            throw IllegalStateException("Stage 0 ingest returned no spoken beats.")
        }
        return HonedScriptResult(
            title = root.optString("title", fallbackTitle.take(60)).trim(),
            chyronHeadline = root.optString("chyronHeadline", fallbackTitle.take(40)).trim(),
            chyronSubline = root.optString("chyronSubline", "Aircheck Desk").trim(),
            beats = beats
        )
    }
}

class CloudPoseGateGateway(
    private val casStore: CasMediaStore,
    private val okHttpClient: OkHttpClient
) : PoseGateGateway {

    override suspend fun validatePhotoPose(
        photoSha: String,
        headModelId: String
    ): PoseGateResult = withContext(Dispatchers.IO) {
        val uid = Firebase.auth.currentUser?.uid
            ?: throw IllegalStateException("Sign in with Google to run Cloud Pose Gate.")
        val localFile = casStore.fileForSha(photoSha, "jpg")
            ?: throw IllegalStateException("Selected photo is missing from local CAS.")

        val gcsPath = uploadCasFileIfAbsent(uid, photoSha, "jpg", localFile)
        val payload = hashMapOf(
            "photoSha" to photoSha,
            "photoGcsPath" to gcsPath,
            "headModelId" to headModelId
        )
        val result = Firebase.functions
            .getHttpsCallable("validatePhotoPose")
            .call(payload)
            .await()

        @Suppress("UNCHECKED_CAST")
        val data = result.getData() as? Map<String, Any?>
            ?: throw IllegalStateException("Cloud Pose Gate returned empty metadata.")

        PoseGateResult(
            checked = true,
            valid = (data["valid"] as? Boolean) ?: false,
            pitchDeg = (data["pitchDeg"] as? Number)?.toFloat() ?: 0f,
            yawDeg = (data["yawDeg"] as? Number)?.toFloat() ?: 0f,
            mouthOccluded = (data["mouthOccluded"] as? Boolean) ?: false,
            canNormalize = (data["canNormalize"] as? Boolean) ?: headModelId.isNotBlank(),
            note = (data["note"] as? String).orEmpty()
        )
    }

    override suspend fun normalizePhotoPose(
        photoSha: String,
        headModelId: String,
        backdropPrompt: String
    ): String = withContext(Dispatchers.IO) {
        val uid = Firebase.auth.currentUser?.uid
            ?: throw IllegalStateException("Sign in with Google to normalize portrait pose.")
        if (headModelId.isBlank()) {
            throw IllegalStateException("No enrolled head_model_id found. Complete Phase A Persona Training first.")
        }
        val localFile = casStore.fileForSha(photoSha, "jpg")
            ?: throw IllegalStateException("Source photo is missing from local CAS.")

        val gcsPath = uploadCasFileIfAbsent(uid, photoSha, "jpg", localFile)
        val payload = hashMapOf(
            "photoSha" to photoSha,
            "photoGcsPath" to gcsPath,
            "headModelId" to headModelId,
            "backdropPrompt" to backdropPrompt
        )
        val result = Firebase.functions
            .getHttpsCallable("normalizePhotoPose")
            .call(payload)
            .await()

        @Suppress("UNCHECKED_CAST")
        val data = result.getData() as? Map<String, Any?>
            ?: throw IllegalStateException("normalizePhotoPose returned empty data.")
        val downloadUrl = (data["normalizedUrl"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("normalizePhotoPose did not return a normalized image URL.")

        val bytes = okHttpClient.newCall(Request.Builder().url(downloadUrl).get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Failed to fetch normalized portrait (HTTP ${resp.code}).")
                }
                resp.body?.bytes() ?: throw IllegalStateException("Empty normalized portrait body.")
            }
        casStore.putBytes(bytes, "jpg").sha256
    }
}

data class MacRigHealthResult(
    val status: String,
    val loraVersion: Int,
    val bundleVersion: String?,
    val pendingPreferences: Int,
    val renderBridgeAvailable: Boolean,
    val activeRenderJobs: Int
)

data class MacRigJobSnapshot(
    val jobId: String,
    val status: RenderJobStatus,
    val progressFraction: Float,
    val stageDetail: String,
    val errorMessage: String?,
    val videoUrl: String?,
    val durationMs: Long,
    val cer: Double? = null,
    val wespeakerCosine: Double? = null,
    val dnsmosP808: Double? = null
)

class CloudRenderJobGateway(
    private val context: Context,
    private val casStore: CasMediaStore,
    private val okHttpClient: OkHttpClient = OkHttpClient()
) : RenderJobGateway {

    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    }

    /**
     * Real HTTP GET /health probe against the user's Mac Studio rig (`ota_server.py`).
     * Never fabricates online state on HTTP or socket errors.
     */
    suspend fun checkMacRigHealth(rigBaseUrl: String): MacRigHealthResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
        val req = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .build()
        val rawBody = okHttpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = extractErrorDetail(bodyStr)
                throw IllegalStateException(
                    "Mac Rig /health failed (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                )
            }
            if (bodyStr.isBlank()) {
                throw IllegalStateException("Mac Rig /health returned an empty response.")
            }
            bodyStr
        }
        val json = JSONObject(rawBody)
        MacRigHealthResult(
            status = json.optNullableString("status") ?: "unknown",
            loraVersion = json.optInt("lora_version", 0),
            bundleVersion = json.optNullableString("bundle_version"),
            pendingPreferences = json.optInt("pending_preferences", 0),
            renderBridgeAvailable = json.optBoolean("render_bridge", false),
            activeRenderJobs = json.optInt("active_render_jobs", 0)
        )
    }

    /**
     * Direct Mac Studio Render Bridge (`POST {rigBaseUrl}/v1/render-video`).
     * Works 100% offline from Firebase / without `google-services.json`.
     */
    suspend fun enqueueMacRigRender(
        rigBaseUrl: String,
        project: ScriptProject,
        photoSha: String?,
        resolution: String = "720P",
        ratio: String = "9:16",
        maxDurationSec: Int = 10
    ): EnqueuedRenderResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
        val spokenText = project.activeBeats.joinToString(" ") { it.spokenText.trim() }.trim()

        if (project.voiceSource == VoiceSource.ENROLLED_VOICE_CLONE && spokenText.isBlank()) {
            throw IllegalStateException("Write at least one spoken beat before rendering.")
        }

        val audioWavBase64: String? = if (project.voiceSource == VoiceSource.MIC_VOICEOVER) {
            val wavSha = project.masterAudioSha
                ?: throw IllegalStateException("Record a mic voiceover take before rendering in Mic Voiceover mode.")
            val wavFile = casStore.fileForSha(wavSha, "wav")
                ?: throw IllegalStateException("Recorded WAV take is missing from local CAS.")
            Base64.getEncoder().encodeToString(wavFile.readBytes())
        } else {
            null
        }

        val photoJpegBase64: String? = photoSha
            ?.takeIf { it.isNotBlank() }
            ?.let { sha -> casStore.fileForSha(sha, "jpg") }
            ?.let { file -> Base64.getEncoder().encodeToString(file.readBytes()) }

        val clampedMaxDuration = maxDurationSec.coerceIn(2, 60)
        val canonicalInput = buildString {
            append(baseUrl).append('|')
            append(project.id).append('|')
            append(project.title).append('|')
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
            .put("title", project.title.ifBlank { "Untitled Aircheck" })
            .put("spoken_text", spokenText)
            .put("voice_source", project.voiceSource.name)
            .put("resolution", resolution)
            .put("ratio", ratio)
            .put("max_duration", clampedMaxDuration)
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
                    "Mac Rig render rejected (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                )
            }
            if (bodyStr.isBlank()) {
                throw IllegalStateException("Mac Rig /v1/render-video returned an empty response.")
            }
            bodyStr
        }

        val json = JSONObject(rawBody)
        val jobId = json.optNullableString("job_id")
            ?: throw IllegalStateException("Mac Rig /v1/render-video did not return a job_id.")
        EnqueuedRenderResult(jobId = jobId, clipId = jobId, inputHash = inputHash)
    }

    /**
     * Polls real persisted render job status from `GET {rigBaseUrl}/v1/jobs/{jobId}`.
     */
    suspend fun pollMacRigJob(rigBaseUrl: String, jobId: String): MacRigJobSnapshot =
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
                        "Mac Rig job poll failed (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                    )
                }
                if (bodyStr.isBlank()) {
                    throw IllegalStateException("Mac Rig job poll returned an empty response.")
                }
                bodyStr
            }

            val json = JSONObject(rawBody)
            val rawVideoUrl = json.optNullableString("video_url")
            val resolvedVideoUrl = when {
                rawVideoUrl == null -> null
                rawVideoUrl.startsWith("http://") || rawVideoUrl.startsWith("https://") -> rawVideoUrl
                rawVideoUrl.startsWith("/") -> "$baseUrl$rawVideoUrl"
                else -> "$baseUrl/$rawVideoUrl"
            }
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

    /**
     * Streams finished MP4 from `GET {videoUrl}` to disk and stores it immutably in `CasMediaStore`.
     */
    suspend fun downloadRenderedMp4ToCas(videoUrl: String): CasStoredAsset =
        withContext(Dispatchers.IO) {
            val cleanUrl = videoUrl.trim()
            require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
                "Invalid Mac Rig render video URL: $videoUrl"
            }
            val tmpFile = casStore.createTempScratchFile("mac_render_", "mp4")
            try {
                val req = Request.Builder().url(cleanUrl).get().build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val detail = extractErrorDetail(resp.body?.string().orEmpty())
                        throw IllegalStateException(
                            "Failed to download Mac Rig MP4 (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                        )
                    }
                    val bodyStream = resp.body?.byteStream()
                        ?: throw IllegalStateException("Mac Rig returned an empty MP4 body.")
                    FileOutputStream(tmpFile).use { out ->
                        bodyStream.copyTo(out, bufferSize = 64 * 1024)
                    }
                }
                if (!tmpFile.exists() || tmpFile.length() == 0L) {
                    throw IllegalStateException("Downloaded Mac Rig MP4 is empty.")
                }
                casStore.putFile(tmpFile, "mp4")
            } finally {
                if (tmpFile.exists()) tmpFile.delete()
            }
        }

    private fun normalizeMacRigBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "Enter a valid Mac Rig URL starting with http:// or https:// (e.g. http://10.0.0.242:8443)."
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

    override suspend fun enqueueClipRender(
        project: ScriptProject,
        persona: PersonaProfile,
        providers: StageProviderSettings,
        photoSha: String
    ): EnqueuedRenderResult = withContext(Dispatchers.IO) {
        val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
            ?: throw IllegalStateException("Sign in with Google to enqueue a cloud render.")
        if (project.activeBeats.isEmpty()) {
            throw IllegalStateException("Add at least one spoken beat before rendering.")
        }

        // Strict zero-silent-fallback preflight checks: fail loudly if persona enrollment is incomplete.
        val resolvedVoiceId = project.voiceIdOverride.ifBlank { persona.voiceId }
        if (project.voiceSource == VoiceSource.ENROLLED_VOICE_CLONE && resolvedVoiceId.isBlank()) {
            throw IllegalStateException(
                "No enrolled voice_id found. Complete Phase A Persona Training or switch to Mic Voiceover."
            )
        }
        if (project.stageConfig.stage4Mode == Stage4Mode.PHOTO_HEAD_LORA && persona.headModelId.isBlank()) {
            throw IllegalStateException(
                "No enrolled head_model_id found. Complete Phase A Persona Training before Photo + Head LoRA rendering."
            )
        }

        val photoFile = casStore.fileForSha(photoSha, "jpg")
            ?: throw IllegalStateException("Selected photo is missing from local CAS.")
        val photoGcsPath = uploadCasFileIfAbsent(uid, photoSha, "jpg", photoFile)

        var micVoiceGcsPath: String? = null
        if (project.voiceSource == VoiceSource.MIC_VOICEOVER) {
            val wavSha = project.masterAudioSha
                ?: throw IllegalStateException("Record a mic voiceover take before rendering in Mic Voiceover mode.")
            val wavFile = casStore.fileForSha(wavSha, "wav")
                ?: throw IllegalStateException("Recorded WAV take is missing from local CAS.")
            micVoiceGcsPath = uploadCasFileIfAbsent(uid, wavSha, "wav", wavFile)
        }

        // Hash full pipeline inputs + provider IDs so SHA-256 stage checkpointing is deterministic
        val canonicalInput = buildString {
            append(project.id).append('|')
            append(photoSha).append('|')
            append(persona.headModelId).append('|')
            append(resolvedVoiceId).append('|')
            append(project.voiceSource.name).append('|')
            append(project.stageConfig.stage4Mode.id).append('|')
            append(providers.voiceProviderId).append('|')
            append(providers.alignProviderId).append('|')
            append(providers.headProviderId).append('|')
            append(providers.packageProviderId).append('|')
            append(project.stageConfig.chyronHeadline).append('|')
            append(project.stageConfig.chyronSubline).append('|')
            project.activeBeats.forEach {
                append(it.spokenText).append('|').append(it.anchorWord).append('|').append(it.evidenceSource)
            }
        }
        val inputHash = CasMediaStore.computeSha256Hex(canonicalInput.toByteArray(Charsets.UTF_8))

        val payload = hashMapOf<String, Any?>(
            "scriptId" to project.id,
            "title" to project.title.ifBlank { "Untitled Aircheck" },
            "inputHash" to inputHash,
            "photoSha" to photoSha,
            "photoGcsPath" to photoGcsPath,
            "headModelId" to persona.headModelId,
            "voiceSource" to project.voiceSource.name,
            "voiceId" to resolvedVoiceId,
            "micVoiceSha" to project.masterAudioSha,
            "micVoiceGcsPath" to micVoiceGcsPath,
            "stage4Mode" to project.stageConfig.stage4Mode.id,
            "sourceUrl" to project.sourceUrl,
            "providers" to mapOf(
                "voiceProviderId" to providers.voiceProviderId,
                "alignProviderId" to providers.alignProviderId,
                "headProviderId" to providers.headProviderId,
                "packageProviderId" to providers.packageProviderId,
                "lanGatewayUrl" to providers.lanGatewayUrl,
                "useLanVoice" to providers.useLanVoice,
                "useLanAlign" to providers.useLanAlign,
                "useLanHead" to providers.useLanHead,
                "useLanPackage" to providers.useLanPackage
            ),
            "packaging" to mapOf(
                "chyronHeadline" to project.stageConfig.chyronHeadline.ifBlank { project.title },
                "chyronSubline" to project.stageConfig.chyronSubline,
                "cornerBugText" to project.stageConfig.cornerBugText,
                "showBrandBug" to project.stageConfig.showBrandBug
            ),
            "beats" to project.activeBeats.mapIndexed { idx, beat ->
                mapOf(
                    "id" to beat.id,
                    "ordinal" to idx,
                    "spokenText" to beat.spokenText,
                    "anchorWord" to beat.anchorWord,
                    "depthTextTag" to beat.depthTextTag,
                    "evidenceSource" to beat.evidenceSource,
                    "phoneticOverride" to beat.phoneticOverride,
                    "bRollSha" to beat.bRollSha
                )
            }
        )

        val response = Firebase.functions
            .getHttpsCallable("enqueueClipRender")
            .call(payload)
            .await()

        @Suppress("UNCHECKED_CAST")
        val data = response.getData() as? Map<String, Any?>
            ?: throw IllegalStateException("enqueueClipRender returned an empty response.")
        val jobId = (data["jobId"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("enqueueClipRender did not return a jobId.")
        val clipId = (data["clipId"] as? String)?.takeIf { it.isNotBlank() } ?: jobId

        EnqueuedRenderResult(jobId = jobId, clipId = clipId, inputHash = inputHash)
    }

    override fun observeRenderJobs(): Flow<List<ClipRecord>> = flow {
        val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
        if (uid.isNullOrBlank()) {
            emit(emptyList())
            return@flow
        }
        emitAll(
            db.collection("users").document(uid).collection("jobs")
                .snapshots()
                .map { snap -> snap.documents.mapNotNull { it.toClipRecordOrNull() } }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toClipRecordOrNull(): ClipRecord? {
        val data = getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: return null
        val peaks = (data["waveformPeaks"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toFloat() }
            .orEmpty()
        val rawWords = (data["alignedWords"] as? List<Map<String, Any?>>).orEmpty()
        val words = rawWords.mapNotNull { w ->
            val text = w["word"] as? String ?: return@mapNotNull null
            val start = (w["startMs"] as? Number)?.toLong() ?: 0L
            val end = (w["endMs"] as? Number)?.toLong() ?: start
            AlignedWord(word = text, startMs = start, endMs = end)
        }
        val createdTs = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        val updatedTs = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)

        return ClipRecord(
            id = (data["clipId"] as? String)?.ifBlank { id } ?: id,
            scriptId = (data["scriptId"] as? String).orEmpty(),
            jobId = id,
            title = (data["title"] as? String).orEmpty(),
            photoSha = data["photoSha"] as? String,
            voiceSha = data["voiceSha"] as? String,
            alignSha = data["alignSha"] as? String,
            lipsyncSha = data["lipsyncSha"] as? String,
            finalSha = data["finalSha"] as? String,
            status = RenderJobStatus.fromRaw(data["status"] as? String),
            progressFraction = (data["progressFraction"] as? Number)?.toFloat() ?: 0f,
            stageDetail = (data["stageDetail"] as? String).orEmpty(),
            errorMessage = data["errorMessage"] as? String,
            durationMs = (data["durationMs"] as? Number)?.toLong() ?: 0L,
            waveformPeaks = peaks,
            thumbHash = data["thumbHash"] as? String,
            alignedWords = words,
            chyronHeadline = (data["chyronHeadline"] as? String).orEmpty(),
            chyronSubline = (data["chyronSubline"] as? String).orEmpty(),
            remoteLipsyncUrl = data["remoteLipsyncUrl"] as? String,
            remoteFinalUrl = data["remoteFinalUrl"] as? String,
            exportedUri = null,
            createdAtMillis = createdTs?.toDate()?.time ?: System.currentTimeMillis(),
            updatedAtMillis = updatedTs?.toDate()?.time ?: System.currentTimeMillis()
        )
    }
}

class CloudPersonaTrainingGateway(
    private val context: Context
) : PersonaTrainingGateway {
    private val db: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    }

    override suspend fun startPersonaTraining(
        trainingVideoGcsUris: List<String>,
        voiceProviderId: String
    ): String = withContext(Dispatchers.IO) {
        if (Firebase.auth.currentUser == null) {
            throw IllegalStateException("Sign in with Google to start Phase A Persona Training.")
        }
        require(trainingVideoGcsUris.isNotEmpty()) {
            "Upload reference talking videos to Cloud Storage before triggering Persona Training."
        }
        val payload = hashMapOf(
            "trainingVideoGcsUris" to trainingVideoGcsUris,
            "voiceProviderId" to voiceProviderId
        )
        val res = Firebase.functions
            .getHttpsCallable("startPersonaTraining")
            .call(payload)
            .await()
        @Suppress("UNCHECKED_CAST")
        val data = res.getData() as? Map<String, Any?>
            ?: throw IllegalStateException("startPersonaTraining returned an empty response.")
        (data["trainingJobId"] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("startPersonaTraining did not return a trainingJobId.")
    }

    override fun observePersonaProfile(): Flow<PersonaProfile?> = flow {
        val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
        if (uid.isNullOrBlank()) {
            emit(null)
            return@flow
        }
        emitAll(
            db.collection("users").document(uid)
                .collection("profile").document("persona")
                .snapshots()
                .map { snap ->
                    val data = snap.data ?: return@map null
                    PersonaProfile(
                        headModelId = (data["headModelId"] as? String).orEmpty(),
                        voiceId = (data["voiceId"] as? String).orEmpty(),
                        defaultPhotoSha = (data["defaultPhotoSha"] as? String).orEmpty(),
                        trainingJobId = (data["trainingJobId"] as? String).orEmpty(),
                        trainingStatus = (data["trainingStatus"] as? String).orEmpty(),
                        trainingError = (data["trainingError"] as? String).orEmpty()
                    )
                }
        )
    }
}

/**
 * Deduplicated Cloud Storage CAS upload: checks `metadata` first and skips upload if `{sha256}` already exists!
 */
private suspend fun uploadCasFileIfAbsent(
    uid: String,
    sha256: String,
    ext: String,
    file: File
): String {
    val gcsPath = "users/$uid/cas/${sha256.take(2)}/$sha256.$ext"
    val ref = Firebase.storage.reference.child(gcsPath)
    val alreadyExists = try {
        ref.metadata.await()
        true
    } catch (e: StorageException) {
        if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) false else throw e
    }
    if (!alreadyExists) {
        ref.putFile(Uri.fromFile(file)).await()
    }
    return gcsPath
}
