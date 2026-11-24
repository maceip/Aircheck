package com.voicestudio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import com.voicestudio.edl.CandidateTake
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VerificationGateThresholds
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.WordToken
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI verification suite for [ClipperScreen], [VernierScrubCanvas],
 * [VernierScrubBar], and [GapCompressionRail].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ClipperScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passMetrics = VerificationGateThresholds().evaluate(
        cer = 0.0f,
        maxPhonemeDurationMs = 210.0f,
        speakerCosineSim = 0.91f,
        dnsmosP808 = 4.38f,
        tailRmsDb = -54.0f,
        syllablesPerSec = 5.0f
    )

    private fun writeRealPcmFile(name: String, totalSamples: Int, peakAmp: Float = 0.6f): File {
        val file = File(tempFolder.root, name)
        val raw = ByteArray(totalSamples * 4)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) {
            val value = if (i % 2 == 0) peakAmp else -peakAmp * 0.8f
            buf.putFloat(value)
        }
        file.writeBytes(raw)
        return file
    }

    private fun buildTestManifest(): Pair<VoiceProjectManifest, FloatArray> {
        val pcm0 = writeRealPcmFile("c_000_t0.pcm", 22_050)
        val pcm1t0 = writeRealPcmFile("c_001_t0.pcm", 22_050)
        val pcm1t1 = writeRealPcmFile("c_001_t1.pcm", 26_460)

        val manifest = VoiceProjectManifest(
            projectId = "ui_test_proj",
            sampleRate = SYNTHESIS_SAMPLE_RATE_HZ,
            globalGapScale = 1.0f,
            activeLoraVersion = 7,
            clauses = listOf(
                ClauseNode(
                    clauseId = "c_000",
                    index = 0,
                    rawText = "Bare metal",
                    normalizedPhonemes = "b ˈɛ ɹ m ˈɛ ɾ ə l",
                    selectedTakeIndex = 0,
                    baseGapAfterSamples = 4_410,
                    candidates = listOf(
                        CandidateTake(
                            takeId = "c_000_t0",
                            pcmFilePath = pcm0.absolutePath,
                            totalSamples = 22_050L,
                            discreteAudioTokens = intArrayOf(10, 20),
                            seed = 1L,
                            temperature = 0.7f,
                            metrics = passMetrics,
                            tokens = listOf(
                                WordToken("Bare", 0L, 11_025L, 0L, 11_025L, 5_500L, null),
                                WordToken("metal", 11_025L, 22_050L, 11_025L, 22_050L, 16_500L, null)
                            )
                        )
                    )
                ),
                ClauseNode(
                    clauseId = "c_001",
                    index = 1,
                    rawText = "voice deck",
                    normalizedPhonemes = "v ˈɔɪ s d ˈɛ k",
                    selectedTakeIndex = 0,
                    baseGapAfterSamples = 4_410,
                    candidates = listOf(
                        CandidateTake(
                            takeId = "c_001_t0",
                            pcmFilePath = pcm1t0.absolutePath,
                            totalSamples = 22_050L,
                            discreteAudioTokens = intArrayOf(30, 40),
                            seed = 2L,
                            temperature = 0.7f,
                            metrics = passMetrics,
                            tokens = listOf(
                                WordToken("voice", 0L, 11_025L, 0L, 11_025L, 5_500L, "vid_cut_1"),
                                WordToken("deck", 11_025L, 22_050L, 11_025L, 22_050L, 16_500L, null)
                            )
                        ),
                        CandidateTake(
                            takeId = "c_001_t1",
                            pcmFilePath = pcm1t1.absolutePath,
                            totalSamples = 26_460L,
                            discreteAudioTokens = intArrayOf(31, 41),
                            seed = 3L,
                            temperature = 0.75f,
                            metrics = passMetrics,
                            tokens = listOf(
                                WordToken("voice", 0L, 13_230L, 0L, 13_230L, 6_600L, "vid_cut_1"),
                                WordToken("deck", 13_230L, 26_460L, 13_230L, 26_460L, 19_800L, null)
                            )
                        )
                    )
                )
            )
        )
        val peaks = PcmWaveformExtractor.extractMinMaxPeaks(manifest)
        return manifest to peaks
    }

    @Test
    fun missingOtaState_rendersHonestEmptyPanelWithoutFakeWaveformAndTriggersSync() {
        var otaSyncClicks = 0
        var exportClicks = 0

        composeRule.setContent {
            ClipperScreen(
                state = ClipperUiState(
                    manifest = null,
                    otaReady = false,
                    otaStatusText = "Tailscale rig 100.64.0.10 not synced yet.",
                    synthesisError = "Missing s2_pro_4b_q4_k_m.gguf in noBackupFilesDir."
                ),
                actions = ClipperActions(
                    onOpenOtaSync = { otaSyncClicks++ },
                    onExportClip = { exportClicks++ }
                )
            )
        }

        composeRule.onNodeWithTag("clipper_empty_ota_state").assertIsDisplayed()
        composeRule.onNodeWithText("Voice OTA bundle not synced").assertIsDisplayed()
        composeRule.onNodeWithText("Tailscale rig 100.64.0.10 not synced yet.").assertIsDisplayed()
        composeRule.onNodeWithTag("clipper_error_banner").assertIsDisplayed()

        // Zero fake waveforms or pills rendered when OTA is missing
        composeRule.onNodeWithTag("word_pill_row").assertDoesNotExist()
        composeRule.onNodeWithTag("vernier_waveform_canvas").assertDoesNotExist()
        composeRule.onNodeWithTag("gap_compression_rail").assertDoesNotExist()

        // Export button must be honestly disabled
        composeRule.onNodeWithTag("clipper_export_button").assertIsNotEnabled()

        // Sync OTA button triggers real callback
        composeRule.onNodeWithTag("clipper_open_ota_sync_button").performClick()
        assertEquals(1, otaSyncClicks)
        assertEquals(0, exportClicks)
    }

    @Test
    fun wordPillSwap_supportsSwipeDownAndAccessibleBadgeTapInZeroMs() {
        val (initialManifest, initialPeaks) = buildTestManifest()
        var uiState by mutableStateOf(
            ClipperUiState(
                manifest = initialManifest,
                waveformMinMaxPeaks = initialPeaks,
                otaReady = true
            )
        )
        val swaps = mutableListOf<Pair<String, Int>>()

        composeRule.setContent {
            ClipperScreen(
                state = uiState,
                actions = ClipperActions(
                    onSwapTake = { clauseId, nextIdx ->
                        swaps += clauseId to nextIdx
                        val updated = uiState.manifest!!.copy(
                            clauses = uiState.manifest!!.clauses.map { c ->
                                if (c.clauseId == clauseId) c.copy(selectedTakeIndex = nextIdx) else c
                            }
                        )
                        uiState = uiState.copy(manifest = updated)
                    }
                )
            )
        }

        // Initial take badge on c_001 is T1/2
        composeRule.onNodeWithTag("word_pill_swap_c_001_0").assertTextContains("T1/2")

        // 1. Vertical swipe down swaps c_001 from Take 0 -> Take 1
        composeRule.onNodeWithTag("word_pill_c_001_0").performTouchInput {
            swipeDown(startY = top + 2f, endY = bottom + 140f)
        }
        composeRule.waitForIdle()
        assertEquals(listOf("c_001" to 1), swaps)
        composeRule.onNodeWithTag("word_pill_swap_c_001_0").assertTextContains("T2/2")

        // 2. Explicit accessible badge click swaps c_001 back from Take 1 -> Take 0
        composeRule.onNodeWithTag("word_pill_swap_c_001_0").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("c_001" to 1, "c_001" to 0), swaps)
        composeRule.onNodeWithTag("word_pill_swap_c_001_0").assertTextContains("T1/2")
    }

    @Test
    fun wordPillTap_opensInlineTextAndPhonemeEditorAndRequeuesSingleClause() {
        val (initialManifest, initialPeaks) = buildTestManifest()
        var uiState by mutableStateOf(
            ClipperUiState(
                manifest = initialManifest,
                waveformMinMaxPeaks = initialPeaks,
                otaReady = true
            )
        )
        var overriddenClause: Triple<String, String, String>? = null
        var toggledAnchor: Triple<String, Int, String?>? = null

        composeRule.setContent {
            ClipperScreen(
                state = uiState,
                actions = ClipperActions(
                    onOverrideClauseText = { id, text, phonemes ->
                        overriddenClause = Triple(id, text, phonemes)
                    },
                    onToggleVideoAnchor = { id, wordIdx, anchorId ->
                        toggledAnchor = Triple(id, wordIdx, anchorId)
                    }
                )
            )
        }

        // Tap word pill "voice" on c_001 -> opens compact inline editor
        composeRule.onNodeWithTag("word_pill_c_001_0").performClick()
        composeRule.onNodeWithTag("clause_override_editor").assertIsDisplayed()

        // Toggle video anchor button on anchored word unlinks cut -> null
        composeRule.onNodeWithTag("toggle_video_anchor_button").performClick()
        assertEquals(Triple("c_001", 0, null), toggledAnchor)

        // Edit clause text and IPA phonemes, then submit Re-queue
        composeRule.onNodeWithTag("override_raw_text_field").performTextClearance()
        composeRule.onNodeWithTag("override_raw_text_field").performTextInput("studio deck")
        composeRule.onNodeWithTag("override_phonemes_field").performTextClearance()
        composeRule.onNodeWithTag("override_phonemes_field").performTextInput("s t uː d i oʊ d ˈɛ k")
        composeRule.onNodeWithTag("override_submit_button").performClick()

        composeRule.waitForIdle()
        assertEquals(
            Triple("c_001", "studio deck", "s t uː d i oʊ d ˈɛ k"),
            overriddenClause
        )
        composeRule.onNodeWithTag("clause_override_editor").assertDoesNotExist()
    }

    @Test
    fun vernierScrubAndGapCompressionRail_snapToZeroCrossingAndMutateGapScale() {
        val (initialManifest, initialPeaks) = buildTestManifest()
        val projection = ClipperTimelineProjection.fromManifest(initialManifest)
        val validZeroCrossings = projection.wordSpans.flatMap {
            listOf(it.timelineZeroCrossIn, it.timelineZeroCrossOut)
        }.toSet()

        var uiState by mutableStateOf(
            ClipperUiState(
                manifest = initialManifest,
                playheadSample = 11_025L,
                waveformMinMaxPeaks = initialPeaks,
                otaReady = true
            )
        )
        val seekHistory = mutableListOf<Long>()
        var hapticTicks = 0
        var playPauseClicks = 0
        var exportClicks = 0

        composeRule.setContent {
            ClipperScreen(
                state = uiState,
                actions = ClipperActions(
                    onPlayPause = {
                        playPauseClicks++
                        uiState = uiState.copy(isPlaying = !uiState.isPlaying)
                    },
                    onSeekSample = { sample ->
                        seekHistory += sample
                        uiState = uiState.copy(playheadSample = sample)
                    },
                    onGlobalGapScaleChange = { scale ->
                        val updated = uiState.manifest!!.copy(globalGapScale = scale)
                        uiState = uiState.copy(manifest = updated)
                    },
                    onExportClip = { exportClicks++ }
                ),
                onHapticTick = { hapticTicks++ }
            )
        }

        // 1. Drag fine Vernier scrub bar left -> seeks smoothly & magnetically snaps to zero-crossing on release
        composeRule.onNodeWithTag("vernier_scrub_bar").performTouchInput {
            swipeLeft(startX = right - 10f, endX = left + 10f)
        }
        composeRule.waitForIdle()
        assertTrue("Expected seek callbacks during Vernier drag", seekHistory.isNotEmpty())
        assertTrue(
            "Final playhead ${uiState.playheadSample} must snap strictly to a WordToken zero-crossing $validZeroCrossings",
            uiState.playheadSample in validZeroCrossings
        )
        assertTrue("Expected CLOCK_TICK haptic on Vernier drag release", hapticTicks >= 1)

        // 2. Coarse drag on waveform canvas also snaps & fires haptic tick
        val ticksBeforeCoarse = hapticTicks
        composeRule.onNodeWithTag("vernier_waveform_canvas").performTouchInput {
            swipeLeft(startX = center.x + 80f, endX = center.x - 80f)
        }
        composeRule.waitForIdle()
        assertTrue(uiState.playheadSample in validZeroCrossings)
        assertTrue(hapticTicks > ticksBeforeCoarse)

        // 3. Mutate GapCompressionRail to 60% via semantics action and verify live duration delta
        composeRule.onNodeWithTag("gap_compression_rail").performSemanticsAction(SemanticsActions.SetProgress) {
            it(0.60f)
        }
        composeRule.waitForIdle()
        assertEquals(0.60f, uiState.manifest!!.globalGapScale, 1e-4f)
        composeRule.onNodeWithTag("gap_scale_percent").assertTextContains("60%")
        // 2 clauses * 4,410 base gap samples = 8,820 gap samples; 40% compression = -3,528 samples = -80ms
        composeRule.onNodeWithTag("gap_duration_delta").assertTextContains("-80ms")

        // 4. Vertical drag down on GapCompressionRail further compresses globalGapScale
        composeRule.onNodeWithTag("gap_compression_rail").performTouchInput {
            swipeDown(startY = top + 20f, endY = bottom - 20f)
        }
        composeRule.waitForIdle()
        assertTrue(
            "Expected swipeDown to compress globalGapScale below 0.60f, got ${uiState.manifest!!.globalGapScale}",
            uiState.manifest!!.globalGapScale < 0.60f
        )

        // 5. Play/Pause & Export buttons fire real callbacks
        composeRule.onNodeWithTag("clipper_play_pause_button").performClick()
        composeRule.onNodeWithTag("clipper_export_button").performClick()
        assertEquals(1, playPauseClicks)
        assertEquals(1, exportClicks)
        assertTrue(uiState.isPlaying)
    }
}
