package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AircheckEndToEndTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fullBlueOceanUserFlowsNav3PersistenceAndZeroSilentFallback() {
        val app = composeRule.activity.application as AircheckApplication

        // 1. Wide-open Script canvas boots immediately with zero legacy jargon
        composeRule.onNodeWithTag("spark_input").assertIsDisplayed()
        composeRule.onNodeWithTag("spark_ai_button").assertIsDisplayed()
        composeRule.onNodeWithTag("target_duration_button").assertIsDisplayed()
        composeRule.onNodeWithText("01 //", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("TITLE //", substring = true).assertDoesNotExist()

        // 2. Edit title, spark, beat text, and cycle target duration (persists into Room WAL)
        composeRule.onNodeWithTag("script_title_input").performTextClearance()
        composeRule.onNodeWithTag("script_title_input").performTextInput("Blue Ocean Launch")
        composeRule.onNodeWithTag("spark_input").performTextClearance()
        composeRule.onNodeWithTag("spark_input").performTextInput("Cloud talking-head pipeline under $1.")
        composeRule.onNodeWithTag("target_duration_button").performClick()
        composeRule.waitForIdle()

        // Scroll to Add Beat button and add a second beat
        composeRule.onNodeWithTag("script_lazy_list").performScrollToNode(hasTestTag("add_beat_button"))
        composeRule.onNodeWithTag("add_beat_button").performClick()
        composeRule.waitForIdle()

        val savedScripts = runBlocking {
            app.container.localRepo.getScriptById(
                app.container.profileStore.current().activeScriptId
            )
        }
        assertTrue("Active script must persist in Room WAL", savedScripts != null)
        assertEquals("Blue Ocean Launch", savedScripts!!.title)
        assertTrue("Script must contain at least 2 beats after Add Beat", savedScripts.beats.size >= 2)

        // 3. Nav3 3-Tab Multi-Backstack: switch Script -> Photo -> Clips -> Script
        composeRule.onNodeWithTag("dock_tab_photo").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pick_photo_button").assertIsDisplayed()
        composeRule.onNodeWithTag("photo_lazy_list").performScrollToNode(hasTestTag("stage4_mode_saved_loop_latentsync"))
        composeRule.onNodeWithTag("stage4_mode_saved_loop_latentsync").performClick()
        composeRule.onNodeWithTag("photo_lazy_list").performScrollToNode(hasTestTag("voice_source_mic_voiceover"))
        composeRule.onNodeWithTag("voice_source_mic_voiceover").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            runBlocking {
                val cur = app.container.profileStore.current()
                cur.stage4Mode.name == "SAVED_LOOP_LATENTSYNC" && cur.voiceSource.name == "MIC_VOICEOVER"
            }
        }

        // Verify Proto DataStore persisted Stage 4 mode & Voice source
        val settingsAfterPhoto = runBlocking { app.container.profileStore.current() }
        assertEquals("SAVED_LOOP_LATENTSYNC", settingsAfterPhoto.stage4Mode.name)
        assertEquals("MIC_VOICEOVER", settingsAfterPhoto.voiceSource.name)

        // Zero Silent Fallback Proof: tapping Render surfaces visible pinned error banner immediately
        composeRule.onNodeWithTag("photo_render_cta").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("studio_error_banner").assertIsDisplayed()

        // Switch to Clips tab and back to Script tab -> verify state & scroll retained cleanly
        composeRule.onNodeWithTag("dock_tab_clips").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("clips_render_cta").assertIsDisplayed()

        composeRule.onNodeWithTag("dock_tab_script").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("script_lazy_list").performScrollToIndex(0)
        composeRule.onNodeWithText("Blue Ocean Launch").assertIsDisplayed()

        // 4. Account & Persona Sheet -> save real head_model_id & voice_id to Proto DataStore
        composeRule.onNodeWithTag("top_bar_account_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("account_lazy_list").performScrollToNode(hasTestTag("save_persona_ids_button"))
        composeRule.onNodeWithTag("head_model_id_input").performTextClearance()
        composeRule.onNodeWithTag("head_model_id_input").performTextInput("ryan_head_v1")
        composeRule.onNodeWithTag("voice_id_input").performTextClearance()
        composeRule.onNodeWithTag("voice_id_input").performTextInput("ryan_voice_v1")
        composeRule.onNodeWithTag("save_persona_ids_button").performClick()
        composeRule.waitForIdle()

        val enrolled = runBlocking { app.container.profileStore.current().persona }
        assertEquals("ryan_head_v1", enrolled.headModelId)
        assertEquals("ryan_voice_v1", enrolled.voiceId)

        composeRule.onNodeWithTag("close_account_sheet_button").performClick()
        composeRule.waitForIdle()

        // 5. System Deck -> verify real persisted BrandChannel switch & CAS cache readout
        composeRule.onNodeWithTag("top_bar_system_deck_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("channel_toggle_broadcast_cobalt").performClick()
        composeRule.onNodeWithTag("cas_cache_size_label").assertIsDisplayed()
        composeRule.onNodeWithTag("clear_cas_cache_button").performClick()
        composeRule.waitForIdle()

        val finalSettings = runBlocking { app.container.profileStore.current() }
        assertEquals("BROADCAST_COBALT", finalSettings.brandChannel.name)

        composeRule.onNodeWithTag("close_system_deck_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("script_lazy_list").performScrollToIndex(0)
        composeRule.onNodeWithTag("spark_input").assertIsDisplayed()
    }
}
