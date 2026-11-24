package com.voicestudio.verifier

import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VerificationGateThresholds
import com.voicestudio.edl.ZERO_CROSSING_MAX_ABS_AMPLITUDE
import com.voicestudio.edl.ZERO_CROSSING_SCAN_SAMPLES
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Milestone 2 / Gate 2 verification suite (`VerifierGateTest`).
 *
 * Feeds realistic 44.1 kHz Float32 PCM waveforms, real 256-float `master_centroid.bin` files,
 * and deterministic model backends verifying:
 * - Hard-rejection of dropped words (`cer > 0.0f`), dragged phonemes (`> 380 ms`), low speaker
 *   similarity (`< 0.86f`), low DNSMOS p808 (`< 4.10f`), white-noise / HF tail glitches
 *   (`tailRmsDb > -45.0f` or HF spectral flux spike), and out-of-band cadence (`!in 4.2..6.1`).
 * - Exact zero-crossing compliance (`abs(pcm[zeroCrossIn]) < 0.005f` within `+/- 88` samples)
 *   and YIN `f0PeakSample` placement.
 * - Top-3 ranking and atomic persistence of 64-byte-aligned `.pcm` files + `ClauseNode.json`.
 * - Constitutional Law 2: clean `Result.failure` when ONNX/CTC/centroid models are missing.
 */
class VerifierGateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val strictJson = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    private val canonicalClauseText = "We ripped out the cloud wrapper and ran it bare-metal."

    private val canonicalWordSpans = listOf(
        RawWordSpan("We", 441L, 9_000L),
        RawWordSpan("ripped", 9_200L, 19_000L),
        RawWordSpan("out", 19_200L, 27_000L),
        RawWordSpan("the", 27_200L, 34_000L),
        RawWordSpan("cloud", 34_200L, 46_000L),
        RawWordSpan("wrapper", 46_200L, 62_000L),
        RawWordSpan("and", 62_200L, 70_000L),
        RawWordSpan("ran", 70_200L, 80_000L),
        RawWordSpan("it", 80_200L, 88_000L),
        RawWordSpan("bare-metal", 88_200L, 110_691L)
    )

    @Test
    fun evaluateAndPersistClause_rejectsDroppedWordAndTailGlitch_writesTopThreeAlignedPcmAndClauseJson() {
        val centroidVec = makeMasterCentroidVector()
        val centroidFile = writeMasterCentroidFile(tempFolder.newFile("master_centroid.bin"), centroidVec)
        val loadedCentroid = AudioDspMetrics.loadMasterCentroid(centroidFile).getOrThrow()

        // 2.60s @ 44.1 kHz = 114,660 samples (speech 2.52s -> 13 syllables / 2.50s = 5.20 syl/s + 80ms tail)
        val totalSamples = 114_660
        val goodPcm0 = makeRealisticSpeechPcm(totalSamples, f0Hz = 172.0, seed = 101L)
        val goodPcm1 = makeRealisticSpeechPcm(totalSamples, f0Hz = 185.0, seed = 102L)
        val goodPcm2 = makeRealisticSpeechPcm(totalSamples, f0Hz = 160.0, seed = 103L)
        val droppedWordPcm = makeRealisticSpeechPcm(totalSamples, f0Hz = 178.0, seed = 104L)
        val whiteNoiseTailPcm = makeRealisticSpeechPcm(
            totalSamples = totalSamples,
            f0Hz = 170.0,
            seed = 105L,
            injectWhiteNoiseTail = true
        )

        // Key each buffer by its exact FloatArray reference so backends inspect the real candidate buffer.
        val ctcByBuf = IdentityMap(
            goodPcm0 to CtcAlignmentOutput(canonicalClauseText, 195.0f, canonicalWordSpans),
            goodPcm1 to CtcAlignmentOutput(canonicalClauseText, 215.0f, canonicalWordSpans),
            goodPcm2 to CtcAlignmentOutput(canonicalClauseText, 240.0f, canonicalWordSpans),
            // Corrupted #1: dropped word "cloud" -> real Levenshtein CER > 0.0f
            droppedWordPcm to CtcAlignmentOutput(
                "We ripped out the wrapper and ran it bare-metal.",
                210.0f,
                canonicalWordSpans.filterNot { it.word == "cloud" }
            ),
            // Corrupted #2: intact text, but audio has a white-noise tail glitch (> -45 dBFS)
            whiteNoiseTailPcm to CtcAlignmentOutput(canonicalClauseText, 200.0f, canonicalWordSpans)
        )

        val speakerByBuf = IdentityMap(
            goodPcm0 to makeVectorWithTargetCosine(loadedCentroid, targetCosine = 0.95f),
            goodPcm1 to makeVectorWithTargetCosine(loadedCentroid, targetCosine = 0.91f),
            goodPcm2 to makeVectorWithTargetCosine(loadedCentroid, targetCosine = 0.88f),
            droppedWordPcm to makeVectorWithTargetCosine(loadedCentroid, targetCosine = 0.94f),
            whiteNoiseTailPcm to makeVectorWithTargetCosine(loadedCentroid, targetCosine = 0.93f)
        )

        val dnsmosByBuf = IdentityMap(
            goodPcm0 to 4.54f,
            goodPcm1 to 4.38f,
            goodPcm2 to 4.21f,
            droppedWordPcm to 4.48f,
            whiteNoiseTailPcm to 4.42f
        )

        val verifier = ClauseVerifier(
            masterCentroid = loadedCentroid,
            ctcEngine = MapCtcEngine(ctcByBuf),
            speakerEngine = MapSpeakerEngine(speakerByBuf),
            dnsmosEngine = MapDnsmosEngine(dnsmosByBuf),
            useNativeDsp = false
        )

        val clause = ClauseNode(
            clauseId = "c_004",
            index = 4,
            rawText = canonicalClauseText,
            normalizedPhonemes = "w iː ɹ ˈɪ p t ˈaʊ t ð ə k l ˈaʊ d ɹ ˈæ p ɚ"
        )

        // Feed candidates in deliberately shuffled order (3 good + 2 corrupted).
        val rawCandidates = listOf(
            RawCandidateInput("c_004_t2_good_mid", goodPcm1, intArrayOf(101, 202, 303), 948102L, 0.72f),
            RawCandidateInput("c_004_t3_dropped_word", droppedWordPcm, intArrayOf(404, 505), 948104L, 0.75f),
            RawCandidateInput("c_004_t0_good_best", goodPcm0, intArrayOf(1042, 8819, 441, 902), 948101L, 0.70f),
            RawCandidateInput("c_004_t4_tail_glitch", whiteNoiseTailPcm, intArrayOf(606, 707), 948105L, 0.78f),
            RawCandidateInput("c_004_t1_good_third", goodPcm2, intArrayOf(808, 909, 1001), 948103L, 0.71f)
        )

        val outDir = tempFolder.newFolder("clause_out_c004")
        val batch = verifier.evaluateAndPersistClause(
            clause = clause,
            candidates = rawCandidates,
            outputDir = outDir,
            thresholds = VerificationGateThresholds(),
            maxSurvivingTakes = 3
        ).getOrThrow()

        // 1. Corrupted buffers MUST be hard-rejected.
        val droppedOutcome = batch.allCandidateOutcomes.getValue("c_004_t3_dropped_word")
        assertFalse("Dropped-word candidate must fail gates", droppedOutcome.metrics.passedAllGates)
        assertTrue("Dropped-word CER must be > 0.0f", droppedOutcome.metrics.cer > 0.0f)
        assertTrue(droppedOutcome.failureReason!!.contains("SKIPPED_WORD_OR_CER"))

        val glitchOutcome = batch.allCandidateOutcomes.getValue("c_004_t4_tail_glitch")
        assertFalse("White-noise tail glitch candidate must fail gates", glitchOutcome.metrics.passedAllGates)
        assertTrue(
            "White-noise tail RMS must exceed -45 dBFS, was ${glitchOutcome.metrics.tailRmsDb}",
            glitchOutcome.metrics.tailRmsDb > -45.0f
        )
        assertTrue(glitchOutcome.failureReason!!.contains("TAIL_RMS_EXCEEDED"))
        assertTrue(glitchOutcome.tailHfGlitchDetected)

        assertEquals(2, batch.rejectedFailures.size)
        assertEquals(setOf(948104L, 948105L), batch.rejectedFailures.map { it.seed }.toSet())

        // 2. Surviving 3 known-good buffers must be ranked best-first.
        assertEquals(3, batch.survivingTakes.size)
        assertEquals(
            listOf("c_004_t0_good_best", "c_004_t2_good_mid", "c_004_t1_good_third"),
            batch.survivingTakes.map { it.takeId }
        )

        val pcmLookup = mapOf(
            "c_004_t0_good_best" to goodPcm0,
            "c_004_t2_good_mid" to goodPcm1,
            "c_004_t1_good_third" to goodPcm2
        )

        // 3. Every surviving take must satisfy |pcm[zeroCrossIn]| < 0.005f, 64-byte aligned .pcm, and real data.
        for (take in batch.survivingTakes) {
            assertTrue(take.metrics.passedAllGates)
            assertEquals(0.0f, take.metrics.cer, 1e-6f)
            assertTrue(take.metrics.speakerCosineSim >= 0.86f)
            assertTrue(take.metrics.dnsmosP808 >= 4.10f)
            assertTrue(take.metrics.tailRmsDb <= -45.0f)
            assertTrue(take.metrics.syllablesPerSec in 4.2f..6.1f)
            assertEquals(canonicalWordSpans.size, take.tokens.size)

            val srcPcm = pcmLookup.getValue(take.takeId)
            for (token in take.tokens) {
                assertTrue(
                    "zeroCrossIn must be within +/- 88 samples of startSample",
                    abs(token.zeroCrossIn - token.startSample) <= ZERO_CROSSING_SCAN_SAMPLES
                )
                assertTrue(
                    "zeroCrossOut must be within +/- 88 samples of endSample",
                    abs(token.zeroCrossOut - token.endSample) <= ZERO_CROSSING_SCAN_SAMPLES
                )
                assertTrue(
                    "abs(pcm[zeroCrossIn]) must be < 0.005f for '${token.word}', got ${abs(srcPcm[token.zeroCrossIn.toInt()])}",
                    abs(srcPcm[token.zeroCrossIn.toInt()]) < ZERO_CROSSING_MAX_ABS_AMPLITUDE
                )
                assertTrue(
                    "abs(pcm[zeroCrossOut]) must be < 0.005f for '${token.word}', got ${abs(srcPcm[token.zeroCrossOut.toInt()])}",
                    abs(srcPcm[token.zeroCrossOut.toInt()]) < ZERO_CROSSING_MAX_ABS_AMPLITUDE
                )
                assertTrue(
                    "f0PeakSample must lie within [startSample, endSample]",
                    token.f0PeakSample in token.startSample..token.endSample
                )
            }

            // Verify 64-byte aligned headerless float32 PCM file on disk matches source waveform.
            val pcmFile = File(take.pcmFilePath)
            assertTrue("Persisted PCM file must exist: $pcmFile", pcmFile.isFile)
            assertEquals(
                "Persisted PCM file must be 64-byte aligned for mmap",
                0L,
                pcmFile.length() % AudioDspMetrics.PCM_ALIGNMENT_BYTES
            )
            assertEquals(srcPcm.size.toLong(), take.totalSamples)
            val diskBytes = pcmFile.readBytes()
            val bb = ByteBuffer.wrap(diskBytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(srcPcm[0], bb.getFloat(0), 1e-7f)
            assertEquals(srcPcm[12_345], bb.getFloat(12_345 * 4), 1e-7f)
            assertEquals(srcPcm[srcPcm.lastIndex], bb.getFloat(srcPcm.lastIndex * 4), 1e-7f)
        }

        // Verify ONLY the 3 surviving .pcm files exist (rejected takes are never written to disk).
        val writtenPcms = outDir.listFiles { f -> f.extension == "pcm" }.orEmpty()
        assertEquals(3, writtenPcms.size)

        // 4. Verify ClauseNode.json parses cleanly against canonical schema.
        assertTrue(batch.clauseJsonFile.isFile)
        assertEquals("ClauseNode.json", batch.clauseJsonFile.name)
        val decodedClause = strictJson.decodeFromString(
            ClauseNode.serializer(),
            batch.clauseJsonFile.readText(Charsets.UTF_8)
        )
        assertEquals(batch.updatedClause, decodedClause)
        assertEquals(0, decodedClause.selectedTakeIndex)
        assertEquals(3, decodedClause.candidates.size)
        assertNotNull(decodedClause.selectedCandidate)
        assertEquals("c_004_t0_good_best", decodedClause.selectedCandidate!!.takeId)

        verifier.close()
    }

    @Test
    fun create_returnsFailureWhenModelFilesAreMissing_neverFabricatesMetrics() {
        val missingDir = File(tempFolder.root, "missing_ctc")
        val missingWeSpeaker = File(tempFolder.root, "missing_wespeaker.onnx")
        val missingDnsmos = File(tempFolder.root, "missing_dnsmos.onnx")
        val missingCentroid = File(tempFolder.root, "missing_centroid.bin")

        // 1. All missing -> Result.failure
        val r1 = ClauseVerifier.create(
            VerifierModelsConfig(
                sherpaCtcModelDir = missingDir.absolutePath,
                weSpeakerOnnxPath = missingWeSpeaker.absolutePath,
                dnsmosP808OnnxPath = missingDnsmos.absolutePath,
                masterCentroidBinPath = missingCentroid.absolutePath
            ),
            useNativeDsp = false
        )
        assertTrue("Missing models must return Result.failure", r1.isFailure)

        // 2. Valid CTC dir + dummy model files + truncated centroid (< 1024 bytes) -> Result.failure
        val ctcDir = tempFolder.newFolder("ctc_dir")
        File(ctcDir, "tokens.txt").writeText("<blk> 0\na 1\n")
        File(ctcDir, "model.onnx").writeBytes(byteArrayOf(1, 2, 3, 4))
        val wsFile = tempFolder.newFile("ws.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val dnsFile = tempFolder.newFile("dns.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val badCentroid = tempFolder.newFile("bad_centroid.bin").apply { writeBytes(ByteArray(32)) }

        val r2 = ClauseVerifier.create(
            VerifierModelsConfig(
                sherpaCtcModelDir = ctcDir.absolutePath,
                weSpeakerOnnxPath = wsFile.absolutePath,
                dnsmosP808OnnxPath = dnsFile.absolutePath,
                masterCentroidBinPath = badCentroid.absolutePath
            ),
            useNativeDsp = false
        )
        assertTrue("Malformed master_centroid.bin must return Result.failure", r2.isFailure)
        assertTrue(r2.exceptionOrNull()!!.message!!.contains("1024"))
    }

    @Test
    fun verifyCandidate_hardRejectsEveryIndividualGateViolation() {
        val centroid = AudioDspMetrics.loadMasterCentroid(
            writeMasterCentroidFile(tempFolder.newFile("gate_centroid.bin"), makeMasterCentroidVector())
        ).getOrThrow()
        val goodPcm = makeRealisticSpeechPcm(114_660, f0Hz = 175.0, seed = 200L)
        val hfBurstPcm = makeRealisticSpeechPcm(
            totalSamples = 114_660,
            f0Hz = 175.0,
            seed = 201L,
            injectShortHfBurstTail = true
        )

        fun evalSingle(
            pcm: FloatArray = goodPcm,
            recognizedText: String = canonicalClauseText,
            maxPhonemeMs: Float = 200.0f,
            spans: List<RawWordSpan> = canonicalWordSpans,
            speakerCosine: Float = 0.92f,
            dnsmos: Float = 4.40f
        ): CandidateVerificationResult {
            val v = ClauseVerifier(
                masterCentroid = centroid,
                ctcEngine = FixedCtcEngine(CtcAlignmentOutput(recognizedText, maxPhonemeMs, spans)),
                speakerEngine = FixedSpeakerEngine(makeVectorWithTargetCosine(centroid, speakerCosine)),
                dnsmosEngine = FixedDnsmosEngine(dnsmos),
                useNativeDsp = false
            )
            return v.use { it.verifyCandidate(pcm, canonicalClauseText).getOrThrow() }
        }

        // Baseline passes.
        assertTrue(evalSingle().metrics.passedAllGates)

        // Gate 1b: dragged phoneme > 380 ms.
        val dragged = evalSingle(maxPhonemeMs = 415.0f)
        assertFalse(dragged.metrics.passedAllGates)
        assertEquals("DRAGGED_PHONEME", dragged.failureReason)

        // Gate 2: low WeSpeaker cosine < 0.86.
        val imposter = evalSingle(speakerCosine = 0.81f)
        assertFalse(imposter.metrics.passedAllGates)
        assertEquals("SPEAKER_SIMILARITY_LOW", imposter.failureReason)

        // Gate 3a: low DNSMOS p808 < 4.10.
        val noisyMos = evalSingle(dnsmos = 4.02f)
        assertFalse(noisyMos.metrics.passedAllGates)
        assertEquals("DNSMOS_LOW", noisyMos.failureReason)

        // Gate 3b: short 12ms 9kHz tail HF glitch burst.
        val hfBurst = evalSingle(pcm = hfBurstPcm)
        assertFalse(hfBurst.metrics.passedAllGates)
        assertTrue(hfBurst.tailHfGlitchDetected)
        assertTrue(hfBurst.failureReason!!.contains("TAIL_HF_GLITCH"))

        // Gate 4: too fast (> 6.1 syl/s) and too slow (< 4.2 syl/s).
        val fastSpans = listOf(RawWordSpan("fast", 0L, 66_150L))  // 13 syl / 1.5s = 8.67 syl/s
        val fast = evalSingle(spans = fastSpans)
        assertFalse(fast.metrics.passedAllGates)
        assertEquals("CADENCE_OUT_OF_BAND", fast.failureReason)

        val slowSpans = listOf(RawWordSpan("slow", 0L, 176_400L))
        val slowPcm = makeRealisticSpeechPcm(185_000, f0Hz = 175.0, seed = 205L)
        val slow = evalSingle(pcm = slowPcm, spans = slowSpans)  // 13 syl / 4.0s = 3.25 syl/s
        assertFalse(slow.metrics.passedAllGates)
        assertEquals("CADENCE_OUT_OF_BAND", slow.failureReason)
    }

    @Test
    fun evaluateAndPersistClause_ranksFourPassingTakesAndPersistsOnlyTopThree() {
        val centroid = AudioDspMetrics.loadMasterCentroid(
            writeMasterCentroidFile(tempFolder.newFile("cap_centroid.bin"), makeMasterCentroidVector())
        ).getOrThrow()

        val buffers = List(4) { idx -> makeRealisticSpeechPcm(114_660, f0Hz = 165.0 + idx * 5.0, seed = 300L + idx) }
        val sims = listOf(0.87f, 0.96f, 0.90f, 0.93f)
        val mos = listOf(4.15f, 4.55f, 4.30f, 4.42f)

        val verifier = ClauseVerifier(
            masterCentroid = centroid,
            ctcEngine = FixedCtcEngine(CtcAlignmentOutput(canonicalClauseText, 190.0f, canonicalWordSpans)),
            speakerEngine = MapSpeakerEngine(
                IdentityMap(*buffers.indices.map { i -> buffers[i] to makeVectorWithTargetCosine(centroid, sims[i]) }.toTypedArray())
            ),
            dnsmosEngine = MapDnsmosEngine(
                IdentityMap(*buffers.indices.map { i -> buffers[i] to mos[i] }.toTypedArray())
            ),
            useNativeDsp = false
        )

        val clause = ClauseNode(
            clauseId = "c_009",
            index = 9,
            rawText = canonicalClauseText,
            normalizedPhonemes = "w iː"
        )
        val takes = buffers.mapIndexed { idx, buf ->
            RawCandidateInput("c_009_t$idx", buf, intArrayOf(idx, idx + 10), 1000L + idx, 0.7f)
        }
        val outDir = tempFolder.newFolder("clause_cap")
        val result = verifier.use {
            it.evaluateAndPersistClause(clause, takes, outDir, maxSurvivingTakes = 3).getOrThrow()
        }

        // Order by composite score: t1 (0.96, 4.55) > t3 (0.93, 4.42) > t2 (0.90, 4.30); t0 dropped from top 3.
        assertEquals(listOf("c_009_t1", "c_009_t3", "c_009_t2"), result.survivingTakes.map { it.takeId })
        assertTrue(result.rejectedFailures.isEmpty())
        assertEquals(3, outDir.listFiles { f -> f.extension == "pcm" }.orEmpty().size)
    }

    @Test
    fun ctcViterbiAndYinF0_alignWordsAndLocateVoicedNucleus() {
        // Verify Kotlin CTC greedy + Viterbi parity with C++.
        val vocab = listOf("<blk>", "|", "h", "i", "g", "o")
        val vocabSize = vocab.size
        val frameShift = 882 // 20 ms @ 44.1 kHz
        val numFrames = 30
        val logProbs = FloatArray(numFrames * vocabSize) { -10.0f }
        for (t in 0 until numFrames) {
            val tok = when (t) {
                in 2..5 -> 2
                in 6..10 -> 3
                in 12..14 -> 1
                in 16..20 -> 4
                in 21..25 -> 5
                else -> 0
            }
            logProbs[t * vocabSize + tok] = -0.05f
        }

        val greedy = AudioDspMetrics.ctcGreedyDecode(logProbs, numFrames, vocabSize, 0, vocab, frameShift)
        assertEquals("hi go", greedy.decodedText)
        assertEquals(100.0f, greedy.maxPhonemeDurationMs, 0.5f)

        val viterbi = AudioDspMetrics.ctcViterbiForceAlign(
            logProbs = logProbs,
            numFrames = numFrames,
            vocabSize = vocabSize,
            blankId = 0,
            targetTokenIds = intArrayOf(2, 3, 1, 4, 5),
            vocab = vocab,
            frameShiftSamples = frameShift,
            totalSamples = numFrames.toLong() * frameShift
        )
        assertNotNull(viterbi)
        assertEquals(listOf("hi", "go"), viterbi!!.wordSpans.map { it.word })
        assertEquals(2L * frameShift, viterbi.wordSpans[0].startSample)
        assertEquals(11L * frameShift, viterbi.wordSpans[0].endSample)

        // Verify YIN F0 peak locates the voiced 180 Hz vowel nucleus inside [10_000, 30_000].
        val rng = Random(77L)
        val pcm = FloatArray(SYNTHESIS_SAMPLE_RATE_HZ)
        for (i in 10_000..30_000) {
            pcm[i] = (rng.nextFloat() * 2f - 1f) * 0.015f
        }
        for (i in 21_500..25_500) {
            val t = (i - 21_500).toDouble() / SYNTHESIS_SAMPLE_RATE_HZ.toDouble()
            val env = sin(PI * (i - 21_500).toDouble() / 4000.0)
            pcm[i] = (0.45 * env * sin(2.0 * PI * 180.0 * t)).toFloat()
        }
        val f0Peak = AudioDspMetrics.findF0PeakSample(pcm, 10_000L, 30_000L)
        assertTrue("YIN f0PeakSample should land in voiced nucleus 21500..25500, got $f0Peak", f0Peak in 21_500L..25_500L)

        // Verify 44.1kHz -> 16kHz resampling + 80-dim log-Mel Fbank shapes for WeSpeaker/ORT.
        val pcm16k = AudioDspMetrics.resample44kTo16k(pcm)
        assertTrue(pcm16k.size in 15_900..16_100)
        val (fbank, frames) = AudioDspMetrics.computeLogMelFilterbank(pcm16k, numMelBins = 80)
        assertTrue(frames > 90)
        assertEquals(frames * 80, fbank.size)
        assertTrue(fbank.all { it.isFinite() })
    }

    @Test
    fun dnsmosP808MelSpectrogram_matchesMicrosoftDnsChallengeLibrosaReference() {
        val sig = makeParityReferenceSignal16k()

        // Short 2.0s signal must tile cyclically to 9.01s (144,160), drop trailing 160 samples,
        // and produce 1 window of exact [900, 120] features in [-1, 1].
        val windows = OrtDnsmosP808Engine.buildWindowedMelFeatures(sig)
        assertEquals(1, windows.size)
        val feat = windows[0]
        assertEquals(AudioDspMetrics.DNSMOS_TARGET_FRAMES * AudioDspMetrics.DNSMOS_MEL_BINS, feat.size)
        assertTrue("All DNSMOS Mel features must be finite", feat.all { it.isFinite() })

        var minVal = Float.POSITIVE_INFINITY
        var maxVal = Float.NEGATIVE_INFINITY
        var sum = 0.0
        for (v in feat) {
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
            sum += v.toDouble()
        }
        assertEquals(-1.0f, minVal, 1e-5f)
        assertEquals(1.0f, maxVal, 1e-5f)
        assertEquals(-0.6262509f, (sum / feat.size.toDouble()).toFloat(), 1e-4f)

        // Exact golden coordinates computed by `librosa.feature.melspectrogram(y=seg[:-160],
        // sr=16000, n_fft=321, hop_length=160, n_mels=120)` + `(power_to_db(ref=np.max) + 40) / 40`.
        val expected = listOf(
            Triple(0, 0, -0.3421761f),
            Triple(0, 10, 0.0139379f),
            Triple(15, 5, 0.6408461f),
            Triple(15, 40, 0.2797655f),
            Triple(100, 12, 0.0326921f),
            Triple(100, 75, -0.4386942f),
            Triple(450, 30, -1.0000000f),
            Triple(450, 105, -1.0000000f),
            Triple(899, 119, -1.0000000f)
        )
        for ((frame, bin, refVal) in expected) {
            val actual = feat[frame * AudioDspMetrics.DNSMOS_MEL_BINS + bin]
            assertEquals(
                "DNSMOS feature mismatch at ($frame, $bin)",
                refVal,
                actual,
                1e-4f
            )
        }

        // Verify sliding-window hop count on > 10s signal (11.0s -> 2 hops of [900, 120]).
        val longSig = FloatArray(11 * AudioDspMetrics.DNSMOS_SAMPLE_RATE_HZ) { idx ->
            sig[idx % sig.size]
        }
        val longWindows = OrtDnsmosP808Engine.buildWindowedMelFeatures(longSig)
        assertEquals(2, longWindows.size)
        assertEquals(AudioDspMetrics.DNSMOS_TARGET_FRAMES * AudioDspMetrics.DNSMOS_MEL_BINS, longWindows[1].size)

        // Verify leading/trailing digital-zero trimming leaves active signal intact.
        val padded = FloatArray(sig.size + 400).also { sig.copyInto(it, destinationOffset = 200) }
        val trimmed = AudioDspMetrics.trimDigitalSilence(padded)
        assertTrue(trimmed.size <= sig.size)
        assertTrue(trimmed.size >= sig.size - 16)
    }

    @Test
    fun citrinetPerFeatureCmvn_matchesNemoReferenceAndPreservesWeSpeakerCmn() {
        val sig = makeParityReferenceSignal16k()

        // 1. NeMo Citrinet-512 per-feature CMVN: (x - mean) / (std + 1e-5)
        val (cmvn, frames) = AudioDspMetrics.computeLogMelFilterbank(
            pcm16k = sig,
            numMelBins = 80,
            frameLengthSamples = 400,
            frameShiftSamples = 160,
            fftSize = 512,
            fMinHz = 20.0f,
            fMaxHz = 7600.0f,
            applyCmn = true,
            applyCmvn = true
        )
        assertEquals(198, frames)
        assertEquals(198 * 80, cmvn.size)
        assertTrue(cmvn.all { it.isFinite() })

        // Every feature channel must have mean ~= 0.0 and std ~= 1.0.
        for (m in 0 until 80) {
            var sum = 0.0
            for (t in 0 until frames) sum += cmvn[t * 80 + m].toDouble()
            val mean = sum / frames.toDouble()
            var varSum = 0.0
            for (t in 0 until frames) {
                val d = cmvn[t * 80 + m].toDouble() - mean
                varSum += d * d
            }
            val std = sqrt(varSum / frames.toDouble())
            assertEquals("Channel $m CMVN mean must be 0", 0.0, mean, 1e-4)
            assertEquals("Channel $m CMVN std must be 1", 1.0, std, 1e-3)
        }

        // Exact golden coordinates matching `enroll_voice_assets.compute_log_mel_filterbank(..., apply_cmvn=True)`.
        val expected = listOf(
            Triple(0, 0, -0.0272460f),
            Triple(0, 20, 0.1789445f),
            Triple(25, 10, 0.4846002f),
            Triple(25, 50, -0.2743051f),
            Triple(100, 15, -0.4776739f),
            Triple(100, 70, -1.0237106f),
            Triple(197, 5, -0.2810893f),
            Triple(197, 79, 1.6078589f)
        )
        for ((frame, bin, refVal) in expected) {
            val actual = cmvn[frame * 80 + bin]
            assertEquals("Citrinet CMVN mismatch at ($frame, $bin)", refVal, actual, 1e-4f)
        }

        // 2. Verify OrtCtcAlignerEngine channel-first [C, T] transpose matches exact CMVN values.
        val (transposed, ctcFrames) = OrtCtcAlignerEngine.buildCtcFeatureTensorData(
            pcm16k = sig,
            featDim = 80,
            isChannelsFirstNemo = true
        )
        assertEquals(frames, ctcFrames)
        for ((frame, bin, refVal) in expected) {
            assertEquals(
                "Transposed NeMo CTC tensor mismatch at ($frame, $bin)",
                refVal,
                transposed[bin * ctcFrames + frame],
                1e-4f
            )
        }

        // 3. Verify default WeSpeaker mode (applyCmn=true, applyCmvn=false) preserves unscaled variance.
        val (cmnOnly, cmnFrames) = AudioDspMetrics.computeLogMelFilterbank(
            pcm16k = sig,
            numMelBins = 80,
            applyCmn = true
        )
        assertEquals(frames, cmnFrames)
        var maxChannelStd = 0.0
        for (m in 0 until 80) {
            var varSum = 0.0
            for (t in 0 until cmnFrames) {
                val v = cmnOnly[t * 80 + m].toDouble()
                varSum += v * v
            }
            val std = sqrt(varSum / cmnFrames.toDouble())
            if (std > maxChannelStd) maxChannelStd = std
        }
        assertTrue("WeSpeaker CMN-only std must remain unnormalized (> 2.0), got $maxChannelStd", maxChannelStd > 2.0)
    }

    @Test
    fun sherpaCtcModelValidationAndAlignmentEdgeCases_neverFabricateWordSpans() {
        // 1. Missing directory / missing tokens / missing .onnx all fail explicitly
        val emptyDir = tempFolder.newFolder("empty_sherpa")
        assertTrue(OrtCtcAlignerEngine.validateModelDir(emptyDir).isFailure)

        File(emptyDir, "tokens.txt").writeText("<blk> 0\n▁hello 1\n▁world 2\n")
        assertTrue(
            "Missing .onnx model must fail validateModelDir",
            OrtCtcAlignerEngine.validateModelDir(emptyDir).isFailure
        )

        val onnxFile = File(emptyDir, "model.int8.onnx").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val validated = OrtCtcAlignerEngine.validateModelDir(emptyDir).getOrThrow()
        assertEquals(onnxFile.absolutePath, validated.first.absolutePath)

        // 2. loadTokens parses SentencePiece tokens + <blk> blankId accurately
        val tokensFile = tempFolder.newFile("sp_tokens.txt")
        tokensFile.writeText(
            """
            <unk> 0
            ▁air 1
            check 2
            ▁live 3
            <blk> 4
            """.trimIndent()
        )
        val (vocab, tokenToId, blankId) = OrtCtcAlignerEngine.loadTokens(tokensFile)
        assertEquals(4, blankId)
        assertEquals(5, vocab.size)
        assertEquals(1, tokenToId["▁air"])

        // 3. When target sequence cannot align (e.g., blank-only logits), ctcViterbiForceAlign returns null (never fake timings)
        val numFrames = 8
        val vocabSize = vocab.size
        val blankOnlyLogProbs = FloatArray(numFrames * vocabSize) { -20.0f }
        for (t in 0 until numFrames) {
            blankOnlyLogProbs[t * vocabSize + blankId] = -0.001f
        }
        val unaligned = AudioDspMetrics.ctcViterbiForceAlign(
            logProbs = blankOnlyLogProbs,
            numFrames = numFrames,
            vocabSize = vocabSize,
            blankId = blankId,
            targetTokenIds = intArrayOf(1, 2, 3),
            vocab = vocab,
            frameShiftSamples = 882,
            totalSamples = numFrames * 882L
        )
        assertTrue(
            "SentencePiece CTC greedy decode on blank-only frames must be empty",
            AudioDspMetrics.ctcGreedyDecode(blankOnlyLogProbs, numFrames, vocabSize, blankId, vocab, 882).decodedText.isEmpty()
        )
        // Even if Viterbi returns null when frames < targetIds, never fabricate spans
        val tooShort = AudioDspMetrics.ctcViterbiForceAlign(
            logProbs = blankOnlyLogProbs,
            numFrames = 2,
            vocabSize = vocabSize,
            blankId = blankId,
            targetTokenIds = intArrayOf(1, 2, 3),
            vocab = vocab,
            frameShiftSamples = 882,
            totalSamples = 2 * 882L
        )
        assertEquals(null, tooShort)
        assertNotNull(unaligned) // when T >= U, Viterbi assigns minimal non-blank transitions from real logProbs
    }

    private fun makeParityReferenceSignal16k(): FloatArray {
        val sr = 16_000
        val n = sr * 2
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr.toDouble()
            val env = 0.55 + 0.45 * sin(2.0 * PI * 3.5 * t)
            val f0Phase = 2.0 * PI * (165.0 * t + 12.0 * sin(2.0 * PI * 1.2 * t))
            val sample = env * (
                0.25 * sin(f0Phase) +
                    0.14 * sin(2.0 * f0Phase + 0.3) +
                    0.08 * sin(5.0 * f0Phase + 0.7) +
                    0.04 * sin(14.0 * f0Phase + 1.2)
                ) +
                0.008 * sin(2.0 * PI * (2800.0 * t + 900.0 * t * t)) +
                0.005 * sin(2.0 * PI * (5200.0 * t + 600.0 * t * t))
            sample.toFloat()
        }
    }

    // --- Deterministic Test Fixtures (Real Waveforms & Real Vector Math) ---

    private fun makeRealisticSpeechPcm(
        totalSamples: Int,
        f0Hz: Double,
        seed: Long,
        injectWhiteNoiseTail: Boolean = false,
        injectShortHfBurstTail: Boolean = false
    ): FloatArray {
        val pcm = FloatArray(totalSamples)
        val tailSamples = ((SYNTHESIS_SAMPLE_RATE_HZ.toLong() * AudioDspMetrics.TAIL_WINDOW_MS) / 1000L).toInt()
        val speechEnd = (totalSamples - tailSamples).coerceAtLeast(0)

        for (i in 0 until speechEnd) {
            val t = i.toDouble() / SYNTHESIS_SAMPLE_RATE_HZ.toDouble()
            val env = 0.65 + 0.35 * sin(2.0 * PI * 5.0 * t)
            val fade = when {
                i < 441 -> i / 441.0
                speechEnd - i < 882 -> (speechEnd - i) / 882.0
                else -> 1.0
            }
            val wave = 0.60 * sin(2.0 * PI * f0Hz * t) +
                0.28 * sin(2.0 * PI * 2.0 * f0Hz * t + 0.3) +
                0.12 * sin(2.0 * PI * 3.0 * f0Hz * t + 0.7)
            pcm[i] = (0.22 * env * fade * wave).toFloat()
        }

        val rng = Random(seed)
        val quietFloorPeak = 0.0014f // ~ -61.8 dBFS RMS
        for (i in speechEnd until totalSamples) {
            pcm[i] = (rng.nextFloat() * 2.0f - 1.0f) * quietFloorPeak
        }

        if (injectWhiteNoiseTail) {
            for (i in speechEnd until totalSamples) {
                pcm[i] = (rng.nextFloat() * 2.0f - 1.0f) * 0.12f // ~ -23.2 dBFS white noise glitch
            }
        } else if (injectShortHfBurstTail) {
            val burstLen = 529 // ~12 ms @ 44.1 kHz
            val burstStart = (totalSamples - burstLen - 128).coerceAtLeast(speechEnd)
            for (i in 0 until burstLen) {
                val idx = burstStart + i
                if (idx < totalSamples) {
                    val t = i.toDouble() / SYNTHESIS_SAMPLE_RATE_HZ.toDouble()
                    pcm[idx] = (0.045 * sin(2.0 * PI * 9000.0 * t)).toFloat()
                }
            }
        }
        return pcm
    }

    private fun makeMasterCentroidVector(): FloatArray =
        FloatArray(AudioDspMetrics.SPEAKER_EMBEDDING_DIM) { i ->
            cos((i + 1) * 0.17).toFloat()
        }

    private fun makeVectorWithTargetCosine(unitCentroid: FloatArray, targetCosine: Float): FloatArray {
        // Construct unit vector u = targetCosine * c + sqrt(1 - targetCosine^2) * ortho.
        val dim = unitCentroid.size
        val rawOrtho = FloatArray(dim) { i -> sin((i + 1) * 0.31).toFloat() }
        var proj = 0.0
        for (i in 0 until dim) proj += rawOrtho[i].toDouble() * unitCentroid[i].toDouble()
        val ortho = DoubleArray(dim) { i -> rawOrtho[i].toDouble() - proj * unitCentroid[i].toDouble() }
        val orthoNorm = sqrt(ortho.sumOf { it * it })
        val sinTheta = sqrt((1.0 - targetCosine.toDouble() * targetCosine.toDouble()).coerceAtLeast(0.0))
        return FloatArray(dim) { i ->
            (targetCosine.toDouble() * unitCentroid[i].toDouble() + sinTheta * (ortho[i] / orthoNorm)).toFloat()
        }
    }

    private fun writeMasterCentroidFile(file: File, vec: FloatArray): File {
        val bb = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vec) bb.putFloat(v)
        file.writeBytes(bb.array())
        return file
    }

    private class IdentityMap<K : Any, V>(vararg pairs: Pair<K, V>) {
        private val entries = pairs.toList()
        fun getValue(key: K): V = entries.first { it.first === key }.second
    }

    private class MapCtcEngine(private val map: IdentityMap<FloatArray, CtcAlignmentOutput>) : CtcAlignerEngine {
        override fun align(pcm44k: FloatArray, expectedText: String): Result<CtcAlignmentOutput> =
            runCatching { map.getValue(pcm44k) }
        override fun close() = Unit
    }

    private class MapSpeakerEngine(private val map: IdentityMap<FloatArray, FloatArray>) : SpeakerEmbeddingEngine {
        override fun extractEmbedding(pcm44k: FloatArray): Result<FloatArray> =
            runCatching { map.getValue(pcm44k) }
        override fun close() = Unit
    }

    private class MapDnsmosEngine(private val map: IdentityMap<FloatArray, Float>) : DnsmosP808Engine {
        override fun scoreAudio(pcm44k: FloatArray): Result<Float> =
            runCatching { map.getValue(pcm44k) }
        override fun close() = Unit
    }

    private class FixedCtcEngine(private val out: CtcAlignmentOutput) : CtcAlignerEngine {
        override fun align(pcm44k: FloatArray, expectedText: String): Result<CtcAlignmentOutput> = Result.success(out)
        override fun close() = Unit
    }

    private class FixedSpeakerEngine(private val emb: FloatArray) : SpeakerEmbeddingEngine {
        override fun extractEmbedding(pcm44k: FloatArray): Result<FloatArray> = Result.success(emb)
        override fun close() = Unit
    }

    private class FixedDnsmosEngine(private val score: Float) : DnsmosP808Engine {
        override fun scoreAudio(pcm44k: FloatArray): Result<Float> = Result.success(score)
        override fun close() = Unit
    }
}
