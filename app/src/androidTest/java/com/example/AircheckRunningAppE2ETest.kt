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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.RenderJobStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * Single comprehensive End-to-End test running directly against the REAL installed Aircheck APK
 * and the REAL running Mac Studio `:8443` server (zero mocks, zero fake servers, zero Firebase).
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
    fun fullRunningAppEndToEndZeroFirebaseRealMacRigRender() {
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

        // 1. Cold boot: verify Script screen loads cleanly with zero error banners.
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("script_title_input").fetchSemanticsNodes().isNotEmpty()
        }
        assertNoErrorBanner("cold boot")
        captureScreenshot(screenshotDir, "e2e_01_script_boot.png")

        // 2. Script screen controls: type title & spoken line, toggle duration, add/cut/delete beat.
        composeRule.onNodeWithTag("script_title_input").performClick()
        composeRule.onNodeWithTag("script_title_input").performTextClearance()
        composeRule.onNodeWithTag("script_title_input").performTextInput("Live Aircheck E2E")

        val initialProject = runBlocking { app.container.localRepo.scripts.first().first() }
        val firstBeatId = initialProject.beats.first().id

        scrollListTo("script_lazy_list", "beat_input_$firstBeatId")
        composeRule.onNodeWithTag("beat_input_$firstBeatId").performClick()
        composeRule.onNodeWithTag("beat_input_$firstBeatId").performTextClearance()
        composeRule.onNodeWithTag("beat_input_$firstBeatId")
            .performTextInput("Live Aircheck check straight to the Mac studio rig.")
        Espresso.closeSoftKeyboard()

        scrollListTo("script_lazy_list", "target_duration_button")
        composeRule.onNodeWithTag("target_duration_button").performClick()

        scrollListTo("script_lazy_list", "add_beat_button")
        composeRule.onNodeWithTag("add_beat_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { app.container.localRepo.scripts.first().first().beats.size >= 2 }
        }
        val secondBeatId = runBlocking { app.container.localRepo.scripts.first().first().beats.last().id }
        scrollListTo("script_lazy_list", "beat_cut_$secondBeatId")
        composeRule.onNodeWithTag("beat_cut_$secondBeatId").performClick()
        scrollListTo("script_lazy_list", "beat_delete_$secondBeatId")
        composeRule.onNodeWithTag("beat_delete_$secondBeatId").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { app.container.localRepo.scripts.first().first().beats.size == 1 }
        }
        assertNoErrorBanner("script editing")

        // 3. Real Android mic recording -> CAS WAV saved -> playback preview works cleanly.
        scrollListTo("script_lazy_list", "record_mic_button")
        composeRule.onNodeWithTag("record_mic_button").performClick()
        Thread.sleep(1_800L)
        composeRule.onNodeWithTag("record_mic_button").performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("preview_wav_button").fetchSemanticsNodes().isNotEmpty()
        }
        assertNoErrorBanner("mic recording")

        val recordedProject = runBlocking { app.container.localRepo.scripts.first().first() }
        assertNotNull("Recorded mic WAV SHA must persist in Room", recordedProject.masterAudioSha)
        val recordedWav = app.container.casStore.fileForSha(recordedProject.masterAudioSha, "wav")
        assertNotNull("Recorded mic WAV file must exist in CAS", recordedWav)
        assertTrue(
            "Recorded mic WAV must contain real PCM audio bytes (got ${recordedWav?.length()})",
            (recordedWav?.length() ?: 0L) > 4096L
        )

        composeRule.onNodeWithTag("preview_wav_button").performClick()
        Thread.sleep(500L)
        composeRule.onNodeWithTag("preview_wav_button").performClick()
        assertNoErrorBanner("wav preview")
        captureScreenshot(screenshotDir, "e2e_02_script_recorded.png")

        // 4. Photo tab: default Mac Rig portrait, voice mode switching, chyron & brand bug switch.
        composeRule.onNodeWithTag("dock_tab_photo").performClick()
        composeRule.onNodeWithTag("pick_photo_button").assertIsDisplayed()
        scrollListTo("photo_lazy_list", "voice_source_enrolled_voice_clone")
        composeRule.onNodeWithTag("voice_source_enrolled_voice_clone").performClick()
        scrollListTo("photo_lazy_list", "voice_source_mic_voiceover")
        composeRule.onNodeWithTag("voice_source_mic_voiceover").performClick()
        scrollListTo("photo_lazy_list", "chyron_headline_input")
        composeRule.onNodeWithTag("chyron_headline_input").performClick()
        composeRule.onNodeWithTag("chyron_headline_input").performTextInput("LIVE E2E")
        scrollListTo("photo_lazy_list", "chyron_subline_input")
        composeRule.onNodeWithTag("chyron_subline_input").performClick()
        composeRule.onNodeWithTag("chyron_subline_input").performTextInput("MAC STUDIO RIG")
        Espresso.closeSoftKeyboard()
        scrollListTo("photo_lazy_list", "show_brand_bug_switch")
        composeRule.onNodeWithTag("show_brand_bug_switch").performClick()
        composeRule.onNodeWithTag("show_brand_bug_switch").performClick()
        assertNoErrorBanner("photo tab")
        captureScreenshot(screenshotDir, "e2e_03_photo_tab.png")

        // 5. System Deck: real HTTP /health probe against live Mac :8443 rig + accent switch.
        composeRule.onNodeWithTag("top_bar_system_deck_button").performClick()
        scrollListTo("system_deck_lazy_list", "test_rig_connection_button")
        composeRule.onNodeWithTag("test_rig_connection_button").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            val nodes = composeRule.onAllNodesWithTag("rig_connection_status_label").fetchSemanticsNodes()
            val text = nodes.firstOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }
                .orEmpty()
            text.startsWith("Online")
        }
        scrollListTo("system_deck_lazy_list", "channel_toggle_broadcast_cobalt")
        composeRule.onNodeWithTag("channel_toggle_broadcast_cobalt").performClick()
        composeRule.onNodeWithTag("channel_toggle_volt_lime").performClick()
        assertNoErrorBanner("system deck")
        scrollListTo("system_deck_lazy_list", "rig_connection_status_label")
        captureScreenshot(screenshotDir, "e2e_04_system_deck_online.png")
        composeRule.onNodeWithTag("close_system_deck_button").performClick()

        // 6. Verify Clipper dead-end is hidden when offline OTA bundle isn't preloaded.
        composeRule.onNodeWithTag("dock_tab_script").performClick()
        assertEquals(
            "Clipper button must stay hidden when offline OTA bundle is not preloaded",
            0,
            composeRule.onAllNodesWithTag("open_clipper_button").fetchSemanticsNodes().size
        )
        assertNoErrorBanner("script clean state")

        // 7. REAL talking-head video render from running Android app -> live Mac :8443 -> ExoPlayer!
        // Switch to Local Voice Clone so a real spoken voice is synthesized on Mac :8443 even if
        // running on a remote test device with a silent mic, then rendered through wan3.0-video.
        scrollListTo("script_lazy_list", "script_voice_mode_enrolled_voice_clone")
        composeRule.onNodeWithTag("script_voice_mode_enrolled_voice_clone").performClick()
        composeRule.onNodeWithTag("script_primary_cta").performClick()

        composeRule.waitUntil(timeoutMillis = 20_000L) {
            assertNoErrorBanner("render enqueue")
            composeRule.onAllNodesWithTag("review_video_stage").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "e2e_05_clip_rendering.png")

        // Wait for the real Mac :8443 pipeline to finish rendering and downloading the MP4 into CAS.
        composeRule.waitUntil(timeoutMillis = 300_000L) {
            assertNoErrorBanner("live Mac rig render")
            val clips = runBlocking { app.container.localRepo.clipCards.first() }
            val latest = clips.firstOrNull()
            if (latest?.status == RenderJobStatus.FAILED) {
                val full = runBlocking { app.container.localRepo.getClipById(latest.id) }
                fail("Mac Rig render job FAILED: ${full?.errorMessage ?: latest.stageDetail}")
            }
            latest?.status == RenderJobStatus.READY && !latest.finalSha.isNullOrBlank()
        }

        val readyCard = runBlocking { app.container.localRepo.clipCards.first().first() }
        val mp4File = app.container.casStore.fileForSha(readyCard.finalSha, "mp4")
        assertNotNull("Rendered MP4 must be cached locally in CAS", mp4File)
        assertTrue(
            "Rendered MP4 must be a real non-empty video file (got ${mp4File?.length()} bytes)",
            (mp4File?.length() ?: 0L) > 50_000L
        )
        assertNoErrorBanner("clip ready")

        // Tap ExoPlayer stage to play the rendered talking-head video.
        composeRule.onNodeWithTag("review_video_stage").performClick()
        Thread.sleep(1_000L)
        assertNoErrorBanner("clip playback")
        captureScreenshot(screenshotDir, "e2e_06_clip_ready_playback.png")

        // Navigate back to Clips tab and confirm the rendered clip tile appears.
        composeRule.onNodeWithTag("review_back_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("clips_grid").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithTag("clip_tile_${readyCard.id}").onFirst().assertIsDisplayed()
        assertNoErrorBanner("clips grid")
        captureScreenshot(screenshotDir, "e2e_07_clips_grid.png")
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
        composeRule.runOnUiThread {
            runCatching {
                val root = composeRule.activity.window.decorView.rootView
                if (root.width > 0 && root.height > 0) {
                    val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    root.draw(canvas)
                    FileOutputStream(File(dir, filename)).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 92, out)
                    }
                    bmp.recycle()
                }
            }
        }
    }
}
