package com.example.agent

import com.example.data.OverlayObjectKind
import com.example.data.gateway.GeneratedOverlayBriefResult
import com.example.draft.AngleDto
import com.example.draft.AnglesResponse
import com.example.draft.ArcBeatDto
import com.example.draft.ArcResponse
import com.example.draft.BrushNudge
import com.example.draft.BrushSwapResponse
import com.example.draft.DensityResponse
import com.example.draft.DraftApiClient
import com.example.draft.DraftStateReducer
import com.example.draft.EditSource
import com.example.draft.LlmMetaDto
import com.example.draft.RewriteResponse
import com.example.draft.SeamHealResponse
import com.example.draft.SynthesizeResponse
import com.example.draft.SynthesizeScriptResponse
import com.example.draft.SynthesizedCandidateDto
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.CachedContext
import com.google.mlkit.genai.prompt.CreateCachedContextRequest
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Typed structured output schema compiled by `com.google.mlkit:genai-schema-compiler` for
 * on-device Gemini Nano broadcast packaging & angle extraction.
 */
@Generable
data class StructuredBroadcastPackaging(
    @Guide(description = "Punchy broadcast lower-third chyron headline, max 36 characters")
    var chyronHeadline: String = "",

    @Guide(description = "Concise broadcast subline or source attribution, max 48 characters")
    var chyronSubline: String = "",

    @Guide(description = "Sharpest angle label extracted from the spoken seed, 2 to 4 words")
    var primaryAngleLabel: String = ""
)

data class OnDevicePromptAvailability(
    val featureStatus: Int,
    val isModelAvailable: Boolean,
    val isStructuredOutputSupported: Boolean,
    val isPrefixCachingSupported: Boolean
)

class OnDeviceLlmException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

interface OnDeviceLlmBackend : AutoCloseable {
    suspend fun checkAvailability(): OnDevicePromptAvailability

    suspend fun generateBroadcastPackaging(
        draftPlainText: String
    ): Result<StructuredBroadcastPackaging>

    suspend fun generateOverlayBrief(
        briefTopic: String
    ): Result<GeneratedOverlayBriefResult>

    suspend fun extractAngles(
        seedTranscript: String,
        steeringConstraints: List<String> = emptyList()
    ): Result<AnglesResponse>

    suspend fun arc(
        seedTranscript: String,
        steeringConstraints: List<String> = emptyList(),
        selectedAngles: List<String> = emptyList(),
        targetSec: Int = 60,
        rerollIndex: Int? = null,
        existingBeats: List<String>? = null
    ): Result<ArcResponse>

    suspend fun synthesize(
        seedTranscript: String,
        selectedAngles: List<String>,
        steeringConstraints: List<String> = emptyList(),
        slot: Int? = null,
        existingDrafts: List<String> = emptyList()
    ): Result<SynthesizeResponse>

    suspend fun synthesizeScript(
        seedTranscript: String,
        selectedAngles: List<String> = emptyList(),
        steeringConstraints: List<String> = emptyList(),
        arcBeats: List<String> = emptyList(),
        targetSec: Int = 60
    ): Result<SynthesizeScriptResponse>

    suspend fun brushSwap(
        markedText: String,
        steeringConstraints: List<String> = emptyList(),
        nudge: BrushNudge? = null,
        excludeAlternatives: List<String> = emptyList()
    ): Result<BrushSwapResponse>

    suspend fun seamHeal(
        markedText: String,
        operation: String,
        steeringConstraints: List<String> = emptyList()
    ): Result<SeamHealResponse>

    suspend fun adjustDensity(
        markedText: String,
        deltaPercent: Int,
        steeringConstraints: List<String> = emptyList()
    ): Result<DensityResponse>

    suspend fun rewrite(
        text: String,
        mode: String = "seed_clean",
        steeringConstraints: List<String> = emptyList()
    ): Result<RewriteResponse>
}

@Serializable
private data class NanoAnglesPayload(
    @SerialName("extracted_angles") val extractedAngles: List<AngleDto> = emptyList(),
    @SerialName("blindspots") val blindspots: List<AngleDto> = emptyList()
)

@Serializable
private data class NanoArcPayload(
    @SerialName("target_sec") val targetSec: Int = 0,
    @SerialName("word_budget") val wordBudget: Int = 0,
    val beats: List<ArcBeatDto> = emptyList()
)

@Serializable
private data class NanoSynthesizePayload(
    val candidates: List<SynthesizedCandidateDto> = emptyList()
)

@Serializable
private data class NanoSynthesizeScriptPayload(
    @SerialName("target_sec") val targetSec: Int = 0,
    @SerialName("word_budget") val wordBudget: Int = 0,
    val candidates: List<SynthesizedCandidateDto> = emptyList()
)

@Serializable
private data class NanoBrushSwapPayload(
    val alternatives: List<String> = emptyList()
)

@Serializable
private data class NanoTextPayload(
    val text: String = ""
)

@Serializable
private data class NanoOverlayBriefPayload(
    val kind: String = "LOWER_THIRD",
    val headline: String = "",
    val subline: String = "",
    @SerialName("callout_stat") val calloutStat: String = "",
    @SerialName("duration_sec") val durationSec: Int = 4
)

/**
 * ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`) wrapper for AICore Gemini Nano.
 *
 * Constitutional Law 2 compliance:
 * - Strictly probes `GenerativeModel.checkStatus() == FeatureStatus.AVAILABLE` before claiming
 *   on-device readiness or running inference.
 * - Never fabricates synthetic headlines, angles, scripts, or fallback data when AICore Gemini
 *   Nano is absent (`UNAVAILABLE`) or fails validation.
 * - Releases `GenerativeModel.close()` cleanly on `close()`.
 */
class OnDevicePromptEngine internal constructor(
    private val modelProvider: () -> GenerativeModel,
    private val completionOverride: (suspend (prompt: String) -> String)?,
    private val availabilityOverride: (suspend () -> OnDevicePromptAvailability)? = null,
) : OnDeviceLlmBackend {

    constructor(
        modelProvider: () -> GenerativeModel = { Generation.getClient() }
    ) : this(modelProvider = modelProvider, completionOverride = null, availabilityOverride = null)

    @Volatile
    private var cachedModel: GenerativeModel? = null
    @Volatile
    private var activePrefixCacheName: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Synchronized
    private fun model(): GenerativeModel {
        val existing = cachedModel
        if (existing != null) return existing
        return modelProvider().also { cachedModel = it }
    }

    override suspend fun checkAvailability(): OnDevicePromptAvailability {
        availabilityOverride?.let { return it() }
        return try {
            val gm = model()
            val status = gm.checkStatus()
            val available = status == FeatureStatus.AVAILABLE
            val structured = if (available) gm.isStructuredOutputFeatureAvailable() else false
            val caching = if (available) gm.isCachingFeatureAvailable() else false
            OnDevicePromptAvailability(
                featureStatus = status,
                isModelAvailable = available,
                isStructuredOutputSupported = structured,
                isPrefixCachingSupported = caching
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: GenAiException) {
            OnDevicePromptAvailability(
                featureStatus = FeatureStatus.UNAVAILABLE,
                isModelAvailable = false,
                isStructuredOutputSupported = false,
                isPrefixCachingSupported = false
            )
        } catch (_: Throwable) {
            OnDevicePromptAvailability(
                featureStatus = FeatureStatus.UNAVAILABLE,
                isModelAvailable = false,
                isStructuredOutputSupported = false,
                isPrefixCachingSupported = false
            )
        }
    }

    /**
     * Caches a >200-word studio style/steering prefix on AICore when prefix caching is supported.
     */
    suspend fun cacheLongSteeringPrefixIfEligible(
        cacheKey: String,
        prefixText: String
    ): CachedContext? {
        val wordCount = prefixText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        if (wordCount <= 200) return null
        val availability = checkAvailability()
        if (!availability.isModelAvailable || !availability.isPrefixCachingSupported) return null

        return try {
            val gm = model()
            val req = CreateCachedContextRequest.Builder(
                cacheKey,
                PromptPrefix(prefixText)
            ).build()
            gm.caches.create(req).also { created ->
                activePrefixCacheName = created.name
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Generates a `@Generable` [StructuredBroadcastPackaging] strictly when AICore Gemini Nano and
     * Structured Output are verified `AVAILABLE` on-device. Returns `Result.failure` honestly otherwise.
     */
    override suspend fun generateBroadcastPackaging(
        draftPlainText: String
    ): Result<StructuredBroadcastPackaging> {
        val clean = draftPlainText.trim()
        if (clean.isEmpty()) {
            return Result.failure(IllegalArgumentException("draftPlainText must not be empty."))
        }
        val availability = checkAvailability()
        if (!availability.isModelAvailable || !availability.isStructuredOutputSupported) {
            return Result.failure(
                IllegalStateException(
                    "AICore Gemini Nano structured output is not installed/available on this device (status=${availability.featureStatus})."
                )
            )
        }
        return try {
            val gm = model()
            val prompt = "Extract broadcast lower-third packaging for this script:\n$clean"
            val baseRequest = GenerateContentRequest.Builder(TextPart(prompt)).build()
            val typedRequest = generateTypedContentRequest(baseRequest, StructuredBroadcastPackaging::class)
            val response = gm.generateContent(typedRequest)
            val parsed = response.candidates.firstOrNull()?.response
                ?: return Result.failure(IllegalStateException("Gemini Nano returned no structured candidates."))
            Result.success(parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun generateOverlayBrief(
        briefTopic: String
    ): Result<GeneratedOverlayBriefResult> {
        val clean = briefTopic.trim()
        if (clean.isEmpty()) {
            return Result.failure(OnDeviceLlmException("briefTopic must not be empty."))
        }
        val availability = checkAvailability()
        if (!availability.isModelAvailable) {
            return Result.failure(
                OnDeviceLlmException(
                    "AICore Gemini Nano is not available on this device (status=${availability.featureStatus})."
                )
            )
        }
        if (completionOverride != null) {
            return runJsonPass(
                prompt = buildOverlayBriefPrompt(clean),
                steeringConstraints = emptyList(),
                cacheKey = "overlay_brief"
            ) { jsonText ->
                val payload = json.decodeFromString<NanoOverlayBriefPayload>(jsonText)
                val headline = DraftStateReducer.sanitizeAscii(payload.headline).trim().uppercase().take(40)
                if (headline.isBlank()) {
                    throw OnDeviceLlmException("Gemini Nano returned an empty overlay headline.")
                }
                GeneratedOverlayBriefResult(
                    kind = OverlayObjectKind.fromRaw(payload.kind),
                    headline = headline,
                    subline = DraftStateReducer.sanitizeAscii(payload.subline).trim().take(56),
                    calloutStat = DraftStateReducer.sanitizeAscii(payload.calloutStat).trim().take(24),
                    durationSec = payload.durationSec.coerceIn(2, 10)
                )
            }
        }
        val pkgResult = generateBroadcastPackaging(clean)
        return pkgResult.fold(
            onSuccess = { pkg ->
                val headline = DraftStateReducer.sanitizeAscii(pkg.chyronHeadline).trim().uppercase().take(40)
                if (headline.isBlank()) {
                    Result.failure(OnDeviceLlmException("Gemini Nano returned an empty overlay headline."))
                } else {
                    Result.success(
                        GeneratedOverlayBriefResult(
                            kind = OverlayObjectKind.LOWER_THIRD,
                            headline = headline,
                            subline = DraftStateReducer.sanitizeAscii(pkg.chyronSubline).trim().take(56),
                            calloutStat = DraftStateReducer.sanitizeAscii(pkg.primaryAngleLabel).trim().take(24),
                            durationSec = 4
                        )
                    )
                }
            },
            onFailure = { err ->
                Result.failure(
                    OnDeviceLlmException(
                        err.message ?: "AICore Gemini Nano overlay generation failed.",
                        err
                    )
                )
            }
        )
    }

    override suspend fun extractAngles(
        seedTranscript: String,
        steeringConstraints: List<String>
    ): Result<AnglesResponse> {
        val cleanSeed = DraftStateReducer.sanitizeAscii(seedTranscript).trim()
        if (cleanSeed.isEmpty()) {
            return Result.failure(OnDeviceLlmException("seed_transcript must not be blank."))
        }
        val prompt = buildString {
            appendLine("Extract 3 sharp editorial angles and 2 blindspots from the seed transcript.")
            appendSteeringBlock(steeringConstraints)
            appendLine("Seed transcript:")
            appendLine(cleanSeed)
            appendLine("Respond ONLY with valid ASCII JSON:")
            appendLine("""{"extracted_angles":[{"id":"ang_1","label":"...","preview":"..."}],"blindspots":[{"id":"bs_1","label":"...","preview":"..."}]}""")
        }
        return runJsonPass(prompt, steeringConstraints, "angles") { jsonText ->
            val payload = json.decodeFromString<NanoAnglesPayload>(jsonText)
            val extracted = payload.extractedAngles.mapIndexedNotNull { idx, item ->
                val label = DraftStateReducer.sanitizeAscii(item.label).trim()
                if (label.isBlank()) null
                else AngleDto(
                    id = item.id.trim().ifBlank { "ang_${idx + 1}" },
                    label = label,
                    preview = DraftStateReducer.sanitizeAscii(item.preview).trim()
                )
            }
            if (extracted.isEmpty()) {
                throw OnDeviceLlmException("Gemini Nano returned no extracted angles.")
            }
            val blindspots = payload.blindspots.mapIndexedNotNull { idx, item ->
                val label = DraftStateReducer.sanitizeAscii(item.label).trim()
                if (label.isBlank()) null
                else AngleDto(
                    id = item.id.trim().ifBlank { "bs_${idx + 1}" },
                    label = label,
                    preview = DraftStateReducer.sanitizeAscii(item.preview).trim()
                )
            }
            AnglesResponse(
                extractedAngles = extracted,
                blindspots = blindspots,
                llmMeta = NANO_LLM_META
            )
        }
    }

    override suspend fun arc(
        seedTranscript: String,
        steeringConstraints: List<String>,
        selectedAngles: List<String>,
        targetSec: Int,
        rerollIndex: Int?,
        existingBeats: List<String>?
    ): Result<ArcResponse> {
        val cleanSeed = DraftStateReducer.sanitizeAscii(seedTranscript).trim()
        if (cleanSeed.isEmpty()) {
            return Result.failure(OnDeviceLlmException("seed_transcript must not be blank."))
        }
        val clampedSec = targetSec.coerceIn(10, 90)
        val expectedWordBudget = (clampedSec * 2.5).toInt().coerceAtLeast(30)
        val prompt = buildString {
            if (rerollIndex != null && !existingBeats.isNullOrEmpty()) {
                appendLine("Rewrite beat index $rerollIndex of this 4-beat ${clampedSec}s broadcast arc while keeping other beats coherent.")
                appendLine("Existing beats: ${existingBeats.joinToString(" | ")}")
            } else {
                appendLine("Build a 4-beat spoken broadcast arc totaling ~${clampedSec}s (~$expectedWordBudget words).")
            }
            if (selectedAngles.isNotEmpty()) {
                appendLine("Selected angles: ${selectedAngles.joinToString("; ")}")
            }
            appendSteeringBlock(steeringConstraints)
            appendLine("Seed transcript:")
            appendLine(cleanSeed)
            appendLine("Respond ONLY with valid ASCII JSON:")
            appendLine("""{"target_sec":$clampedSec,"word_budget":$expectedWordBudget,"beats":[{"label":"HOOK","start_sec":0,"end_sec":15,"text":"..."}]}""")
        }
        return runJsonPass(prompt, steeringConstraints, "arc") { jsonText ->
            val payload = json.decodeFromString<NanoArcPayload>(jsonText)
            val beats = payload.beats.mapIndexedNotNull { idx, beat ->
                val cleanText = DraftStateReducer.sanitizeAscii(beat.text).trim()
                if (cleanText.isBlank()) null
                else ArcBeatDto(
                    label = DraftStateReducer.sanitizeAscii(beat.label).trim().ifBlank { "BEAT ${idx + 1}" },
                    startSec = beat.startSec.coerceAtLeast(0),
                    endSec = beat.endSec.coerceAtLeast(beat.startSec + 1),
                    text = cleanText
                )
            }
            if (beats.isEmpty()) {
                throw OnDeviceLlmException("Gemini Nano returned an empty arc.")
            }
            ArcResponse(
                targetSec = payload.targetSec.takeIf { it > 0 } ?: clampedSec,
                wordBudget = payload.wordBudget.takeIf { it > 0 } ?: expectedWordBudget,
                beats = beats,
                llmMeta = NANO_LLM_META
            )
        }
    }

    override suspend fun synthesize(
        seedTranscript: String,
        selectedAngles: List<String>,
        steeringConstraints: List<String>,
        slot: Int?,
        existingDrafts: List<String>
    ): Result<SynthesizeResponse> {
        val cleanSeed = DraftStateReducer.sanitizeAscii(seedTranscript).trim()
        if (cleanSeed.isEmpty()) {
            return Result.failure(OnDeviceLlmException("seed_transcript must not be blank."))
        }
        if (slot != null && slot !in 0..2) {
            return Result.failure(IllegalArgumentException("slot must be null or in 0..2, got $slot"))
        }
        val prompt = buildString {
            if (slot != null) {
                appendLine("Generate 1 fresh broadcast draft candidate for slot $slot distinct from existing drafts.")
            } else {
                appendLine("Generate 3 distinct spoken broadcast draft candidates (DIRECT, PUNCHY, CONTRARIAN).")
            }
            if (selectedAngles.isNotEmpty()) {
                appendLine("Selected angles: ${selectedAngles.joinToString("; ")}")
            }
            if (existingDrafts.isNotEmpty()) {
                appendLine("Existing drafts to avoid repeating: ${existingDrafts.joinToString(" || ")}")
            }
            appendSteeringBlock(steeringConstraints)
            appendLine("Seed transcript:")
            appendLine(cleanSeed)
            appendLine("Respond ONLY with valid ASCII JSON:")
            appendLine("""{"candidates":[{"posture_label":"DIRECT","text":"..."}]}""")
        }
        return runJsonPass(prompt, steeringConstraints, "synthesize") { jsonText ->
            val payload = json.decodeFromString<NanoSynthesizePayload>(jsonText)
            val candidates = payload.candidates.mapIndexedNotNull { idx, c ->
                val text = DraftStateReducer.sanitizeAscii(c.text).trim()
                if (text.isBlank()) null
                else SynthesizedCandidateDto(
                    postureLabel = DraftStateReducer.sanitizeAscii(c.postureLabel).trim()
                        .ifBlank { DEFAULT_POSTURES.getOrElse(idx) { "DRAFT ${idx + 1}" } },
                    text = text
                )
            }
            if (candidates.isEmpty()) {
                throw OnDeviceLlmException("Gemini Nano returned zero draft candidates.")
            }
            SynthesizeResponse(
                candidates = candidates,
                llmMeta = NANO_LLM_META
            )
        }
    }

    override suspend fun synthesizeScript(
        seedTranscript: String,
        selectedAngles: List<String>,
        steeringConstraints: List<String>,
        arcBeats: List<String>,
        targetSec: Int
    ): Result<SynthesizeScriptResponse> {
        val cleanSeed = DraftStateReducer.sanitizeAscii(seedTranscript).trim()
        if (cleanSeed.isEmpty()) {
            return Result.failure(OnDeviceLlmException("seed_transcript must not be blank."))
        }
        val clampedSec = targetSec.coerceIn(10, 90)
        val expectedWordBudget = (clampedSec * 2.5).toInt().coerceAtLeast(30)
        val prompt = buildString {
            appendLine("Write 3 spoken newscast script versions (DIRECT, PUNCHY, STORY) targeting ~${clampedSec}s (~$expectedWordBudget words). Use plain ASCII only.")
            if (selectedAngles.isNotEmpty()) {
                appendLine("Selected angles: ${selectedAngles.joinToString("; ")}")
            }
            if (arcBeats.isNotEmpty()) {
                appendLine("Arc beats: ${arcBeats.joinToString(" | ")}")
            }
            appendSteeringBlock(steeringConstraints)
            appendLine("Seed transcript:")
            appendLine(cleanSeed)
            appendLine("Respond ONLY with valid ASCII JSON:")
            appendLine("""{"target_sec":$clampedSec,"word_budget":$expectedWordBudget,"candidates":[{"posture_label":"DIRECT","text":"..."},{"posture_label":"PUNCHY","text":"..."},{"posture_label":"STORY","text":"..."}]}""")
        }
        return runJsonPass(prompt, steeringConstraints, "synthesize_script") { jsonText ->
            val payload = json.decodeFromString<NanoSynthesizeScriptPayload>(jsonText)
            val candidates = payload.candidates.mapIndexedNotNull { idx, c ->
                val text = DraftStateReducer.sanitizeAscii(c.text).trim()
                if (text.isBlank()) null
                else SynthesizedCandidateDto(
                    postureLabel = DraftStateReducer.sanitizeAscii(c.postureLabel).trim()
                        .ifBlank { DEFAULT_POSTURES.getOrElse(idx) { "DRAFT ${idx + 1}" } },
                    text = text
                )
            }
            if (candidates.isEmpty()) {
                throw OnDeviceLlmException("Gemini Nano returned zero script candidates.")
            }
            SynthesizeScriptResponse(
                targetSec = payload.targetSec.takeIf { it > 0 } ?: clampedSec,
                wordBudget = payload.wordBudget.takeIf { it > 0 } ?: expectedWordBudget,
                candidates = candidates,
                llmMeta = NANO_LLM_META
            )
        }
    }

    override suspend fun brushSwap(
        markedText: String,
        steeringConstraints: List<String>,
        nudge: BrushNudge?,
        excludeAlternatives: List<String>
    ): Result<BrushSwapResponse> {
        if (markedText.isBlank()) {
            return Result.failure(OnDeviceLlmException("marked_text must not be blank."))
        }
        val prompt = buildString {
            appendLine("Provide 3 concise replacement alternatives ONLY for the phrase wrapped in <replace>...</replace>.")
            if (nudge != null) {
                appendLine("Tone nudge: ${nudge.wire}.")
            }
            if (excludeAlternatives.isNotEmpty()) {
                appendLine("Exclude alternatives: ${excludeAlternatives.joinToString(", ")}")
            }
            appendSteeringBlock(steeringConstraints)
            appendLine("Marked script:")
            appendLine(markedText)
            appendLine("Respond ONLY with valid ASCII JSON:")
            appendLine("""{"alternatives":["option 1","option 2","option 3"]}""")
        }
        return runJsonPass(prompt, steeringConstraints, "brush_swap") { jsonText ->
            val payload = json.decodeFromString<NanoBrushSwapPayload>(jsonText)
            val alts = payload.alternatives
                .map { DraftStateReducer.sanitizeAscii(it).trim() }
                .filter { it.isNotBlank() }
            if (alts.isEmpty()) {
                throw OnDeviceLlmException("Gemini Nano returned zero brush-swap alternatives.")
            }
            BrushSwapResponse(
                alternatives = alts,
                nudge = nudge?.wire,
                llmMeta = NANO_LLM_META
            )
        }
    }

    override suspend fun seamHeal(
        markedText: String,
        operation: String,
        steeringConstraints: List<String>
    ): Result<SeamHealResponse> {
        if (markedText.isBlank()) {
            return Result.failure(OnDeviceLlmException("marked_text must not be blank."))
        }
        if (operation !in DraftApiClient.VALID_SEAM_HEAL_OPERATIONS) {
            return Result.failure(
                IllegalArgumentException(
                    "Invalid seam-heal operation '$operation'. Must be one of ${DraftApiClient.VALID_SEAM_HEAL_OPERATIONS}."
                )
            )
        }
        val prompt = buildString {
            appendLine("Smooth sentence transitions after '$operation' without altering any substring inside <lock>...</lock>.")
            appendSteeringBlock(steeringConstraints)
            appendLine("Marked script:")
            appendLine(markedText)
            appendLine("Respond ONLY with valid ASCII JSON containing the full plain-text script without tags:")
            appendLine("""{"text":"..."}""")
        }
        return runJsonPass(prompt, steeringConstraints, "seam_heal") { jsonText ->
            val payload = json.decodeFromString<NanoTextPayload>(jsonText)
            val verifiedText = enforceLockParityAndSanitize(
                originalMarkedText = markedText,
                candidateOutput = payload.text,
                operationLabel = "seam_heal"
            )
            SeamHealResponse(
                text = verifiedText,
                lockParityPreserved = true,
                llmMeta = NANO_LLM_META
            )
        }
    }

    override suspend fun adjustDensity(
        markedText: String,
        deltaPercent: Int,
        steeringConstraints: List<String>
    ): Result<DensityResponse> {
        if (markedText.isBlank()) {
            return Result.failure(OnDeviceLlmException("marked_text must not be blank."))
        }
        val clampedDelta = deltaPercent.coerceIn(DraftApiClient.MIN_DENSITY_DELTA, DraftApiClient.MAX_DENSITY_DELTA)
        val prompt = buildString {
            appendLine("Adjust spoken script length by $clampedDelta% while preserving every substring inside <lock>...</lock> verbatim.")
            appendSteeringBlock(steeringConstraints)
            appendLine("Marked script:")
            appendLine(markedText)
            appendLine("Respond ONLY with valid ASCII JSON containing the full plain-text script without tags:")
            appendLine("""{"text":"..."}""")
        }
        return runJsonPass(prompt, steeringConstraints, "density") { jsonText ->
            val payload = json.decodeFromString<NanoTextPayload>(jsonText)
            val verifiedText = enforceLockParityAndSanitize(
                originalMarkedText = markedText,
                candidateOutput = payload.text,
                operationLabel = "density"
            )
            DensityResponse(
                text = verifiedText,
                lockParityPreserved = true,
                llmMeta = NANO_LLM_META
            )
        }
    }

    override suspend fun rewrite(
        text: String,
        mode: String,
        steeringConstraints: List<String>
    ): Result<RewriteResponse> {
        if (text.isBlank()) {
            return Result.failure(OnDeviceLlmException("text must not be blank."))
        }
        val prompt = buildString {
            appendLine("Rewrite and sharpen the text for mode '$mode' in plain spoken ASCII. Preserve any substring inside <lock>...</lock> verbatim.")
            appendSteeringBlock(steeringConstraints)
            appendLine("Input text:")
            appendLine(text)
            appendLine("Respond ONLY with valid ASCII JSON:")
            appendLine("""{"text":"..."}""")
        }
        return runJsonPass(prompt, steeringConstraints, "rewrite") { jsonText ->
            val payload = json.decodeFromString<NanoTextPayload>(jsonText)
            val verifiedText = enforceLockParityAndSanitize(
                originalMarkedText = text,
                candidateOutput = payload.text,
                operationLabel = "rewrite"
            )
            RewriteResponse(
                text = verifiedText,
                lockParityPreserved = true,
                llmMeta = NANO_LLM_META
            )
        }
    }

    private suspend fun <T> runJsonPass(
        prompt: String,
        steeringConstraints: List<String>,
        cacheKey: String,
        decode: (String) -> T
    ): Result<T> {
        val availability = checkAvailability()
        if (!availability.isModelAvailable) {
            return Result.failure(
                OnDeviceLlmException(
                    "AICore Gemini Nano is not installed/available on this device (status=${availability.featureStatus})."
                )
            )
        }
        return try {
            val steeringBlock = steeringConstraints.joinToString("\n")
            val cached = if (steeringBlock.isNotBlank() && completionOverride == null) {
                cacheLongSteeringPrefixIfEligible("aircheck_$cacheKey", steeringBlock)
            } else {
                null
            }
            val rawResponse = if (completionOverride != null) {
                completionOverride.invoke(prompt)
            } else {
                val gm = model()
                val reqBuilder = GenerateContentRequest.Builder(TextPart(prompt)).apply {
                    cached?.let { cachedContextName = it.name }
                }
                val response = gm.generateContent(reqBuilder.build())
                response.candidates.firstOrNull()?.text.orEmpty()
            }
            if (rawResponse.isBlank()) {
                return Result.failure(OnDeviceLlmException("Gemini Nano returned an empty response."))
            }
            val strictJson = extractStrictJsonObject(rawResponse)
            Result.success(decode(strictJson))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnDeviceLlmException) {
            Result.failure(e)
        } catch (e: GenAiException) {
            Result.failure(OnDeviceLlmException(e.message ?: "AICore GenAI failure.", e))
        } catch (e: Throwable) {
            Result.failure(OnDeviceLlmException(e.message ?: "Gemini Nano response validation failed.", e))
        }
    }

    private fun extractStrictJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }
        val openFenceIdx = trimmed.indexOf("```")
        val unwrapped = if (openFenceIdx >= 0 && trimmed.endsWith("```")) {
            val fenceRegion = trimmed.substring(openFenceIdx)
            val afterOpeningFence = fenceRegion.removePrefix("```")
            val bodyStart = FENCE_LANG_PREFIX_REGEX.find(afterOpeningFence)?.range?.last?.plus(1) ?: 0
            val closingIdx = afterOpeningFence.lastIndexOf("```")
            if (closingIdx >= bodyStart) {
                afterOpeningFence.substring(bodyStart, closingIdx).trim()
            } else {
                trimmed
            }
        } else {
            trimmed
        }
        if (!unwrapped.startsWith("{") || !unwrapped.endsWith("}")) {
            throw OnDeviceLlmException("Gemini Nano response is not a valid JSON object.")
        }
        return unwrapped
    }

    private fun enforceLockParityAndSanitize(
        originalMarkedText: String,
        candidateOutput: String,
        operationLabel: String
    ): String {
        val stripped = TAG_REGEX.replace(candidateOutput, "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
        if (stripped.isBlank()) {
            throw OnDeviceLlmException("Gemini Nano returned empty text for $operationLabel.")
        }
        val lockedSpans = LOCK_SPAN_REGEX.findAll(originalMarkedText)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()
        if (lockedSpans.isEmpty()) {
            return DraftStateReducer.sanitizeAscii(stripped).trim().also {
                if (it.isBlank()) throw OnDeviceLlmException("Gemini Nano returned blank text for $operationLabel.")
            }
        }

        val originalClauses = buildOriginalClausesFromMarkedText(originalMarkedText)
        val ascii = DraftStateReducer.sanitizeAscii(stripped).trim()
        val asciiParity = DraftStateReducer.verifyLockParity(
            originalClauses = originalClauses,
            mutatedText = ascii,
            source = EditSource.BRUSH_SWAP
        )
        if (asciiParity.preserved) {
            return ascii
        }
        val rawParity = DraftStateReducer.verifyLockParity(
            originalClauses = originalClauses,
            mutatedText = stripped,
            source = EditSource.BRUSH_SWAP
        )
        if (!rawParity.preserved) {
            throw OnDeviceLlmException(
                "Gemini Nano altered locked words (${rawParity.missingPhrases.joinToString()}) during $operationLabel."
            )
        }
        return stripped
    }

    private fun buildOriginalClausesFromMarkedText(
        originalMarkedText: String
    ): List<com.example.draft.ClauseNode> {
        val normalized = REPLACE_TAG_REGEX.replace(originalMarkedText, "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        val clauses = mutableListOf<com.example.draft.ClauseNode>()
        var cursor = 0

        fun appendSegment(segment: String, locked: Boolean) {
            if (segment.isEmpty()) return
            if (segment.isBlank()) {
                if (segment.contains("\n\n") && clauses.isNotEmpty()) {
                    val lastIdx = clauses.lastIndex
                    val lastClause = clauses[lastIdx]
                    val lastTokens = lastClause.activeVersion.tokens
                    if (lastTokens.isNotEmpty() && !lastTokens.last().trailingSpace.contains("\n\n")) {
                        val updatedTokens = lastTokens.dropLast(1) + lastTokens.last().copy(trailingSpace = "\n\n")
                        val updatedVersions = lastClause.versions.dropLast(1) +
                            lastClause.activeVersion.copy(tokens = updatedTokens)
                        clauses[lastIdx] = lastClause.copy(versions = updatedVersions)
                    }
                }
                return
            }
            val segClauses = DraftStateReducer.tokenizeIntoClauses(
                text = segment,
                source = EditSource.MODEL_INIT,
                lockedSubstrings = emptyList()
            )
            val endsWithParagraphBreak = segment.trimEnd(' ', '\t').endsWith("\n\n")
            segClauses.forEachIndexed { idx, clause ->
                val rawTokens = clause.activeVersion.tokens
                val mappedTokens = rawTokens.mapIndexed { tIdx, tok ->
                    val needsParagraphTrailing = endsWithParagraphBreak &&
                        idx == segClauses.lastIndex &&
                        tIdx == rawTokens.lastIndex &&
                        !tok.trailingSpace.contains("\n\n")
                    tok.copy(
                        verbatimLocked = locked,
                        trailingSpace = if (needsParagraphTrailing) "\n\n" else tok.trailingSpace
                    )
                }
                clauses += clause.copy(
                    id = "c_${clauses.size}",
                    versions = listOf(clause.activeVersion.copy(tokens = mappedTokens))
                )
            }
        }

        for (match in LOCK_SPAN_REGEX.findAll(normalized)) {
            if (match.range.first > cursor) {
                appendSegment(normalized.substring(cursor, match.range.first), locked = false)
            }
            appendSegment(match.groupValues[1], locked = true)
            cursor = match.range.last + 1
        }
        if (cursor < normalized.length) {
            appendSegment(normalized.substring(cursor), locked = false)
        }
        return clauses
    }

    private fun StringBuilder.appendSteeringBlock(steeringConstraints: List<String>) {
        if (steeringConstraints.isNotEmpty()) {
            appendLine("Steering constraints:")
            steeringConstraints.forEach { appendLine("- $it") }
        }
    }

    private fun buildOverlayBriefPrompt(topic: String): String =
        """Create a broadcast news overlay brief for: $topic
Respond ONLY with valid ASCII JSON:
{"kind":"LOWER_THIRD","headline":"MAX 40 CHARS ALL CAPS","subline":"MAX 56 CHARS","callout_stat":"MAX 24 CHARS","duration_sec":4}"""

    @Synchronized
    override fun close() {
        runCatching { cachedModel?.close() }
        cachedModel = null
        activePrefixCacheName = null
    }

    companion object {
        val NANO_LLM_META: LlmMetaDto = LlmMetaDto(
            provider = "local_aicore_nano",
            model = "gemini-nano",
            isLocal = true
        )

        private val DEFAULT_POSTURES = listOf("DIRECT", "PUNCHY", "STORY")
        private val TAG_REGEX = Regex("</?(?:lock|replace)>", RegexOption.IGNORE_CASE)
        private val REPLACE_TAG_REGEX = Regex("</?replace>", RegexOption.IGNORE_CASE)
        private val LOCK_SPAN_REGEX = Regex("<lock>(.*?)</lock>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val FENCE_LANG_PREFIX_REGEX = Regex("^[a-zA-Z0-9_-]*(?=\\s|\\{)\\s*")
    }
}
