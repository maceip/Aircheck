package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.BrandChannel
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.estimateDurationSeconds
import com.example.data.formatDurationBadge
import com.example.engine.AudioEngine
import com.example.ui.StudioUiState
import com.example.ui.screens.ScriptScreen
import com.example.ui.theme.AircheckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AircheckStudioTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appNameMatchesAircheckIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Aircheck", context.getString(R.string.app_name))
    }

    @Test
    fun wavRiffHeaderPeakDecodingAndAdultMalePitchEstimatorWorkAccurately() {
        val sampleRate = 16_000
        val seconds = 2
        val totalSamples = sampleRate * seconds
        val targetFreqHz = 118.0 // Adult-male fundamental frequency (< 165 Hz)
        val pcmBuffer = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) {
            val sample = (sin(2.0 * PI * targetFreqHz * i / sampleRate) * 16_000.0).toInt().toShort()
            pcmBuffer.putShort(sample)
        }

        val tmpFile = File.createTempFile("aircheck_test_vo", ".wav")
        try {
            AudioEngine.writeRiffWav(
                file = tmpFile,
                pcmData = pcmBuffer.array(),
                sampleRate = sampleRate,
                channels = 1,
                bitsPerSample = 16
            )

            val meta = AudioEngine.parseWavHeader(tmpFile)
            assertTrue("WAV header must parse cleanly", meta != null)
            assertEquals(sampleRate, meta!!.sampleRate)
            assertEquals(1, meta.channels)
            assertEquals(16, meta.bitsPerSample)
            assertTrue("Duration must be ~2.0s", meta.durationSec in 1.9f..2.1f)

            val peaks = AudioEngine.decodeWavPeaks(tmpFile, 48)
            assertEquals(48, peaks.size)
            assertTrue("Decoded peaks must have real signal amplitude", peaks.maxOrNull()!! > 0.8f)

            val pitchHz = AudioEngine.estimateMedianPitchHz(tmpFile)
            assertTrue(
                "Estimated adult-male pitch ($pitchHz Hz) must be under 165 Hz and near 118 Hz",
                pitchHz in 105f..135f
            )
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun durationEstimationAndFormattingRespectMinimumBeatLength() {
        assertEquals(3, estimateDurationSeconds("Quick hook.", 3))
        assertEquals("05s", formatDurationBadge(5))
        assertEquals("30s", formatDurationBadge(30))
    }

    @Test
    fun scriptScreenLaunchesWideOpenWithoutJargonOrDefaultDrawers() {
        var latestSpark = ""
        var sparkAiTriggered = false

        val initialState = StudioUiState(
            activeProject = ScriptProject(
                title = "",
                ideaSpark = "",
                beats = listOf(ScriptBeat(id = "b1", index = 0, spokenText = ""))
            )
        )

        composeTestRule.setContent {
            AircheckTheme(channel = BrandChannel.VOLT_LIME) {
                ScriptScreen(
                    uiState = initialState,
                    onUpdateTitle = {},
                    onUpdateSpark = { latestSpark = it },
                    onSetTargetDuration = {},
                    onToggleHoneSpark = { sparkAiTriggered = true },
                    onUpdateBeatText = { _, _ -> },
                    onUpdateBeatMetadata = { _, _, _, _, _, _ -> },
                    onToggleExpandBeat = {},
                    onFactCheckBeat = {},
                    onShorten20Percent = {},
                    onAcceptShortProposal = {},
                    onRejectShortProposal = {},
                    onToggleCutBeat = {},
                    onToggleBeatMicRecord = {},
                    onAddBeat = {},
                    onDeleteBeat = {},
                    onDismissError = {},
                    onPrimaryAction = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Clean wide-open inputs & inline ✨ trigger exist immediately
        composeTestRule.onNodeWithTag("spark_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("spark_ai_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("beat_input_b1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("script_primary_cta").assertIsDisplayed()

        // Banned jargon/prefixes must never appear
        composeTestRule.onNodeWithText("01 //", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("TITLE //", substring = true).assertDoesNotExist()

        composeTestRule.onNodeWithTag("spark_input").performTextInput("AI chip prices fell 30%")
        composeTestRule.onNodeWithTag("spark_ai_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(latestSpark.contains("AI chip prices"))
        assertTrue(sparkAiTriggered)
    }
}
