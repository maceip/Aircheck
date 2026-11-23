package com.example

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.RenderJobStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Single real End-to-End test running directly against the REAL installed Aircheck APK
 * and the REAL running Mac Studio `:8443` server (zero mocks, zero fake servers, zero Firebase).
 *
 * Exercises EVERY user-facing text input's ✨ AI assist button, mic recording, live News Desk
 * broadcast preview, Mac video gallery sync, and a real talking-head broadcast render with
 * BREAKING NEWS chyron graphics burned in.
 */
@RunWith(AndroidJUnit4::class)
class AircheckRunningAppE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun grantRuntimePermissions() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(
            inst.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO
        )
    }

    @Test
    fun fullCommonUserBroadcastFlowWithAiAssistAndLiveVideoRender() {
        // 0. Prove Firebase is physically absent from the shipped APK classpath.
        val firebasePresent = try {
            Class.forName("com.google.firebase.FirebaseApp")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        assertEquals("Firebase SDK must NOT exist in Aircheck APK", false, firebasePresent)

        val app = composeRule.activity.application as AircheckApplication
        val screenshotDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "e2e_screenshots").apply {
            mkdirs()
        }

        // 1. Cold boot on Story tab: clean launch, zero error banners, zero OTA traps.
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("story_idea_input").fetchSemanticsNodes().isNotEmpty()
        }
        assertNoErrorBanner("cold boot")
        captureScreenshot(screenshotDir, "ux_01_story_empty.png")

        // 2. Story Idea ✨ AI Assist: drop a rough idea -> tap ✨ -> real AI generates headline,
        //    subline, and deadpan news segments!
        composeRule.onNodeWithTag("story_idea_input").performClick()
        composeRule.onNodeWithTag("story_idea_input")
            .performTextInput("Two raccoons hijacked a Waymo in San Francisco and drove to Taco Bell")
        Espresso.closeSoftKeyboard()

        composeRule.onNodeWithTag("idea_ai_button").performClick()
        composeRule.waitUntil(timeoutMillis = 45_000L) {
            assertNoErrorBanner("story idea AI assist")
            val proj = runBlocking { app.container.localRepo.scripts.first().first() }
            proj.stageConfig.chyronHeadline.isNotBlank() &&
                proj.beats.size >= 2 &&
                proj.beats.all { it.spokenText.isNotBlank() }
        }
        assertNoErrorBanner("story idea fleshed out")

        // 3. Segment ✨ AI Assist: tap ✨ on Segment 1 -> real AI polishes the segment!
        val beforeSeg = runBlocking { app.container.localRepo.scripts.first().first().beats.first() }
        scrollListTo("script_lazy_list", "segment_ai_button_${beforeSeg.id}")
        composeRule.onNodeWithTag("segment_ai_button_${beforeSeg.id}").performClick()
        composeRule.waitUntil(timeoutMillis = 45_000L) {
            assertNoErrorBanner("segment AI assist")
            val afterSeg = runBlocking {
                app.container.localRepo.scripts.first().first().beats.first { it.id == beforeSeg.id }
            }
            afterSeg.spokenText.isNotBlank() && afterSeg.spokenText != beforeSeg.spokenText
        }

        // 4. Optional Mic Recording on Segment 1 -> WAV cached -> Play -> switch back to AI voice.
        scrollListTo("script_lazy_list", "beat_mic_${beforeSeg.id}")
        composeRule.onNodeWithTag("beat_mic_${beforeSeg.id}").performClick()
        Thread.sleep(1_500L)
        composeRule.onNodeWithTag("beat_mic_${beforeSeg.id}").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("preview_wav_button").fetchSemanticsNodes().isNotEmpty()
        }
        assertNoErrorBanner("mic recording")
        scrollListTo("script_lazy_list", "preview_wav_button")
        composeRule.onNodeWithTag("preview_wav_button").performClick()
        Thread.sleep(400L)
        composeRule.onNodeWithTag("preview_wav_button").performClick()
        captureScreenshot(screenshotDir, "ux_02_story_ai_and_mic.png")

        // Switch cleanly back to AI voice and trim to 1 crisp segment for fast live render check.
        composeRule.onNodeWithTag("clear_mic_take_button").performClick()
        while (true) {
            val currentBeats = runBlocking { app.container.localRepo.scripts.first().first().beats }
            if (currentBeats.size <= 1) break
            val extraId = currentBeats.last().id
            scrollListTo("script_lazy_list", "beat_delete_$extraId")
            composeRule.onNodeWithTag("beat_delete_$extraId").performClick()
            composeRule.waitForIdle()
        }
        assertNoErrorBanner("story tab complete")

        // 5. Tab 2 — Desk: live anchor portrait + LIVE/BREAKING NEWS chyron preview +
        //    ✨ AI Assist on BOTH Headline AND Subline!
        composeRule.onNodeWithTag("dock_tab_photo").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("anchor_preview_box").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(800L) // allow real portrait image load from :8443/v1/default-avatar

        val headlineBefore = runBlocking { app.container.localRepo.scripts.first().first().stageConfig.chyronHeadline }
        scrollListTo("photo_lazy_list", "headline_ai_button")
        composeRule.onNodeWithTag("headline_ai_button").performClick()
        composeRule.waitUntil(timeoutMillis = 45_000L) {
            assertNoErrorBanner("headline AI assist")
            val cur = runBlocking { app.container.localRepo.scripts.first().first().stageConfig.chyronHeadline }
            cur.isNotBlank() && cur != headlineBefore
        }

        val sublineBefore = runBlocking { app.container.localRepo.scripts.first().first().stageConfig.chyronSubline }
        scrollListTo("photo_lazy_list", "subline_ai_button")
        composeRule.onNodeWithTag("subline_ai_button").performClick()
        composeRule.waitUntil(timeoutMillis = 45_000L) {
            assertNoErrorBanner("subline AI assist")
            val cur = runBlocking { app.container.localRepo.scripts.first().first().stageConfig.chyronSubline }
            cur.isNotBlank() && cur != sublineBefore
        }

        scrollListTo("photo_lazy_list", "anchor_preview_box")
        assertNoErrorBanner("desk tab")
        captureScreenshot(screenshotDir, "ux_03_desk_live_preview.png")

        // 6. Tab 3 — Clips: real videos synced from Mac :8443/v1/renders (zero bogus Render CTA).
        composeRule.onNodeWithTag("dock_tab_clips").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithTag("clips_grid").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(1_000L) // let remote video thumbnails load from :8443/v1/render-thumbs/
        assertNoErrorBanner("clips gallery")
        captureScreenshot(screenshotDir, "ux_04_clips_gallery.png")

        // 7. Studio Settings: verify live Mac connection status & zero OTA error walls.
        composeRule.onNodeWithTag("top_bar_system_deck_button").performClick()
        composeRule.onNodeWithTag("test_rig_connection_button").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            val text = composeRule.onAllNodesWithTag("rig_connection_status_label")
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }
                .orEmpty()
            text == "Connected"
        }
        captureScreenshot(screenshotDir, "ux_05_settings.png")
        composeRule.onNodeWithTag("close_system_deck_button").performClick()

        // 8. REAL end-to-end news clip generation from Desk -> Mac :8443 (with BREAKING NEWS
        //    chyron burned in) -> downloaded into phone -> ExoPlayer playback!
        val beforeClipIds = runBlocking { app.container.localRepo.clipCards.first().map { it.id }.toSet() }
        composeRule.onNodeWithTag("dock_tab_photo").performClick()
        composeRule.onNodeWithTag("photo_render_cta").performClick()

        composeRule.waitUntil(timeoutMillis = 20_000L) {
            assertNoErrorBanner("render enqueue")
            composeRule.onAllNodesWithTag("review_video_stage").fetchSemanticsNodes().isNotEmpty()
        }

        var renderedClipId: String? = null
        composeRule.waitUntil(timeoutMillis = 480_000L) {
            assertNoErrorBanner("live Mac broadcast render")
            val cards = runBlocking { app.container.localRepo.clipCards.first() }
            val newest = cards.firstOrNull { it.id !in beforeClipIds }
            if (newest?.status == RenderJobStatus.FAILED) {
                val full = runBlocking { app.container.localRepo.getClipById(newest.id) }
                fail("News clip render FAILED: ${full?.errorMessage ?: newest.stageDetail}")
            }
            if (newest?.status == RenderJobStatus.READY && !newest.finalSha.isNullOrBlank()) {
                renderedClipId = newest.id
                true
            } else {
                false
            }
        }

        val readyId = requireNotNull(renderedClipId) { "Rendered clip ID missing" }
        val readyClip = runBlocking { app.container.localRepo.getClipById(readyId) }
        assertNotNull("Rendered clip must exist in Room", readyClip)
        val mp4File = app.container.casStore.fileForSha(readyClip?.finalSha, "mp4")
        assertNotNull("Rendered MP4 must be cached locally", mp4File)
        assertTrue(
            "Rendered MP4 must be a real non-empty video (got ${mp4File?.length()} bytes)",
            (mp4File?.length() ?: 0L) > 50_000L
        )
        assertNotEquals("", readyClip?.chyronHeadline.orEmpty())
        assertNoErrorBanner("clip ready")

        composeRule.onNodeWithTag("review_video_stage").performClick()
        Thread.sleep(1_000L)
        captureScreenshot(screenshotDir, "ux_06_clip_ready.png")

        composeRule.onNodeWithTag("review_back_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("clips_grid").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("clip_tile_$readyId").onFirst().assertIsDisplayed()
        assertNoErrorBanner("final clips grid")
    }

    private fun scrollListTo(listTag: String, itemTag: String) {
        composeRule.onNodeWithTag(listTag).performScrollToNode(hasTestTag(itemTag))
    }

    private fun assertNoErrorBanner(stage: String) {
        val errorNodes = composeRule.onAllNodesWithTag("studio_error_banner").fetchSemanticsNodes()
        if (errorNodes.isNotEmpty()) {
            val errorText = errorNodes.joinToString(" | ") { node ->
                node.config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString(" ") { it.text }
                    ?: node.children.mapNotNull { child ->
                        child.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                    }.joinToString(" ")
            }
            fail("Unexpected error banner during [$stage]: $errorText")
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
