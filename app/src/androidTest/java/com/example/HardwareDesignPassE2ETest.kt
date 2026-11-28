package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.HardwareTheme
import com.example.ui.design.AcTokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on-device UI test for the final hardware design pass:
 * 1. Persisted Stealth Black / Light hardware theme switching in SystemDeckSheet + live AcTokens update.
 * 2. Spark Recorder bezel grille & calibrated waveform display.
 * 3. Cut monitor readout pill, timeline lanes, and Newsroom -> Backdrop Exposure detent ruler stepping.
 */
@RunWith(AndroidJUnit4::class)
class HardwareDesignPassE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun hardwareDesignPassControlsAndPersistenceWorkEndToEnd() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AircheckApplication
        val profileStore = app.container.profileStore

        // Start from a clean Light finish so assertions are deterministic.
        runBlocking { profileStore.setHardwareTheme(HardwareTheme.LIGHT) }
        composeRule.waitForIdle()

        // 1. Spark Recorder panel: grille, waveform, and concave hold-to-record key.
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("cs_spark_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_recorder_grille").assertIsDisplayed()
        composeRule.onNodeWithTag("cs_recorder_waveform").assertIsDisplayed()
        composeRule.onNodeWithTag("cs_seed_hold_record").assertIsDisplayed()

        // 2. Open Settings -> toggle Stealth Black finish -> verify live AcTokens & Proto DataStore persistence.
        composeRule.onNodeWithTag("cs_spark_settings").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("system_deck_lazy_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("system_deck_lazy_list")
            .performScrollToNode(hasTestTag("hardware_theme_card"))
        composeRule.onNodeWithTag("hardware_theme_dark").performClick()
        composeRule.waitForIdle()

        assertTrue("AcTokens should switch to dark Stealth Black palette", AcTokens.isDark)
        composeRule.onNodeWithTag("system_deck_lazy_list")
            .performScrollToNode(hasTestTag("hardware_theme_card"))
        composeRule.onNodeWithTag("hardware_theme_dark").assertIsSelected()
        composeRule.waitUntil(5_000L) {
            runBlocking { profileStore.current().hardwareTheme == HardwareTheme.DARK }
        }
        assertEquals(HardwareTheme.DARK, runBlocking { profileStore.current().hardwareTheme })

        // Verify Mac Studio server status label renders cleanly.
        composeRule.onNodeWithTag("system_deck_lazy_list")
            .performScrollToNode(hasTestTag("rig_connection_status_label"))
        composeRule.onNodeWithTag("rig_connection_status_label").assertIsDisplayed()

        // Switch back to Light and verify persistence.
        composeRule.onNodeWithTag("system_deck_lazy_list")
            .performScrollToNode(hasTestTag("hardware_theme_card"))
        composeRule.onNodeWithTag("hardware_theme_light").performClick()
        composeRule.waitForIdle()
        assertFalse("AcTokens should return to Light palette", AcTokens.isDark)
        composeRule.waitUntil(5_000L) {
            runBlocking { profileStore.current().hardwareTheme == HardwareTheme.LIGHT }
        }

        // Close Settings -> go to 02 CUT -> verify monitor & SIGMA mode pill -> Newsroom -> Backdrop ruler.
        composeRule.onNodeWithTag("close_system_deck_button").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("cs_crumb_2").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_crumb_2").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("cs_cut_monitor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_cut_monitor").assertIsDisplayed()
        composeRule.onNodeWithTag("cs_cut_monitor_mode", useUnmergedTree = true).assertIsDisplayed()

        // Navigate to Newsroom -> Backdrop and exercise the machined Exposure fader ruler.
        composeRule.onNodeWithTag("cs_cut_look").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("cs_newsroom_backdrop").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_newsroom_backdrop").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithTag("cs_backdrop_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_scrub_ev").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("cs_scrub_ev_inc").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cs_scrub_ev_dec").performClick()
        composeRule.waitForIdle()
    }
}
