package com.example

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.BreakingNewsStyle
import com.example.data.BrollSegmentMode
import com.example.data.CorePipelineSession
import com.example.data.CoreRenderOptions
import com.example.data.LlmProviderChoice
import com.example.data.LlmStage
import com.example.data.NewsroomLook
import com.example.data.OverlayObjectKind
import com.example.draft.BrushNudge
import com.example.draft.DraftTone
import com.example.draft.ShotType
import com.example.ui.StudioViewModel
import com.example.ui.draft.DraftDeckViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Live Android 17 Running-App E2E for Clip Studio v5 (`01 SPARK` -> `02 SCRIPT` -> `03 CUT` ->
 * `Video look` + Settings) against the LIVE Mac `:8443` server (`local_mlx` + `remote_bailian`
 * LLM drafting passes, real `180x252` rig shot previews, Room persistence, and live backdrop plate
 * + AI headline polish).
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
    fun fullClipStudioV5EndToEndWithLiveMacServer() {
        val firebasePresent = try {
            Class.forName("com.google.firebase.FirebaseApp")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        assertEquals("Firebase SDK must NOT exist in Aircheck APK", false, firebasePresent)

        val screenshotDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "e2e_v5_screenshots").apply { mkdirs() }
        val density = composeRule.activity.resources.displayMetrics.density

        // Point profileStore cleanly at the live Mac :8443 rig and clear prior overlays for deterministic verification
        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(false)
            app.container.profileStore.setTailscaleRigUrl("http://127.0.0.1:8443")
            app.container.profileStore.setLlmStageConfig(LlmStage.DRAFTING, LlmProviderChoice.MAC_RIG_DEFAULT)
            app.container.profileStore.setLlmStageConfig(LlmStage.SCRIPT_EDIT, LlmProviderChoice.MAC_RIG_DEFAULT)
            app.container.profileStore.setLlmStageConfig(LlmStage.NEWSROOM_OVERLAY, LlmProviderChoice.MAC_RIG_DEFAULT)
            app.container.localRepo.listSavedNewsroomOverlays().forEach {
                app.container.localRepo.deleteNewsroomOverlay(it.id)
            }
            app.container.localRepo.newsroomLooksOnce().forEach {
                app.container.localRepo.deleteNewsroomLook(it.id)
            }
            app.container.localRepo.saveNewsroomLook(
                NewsroomLook(
                    name = "LOOK_01",
                    plateId = "plate_8c805a9f4007",
                    plateUrl = "http://127.0.0.1:8443/v1/plates/plate_8c805a9f4007.png",
                    platePrompt = "sleek dark obsidian television newsroom anchor desk, crimson and cobalt LED ticker walls, shallow depth of field",
                    blurPercent = 0,
                    brightnessEv = 0.0,
                    light = NewsroomLook.LIGHT_NEUTRAL,
                    isDefault = true
                )
            )
            app.container.localRepo.saveCorePipelineSession(CorePipelineSession())
        }

        // 1. 01 SPARK Empty State: select tone pills -> tap "type it instead" -> live /v1/draft/angles + /v1/draft/arc + shotPreviews
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("cs_spark_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread {
            studioVm.testMacRigConnection("http://127.0.0.1:8443")
            draftVm.probeRigHealth()
            draftVm.resetNewDraft()
            studioVm.dismissToast()
            studioVm.dismissNotice()
            studioVm.dismissError()
        }
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("cs_type_instead").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("cs_tone_direct").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { draftVm.uiState.value.tone == DraftTone.DIRECT }
        composeRule.onNodeWithTag("cs_tone_hot_take").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { draftVm.uiState.value.tone == DraftTone.HOT_TAKE }
        captureScreenshot(screenshotDir, "01_spark_empty_v5.png")

        composeRule.onNodeWithTag("cs_type_instead").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_typed_note_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_voice_note_editor").performTextReplacement(
            "Monthly AI video editing subscriptions are obsolete now that flagship mobile silicon runs voice cloning, captions, and multi-shot cuts locally on your phone with zero monthly rent."
        )
        Espresso.closeSoftKeyboard()
        Thread.sleep(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cs_typed_note_submit").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            if (composeRule.onAllNodesWithTag("cs_typed_note_sheet").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithTag("cs_typed_note_submit").performClick()
                false
            } else {
                true
            }
        }

        composeRule.waitUntil(timeoutMillis = 120_000L) {
            val d = draftVm.uiState.value.document
            d.arcBeats.size == 4 && d.extractedAngles.isNotEmpty() && !draftVm.uiState.value.isBusy
        }
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            val previews = draftVm.uiState.value.shotPreviews
            previews.isNotEmpty() &&
                previews.values.any { path -> File(path).isFile && File(path).length() > 500L } &&
                draftVm.uiState.value.inFlightShotIds.isEmpty()
        }

        val firstPreviewFile = draftVm.uiState.value.shotPreviews.values
            .map(::File)
            .first { it.isFile && it.length() > 500L }
        val decodedBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(firstPreviewFile.absolutePath, decodedBounds)
        assertEquals("Expected 180px wide rig shot preview JPEG", 180, decodedBounds.outWidth)
        assertEquals("Expected 252px tall rig shot preview JPEG", 252, decodedBounds.outHeight)
        Thread.sleep(600L)
        composeRule.waitForIdle()
        captureScreenshot(screenshotDir, "02_spark_outline_v5.png")

        // Open Anchors sheet -> toggle anchor + "more" + "keep N anchors"
        composeRule.onNodeWithTag("cs_anchors_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_anchors_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        val keep = draftVm.uiState.value.document.extractedAngles.first()
        composeRule.onNodeWithTag("cs_keep_${keep.id}").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            draftVm.uiState.value.document.extractedAngles.first { it.id == keep.id }.selected != keep.selected
        }
        composeRule.onNodeWithTag("cs_fresh_angles").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            !draftVm.uiState.value.isBusy && !draftVm.uiState.value.isExtractingAngles &&
                draftVm.uiState.value.document.extractedAngles.isNotEmpty()
        }
        captureScreenshot(screenshotDir, "03_spark_anchors_v5.png")
        composeRule.onNodeWithTag("cs_anchors_done").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_anchors_sheet").fetchSemanticsNodes().isEmpty()
        }

        // Cycle beat 0 shot 0 through all 4 shot types (face -> pip -> broll -> card -> back)
        val initialType = draftVm.uiState.value.document.arcBeats[0].shots[0].type
        var expectedNext = initialType.nextInCycle()
        repeat(4) {
            composeRule.onNodeWithTag("cs_shot_0_0").performClick()
            val target = expectedNext
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                draftVm.uiState.value.document.arcBeats[0].shots[0].type == target
            }
            expectedNext = expectedNext.nextInCycle()
        }

        // Tap "+" on beat 0 to add a b-roll shot
        val shotsBeforeAdd = draftVm.uiState.value.document.arcBeats[0].shots.size
        composeRule.onNodeWithTag("cs_add_shot_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            draftVm.uiState.value.document.arcBeats[0].shots.size == shotsBeforeAdd + 1
        }
        assertEquals(ShotType.BROLL, draftVm.uiState.value.document.arcBeats[0].shots.last().type)

        // Tap sparkle rewrite on beat 1 (`cs_reroll_beat_1`)
        val beat1Before = draftVm.uiState.value.document.arcBeats[1].text
        composeRule.onNodeWithTag("cs_reroll_beat_1").performClick()
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            val d = draftVm.uiState.value.document
            d.lastRerolledBeat == 1 && !draftVm.uiState.value.isBusy && draftVm.uiState.value.rerollingBeatIndex < 0
        }
        assertNotEquals(beat1Before, draftVm.uiState.value.document.arcBeats[1].text)

        // Ensure at least one CARD shot is present in the 4-beat plan before writing script
        if (draftVm.uiState.value.document.arcBeats.flatMap { it.shots }.none { it.type == ShotType.CARD }) {
            val lastBeatIdx = draftVm.uiState.value.document.arcBeats.lastIndex
            val lastShotIdx = draftVm.uiState.value.document.arcBeats[lastBeatIdx].shots.lastIndex
            while (draftVm.uiState.value.document.arcBeats[lastBeatIdx].shots[lastShotIdx].type != ShotType.CARD) {
                composeRule.onNodeWithTag("cs_shot_${lastBeatIdx}_$lastShotIdx").performClick()
                composeRule.waitForIdle()
            }
        }

        // Tap "write the script" -> auto-opens 02 SCRIPT
        composeRule.onNodeWithTag("cs_write_script").performClick()
        composeRule.waitUntil(timeoutMillis = 150_000L) {
            val d = draftVm.uiState.value.document
            d.isVideoScript && d.candidates.size == 3 && !draftVm.uiState.value.isBusy &&
                composeRule.onAllNodesWithTag("cs_script_root").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "04_script_versions_v5.png")

        // 2. 02 SCRIPT: switch 3-way posture, toggle length row + shorter, keyboard edit + undo, single-tap phrase swap
        composeRule.onNodeWithTag("cs_version_1").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { draftVm.uiState.value.document.activeCandidateIndex == 1 }
        composeRule.onNodeWithTag("cs_version_2").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { draftVm.uiState.value.document.activeCandidateIndex == 2 }
        composeRule.onNodeWithTag("cs_version_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { draftVm.uiState.value.document.activeCandidateIndex == 0 }

        composeRule.onNodeWithTag("cs_length_toggle").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_length_row").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "05_script_length_v5.png")
        composeRule.onNodeWithTag("cs_length_shorter").performClick()
        composeRule.waitUntil(timeoutMillis = 90_000L) { !draftVm.uiState.value.isBusy }
        composeRule.onNodeWithTag("cs_length_toggle").performClick()

        // Full-script keyboard edit + undo
        val textBeforeKeyboard = draftVm.uiState.value.document.activeCandidate!!.plainText()
        composeRule.onNodeWithTag("cs_keyboard").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_raw_editor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_raw_editor").performTextReplacement(
            "$textBeforeKeyboard\n\nPay once for the silicon in your pocket."
        )
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("cs_raw_apply").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            !draftVm.uiState.value.rawKeyboardMode &&
                draftVm.uiState.value.document.activeCandidate!!.plainText().contains("silicon in your pocket")
        }
        dismissToastIfPresent()
        composeRule.onNodeWithTag("cs_script_undo").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            draftVm.uiState.value.document.activeCandidate!!.plainText() == textBeforeKeyboard
        }
        dismissToastIfPresent()

        // Single-tap phrase on cs_para_0 -> live /v1/draft/brush-swap SWAP THIS PHRASE + nudges + pin + keep this
        composeRule.onNodeWithTag("cs_para_0").performTouchInput {
            click(Offset(96f * density, 22f * density))
        }
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            val st = draftVm.uiState.value
            st.selectedPhrase != null && st.brushAlternatives.isNotEmpty() && !st.isBusy
        }
        composeRule.onNodeWithTag("cs_phrase_sheet").assertIsDisplayed()
        captureScreenshot(screenshotDir, "06_script_phrase_swap_v5.png")

        composeRule.onNodeWithTag("cs_nudge_sharper").performClick()
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            draftVm.uiState.value.activeBrushNudge == BrushNudge.SHARPER && !draftVm.uiState.value.isBusy
        }
        composeRule.onNodeWithTag("cs_nudge_simpler").performClick()
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            draftVm.uiState.value.activeBrushNudge == BrushNudge.SIMPLER && !draftVm.uiState.value.isBusy
        }
        composeRule.onNodeWithTag("cs_nudge_more").performClick()
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            draftVm.uiState.value.activeBrushNudge == BrushNudge.MORE && !draftVm.uiState.value.isBusy
        }

        // Commit phrase swap via "keep this" (`cs_keep_phrase`)
        composeRule.onNodeWithTag("cs_keep_phrase").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { draftVm.uiState.value.selectedPhrase == null }

        // Single-tap a second phrase on cs_para_0 and verbatim-lock it via "pin" (`cs_pin_phrase`, which locks & dismisses)
        composeRule.onNodeWithTag("cs_para_0").performTouchInput {
            click(Offset(96f * density, 22f * density))
        }
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            draftVm.uiState.value.selectedPhrase != null && !draftVm.uiState.value.isBusy
        }
        val pinRange = checkNotNull(draftVm.uiState.value.selectedPhrase)
        composeRule.onNodeWithTag("cs_pin_phrase").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            draftVm.isRangeLocked(pinRange) && draftVm.uiState.value.selectedPhrase == null
        }

        // Tap "make the video" -> maps shotPlan (BROLL_CUTAWAY + clean QUOTE_BRIEF card) into 03 CUT
        composeRule.onNodeWithTag("cs_create_video").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isNotEmpty() &&
                studioVm.uiState.value.corePipelineSession.clauses.isNotEmpty() &&
                studioVm.uiState.value.savedOverlays.any { it.kind == OverlayObjectKind.QUOTE_BRIEF }
        }
        composeRule.runOnUiThread { studioVm.dismissNotice() }
        composeRule.waitForIdle()

        val initialCutSession = studioVm.uiState.value.corePipelineSession
        assertTrue(
            "shotPlan must map PIP/BROLL beats onto BROLL_CUTAWAY clauses",
            initialCutSession.clauses.any { it.segmentMode == BrollSegmentMode.BROLL_CUTAWAY }
        )
        val seededQuoteCard = studioVm.uiState.value.savedOverlays.first { it.kind == OverlayObjectKind.QUOTE_BRIEF }
        assertTrue(seededQuoteCard.id in initialCutSession.selectedOverlayIds)
        assertFalse(
            "Quote card headline must strip LLM prompt prefixes, got: ${seededQuoteCard.headline}",
            seededQuoteCard.headline.lowercase().startsWith("text card") ||
                seededQuoteCard.headline.lowercase().startsWith("quote card")
        )

        // 3. 03 CUT: verify 218dp monitor, tool pills, words <-> b-roll mode, single-tap clause edit + undo
        composeRule.onNodeWithTag("cs_cut_monitor").assertIsDisplayed()
        fun persistedSession() = runBlocking { app.container.localRepo.getLatestCorePipelineSession() }!!

        val capBefore = persistedSession().renderOptions.captions
        composeRule.onNodeWithTag("cs_cut_captions").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { persistedSession().renderOptions.captions == !capBefore }
        composeRule.onNodeWithTag("cs_cut_captions").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { persistedSession().renderOptions.captions == capBefore }

        val stripBefore = persistedSession().renderOptions.stripSilence
        composeRule.onNodeWithTag("cs_cut_strip_silence").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { persistedSession().renderOptions.stripSilence == !stripBefore }

        val punchBefore = persistedSession().renderOptions.punchIn
        composeRule.onNodeWithTag("cs_cut_punch_in").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { persistedSession().renderOptions.punchIn == !punchBefore }

        composeRule.onNodeWithTag("cs_cut_aspect").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().renderOptions.aspect == CoreRenderOptions.ASPECT_HORIZONTAL
        }
        composeRule.onNodeWithTag("cs_cut_aspect").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().renderOptions.aspect == CoreRenderOptions.ASPECT_VERTICAL
        }

        // Switch words <-> b-roll mode and toggle clause 0
        composeRule.onNodeWithTag("cs_cut_mode_timeline").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_timeline_toggle_0").fetchSemanticsNodes().isNotEmpty()
        }
        val mode0Before = persistedSession().clauses[0].segmentMode
        composeRule.onNodeWithTag("cs_timeline_toggle_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) { persistedSession().clauses[0].segmentMode != mode0Before }
        captureScreenshot(screenshotDir, "07_cut_broll_mode_v5.png")
        dismissToastIfPresent()
        composeRule.onNodeWithTag("cs_cut_mode_transcript").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_clause_0").fetchSemanticsNodes().isNotEmpty()
        }

        // Single-tap clause 0 -> expand inline actions -> edit words -> save words -> undo
        val clause0Original = persistedSession().clauses[0].spokenText
        composeRule.onNodeWithTag("cs_clause_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_clause_edit_btn_0").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "08_cut_clause_expanded_v5.png")
        composeRule.onNodeWithTag("cs_clause_edit_btn_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_clause_editor_0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_clause_editor_0").performTextReplacement(
            "Local mobile silicon makes cloud video subscriptions obsolete."
        )
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("cs_clause_apply_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().clauses[0].spokenText == "Local mobile silicon makes cloud video subscriptions obsolete."
        }
        composeRule.onNodeWithTag("cs_cut_undo").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().clauses[0].spokenText == clause0Original
        }

        // 4. Video look: open -> verify unclipped LIGHT bar -> duplicate -> generate backdrop -> sliders -> title -> cards -> use look
        composeRule.onNodeWithTag("cs_cut_look").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_newsroom_root").fetchSemanticsNodes().isNotEmpty()
        }

        // Verify BLUR, BRIGHTNESS, and 3-way LIGHT all sit 100% unclipped above the bottom dock BEFORE scrolling
        val lightWarmBounds = composeRule.onNodeWithTag("cs_light_warm").fetchSemanticsNode().boundsInRoot
        val useLookBounds = composeRule.onNodeWithTag("cs_use_look").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "3-way LIGHT segmented control must sit completely unclipped above 'use this look' dock " +
                "(lightBottom=${lightWarmBounds.bottom}, dockTop=${useLookBounds.top})",
            lightWarmBounds.height > 20f && lightWarmBounds.bottom <= useLookBounds.top
        )

        // Duplicate current look directly via header `cs_look_duplicate` & verify Room persistence + toast
        val looksBeforeDup = runBlocking { app.container.localRepo.newsroomLooksOnce().size }
        composeRule.onNodeWithTag("cs_look_duplicate").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { app.container.localRepo.newsroomLooksOnce().size } >= looksBeforeDup + 1 &&
                studioVm.uiState.value.newsroomLooks.isNotEmpty() &&
                composeRule.onAllNodesWithTag("cs_toast").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("look duplicated").assertIsDisplayed()
        dismissToastIfPresent()

        val activeLookId = studioVm.uiState.value.newsroomLooks.first().id

        // Verify live :8443 plate renders inside VideoLookPreviewPanel (`cs_look_plate`), AND generate a brand-new
        // studio backdrop plate via `cs_backdrop_prompt` + `cs_backdrop_generate` against live :8443 (`200 OK`, zero errors).
        val priorPlateId = runBlocking { app.container.localRepo.getNewsroomLook(activeLookId)?.plateId }
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("cs_look_plate").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_backdrop_prompt").performScrollTo().performClick()
        composeRule.onNodeWithTag("cs_backdrop_prompt").performTextReplacement(
            "architectural dark graphite broadcast studio, warm tungsten rim light, soft depth of field"
        )
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("cs_backdrop_generate").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 120_000L) {
            val l = runBlocking { app.container.localRepo.getNewsroomLook(activeLookId) }
            !studioVm.uiState.value.isGeneratingBackdrop &&
                l != null &&
                !l.plateId.isNullOrBlank() &&
                l.plateId != priorPlateId &&
                !l.plateUrl.isNullOrBlank()
        }
        assertNull(
            "Backdrop generation must succeed cleanly on live :8443 without errors",
            studioVm.uiState.value.errorMessage
        )
        composeRule.onNodeWithTag("cs_look_plate").assertIsDisplayed()

        // Scroll back up to ACCENT / BLUR / BRIGHTNESS / LIGHT, step values, and capture clean unclipped screenshot
        composeRule.onNodeWithTag("cs_accent_cobalt").performScrollTo().performClick()
        composeRule.onNodeWithTag("cs_scrub_blur_inc").performScrollTo().performClick()
        composeRule.onNodeWithTag("cs_scrub_blur_inc").performClick()
        composeRule.onNodeWithTag("cs_scrub_ev_inc").performScrollTo().performClick()
        composeRule.onNodeWithTag("cs_light_warm").performScrollTo().performClick()
        composeRule.onNodeWithTag("cs_accent_crimson").performScrollTo()
        Thread.sleep(900L)
        composeRule.waitForIdle()
        captureScreenshot(screenshotDir, "09_video_look_background_v5.png")

        // Switch to `Style` (`cs_newsroom_tab_1`) -> toggle Top-Left News Logo Bug + edit bug text + edit title + AI refresh + tag pills
        composeRule.onNodeWithTag("cs_newsroom_tab_1").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_bug_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_bug_toggle").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().renderOptions.showBrandBug
        }
        composeRule.onNodeWithTag("cs_bug_input").performTextReplacement("AIRCHECK LIVE")
        Espresso.closeSoftKeyboard()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().renderOptions.cornerBugText == "AIRCHECK LIVE"
        }

        composeRule.onNodeWithTag("cs_title_input").performScrollTo().performTextReplacement("Monthly editing subscriptions are over")
        Espresso.closeSoftKeyboard()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().renderOptions.chyronHeadline == "Monthly editing subscriptions are over"
        }

        composeRule.onNodeWithTag("cs_title_refresh").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            !studioVm.uiState.value.isRefreshingHeadline &&
                studioVm.uiState.value.toastMessage == "title refreshed"
        }
        assertTrue(persistedSession().renderOptions.chyronHeadline.isNotBlank())
        dismissToastIfPresent()

        composeRule.onNodeWithTag("cs_tag_developing").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().renderOptions.breakingStyle == BreakingNewsStyle.DEVELOPING
        }
        composeRule.onNodeWithTag("cs_tag_breaking").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().renderOptions.breakingStyle == BreakingNewsStyle.BREAKING
        }
        captureScreenshot(screenshotDir, "10_video_look_title_v5.png")

        // Switch to `Cards` (`cs_newsroom_tab_2`) -> verify clean attached quote card copy, inline edit, & toggle off/on
        composeRule.onNodeWithTag("cs_newsroom_tab_2").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_cards_summary").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("1 quote card attached").assertIsDisplayed()
        composeRule.onNodeWithTag("cs_attached_card_line_0").assertIsDisplayed()
        assertTrue(
            "Card summary must not contain raw 'Text card reading:' prefix",
            composeRule.onAllNodesWithText("Text card reading", substring = true).fetchSemanticsNodes().isEmpty()
        )
        // Edit quote card inline & verify Room persistence
        composeRule.onNodeWithTag("cs_overlay_edit_${seededQuoteCard.id}").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_card_headline_field_${seededQuoteCard.id}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_card_headline_field_${seededQuoteCard.id}").performTextReplacement("Own the silicon once")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("cs_card_save_${seededQuoteCard.id}").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            studioVm.uiState.value.savedOverlays.firstOrNull { it.id == seededQuoteCard.id }?.headline == "Own the silicon once"
        }
        captureScreenshot(screenshotDir, "11_video_look_cards_v5.png")

        // Toggle saved card off -> "0 cards attached", then toggle back on -> "1 quote card attached"
        composeRule.onNodeWithTag("cs_overlay_${seededQuoteCard.id}").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("0 cards attached").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_overlay_${seededQuoteCard.id}").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("1 quote card attached").fetchSemanticsNodes().isNotEmpty()
        }

        // Switch to `B-Roll` (`cs_newsroom_tab_3`) -> toggle Full Cut & Split 50/50 framing
        composeRule.onNodeWithTag("cs_newsroom_tab_3").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_broll_framing_full").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_broll_framing_full").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().renderOptions.brollFraming == CoreRenderOptions.BROLL_FRAMING_FULL
        }
        composeRule.onNodeWithTag("cs_broll_framing_split").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            persistedSession().renderOptions.brollFraming == CoreRenderOptions.BROLL_FRAMING_SPLIT
        }
        captureScreenshot(screenshotDir, "11b_video_look_broll_v5.png")

        // Tap `Tweak AI` (`cs_tweak_ai_button`) -> exercise live POST /v1/ai-assist (mode=tweak_video_look)
        composeRule.onNodeWithTag("cs_tweak_ai_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_ai_tweak_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_ai_tweak_input").performTextReplacement(
            "Use cobalt blue accent, keep warm lighting, set blur to 10% and brightness +0.2 EV, and keep top-left logo AIRCHECK LIVE"
        )
        Espresso.closeSoftKeyboard()
        captureScreenshot(screenshotDir, "11c_video_look_tweak_ai_sheet.png")
        composeRule.onNodeWithTag("cs_ai_tweak_submit").performClick()
        composeRule.waitUntil(timeoutMillis = 90_000L) {
            !studioVm.uiState.value.isTweakingLook &&
                composeRule.onAllNodesWithTag("cs_toast").fetchSemanticsNodes().isNotEmpty()
        }
        assertNull("Tweak AI must succeed without error", studioVm.uiState.value.errorMessage)
        dismissToastIfPresent()

        // Tap "use this look" -> returns to 03 CUT with persisted default look + v5 toast
        composeRule.onNodeWithTag("cs_use_look").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("cs_toast").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("look applied · render to see it").assertIsDisplayed()
        captureScreenshot(screenshotDir, "12_cut_look_applied_toast_v5.png")

        val persistedLook = runBlocking { app.container.localRepo.getDefaultNewsroomLook() }
        assertNotNull(persistedLook)
        assertEquals(activeLookId, persistedLook!!.id)
        assertEquals(10, persistedLook.blurPercent)
        assertEquals(0.2, persistedLook.brightnessEv, 1e-6)
        assertEquals(NewsroomLook.LIGHT_WARM, persistedLook.light)
        assertEquals(NewsroomLook.ACCENT_COBALT, persistedLook.accentHex)

        dismissToastIfPresent()

        // Open Settings modal from 03 CUT and verify live Mac :8443 rig health & Voice Care entry
        composeRule.onNodeWithTag("cs_cut_settings").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("open_voice_model_care_button").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "13_cut_settings_v5.png")
    }

    private fun dismissToastIfPresent() {
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithTag("cs_toast_ok").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("cs_toast_ok").performClick()
            composeRule.waitForIdle()
        }
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
