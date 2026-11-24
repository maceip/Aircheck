package com.example.draft

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

@Serializable
data class BrushSwapRequest(
    @SerialName("marked_text") val markedText: String,
    @SerialName("steering_constraints") val steeringConstraints: List<String> = emptyList(),
    val nudge: String? = null,
    @SerialName("exclude_alternatives") val excludeAlternatives: List<String> = emptyList(),
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class BrushSwapResponse(
    val alternatives: List<String> = emptyList(),
    val nudge: String? = null,
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

/** Tone nudges accepted by `POST /v1/draft/brush-swap` (`nudge`). */
enum class BrushNudge(val wire: String, val label: String) {
    SIMPLER("simpler", "simpler"),
    SHARPER("sharper", "sharper"),
    LESS_FORMAL("less_formal", "less formal"),
    MORE("more", "3 more")
}

@Serializable
data class ArcRequest(
    @SerialName("seed_transcript") val seedTranscript: String,
    @SerialName("steering_constraints") val steeringConstraints: List<String> = emptyList(),
    @SerialName("selected_angles") val selectedAngles: List<String> = emptyList(),
    @SerialName("target_sec") val targetSec: Int = 60,
    @SerialName("reroll_index") val rerollIndex: Int? = null,
    @SerialName("existing_beats") val existingBeats: List<String>? = null,
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class ArcBeatDto(
    val label: String,
    @SerialName("start_sec") val startSec: Int,
    @SerialName("end_sec") val endSec: Int,
    val text: String
)

@Serializable
data class ArcResponse(
    @SerialName("target_sec") val targetSec: Int,
    @SerialName("word_budget") val wordBudget: Int,
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
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
data class SynthesizeScriptResponse(
    @SerialName("target_sec") val targetSec: Int,
    @SerialName("word_budget") val wordBudget: Int,
    val candidates: List<SynthesizedCandidateDto> = emptyList(),
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
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
        llmModel: String? = null
    ): AnglesResponse {
        val cleanSeed = seedTranscript.trim()
        if (cleanSeed.isEmpty()) {
            throw IOException("seed_transcript must not be blank.")
        }
        return postJson(
            path = "/v1/draft/angles",
            requestBody = json.encodeToString(
                AnglesRequest(
                    seedTranscript = cleanSeed,
                    steeringConstraints = steeringConstraints,
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
        llmModel: String? = null
    ): SynthesizeResponse {
        val cleanSeed = seedTranscript.trim()
        if (cleanSeed.isEmpty()) {
            throw IOException("seed_transcript must not be blank.")
        }
        if (slot != null && slot !in 0..2) {
            throw IllegalArgumentException("slot must be null or in 0..2, got $slot")
        }
        return postJson(
            path = "/v1/draft/synthesize",
            requestBody = json.encodeToString(
                SynthesizeRequest(
                    seedTranscript = cleanSeed,
                    selectedAngles = selectedAngles,
                    steeringConstraints = steeringConstraints,
                    slot = slot,
                    existingDrafts = existingDrafts,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<SynthesizeResponse>(body) }
        )
    }

    /** `POST /v1/draft/arc`: 4-beat outline, or a single-beat reroll when [rerollIndex] is set. */
    suspend fun arc(
        seedTranscript: String,
        steeringConstraints: List<String>,
        selectedAngles: List<String>,
        targetSec: Int = 60,
        rerollIndex: Int? = null,
        existingBeats: List<String>? = null,
        llmProvider: String? = null,
        llmModel: String? = null
    ): ArcResponse {
        val cleanSeed = seedTranscript.trim()
        if (cleanSeed.isEmpty()) throw IOException("seed_transcript must not be blank.")
        return postJson(
            path = "/v1/draft/arc",
            requestBody = json.encodeToString(
                ArcRequest(
                    seedTranscript = cleanSeed,
                    steeringConstraints = steeringConstraints,
                    selectedAngles = selectedAngles,
                    targetSec = targetSec,
                    rerollIndex = rerollIndex,
                    existingBeats = existingBeats,
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
        llmModel: String? = null
    ): SynthesizeScriptResponse {
        val cleanSeed = seedTranscript.trim()
        if (cleanSeed.isEmpty()) throw IOException("seed_transcript must not be blank.")
        return postJson(
            path = "/v1/draft/synthesize-script",
            requestBody = json.encodeToString(
                SynthesizeScriptRequest(
                    seedTranscript = cleanSeed,
                    selectedAngles = selectedAngles,
                    steeringConstraints = steeringConstraints,
                    arcBeats = arcBeats,
                    targetSec = targetSec,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<SynthesizeScriptResponse>(body) }
        )
    }

    /** 4. `POST /v1/draft/brush-swap`. */
    suspend fun brushSwap(
        markedText: String,
        steeringConstraints: List<String> = emptyList(),
        nudge: BrushNudge? = null,
        excludeAlternatives: List<String> = emptyList(),
        llmProvider: String? = null,
        llmModel: String? = null
    ): BrushSwapResponse {
        if (markedText.isBlank()) {
            throw IOException("marked_text must not be blank.")
        }
        return postJson(
            path = "/v1/draft/brush-swap",
            requestBody = json.encodeToString(
                BrushSwapRequest(
                    markedText = markedText,
                    steeringConstraints = steeringConstraints,
                    nudge = nudge?.wire,
                    excludeAlternatives = excludeAlternatives,
                    llmProvider = normalizeWireOptional(llmProvider),
                    llmModel = normalizeWireOptional(llmModel)
                )
            ),
            parse = { body -> json.decodeFromString<BrushSwapResponse>(body) }
        )
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
