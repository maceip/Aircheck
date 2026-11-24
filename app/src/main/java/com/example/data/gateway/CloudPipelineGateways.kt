package com.example.data.gateway

import com.example.data.AlignedWord
import com.example.data.CoreRenderOptions
import com.example.data.CoreStoryClause
import com.example.data.NewsroomLook
import com.example.data.RigQualityMetrics
import com.example.data.SilenceGap
import com.example.data.StudioCutStats
import com.example.data.OverlayObjectKind
import com.example.data.RenderJobStatus
import com.example.data.SavedNewsroomOverlay
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
            activeRenderJobs = json.optInt("active_render_jobs", 0),
            remotionWorkerPresent = json.optBoolean("remotion_worker_present", false),
            remotionEnabled = json.optBoolean("remotion_enabled", false),
            remotionRendersCompleted = json.optInt("remotion_renders_completed", 0),
            remotionVersion = json.optNullableString("remotion_version")
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
        maxDurationSec: Int,
        words: List<AlignedWord>,
        alignUrl: String?,
        useRemotion: Boolean?
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

        val headline = project.stageConfig.chyronHeadline.trim()
        val subline = project.stageConfig.chyronSubline.trim()
        val showBug = project.stageConfig.showBrandBug

        val clampedMaxDuration = maxDurationSec.coerceIn(2, 75)
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
        if (!project.masterAudioSha.isNullOrBlank()) {
            payload.put("master_audio_sha", project.masterAudioSha)
        }
        if (!photoJpegBase64.isNullOrBlank()) {
            payload.put("photo_jpeg_base64", photoJpegBase64)
        }
        if (words.isNotEmpty()) {
            payload.put("words", encodeAlignedWordsArray(words))
        }
        if (!alignUrl.isNullOrBlank()) {
            payload.put("align_url", alignUrl.trim())
        }
        if (useRemotion != null) {
            payload.put("use_remotion", useRemotion)
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
                if (resp.code == 404) {
                    val detail = extractErrorDetail(bodyStr)
                    throw RigJobNotFoundException(
                        "Rig job $cleanJobId not found (HTTP 404)${if (detail.isNotBlank()) ": $detail" else "."}"
                    )
                }
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
            val rawAlignUrl = json.optNullableString("align_url")
            val resolvedAlignUrl = rawAlignUrl?.let { resolveAbsoluteUrl(baseUrl, it) }
            val metricsObj = if (!json.isNull("metrics")) json.optJSONObject("metrics") else null
            val parsedMetrics = parseRigQualityMetrics(metricsObj)
            val parsedWords = parseAlignedWords(json.optJSONArray("words"))

            MacRigJobSnapshot(
                jobId = json.optNullableString("job_id") ?: cleanJobId,
                status = RenderJobStatus.fromRaw(json.optNullableString("status")),
                progressFraction = json.optDouble("progress_fraction", 0.0).toFloat().coerceIn(0f, 1f),
                stageDetail = json.optNullableString("stage_detail").orEmpty(),
                errorMessage = json.optNullableString("error_message"),
                videoUrl = resolvedVideoUrl,
                durationMs = json.optLong("duration_ms", 0L).coerceAtLeast(0L),
                cer = parsedMetrics?.cer,
                wespeakerCosine = parsedMetrics?.wespeakerCosine,
                dnsmosP808 = parsedMetrics?.dnsmosP808,
                errorKind = json.optNullableString("error_kind"),
                words = parsedWords,
                alignUrl = resolvedAlignUrl,
                metrics = parsedMetrics
            )
        }

    override suspend fun downloadRenderedMp4ToCas(videoUrl: String): CasStoredAsset =
        downloadRemoteAssetToCas(videoUrl, "mp4")

    override suspend fun downloadAudioWavToCas(audioUrl: String): CasStoredAsset =
        downloadRemoteAssetToCas(audioUrl, "wav")

    override suspend fun fetchAlignedWords(alignUrl: String): List<AlignedWord> = withContext(Dispatchers.IO) {
        val cleanUrl = alignUrl.trim()
        require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            "Invalid align_url: $alignUrl"
        }
        val req = Request.Builder().url(cleanUrl).get().build()
        val rawBody = okHttpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = extractErrorDetail(bodyStr)
                throw IllegalStateException(
                    "Failed fetching alignment JSON (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                )
            }
            bodyStr.trim()
        }
        if (rawBody.isEmpty()) return@withContext emptyList()
        if (rawBody.startsWith("[")) {
            parseAlignedWords(JSONArray(rawBody))
        } else {
            val obj = JSONObject(rawBody)
            parseAlignedWords(obj.optJSONArray("words"))
        }
    }

    private suspend fun downloadRemoteAssetToCas(
        assetUrl: String,
        ext: String
    ): CasStoredAsset = withContext(Dispatchers.IO) {
        val cleanUrl = assetUrl.trim()
        require(cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
            "Invalid asset URL: $assetUrl"
        }
        val tmpFile = casStore.createTempScratchFile("mac_asset_", ext)
        try {
            val req = Request.Builder().url(cleanUrl).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val detail = extractErrorDetail(resp.body?.string().orEmpty())
                    throw IllegalStateException(
                        "Failed downloading .$ext asset (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                    )
                }
                val bodyStream = resp.body?.byteStream()
                    ?: throw IllegalStateException("Empty .$ext response.")
                FileOutputStream(tmpFile).use { out ->
                    bodyStream.copyTo(out, bufferSize = 64 * 1024)
                }
            }
            if (!tmpFile.exists() || tmpFile.length() == 0L) {
                throw IllegalStateException("Downloaded .$ext asset is empty.")
            }
            casStore.putFile(tmpFile, ext)
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    override suspend fun synthesizeCoreStoryTts(
        rigBaseUrl: String,
        projectId: String,
        clauses: List<CoreStoryClause>,
        task: RigTaskHandle?
    ): CoreTtsSynthesisResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
        val nonEmpty = clauses.filter { it.spokenText.isNotBlank() }
        require(nonEmpty.isNotEmpty()) { "Write at least one story clause before running TTS." }

        val clausesArr = JSONArray()
        nonEmpty.forEach { c ->
            clausesArr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("spoken_text", c.spokenText.trim())
            )
        }
        val payload = JSONObject()
            .put("project_id", projectId)
            .put("spoken_text", nonEmpty.joinToString(" ") { it.spokenText.trim() })
            .put("clauses", clausesArr)

        val json = runRigTask(baseUrl, "/v1/core/tts", payload, "TTS synthesis", task)
        val ttsId = json.optNullableString("tts_id")
            ?: throw IllegalStateException("Server /v1/core/tts did not return tts_id.")
        val rawAudioUrl = json.optNullableString("audio_url")
            ?: throw IllegalStateException("Server /v1/core/tts did not return audio_url.")
        val resolvedAudioUrl = resolveAbsoluteUrl(baseUrl, rawAudioUrl)
        val durationMs = json.optLong("duration_ms", 0L).coerceAtLeast(1L)

        val peaksArr = json.optJSONArray("waveform_peaks") ?: JSONArray()
        val peaks = buildList {
            for (i in 0 until peaksArr.length()) {
                add(peaksArr.optDouble(i, 0.0).toFloat().coerceIn(0f, 1f))
            }
        }

        val serverClausesArr = json.optJSONArray("clauses") ?: JSONArray()
        val timedById = linkedMapOf<String, Pair<Long, Long>>()
        for (i in 0 until serverClausesArr.length()) {
            val obj = serverClausesArr.optJSONObject(i) ?: continue
            val cid = obj.optNullableString("id") ?: continue
            val sMs = obj.optLong("start_ms", 0L).coerceAtLeast(0L)
            val eMs = obj.optLong("end_ms", durationMs).coerceAtLeast(sMs)
            timedById[cid] = sMs to eMs
        }

        val timedClauses = nonEmpty.mapIndexed { idx, orig ->
            val bounds = timedById[orig.id]
            orig.copy(
                index = idx,
                startMs = bounds?.first ?: orig.startMs,
                endMs = bounds?.second ?: orig.endMs
            )
        }

        val rawAlignUrl = json.optNullableString("align_url")
        val resolvedAlignUrl = rawAlignUrl?.let { resolveAbsoluteUrl(baseUrl, it) }
        val metricsObj = if (!json.isNull("metrics")) json.optJSONObject("metrics") else null
        val parsedMetrics = parseRigQualityMetrics(metricsObj)
        val parsedWords = parseAlignedWords(json.optJSONArray("words"))

        CoreTtsSynthesisResult(
            ttsId = ttsId,
            audioUrl = resolvedAudioUrl,
            durationMs = durationMs,
            waveformPeaks = peaks,
            timedClauses = timedClauses,
            words = parsedWords,
            alignUrl = resolvedAlignUrl,
            metrics = parsedMetrics
        )
    }

    override suspend fun chopTalkingHeadIntoBroll(
        rigBaseUrl: String,
        scriptId: String,
        title: String,
        sourceJobId: String?,
        sourceMp4Sha: String?,
        clauses: List<CoreStoryClause>,
        overlays: List<SavedNewsroomOverlay>,
        renderOptions: CoreRenderOptions?,
        look: NewsroomLook?,
        task: RigTaskHandle?,
        alignedWords: List<AlignedWord>,
        alignUrl: String?,
        useRemotion: Boolean?
    ): ChoppedBrollResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
        val mp4Base64: String? = if (sourceJobId.isNullOrBlank() && !sourceMp4Sha.isNullOrBlank()) {
            val file = casStore.fileForSha(sourceMp4Sha, "mp4")
                ?: throw IllegalStateException("Local talking-head MP4 is missing from CAS.")
            Base64.getEncoder().encodeToString(file.readBytes())
        } else {
            null
        }

        val clausesArr = JSONArray()
        clauses.forEachIndexed { idx, c ->
            val clauseObj = JSONObject()
                .put("id", c.id)
                .put("index", if (c.index >= 0) c.index else idx)
                .put("spoken_text", c.spokenText)
                .put("start_ms", c.startMs)
                .put("end_ms", c.endMs)
                .put("segment_mode", c.segmentMode.name)
                .put("broll_caption", c.brollCaption.ifBlank { c.spokenText })
                .put("b_roll_prompt", c.bRollPrompt.ifBlank { c.brollCaption })
            if (!c.bRollSha.isNullOrBlank()) {
                clauseObj.put("b_roll_sha", c.bRollSha)
            }
            if (c.depthTextTag.isNotBlank()) {
                clauseObj.put("depth_text_tag", c.depthTextTag)
            }
            clausesArr.put(clauseObj)
        }

        val overlaysArr = JSONArray()
        overlays.forEach { ovl ->
            overlaysArr.put(
                JSONObject()
                    .put("id", ovl.id)
                    .put("kind", ovl.kind.name)
                    .put("headline", ovl.headline)
                    .put("subline", ovl.subline)
                    .put("callout_stat", ovl.calloutStat)
                    .put("duration_sec", ovl.durationSec)
            )
        }

        val payload = JSONObject()
            .put("script_id", scriptId)
            .put("title", title)
            .put("clauses", clausesArr)
            .put("overlays", overlaysArr)
        if (!sourceJobId.isNullOrBlank()) {
            payload.put("source_job_id", sourceJobId)
        }
        if (!mp4Base64.isNullOrBlank()) {
            payload.put("source_mp4_base64", mp4Base64)
        }
        if (alignedWords.isNotEmpty()) {
            payload.put("words", encodeAlignedWordsArray(alignedWords))
        }
        if (!alignUrl.isNullOrBlank()) {
            payload.put("align_url", alignUrl.trim())
        }
        val effectiveUseRemotion = useRemotion ?: renderOptions?.useRemotion
        if (renderOptions != null) {
            val opts = JSONObject()
                .put("strip_silence", renderOptions.stripSilence)
                .put("punch_in", renderOptions.punchIn)
                .put("captions", renderOptions.captions)
                .put("aspect", renderOptions.aspect)
                .put("chyron_headline", renderOptions.chyronHeadline)
                .put("chyron_subline", renderOptions.chyronSubline)
                .put("show_brand_bug", renderOptions.showBrandBug)
                .put("corner_bug_text", renderOptions.cornerBugText)
            if (effectiveUseRemotion != null) {
                opts.put("use_remotion", effectiveUseRemotion)
            }
            if (look != null) {
                val lookJson = JSONObject()
                    .put("blur_percent", look.blurPercent)
                    .put("brightness_ev", look.brightnessEv)
                    .put("light", look.light)
                    .put("grain", look.grain)
                    .put("border", look.border)
                if (!look.plateId.isNullOrBlank()) lookJson.put("plate_id", look.plateId)
                opts.put("look", lookJson)
            }
            payload.put("render_options", opts)
        } else if (effectiveUseRemotion != null) {
            payload.put("use_remotion", effectiveUseRemotion)
        }

        val json = runRigTask(baseUrl, "/v1/core/chop-broll", payload, "B-roll chop", task)
        val jobId = json.optNullableString("job_id")
            ?: throw IllegalStateException("Server /v1/core/chop-broll missing job_id.")
        val rawVideoUrl = json.optNullableString("video_url")
            ?: throw IllegalStateException("Server /v1/core/chop-broll missing video_url.")
        ChoppedBrollResult(
            jobId = jobId,
            videoUrl = resolveAbsoluteUrl(baseUrl, rawVideoUrl),
            durationMs = json.optLong("duration_ms", 0L).coerceAtLeast(0L),
            brollCutCount = json.optInt("broll_cut_count", 0),
            appliedOverlayCount = json.optInt("applied_overlay_count", 0),
            studioStats = if (renderOptions != null) {
                StudioCutStats(
                    brollCutCount = json.optInt("broll_cut_count", 0),
                    appliedOverlayCount = json.optInt("applied_overlay_count", 0),
                    captionCount = json.optInt("caption_count", 0),
                    punchInCount = json.optInt("punch_in_count", 0),
                    removedSilenceGapCount = json.optInt("removed_silence_gap_count", 0),
                    removedSilenceMs = json.optLong("removed_silence_ms", 0L),
                    aspect = json.optString("aspect", renderOptions.aspect),
                    remotionUsed = json.optBoolean("remotion_used", false),
                    renderer = json.optNullableString("renderer")
                )
            } else {
                null
            }
        )
    }

    override suspend fun detectSilenceGaps(rigBaseUrl: String, sourceJobId: String): SilenceGapsResult =
        withContext(Dispatchers.IO) {
            val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
            val json = postRigJson(
                "$baseUrl/v1/core/silence-gaps",
                JSONObject().put("source_job_id", sourceJobId),
                label = "Silence detection"
            )
            val arr = json.optJSONArray("gaps") ?: JSONArray()
            SilenceGapsResult(
                durationMs = json.optLong("duration_ms", 0L),
                gaps = (0 until arr.length()).map { i ->
                    val g = arr.getJSONObject(i)
                    SilenceGap(startMs = g.getLong("start_ms"), endMs = g.getLong("end_ms"))
                },
                removableMs = json.optLong("removable_ms", 0L)
            )
        }

    override suspend fun generateBackdropPlate(
        rigBaseUrl: String,
        prompt: String,
        aspect: String,
        task: RigTaskHandle?
    ): BackdropPlateResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
        val json = runRigTask(
            baseUrl,
            "/v1/newsroom/backdrop",
            JSONObject().put("prompt", prompt).put("aspect", aspect),
            "Backdrop generation",
            task
        )
        val plateId = json.optNullableString("plate_id")
            ?: throw IllegalStateException("Server /v1/newsroom/backdrop missing plate_id.")
        val url = json.optNullableString("url")
            ?: throw IllegalStateException("Server /v1/newsroom/backdrop missing url.")
        BackdropPlateResult(
            plateId = plateId,
            url = resolveAbsoluteUrl(baseUrl, url),
            prompt = json.optString("prompt", prompt)
        )
    }

    /**
     * Submits a long rig stage as a durable async task (`?async=1`) and polls `GET /v1/jobs/<id>`
     * until it finishes. With [task] resuming an id, polling continues on the existing rig task;
     * a task lost to a rig restart (or unknown to the rig) is resubmitted once.
     */
    private suspend fun runRigTask(
        baseUrl: String,
        path: String,
        payload: JSONObject,
        label: String,
        task: RigTaskHandle?
    ): JSONObject {
        var taskId = task?.resumeTaskId?.takeIf { it.isNotBlank() }
        var resubmitted = false
        while (true) {
            if (taskId == null) {
                val accepted = postRigJson("$baseUrl$path?async=1", payload, label, readTimeoutSec = 60L)
                taskId = accepted.optNullableString("job_id")
                    ?: throw IllegalStateException("$label: rig did not return a task id.")
                task?.onSubmitted?.invoke(taskId)
            }
            var errors = 0
            while (true) {
                val poll = runCatching { getRigJob(baseUrl, taskId!!) }
                if (poll.isFailure) {
                    val err = poll.exceptionOrNull()
                    if (err is kotlinx.coroutines.CancellationException) throw err
                    if (err is RigTaskNotFound) {
                        if (!resubmitted) break
                        throw err
                    }
                    if (++errors >= 30) throw IllegalStateException("$label: lost contact with rig (${err?.localizedMessage}).")
                    kotlinx.coroutines.delay(RIG_TASK_POLL_MS)
                    continue
                }
                errors = 0
                val job = poll.getOrThrow()
                when (job.optString("status")) {
                    "READY" -> return job.optJSONObject("result")
                        ?: throw IllegalStateException("$label: rig task finished without a result.")
                    "FAILED" -> {
                        if (job.optString("error_kind") == "rig_restarted" && !resubmitted) break
                        throw IllegalStateException("$label failed: ${job.optNullableString("error_message") ?: "unknown rig error"}")
                    }
                }
                task?.onProgress?.invoke(job.optNullableString("stage_detail") ?: "$label running")
                kotlinx.coroutines.delay(RIG_TASK_POLL_MS)
            }
            // Lost on the rig: resubmit exactly once.
            resubmitted = true
            taskId = null
        }
    }

    private class RigTaskNotFound(msg: String) : IllegalStateException(msg)

    private fun getRigJob(baseUrl: String, taskId: String): JSONObject {
        val req = Request.Builder().url("$baseUrl/v1/jobs/$taskId").get().build()
        return okHttpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (resp.code == 404) throw RigTaskNotFound("Rig task $taskId not found.")
            if (!resp.isSuccessful) throw IllegalStateException("Task status HTTP ${resp.code}: ${extractErrorDetail(bodyStr)}")
            JSONObject(bodyStr)
        }
    }

    private fun postRigJson(
        url: String,
        body: JSONObject,
        label: String,
        readTimeoutSec: Long = 180L
    ): JSONObject {
        val client = okHttpClient.newBuilder().readTimeout(readTimeoutSec, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val raw = client.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = extractErrorDetail(bodyStr)
                throw IllegalStateException(
                    "$label failed (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                )
            }
            if (bodyStr.isBlank()) throw IllegalStateException("$label returned an empty response.")
            bodyStr
        }
        return JSONObject(raw)
    }

    override suspend fun generateAiOverlayBrief(
        rigBaseUrl: String,
        briefPrompt: String
    ): GeneratedOverlayBriefResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
        val cleanPrompt = briefPrompt.trim()
        require(cleanPrompt.isNotEmpty()) { "Enter a news brief topic before generating an AI overlay." }

        val payload = JSONObject()
            .put("mode", "generate_overlay_brief")
            .put("text", cleanPrompt)

        val req = Request.Builder()
            .url("$baseUrl/v1/ai-assist")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val rawBody = okHttpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = extractErrorDetail(bodyStr)
                throw IllegalStateException(
                    "AI overlay generation failed (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                )
            }
            if (bodyStr.isBlank()) {
                throw IllegalStateException("Server /v1/ai-assist returned an empty response.")
            }
            bodyStr
        }

        val json = JSONObject(rawBody)
        val headline = json.optNullableString("headline")
            ?: throw IllegalStateException("AI overlay brief missing headline.")
        GeneratedOverlayBriefResult(
            kind = OverlayObjectKind.fromRaw(json.optNullableString("kind")),
            headline = headline,
            subline = json.optNullableString("subline").orEmpty(),
            calloutStat = json.optNullableString("callout_stat").orEmpty(),
            durationSec = json.optInt("duration_sec", 4).coerceIn(2, 15)
        )
    }

    override suspend fun requestRemotionInstall(
        rigBaseUrl: String,
        userRequest: String,
        enableBrollPopups: Boolean
    ): RemotionInstallResult = withContext(Dispatchers.IO) {
        val baseUrl = normalizeMacRigBaseUrl(rigBaseUrl)
        val cleanRequest = userRequest.trim()
        require(cleanRequest.isNotEmpty()) { "Enter a request describing how you want Remotion configured." }

        val payload = JSONObject()
            .put("user_request", cleanRequest)
            .put("enable_broll_popups", enableBrollPopups)

        val req = Request.Builder()
            .url("$baseUrl/v1/remotion/install")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val rawBody = okHttpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = extractErrorDetail(bodyStr)
                throw IllegalStateException(
                    "Remotion setup failed (HTTP ${resp.code})${if (detail.isNotBlank()) ": $detail" else "."}"
                )
            }
            if (bodyStr.isBlank()) {
                throw IllegalStateException("Server /v1/remotion/install returned an empty response.")
            }
            bodyStr
        }
        val json = JSONObject(rawBody)
        RemotionInstallResult(
            installed = json.optBoolean("remotion_enabled", false) ||
                json.optBoolean("remotion_installed", false) ||
                json.optString("status") == "installed",
            remotionVersion = json.optNullableString("remotion_version"),
            packageProviderId = json.optNullableString("package_provider_id") ?: "mac_rig_remotion",
            userRequest = json.optNullableString("user_request") ?: cleanRequest,
            enableBrollPopups = json.optBoolean("enable_broll_popups", enableBrollPopups)
        )
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

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else runCatching { getBoolean(key) }.getOrNull()

    private fun parseAlignedWords(arr: JSONArray?): List<AlignedWord> {
        if (arr == null || arr.length() == 0) return emptyList()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val word = obj.optNullableString("word") ?: continue
                val startMs = when {
                    obj.has("start_ms") && !obj.isNull("start_ms") -> obj.optLong("start_ms", 0L)
                    obj.has("startMs") && !obj.isNull("startMs") -> obj.optLong("startMs", 0L)
                    else -> 0L
                }.coerceAtLeast(0L)
                val endMs = when {
                    obj.has("end_ms") && !obj.isNull("end_ms") -> obj.optLong("end_ms", startMs)
                    obj.has("endMs") && !obj.isNull("endMs") -> obj.optLong("endMs", startMs)
                    else -> startMs
                }.coerceAtLeast(startMs)
                add(AlignedWord(word = word, startMs = startMs, endMs = endMs))
            }
        }
    }

    private fun encodeAlignedWordsArray(words: List<AlignedWord>): JSONArray {
        val arr = JSONArray()
        words.forEach { w ->
            arr.put(
                JSONObject()
                    .put("word", w.word)
                    .put("start_ms", w.startMs)
                    .put("end_ms", w.endMs)
            )
        }
        return arr
    }

    private fun parseRigQualityMetrics(metricsObj: JSONObject?): RigQualityMetrics? {
        if (metricsObj == null) return null
        val cer = metricsObj.optNullableDouble("cer")
        val wespeakerCosine = metricsObj.optNullableDouble("wespeaker_cosine")
        val dnsmosP808 = metricsObj.optNullableDouble("dnsmos_p808")
        val tailRmsDbfs = metricsObj.optNullableDouble("tail_rms_dbfs")
        val masteredLufs = metricsObj.optNullableDouble("mastered_lufs")
        val syllablesPerSec = metricsObj.optNullableDouble("syllables_per_sec")
        val maxPhonemeDurationMs = metricsObj.optNullableDouble("max_phoneme_duration_ms")
        val avSyncDriftMs = metricsObj.optNullableDouble("av_sync_drift_ms")
        val passedAllStrictGates = metricsObj.optNullableBoolean("passed_all_strict_gates")
        if (
            cer == null &&
            wespeakerCosine == null &&
            dnsmosP808 == null &&
            tailRmsDbfs == null &&
            masteredLufs == null &&
            syllablesPerSec == null &&
            maxPhonemeDurationMs == null &&
            avSyncDriftMs == null &&
            passedAllStrictGates == null
        ) {
            return null
        }
        return RigQualityMetrics(
            cer = cer,
            wespeakerCosine = wespeakerCosine,
            dnsmosP808 = dnsmosP808,
            tailRmsDbfs = tailRmsDbfs,
            masteredLufs = masteredLufs,
            syllablesPerSec = syllablesPerSec,
            maxPhonemeDurationMs = maxPhonemeDurationMs,
            avSyncDriftMs = avSyncDriftMs,
            passedAllStrictGates = passedAllStrictGates
        )
    }
}

private const val RIG_TASK_POLL_MS: Long = 2_000L
