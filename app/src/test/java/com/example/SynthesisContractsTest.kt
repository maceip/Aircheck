package com.example

import com.example.data.synthesis.CandidateTake
import com.example.data.synthesis.ClauseNode
import com.example.data.synthesis.DEFAULT_BASE_GAP_SAMPLES
import com.example.data.synthesis.GateFailureRecord
import com.example.data.synthesis.OtaArtifactFormat
import com.example.data.synthesis.OtaArtifactRole
import com.example.data.synthesis.OtaBundleManifest
import com.example.data.synthesis.OtaModelArtifact
import com.example.data.synthesis.OtaUpdateCadence
import com.example.data.synthesis.PreferenceTelemetryRecord
import com.example.data.synthesis.PreferenceUserAction
import com.example.data.synthesis.SYNTHESIS_SAMPLE_RATE_HZ
import com.example.data.synthesis.VerificationGateThresholds
import com.example.data.synthesis.VoiceProjectManifest
import com.example.data.synthesis.WordToken
import com.example.data.synthesis.computeAnchorTimestampsUs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SynthesisContractsTest {

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Test
    fun voiceProjectManifest_roundTripsCanonicalSpecJson() {
        val thresholds = VerificationGateThresholds()
        val metrics = thresholds.evaluate(
            cer = 0.0f,
            maxPhonemeDurationMs = 210.0f,
            speakerCosineSim = 0.914f,
            dnsmosP808 = 4.42f,
            tailRmsDb = -54.0f,
            syllablesPerSec = 5.1f
        )
        assertTrue(metrics.passedAllGates)

        val manifest = VoiceProjectManifest(
            projectId = "proj_001",
            sampleRate = SYNTHESIS_SAMPLE_RATE_HZ,
            globalGapScale = 1.0f,
            activeLoraVersion = 12,
            clauses = listOf(
                ClauseNode(
                    clauseId = "c_004",
                    index = 0,
                    rawText = "We ripped out the cloud wrapper and ran it bare-metal.",
                    normalizedPhonemes = "w iː ɹ ˈɪ p t ˈaʊ t ð ə k l ˈaʊ d ɹ ˈæ p ɚ",
                    acousticPrefixPcmPath = "takes/c_003_winner_tail_1500ms.pcm",
                    selectedTakeIndex = 0,
                    baseGapAfterSamples = DEFAULT_BASE_GAP_SAMPLES,
                    candidates = listOf(
                        CandidateTake(
                            takeId = "c_004_t0",
                            pcmFilePath = "takes/c_004_t0_44k_f32.pcm",
                            totalSamples = 110_250L,
                            discreteAudioTokens = intArrayOf(1042, 8819, 441, 902),
                            seed = 948102L,
                            temperature = 0.72f,
                            metrics = metrics,
                            tokens = listOf(
                                WordToken(
                                    word = "ripped",
                                    startSample = 18240L,
                                    endSample = 31680L,
                                    zeroCrossIn = 18233L,
                                    zeroCrossOut = 31689L,
                                    f0PeakSample = 24100L,
                                    videoAnchorId = "vid_event_02"
                                )
                            )
                        )
                    )
                )
            )
        )

        val encoded = json.encodeToString(VoiceProjectManifest.serializer(), manifest)
        val decoded = json.decodeFromString(VoiceProjectManifest.serializer(), encoded)
        assertEquals(manifest, decoded)
        assertEquals(44_100, decoded.sampleRate)
        assertEquals(4, decoded.clauses.single().selectedCandidate!!.discreteAudioTokens.size)
    }

    @Test
    fun verificationGateThresholds_enforcesAllFourRectifiedGates() {
        val thresholds = VerificationGateThresholds()

        assertTrue(
            thresholds.evaluate(
                cer = 0.0f,
                maxPhonemeDurationMs = 380.0f,
                speakerCosineSim = 0.86f,
                dnsmosP808 = 4.10f,
                tailRmsDb = -45.0f,
                syllablesPerSec = 5.0f
            ).passedAllGates
        )

        // Gate 1: dropped word (CER > 0)
        assertFalse(thresholds.evaluate(0.05f, 200f, 0.92f, 4.4f, -55f, 5.0f).passedAllGates)
        // Gate 1b: dragged phoneme (> 380ms)
        assertFalse(thresholds.evaluate(0.0f, 410f, 0.92f, 4.4f, -55f, 5.0f).passedAllGates)
        // Gate 2: WeSpeaker cosine < 0.86
        assertFalse(thresholds.evaluate(0.0f, 200f, 0.85f, 4.4f, -55f, 5.0f).passedAllGates)
        // Gate 3: DNSMOS p808 < 4.10
        assertFalse(thresholds.evaluate(0.0f, 200f, 0.92f, 4.05f, -55f, 5.0f).passedAllGates)
        // Gate 3b: white-noise tail glitch (> -45 dBFS)
        assertFalse(thresholds.evaluate(0.0f, 200f, 0.92f, 4.4f, -32f, 5.0f).passedAllGates)
        // Gate 4: cadence outside 4.2..6.1 syl/s
        assertFalse(thresholds.evaluate(0.0f, 200f, 0.92f, 4.4f, -55f, 6.4f).passedAllGates)
    }

    @Test
    fun computeAnchorTimestampsUs_shiftsDeterministicallyWithGlobalGapCompression() {
        val passMetrics = VerificationGateThresholds().evaluate(0f, 200f, 0.91f, 4.3f, -52f, 5.0f)

        fun makeClause(idx: Int, anchorId: String?): ClauseNode = ClauseNode(
            clauseId = "c_00$idx",
            index = idx,
            rawText = "Clause $idx",
            normalizedPhonemes = "k l ɔ z",
            baseGapAfterSamples = 4_410, // 100 ms @ 44.1 kHz
            candidates = listOf(
                CandidateTake(
                    takeId = "c_00${idx}_t0",
                    pcmFilePath = "takes/c_00${idx}_t0.pcm",
                    totalSamples = 44_100L,
                    discreteAudioTokens = intArrayOf(1, 2, 3),
                    seed = idx.toLong(),
                    temperature = 0.7f,
                    metrics = passMetrics,
                    tokens = listOf(
                        WordToken("hello", 0L, 22_050L, 0L, 22_050L, 11_000L, anchorId),
                        WordToken("world", 22_050L, 44_100L, 22_050L, 44_100L, 33_000L, null)
                    )
                )
            )
        )

        val base = VoiceProjectManifest(
            projectId = "p1",
            globalGapScale = 1.0f,
            clauses = listOf(
                makeClause(0, "vid_0"),
                makeClause(1, "vid_1"),
                makeClause(2, "vid_2")
            )
        )
        val compressed = base.copy(globalGapScale = 0.60f) // 40% gap compression -> 60ms gaps

        val baseAnchors = computeAnchorTimestampsUs(base)
        val compAnchors = computeAnchorTimestampsUs(compressed)

        assertEquals(0L, baseAnchors["vid_0"])
        assertEquals(1_100_000L, baseAnchors["vid_1"])
        assertEquals(2_200_000L, baseAnchors["vid_2"])

        assertEquals(0L, compAnchors["vid_0"])
        assertTrue(abs(compAnchors["vid_1"]!! - 1_060_000L) < 1_000L)
        assertTrue(abs(compAnchors["vid_2"]!! - 2_120_000L) < 1_000L)
    }

    @Test
    fun otaBundleManifest_enforcesRectifiedGgufLiteRtAndOnnxBudget() {
        val sha = "b".repeat(64)
        val manifest = OtaBundleManifest(
            bundleVersion = "2026.11.22-lora-v012",
            loraVersion = 12,
            createdAtUtc = "2026-11-22T22:00:00Z",
            artifacts = listOf(
                OtaModelArtifact(
                    role = OtaArtifactRole.S2_PRO_BASE_GGUF,
                    filename = "s2_pro_4b_q4_k_m.gguf",
                    format = OtaArtifactFormat.GGUF,
                    sha256 = sha,
                    sizeBytes = 2_600_000_000L,
                    estimatedRamMb = 3_200,
                    updateCadence = OtaUpdateCadence.STATIC_ONCE
                ),
                OtaModelArtifact(
                    role = OtaArtifactRole.CODEC_DECODER_TFLITE,
                    filename = "codec_decoder_fp16.tflite",
                    format = OtaArtifactFormat.TFLITE,
                    sha256 = sha,
                    sizeBytes = 262_000_000L,
                    estimatedRamMb = 420,
                    updateCadence = OtaUpdateCadence.STATIC_ONCE
                ),
                OtaModelArtifact(
                    role = OtaArtifactRole.VOICE_LORA_GGUF,
                    filename = "voice_lora_v012.gguf",
                    format = OtaArtifactFormat.GGUF,
                    sha256 = sha,
                    sizeBytes = 35_000_000L,
                    estimatedRamMb = 64,
                    updateCadence = OtaUpdateCadence.NIGHTLY_DELTA
                ),
                OtaModelArtifact(
                    role = OtaArtifactRole.WESPEAKER_RESNET34_ONNX,
                    filename = "wespeaker_resnet34.onnx",
                    format = OtaArtifactFormat.ONNX,
                    sha256 = sha,
                    sizeBytes = 25_000_000L,
                    estimatedRamMb = 80,
                    updateCadence = OtaUpdateCadence.STATIC_ONCE
                ),
                OtaModelArtifact(
                    role = OtaArtifactRole.DNSMOS_P808_ONNX,
                    filename = "dnsmos_p808.onnx",
                    format = OtaArtifactFormat.ONNX,
                    sha256 = sha,
                    sizeBytes = 4_000_000L,
                    estimatedRamMb = 24,
                    updateCadence = OtaUpdateCadence.STATIC_ONCE
                )
            )
        )
        assertTrue(manifest.fitsMemoryBudget())
        assertEquals(3_788, manifest.totalEstimatedRamMb())
    }

    @Test
    fun preferenceTelemetryRecord_matchesExactSpecJsonlFormat() {
        val rawSpecJson = """
            {
              "timestamp": 1795409000,
              "clause_id": "c_004",
              "lora_version": 12,
              "raw_text": "We ripped out the cloud wrapper and ran it bare-metal.",
              "normalized_phonemes": "w iː ɹ ˈɪ p t ˈaʊ t ð ə k l ˈaʊ d ɹ ˈæ p ɚ...",
              "user_action": "TAKE_SWAP_AND_PACE_EDIT",
              "user_gap_scale": 0.45,
              "chosen_tokens": [1042, 8819, 441, 902],
              "rejected_tokens": [1042, 7104, 118, 994],
              "gate_failures": [
                {"seed": 948104, "cer": 0.08, "reason": "SKIPPED_WORD", "tokens": [1042, 331]}
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString(PreferenceTelemetryRecord.serializer(), rawSpecJson)
        assertEquals("c_004", decoded.clauseId)
        assertEquals(12, decoded.loraVersion)
        assertEquals(PreferenceUserAction.TAKE_SWAP_AND_PACE_EDIT, decoded.userAction)
        assertEquals(0.45f, decoded.userGapScale, 1e-6f)
        assertEquals(listOf(1042, 8819, 441, 902), decoded.chosenTokens)
        assertEquals(
            GateFailureRecord(seed = 948104L, cer = 0.08f, reason = "SKIPPED_WORD", tokens = listOf(1042, 331)),
            decoded.gateFailures.single()
        )

        val line = json.encodeToString(PreferenceTelemetryRecord.serializer(), decoded)
        assertFalse(line.contains("\n"))
    }
}
