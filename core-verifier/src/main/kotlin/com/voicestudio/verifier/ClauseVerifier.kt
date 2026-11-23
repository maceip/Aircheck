package com.voicestudio.verifier

import ai.onnxruntime.OrtEnvironment
import com.voicestudio.edl.CandidateTake
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.GateFailureRecord
import com.voicestudio.edl.GateMetrics
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VerificationGateThresholds
import com.voicestudio.edl.WordToken
import com.voicestudio.edl.ZERO_CROSSING_SCAN_SAMPLES
import kotlinx.serialization.json.Json
import java.io.File

data class VerifierModelsConfig(
    val sherpaCtcModelDir: String,
    val weSpeakerOnnxPath: String,
    val dnsmosP808OnnxPath: String,
    val masterCentroidBinPath: String
)

data class RawCandidateInput(
    val takeId: String,
    val pcm44k: FloatArray,
    val discreteAudioTokens: IntArray,
    val seed: Long,
    val temperature: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawCandidateInput) return false
        return takeId == other.takeId &&
            pcm44k.contentEquals(other.pcm44k) &&
            discreteAudioTokens.contentEquals(other.discreteAudioTokens) &&
            seed == other.seed &&
            temperature == other.temperature
    }

    override fun hashCode(): Int {
        var result = takeId.hashCode()
        result = 31 * result + pcm44k.contentHashCode()
        result = 31 * result + discreteAudioTokens.contentHashCode()
        result = 31 * result + seed.hashCode()
        result = 31 * result + temperature.hashCode()
        return result
    }
}

data class CandidateVerificationResult(
    val metrics: GateMetrics,
    val tokens: List<WordToken>,
    val failureReason: String?,
    val tailHfSpectralFlux: Float,
    val tailHfGlitchDetected: Boolean,
    val compositeScore: Float
)

data class ClauseVerificationBatchResult(
    val updatedClause: ClauseNode,
    val clauseJsonFile: File,
    val survivingTakes: List<CandidateTake>,
    val rejectedFailures: List<GateFailureRecord>,
    val allCandidateOutcomes: Map<String, CandidateVerificationResult>
)

/**
 * Milestone 2 (`:core-verifier`) 4-Gate Verification Orchestrator.
 *
 * Evaluates candidate 44.1 kHz Float32 PCM buffers in RAM across all 4 rectified gates:
 * 1. CTC Forced Alignment (`cer == 0.0f`, `maxPhonemeDurationMs <= 380.0f`) + word spans
 * 2. WeSpeaker ResNet34 (`wespeaker_resnet34.onnx`) L2-normalized cosine similarity `>= 0.86f`
 *    against the 256-float `master_centroid.bin`
 * 3. Microsoft DNSMOS p808 (`dnsmos_p808.onnx`) `>= 4.10f` + final-80ms `tailRmsDb <= -45.0f` dBFS
 *    and high-frequency tail spectral-flux glitch detection
 * 4. English syllable cadence in `4.2f..6.1f` syllables/sec
 *
 * Scans `+/- 88` samples around each aligned word boundary for zero crossings (`|pcm| < 0.005f`)
 * and YIN F0 pitch-energy peaks, ranks surviving takes by composite quality score, and persists
 * `ClauseNode.json` + the top 3 64-byte-aligned headerless `.pcm` files atomically to disk.
 *
 * Constitutional Law 2: Missing or corrupt ONNX/CTC/centroid files return `Result.failure(...)`
 * cleanly — NEVER fabricates passing `GateMetrics`.
 */
class ClauseVerifier(
    masterCentroid: FloatArray,
    private val ctcEngine: CtcAlignerEngine,
    private val speakerEngine: SpeakerEmbeddingEngine,
    private val dnsmosEngine: DnsmosP808Engine,
    private val useNativeDsp: Boolean = true,
    private var nativeHandle: Long = 0L
) : AutoCloseable {

    private val normalizedMasterCentroid: FloatArray
    private var closed: Boolean = false

    init {
        require(masterCentroid.size == AudioDspMetrics.SPEAKER_EMBEDDING_DIM) {
            "masterCentroid must have exactly ${AudioDspMetrics.SPEAKER_EMBEDDING_DIM} floats, got ${masterCentroid.size}"
        }
        var normSq = 0.0
        for (v in masterCentroid) {
            require(v.isFinite()) { "masterCentroid contains non-finite value" }
            normSq += v.toDouble() * v.toDouble()
        }
        val norm = kotlin.math.sqrt(normSq)
        require(norm > 1e-12) { "masterCentroid must be non-zero" }
        normalizedMasterCentroid = FloatArray(masterCentroid.size) { i ->
            (masterCentroid[i].toDouble() / norm).toFloat()
        }
    }

    /**
     * Evaluates a single 44.1 kHz Float32 candidate buffer against all 4 gates in RAM.
     */
    fun verifyCandidate(
        pcm44k: FloatArray,
        expectedText: String,
        thresholds: VerificationGateThresholds = VerificationGateThresholds()
    ): Result<CandidateVerificationResult> {
        if (closed) {
            return Result.failure(IllegalStateException("ClauseVerifier has been closed"))
        }
        if (pcm44k.isEmpty()) {
            return Result.failure(IllegalArgumentException("pcm44k buffer must not be empty"))
        }
        if (expectedText.isBlank()) {
            return Result.failure(IllegalArgumentException("expectedText must not be blank"))
        }

        // Gate 1: CTC Forced Alignment
        val ctcOut = ctcEngine.align(pcm44k, expectedText).getOrElse {
            return Result.failure(IllegalStateException("Gate 1 CTC aligner failed: ${it.message}", it))
        }
        val cer = AudioDspMetrics.computeCer(expectedText, ctcOut.recognizedText)
        val maxPhonemeDurationMs = ctcOut.maxPhonemeDurationMs

        // Gate 2: WeSpeaker ResNet34 cosine similarity vs 256-float master centroid
        val embedding = speakerEngine.extractEmbedding(pcm44k).getOrElse {
            return Result.failure(IllegalStateException("Gate 2 WeSpeaker failed: ${it.message}", it))
        }
        if (embedding.size != AudioDspMetrics.SPEAKER_EMBEDDING_DIM) {
            return Result.failure(
                IllegalStateException(
                    "Gate 2 WeSpeaker returned ${embedding.size} dims, expected ${AudioDspMetrics.SPEAKER_EMBEDDING_DIM}"
                )
            )
        }
        val speakerCosineSim = AudioDspMetrics.cosineSimilarity(embedding, normalizedMasterCentroid)

        // Gate 3a: Microsoft DNSMOS p808
        val dnsmosP808 = dnsmosEngine.scoreAudio(pcm44k).getOrElse {
            return Result.failure(IllegalStateException("Gate 3 DNSMOS p808 failed: ${it.message}", it))
        }
        if (!dnsmosP808.isFinite()) {
            return Result.failure(IllegalStateException("Gate 3 DNSMOS p808 returned non-finite score"))
        }

        // Gate 3b + Zero-Crossing (+/- 88 samples) + YIN F0 Peak Scanner (native C++ on Android, parity DSP on JVM)
        val (tailAnalysis, alignedTokens) = scanTailAndWordBoundaries(pcm44k, ctcOut.wordSpans)

        // Gate 4: Deterministic English syllable cadence over active speech span
        val activeSpeechSamples = if (alignedTokens.isNotEmpty()) {
            (alignedTokens.last().endSample - alignedTokens.first().startSample)
                .takeIf { it > 0L } ?: pcm44k.size.toLong()
        } else {
            pcm44k.size.toLong()
        }
        val syllablesPerSec = AudioDspMetrics.computeSyllablesPerSec(
            expectedText = expectedText,
            activeSpeechSamples = activeSpeechSamples,
            sampleRateHz = SYNTHESIS_SAMPLE_RATE_HZ
        )

        val baseMetrics = thresholds.evaluate(
            cer = cer,
            maxPhonemeDurationMs = maxPhonemeDurationMs,
            speakerCosineSim = speakerCosineSim,
            dnsmosP808 = dnsmosP808,
            tailRmsDb = tailAnalysis.tailRmsDb,
            syllablesPerSec = syllablesPerSec
        )

        val failures = mutableListOf<String>()
        if (cer > thresholds.maxCer) failures.add("SKIPPED_WORD_OR_CER")
        if (maxPhonemeDurationMs > thresholds.maxPhonemeDurationMs) failures.add("DRAGGED_PHONEME")
        if (speakerCosineSim < thresholds.minSpeakerCosineSim) failures.add("SPEAKER_SIMILARITY_LOW")
        if (dnsmosP808 < thresholds.minDnsmosP808) failures.add("DNSMOS_LOW")
        if (tailAnalysis.tailRmsDb > thresholds.maxTailRmsDb) failures.add("TAIL_RMS_EXCEEDED")
        if (tailAnalysis.hfGlitchDetected) failures.add("TAIL_HF_GLITCH")
        if (syllablesPerSec !in thresholds.minSyllablesPerSec..thresholds.maxSyllablesPerSec) {
            failures.add("CADENCE_OUT_OF_BAND")
        }

        val passedAllGates = baseMetrics.passedAllGates && !tailAnalysis.hfGlitchDetected && failures.isEmpty()
        val finalMetrics = baseMetrics.copy(passedAllGates = passedAllGates)
        val compositeScore = computeCompositeScore(finalMetrics)

        return Result.success(
            CandidateVerificationResult(
                metrics = finalMetrics,
                tokens = alignedTokens,
                failureReason = failures.takeIf { it.isNotEmpty() }?.joinToString("|"),
                tailHfSpectralFlux = tailAnalysis.tailHfSpectralFlux,
                tailHfGlitchDetected = tailAnalysis.hfGlitchDetected,
                compositeScore = compositeScore
            )
        )
    }

    /**
     * Evaluates all candidate takes for a [ClauseNode] in RAM, hard-rejects any candidate failing
     * any of the 4 gates, ranks passing takes by composite score (favoring `speakerCosineSim` and
     * `dnsmosP808`), and atomically writes the top [maxSurvivingTakes] (default 3) 64-byte-aligned
     * `.pcm` files plus `ClauseNode.json` to [outputDir].
     */
    fun evaluateAndPersistClause(
        clause: ClauseNode,
        candidates: List<RawCandidateInput>,
        outputDir: File,
        thresholds: VerificationGateThresholds = VerificationGateThresholds(),
        maxSurvivingTakes: Int = DEFAULT_MAX_SURVIVING_TAKES,
        clauseJsonFileName: String = DEFAULT_CLAUSE_JSON_FILENAME
    ): Result<ClauseVerificationBatchResult> {
        if (closed) {
            return Result.failure(IllegalStateException("ClauseVerifier has been closed"))
        }
        if (candidates.isEmpty()) {
            return Result.failure(IllegalArgumentException("No candidate takes supplied for clause ${clause.clauseId}"))
        }
        if (maxSurvivingTakes <= 0) {
            return Result.failure(IllegalArgumentException("maxSurvivingTakes must be > 0"))
        }

        val outcomesByTakeId = linkedMapOf<String, CandidateVerificationResult>()
        val passingPairs = mutableListOf<Pair<RawCandidateInput, CandidateVerificationResult>>()
        val rejectedFailures = mutableListOf<GateFailureRecord>()

        for (candidate in candidates) {
            val outcome = verifyCandidate(
                pcm44k = candidate.pcm44k,
                expectedText = clause.rawText,
                thresholds = thresholds
            ).getOrElse { err ->
                return Result.failure(
                    IllegalStateException("Verification aborted on take '${candidate.takeId}': ${err.message}", err)
                )
            }
            outcomesByTakeId[candidate.takeId] = outcome

            if (outcome.metrics.passedAllGates) {
                passingPairs.add(candidate to outcome)
            } else {
                rejectedFailures.add(
                    GateFailureRecord(
                        seed = candidate.seed,
                        cer = outcome.metrics.cer,
                        reason = outcome.failureReason ?: "GATE_REJECTED",
                        tokens = candidate.discreteAudioTokens.toList()
                    )
                )
            }
        }

        // Rank passing candidates descending by composite score (speakerCosineSim + dnsmosP808).
        val rankedSurvivors = passingPairs
            .sortedWith(
                compareByDescending<Pair<RawCandidateInput, CandidateVerificationResult>> { it.second.compositeScore }
                    .thenByDescending { it.second.metrics.speakerCosineSim }
                    .thenByDescending { it.second.metrics.dnsmosP808 }
                    .thenBy { it.second.metrics.tailRmsDb }
            )
            .take(maxSurvivingTakes)

        outputDir.mkdirs()
        val persistedTakes = mutableListOf<CandidateTake>()
        for ((rawTake, outcome) in rankedSurvivors) {
            val pcmFile = File(outputDir, "${rawTake.takeId}_44k_f32.pcm")
            val totalSamples = runCatching {
                AudioDspMetrics.writeAlignedPcmFile(rawTake.pcm44k, pcmFile)
            }.getOrElse { err ->
                return Result.failure(err)
            }
            persistedTakes.add(
                CandidateTake(
                    takeId = rawTake.takeId,
                    pcmFilePath = pcmFile.absolutePath,
                    totalSamples = totalSamples,
                    discreteAudioTokens = rawTake.discreteAudioTokens.copyOf(),
                    seed = rawTake.seed,
                    temperature = rawTake.temperature,
                    metrics = outcome.metrics,
                    tokens = outcome.tokens
                )
            )
        }

        val updatedClause = clause.copy(
            selectedTakeIndex = 0,
            candidates = persistedTakes
        )
        val clauseJsonFile = File(outputDir, clauseJsonFileName)
        val writeJsonResult = runCatching {
            writeAtomicTextFile(
                clauseJsonFile,
                prettyJson.encodeToString(ClauseNode.serializer(), updatedClause)
            )
        }
        if (writeJsonResult.isFailure) {
            return Result.failure(writeJsonResult.exceptionOrNull()!!)
        }

        return Result.success(
            ClauseVerificationBatchResult(
                updatedClause = updatedClause,
                clauseJsonFile = clauseJsonFile,
                survivingTakes = persistedTakes,
                rejectedFailures = rejectedFailures,
                allCandidateOutcomes = outcomesByTakeId
            )
        )
    }

    private fun scanTailAndWordBoundaries(
        pcm44k: FloatArray,
        rawSpans: List<RawWordSpan>
    ): Pair<AudioDspMetrics.TailAnalysisResult, List<WordToken>> {
        if (useNativeDsp && VerifierJni.isNativeAvailable) {
            val starts = LongArray(rawSpans.size) { rawSpans[it].startSample }
            val ends = LongArray(rawSpans.size) { rawSpans[it].endSample }
            val nativeScan = VerifierJni.scanZeroCrossingsAndTailOrNull(pcm44k, starts, ends)
            if (nativeScan != null && nativeScan.boundaries.size == rawSpans.size) {
                val tail = AudioDspMetrics.TailAnalysisResult(
                    tailRmsDb = nativeScan.tailRmsDb,
                    tailPeakFrameRmsDb = nativeScan.tailPeakFrameRmsDb,
                    tailHfEnergyDb = nativeScan.tailHfEnergyDb,
                    tailHfSpectralFlux = nativeScan.tailHfSpectralFlux,
                    hfGlitchDetected = nativeScan.tailHfGlitchDetected
                )
                val tokens = rawSpans.indices.map { i ->
                    val b = nativeScan.boundaries[i]
                    WordToken(
                        word = rawSpans[i].word,
                        startSample = b.startSample,
                        endSample = b.endSample,
                        zeroCrossIn = b.zeroCrossIn,
                        zeroCrossOut = b.zeroCrossOut,
                        f0PeakSample = b.f0PeakSample,
                        videoAnchorId = null
                    )
                }
                return tail to tokens
            }
        }

        val tail = AudioDspMetrics.analyzeTail(pcm44k)
        val tokens = AudioDspMetrics.scanWordBoundaries(
            pcm44k = pcm44k,
            rawSpans = rawSpans,
            scanRadius = ZERO_CROSSING_SCAN_SAMPLES,
            sampleRateHz = SYNTHESIS_SAMPLE_RATE_HZ
        )
        return tail to tokens
    }

    override fun close() {
        if (closed) return
        closed = true
        if (nativeHandle != 0L && VerifierJni.isNativeAvailable) {
            runCatching { VerifierJni.nativeDestroyVerifier(nativeHandle) }
            nativeHandle = 0L
        }
        runCatching { ctcEngine.close() }
        runCatching { speakerEngine.close() }
        runCatching { dnsmosEngine.close() }
    }

    companion object {
        const val DEFAULT_MAX_SURVIVING_TAKES: Int = 3
        const val DEFAULT_CLAUSE_JSON_FILENAME: String = "ClauseNode.json"

        private val prettyJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        /**
         * Composite candidate ranking score matching SPEC / Tinker GRPO reward weighting:
         * strongly favors speaker identity (`speakerCosineSim`) plus acoustic quality (`dnsmosP808`).
         */
        fun computeCompositeScore(metrics: GateMetrics): Float {
            if (!metrics.passedAllGates) return Float.NEGATIVE_INFINITY
            val normalizedMos = (metrics.dnsmosP808 / 5.0f).coerceIn(0.0f, 1.0f)
            return 1.5f * metrics.speakerCosineSim + 0.5f * normalizedMos
        }

        /**
         * Production factory wiring real ONNX Runtime Android sessions (`wespeaker_resnet34.onnx`,
         * `dnsmos_p808.onnx`, `sherpa_ctc_model_dir`), loading the 256-float `master_centroid.bin`,
         * and initializing the C++20 native verifier handle on Android.
         *
         * Constitutional Law 2: Returns `Result.failure(...)` cleanly whenever any model file or
         * centroid is missing, unreadable, or corrupt — never fabricates passing metrics.
         */
        fun create(
            config: VerifierModelsConfig,
            useNativeDsp: Boolean = true
        ): Result<ClauseVerifier> {
            val ctcDir = File(config.sherpaCtcModelDir)
            val weSpeakerFile = File(config.weSpeakerOnnxPath)
            val dnsmosFile = File(config.dnsmosP808OnnxPath)
            val centroidFile = File(config.masterCentroidBinPath)

            // Validate all 4 artifacts before initializing ORT sessions.
            OrtCtcAlignerEngine.validateModelDir(ctcDir).onFailure { return Result.failure(it) }
            if (!weSpeakerFile.isFile || !weSpeakerFile.canRead() || weSpeakerFile.length() == 0L) {
                return Result.failure(
                    IllegalArgumentException("Missing WeSpeaker ONNX model: ${weSpeakerFile.absolutePath}")
                )
            }
            if (!dnsmosFile.isFile || !dnsmosFile.canRead() || dnsmosFile.length() == 0L) {
                return Result.failure(
                    IllegalArgumentException("Missing DNSMOS p808 ONNX model: ${dnsmosFile.absolutePath}")
                )
            }
            val centroid = AudioDspMetrics.loadMasterCentroid(centroidFile)
                .getOrElse { return Result.failure(it) }

            val env = runCatching { OrtEnvironment.getEnvironment() }
                .getOrElse { return Result.failure(IllegalStateException("Failed to init OrtEnvironment: ${it.message}", it)) }

            val ctcEngine = OrtCtcAlignerEngine.create(env, ctcDir)
                .getOrElse { return Result.failure(it) }
            val speakerEngine = OrtWeSpeakerEngine.create(env, weSpeakerFile).getOrElse { err ->
                ctcEngine.close()
                return Result.failure(err)
            }
            val dnsmosEngine = OrtDnsmosP808Engine.create(env, dnsmosFile).getOrElse { err ->
                speakerEngine.close()
                ctcEngine.close()
                return Result.failure(err)
            }

            var handle = 0L
            if (useNativeDsp && VerifierJni.isNativeAvailable) {
                handle = VerifierJni.nativeInitVerifier(
                    sherpaCtcModelDir = ctcDir.absolutePath,
                    weSpeakerOnnxPath = weSpeakerFile.absolutePath,
                    dnsmosP808OnnxPath = dnsmosFile.absolutePath,
                    masterCentroidBinPath = centroidFile.absolutePath
                )
                if (handle == 0L) {
                    dnsmosEngine.close()
                    speakerEngine.close()
                    ctcEngine.close()
                    return Result.failure(IllegalStateException("Native C++ VerifierGates::Init failed"))
                }
            }

            return Result.success(
                ClauseVerifier(
                    masterCentroid = centroid,
                    ctcEngine = ctcEngine,
                    speakerEngine = speakerEngine,
                    dnsmosEngine = dnsmosEngine,
                    useNativeDsp = useNativeDsp,
                    nativeHandle = handle
                )
            )
        }

        private fun writeAtomicTextFile(target: File, content: String) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                tmp.delete()
                throw IllegalStateException("Failed to replace existing file: ${target.absolutePath}")
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IllegalStateException("Atomic rename failed for file: ${target.absolutePath}")
            }
        }
    }
}
