package com.example

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.BrollSegmentMode
import com.example.data.CoreRenderOptions
import com.example.data.NewsroomLook
import com.example.draft.BrushNudge
import com.example.graph.GraphContactRecord
import com.example.graph.GraphPostRecord
import com.example.ui.StudioViewModel
import com.example.ui.draft.DraftDeckViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Live Android 17 Running-App E2E for Clip Studio v4 (`01 SPARK` -> `02 SCRIPT` -> `03 CUT` ->
 * `04 NEWSROOM` + Settings) against the LIVE Mac `:8443` server (`local_mlx` + `remote_bailian`
 * mixed LLM drafting passes + Room persistence + real SQLite FTS5 X Network sheet).
 */
@RunWith(AndroidJUnit4::class)
class AircheckRunningAppE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val draftVm: DraftDeckViewModel
        get() = ViewModelProvider(composeRule.activity)[DraftDeckViewModel::class.java]
    private val studioVm: StudioViewModel
        get() = ViewModelProvider(composeRule.activity)[StudioViewModel::class.java]
    private val app: AircheckApplication
        get() = composeRule.activity.application as AircheckApplication

    @Before
    fun grantRuntimePermissions() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun fullClipStudioV4EndToEndWithLiveMacServer() {
        val firebasePresent = try {
            Class.forName("com.google.firebase.FirebaseApp")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        assertEquals("Firebase SDK must NOT exist in Aircheck APK", false, firebasePresent)

        val screenshotDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "e2e_screenshots").apply { mkdirs() }
        val density = composeRule.activity.resources.displayMetrics.density

        // 1. 01 SPARK: enter voice note -> live /v1/draft/angles + /v1/draft/arc (4 beats)
        composeRule.waitUntil(timeoutMillis = 12_000L) {
            composeRule.onAllNodesWithTag("cs_spark_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread { draftVm.resetNewDraft() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("cs_voice_note_toggle").performClick()
        composeRule.onNodeWithTag("cs_voice_note_editor").performTextClearance()
        composeRule.onNodeWithTag("cs_voice_note_editor").performTextInput(
            "Every phone brand brags about bigger sensors yet default phone video looks more artificial every fall. " +
                "Auto-HDR flattens skin tones and bakes edge halos, so filmmakers install manual log apps just to turn sharpening off."
        )
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("cs_voice_note_toggle").performClick()

        composeRule.waitUntil(timeoutMillis = 90_000L) {
            val d = draftVm.uiState.value.document
            d.arcBeats.size == 4 && d.extractedAngles.isNotEmpty() && !draftVm.uiState.value.isBusy
        }
        composeRule.onNodeWithTag("cs_spark_target").assertIsDisplayed()
        captureScreenshot(screenshotDir, "v4_01_spark.png")

        // Toggle angle chips + re-roll beat 1
        val keep = draftVm.uiState.value.document.extractedAngles.first()
        composeRule.onNodeWithTag("cs_keep_${keep.id}").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            draftVm.uiState.value.document.extractedAngles.first { it.id == keep.id }.selected != keep.selected
        }

        val beat1Before = draftVm.uiState.value.document.arcBeats[1].text
        composeRule.onNodeWithTag("cs_arc_beat_1").performClick()
        composeRule.waitUntil(timeoutMillis = 60_000L) {
            val d = draftVm.uiState.value.document
            d.lastRerolledBeat == 1 && !draftVm.uiState.value.isBusy
        }
        assertNotEquals(beat1Before, draftVm.uiState.value.document.arcBeats[1].text)

        // Write 60s script -> opens 02 SCRIPT
        composeRule.onNodeWithTag("cs_write_script").performClick()
        composeRule.waitUntil(timeoutMillis = 120_000L) {
            val d = draftVm.uiState.value.document
            d.isVideoScript && d.candidates.size == 3 && !draftVm.uiState.value.isBusy &&
                composeRule.onAllNodesWithTag("cs_script_root").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "v4_02_script.png")

        // 2. 02 SCRIPT: switch posture v2 -> v1, tap first word twice -> live /v1/draft/brush-swap -> nudge + pin + undo
        composeRule.onNodeWithTag("cs_version_1").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000L) { draftVm.uiState.value.document.activeCandidateIndex == 1 }
        composeRule.onNodeWithTag("cs_version_0").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000L) { draftVm.uiState.value.document.activeCandidateIndex == 0 }

        composeRule.onNodeWithTag("cs_para_0").performTouchInput {
            click(Offset(62f * density, 16f * density))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cs_para_0").performTouchInput {
            click(Offset(62f * density, 16f * density))
        }
        composeRule.waitUntil(timeoutMillis = 60_000L) {
            val st = draftVm.uiState.value
            st.brushAlternatives.isNotEmpty() && st.activeBrushRange != null && !st.isBusy
        }
        captureScreenshot(screenshotDir, "v4_02b_selected_phrase.png")

        composeRule.onNodeWithTag("cs_nudge_simpler").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 60_000L) {
            draftVm.uiState.value.activeBrushNudge == BrushNudge.SIMPLER && !draftVm.uiState.value.isBusy
        }
        val pinRange = draftVm.uiState.value.activeBrushRange!!
        composeRule.onNodeWithTag("cs_pin_phrase").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { draftVm.isRangeLocked(pinRange) }

        // Populate 1 real local SQLite FTS5 graph contact & open X Network drawer
        val nowMs = System.currentTimeMillis()
        app.container.localGraphDatabase.upsertContactsAndPosts(
            contacts = listOf(
                GraphContactRecord(
                    handle = "mobile_eng_peer",
                    displayName = "Mobile Eng Peer",
                    bio = "Mobile camera sensor and video pipeline engineer",
                    followerCount = 4200,
                    mutualRepliesCount = 5,
                    lastInteractionEpochMs = nowMs - 2L * 86_400_000L
                )
            ),
            posts = listOf(
                GraphPostRecord(
                    tweetId = "1900000000000000007",
                    handle = "mobile_eng_peer",
                    text = "Phone sensors keep growing while auto-HDR sharpening makes video look more artificial.",
                    createdAtEpochMs = nowMs - 86_400_000L,
                    isReplyToUser = true
                )
            )
        )
        composeRule.onNodeWithTag("cs_x_network_bar").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_x_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "v4_02c_x_network.png")
        composeRule.onNodeWithTag("cs_x_network_bar").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_x_sheet").fetchSemanticsNodes().isEmpty()
        }

        // 3. Create video -> 03 CUT: verify StereoMeter, render option persistence, timeline B-roll, transcript edit + undo
        composeRule.onNodeWithTag("cs_create_video").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isNotEmpty() &&
                studioVm.uiState.value.corePipelineSession.clauses.isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_stereo_meter").assertIsDisplayed()
        captureScreenshot(screenshotDir, "v4_03_cut.png")

        fun persisted() = runBlocking { app.container.localRepo.getLatestCorePipelineSession() }!!
        composeRule.onNodeWithTag("cs_cut_captions").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { persisted().renderOptions.captions }
        composeRule.onNodeWithTag("cs_cut_aspect").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { persisted().renderOptions.aspect == CoreRenderOptions.ASPECT_HORIZONTAL }
        composeRule.onNodeWithTag("cs_cut_aspect").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { persisted().renderOptions.aspect == CoreRenderOptions.ASPECT_VERTICAL }
        composeRule.onNodeWithTag("cs_cut_punch_in").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { persisted().renderOptions.punchIn }

        composeRule.onNodeWithTag("cs_cut_mode_timeline").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_timeline_toggle_0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_timeline_toggle_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { persisted().clauses[0].segmentMode == BrollSegmentMode.BROLL_CUTAWAY }
        captureScreenshot(screenshotDir, "v4_03b_timeline.png")
        composeRule.onNodeWithTag("cs_cut_mode_transcript").performClick()

        composeRule.onNodeWithTag("cs_clause_0").performTouchInput { longClick() }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_clause_editor_0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_clause_editor_0").performTextClearance()
        composeRule.onNodeWithTag("cs_clause_editor_0").performTextInput("Phone sensors grow while default video looks worse.")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("cs_clause_apply_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persisted().clauses[0].spokenText.contains("Phone sensors grow")
        }

        // 4. 04 NEWSROOM: open look studio, create look, adjust draggable EV tick ruler, warm/grain/border, save, set default
        composeRule.onNodeWithTag("cs_cut_look").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_newsroom_root").fetchSemanticsNodes().isNotEmpty()
        }
        val beforeLooks = studioVm.uiState.value.newsroomLooks.size
        composeRule.onNodeWithTag("cs_look_new").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { app.container.localRepo.newsroomLooksOnce().size } == beforeLooks + 1
        }
        val newLook = studioVm.uiState.value.newsroomLooks.first()
        composeRule.onNodeWithTag("cs_scrub_ev_inc").performScrollTo().performClick()
        composeRule.onNodeWithTag("cs_scrub_ev_inc").performClick()
        composeRule.onNodeWithTag("cs_pill_warm").performScrollTo().performClick()
        composeRule.onNodeWithTag("cs_pill_grain").performClick()
        composeRule.onNodeWithTag("cs_pill_border").performClick()
        composeRule.onNodeWithTag("cs_look_save").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            val l = runBlocking { app.container.localRepo.getNewsroomLook(newLook.id) }
            l != null && l.brightnessEv == 0.2 && l.light == NewsroomLook.LIGHT_WARM && l.grain && l.border
        }
        composeRule.onNodeWithTag("cs_look_default").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { app.container.localRepo.getDefaultNewsroomLook() }?.id == newLook.id
        }
        captureScreenshot(screenshotDir, "v4_04_newsroom.png")

        // Return to 03 CUT and open Settings modal to verify live Mac :8443 rig health
        composeRule.onNodeWithTag("cs_newsroom_back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_cut_settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_cut_settings").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("system_deck_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "v4_05_settings.png")
        assertTrue(studioVm.uiState.value.newsroomLooks.isNotEmpty())
    }

    private fun captureScreenshot(dir: File, filename: String) {
        composeRule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (bmp != null) {
            FileOutputStream(File(dir, filename)).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 92, out)
            }
            bmp.recycle()
        }
    }
}
