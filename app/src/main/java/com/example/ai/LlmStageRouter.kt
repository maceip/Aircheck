package com.example.ai

import com.example.agent.OnDeviceLlmBackend
import com.example.data.LlmProviderChoice
import com.example.data.LlmStage
import com.example.data.LlmStageConfig
import com.example.data.OverlayObjectKind
import com.example.data.gateway.GeneratedOverlayBriefResult
import com.example.data.local.AircheckProfileStore
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.draft.AnglesResponse
import com.example.draft.ArcResponse
import com.example.draft.BrushNudge
import com.example.draft.BrushSwapResponse
import com.example.draft.DensityResponse
import com.example.draft.DraftApiClient
import com.example.draft.LlmConfigUpdateResponse
import com.example.draft.LlmMetaDto
import com.example.draft.LlmProvidersResponse
import com.example.draft.LlmStageBindingDto
import com.example.draft.RewriteResponse
import com.example.draft.SeamHealResponse
import com.example.draft.SynthesizeResponse
import com.example.draft.SynthesizeScriptResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class LlmRoutingException(
    val stage: LlmStage,
    val provider: String,
    val isTransportFailure: Boolean,
    val statusCode: Int? = null,
    val serverStage: String? = null,
    val errorCode: String? = null,
    override val message: String,
    override val cause: Throwable? = null
) : IOException(message, cause)

data class RoutedOverlayBriefResult(
    val brief: GeneratedOverlayBriefResult,
    val llmMeta: LlmMetaDto
)

@Serializable
private data class AiAssistOverlayRequestDto(
    val mode: String = "generate_overlay_brief",
    val text: String,
    @SerialName("llm_provider") val llmProvider: String? = null,
    @SerialName("llm_model") val llmModel: String? = null
)

@Serializable
private data class AiAssistOverlayResponseDto(
    val mode: String = "",
    val kind: String = "LOWER_THIRD",
    val headline: String = "",
    val subline: String = "",
    @SerialName("callout_stat") val calloutStat: String = "",
    @SerialName("duration_sec") val durationSec: Int = 4,
    @SerialName("llm_meta") val llmMeta: LlmMetaDto? = null
)

/**
 * Per-stage LLM router unifying:
 * - On-device Gemini Nano ([OnDeviceLlmBackend])
 * - Mac `:8443` drafting micro-passes ([DraftApiClient])
 * - Mac `:8443` `/v1/ai-assist`, `/v1/llm/providers`, `/v1/llm/config` over [OkHttpClient]
 *
 * Constitutional Law 2 guarantee: ZERO silent fallback across providers.
 * If [LlmProviderChoice.LOCAL_AICORE_NANO] is selected and fails, an explicit [LlmRoutingException]
 * is thrown/returned without calling `:8443`. If a Mac local or hosted provider fails or returns
 * HTTP 400/502/503, the exact server error/provider/stage is surfaced without calling Gemini Nano.
 */
class LlmStageRouter(
    private val profileStore: AircheckProfileStore,
    private val draftApiClientProvider: (String) -> DraftApiClient,
    private val onDeviceEngine: OnDeviceLlmBackend,
    private val okHttpClient: OkHttpClient
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    suspend fun extractAngles(
        seedTranscript: String,
        steeringConstraints: List<String> = emptyList(),
        rigUrlOverride: String? = null
    ): AnglesResponse {
        val stage = LlmStage.DRAFTING
        return executeStagePass(stage, rigUrlOverride,
            onDevice = { onDeviceEngine.extractAngles(seedTranscript, steeringConstraints) },
            onRig = { client, wireProvider, model ->
                client.extractAngles(
                    seedTranscript = seedTranscript,
                    steeringConstraints = steeringConstraints,
                    llmProvider = wireProvider,
                    llmModel = model
                )
            }
        )
    }

    suspend fun arc(
        seedTranscript: String,
        steeringConstraints: List<String> = emptyList(),
        selectedAngles: List<String> = emptyList(),
        targetSec: Int = 60,
        rerollIndex: Int? = null,
        existingBeats: List<String>? = null,
        rigUrlOverride: String? = null
    ): ArcResponse {
        val stage = LlmStage.DRAFTING
        return executeStagePass(stage, rigUrlOverride,
            onDevice = {
                onDeviceEngine.arc(
                    seedTranscript = seedTranscript,
                    steeringConstraints = steeringConstraints,
                    selectedAngles = selectedAngles,
                    targetSec = targetSec,
                    rerollIndex = rerollIndex,
                    existingBeats = existingBeats
                )
            },
            onRig = { client, wireProvider, model ->
                client.arc(
                    seedTranscript = seedTranscript,
                    steeringConstraints = steeringConstraints,
                    selectedAngles = selectedAngles,
                    targetSec = targetSec,
                    rerollIndex = rerollIndex,
                    existingBeats = existingBeats,
                    llmProvider = wireProvider,
                    llmModel = model
                )
            }
        )
    }

    suspend fun synthesize(
        seedTranscript: String,
        selectedAngles: List<String>,
        steeringConstraints: List<String> = emptyList(),
        slot: Int? = null,
        existingDrafts: List<String> = emptyList(),
        rigUrlOverride: String? = null
    ): SynthesizeResponse {
        val stage = LlmStage.SCRIPT_EDIT
        return executeStagePass(stage, rigUrlOverride,
            onDevice = {
                onDeviceEngine.synthesize(
                    seedTranscript = seedTranscript,
                    selectedAngles = selectedAngles,
                    steeringConstraints = steeringConstraints,
                    slot = slot,
                    existingDrafts = existingDrafts
                )
            },
            onRig = { client, wireProvider, model ->
                client.synthesize(
                    seedTranscript = seedTranscript,
                    selectedAngles = selectedAngles,
                    steeringConstraints = steeringConstraints,
                    slot = slot,
                    existingDrafts = existingDrafts,
                    llmProvider = wireProvider,
                    llmModel = model
                )
            }
        )
    }

    suspend fun synthesizeScript(
        seedTranscript: String,
        selectedAngles: List<String> = emptyList(),
        steeringConstraints: List<String> = emptyList(),
        arcBeats: List<String> = emptyList(),
        targetSec: Int = 60,
        rigUrlOverride: String? = null
    ): SynthesizeScriptResponse {
        val stage = LlmStage.SCRIPT_EDIT
        return executeStagePass(stage, rigUrlOverride,
            onDevice = {
                onDeviceEngine.synthesizeScript(
                    seedTranscript = seedTranscript,
                    selectedAngles = selectedAngles,
                    steeringConstraints = steeringConstraints,
                    arcBeats = arcBeats,
                    targetSec = targetSec
                )
            },
            onRig = { client, wireProvider, model ->
                client.synthesizeScript(
                    seedTranscript = seedTranscript,
                    selectedAngles = selectedAngles,
                    steeringConstraints = steeringConstraints,
                    arcBeats = arcBeats,
                    targetSec = targetSec,
                    llmProvider = wireProvider,
                    llmModel = model
                )
            }
        )
    }

    suspend fun brushSwap(
        markedText: String,
        steeringConstraints: List<String> = emptyList(),
        nudge: BrushNudge? = null,
        excludeAlternatives: List<String> = emptyList(),
        rigUrlOverride: String? = null
    ): BrushSwapResponse {
        val stage = LlmStage.SCRIPT_EDIT
        return executeStagePass(stage, rigUrlOverride,
            onDevice = {
                onDeviceEngine.brushSwap(
                    markedText = markedText,
                    steeringConstraints = steeringConstraints,
                    nudge = nudge,
                    excludeAlternatives = excludeAlternatives
                )
            },
            onRig = { client, wireProvider, model ->
                client.brushSwap(
                    markedText = markedText,
                    steeringConstraints = steeringConstraints,
                    nudge = nudge,
                    excludeAlternatives = excludeAlternatives,
                    llmProvider = wireProvider,
                    llmModel = model
                )
            }
        )
    }

    suspend fun seamHeal(
        markedText: String,
        operation: String,
        steeringConstraints: List<String> = emptyList(),
        rigUrlOverride: String? = null
    ): SeamHealResponse {
        val stage = LlmStage.SCRIPT_EDIT
        return executeStagePass(stage, rigUrlOverride,
            onDevice = {
                onDeviceEngine.seamHeal(
                    markedText = markedText,
                    operation = operation,
                    steeringConstraints = steeringConstraints
                )
            },
            onRig = { client, wireProvider, model ->
                client.seamHeal(
                    markedText = markedText,
                    operation = operation,
                    steeringConstraints = steeringConstraints,
                    llmProvider = wireProvider,
                    llmModel = model
                )
            }
        )
    }

    suspend fun adjustDensity(
        markedText: String,
        deltaPercent: Int,
        steeringConstraints: List<String> = emptyList(),
        rigUrlOverride: String? = null
    ): DensityResponse {
        val stage = LlmStage.SCRIPT_EDIT
        return executeStagePass(stage, rigUrlOverride,
            onDevice = {
                onDeviceEngine.adjustDensity(
                    markedText = markedText,
                    deltaPercent = deltaPercent,
                    steeringConstraints = steeringConstraints
                )
            },
            onRig = { client, wireProvider, model ->
                client.adjustDensity(
                    markedText = markedText,
                    deltaPercent = deltaPercent,
                    steeringConstraints = steeringConstraints,
                    llmProvider = wireProvider,
                    llmModel = model
                )
            }
        )
    }

    suspend fun rewrite(
        text: String,
        mode: String = "seed_clean",
        steeringConstraints: List<String> = emptyList(),
        rigUrlOverride: String? = null
    ): RewriteResponse {
        val stage = if (mode.trim().equals("seed_clean", ignoreCase = true)) {
            LlmStage.DRAFTING
        } else {
            LlmStage.SCRIPT_EDIT
        }
        return executeStagePass(stage, rigUrlOverride,
            onDevice = {
                onDeviceEngine.rewrite(
                    text = text,
                    mode = mode,
                    steeringConstraints = steeringConstraints
                )
            },
            onRig = { client, wireProvider, model ->
                client.rewrite(
                    text = text,
                    mode = mode,
                    steeringConstraints = steeringConstraints,
                    llmProvider = wireProvider,
                    llmModel = model
                )
            }
        )
    }

    suspend fun generateOverlayBrief(
        topic: String,
        rigUrlOverride: String? = null
    ): Result<RoutedOverlayBriefResult> {
        val cleanTopic = topic.trim()
        val stage = LlmStage.NEWSROOM_OVERLAY
        if (cleanTopic.isEmpty()) {
            return Result.failure(
                LlmRoutingException(
                    stage = stage,
                    provider = "unknown",
                    isTransportFailure = false,
                    message = "Overlay topic must not be empty."
                )
            )
        }

        val settings = profileStore.current()
        val stageCfg = settings.llmRouting.forStage(stage)
        if (stageCfg.provider == LlmProviderChoice.LOCAL_AICORE_NANO) {
            return onDeviceEngine.generateOverlayBrief(cleanTopic).fold(
                onSuccess = { brief ->
                    Result.success(
                        RoutedOverlayBriefResult(
                            brief = brief,
                            llmMeta = LlmMetaDto(
                                provider = LlmProviderChoice.LOCAL_AICORE_NANO.id,
                                model = "gemini-nano",
                                isLocal = true
                            )
                        )
                    )
                },
                onFailure = { err ->
                    if (err is CancellationException) throw err
                    Result.failure(
                        LlmRoutingException(
                            stage = stage,
                            provider = LlmProviderChoice.LOCAL_AICORE_NANO.id,
                            isTransportFailure = false,
                            message = err.message ?: "On-device Gemini Nano failed for Newsroom overlay.",
                            cause = err
                        )
                    )
                }
            )
        }

        val rigUrl = resolveRigUrl(rigUrlOverride, settings.tailscaleRigUrl)
        val wireProvider = stageCfg.resolveWireProviderId()
        val wireModel = stageCfg.normalizedModelOverride()
        val providerLabel = wireProvider ?: LlmProviderChoice.MAC_RIG_DEFAULT.id

        return withContext(Dispatchers.IO) {
            try {
                val client = draftApiClientProvider(rigUrl)
                val body = json.encodeToString(
                    AiAssistOverlayRequestDto(
                        mode = "generate_overlay_brief",
                        text = cleanTopic,
                        llmProvider = wireProvider,
                        llmModel = wireModel
                    )
                )
                val baseUrl = client.resolveActiveBaseUrl(forceRefresh = false)
                try {
                    executeOverlayPost(baseUrl, body, stage, providerLabel, wireModel, stageCfg.provider.isLocal)
                } catch (e: IOException) {
                    val refreshed = client.resolveActiveBaseUrl(forceRefresh = true)
                    if (refreshed == baseUrl) throw e
                    executeOverlayPost(refreshed, body, stage, providerLabel, wireModel, stageCfg.provider.isLocal)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Result.failure(
                    LlmRoutingException(
                        stage = stage,
                        provider = providerLabel,
                        isTransportFailure = true,
                        message = e.message ?: "Mac rig unreachable for Newsroom overlay.",
                        cause = e
                    )
                )
            } catch (e: Throwable) {
                Result.failure(
                    LlmRoutingException(
                        stage = stage,
                        provider = providerLabel,
                        isTransportFailure = false,
                        message = e.message ?: "Newsroom overlay request failed.",
                        cause = e
                    )
                )
            }
        }
    }

    private fun executeOverlayPost(
        baseUrl: String,
        body: String,
        stage: LlmStage,
        providerLabel: String,
        wireModel: String?,
        isLocal: Boolean
    ): Result<RoutedOverlayBriefResult> {
        val req = Request.Builder()
            .url("$baseUrl/v1/ai-assist")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            val rawBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errInfo = DraftApiClient.parseServerErrorInfoStatic(json, rawBody)
                val failedProvider = errInfo.provider ?: providerLabel
                return Result.failure(
                    LlmRoutingException(
                        stage = stage,
                        provider = failedProvider,
                        isTransportFailure = false,
                        statusCode = resp.code,
                        serverStage = errInfo.stage,
                        errorCode = errInfo.errorCode,
                        message = "POST /v1/ai-assist failed (HTTP ${resp.code}, provider=$failedProvider)" +
                            if (errInfo.detail.isNotBlank()) ": ${errInfo.detail}" else ""
                    )
                )
            }
            if (rawBody.isBlank()) {
                return Result.failure(
                    LlmRoutingException(
                        stage = stage,
                        provider = providerLabel,
                        isTransportFailure = false,
                        message = "POST /v1/ai-assist returned an empty response body."
                    )
                )
            }
            val dto = json.decodeFromString<AiAssistOverlayResponseDto>(rawBody)
            val headline = dto.headline.trim()
            if (headline.isBlank()) {
                return Result.failure(
                    LlmRoutingException(
                        stage = stage,
                        provider = dto.llmMeta?.provider?.ifBlank { null } ?: providerLabel,
                        isTransportFailure = false,
                        message = "POST /v1/ai-assist returned an empty overlay headline."
                    )
                )
            }
            val brief = GeneratedOverlayBriefResult(
                kind = OverlayObjectKind.fromRaw(dto.kind),
                headline = headline,
                subline = dto.subline.trim(),
                calloutStat = dto.calloutStat.trim(),
                durationSec = dto.durationSec.coerceIn(2, 10)
            )
            val meta = dto.llmMeta ?: LlmMetaDto(
                provider = providerLabel,
                model = wireModel.orEmpty(),
                isLocal = isLocal
            )
            return Result.success(RoutedOverlayBriefResult(brief = brief, llmMeta = meta))
        }
    }

    suspend fun fetchRigProviders(
        rigUrlOverride: String? = null
    ): Result<LlmProvidersResponse> = withContext(Dispatchers.IO) {
        try {
            val settings = profileStore.current()
            val rigUrl = resolveRigUrl(rigUrlOverride, settings.tailscaleRigUrl)
            val client = draftApiClientProvider(rigUrl)
            val baseUrl = client.resolveActiveBaseUrl(forceRefresh = false)
            try {
                executeFetchRigProviders(baseUrl)
            } catch (e: IOException) {
                val refreshed = client.resolveActiveBaseUrl(forceRefresh = true)
                if (refreshed == baseUrl) throw e
                executeFetchRigProviders(refreshed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun executeFetchRigProviders(baseUrl: String): Result<LlmProvidersResponse> {
        val req = Request.Builder()
            .url("$baseUrl/v1/llm/providers")
            .get()
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            val rawBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errInfo = DraftApiClient.parseServerErrorInfoStatic(json, rawBody)
                return Result.failure(
                    DraftApiClient.HttpStatusException(
                        statusCode = resp.code,
                        message = "GET /v1/llm/providers failed (HTTP ${resp.code})" +
                            if (errInfo.detail.isNotBlank()) ": ${errInfo.detail}" else "",
                        provider = errInfo.provider,
                        stage = errInfo.stage,
                        errorCode = errInfo.errorCode
                    )
                )
            }
            if (rawBody.isBlank()) {
                return Result.failure(IOException("GET /v1/llm/providers returned an empty body."))
            }
            return Result.success(json.decodeFromString<LlmProvidersResponse>(rawBody))
        }
    }

    suspend fun pushRigStageConfig(
        stages: Map<String, LlmStageBindingDto>,
        providerUrls: Map<String, String>? = null,
        rigUrlOverride: String? = null
    ): Result<LlmConfigUpdateResponse> = withContext(Dispatchers.IO) {
        try {
            val settings = profileStore.current()
            val rigUrl = resolveRigUrl(rigUrlOverride, settings.tailscaleRigUrl)
            val client = draftApiClientProvider(rigUrl)
            val payload = buildJsonObject {
                if (stages.isNotEmpty()) {
                    putJsonObject("stages") {
                        for ((stageName, binding) in stages) {
                            putJsonObject(stageName) {
                                put("provider", binding.provider.trim())
                                val cleanModel = binding.model?.trim()?.ifBlank { null }
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
            val payloadString = payload.toString()
            val baseUrl = client.resolveActiveBaseUrl(forceRefresh = false)
            try {
                executePushRigStageConfig(baseUrl, payloadString)
            } catch (e: IOException) {
                val refreshed = client.resolveActiveBaseUrl(forceRefresh = true)
                if (refreshed == baseUrl) throw e
                executePushRigStageConfig(refreshed, payloadString)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun executePushRigStageConfig(
        baseUrl: String,
        payloadString: String
    ): Result<LlmConfigUpdateResponse> {
        val req = Request.Builder()
            .url("$baseUrl/v1/llm/config")
            .post(payloadString.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            val rawBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errInfo = DraftApiClient.parseServerErrorInfoStatic(json, rawBody)
                return Result.failure(
                    DraftApiClient.HttpStatusException(
                        statusCode = resp.code,
                        message = "POST /v1/llm/config failed (HTTP ${resp.code})" +
                            if (errInfo.detail.isNotBlank()) ": ${errInfo.detail}" else "",
                        provider = errInfo.provider,
                        stage = errInfo.stage,
                        errorCode = errInfo.errorCode
                    )
                )
            }
            if (rawBody.isBlank()) {
                return Result.failure(IOException("POST /v1/llm/config returned an empty body."))
            }
            return Result.success(json.decodeFromString<LlmConfigUpdateResponse>(rawBody))
        }
    }

    suspend fun pushStageConfig(
        stage: LlmStage,
        config: LlmStageConfig,
        rigUrlOverride: String? = null
    ): Result<LlmConfigUpdateResponse> {
        val wireProvider = config.resolveWireProviderId()
            ?: return Result.failure(
                IllegalArgumentException("${config.provider.id} does not map to a Mac rig provider.")
            )
        var model = config.normalizedModelOverride()
        if (model == null && wireProvider != LlmProviderChoice.LOCAL_MAC_MLX.id) {
            val providersSnapshot = fetchRigProviders(rigUrlOverride).getOrNull()
            model = providersSnapshot?.providers
                ?.firstOrNull { it.id == wireProvider }
                ?.defaultModel
                ?.trim()
                ?.ifBlank { null }
        }
        val serverStages = SERVER_STAGES_FOR_GROUP[stage].orEmpty()
        val bindings = serverStages.associateWith {
            LlmStageBindingDto(provider = wireProvider, model = model)
        }
        return pushRigStageConfig(stages = bindings, rigUrlOverride = rigUrlOverride)
    }

    private suspend fun <T> executeStagePass(
        stage: LlmStage,
        rigUrlOverride: String?,
        onDevice: suspend () -> Result<T>,
        onRig: suspend (client: DraftApiClient, wireProvider: String?, wireModel: String?) -> T
    ): T {
        val settings = profileStore.current()
        val stageCfg = settings.llmRouting.forStage(stage)
        if (stageCfg.provider == LlmProviderChoice.LOCAL_AICORE_NANO) {
            val res = onDevice()
            return res.getOrElse { err ->
                if (err is CancellationException) throw err
                throw LlmRoutingException(
                    stage = stage,
                    provider = LlmProviderChoice.LOCAL_AICORE_NANO.id,
                    isTransportFailure = false,
                    message = err.message ?: "On-device Gemini Nano failed for ${stage.name}.",
                    cause = err
                )
            }
        }

        val rigUrl = resolveRigUrl(rigUrlOverride, settings.tailscaleRigUrl)
        val wireProvider = stageCfg.resolveWireProviderId()
        val wireModel = stageCfg.normalizedModelOverride()
        val providerLabel = wireProvider ?: LlmProviderChoice.MAC_RIG_DEFAULT.id

        return try {
            val client = draftApiClientProvider(rigUrl)
            onRig(client, wireProvider, wireModel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DraftApiClient.HttpStatusException) {
            throw LlmRoutingException(
                stage = stage,
                provider = e.provider ?: providerLabel,
                isTransportFailure = false,
                statusCode = e.statusCode,
                serverStage = e.stage,
                errorCode = e.errorCode,
                message = e.message ?: "Mac rig returned HTTP ${e.statusCode}.",
                cause = e
            )
        } catch (e: IOException) {
            throw LlmRoutingException(
                stage = stage,
                provider = providerLabel,
                isTransportFailure = true,
                message = e.message ?: "Mac rig unreachable.",
                cause = e
            )
        }
    }

    private fun resolveRigUrl(overrideUrl: String?, persistedUrl: String): String =
        overrideUrl?.trim()?.ifBlank { null }
            ?: persistedUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val SERVER_STAGES_FOR_GROUP: Map<LlmStage, List<String>> = mapOf(
            LlmStage.DRAFTING to listOf("draft.angles", "draft.arc"),
            LlmStage.SCRIPT_EDIT to listOf(
                "draft.synthesize",
                "draft.synthesize_script",
                "draft.brush_swap",
                "draft.seam_heal",
                "draft.density",
                "draft.rewrite"
            ),
            LlmStage.NEWSROOM_OVERLAY to listOf("ai_assist")
        )
    }
}
