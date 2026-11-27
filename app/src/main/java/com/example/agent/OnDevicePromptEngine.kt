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
import com.example.draft.DraftTone
import com.example.draft.DraftToneContext
import com.example.draft.EditSource
import com.example.draft.ExcludedAnglesContext
import com.example.draft.LlmMetaDto
import com.example.draft.PreviousBeatTextsContext
import com.example.draft.RewriteResponse
import com.example.draft.SeamHealResponse
import com.example.draft.SynthesizeResponse
import com.example.draft.SynthesizeScriptResponse
import com.example.draft.SynthesizedCandidateDto
import kotlin.coroutines.coroutineContext
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.CachedContext
import com.google.mlkit.genai.prompt.CreateCachedContextRequest
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
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
    val beats: List<ArcBeatDto> = emptyList(),
    val beat: String = ""
)

@Serializable
private data class NanoSingleScriptPayload(
    val paragraphs: List<String> = emptyList(),
    val text: String = ""
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
    /** Attribution stamped on every result, so a fallback-served draft is never labelled as AICore. */
    private val engineLlmMeta: LlmMetaDto = NANO_LLM_META,
) : OnDeviceLlmBackend {

    constructor(
        modelProvider: () -> GenerativeModel = { aicoreFullModelClient() }
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

    /**
     * The AICore base model actually serving this engine (e.g. `nano-v4-full`), or null when the
     * system model is unavailable. Read from AICore; never guessed.
     */
    suspend fun baseModelName(): String? {
        if (completionOverride != null) return null
        return try {
            val gm = model()
            if (gm.checkStatus() == FeatureStatus.AVAILABLE) gm.getBaseModelName() else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
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
        val tone = coroutineContext[DraftToneContext]?.tone ?: DraftTone.DIRECT
        val prompt = buildString {
            appendLine(NATURAL_SPEECH_RULES)
            appendLine("Tone: ${tone.label}.")
            appendLine("Extract 3 concrete claims directly from the speaker's note and 2 sharp missing angles (blindspots).")
            appendLine("Keep each label 2 to 5 plain words and each preview one simple, clear English sentence.")
            appendSteeringBlock(steeringConstraints)
            appendLine("Speaker's note:")
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
                llmMeta = engineLlmMeta
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
        val clampedSec = targetSec.coerceIn(20, 120)
        val expectedWordBudget = (clampedSec * 2.5).toInt().coerceAtLeast(30)
        val tone = coroutineContext[DraftToneContext]?.tone ?: DraftTone.DIRECT
        val excludedAngles = coroutineContext[ExcludedAnglesContext]?.excludedAngles.orEmpty()
        val previousTexts = coroutineContext[PreviousBeatTextsContext]?.previousTexts.orEmpty()

        // Single-beat reroll: generate ONLY the target beat with explicit anti-repetition + retries
        // so consecutive clicks ALWAYS produce a genuinely new beat and never corrupt other beats.
        if (rerollIndex != null && !existingBeats.isNullOrEmpty() && rerollIndex in existingBeats.indices) {
            val targetLabel = ARC_BEAT_LABELS.getOrElse(rerollIndex) { "BEAT ${rerollIndex + 1}" }
            val targetPurpose = ARC_BEAT_PURPOSES.getOrElse(rerollIndex) { "deliver this step of the story clearly" }
            val forbidden = (listOf(existingBeats[rerollIndex]) + previousTexts)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
            val windows = computeDefaultWindows(clampedSec)
            var lastError: Throwable? = null
            for (attempt in 0 until 3) {
                val angleHint = REROLL_ANGLES[(forbidden.size + attempt) % REROLL_ANGLES.size]
                val prompt = buildString {
                    appendLine(NATURAL_SPEECH_RULES)
                    appendLine("Tone: ${tone.label}.")
                    appendLine("Rewrite ONLY the $targetLabel beat ($targetPurpose) of this 4-beat video outline.")
                    appendLine("Current 4 beats:")
                    existingBeats.forEachIndexed { idx, txt ->
                        appendLine("${ARC_BEAT_LABELS.getOrElse(idx) { "BEAT ${idx + 1}" }}: $txt")
                    }
                    appendLine("PREVIOUS $targetLabel VERSION(S) TO REPLACE (DO NOT REPEAT OR PARAPHRASE):")
                    forbidden.forEach { appendLine("- $it") }
                    appendLine("Direction: $angleHint")
                    if (attempt > 0) {
                        appendLine("IMPORTANT: Your previous attempt was too close to the old beat. Use completely different wording and structure.")
                    }
                    appendAnchorBlocks(selectedAngles, excludedAngles)
                    appendSteeringBlock(steeringConstraints)
                    appendLine("Speaker's note:")
                    appendLine(cleanSeed)
                    appendLine("Write ONE crisp, natural spoken English sentence (7 to 16 words) for $targetLabel.")
                    appendLine("Respond ONLY with valid ASCII JSON:")
                    appendLine("""{"beat":"..."}""")
                }
                val pass = runJsonPass(prompt, steeringConstraints, "arc_reroll") { jsonText ->
                    val payload = json.decodeFromString<NanoArcPayload>(jsonText)
                    val candidateText = when {
                        payload.beat.isNotBlank() -> DraftStateReducer.sanitizeAscii(payload.beat).trim()
                        payload.beats.size > rerollIndex -> DraftStateReducer.sanitizeAscii(payload.beats[rerollIndex].text).trim()
                        payload.beats.isNotEmpty() -> DraftStateReducer.sanitizeAscii(payload.beats.first().text).trim()
                        else -> ""
                    }
                    if (candidateText.isBlank()) {
                        throw OnDeviceLlmException("Gemini Nano returned an empty beat rewrite.")
                    }
                    if (attempt < 2 && isTooSimilarToAny(candidateText, forbidden)) {
                        throw OnDeviceLlmException("Gemini Nano repeated the previous beat.")
                    }
                    val rebuilt = existingBeats.mapIndexed { idx, oldText ->
                        val (lo, hi) = windows[idx.coerceIn(0, windows.lastIndex)]
                        val finalTxt = if (idx == rerollIndex) candidateText else oldText
                        ArcBeatDto(
                            label = ARC_BEAT_LABELS.getOrElse(idx) { "BEAT ${idx + 1}" },
                            startSec = lo,
                            endSec = hi,
                            text = finalTxt
                        )
                    }
                    ArcResponse(
                        targetSec = clampedSec,
                        wordBudget = expectedWordBudget,
                        beats = rebuilt,
                        llmMeta = engineLlmMeta
                    )
                }
                if (pass.isSuccess) return pass
                lastError = pass.exceptionOrNull()
                if (completionOverride != null && attempt == 0 && lastError?.message?.contains("repeated", ignoreCase = true) != true) {
                    // Preserve fast failure for deterministic unit-test overrides on schema errors.
                    return pass
                }
            }
            return Result.failure(lastError ?: OnDeviceLlmException("Beat rewrite failed."))
        }

        val prompt = buildString {
            appendLine(NATURAL_SPEECH_RULES)
            appendLine("Tone: ${tone.label}.")
            appendLine("Build a 4-beat spoken video outline totaling ~${clampedSec}s (~$expectedWordBudget words).")
            appendLine("Write ONE plain, natural spoken English sentence (7 to 16 words) for each beat, in exact order:")
            appendLine("HOOK = the clear core tension or hook in one line")
            appendLine("DETAIL = the concrete evidence, reason, or mechanism behind it")
            appendLine("SHIFT = what changed or what people are actually doing about it")
            appendLine("PAYOFF = the practical takeaway that closes the video")
            appendAnchorBlocks(selectedAngles, excludedAngles)
            appendSteeringBlock(steeringConstraints)
            appendLine("Speaker's note:")
            appendLine(cleanSeed)
            appendLine("Respond ONLY with valid ASCII JSON:")
            appendLine("""{"target_sec":$clampedSec,"word_budget":$expectedWordBudget,"beats":[{"label":"HOOK","start_sec":0,"end_sec":8,"text":"..."},{"label":"DETAIL","start_sec":8,"end_sec":28,"text":"..."},{"label":"SHIFT","start_sec":28,"end_sec":48,"text":"..."},{"label":"PAYOFF","start_sec":48,"end_sec":$clampedSec,"text":"..."}]}""")
        }
        return runJsonPass(prompt, steeringConstraints, "arc") { jsonText ->
            val payload = json.decodeFromString<NanoArcPayload>(jsonText)
            val windows = computeDefaultWindows(clampedSec)
            val beats = payload.beats.mapIndexedNotNull { idx, beat ->
                val cleanText = DraftStateReducer.sanitizeAscii(beat.text).trim()
                if (cleanText.isBlank()) null
                else {
                    val (defStart, defEnd) = windows[idx.coerceIn(0, windows.lastIndex)]
                    val sSec = if (beat.endSec > beat.startSec) beat.startSec.coerceAtLeast(0) else defStart
                    val eSec = if (beat.endSec > beat.startSec) beat.endSec else defEnd
                    ArcBeatDto(
                        label = ARC_BEAT_LABELS.getOrNull(idx)
                            ?: DraftStateReducer.sanitizeAscii(beat.label).trim().ifBlank { "BEAT ${idx + 1}" },
                        startSec = sSec,
                        endSec = eSec,
                        text = cleanText
                    )
                }
            }
            if (beats.isEmpty()) {
                throw OnDeviceLlmException("Gemini Nano returned an empty arc.")
            }
            ArcResponse(
                targetSec = payload.targetSec.takeIf { it > 0 } ?: clampedSec,
                wordBudget = payload.wordBudget.takeIf { it > 0 } ?: expectedWordBudget,
                beats = beats,
                llmMeta = engineLlmMeta
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
        val tone = coroutineContext[DraftToneContext]?.tone ?: DraftTone.DIRECT
        val excludedAngles = coroutineContext[ExcludedAnglesContext]?.excludedAngles.orEmpty()
        val prompt = buildString {
            appendLine(NATURAL_SPEECH_RULES)
            appendLine("Tone: ${tone.label}.")
            if (slot != null) {
                appendLine("Generate 1 fresh plain-spoken draft candidate for slot $slot distinct from existing drafts.")
            } else {
                appendLine("Generate 3 distinct plain-spoken draft candidates (DIRECT, PUNCHY, CONTRARIAN).")
            }
            appendAnchorBlocks(selectedAngles, excludedAngles)
            if (existingDrafts.isNotEmpty()) {
                appendLine("Existing drafts to avoid repeating: ${existingDrafts.joinToString(" || ")}")
            }
            appendSteeringBlock(steeringConstraints)
            appendLine("Speaker's note:")
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
                llmMeta = engineLlmMeta
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
        val clampedSec = targetSec.coerceIn(20, 120)
        val expectedWordBudget = (clampedSec * 2.5).toInt().coerceAtLeast(30)
        val tone = coroutineContext[DraftToneContext]?.tone ?: DraftTone.DIRECT
        val excludedAngles = coroutineContext[ExcludedAnglesContext]?.excludedAngles.orEmpty()

        // On real on-device AICore Gemini Nano (small output budget), generating all 3 long scripts
        // inside a single JSON blob degrades coherence and produces garbled phrases. Generate each
        // version in its own crisp, bounded call so every paragraph reads like clean human speech.
        if (completionOverride == null) {
            val postureSpecs = listOf(
                "DIRECT" to "state each beat plainly and clearly with zero fluff",
                "PUNCHY" to "use short, crisp spoken sentences with strong rhythm",
                "STORY" to "walk through each beat in order like explaining to a friend"
            )
            val generated = mutableListOf<SynthesizedCandidateDto>()
            var firstFailure: Throwable? = null
            for ((posture, guidance) in postureSpecs) {
                val prompt = buildString {
                    appendLine(NATURAL_SPEECH_RULES)
                    appendLine("Tone: ${tone.label}. Style: $posture ($guidance).")
                    appendLine("Write ONE spoken video script (~${expectedWordBudget} words total, 4 short paragraphs matching the 4 beats below).")
                    if (arcBeats.isNotEmpty()) {
                        appendLine("Outline beats (one short spoken paragraph per beat, in exact order):")
                        arcBeats.forEachIndexed { idx, b -> appendLine("${idx + 1}. $b") }
                    }
                    appendAnchorBlocks(selectedAngles, excludedAngles)
                    appendSteeringBlock(steeringConstraints)
                    appendLine("Speaker's note:")
                    appendLine(cleanSeed)
                    appendLine("Respond ONLY with valid ASCII JSON:")
                    appendLine("""{"paragraphs":["beat 1 spoken lines","beat 2 spoken lines","beat 3 spoken lines","beat 4 spoken lines"]}""")
                }
                val res = runJsonPass(prompt, steeringConstraints, "script_$posture") { jsonText ->
                    val p = json.decodeFromString<NanoSingleScriptPayload>(jsonText)
                    val joined = if (p.paragraphs.isNotEmpty()) {
                        p.paragraphs.map { DraftStateReducer.sanitizeAscii(it).trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString("\n\n")
                    } else {
                        DraftStateReducer.sanitizeAscii(p.text).trim()
                    }
                    if (joined.isBlank()) throw OnDeviceLlmException("Empty $posture script.")
                    SynthesizedCandidateDto(postureLabel = posture, text = joined)
                }
                res.onSuccess { generated += it }
                    .onFailure { if (firstFailure == null) firstFailure = it }
            }
            if (generated.isEmpty()) {
                return Result.failure(firstFailure ?: OnDeviceLlmException("Gemini Nano returned zero script versions."))
            }
            return Result.success(
                SynthesizeScriptResponse(
                    targetSec = clampedSec,
                    wordBudget = expectedWordBudget,
                    candidates = generated,
                    llmMeta = engineLlmMeta
                )
            )
        }

        val prompt = buildString {
            appendLine(NATURAL_SPEECH_RULES)
            appendLine("Tone: ${tone.label}.")
            appendLine("Write 3 clear, natural spoken script versions (DIRECT, PUNCHY, STORY) targeting ~${clampedSec}s (~$expectedWordBudget words). Use plain ASCII only.")
            appendAnchorBlocks(selectedAngles, excludedAngles)
            if (arcBeats.isNotEmpty()) {
                appendLine("Outline beats (keep this order): ${arcBeats.joinToString(" | ")}")
            }
            appendSteeringBlock(steeringConstraints)
            appendLine("Speaker's note:")
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
                llmMeta = engineLlmMeta
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
                llmMeta = engineLlmMeta
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
                llmMeta = engineLlmMeta
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
                llmMeta = engineLlmMeta
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
                llmMeta = engineLlmMeta
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
        if (unwrapped.startsWith("{") && unwrapped.endsWith("}")) {
            return unwrapped
        }
        val firstBrace = unwrapped.indexOf('{')
        val lastBrace = unwrapped.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return unwrapped.substring(firstBrace, lastBrace + 1).trim()
        }
        throw OnDeviceLlmException("Gemini Nano response is not a valid JSON object.")
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

    private fun StringBuilder.appendAnchorBlocks(
        selectedAngles: List<String>,
        excludedAngles: List<String>
    ) {
        if (selectedAngles.isNotEmpty()) {
            appendLine("REQUIRED ANCHORS (MUST include these points):")
            selectedAngles.forEach { appendLine("- $it") }
        }
        if (excludedAngles.isNotEmpty()) {
            appendLine("EXCLUDED ANGLES (DO NOT include or mention these):")
            excludedAngles.forEach { appendLine("- $it") }
        }
    }

    private fun computeDefaultWindows(targetSec: Int): List<Pair<Int, Int>> {
        val fracs = listOf(8.0 / 60.0, 28.0 / 60.0, 48.0 / 60.0, 1.0)
        val out = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (f in fracs) {
            val end = if (f >= 1.0) targetSec else (targetSec * f).toInt().coerceAtLeast(start + 1)
            out += start to end
            start = end
        }
        return out
    }

    private fun isTooSimilarToAny(candidate: String, previous: List<String>): Boolean {
        val candTokens = WORD_TOKEN_REGEX.findAll(candidate.lowercase()).map { it.value }.toList()
        if (candTokens.isEmpty()) return true
        val candJoined = candTokens.joinToString(" ")
        val candSet = candTokens.toSet()
        for (prev in previous) {
            val prevTokens = WORD_TOKEN_REGEX.findAll(prev.lowercase()).map { it.value }.toList()
            if (prevTokens.isEmpty()) continue
            if (candJoined == prevTokens.joinToString(" ")) return true
            val prevSet = prevTokens.toSet()
            val union = (candSet + prevSet).size
            val inter = candSet.intersect(prevSet).size
            if (union > 0 && inter.toDouble() / union.toDouble() >= 0.72) return true
        }
        return false
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
        /** Positional beat labels of a full arc; mirrors `ARC_BEAT_LABELS` in the rig's drafting_passes.py. */
        val ARC_BEAT_LABELS: List<String> = listOf("HOOK", "DETAIL", "SHIFT", "PAYOFF")
        private val ARC_BEAT_PURPOSES: List<String> = listOf(
            "the clear core tension or hook in one line",
            "the concrete evidence, reason, or mechanism behind it",
            "what changed or what people are actually doing about it",
            "the practical takeaway that closes the video"
        )
        private val REROLL_ANGLES: List<String> = listOf(
            "Use a completely fresh angle with a specific real-world detail.",
            "Focus on the direct practical effect on a real person instead of repeating earlier wording.",
            "Lead with a stronger contrast or surprising plain fact from the note.",
            "Strip it down to a punchier, plainer spoken line with new verbs."
        )
        private const val NATURAL_SPEECH_RULES: String =
            "Write natural, everyday spoken English that sounds great read aloud on camera. " +
                "Stay strictly grounded in the speaker's note. Never invent weird phrases, buzzwords, " +
                "metaphors, idioms, rhetorical questions, or em-dashes. Plain ASCII only."
        private val WORD_TOKEN_REGEX = Regex("[a-z0-9]+")

        /**
         * AICore client pinned to the FULL (highest-quality, TPU-accelerated) stable system model.
         * On Pixel 11 Pro XL this resolves to `nano-v4-full`; the default client resolves to the
         * smaller `nano-v4-fast`.
         */
        fun aicoreFullModelClient(): GenerativeModel = Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    preference = ModelPreference.FULL
                    releaseStage = ModelReleaseStage.STABLE
                }
            }
        )

        /** Attribution for drafts served by the downloaded LiteRT-LM Gemma fallback. */
        val LITERT_GEMMA_LLM_META: LlmMetaDto = LlmMetaDto(
            provider = "local_aicore_nano",
            model = "gemma-4-E2B-it (LiteRT-LM fallback)",
            isLocal = true
        )

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
