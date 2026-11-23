package com.example

import com.example.data.synthesis.ClauseNode
import com.example.data.synthesis.OtaArtifactFormat
import com.example.data.synthesis.OtaArtifactRole
import com.example.data.synthesis.OtaBundleManifest
import com.example.data.synthesis.OtaModelArtifact
import com.example.data.synthesis.PipelineStage
import com.example.data.synthesis.PreferenceActionType
import com.example.data.synthesis.PreferenceTuple
import com.example.data.synthesis.ProsodyBackend
import com.example.data.synthesis.VerificationGateThresholds
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthesisContractsTest {

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Test
    fun clauseNode_roundTripsExactCanonicalWireJson() {
        val canonicalJson = """
            {
              "clause_id": "c_004",
              "raw_text": "We ripped out the cloud wrapper and ran it bare-metal.",
              "normalized_phonemes": "w iː ɹ ˈɪ p t ˈaʊ t ð ə k l ˈaʊ d ɹ ˈæ p ɚ...",
              "acoustic_prefix_uri": "takes/c_003_winner_tail_1500ms.pcm",
              "selected_take_index": 0,
              "gap_after_ms": 85,
              "candidates": [
                {
                  "take_id": "c_004_t0",
                  "pcm_uri": "takes/c_004_t0_48k_f32.pcm",
                  "seed": 948102,
                  "temperature": 0.72,
                  "metrics": {
                    "cer": 0.0,
                    "speaker_cosine_sim": 0.914,
                    "utmos_score": 4.42,
                    "syllables_per_sec": 5.1,
                    "passed_all_gates": true
                  },
                  "tokens": [
                    {
                      "word": "ripped",
                      "start_sample": 18240,
                      "end_sample": 31680,
                      "zero_cross_in": 18233,
                      "zero_cross_out": 31689,
                      "f0_peak_sample": 24100,
                      "video_anchor_id": "vid_event_02"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = json.decodeFromString(ClauseNode.serializer(), canonicalJson)
        assertEquals("c_004", decoded.clauseId)
        assertEquals("takes/c_003_winner_tail_1500ms.pcm", decoded.acousticPrefixUri)
        assertEquals(0, decoded.selectedTakeIndex)
        assertEquals(85, decoded.gapAfterMs)
        assertEquals(1, decoded.candidates.size)

        val candidate = decoded.selectedCandidate!!
        assertEquals("c_004_t0", candidate.takeId)
        assertEquals("takes/c_004_t0_48k_f32.pcm", candidate.pcmUri)
        assertEquals(948102L, candidate.seed)
        assertEquals(0.72, candidate.temperature, 1e-9)
        assertEquals(0.0, candidate.metrics.cer, 1e-9)
        assertEquals(0.914, candidate.metrics.speakerCosineSim, 1e-9)
        assertEquals(4.42, candidate.metrics.utmosScore, 1e-9)
        assertEquals(5.1, candidate.metrics.syllablesPerSec, 1e-9)
        assertTrue(candidate.metrics.passedAllGates)

        val token = candidate.tokens.single()
        assertEquals("ripped", token.word)
        assertEquals(18240L, token.startSample)
        assertEquals(31680L, token.endSample)
        assertEquals(18233L, token.zeroCrossIn)
        assertEquals(31689L, token.zeroCrossOut)
        assertEquals(24100L, token.f0PeakSample)
        assertEquals("vid_event_02", token.videoAnchorId)

        val reEncoded = json.encodeToString(ClauseNode.serializer(), decoded)
        val reDecoded = json.decodeFromString(ClauseNode.serializer(), reEncoded)
        assertEquals(decoded, reDecoded)
    }

    @Test
    fun verificationGateThresholds_enforcesZeroCerAndFourGates() {
        val thresholds = VerificationGateThresholds()

        val pass = thresholds.evaluate(
            cer = 0.0,
            speakerCosineSim = 0.914,
            utmosScore = 4.42,
            syllablesPerSec = 5.1
        )
        assertTrue(pass.passedAllGates)

        val failCer = thresholds.evaluate(
            cer = 0.01,
            speakerCosineSim = 0.914,
            utmosScore = 4.42,
            syllablesPerSec = 5.1
        )
        assertFalse(failCer.passedAllGates)
    }

    @Test
    fun otaBundleManifest_enforcesMemoryCeilings() {
        val validSha = "a".repeat(64)
        val validManifest = OtaBundleManifest(
            bundleVersion = "2026.11.22-grpo-r1",
            createdAtUtc = "2026-11-22T22:00:00Z",
            prosodyBackend = ProsodyBackend.MING_3_0_TTS_TINY,
            adapterCheckpointId = "grpo_opsd_001",
            artifacts = listOf(
                OtaModelArtifact(
                    role = OtaArtifactRole.PROSODY_GENERATOR,
                    filename = "ming_3_0_tts_tiny_q4.litertlm",
                    format = OtaArtifactFormat.LITERTLM,
                    sha256 = validSha,
                    sizeBytes = 2_400_000_000L,
                    estimatedRamMb = 4_200,
                    stage = PipelineStage.STAGE_A
                ),
                OtaModelArtifact(
                    role = OtaArtifactRole.CTC_VERIFIER,
                    filename = "ctc_en_int8.tflite",
                    format = OtaArtifactFormat.TFLITE,
                    sha256 = validSha,
                    sizeBytes = 90_000_000L,
                    estimatedRamMb = 140,
                    stage = PipelineStage.STAGE_B
                ),
                OtaModelArtifact(
                    role = OtaArtifactRole.WESPEAKER_VERIFIER,
                    filename = "wespeaker_int8.tflite",
                    format = OtaArtifactFormat.TFLITE,
                    sha256 = validSha,
                    sizeBytes = 40_000_000L,
                    estimatedRamMb = 110,
                    stage = PipelineStage.STAGE_B
                ),
                OtaModelArtifact(
                    role = OtaArtifactRole.UTMOS_VERIFIER,
                    filename = "utmosv2_int8.tflite",
                    format = OtaArtifactFormat.TFLITE,
                    sha256 = validSha,
                    sizeBytes = 50_000_000L,
                    estimatedRamMb = 95,
                    stage = PipelineStage.STAGE_B
                ),
                OtaModelArtifact(
                    role = OtaArtifactRole.RVC_V2_RESYNTH,
                    filename = "rvc_v2_48k.onnx",
                    format = OtaArtifactFormat.ONNX,
                    sha256 = validSha,
                    sizeBytes = 120_000_000L,
                    estimatedRamMb = 480,
                    stage = PipelineStage.STAGE_C
                )
            )
        )

        assertEquals(345, validManifest.verifierRamMb())
        assertEquals(4_200, validManifest.peakStageRamMb())
        assertTrue(validManifest.fitsMemoryBudget())

        val overBudgetVerifier = validManifest.copy(
            artifacts = validManifest.artifacts + OtaModelArtifact(
                role = OtaArtifactRole.SPEAKER_CENTROID,
                filename = "huge.f32",
                format = OtaArtifactFormat.F32,
                sha256 = validSha,
                sizeBytes = 20_000_000L,
                estimatedRamMb = 20,
                stage = PipelineStage.STAGE_B
            )
        )
        assertFalse(overBudgetVerifier.fitsMemoryBudget())
    }

    @Test
    fun preferenceTuple_serializesToSingleLineJsonl() {
        val tuple = PreferenceTuple(
            eventId = "pref_001",
            timestampUtc = "2026-11-22T22:05:00Z",
            bundleVersion = "2026.11.22-grpo-r1",
            scriptId = "proj_abc",
            clauseId = "c_004",
            rawText = "We ripped out the cloud wrapper and ran it bare-metal.",
            normalizedPhonemes = "w iː ɹ ˈɪ p t",
            acousticPrefixUri = "takes/c_003_winner_tail_1500ms.pcm",
            actionType = PreferenceActionType.TAKE_SWAP,
            chosenTakeId = "c_004_t2",
            rejectedTakeIds = listOf("c_004_t0", "c_004_t1", "c_004_t3"),
            gapAfterMs = 85
        )
        val line = json.encodeToString(PreferenceTuple.serializer(), tuple)
        assertFalse(line.contains("\n"))
        assertEquals(tuple, json.decodeFromString(PreferenceTuple.serializer(), line))
    }
}
