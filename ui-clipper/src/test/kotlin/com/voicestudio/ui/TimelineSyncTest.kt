package com.voicestudio.ui

import com.voicestudio.edl.CandidateTake
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.GateMetrics
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VERNIER_COARSE_SAMPLES_PER_DP
import com.voicestudio.edl.VERNIER_FINE_SAMPLES_PER_INCH
import com.voicestudio.edl.VerificationGateThresholds
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.WordToken
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gate 4 Verification Suite:
 * - 5-clause VoiceProjectManifest with 3 linked video cuts (`videoAnchorId` on clauses 1, 3, 4).
 * - 40% global gap compression (`globalGapScale = 0.60f`): asserts ALL 3 Media3
 *   `ClippingConfiguration` timestamps shift to match exact post-compression cumulative sample
 *   offsets within `< 1 ms` (`< 1_000 us`), and Media3 `Composition` builds cleanly.
 * - Take swap (`selectedTakeIndex`) shifts downstream Media3 video anchors accurately.
 * - Vernier 1:20 gear physics + real 44.1 kHz `.pcm` waveform extraction (zero fabrication).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TimelineSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passingMetrics: GateMetrics = VerificationGateThresholds().evaluate(
        cer = 0.0f,
        maxPhonemeDurationMs = 220.0f,
        speakerCosineSim = 0.92f,
        dnsmosP808 = 4.41f,
        tailRmsDb = -52.5f,
        syllablesPerSec = 5.1f
    )

    private fun buildFiveClauseManifest(
        globalGapScale: Float = 1.0f,
        clause1SelectedTake: Int = 0,
        pcmPaths: Map<String, String> = emptyMap()
    ): VoiceProjectManifest {
        val clause0 = ClauseNode(
            clauseId = "c_000",
            index = 0,
            rawText = "We ripped",
            normalizedPhonemes = "w iː ɹ ˈɪ p t",
            selectedTakeIndex = 0,
            baseGapAfterSamples = 4_410, // 100 ms
            candidates = listOf(
                CandidateTake(
                    takeId = "c_000_t0",
                    pcmFilePath = pcmPaths["c_000_t0"] ?: "takes/c_000_t0.pcm",
                    totalSamples = 22_150L,
                    discreteAudioTokens = intArrayOf(101, 102, 103),
                    seed = 1000L,
                    temperature = 0.7f,
                    metrics = passingMetrics,
                    tokens = listOf(
                        WordToken("We", 44L, 11_044L, 40L, 11_040L, 5_500L, null),
                        WordToken("ripped", 11_044L, 22_094L, 11_040L, 22_090L, 16_500L, null)
                    )
                )
            )
        )

        val clause1Take0 = CandidateTake(
            takeId = "c_001_t0",
            pcmFilePath = pcmPaths["c_001_t0"] ?: "takes/c_001_t0.pcm",
            totalSamples = 33_200L,
            discreteAudioTokens = intArrayOf(201, 202, 203),
            seed = 2000L,
            temperature = 0.7f,
            metrics = passingMetrics,
            tokens = listOf(
                WordToken("out", 104L, 11_129L, 100L, 11_125L, 5_600L, null),
                WordToken("cloud", 11_129L, 33_180L, 11_125L, 33_175L, 22_150L, "vid_cut_1")
            )
        )
        // Take 1 is +11,025 samples (+250 ms) longer overall, and places "cloud" +4,410 samples (+100 ms) later.
        val clause1Take1 = CandidateTake(
            takeId = "c_001_t1",
            pcmFilePath = pcmPaths["c_001_t1"] ?: "takes/c_001_t1.pcm",
            totalSamples = 44_300L,
            discreteAudioTokens = intArrayOf(211, 212, 213),
            seed = 2001L,
            temperature = 0.75f,
            metrics = passingMetrics,
            tokens = listOf(
                WordToken("out", 104L, 15_540L, 100L, 15_535L, 7_800L, null),
                WordToken("cloud", 15_540L, 44_205L, 15_535L, 44_200L, 29_800L, "vid_cut_1")
            )
        )

        val clause1 = ClauseNode(
            clauseId = "c_001",
            index = 1,
            rawText = "out cloud",
            normalizedPhonemes = "ˈaʊ t k l ˈaʊ d",
            selectedTakeIndex = clause1SelectedTake,
            baseGapAfterSamples = 8_820, // 200 ms
            candidates = listOf(clause1Take0, clause1Take1)
        )

        val clause2 = ClauseNode(
            clauseId = "c_002",
            index = 2,
            rawText = "wrappers",
            normalizedPhonemes = "ɹ ˈæ p ɚ z",
            selectedTakeIndex = 0,
            baseGapAfterSamples = 6_615, // 150 ms
            candidates = listOf(
                CandidateTake(
                    takeId = "c_002_t0",
                    pcmFilePath = pcmPaths["c_002_t0"] ?: "takes/c_002_t0.pcm",
                    totalSamples = 44_200L,
                    discreteAudioTokens = intArrayOf(301, 302, 303),
                    seed = 3000L,
                    temperature = 0.7f,
                    metrics = passingMetrics,
                    tokens = listOf(
                        WordToken("wrappers", 24L, 44_124L, 20L, 44_120L, 22_050L, null)
                    )
                )
            )
        )

        val clause3 = ClauseNode(
            clauseId = "c_003",
            index = 3,
            rawText = "bare metal",
            normalizedPhonemes = "b ˈɛ ɹ m ˈɛ ɾ ə l",
            selectedTakeIndex = 0,
            baseGapAfterSamples = 4_410, // 100 ms
            candidates = listOf(
                CandidateTake(
                    takeId = "c_003_t0",
                    pcmFilePath = pcmPaths["c_003_t0"] ?: "takes/c_003_t0.pcm",
                    totalSamples = 44_200L,
                    discreteAudioTokens = intArrayOf(401, 402, 403),
                    seed = 4000L,
                    temperature = 0.7f,
                    metrics = passingMetrics,
                    tokens = listOf(
                        WordToken("bare", 52L, 22_104L, 50L, 22_100L, 11_075L, "vid_cut_3"),
                        WordToken("metal", 22_104L, 44_154L, 22_100L, 44_150L, 33_125L, null)
                    )
                )
            )
        )

        val clause4 = ClauseNode(
            clauseId = "c_004",
            index = 4,
            rawText = "on device",
            normalizedPhonemes = "ˈɑ n d ɪ v ˈaɪ s",
            selectedTakeIndex = 0,
            baseGapAfterSamples = 3_969, // 90 ms
            candidates = listOf(
                CandidateTake(
                    takeId = "c_004_t0",
                    pcmFilePath = pcmPaths["c_004_t0"] ?: "takes/c_004_t0.pcm",
                    totalSamples = 33_100L,
                    discreteAudioTokens = intArrayOf(501, 502, 503),
                    seed = 5000L,
                    temperature = 0.7f,
                    metrics = passingMetrics,
                    tokens = listOf(
                        WordToken("on", 0L, 11_025L, 0L, 11_025L, 5_500L, null),
                        WordToken("device", 11_025L, 33_075L, 11_025L, 33_075L, 22_050L, "vid_cut_4")
                    )
                )
            )
        )

        return VoiceProjectManifest(
            projectId = "gate4_proj",
            sampleRate = SYNTHESIS_SAMPLE_RATE_HZ,
            globalGapScale = globalGapScale,
            activeLoraVersion = 14,
            clauses = listOf(clause0, clause1, clause2, clause3, clause4)
        )
    }

    private val defaultBindings = listOf(
        VideoAnchorBinding("vid_cut_1", "file:///storage/emulated/0/Movies/cut1.mp4"),
        VideoAnchorBinding("vid_cut_3", "file:///storage/emulated/0/Movies/cut3.mp4"),
        VideoAnchorBinding("vid_cut_4", "file:///storage/emulated/0/Movies/cut4.mp4")
    )

    @Test
    fun gate4_compressGlobalGaps40Percent_shiftsAll3Media3ClippingConfigsWithin1Ms() {
        val baseline = buildFiveClauseManifest(globalGapScale = 1.0f)
        val compressed = baseline.copy(globalGapScale = 0.60f) // 40% gap compression

        val baseSpecs = Media3BeatExporter.buildMedia3ClippingSpecs(baseline, defaultBindings)
        val compressedSpecs = Media3BeatExporter.buildMedia3ClippingSpecs(compressed, defaultBindings)

        assertEquals(3, baseSpecs.size)
        assertEquals(3, compressedSpecs.size)

        // Baseline (100% gaps):
        //   vid_cut_1 = 37,485 samples = 850,000 us
        //   vid_cut_3 = 119,070 samples = 2,700,000 us
        //   vid_cut_4 = 178,605 samples = 4,050,000 us
        assertEquals(850_000L, baseSpecs[0].clippingConfiguration.startPositionUs)
        assertEquals(2_700_000L, baseSpecs[1].clippingConfiguration.startPositionUs)
        assertEquals(4_050_000L, baseSpecs[2].clippingConfiguration.startPositionUs)

        // Expected post-compression (globalGapScale = 0.60f -> 40% shorter gaps):
        //   vid_cut_1 = 22,050 + 2,646 + 11,025 = 35,721 samples -> 810,000 us (-40 ms)
        //   vid_cut_3 = 35,721 + (33,075 - 11,025) + 5,292 + 44,100 + 3,969 = 111,132 samples -> 2,520,000 us (-180 ms)
        //   vid_cut_4 = 111,132 + 44,100 + 2,646 + 11,025 = 168,903 samples -> 3,830,000 us (-220 ms)
        val expectedSamples = listOf(35_721L, 111_132L, 168_903L)
        val expectedUs = listOf(810_000L, 2_520_000L, 3_830_000L)
        val expectedIds = listOf("vid_cut_1", "vid_cut_3", "vid_cut_4")

        val canonicalAnchors = Media3BeatExporter.computeAnchorTimestampsUs(compressed)
        val totalCompressedUs = Media3BeatExporter.computeTotalTimelineDurationUs(compressed)

        for (i in 0 until 3) {
            val spec = compressedSpecs[i]
            assertEquals(expectedIds[i], spec.videoAnchorId)
            assertEquals(expectedSamples[i], spec.anchorSample)
            assertEquals(canonicalAnchors.getValue(expectedIds[i]), spec.startPositionUs)

            val clipStartUs = spec.clippingConfiguration.startPositionUs
            val clipStartMs = spec.clippingConfiguration.startPositionMs
            assertTrue(
                "Anchor ${expectedIds[i]} startPositionUs=$clipStartUs drifted from ${expectedUs[i]}",
                abs(clipStartUs - expectedUs[i]) < 1_000L
            )
            assertEquals(expectedUs[i] / 1_000L, clipStartMs)
            assertEquals(clipStartUs, spec.mediaItem.clippingConfiguration.startPositionUs)

            // Every cut's endPositionUs must lock tightly to the next cut (or total timeline end)
            val expectedEndUs = expectedUs.getOrNull(i + 1) ?: totalCompressedUs
            assertTrue(
                "Anchor ${expectedIds[i]} endPositionUs=${spec.clippingConfiguration.endPositionUs} drifted from $expectedEndUs",
                abs(spec.clippingConfiguration.endPositionUs - expectedEndUs) < 1_000L
            )
        }

        // Verify Composition builds cleanly with video cut sequence + master voice audio sequence
        val composition = Media3BeatExporter.buildComposition(
            manifest = compressed,
            videoBindings = defaultBindings,
            masterAudioUri = "file:///storage/emulated/0/Music/voice_master.wav"
        )
        assertEquals(2, composition.sequences.size)
        val videoSequence = composition.sequences[0]
        // Leading gap (0 -> 810,000 us) + 3 bound video cuts = 4 items in video sequence
        assertEquals(4, videoSequence.editedMediaItems.size)
        assertEquals(
            810_000L,
            videoSequence.editedMediaItems[1].mediaItem.clippingConfiguration.startPositionUs
        )
        assertEquals(
            2_520_000L,
            videoSequence.editedMediaItems[2].mediaItem.clippingConfiguration.startPositionUs
        )
        assertEquals(
            3_830_000L,
            videoSequence.editedMediaItems[3].mediaItem.clippingConfiguration.startPositionUs
        )
    }

    @Test
    fun gate4_swappingSelectedTakeIndex_shiftsDownstreamVideoAnchorsAccurately() {
        val compressedTake0 = buildFiveClauseManifest(globalGapScale = 0.60f, clause1SelectedTake = 0)
        val compressedTake1 = buildFiveClauseManifest(globalGapScale = 0.60f, clause1SelectedTake = 1)

        val specsTake0 = Media3BeatExporter.buildMedia3ClippingSpecs(compressedTake0, defaultBindings)
        val specsTake1 = Media3BeatExporter.buildMedia3ClippingSpecs(compressedTake1, defaultBindings)

        // Take 1 places "cloud" +100,000 us later inside clause 1 and extends clause 1 by +250,000 us.
        val cut1DeltaUs = specsTake1[0].clippingConfiguration.startPositionUs -
            specsTake0[0].clippingConfiguration.startPositionUs
        val cut3DeltaUs = specsTake1[1].clippingConfiguration.startPositionUs -
            specsTake0[1].clippingConfiguration.startPositionUs
        val cut4DeltaUs = specsTake1[2].clippingConfiguration.startPositionUs -
            specsTake0[2].clippingConfiguration.startPositionUs

        assertTrue("Expected +100ms shift on clause 1 anchor, got $cut1DeltaUs us", abs(cut1DeltaUs - 100_000L) < 1_000L)
        assertTrue("Expected +250ms shift on clause 3 anchor, got $cut3DeltaUs us", abs(cut3DeltaUs - 250_000L) < 1_000L)
        assertTrue("Expected +250ms shift on clause 4 anchor, got $cut4DeltaUs us", abs(cut4DeltaUs - 250_000L) < 1_000L)

        assertEquals(910_000L, specsTake1[0].clippingConfiguration.startPositionUs)
        assertEquals(2_770_000L, specsTake1[1].clippingConfiguration.startPositionUs)
        assertEquals(4_080_000L, specsTake1[2].clippingConfiguration.startPositionUs)
    }

    @Test
    fun vernierPhysics_enforcesCoarseAndFine1To20GearRatiosAndZeroCrossSnap() {
        // Coarse waveform drag: 1 dp = 220 samples (~5.0 ms)
        assertEquals(VERNIER_COARSE_SAMPLES_PER_DP.toLong(), VernierPhysics.coarseDragDeltaSamples(-1.0f))
        // Fine Vernier strip: 160 dp (1 inch) = 2,205 samples = 50.0 ms exact
        assertEquals(VERNIER_FINE_SAMPLES_PER_INCH.toLong(), VernierPhysics.fineDragDeltaSamples(-160.0f))
        assertEquals(-VERNIER_FINE_SAMPLES_PER_INCH.toLong(), VernierPhysics.fineDragDeltaSamples(160.0f))

        val manifest = buildFiveClauseManifest(globalGapScale = 0.60f)
        val projection = ClipperTimelineProjection.fromManifest(manifest)
        // Near vid_cut_1 (35,721 samples): 35,760 should snap strictly back to 35,721
        assertEquals(35_721L, projection.nearestZeroCrossingSample(35_760L))
        // Near clause 0 word 1 zeroCrossIn (11,000 samples): 10,950 should snap to 11,000
        assertEquals(11_000L, projection.nearestZeroCrossingSample(10_950L))
    }

    @Test
    fun pcmWaveformExtractor_decodesRealFloat32PcmAndNeverFabricatesWhenMissing() {
        val missingManifest = buildFiveClauseManifest(globalGapScale = 1.0f)
        val missingPeaks = PcmWaveformExtractor.extractMinMaxPeaks(missingManifest)
        assertEquals(0, missingPeaks.size)

        // Write a real 44.1 kHz Float32 little-endian .pcm file for clause 0
        val pcmFile = File(tempFolder.root, "c_000_t0.pcm")
        val sampleCount = 22_150
        val bytes = ByteArray(sampleCount * 4)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val v = if (i % 2 == 0) 0.5f else -0.25f
            buf.putFloat(v)
        }
        pcmFile.writeBytes(bytes)

        val realManifest = buildFiveClauseManifest(
            globalGapScale = 1.0f,
            pcmPaths = mapOf("c_000_t0" to pcmFile.absolutePath)
        )
        val realPeaks = PcmWaveformExtractor.extractMinMaxPeaks(realManifest, samplesPerBucket = 220)
        assertTrue("Expected decoded real peaks", realPeaks.size >= 200)
        assertEquals(-0.25f, realPeaks[0], 1e-5f)
        assertEquals(0.5f, realPeaks[1], 1e-5f)
    }
}
