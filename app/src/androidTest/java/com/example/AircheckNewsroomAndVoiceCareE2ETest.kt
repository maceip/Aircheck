package com.example

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.CorePipelineSession
import com.example.data.CorePipelineStage
import com.example.data.RenderJobStatus
import com.example.graph.GraphPostRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AircheckNewsroomAndVoiceCareE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var serverSocket: ServerSocket? = null
    private val serverRunning = AtomicBoolean(false)

    @Before
    fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(
            inst.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO
        )
    }

    @After
    fun tearDown() {
        serverRunning.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun buildRealPcmWavBytes(freqHz: Double, durationMs: Int = 1_400): ByteArray {
        val sampleRate = 16_000
        val totalSamples = (sampleRate * durationMs) / 1_000
        val pcmDataSize = totalSamples * 2
        val out = ByteArrayOutputStream(44 + pcmDataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcmDataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmDataSize)
        }
        out.write(header.array())
        val pcmBuf = ByteBuffer.allocate(pcmDataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val sample = (0.45 * sin(2.0 * PI * freqHz * t) * 32767.0).toInt().toShort()
            pcmBuf.putShort(sample)
        }
        out.write(pcmBuf.array())
        return out.toByteArray()
    }

    @Test
    fun android17CorePipelineFiveStages_optionalSavableA2uiOverlays_andPhoneOnlyVoiceCareEndToEnd() {
        val app = composeRule.activity.application as AircheckApplication
        val screenshotDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "core_e2e").apply { mkdirs() }
        File(app.noBackupFilesDir, "voice_care").deleteRecursively()
        runBlocking {
            app.container.localRepo.listSavedNewsroomOverlays().forEach {
                app.container.localRepo.deleteNewsroomOverlay(it.id)
            }
            app.container.localRepo.saveCorePipelineSession(CorePipelineSession())
        }

        // 1. Verify Android 17 PlatformStorage AppSearch 128-dim EmbeddingVector round-trip on real OS
        runBlocking {
            val indexed = app.container.graphAppSearchIndexer.indexPosts(
                listOf(
                    GraphPostRecord(
                        tweetId = "tweet_remotion_1",
                        handle = "androidmedia",
                        text = "Media3 CompositionPlayer MultipleInputVideoGraph newsroom chyron popups",
                        createdAtEpochMs = 1_760_000_000_000L,
                        isReplyToUser = true
                    )
                )
            )
            assertTrue("Android 17 PlatformStorage AppSearch must index post", indexed >= 1)
            val hits = app.container.graphAppSearchIndexer.searchSimilarPosts(
                "newsroom chyron popups Media3",
                limit = 5
            )
            assertTrue(
                "Expected semantic vector search hit on Android 17",
                hits.any { it.tweetId == "tweet_remotion_1" }
            )
        }

        // 2. Verify main script flow is untouched and uncluttered
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("tactile_drafting_deck_root").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("Voice Model Care").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Create Newsroom Overlays").fetchSemanticsNodes().isEmpty())

        // Seed real local phone WAV sample + local LoRA file for Voice Model Care verification
        val pcm16k = ShortArray(16_000) { i ->
            val t = i.toDouble() / 16_000.0
            (0.5 * sin(2.0 * PI * 440.0 * t) * 32767.0).toInt().toShort()
        }
        runBlocking {
            app.container.voiceModelCareManager.writeWavSampleForCare(pcm16k, "e2e_phone_voice.wav")
            val loraSrc = File(app.cacheDir, "voice_lora_v012.gguf").apply {
                writeBytes("GGUF_ANDROID17_PHONE_ONLY_LORA_V012".toByteArray(Charsets.UTF_8))
            }
            app.container.voiceModelCareManager.importLocalModelOrLoraFile(loraSrc)
        }

        // 3. Start embedded loopback server supporting Core Pipeline (TTS + Head + B-Roll Chop) AND Optional Overlay Builder
        val remotionBodies = CopyOnWriteArrayList<String>()
        val renderBodies = CopyOnWriteArrayList<String>()
        val chopBodies = CopyOnWriteArrayList<String>()
        val ttsPassCounter = AtomicInteger(0)

        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        serverRunning.set(true)
        val port = socket.localPort

        thread(name = "core-pipeline-e2e-http", isDaemon = true) {
            while (serverRunning.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { conn ->
                    val reader = BufferedReader(InputStreamReader(conn.getInputStream(), Charsets.UTF_8))
                    val requestLine = reader.readLine() ?: return@use
                    var contentLength = 0
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.lowercase().startsWith("content-length:")) {
                            contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                    }
                    val bodyChars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val n = reader.read(bodyChars, readTotal, contentLength - readTotal)
                        if (n <= 0) break
                        readTotal += n
                    }
                    val reqBody = String(bodyChars, 0, readTotal)
                    val path = requestLine.split(" ").getOrNull(1).orEmpty()

                    val (contentType, respBytes) = when {
                        path.startsWith("/health") || path.startsWith("/v1/health") -> {
                            "application/json" to """{"status":"ok","lora_version":4,"render_bridge_available":true}"""
                                .toByteArray(Charsets.UTF_8)
                        }
                        path.startsWith("/v1/remotion/install") -> {
                            remotionBodies.add(reqBody)
                            "application/json" to """{"status":"installed","remotion_enabled":true,"remotion_version":"4.0.242","package_provider_id":"mac_rig_remotion","user_request":"Install Remotion","enable_broll_popups":true}"""
                                .toByteArray(Charsets.UTF_8)
                        }
                        path.startsWith("/v1/ai-assist") -> {
                            "application/json" to """{"kind":"STAT_CALLOUT","headline":"CHIP YIELD +38%","subline":"FOUNDRY BRIEF","callout_stat":"+38%","duration_sec":4}"""
                                .toByteArray(Charsets.UTF_8)
                        }
                        path.startsWith("/v1/core/tts") -> {
                            val pass = ttsPassCounter.incrementAndGet()
                            "application/json" to """{"tts_id":"e2e_tts_$pass","audio_url":"http://127.0.0.1:$port/v1/renders/e2e_tts_$pass.wav","duration_ms":2200,"waveform_peaks":[0.4,0.8,0.6,0.9],"clauses":[{"id":"c0","index":0,"spoken_text":"Clause one.","start_ms":0,"end_ms":1100},{"id":"c1","index":1,"spoken_text":"Clause two.","start_ms":1100,"end_ms":2200}]}"""
                                .toByteArray(Charsets.UTF_8)
                        }
                        path.startsWith("/v1/render-video") -> {
                            renderBodies.add(reqBody)
                            "application/json" to """{"job_id":"e2e_head_01","clip_id":"clip_e2e_head_01","input_hash":"1234abcd"}"""
                                .toByteArray(Charsets.UTF_8)
                        }
                        path.startsWith("/v1/jobs/e2e_head_01") -> {
                            "application/json" to """{"job_id":"e2e_head_01","status":"READY","progress_fraction":1.0,"stage_detail":"Ready","video_url":"http://127.0.0.1:$port/v1/renders/e2e_head_01.mp4","duration_ms":2200}"""
                                .toByteArray(Charsets.UTF_8)
                        }
                        path.startsWith("/v1/core/chop-broll") -> {
                            chopBodies.add(reqBody)
                            val n = chopBodies.size
                            "application/json" to """{"job_id":"e2e_broll_$n","status":"READY","video_url":"http://127.0.0.1:$port/v1/renders/e2e_broll_$n.mp4","duration_ms":2200,"broll_cut_count":1,"applied_overlay_count":0}"""
                                .toByteArray(Charsets.UTF_8)
                        }
                        path.endsWith(".wav") -> {
                            val freq = if (path.contains("_1.wav")) 350.0 else 480.0
                            "audio/wav" to buildRealPcmWavBytes(freqHz = freq, durationMs = 1_200)
                        }
                        else -> {
                            "video/mp4" to ("MP4_BYTES_FOR_$path").toByteArray(Charsets.UTF_8)
                        }
                    }

                    val httpHeader = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: ${respBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.UTF_8)
                    conn.getOutputStream().write(httpHeader)
                    conn.getOutputStream().write(respBytes)
                    conn.getOutputStream().flush()
                }
            }
        }

        runBlocking {
            app.container.profileStore.setTailscaleRigUrl("http://127.0.0.1:$port")
        }

        // 4. Exercise Phone-Only Voice Model Care screen first
        composeRule.onNodeWithTag("top_settings_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("open_voice_model_care_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_voice_model_care_button")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("voice_care_bundles_path_text").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("voice_care_phone_only_switch").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { !app.container.profileStore.current().phoneOnlyVoiceMode }
        }
        composeRule.onNodeWithTag("voice_care_phone_only_switch").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { app.container.profileStore.current().phoneOnlyVoiceMode }
        }
        assertFalse(runBlocking { app.container.profileStore.current().providers.useLanVoice })

        composeRule.onNodeWithTag("voice_care_lazy_list").performScrollToKey("quality_check_card")
        composeRule.onNodeWithTag("run_voice_quality_check_button")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("voice_quality_summary_text").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "e2e_voice_model_care.png")
        composeRule.onNodeWithTag("voice_care_back_button").performClick()
        composeRule.waitForIdle()
        // Allow Step 5 Core Pipeline E2E to exercise the embedded loopback rig TTS endpoint
        runBlocking { app.container.profileStore.setPhoneOnlyVoiceMode(false) }

        // 5. Run the FULL 5-stage Core Story Pipeline WITHOUT visiting Newsroom Overlays!
        composeRule.onNodeWithTag("open_core_pipeline_button")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("core_tts_story_input").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("core_tts_story_input").performClick()
        composeRule.onNodeWithTag("core_tts_story_input").performTextClearance()
        composeRule.onNodeWithTag("core_tts_story_input").performTextInput(
            "Edge voice models keep personal biometrics strictly on the handset. Zero cloud round-trips interrupt your cadence."
        )
        Espresso.closeSoftKeyboard()

        // Stage 1: Synthesize TTS -> advances to Stage 2 (Iterate on Story)
        composeRule.onNodeWithTag("core_run_tts_button").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("core_story_iteration_badge").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "e2e_core_stage2_story.png")

        // Stage 2: Edit story clause + Re-run TTS (Pass #2)
        composeRule.onNodeWithTag("core_clause_input_0").performScrollTo().performClick()
        composeRule.onNodeWithTag("core_clause_input_0").performTextClearance()
        composeRule.onNodeWithTag("core_clause_input_0").performTextInput(
            "On-phone voice models keep personal biometrics strictly on the handset."
        )
        Espresso.closeSoftKeyboard()

        composeRule.onNodeWithTag("core_pipeline_lazy_list").performScrollToKey("stage_02_story_actions")
        composeRule.onNodeWithTag("core_rerun_tts_button")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("TTS Pass #2").fetchSemanticsNodes().isNotEmpty()
        }

        // Advance to Stage 3 (Speech Matched to Talking Head)
        composeRule.onNodeWithTag("core_pipeline_lazy_list").performScrollToKey("stage_02_story_actions")
        composeRule.onNodeWithTag("core_to_head_button")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("core_run_head_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("core_run_head_button").performClick()

        // Advances to Stage 4 (Chop Talking Head into B-Roll) with 0 overlays attached!
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("core_broll_cut_count_label").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, renderBodies.size)
        assertTrue(renderBodies.first().contains("\"voice_source\":\"MIC_VOICEOVER\""))
        assertTrue(renderBodies.first().contains("\"chyron_headline\":\"\""))

        composeRule.onNodeWithTag("core_pipeline_lazy_list").performScrollToKey("stage_04_optional_overlays")
        composeRule.onNodeWithText("0 attached").assertIsDisplayed()
        captureScreenshot(screenshotDir, "e2e_core_stage4_broll.png")

        composeRule.onNodeWithTag("core_pipeline_lazy_list").performScrollToKey("stage_04_commit_chop")
        composeRule.onNodeWithTag("core_run_chop_broll_button")
            .performScrollTo()
            .performClick()

        // Advances to Stage 5 (Final Review)
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("review_video_stage").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, chopBodies.size)
        assertTrue(chopBodies.first().contains("\"overlays\":[]"))

        val latestSession = runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
        assertNotNull(latestSession)
        assertEquals(CorePipelineStage.FINAL_REVIEW, latestSession!!.stage)
        val producedClip = runBlocking { app.container.localRepo.getClipById(latestSession.finalClipId!!) }
        assertNotNull(producedClip)
        assertEquals(RenderJobStatus.READY, producedClip!!.status)

        // 6. Now verify Optional Savable Newsroom Overlays for a News Brief:
        //    Switch back to Stage 4 -> tap "Create Newsroom Overlays" -> install Remotion if needed ->
        //    generate AI overlay -> save to Room -> return to Stage 4 -> attach overlay -> chop!
        composeRule.onNodeWithTag("core_stage_tab_CHOP_INTO_BROLL").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("core_pipeline_lazy_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("core_pipeline_lazy_list").performScrollToKey("stage_04_optional_overlays")
        composeRule.onNodeWithTag("core_open_overlay_builder_button")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Ask to Install Remotion").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Create Savable News Brief Overlay").fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithText("Ask to Install Remotion").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Ask to Install Remotion").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 8_000L) {
                remotionBodies.isNotEmpty() &&
                    composeRule.onAllNodesWithText("Create Savable News Brief Overlay")
                        .fetchSemanticsNodes().isNotEmpty()
            }
        }

        // Enter brief topic & click Generate AI Overlay -> then Save Overlay for News Briefs
        val topicField = composeRule.onAllNodes(hasSetTextAction())[0]
        topicField.performScrollTo().performClick()
        topicField.performTextInput("Wafer foundry yield climbed 38% this quarter")
        Espresso.closeSoftKeyboard()

        composeRule.onNodeWithText("Generate AI Overlay").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("CHIP YIELD +38%").fetchSemanticsNodes().isNotEmpty()
        }
        captureScreenshot(screenshotDir, "e2e_newsroom_a2ui.png")

        composeRule.onNodeWithText("Save Overlay for News Briefs").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            runBlocking { app.container.localRepo.listSavedNewsroomOverlays().isNotEmpty() }
        }
        val savedOverlay = runBlocking { app.container.localRepo.listSavedNewsroomOverlays().first() }
        assertEquals("CHIP YIELD +38%", savedOverlay.headline)

        // Return to Stage 4 of Core Pipeline -> attach saved overlay -> chop News Brief!
        composeRule.onNodeWithTag("newsroom_studio_back_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("core_overlay_toggle_${savedOverlay.id}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("core_overlay_toggle_${savedOverlay.id}")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("1 attached").assertIsDisplayed()

        composeRule.onNodeWithTag("core_pipeline_lazy_list").performScrollToKey("stage_04_commit_chop")
        composeRule.onNodeWithTag("core_run_chop_broll_button")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            chopBodies.size >= 2 &&
                composeRule.onAllNodesWithTag("review_video_stage").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(chopBodies[1].contains("CHIP YIELD +38%"))
        captureScreenshot(screenshotDir, "e2e_core_stage5_review.png")

        // Back out cleanly to main script deck
        composeRule.onNodeWithTag("core_pipeline_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tactile_drafting_deck_root").assertIsDisplayed()
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
