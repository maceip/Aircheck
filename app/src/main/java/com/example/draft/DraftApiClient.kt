package com.example.draft

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class LlmMetaDto(
    val provider: String = "",
    val model: String = "",
    @SerialName("is_local") val isLocal: Boolean = false
)

@Serializable
data class LlmProviderEntryDto(
    val id: String,
    val label: String = "",
    @SerialName("is_local") val isLocal: Boolean = false,
    val available: Boolean = false,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("default_model") val defaultModel: String = "",
    val detail: String = "",
    val models: List<String> = emptyList()
)

@Serializable
data class LlmStageBindingDto(
    val provider: String = "",
    val model: String? = null
)

@Serializable
data class LlmProvidersResponse(
    val providers: List<LlmProviderEntryDto> = emptyList(),
    val stages: Map<String, LlmStageBindingDto> = emptyMap(),
    @SerialName("provider_urls") val providerUrls: Map<String, String> = emptyMap()
)

@Serializable
data class LlmConfigUpdateResponse(
    val status: String = "ok",
    val stages: Map<String, LlmStageBindingDto> = emptyMap(),
    @SerialName("provider_urls") val providerUrls: Map<String, String> = emptyMap()
)

@Serializable
data class TranscribeRequest(
    @SerialName("audio_wav_base64") val audioWavBase64: String
)

@Serializable
data class TranscribeResponse(
    val transcript: String,
    @SerialName("duration_ms") val durationMs: Long = 0L
)

@Serializable
data class AnglesRequest(
    @SerialName("seed_transcript") val seedTranscript: String,
    @SerialName("steering_constraints") val steeringConstraints: List<String> = emptyList(),
    val tone: String? = null,
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class AngleDto(
    val id: String,
    val label: String,
    val preview: String
) {
    fun toAngleChip(isBlindspot: Boolean, selected: Boolean = false): AngleChip = AngleChip(
        id = id,
        label = label,
        preview = preview,
        isBlindspot = isBlindspot,
        selected = selected
    )
}

@Serializable
data class AnglesResponse(
    @SerialName("extracted_angles") val extractedAngles: List<AngleDto> = emptyList(),
    @SerialName("blindspots") val blindspots: List<AngleDto> = emptyList(),
    val tone: String? = null,
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
) {
    fun toExtractedAngleChips(defaultSelected: Boolean = false): List<AngleChip> =
        extractedAngles.map { it.toAngleChip(isBlindspot = false, selected = defaultSelected) }

    fun toBlindspotAngleChips(defaultSelected: Boolean = false): List<AngleChip> =
        blindspots.map { it.toAngleChip(isBlindspot = true, selected = defaultSelected) }
}

@Serializable
data class SynthesizeRequest(
    @SerialName("seed_transcript") val seedTranscript: String,
    @SerialName("selected_angles") val selectedAngles: List<String>,
    @SerialName("steering_constraints") val steeringConstraints: List<String> = emptyList(),
    val slot: Int? = null,
    @SerialName("existing_drafts") val existingDrafts: List<String> = emptyList(),
    val tone: String? = null,
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class SynthesizedCandidateDto(
    @SerialName("posture_label") val postureLabel: String,
    val text: String
)

@Serializable
data class SynthesizeResponse(
    val candidates: List<SynthesizedCandidateDto> = emptyList(),
    val tone: String? = null,
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

@Serializable
data class BrushSwapRequest(
    @SerialName("marked_text") val markedText: String,
    @SerialName("steering_constraints") val steeringConstraints: List<String> = emptyList(),
    val nudge: String? = null,
    @SerialName("exclude_alternatives") val excludeAlternatives: List<String> = emptyList(),
    val tone: String? = null,
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class BrushSwapResponse(
    val alternatives: List<String> = emptyList(),
    val nudge: String? = null,
    val tone: String? = null,
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

/** Tone nudges accepted by `POST /v1/draft/brush-swap` (`nudge`). */
enum class BrushNudge(val wire: String, val label: String) {
    SIMPLER("simpler", "simpler"),
    SHARPER("sharper", "sharper"),
    LESS_FORMAL("less_formal", "less formal"),
    MORE("more", "3 more");

    companion object {
        fun fromUiOrWire(raw: String): BrushNudge = when (raw.trim().lowercase()) {
            "punchier", "sharper" -> SHARPER
            "plainer", "simpler" -> SIMPLER
            "less_formal", "less formal" -> LESS_FORMAL
            "more", "3 more" -> MORE
            else -> MORE
        }
    }
}

@Serializable
data class ShotSpecDto(
    val type: String = "face",
    val prompt: String = "",
    val id: String? = null
)

@Serializable
data class ArcRequest(
    @SerialName("seed_transcript") val seedTranscript: String,
    @SerialName("steering_constraints") val steeringConstraints: List<String> = emptyList(),
    @SerialName("selected_angles") val selectedAngles: List<String> = emptyList(),
    @SerialName("target_sec") val targetSec: Int = 60,
    val tone: String? = null,
    @SerialName("reroll_index") val rerollIndex: Int? = null,
    @SerialName("existing_beats") val existingBeats: List<String>? = null,
    @SerialName("existing_beat_shots") val existingBeatShots: List<List<ShotSpecDto>>? = null,
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class ArcBeatDto(
    val label: String,
    @SerialName("start_sec") val startSec: Int = 0,
    @SerialName("end_sec") val endSec: Int = 0,
    val text: String,
    val id: String? = null,
    val window: String? = null,
    val shots: List<ShotSpecDto> = emptyList()
) {
    fun toArcBeat(
        beatIndex: Int,
        shotIdFactory: (beatIndex: Int, shotIndex: Int, type: ShotType) -> String = { bIdx, sIdx, t ->
            "shot_b${bIdx}_s${sIdx}_${t.wireValue}_" + UUID.randomUUID().toString().replace("-", "").take(8)
        }
    ): ArcBeat {
        val (resolvedStart, resolvedEnd) = resolveWindowSeconds(startSec, endSec, window, beatIndex)
        val resolvedShots = if (shots.isNotEmpty()) {
            shots.mapIndexed { sIdx, dto ->
                val parsedType = ShotType.fromWire(dto.type)
                val resolvedId = dto.id?.trim()?.takeIf { it.isNotEmpty() }
                    ?: shotIdFactory(beatIndex, sIdx, parsedType)
                ShotSpec(
                    id = resolvedId,
                    type = parsedType,
                    prompt = dto.prompt.trim().ifBlank { text.trim().take(72) }
                )
            }
        } else {
            ShotType.defaultTypesForBeat(beatIndex).mapIndexed { sIdx, defaultType ->
                ShotSpec(
                    id = shotIdFactory(beatIndex, sIdx, defaultType),
                    type = defaultType,
                    prompt = text.trim().take(72)
                )
            }
        }
        val cleanWindow = window?.trim()?.takeIf { it.isNotEmpty() } ?: "${resolvedStart}-${resolvedEnd}s"
        return ArcBeat(
            label = label,
            startSec = resolvedStart,
            endSec = resolvedEnd,
            text = text,
            shots = resolvedShots,
            id = id?.trim()?.takeIf { it.isNotEmpty() } ?: "beat_$beatIndex",
            window = cleanWindow
        )
    }

    companion object {
        private val WINDOW_REGEX = Regex("(\\d+)\\s*[-–]\\s*(\\d+)")
        private val DEFAULT_WINDOWS = listOf(0 to 8, 8 to 28, 28 to 48, 48 to 60)

        internal fun resolveWindowSeconds(
            startSec: Int,
            endSec: Int,
            window: String?,
            beatIndex: Int
        ): Pair<Int, Int> {
            if (endSec > startSec || startSec > 0) {
                return startSec to endSec.coerceAtLeast(startSec)
            }
            if (!window.isNullOrBlank()) {
                val match = WINDOW_REGEX.find(window)
                if (match != null) {
                    val s = match.groupValues[1].toIntOrNull() ?: 0
                    val e = match.groupValues[2].toIntOrNull() ?: s
                    return s to e.coerceAtLeast(s)
                }
            }
            return DEFAULT_WINDOWS[beatIndex.coerceAtLeast(0) % DEFAULT_WINDOWS.size]
        }
    }
}

@Serializable
data class ArcResponse(
    @SerialName("target_sec") val targetSec: Int = 60,
    @SerialName("word_budget") val wordBudget: Int = 150,
    val tone: String? = null,
    val beats: List<ArcBeatDto> = emptyList(),
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

@Serializable
data class SynthesizeScriptRequest(
    @SerialName("seed_transcript") val seedTranscript: String,
    @SerialName("selected_angles") val selectedAngles: List<String> = emptyList(),
    @SerialName("steering_constraints") val steeringConstraints: List<String> = emptyList(),
    @SerialName("arc_beats") val arcBeats: List<String> = emptyList(),
    @SerialName("target_sec") val targetSec: Int = 60,
    val tone: String? = null,
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class SynthesizeScriptResponse(
    @SerialName("target_sec") val targetSec: Int = 60,
    @SerialName("word_budget") val wordBudget: Int = 150,
    val tone: String? = null,
    val candidates: List<SynthesizedCandidateDto> = emptyList(),
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

@Serializable
data class FollowupRequest(
    @SerialName("last_title") val lastTitle: String,
    @SerialName("last_transcript") val lastTranscript: String,
    @SerialName("top_reply") val topReply: String? = null,
    val angle: String = "overview",
    @SerialName("target_sec") val targetSec: Int = 60,
    val tone: String? = null,
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class FollowupDirectionDto(
    val id: String,
    val theme: String,
    val words: String,
    val beats: List<ArcBeatDto> = emptyList()
)

@Serializable
data class FollowupResponse(
    val angle: String = "overview",
    @SerialName("target_sec") val targetSec: Int = 60,
    @SerialName("word_budget") val wordBudget: Int = 150,
    val beats: List<ArcBeatDto> = emptyList(),
    @SerialName("answer_beats") val answerBeats: List<ArcBeatDto> = emptyList(),
    val directions: List<FollowupDirectionDto> = emptyList(),
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

@Serializable
data class ShotPreviewRequest(
    @SerialName("shot_type") val shotType: String,
    val prompt: String = "",
    val headline: String? = null,
    @SerialName("photo_jpeg_base64") val photoJpegBase64: String? = null,
    @SerialName("plate_id") val plateId: String? = null,
    @SerialName("generate_backdrop") val generateBackdrop: Boolean = false
)

@Serializable
data class ShotPreviewResponse(
    @SerialName("preview_id") val previewId: String,
    @SerialName("preview_url") val previewUrl: String,
    @SerialName("shot_type") val shotType: String = "face",
    val width: Int = 180,
    val height: Int = 252
)

@Serializable
data class SeamHealRequest(
    @SerialName("marked_text") val markedText: String,
    val operation: String,
    @SerialName("steering_constraints") val steeringConstraints: List<String> = emptyList(),
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class SeamHealResponse(
    val text: String,
    @SerialName("lock_parity_preserved") val lockParityPreserved: Boolean = true,
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

@Serializable
data class DensityRequest(
    @SerialName("marked_text") val markedText: String,
    @SerialName("delta_percent") val deltaPercent: Int,
    @SerialName("steering_constraints") val steeringConstraints: List<String> = emptyList(),
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class DensityResponse(
    val text: String,
    @SerialName("lock_parity_preserved") val lockParityPreserved: Boolean = true,
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

@Serializable
data class RewriteRequest(
    val text: String,
    val mode: String = "seed_clean",
    @SerialName("steering_constraints") val steeringConstraints: List<String> = emptyList(),
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class RewriteResponse(
    val text: String,
    @SerialName("lock_parity_preserved") val lockParityPreserved: Boolean = true,
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

/**
 * Real OkHttp + `kotlinx.serialization.json` client for the Mac `:8443` drafting micro-pass
 * endpoints (`POST /v1/draft/transcribe`, `angles`, `synthesize`, `brush-swap`, `seam-heal`,
 * and `density`).
 *
 * Auto-resolves the active server URL by probing `http://127.0.0.1:8443/health` (USB `adb reverse`)
 * first and falling back to [configuredRigUrl] (e.g. `http://10.0.0.242:8443`).
 *
 * Constitutional guarantee: ZERO fake, mocked, or placeholder fallback responses. Every non-2xx
 * HTTP response or connectivity error throws an explicit [IOException] carrying server detail.
 */
class DraftApiClient(
    private val configuredRigUrl: String = DEFAULT_RIG_LAN_URL,
    private val usbLoopbackUrl: String = DEFAULT_RIG_USB_URL,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val healthProbeTimeoutMs: Long = DEFAULT_HEALTH_PROBE_TIMEOUT_MS
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    private val probeClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(healthProbeTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(healthProbeTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(healthProbeTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    @Volatile
    private var cachedBaseUrl: String? = null

    /** Default tone sent when no explicit `tone` parameter or [DraftToneContext] is supplied. */
    @Volatile
    var activeTone: DraftTone = DraftTone.HOT_TAKE

    private suspend fun resolveWireTone(explicitTone: String?): String {
        val clean = normalizeWireOptional(explicitTone)
        if (clean != null) return DraftTone.fromWire(clean).wireValue
        val fromCtx = coroutineContext[DraftToneContext]?.tone
        return (fromCtx ?: activeTone).wireValue
    }

    private suspend fun resolveExistingBeatShots(
        explicitShots: List<List<ShotSpecDto>>?,
        rerollIndex: Int?
    ): List<List<ShotSpecDto>>? {
        if (explicitShots != null) return explicitShots
        if (rerollIndex == null) return null
        val fromCtx = coroutineContext[ExistingBeatShotsContext]?.beatShots ?: return null
        if (fromCtx.isEmpty()) return null
        return fromCtx.map { row ->
            row.map { ShotSpecDto(type = it.type.wireValue, prompt = it.prompt) }
        }
    }

    /**
     * Resolves the active `:8443` base URL via fast `/health` probe:
     * tries [usbLoopbackUrl] (`http://127.0.0.1:8443`) first, falling back to [configuredRigUrl].
     */
    suspend fun resolveActiveBaseUrl(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedBaseUrl?.let { return@withContext it }
        }
        val candidates = buildCandidateBaseUrls()
        val errors = mutableListOf<String>()

        for (baseUrl in candidates) {
            val probeReq = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()
            try {
                probeClient.newCall(probeReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        cachedBaseUrl = baseUrl
                        return@withContext baseUrl
                    }
                    val detail = extractServerErrorDetail(resp.body?.string().orEmpty())
                    errors += "$baseUrl (HTTP ${resp.code}${if (detail.isNotBlank()) ": $detail" else ""})"
                }
            } catch (e: IOException) {
                errors += "$baseUrl (${e.message ?: e::class.java.simpleName})"
            }
        }

        cachedBaseUrl = null
        throw IOException(
            "Mac drafting server unreachable on /health. Checked: ${errors.joinToString("; ")}"
        )
    }

    fun clearResolvedBaseUrlCache() {
        cachedBaseUrl = null
    }

    /** 1. `POST /v1/draft/transcribe` (from WAV file). */
    suspend fun transcribe(wavFile: File): TranscribeResponse = withContext(Dispatchers.IO) {
        if (!wavFile.exists() || wavFile.length() == 0L) {
            throw IOException("Audio WAV file is missing or empty: ${wavFile.absolutePath}")
        }
        transcribeWavBytes(wavFile.readBytes())
    }

    /** 1b. `POST /v1/draft/transcribe` (from raw WAV bytes). */
    suspend fun transcribeWavBytes(wavBytes: ByteArray): TranscribeResponse {
        if (wavBytes.isEmpty()) {
            throw IOException("Audio WAV payload is empty.")
        }
        val b64 = Base64.getEncoder().encodeToString(wavBytes)
        return postJson(
            path = "/v1/draft/transcribe",
            requestBody = json.encodeToString(TranscribeRequest(audioWavBase64 = b64)),
            parse = { body -> json.decodeFromString<TranscribeResponse>(body) }
        )
    }

    /** 2. `POST /v1/draft/angles`. */
    suspend fun extractAngles(
        seedTranscript: String,
        steeringConstraints: List<String> = emptyList(),
        llmProvider: String? = null,
        llmModel: String? = null,
        tone: String? = null
    ): AnglesResponse {
        val cleanSeed = seedTranscript.trim()
        if (cleanSeed.isEmpty()) {
            throw IOException("seed_transcript must not be blank.")
        }
        val wireTone = resolveWireTone(tone)
        return postJson(
            path = "/v1/draft/angles",
            requestBody = json.encodeToString(
                AnglesRequest(
                    seedTranscript = cleanSeed,
                    steeringConstraints = steeringConstraints,
                    tone = wireTone,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<AnglesResponse>(body) }
        )
    }

    /** 3. `POST /v1/draft/synthesize`. */
    suspend fun synthesize(
        seedTranscript: String,
        selectedAngles: List<String>,
        steeringConstraints: List<String> = emptyList(),
        slot: Int? = null,
        existingDrafts: List<String> = emptyList(),
        llmProvider: String? = null,
        llmModel: String? = null,
        tone: String? = null
    ): SynthesizeResponse {
        val cleanSeed = seedTranscript.trim()
        if (cleanSeed.isEmpty()) {
            throw IOException("seed_transcript must not be blank.")
        }
        if (slot != null && slot !in 0..2) {
            throw IllegalArgumentException("slot must be null or in 0..2, got $slot")
        }
        val wireTone = resolveWireTone(tone)
        return postJson(
            path = "/v1/draft/synthesize",
            requestBody = json.encodeToString(
                SynthesizeRequest(
                    seedTranscript = cleanSeed,
                    selectedAngles = selectedAngles,
                    steeringConstraints = steeringConstraints,
                    slot = slot,
                    existingDrafts = existingDrafts,
                    tone = wireTone,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<SynthesizeResponse>(body) }
        )
    }

    /** `POST /v1/draft/arc`: 4-beat outline + shot plan, or a single-beat reroll when [rerollIndex] is set. */
    suspend fun arc(
        seedTranscript: String,
        steeringConstraints: List<String>,
        selectedAngles: List<String>,
        targetSec: Int = 60,
        rerollIndex: Int? = null,
        existingBeats: List<String>? = null,
        llmProvider: String? = null,
        llmModel: String? = null,
        tone: String? = null,
        existingBeatShots: List<List<ShotSpecDto>>? = null
    ): ArcResponse {
        val cleanSeed = seedTranscript.trim()
        if (cleanSeed.isEmpty()) throw IOException("seed_transcript must not be blank.")
        val wireTone = resolveWireTone(tone)
        val wireShots = resolveExistingBeatShots(existingBeatShots, rerollIndex)
        return postJson(
            path = "/v1/draft/arc",
            requestBody = json.encodeToString(
                ArcRequest(
                    seedTranscript = cleanSeed,
                    steeringConstraints = steeringConstraints,
                    selectedAngles = selectedAngles,
                    targetSec = targetSec,
                    tone = wireTone,
                    rerollIndex = rerollIndex,
                    existingBeats = existingBeats,
                    existingBeatShots = wireShots,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<ArcResponse>(body) }
        )
    }

    /** Alias for [arc] proposing a full 4-beat outline + shot plan. */
    suspend fun proposeArc(
        seedTranscript: String,
        steeringConstraints: List<String> = emptyList(),
        selectedAngles: List<String> = emptyList(),
        targetSec: Int = 60,
        tone: DraftTone? = null,
        llmProvider: String? = null,
        llmModel: String? = null
    ): ArcResponse = arc(
        seedTranscript = seedTranscript,
        steeringConstraints = steeringConstraints,
        selectedAngles = selectedAngles,
        targetSec = targetSec,
        rerollIndex = null,
        existingBeats = null,
        llmProvider = llmProvider,
        llmModel = llmModel,
        tone = tone?.wireValue
    )

    /** `POST /v1/draft/reroll-beat`: rewrites a single beat and its shot plan while keeping all others. */
    suspend fun rerollBeat(
        seedTranscript: String,
        rerollIndex: Int,
        existingBeats: List<String>,
        steeringConstraints: List<String> = emptyList(),
        selectedAngles: List<String> = emptyList(),
        targetSec: Int = 60,
        existingBeatShots: List<List<ShotSpecDto>>? = null,
        tone: DraftTone? = null,
        llmProvider: String? = null,
        llmModel: String? = null
    ): ArcResponse {
        val cleanSeed = seedTranscript.trim()
        if (cleanSeed.isEmpty()) throw IOException("seed_transcript must not be blank.")
        val wireTone = resolveWireTone(tone?.wireValue)
        val wireShots = resolveExistingBeatShots(existingBeatShots, rerollIndex)
        return postJson(
            path = "/v1/draft/reroll-beat",
            requestBody = json.encodeToString(
                ArcRequest(
                    seedTranscript = cleanSeed,
                    steeringConstraints = steeringConstraints,
                    selectedAngles = selectedAngles,
                    targetSec = targetSec,
                    tone = wireTone,
                    rerollIndex = rerollIndex,
                    existingBeats = existingBeats,
                    existingBeatShots = wireShots,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<ArcResponse>(body) }
        )
    }

    /** `POST /v1/draft/synthesize-script`: 3 spoken-video versions (DIRECT / PUNCHY / STORY). */
    suspend fun synthesizeScript(
        seedTranscript: String,
        selectedAngles: List<String>,
        steeringConstraints: List<String>,
        arcBeats: List<String>,
        targetSec: Int = 60,
        llmProvider: String? = null,
        llmModel: String? = null,
        tone: String? = null
    ): SynthesizeScriptResponse {
        val cleanSeed = seedTranscript.trim()
        if (cleanSeed.isEmpty()) throw IOException("seed_transcript must not be blank.")
        val wireTone = resolveWireTone(tone)
        return postJson(
            path = "/v1/draft/synthesize-script",
            requestBody = json.encodeToString(
                SynthesizeScriptRequest(
                    seedTranscript = cleanSeed,
                    selectedAngles = selectedAngles,
                    steeringConstraints = steeringConstraints,
                    arcBeats = arcBeats,
                    targetSec = targetSec,
                    tone = wireTone,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<SynthesizeScriptResponse>(body) }
        )
    }

    /** Alias for [synthesizeScript] with typed [DraftTone]. */
    suspend fun writeVideoScript(
        seedTranscript: String,
        selectedAngles: List<String> = emptyList(),
        steeringConstraints: List<String> = emptyList(),
        arcBeats: List<String> = emptyList(),
        targetSec: Int = 60,
        tone: DraftTone? = null,
        llmProvider: String? = null,
        llmModel: String? = null
    ): SynthesizeScriptResponse = synthesizeScript(
        seedTranscript = seedTranscript,
        selectedAngles = selectedAngles,
        steeringConstraints = steeringConstraints,
        arcBeats = arcBeats,
        targetSec = targetSec,
        llmProvider = llmProvider,
        llmModel = llmModel,
        tone = tone?.wireValue
    )

    /** 4. `POST /v1/draft/brush-swap`. */
    suspend fun brushSwap(
        markedText: String,
        steeringConstraints: List<String> = emptyList(),
        nudge: BrushNudge? = null,
        excludeAlternatives: List<String> = emptyList(),
        llmProvider: String? = null,
        llmModel: String? = null,
        tone: String? = null
    ): BrushSwapResponse {
        if (markedText.isBlank()) {
            throw IOException("marked_text must not be blank.")
        }
        val wireTone = resolveWireTone(tone)
        return postJson(
            path = "/v1/draft/brush-swap",
            requestBody = json.encodeToString(
                BrushSwapRequest(
                    markedText = markedText,
                    steeringConstraints = steeringConstraints,
                    nudge = nudge?.wire,
                    excludeAlternatives = excludeAlternatives,
                    tone = wireTone,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<BrushSwapResponse>(body) }
        )
    }

    /** Alias for [brushSwap] when applying an explicit [BrushNudge] with optional [DraftTone]. */
    suspend fun brushNudge(
        markedText: String,
        nudge: BrushNudge,
        steeringConstraints: List<String> = emptyList(),
        excludeAlternatives: List<String> = emptyList(),
        tone: DraftTone? = null,
        llmProvider: String? = null,
        llmModel: String? = null
    ): BrushSwapResponse = brushSwap(
        markedText = markedText,
        steeringConstraints = steeringConstraints,
        nudge = nudge,
        excludeAlternatives = excludeAlternatives,
        llmProvider = llmProvider,
        llmModel = llmModel,
        tone = tone?.wireValue
    )

    /** `POST /v1/draft/followup`: LLM-authored follow-up outline & directions for the user's last video. */
    suspend fun followup(
        lastTitle: String,
        lastTranscript: String,
        topReply: String? = null,
        angle: String = "overview",
        targetSec: Int = 60,
        tone: String? = null,
        llmProvider: String? = null,
        llmModel: String? = null
    ): FollowupResponse {
        val cleanTitle = lastTitle.trim()
        val cleanTranscript = lastTranscript.trim().ifBlank { cleanTitle }
        if (cleanTitle.isEmpty() && cleanTranscript.isEmpty()) {
            throw IOException("last_title or last_transcript must not be blank.")
        }
        val normalizedAngle = angle.trim().lowercase().replace(Regex("[\\s-]+"), "_").ifBlank { "overview" }
        val wireTone = resolveWireTone(tone)
        return postJson(
            path = "/v1/draft/followup",
            requestBody = json.encodeToString(
                FollowupRequest(
                    lastTitle = cleanTitle.ifBlank { cleanTranscript.take(40) },
                    lastTranscript = cleanTranscript,
                    topReply = normalizeWireOptional(topReply),
                    angle = normalizedAngle,
                    targetSec = targetSec,
                    tone = wireTone,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<FollowupResponse>(body) }
        )
    }

    /** `POST /v1/studio/shot-preview`: renders and caches a real 180x252 JPEG preview frame on the rig. */
    suspend fun requestShotPreview(
        shotType: String,
        prompt: String,
        headline: String? = null,
        photoJpegBase64: String? = null,
        plateId: String? = null,
        generateBackdrop: Boolean = false
    ): ShotPreviewResponse {
        val cleanType = ShotType.fromWire(shotType).wireValue
        return postJson(
            path = "/v1/studio/shot-preview",
            requestBody = json.encodeToString(
                ShotPreviewRequest(
                    shotType = cleanType,
                    prompt = prompt.trim(),
                    headline = normalizeWireOptional(headline),
                    photoJpegBase64 = normalizeWireOptional(photoJpegBase64),
                    plateId = normalizeWireOptional(plateId),
                    generateBackdrop = generateBackdrop
                )
            ),
            parse = { body -> json.decodeFromString<ShotPreviewResponse>(body) }
        )
    }

    /** Downloads raw binary bytes (such as `/v1/renders/shotprev_<hash>.jpg`) from the active rig. */
    suspend fun downloadPreviewBytes(previewUrlOrPath: String): ByteArray = withContext(Dispatchers.IO) {
        val trimmed = previewUrlOrPath.trim()
        if (trimmed.isEmpty()) throw IOException("Preview URL must not be blank.")
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return@withContext executeGetBytes(trimmed)
        }
        val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val baseUrl = resolveActiveBaseUrl(forceRefresh = false)
        try {
            executeGetBytes("$baseUrl$path")
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: IOException) {
            val refreshed = resolveActiveBaseUrl(forceRefresh = true)
            if (refreshed == baseUrl) throw e
            executeGetBytes("$refreshed$path")
        }
    }

    /** 5. `POST /v1/draft/seam-heal`. */
    suspend fun seamHeal(
        markedText: String,
        operation: String,
        steeringConstraints: List<String> = emptyList(),
        llmProvider: String? = null,
        llmModel: String? = null
    ): SeamHealResponse {
        if (markedText.isBlank()) {
            throw IOException("marked_text must not be blank.")
        }
        if (operation !in VALID_SEAM_HEAL_OPERATIONS) {
            throw IllegalArgumentException(
                "Invalid seam-heal operation '$operation'. Must be one of $VALID_SEAM_HEAL_OPERATIONS."
            )
        }
        return postJson(
            path = "/v1/draft/seam-heal",
            requestBody = json.encodeToString(
                SeamHealRequest(
                    markedText = markedText,
                    operation = operation,
                    steeringConstraints = steeringConstraints,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<SeamHealResponse>(body) }
        )
    }

    /** 6. `POST /v1/draft/density`. */
    suspend fun adjustDensity(
        markedText: String,
        deltaPercent: Int,
        steeringConstraints: List<String> = emptyList(),
        llmProvider: String? = null,
        llmModel: String? = null
    ): DensityResponse {
        if (markedText.isBlank()) {
            throw IOException("marked_text must not be blank.")
        }
        val clampedDelta = deltaPercent.coerceIn(MIN_DENSITY_DELTA, MAX_DENSITY_DELTA)
        return postJson(
            path = "/v1/draft/density",
            requestBody = json.encodeToString(
                DensityRequest(
                    markedText = markedText,
                    deltaPercent = clampedDelta,
                    steeringConstraints = steeringConstraints,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<DensityResponse>(body) }
        )
    }

    /** 7. `POST /v1/draft/rewrite` — inline ✨ AI rewrite/clean/polish. */
    suspend fun rewrite(
        text: String,
        mode: String = "seed_clean",
        steeringConstraints: List<String> = emptyList(),
        llmProvider: String? = null,
        llmModel: String? = null
    ): RewriteResponse {
        if (text.isBlank()) {
            throw IOException("text must not be blank.")
        }
        return postJson(
            path = "/v1/draft/rewrite",
            requestBody = json.encodeToString(
                RewriteRequest(
                    text = text,
                    mode = mode,
                    steeringConstraints = steeringConstraints,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<RewriteResponse>(body) }
        )
    }

    /** `GET /v1/llm/providers`: live provider discovery & current per-stage configuration on Mac `:8443`. */
    suspend fun fetchLlmProviders(): LlmProvidersResponse = getJson(
        path = "/v1/llm/providers",
        parse = { body -> json.decodeFromString<LlmProvidersResponse>(body) }
    )

    /** `POST /v1/llm/config`: updates persisted per-stage provider/model config on Mac `:8443`. */
    suspend fun updateLlmConfig(
        stages: Map<String, LlmStageBindingDto>,
        providerUrls: Map<String, String>? = null
    ): LlmConfigUpdateResponse {
        val payload = buildJsonObject {
            if (stages.isNotEmpty()) {
                putJsonObject("stages") {
                    for ((stageName, binding) in stages) {
                        putJsonObject(stageName) {
                            put("provider", binding.provider.trim())
                            val cleanModel = normalizeWireOptional(binding.model)
                            if (cleanModel != null) {
                                put("model", cleanModel)
                            }
                        }
                    }
                }
            }
            if (providerUrls != null) {
                putJsonObject("provider_urls") {
                    for ((prov, url) in providerUrls) {
                        put(prov.trim(), url.trim())
                    }
                }
            }
        }
        return postJson(
            path = "/v1/llm/config",
            requestBody = payload.toString(),
            parse = { body -> json.decodeFromString<LlmConfigUpdateResponse>(body) }
        )
    }

    private fun normalizeWireOptional(raw: String?): String? =
        raw?.trim()?.ifBlank { null }

    private suspend fun <T> getJson(
        path: String,
        parse: (String) -> T
    ): T = withContext(Dispatchers.IO) {
        val baseUrl = resolveActiveBaseUrl(forceRefresh = false)
        try {
            executeGet(baseUrl, path, parse)
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: IOException) {
            val refreshed = resolveActiveBaseUrl(forceRefresh = true)
            if (refreshed == baseUrl) throw e
            executeGet(refreshed, path, parse)
        }
    }

    private suspend fun <T> postJson(
        path: String,
        requestBody: String,
        parse: (String) -> T
    ): T = withContext(Dispatchers.IO) {
        val baseUrl = resolveActiveBaseUrl(forceRefresh = false)
        try {
            executePost(baseUrl, path, requestBody, parse)
        } catch (e: HttpStatusException) {
            // Do not mask real server 4xx/5xx errors as transport failures.
            throw e
        } catch (e: IOException) {
            // Cached base URL may have dropped (e.g. USB unplugged); re-probe once and retry.
            val refreshed = resolveActiveBaseUrl(forceRefresh = true)
            if (refreshed == baseUrl) throw e
            executePost(refreshed, path, requestBody, parse)
        }
    }

    private fun <T> executeGet(
        baseUrl: String,
        path: String,
        parse: (String) -> T
    ): T {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errInfo = parseServerErrorInfo(rawBody)
                throw HttpStatusException(
                    statusCode = response.code,
                    message = "GET $path failed (HTTP ${response.code})" +
                        if (errInfo.detail.isNotBlank()) ": ${errInfo.detail}" else "",
                    provider = errInfo.provider,
                    stage = errInfo.stage,
                    errorCode = errInfo.errorCode
                )
            }
            if (rawBody.isBlank()) {
                throw IOException("GET $path returned an empty response body.")
            }
            return try {
                parse(rawBody)
            } catch (e: Exception) {
                throw IOException("Failed to parse response from GET $path: ${e.message}", e)
            }
        }
    }

    private fun executeGetBytes(fullUrl: String): ByteArray {
        val request = Request.Builder()
            .url(fullUrl)
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val rawBody = response.body?.string().orEmpty()
                val errInfo = parseServerErrorInfo(rawBody)
                throw HttpStatusException(
                    statusCode = response.code,
                    message = "GET $fullUrl failed (HTTP ${response.code})" +
                        if (errInfo.detail.isNotBlank()) ": ${errInfo.detail}" else "",
                    provider = errInfo.provider,
                    stage = errInfo.stage,
                    errorCode = errInfo.errorCode
                )
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) {
                throw IOException("GET $fullUrl returned 0 bytes.")
            }
            return bytes
        }
    }

    private fun <T> executePost(
        baseUrl: String,
        path: String,
        requestBody: String,
        parse: (String) -> T
    ): T {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errInfo = parseServerErrorInfo(rawBody)
                throw HttpStatusException(
                    statusCode = response.code,
                    message = "POST $path failed (HTTP ${response.code})" +
                        if (errInfo.detail.isNotBlank()) ": ${errInfo.detail}" else "",
                    provider = errInfo.provider,
                    stage = errInfo.stage,
                    errorCode = errInfo.errorCode
                )
            }
            if (rawBody.isBlank()) {
                throw IOException("POST $path returned an empty response body.")
            }
            return try {
                parse(rawBody)
            } catch (e: Exception) {
                throw IOException("Failed to parse response from POST $path: ${e.message}", e)
            }
        }
    }

    private fun buildCandidateBaseUrls(): List<String> {
        val ordered = LinkedHashSet<String>()
        normalizeBaseUrlOrNull(usbLoopbackUrl)?.let { ordered += it }
        normalizeBaseUrlOrNull(configuredRigUrl)?.let { ordered += it }
        return ordered.toList()
    }

    private fun normalizeBaseUrlOrNull(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        return trimmed
    }

    private fun extractServerErrorDetail(rawBody: String): String =
        parseServerErrorInfo(rawBody).detail

    internal data class ServerErrorInfo(
        val detail: String,
        val errorCode: String? = null,
        val provider: String? = null,
        val stage: String? = null
    )

    internal fun parseServerErrorInfo(rawBody: String): ServerErrorInfo =
        parseServerErrorInfoStatic(json, rawBody)

    class HttpStatusException(
        val statusCode: Int,
        message: String,
        val provider: String? = null,
        val stage: String? = null,
        val errorCode: String? = null
    ) : IOException(message)

    companion object {
        const val DEFAULT_RIG_USB_URL: String = "http://127.0.0.1:8443"
        const val DEFAULT_RIG_LAN_URL: String = "http://10.0.0.242:8443"
        const val DEFAULT_HEALTH_PROBE_TIMEOUT_MS: Long = 1_500L
        const val MIN_DENSITY_DELTA: Int = -25
        const val MAX_DENSITY_DELTA: Int = 15

        val VALID_SEAM_HEAL_OPERATIONS: Set<String> = setOf(
            "strike_cut",
            "voice_splice",
            "reorder",
            "weave_mention"
        )

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun parseServerErrorInfoStatic(jsonParser: Json, rawBody: String): ServerErrorInfo {
            val trimmed = rawBody.trim()
            if (trimmed.isEmpty()) return ServerErrorInfo(detail = "")
            return runCatching {
                val element = jsonParser.parseToJsonElement(trimmed)
                if (element is JsonObject) {
                    val keys = listOf("detail", "error_message", "error", "message")
                    var resolvedDetail = ""
                    for (key in keys) {
                        val prim = element[key] as? JsonPrimitive
                        val text = prim?.contentOrNull?.trim()
                        if (!text.isNullOrEmpty()) {
                            resolvedDetail = text
                            break
                        }
                    }
                    val errorCode = (element["error"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
                    val provider = (element["provider"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
                    val stage = (element["stage"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
                    return@runCatching ServerErrorInfo(
                        detail = resolvedDetail.ifBlank { trimmed.take(240) },
                        errorCode = errorCode,
                        provider = provider,
                        stage = stage
                    )
                }
                ServerErrorInfo(detail = trimmed.take(240))
            }.getOrElse { ServerErrorInfo(detail = trimmed.take(240)) }
        }

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
