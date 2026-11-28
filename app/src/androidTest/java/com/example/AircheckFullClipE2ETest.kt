package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.BreakingNewsStyle
import com.example.data.ClipTransitionType
import com.example.data.CorePipelineSession
import com.example.data.CoreRenderOptions
import com.example.data.CutTimeline
import com.example.data.ExportState
import com.example.data.NewsroomLook
import com.example.data.OverlayObjectKind
import com.example.draft.DraftTone
import com.example.ui.RigConnectionStatus
import com.example.ui.StudioViewModel
import com.example.ui.draft.DraftDeckViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * One end-to-end run that generates a real clip on the live rig and touches every feature on the
 * way: 01 SPARK (tone, typed idea, outline, anchors, beat edit and rewrite), voice (rig TTS),
 * talking head, 02 CUT (play, trim, split, B-roll generate + framing, transitions per join and for
 * every join, captions), Newsroom (AI graphic set, chyron, corner bug, a new stat graphic, LEDs),
 * Backdrop (accent, light, grain, border, generated set), the studio render, and export.
 *
 * Nothing is mocked. Run it on the Pixel (USB, `adb reverse tcp:8443 tcp:8443`) or Limrun with the
 * rig up (`aircheck-rig start`). Optional instrumentation arg `rigUrl` overrides the saved rig URL
 * for the run and is restored afterwards.
 *
 * Every stage is recorded as PASS, FAIL or NOT RUN in `e2e_full_clip/report.json` under the app's
 * external files dir, plus a screenshot per stage. Outputs stay on the device; never commit them.
 */
@RunWith(AndroidJUnit4::class)
class AircheckFullClipE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val draftVm: DraftDeckViewModel
        get() = ViewModelProvider(composeRule.activity)[DraftDeckViewModel::class.java]
    private val studioVm: StudioViewModel
        get() = ViewModelProvider(composeRule.activity)[StudioViewModel::class.java]
    private val app: AircheckApplication
        get() = composeRule.activity.application as AircheckApplication

    private val outDir: File by lazy {
        File(app.getExternalFilesDir(null) ?: app.filesDir, "e2e_full_clip").apply { mkdirs() }
    }
    private val stages = mutableListOf<JSONObject>()
    private val facts = JSONObject()
    private var savedRigUrl: String? = null

    @Before
    fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        // The run lasts far longer than the screen timeout: wake the device and keep this activity
        // visible (over the lock screen too) for the whole run. Test-only window flags, no settings.
        inst.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        composeRule.runOnUiThread {
            composeRule.activity.setShowWhenLocked(true)
            composeRule.activity.setTurnScreenOn(true)
            composeRule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        inst.waitForIdleSync()
    }

    @After
    fun tearDown() {
        savedRigUrl?.let { url -> runBlocking { app.container.profileStore.setTailscaleRigUrl(url) } }
    }

    @Test
    fun generatesARealClipUsingEveryFeature() {
        val planned = listOf(
            "rig", "spark_idea", "spark_anchors", "spark_beats", "voice", "talking_head",
            "cut_trim_split", "cut_broll", "cut_transitions", "cut_captions", "newsroom_ai_set",
            "newsroom_chyron", "newsroom_bug", "newsroom_new_graphic", "backdrop", "render", "export"
        )
        try {
            runAll()
        } finally {
            val ran = stages.map { it.getString("stage") }.toSet()
            planned.filterNot { it in ran }.forEach { stages += JSONObject().put("stage", it).put("result", "NOT RUN") }
            val report = JSONObject().put("stages", JSONArray(stages)).put("facts", facts)
            File(outDir, "report.json").writeText(report.toString(2))
            stages.forEach { Log.i(TAG, "STAGE ${it.getString("stage")} ${it.getString("result")} ${it.optString("detail")}") }
            Log.i(TAG, "FACTS $facts")
        }
    }

    private fun runAll() {
        val density = composeRule.activity.resources.displayMetrics.density
        fun session(): CorePipelineSession = studioVm.uiState.value.corePipelineSession
        fun persisted(): CorePipelineSession = runBlocking { app.container.localRepo.getLatestCorePipelineSession() }!!

        stage("rig") {
            val override = InstrumentationRegistry.getArguments().getString("rigUrl")?.trim()?.ifBlank { null }
            if (override != null) {
                savedRigUrl = runBlocking { app.container.profileStore.settingsFlow.first().tailscaleRigUrl }
                runBlocking { app.container.profileStore.setTailscaleRigUrl(override) }
            }
            val url = runBlocking { app.container.profileStore.settingsFlow.first().tailscaleRigUrl }
            composeRule.waitUntil(15_000L) { composeRule.onAllNodesWithTag("cs_spark_root").fetchSemanticsNodes().isNotEmpty() }
            composeRule.runOnUiThread {
                studioVm.testMacRigConnection(url)
                draftVm.probeRigHealth()
                draftVm.resetNewDraft()
                studioVm.dismissToast()
                studioVm.dismissNotice()
                studioVm.dismissError()
            }
            waitFor("rig online", 30_000L) { studioVm.uiState.value.rigHealth.status == RigConnectionStatus.ONLINE }
            val settings = studioVm.uiState.value.settings
            facts.put("requested_voice_backend", settings.rigVoiceBackend)
            facts.put("requested_video_backend", settings.rigVideoBackend)
            "rig online (override=${override != null})"
        }

        val resume = InstrumentationRegistry.getArguments().getString("resumeFromCut")?.trim() == "true"
        facts.put("resumed", resume)
        if (resume) {
            // Ryan chose "resume from the existing head": reuse the voice + talking head already made on
            // this phone instead of generating them again. Those stages are SKIPPED, never PASS.
            val s0 = persisted()
            val wav = studioVm.casStore.fileForSha(s0.ttsVoiceSha, "wav")
            val head = studioVm.casStore.fileForSha(s0.talkingHeadSha, "mp4")
            stage("resume") {
                assertTrue("latest session ${s0.id} has no voice", s0.hasTtsAudio)
                assertTrue("latest session ${s0.id} has no talking head", s0.hasTalkingHead)
                assertTrue("voice wav ${s0.ttsVoiceSha} missing from CAS", wav != null && wav.length() > 1_000L)
                assertTrue("talking head mp4 ${s0.talkingHeadSha} missing from CAS", head != null && head.length() > 1_000L)
                composeRule.onNodeWithTag("cs_crumb_2").performClick()
                waitFor("cut opened on session ${s0.id}", 20_000L) {
                    composeRule.onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isNotEmpty() && session().id == s0.id
                }
                // Clear earlier runs' edits so the cut stages start clean: trims and every B-roll block.
                composeRule.runOnUiThread { studioVm.resetCutClips() }
                waitFor("trims reset", 5_000L) { persisted().cutClips.isEmpty() }
                persisted().brollSegments.map { it.id }.forEach { id ->
                    composeRule.runOnUiThread { studioVm.removeBrollSegment(id) }
                    waitFor("b-roll $id removed", 5_000L) { persisted().brollSegments.none { it.id == id } }
                }
                composeRule.runOnUiThread { studioVm.dismissToast(); studioVm.dismissNotice(); studioVm.dismissError() }
                val probe = probeVideo(Uri.fromFile(head!!))
                facts.put("tts_source", s0.ttsSource.ifBlank { "unknown" })
                facts.put("tts_duration_ms", s0.ttsDurationMs)
                facts.put("head_video", probe)
                screenshot("resume_cut")
                "session ${s0.id}, voice ${s0.ttsDurationMs} ms, head ${probe.optLong("duration_ms")} ms, trims and b-roll cleared"
            }
            val skipped = "SKIPPED (resumed from session ${s0.id}, head sha ${s0.talkingHeadSha})"
            listOf("spark_idea", "spark_anchors", "spark_beats", "voice", "talking_head").forEach {
                stages += JSONObject().put("stage", it).put("result", "SKIPPED").put("detail", skipped)
            }
        } else {
        stage("spark_idea") {
            composeRule.onNodeWithTag("cs_tone_explainer").scrollIntoView().performClick()
            waitFor("tone explainer", 5_000L) { draftVm.uiState.value.tone == DraftTone.EXPLAINER }
            composeRule.onNodeWithTag("cs_type_instead").performClick()
            waitForTag("cs_typed_note_sheet")
            composeRule.onNodeWithTag("cs_voice_note_editor").performTextReplacement(IDEA)
            Espresso.closeSoftKeyboard()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("cs_typed_note_submit").performClick()
            waitFor("4-beat outline", 180_000L) {
                val st = draftVm.uiState.value
                st.document.arcBeats.size == 4 && !st.isBusy && !st.isExtractingAngles
            }
            screenshot("spark_outline")
            "4 beats, ${draftVm.uiState.value.document.extractedAngles.size} anchors offered"
        }

        stage("spark_anchors") {
            composeRule.onNodeWithTag("cs_anchors_button").performClick()
            waitForTag("cs_anchors_sheet")
            val angles = draftVm.uiState.value.document.extractedAngles
            var detail = "no offered anchors"
            if (angles.isNotEmpty()) {
                val first = angles.first()
                composeRule.onNodeWithTag("cs_keep_${first.id}").scrollIntoView().performClick()
                waitFor("anchor toggled", 5_000L) {
                    draftVm.uiState.value.document.extractedAngles.firstOrNull { it.id == first.id }?.selected != first.selected
                }
                detail = "toggled ${first.id}"
            }
            composeRule.onNodeWithTag("cs_anchor_custom_input").scrollIntoView().performTextReplacement(CUSTOM_ANCHOR)
            Espresso.closeSoftKeyboard()
            composeRule.onNodeWithTag("cs_anchor_custom_add").scrollIntoView().performClick()
            waitFor("custom anchor", 5_000L) {
                val d = draftVm.uiState.value.document
                (d.extractedAngles + d.blindspotAngles).any { it.label.contains("one-time", ignoreCase = true) && it.selected }
            }
            screenshot("spark_anchors")
            composeRule.onNodeWithTag("cs_anchors_done").scrollIntoView().performClick()
            waitFor("beats after anchors", 180_000L) {
                val st = draftVm.uiState.value
                composeRule.onAllNodesWithTag("cs_anchors_sheet").fetchSemanticsNodes().isEmpty() &&
                    st.document.arcBeats.size == 4 && !st.isBusy && st.rerollingBeatIndices.isEmpty()
            }
            "$detail, custom anchor added and applied"
        }

        stage("spark_beats") {
            composeRule.onNodeWithTag("cs_beat_edit_0").scrollIntoView().performClick()
            waitForTag("cs_beat_editor_0")
            composeRule.onNodeWithTag("cs_beat_editor_0").performTextReplacement(EDITED_HOOK)
            Espresso.closeSoftKeyboard()
            composeRule.onNodeWithTag("cs_beat_save_0").performClick()
            waitFor("beat 0 saved", 5_000L) { draftVm.uiState.value.document.arcBeats.firstOrNull()?.text == EDITED_HOOK }
            val beat2Before = draftVm.uiState.value.document.arcBeats[2].text
            composeRule.onNodeWithTag("cs_reroll_beat_2").scrollIntoView().performClick()
            waitFor("beat 2 rewritten", 120_000L) {
                val st = draftVm.uiState.value
                st.rerollingBeatIndices.isEmpty() && !st.isBusy && st.document.arcBeats[2].text != beat2Before
            }
            screenshot("spark_beats_edited")
            "beat 0 edited, beat 2 rewritten by rig"
        }

        stage("voice") {
            composeRule.onNodeWithTag("cs_create_video").performClick()
            waitFor("cut opened", 20_000L) {
                composeRule.onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isNotEmpty() && session().clauses.isNotEmpty()
            }
            // make the video auto-starts Stage 1 TTS; tap the primary only if it did not.
            Thread.sleep(2_000L)
            if (!studioVm.uiState.value.isCoreStageBusy && !session().hasTtsAudio) {
                composeRule.onNodeWithTag("cs_cut_primary").performClick()
            }
            waitForStage("voice", 20 * 60_000L) { session().hasTtsAudio }
            val wav = studioVm.casStore.fileForSha(session().ttsVoiceSha, "wav")
            val s = session()
            facts.put("tts_source", s.ttsSource.ifBlank { "unknown" })
            facts.put("tts_duration_ms", s.ttsDurationMs)
            assertTrue("voice duration must be measured > 0", s.ttsDurationMs > 0L)
            assertTrue("voice wav must be on the phone or at a rig URL", (wav != null && wav.length() > 1_000L) || !s.ttsAudioUrl.isNullOrBlank())
            screenshot("cut_voiced")
            "${s.clauses.size} lines, ${s.ttsDurationMs} ms, source=${s.ttsSource.ifBlank { "unknown" }}"
        }

        stage("talking_head") {
            composeRule.onNodeWithTag("cs_cut_primary").performClick()
            waitForStage("talking head", 45 * 60_000L) { session().hasTalkingHead }
            val s = session()
            val head = studioVm.casStore.fileForSha(s.talkingHeadSha, "mp4")
            val probe = head?.let { probeVideo(Uri.fromFile(it)) }
            facts.put("head_video", probe ?: JSONObject().put("local_file", false).put("url_present", !s.talkingHeadVideoUrl.isNullOrBlank()))
            assertTrue("talking head must exist", head != null || !s.talkingHeadVideoUrl.isNullOrBlank())
            probe?.let { assertTrue("talking head must have a video track", it.optBoolean("has_video")) }
            screenshot("cut_head")
            "head ${probe?.optLong("duration_ms") ?: "remote"} ms"
        }

        }

        stage("cut_trim_split") {
            val trimIn = composeRule.onNode(tagPrefix("cs_trim_in_"))
            trimIn.performTouchInput { swipeRight(startX = centerX, endX = centerX + 24f * density) }
            waitFor("trimmed in", 5_000L) { CutTimeline.effectiveClips(persisted()).first().sourceStartMs > 0L }
            // Move the playhead with real playback, then split there.
            composeRule.onNodeWithTag("cs_cut_well_play").scrollIntoView().performClick()
            Thread.sleep(2_500L)
            composeRule.onNodeWithTag("cs_cut_well_play").performClick()
            composeRule.waitForIdle()
            val clipsBefore = CutTimeline.effectiveClips(persisted()).size
            composeRule.onNodeWithTag("cs_cut_split").scrollIntoView().performClick()
            waitFor("split", 5_000L) { CutTimeline.effectiveClips(persisted()).size == clipsBefore + 1 }
            screenshot("cut_trim_split")
            val clips = CutTimeline.effectiveClips(persisted())
            "clips=${clips.size}, first starts at ${clips.first().sourceStartMs} ms"
        }

        stage("cut_broll") {
            val before = persisted().brollSegments.map { it.id }.toSet()
            composeRule.onNodeWithTag("cs_cut_add_broll").scrollIntoView().performClick()
            waitFor("segment added", 5_000L) { persisted().brollSegments.any { it.id !in before } }
            val segId = persisted().brollSegments.first { it.id !in before }.id
            // The new block sits under the playhead and partly under a line-linked block, so a touch on it
            // lands on another target (run 3 opened the other segment's sheet; run 4's edge tap left the
            // sheet open for under a second). Invoke the block's own click action instead.
            composeRule.onNodeWithTag("cs_broll_block_$segId").scrollIntoView()
                .performSemanticsAction(SemanticsActions.OnClick)
            waitForTag("cs_broll_sheet")
            composeRule.waitForIdle()
            Thread.sleep(1_000L)
            screenshot("cut_broll_sheet_open")
            assertTrue("b-roll sheet must stay open", composeRule.onAllNodesWithTag("cs_broll_sheet").fetchSemanticsNodes().isNotEmpty())
            composeRule.onNodeWithTag("cs_broll_sheet_prompt").performTextReplacement(BROLL_PROMPT)
            Espresso.closeSoftKeyboard()
            composeRule.onNodeWithTag("cs_broll_sheet_generate").performClick()
            waitFor("b-roll generated", 10 * 60_000L) {
                val segs = persisted().brollSegments
                val seg = segs.firstOrNull { it.id == segId }
                failOnError("b-roll")
                segs.firstOrNull { it.id != segId && it.prompt == BROLL_PROMPT && !it.mediaId.isNullOrBlank() }?.let {
                    throw AssertionError("b-roll sheet edited ${it.id}, not the new segment $segId")
                }
                seg != null && (!seg.sha.isNullOrBlank() || !seg.mediaId.isNullOrBlank()) &&
                    segId !in studioVm.uiState.value.generatingBrollSegmentIds
            }
            composeRule.onNodeWithTag("cs_broll_framing_${CoreRenderOptions.BROLL_FRAMING_FULL}").scrollIntoView().performClick()
            waitFor("framing full", 5_000L) {
                persisted().brollSegments.first { it.id == segId }.framingOverride == CoreRenderOptions.BROLL_FRAMING_FULL
            }
            screenshot("cut_broll_sheet")
            composeRule.onNodeWithTag("cs_broll_sheet_done").scrollIntoView().performClick()
            waitFor("b-roll sheet closed", 5_000L) { composeRule.onAllNodesWithTag("cs_broll_sheet").fetchSemanticsNodes().isEmpty() }
            val seg = persisted().brollSegments.first { it.id == segId }
            "segment ${seg.durationMs} ms, media=${seg.mediaId ?: seg.sha}"
        }

        stage("cut_transitions") {
            composeRule.onNodeWithTag("cs_cut_transitions").performClick()
            waitForTag("cs_transition_sheet")
            composeRule.onNodeWithTag("cs_transition_tile_${ClipTransitionType.DISSOLVE.wire}").scrollIntoView().performClick()
            composeRule.onNodeWithTag("cs_transition_speed_350").scrollIntoView().performClick()
            composeRule.onNodeWithTag("cs_transition_apply_all").scrollIntoView().performClick()
            waitFor("dissolve on every join", 5_000L) {
                persisted().clauses.drop(1).all { it.transitionType == ClipTransitionType.DISSOLVE.wire }
            }
            composeRule.onNodeWithTag("cs_transition_done").scrollIntoView().performClick()
            waitFor("sheet closed", 5_000L) { composeRule.onAllNodesWithTag("cs_transition_sheet").fetchSemanticsNodes().isEmpty() }
            // One join gets its own transition from its diamond.
            val join = composeRule.onAllNodes(tagPrefix("cs_cut_join_")).fetchSemanticsNodes().firstOrNull()
            var detail = "dissolve on every join, no single join visible"
            if (join != null) {
                val tag = join.config[SemanticsProperties.TestTag]
                val idx = tag.removePrefix("cs_cut_join_").toInt()
                composeRule.onNodeWithTag(tag).scrollIntoView().performClick()
                waitForTag("cs_transition_sheet")
                composeRule.onNodeWithTag("cs_transition_tile_${ClipTransitionType.FADE_BLACK.wire}").scrollIntoView().performClick()
                waitFor("join $idx dip black", 5_000L) { persisted().clauses[idx].transitionType == ClipTransitionType.FADE_BLACK.wire }
                composeRule.onNodeWithTag("cs_transition_done").scrollIntoView().performClick()
                detail = "dissolve on every join, dip black on join $idx"
            }
            screenshot("cut_transitions")
            detail
        }

        stage("cut_captions") {
            if (!persisted().renderOptions.captions) composeRule.onNodeWithTag("cs_cut_captions").scrollIntoView().performClick()
            waitFor("captions on", 5_000L) { persisted().renderOptions.captions }
            "captions on"
        }

        stage("newsroom_ai_set") {
            composeRule.onNodeWithTag("cs_cut_look").performClick()
            waitForTag("cs_newsroom_root")
            val before = studioVm.uiState.value.savedOverlays.size
            composeRule.onNodeWithTag("cs_generate_overlay_set").performClick()
            waitFor("AI graphic set", 180_000L) {
                failOnError("graphic set")
                !studioVm.uiState.value.isGeneratingOverlaySet && studioVm.uiState.value.savedOverlays.size > before
            }
            screenshot("newsroom_ai_set")
            "${studioVm.uiState.value.savedOverlays.size - before} graphics generated"
        }

        stage("newsroom_chyron") {
            if (!persisted().renderOptions.showLowerThird) composeRule.onNodeWithTag("cs_graphic_led_chyron").performClick()
            waitFor("chyron on", 5_000L) { persisted().renderOptions.showLowerThird }
            composeRule.onNodeWithTag("cs_graphic_card_chyron").performClick()
            waitForTag("cs_graphic_sheet_chyron")
            composeRule.onNodeWithTag("cs_title_refresh").performClick()
            waitFor("AI headline", 120_000L) { !studioVm.uiState.value.isRefreshingHeadline }
            composeRule.onNodeWithTag("cs_title_input").performTextReplacement(CHYRON)
            composeRule.onNodeWithTag("cs_subline_input").performTextReplacement(SUBLINE)
            Espresso.closeSoftKeyboard()
            composeRule.onNodeWithTag("cs_tag_${BreakingNewsStyle.DEVELOPING.wire}").scrollIntoView().performClick()
            composeRule.onNodeWithTag("cs_graphic_save").scrollIntoView().performClick()
            waitFor("chyron saved", 5_000L) {
                val o = persisted().renderOptions
                o.chyronHeadline == CHYRON && o.chyronSubline == SUBLINE && o.breakingStyle == BreakingNewsStyle.DEVELOPING
            }
            "chyron headline, subline and DEVELOPING tag saved"
        }

        stage("newsroom_bug") {
            if (!persisted().renderOptions.showBrandBug) composeRule.onNodeWithTag("cs_graphic_led_bug").performClick()
            waitFor("bug on", 5_000L) { persisted().renderOptions.showBrandBug }
            composeRule.onNodeWithTag("cs_graphic_card_bug").scrollIntoView().performClick()
            waitForTag("cs_graphic_sheet_bug")
            composeRule.onNodeWithTag("cs_bug_input").performTextReplacement(BUG)
            Espresso.closeSoftKeyboard()
            composeRule.onNodeWithTag("cs_graphic_save").performClick()
            waitFor("bug saved", 5_000L) { persisted().renderOptions.cornerBugText == BUG && persisted().renderOptions.showBrandBug }
            "corner bug $BUG on"
        }

        stage("newsroom_new_graphic") {
            val before = studioVm.uiState.value.savedOverlays.map { it.id }.toSet()
            composeRule.onNodeWithTag("cs_newsroom_new").performClick()
            waitForTag("cs_graphic_sheet_overlay")
            composeRule.onNodeWithTag("cs_new_ovl_kind_${OverlayObjectKind.STAT_CALLOUT.name}").performClick()
            composeRule.onNodeWithTag("cs_overlay_text").performTextReplacement(STAT_TEXT)
            composeRule.onNodeWithTag("cs_overlay_number").performTextReplacement(STAT_NUMBER)
            Espresso.closeSoftKeyboard()
            composeRule.onNodeWithTag("cs_overlay_secs_5").performClick()
            composeRule.onNodeWithTag("cs_graphic_save").scrollIntoView().performClick()
            waitFor("stat graphic attached", 10_000L) {
                val added = studioVm.uiState.value.savedOverlays.firstOrNull { it.id !in before }
                added != null && added.kind == OverlayObjectKind.STAT_CALLOUT && added.id in persisted().selectedOverlayIds
            }
            screenshot("newsroom_graphics")
            "stat graphic attached; ${persisted().selectedOverlayIds.size} graphics attached in total"
        }

        stage("backdrop") {
            composeRule.onNodeWithTag("cs_newsroom_backdrop").performClick()
            waitForTag("cs_backdrop_root")
            composeRule.onNodeWithTag("cs_accent_cobalt").scrollIntoView().performClick()
            composeRule.onNodeWithTag("cs_light_warm").scrollIntoView().performClick()
            composeRule.onNodeWithTag("cs_look_grain").scrollIntoView().performClick()
            composeRule.onNodeWithTag("cs_look_border").scrollIntoView().performClick()
            fun look(): NewsroomLook? = runBlocking { app.container.localRepo.getDefaultNewsroomLook() }
            waitFor("look saved", 10_000L) {
                val l = look()
                l != null && l.accentHex.equals(NewsroomLook.ACCENT_COBALT, ignoreCase = true) && l.light == NewsroomLook.LIGHT_WARM
            }
            val priorPlate = look()?.plateId
            composeRule.onNodeWithTag("cs_backdrop_prompt").scrollIntoView().performTextReplacement(BACKDROP_PROMPT)
            Espresso.closeSoftKeyboard()
            composeRule.onNodeWithTag("cs_backdrop_generate").performClick()
            waitFor("backdrop generated", 15 * 60_000L) {
                failOnError("backdrop")
                val l = look()
                !studioVm.uiState.value.isGeneratingBackdrop && l != null && !l.plateId.isNullOrBlank() && l.plateId != priorPlate
            }
            screenshot("backdrop")
            val l = look()!!
            composeRule.onNodeWithTag("cs_backdrop_back").performClick()
            waitForTag("cs_newsroom_root")
            composeRule.onNodeWithTag("cs_use_look").performClick()
            waitForTag("cs_cut_root")
            "plate ${l.plateId}, grain=${l.grain}, border=${l.border}"
        }

        stage("render") {
            dismissToast()
            assertTrue("voice must not need re-voicing", session().unsyncedClauseIndices.isEmpty())
            composeRule.onNodeWithTag("cs_cut_primary").performClick()
            waitForStage("studio render", 45 * 60_000L) {
                val s = session()
                val look = studioVm.uiState.value.newsroomLooks.firstOrNull { it.isDefault }
                s.hasFinalVideo && s.lastCutSignature == s.cutSignature(look, studioVm.uiState.value.savedOverlays)
            }
            val s = session()
            val stats = s.lastCutStats
            facts.put("renderer", stats?.renderer ?: "unknown")
            facts.put("remotion_used", stats?.remotionUsed ?: JSONObject.NULL)
            facts.put("cut_stats", JSONObject()
                .put("broll_cuts", stats?.brollCutCount ?: JSONObject.NULL)
                .put("overlays", stats?.appliedOverlayCount ?: JSONObject.NULL)
                .put("captions", stats?.captionCount ?: JSONObject.NULL)
                .put("aspect", stats?.aspect ?: JSONObject.NULL))
            val file = studioVm.casStore.fileForSha(s.finalVideoSha, "mp4")
            assertNotNull("final mp4 must be on the phone", file)
            val probe = probeVideo(Uri.fromFile(file!!))
            facts.put("final_video", probe.put("bytes", file.length()).put("sha", s.finalVideoSha))
            assertTrue("final video track", probe.optBoolean("has_video"))
            assertTrue("final audio track", probe.optBoolean("has_audio"))
            assertTrue("final duration > 1 s", probe.optLong("duration_ms") > 1_000L)
            assertNotNull("rig must echo cut stats", stats)
            assertTrue("b-roll used in render", stats!!.brollCutCount >= 1)
            assertTrue("graphics used in render", stats.appliedOverlayCount >= 1)
            assertTrue("captions used in render", stats.captionCount >= 1)
            screenshot("cut_rendered")
            "final ${probe.optLong("duration_ms")} ms ${probe.optString("size")}, renderer=${stats.renderer ?: "unknown"}"
        }

        stage("export") {
            composeRule.onNodeWithTag("cs_cut_primary").performClick()
            waitFor("exported", 120_000L) {
                failOnError("export")
                studioVm.uiState.value.exportState == ExportState.EXPORTED
            }
            // The share sheet opens over the app; close it.
            Thread.sleep(1_500L)
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            val clipId = session().finalClipId
            assertNotNull(clipId)
            val clip = runBlocking { studioVm.observeClip(clipId!!).first() }
            val uri = clip?.exportedUri
            assertNotNull("exported MediaStore uri", uri)
            val bytes = app.contentResolver.openFileDescriptor(Uri.parse(uri), "r")!!.use { it.statSize }
            assertTrue("exported file has bytes", bytes > 0L)
            facts.put("exported_uri", uri).put("exported_bytes", bytes)
            "saved to Photos, $bytes bytes"
        }
    }

    private fun stage(name: String, block: () -> String) {
        val started = System.currentTimeMillis()
        try {
            val detail = block()
            stages += JSONObject().put("stage", name).put("result", "PASS").put("detail", detail)
                .put("seconds", (System.currentTimeMillis() - started) / 1000.0)
        } catch (t: Throwable) {
            runCatching { screenshot("FAIL_$name") }
            stages += JSONObject().put("stage", name).put("result", "FAIL")
                .put("detail", "${t.javaClass.simpleName}: ${t.message}")
                .put("app_error", studioVm.uiState.value.errorMessage ?: JSONObject.NULL)
                .put("seconds", (System.currentTimeMillis() - started) / 1000.0)
            throw t
        }
    }

    /** Waits for a rig stage started from the Cut primary key; fails fast on the app's error. */
    private fun waitForStage(label: String, timeoutMs: Long, done: () -> Boolean) {
        waitFor(label, timeoutMs) {
            failOnError(label)
            !studioVm.uiState.value.isCoreStageBusy && done()
        }
    }

    private fun failOnError(label: String) {
        val err = studioVm.uiState.value.errorMessage
        if (!err.isNullOrBlank()) throw AssertionError("$label: app error \"$err\"")
    }

    private fun waitFor(label: String, timeoutMs: Long, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMs) { condition() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("timed out after ${timeoutMs / 1000}s waiting for $label", e)
        }
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000L) =
        waitFor(tag, timeoutMs) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Scrolls the node into view when it sits in a scrollable parent; sheets that fit need no scroll. */
    private fun SemanticsNodeInteraction.scrollIntoView(): SemanticsNodeInteraction {
        try {
            performScrollTo()
        } catch (e: AssertionError) {
            if (e.message?.contains("no parent layout with a Scroll") != true) throw e
        }
        return this
    }

    private fun tagPrefix(prefix: String) = SemanticsMatcher("testTag starts with $prefix") {
        it.config.getOrElseNullable(SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true
    }

    private fun dismissToast() {
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithTag("cs_toast_ok").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("cs_toast_ok").performClick()
            composeRule.waitForIdle()
        }
    }

    private fun probeVideo(uri: Uri): JSONObject {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(app, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            JSONObject()
                .put("duration_ms", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
                .put("has_video", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
                .put("has_audio", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
                .put("size", "${w}x$h")
        } finally {
            r.release()
        }
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        bmp.recycle()
    }

    private companion object {
        const val TAG = "AircheckE2E"
        const val IDEA = "Monthly AI video editing subscriptions are obsolete now that flagship phones run voice, " +
            "captions and multi-shot cuts locally with no monthly rent."
        const val CUSTOM_ANCHOR = "Pay one-time for the phone you already own"
        const val EDITED_HOOK = "Stop paying monthly rent for AI video editing."
        const val BROLL_PROMPT = "close up of a phone editing a video timeline on a desk, warm light"
        const val CHYRON = "Editing rent is over"
        const val SUBLINE = "Phones now cut video locally"
        const val BUG = "AIRCHECK E2E"
        const val STAT_TEXT = "Monthly editing subscription"
        const val STAT_NUMBER = "\$0"
        const val BACKDROP_PROMPT = "dark graphite broadcast studio, warm tungsten rim light, soft depth of field"
    }
}
