package com.example.ui

import android.app.Application
import android.os.Looper
import androidx.a2ui.compose.ui.testing.A2uiTestController
import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.a2ui.model.protocol.A2uiEventAction
import androidx.a2ui.model.protocol.A2uiException
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.AircheckApp
import com.example.AircheckApplication
import com.example.data.CorePipelineStage
import com.example.data.RenderJobStatus
import com.example.ui.a2ui.createStockAircheckA2uiCatalog
import com.example.ui.draft.DraftDeckViewModel
import com.example.ui.screens.CorePipelineScreen
import com.example.ui.screens.NewsroomStudioScreen
import com.example.ui.screens.VoiceModelCareScreen
import com.example.ui.theme.AircheckTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w411dp-h915dp-xxhdpi")
class AircheckSeparateScreensAndA2uiTest {

    private lateinit var app: AircheckApplication
    private var httpServer: HttpServer? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>() as AircheckApplication
        // Robolectric skips androidx.startup; background pipeline stages run on a synchronous test WorkManager.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            androidx.work.Configuration.Builder()
                .setExecutor(androidx.work.testing.SynchronousExecutor())
                .setTaskExecutor(androidx.work.testing.SynchronousExecutor())
                .build()
        )
        File(app.noBackupFilesDir, "voice_care").deleteRecursively()
        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(true)
            app.container.localRepo.listSavedNewsroomOverlays().forEach {
                app.container.localRepo.deleteNewsroomOverlay(it.id)
            }
        }
    }

    @After
    fun tearDown() {
        httpServer?.stop(0)
        httpServer = null
    }

    private fun buildRealPcmWavBytes(freqHz: Double, durationMs: Int = 1_600): ByteArray {
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
            putShort(1) // PCM
            putShort(1) // Mono
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
    fun corePipeline_runsFullFiveStagesWithoutNewsroomOverlays() = runComposeUiTest {
        val ttsCounter = AtomicInteger(0)
        val renderBodies = CopyOnWriteArrayList<String>()
        val chopBodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/jobs/task_") { serveRigTask(it) }
        val portPlaceholder = AtomicInteger(0)

        server.createContext("/health") { exchange ->
            val resp = """
                {
                  "status": "ok",
                  "lora_version": 4,
                  "bundle_version": "2026.11",
                  "pending_preferences": 0,
                  "render_bridge_available": true,
                  "active_render_jobs": 0
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }

        server.createContext("/v1/core/tts") { exchange ->
            val pass = ttsCounter.incrementAndGet()
            val p = portPlaceholder.get()
            val resp = """
                {
                  "tts_id": "tts_pass_$pass",
                  "audio_url": "http://127.0.0.1:$p/v1/renders/tts_pass_$pass.wav",
                  "duration_ms": 2400,
                  "waveform_peaks": [0.35, 0.72, 0.58, 0.84],
                  "clauses": [
                    {"id": "c0", "index": 0, "spoken_text": "First clause.", "start_ms": 0, "end_ms": 1200},
                    {"id": "c1", "index": 1, "spoken_text": "Second clause.", "start_ms": 1200, "end_ms": 2400}
                  ]
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            respondMaybeAsync(exchange, resp)
        }

        server.createContext("/v1/render-video") { exchange ->
            val req = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            renderBodies.add(req)
            val resp = """{"job_id":"head_job_01","clip_id":"clip_head_01","input_hash":"abc12345"}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }

        server.createContext("/v1/jobs/head_job_01") { exchange ->
            val p = portPlaceholder.get()
            val resp = """
                {
                  "job_id": "head_job_01",
                  "status": "READY",
                  "progress_fraction": 1.0,
                  "stage_detail": "Ready",
                  "video_url": "http://127.0.0.1:$p/v1/renders/head_job_01.mp4",
                  "duration_ms": 2400
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }

        server.createContext("/v1/core/chop-broll") { exchange ->
            val req = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            chopBodies.add(req)
            val p = portPlaceholder.get()
            val resp = """
                {
                  "job_id": "broll_final_01",
                  "status": "READY",
                  "video_url": "http://127.0.0.1:$p/v1/renders/broll_final_01.mp4",
                  "duration_ms": 2400,
                  "broll_cut_count": 1,
                  "applied_overlay_count": 0
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            respondMaybeAsync(exchange, resp)
        }

        server.createContext("/v1/renders/") { exchange ->
            val path = exchange.requestURI.path
            val bytes = if (path.endsWith(".wav")) {
                val freq = if (path.contains("pass_1")) 330.0 else 440.0
                buildRealPcmWavBytes(freqHz = freq, durationMs = 1_200)
            } else {
                ("MP4_PAYLOAD_FOR_$path").toByteArray(Charsets.UTF_8)
            }
            exchange.responseHeaders.add(
                "Content-Type",
                if (path.endsWith(".wav")) "audio/wav" else "video/mp4"
            )
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        server.start()
        httpServer = server
        val port = server.address.port
        portPlaceholder.set(port)

        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(false)
            app.container.profileStore.setTailscaleRigUrl("http://127.0.0.1:$port")
        }

        var studioVmRef: StudioViewModel? = null
        setContent {
            // Clip Studio v4 owns the default route; the legacy 5-stage screen is exercised directly.
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val st by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme(channel = st.settings.brandChannel) {
                CorePipelineScreen(
                    uiState = st,
                    viewModel = studioVm,
                    onBack = {},
                    onOpenOptionalOverlayBuilder = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)

        // Route story into the single Core Pipeline (just like create video from 02 SCRIPT)
        studioVm.renderDraftAsBroadcastClip(
            plainText = "Local neural codecs cut latency in half. Cloud round-trips break conversational rhythm.",
            headline = "LOCAL CODECS"
        )

        // Stage 1: 01 TTS opens automatically
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("core_run_tts_button").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(CorePipelineStage.TTS, studioVm.uiState.value.corePipelineSession.stage)

        // Synthesize TTS Pass #1 -> advances automatically to Stage 2 (02 ITERATE_STORY)
        onNodeWithTag("core_run_tts_button").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.stage == CorePipelineStage.ITERATE_STORY &&
                studioVm.uiState.value.corePipelineSession.ttsIterationCount == 1
        }
        val firstVoiceSha = studioVm.uiState.value.corePipelineSession.ttsVoiceSha
        assertNotNull(firstVoiceSha)

        // Stage 2: Iterate on Story — edit clause 0 & re-run TTS (Pass #2)
        onNodeWithTag("core_clause_input_0")
            .performScrollTo()
            .performTextClearance()
        onNodeWithTag("core_clause_input_0")
            .performTextInput("On-phone neural codecs cut latency below 20 milliseconds.")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        onNodeWithTag("core_pipeline_lazy_list").performScrollToKey("stage_02_story_actions")
        onNodeWithTag("core_rerun_tts_button")
            .performScrollTo()
            .performClick()

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.ttsIterationCount == 2
        }
        val secondVoiceSha = studioVm.uiState.value.corePipelineSession.ttsVoiceSha
        assertNotNull(secondVoiceSha)
        assertFalse("Second TTS iteration must update voice WAV SHA", firstVoiceSha == secondVoiceSha)

        // Lock story & advance to Stage 3 (03 SPEECH_TO_TALKING_HEAD)
        onNodeWithTag("core_pipeline_lazy_list").performScrollToKey("stage_02_story_actions")
        onNodeWithTag("core_to_head_button")
            .performScrollTo()
            .performClick()

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.stage == CorePipelineStage.SPEECH_TO_TALKING_HEAD
        }

        // Stage 3: Match Speech to Talking Head -> advances to Stage 4 (04 CHOP_INTO_BROLL)
        onNodeWithTag("core_run_head_button").performClick()
        waitUntil(timeoutMillis = 6_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.stage == CorePipelineStage.CHOP_INTO_BROLL &&
                studioVm.uiState.value.corePipelineSession.hasTalkingHead
        }
        assertEquals(1, renderBodies.size)
        assertTrue("Stage 3 must use locked MIC_VOICEOVER WAV", renderBodies.first().contains("\"voice_source\":\"MIC_VOICEOVER\""))
        assertTrue("Stage 3 must pass locked TTS SHA", renderBodies.first().contains(secondVoiceSha!!))
        assertTrue("Stage 3 must transmit Base64 WAV bytes", renderBodies.first().contains("\"audio_wav_base64\":\""))
        assertTrue("Stage 3 must NOT burn premature chyron", renderBodies.first().contains("\"chyron_headline\":\"\""))

        // Stage 4: Chop Talking Head into B-Roll WITH ZERO Newsroom Overlays
        assertEquals(0, studioVm.uiState.value.corePipelineSession.selectedOverlayIds.size)
        onNodeWithTag("core_pipeline_lazy_list").performScrollToKey("stage_04_optional_overlays")
        onNodeWithText("0 attached").assertIsDisplayed()

        onNodeWithTag("core_pipeline_lazy_list").performScrollToKey("stage_04_commit_chop")
        onNodeWithTag("core_run_chop_broll_button")
            .performScrollTo()
            .performClick()

        // Stage 5: Advances to FINAL_REVIEW backed by a READY ClipRecord in Room!
        waitUntil(timeoutMillis = 6_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.stage == CorePipelineStage.FINAL_REVIEW &&
                onAllNodesWithTag("review_video_stage").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, chopBodies.size)
        assertTrue("Standard run must send empty overlays array", chopBodies.first().contains("\"overlays\":[]"))
        assertTrue("Stage 4 chop must include BROLL_CUTAWAY segment", chopBodies.first().contains("BROLL_CUTAWAY"))

        val finalClipId = studioVm.uiState.value.corePipelineSession.finalClipId
        assertNotNull(finalClipId)
        val clipInRoom = runBlocking { app.container.localRepo.getClipById(finalClipId!!) }
        assertNotNull(clipInRoom)
        assertEquals(RenderJobStatus.READY, clipInRoom!!.status)
        assertEquals(secondVoiceSha, clipInRoom.voiceSha)
    }

    @Test
    fun newsroomOverlayPage_generatesAiOverlay_savesToRoom_andAttachesOptionallyInStage4() = runComposeUiTest {
        val remotionInstallBodies = CopyOnWriteArrayList<String>()
        val aiAssistBodies = CopyOnWriteArrayList<String>()
        val chopBodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/jobs/task_") { serveRigTask(it) }
        val portPlaceholder = AtomicInteger(0)

        server.createContext("/health") { exchange ->
            val resp = """{"status":"ok","lora_version":1,"render_bridge_available":true}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }

        server.createContext("/v1/remotion/install") { exchange ->
            remotionInstallBodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val resp = """
                {
                  "status": "installed",
                  "remotion_enabled": true,
                  "remotion_version": "4.0.242",
                  "package_provider_id": "mac_rig_remotion",
                  "user_request": "Please install Remotion for live news pop-ups and B-roll",
                  "enable_broll_popups": true
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }

        server.createContext("/v1/ai-assist") { exchange ->
            aiAssistBodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val resp = """
                {
                  "kind": "STAT_CALLOUT",
                  "headline": "GRID CAPACITY +42%",
                  "subline": "ERCOT BATTERY DISPATCH REPORT",
                  "callout_stat": "+42% MW",
                  "duration_sec": 4
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }

        server.createContext("/v1/core/chop-broll") { exchange ->
            chopBodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val p = portPlaceholder.get()
            val resp = """
                {
                  "job_id": "brief_broll_02",
                  "status": "READY",
                  "video_url": "http://127.0.0.1:$p/v1/renders/brief_broll_02.mp4",
                  "duration_ms": 2000,
                  "broll_cut_count": 1,
                  "applied_overlay_count": 1
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            respondMaybeAsync(exchange, resp)
        }

        server.createContext("/v1/renders/") { exchange ->
            val bytes = "MP4_BRIEF_WITH_SAVED_OVERLAY".toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        server.start()
        httpServer = server
        val port = server.address.port
        portPlaceholder.set(port)

        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(false)
            app.container.profileStore.setTailscaleRigUrl("http://127.0.0.1:$port")
        }

        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val state = studioVm.uiState.collectAsStateWithLifecycle().value
            AircheckTheme {
                NewsroomStudioScreen(
                    uiState = state,
                    a2uiController = studioVm.a2uiController,
                    onDeleteSavedOverlay = studioVm::deleteSavedNewsroomOverlay,
                    onBack = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)

        // If Remotion was not used yet in this test process, verify install prompt -> transition
        if (studioVm.uiState.value.isRemotionUnused) {
            waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                onAllNodesWithText("Remotion Video Overlays Not Installed Yet")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val promptInput = onNode(hasSetTextAction())
            promptInput.performScrollTo()
            promptInput.performTextClearance()
            promptInput.performTextInput("Please install Remotion for live news pop-ups and B-roll")
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            onNodeWithText("Ask to Install Remotion")
                .performScrollTo()
                .performClick()

            waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                remotionInstallBodies.isNotEmpty() &&
                    onAllNodesWithText("Create Savable News Brief Overlay")
                        .fetchSemanticsNodes().isNotEmpty()
            }
        } else {
            waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                onAllNodesWithText("Create Savable News Brief Overlay")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }

        // Generate AI Overlay fields via the stock Jetpack A2UI "Generate AI Overlay" button
        val topicField = onAllNodes(hasSetTextAction())[0]
        topicField.performScrollTo()
        topicField.performTextInput("Texas battery storage dispatched 42% more power during peak evening load")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        onNodeWithText("Generate AI Overlay")
            .performScrollTo()
            .performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            aiAssistBodies.isNotEmpty() &&
                onAllNodesWithText("GRID CAPACITY +42%").fetchSemanticsNodes().isNotEmpty()
        }

        // Click "Save Overlay for News Briefs" on the stock Jetpack A2UI surface
        onNodeWithText("Save Overlay for News Briefs")
            .performScrollTo()
            .performClick()

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.savedOverlays.any { it.headline == "GRID CAPACITY +42%" }
        }
        val savedOverlay = studioVm.uiState.value.savedOverlays.first { it.headline == "GRID CAPACITY +42%" }
        assertTrue(savedOverlay.a2uiComponentsJson.contains("\"component\":\"Card\""))
        assertEquals("ERCOT BATTERY DISPATCH REPORT", savedOverlay.subline)

        // Verify optional attachment in Stage 4 of Core Pipeline
        val headAsset = runBlocking {
            app.container.casStore.putBytes("HEAD_MP4_BYTES".toByteArray(Charsets.UTF_8), "mp4")
        }
        val seeded = runBlocking {
            val base = app.container.corePipelineCoordinator.seedSessionFromStory(
                rawStoryText = "Battery dispatch set an evening record. Grid frequency held steady.",
                headline = "GRID BRIEF"
            )
            val stage4Session = base.copy(
                stage = CorePipelineStage.CHOP_INTO_BROLL,
                ttsVoiceSha = "deadbeef12345678",
                ttsDurationMs = 2_000L,
                talkingHeadJobId = "head_brief_01",
                talkingHeadSha = headAsset.sha256,
                selectedOverlayIds = listOf(savedOverlay.id)
            )
            app.container.localRepo.saveCorePipelineSession(stage4Session)
            stage4Session
        }
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.id == seeded.id &&
                studioVm.uiState.value.corePipelineSession.selectedOverlayIds.contains(savedOverlay.id)
        }

        studioVm.runCoreStage4ChopBroll()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            chopBodies.isNotEmpty() &&
                studioVm.uiState.value.corePipelineSession.stage == CorePipelineStage.FINAL_REVIEW
        }
        assertTrue(chopBodies.first().contains("GRID CAPACITY +42%"))
        assertTrue(chopBodies.first().contains("ERCOT BATTERY DISPATCH REPORT"))

        // Verify removing the saved overlay deletes it from Room
        onNodeWithTag("newsroom_studio_list").performScrollToKey(savedOverlay.id)
        onNodeWithTag("delete_saved_overlay_${savedOverlay.id}")
            .performScrollTo()
            .performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.savedOverlays.none { it.id == savedOverlay.id }
        }
    }

    @Test
    fun stockA2uiTestController_verifiesBasicCatalogAndDefensiveErrorBoundary() = runComposeUiTest {
        val catalog = createStockAircheckA2uiCatalog(app)
        val controller = A2uiTestController(
            catalog = catalog,
            initialComponents = listOf(
                A2uiComponentPayload(
                    id = "root",
                    type = "Card",
                    properties = mapOf("child" to "col")
                ),
                A2uiComponentPayload(
                    id = "col",
                    type = "Column",
                    properties = mapOf("children" to listOf("headline", "cta_btn"))
                ),
                A2uiComponentPayload(
                    id = "headline",
                    type = "Text",
                    properties = mapOf(
                        "text" to mapOf("path" to "/banner"),
                        "variant" to "h4"
                    )
                ),
                A2uiComponentPayload(
                    id = "cta_txt",
                    type = "Text",
                    properties = mapOf("text" to "Ask to Install Remotion", "variant" to "body")
                ),
                A2uiComponentPayload(
                    id = "cta_btn",
                    type = "Button",
                    properties = mapOf(
                        "child" to "cta_txt",
                        "variant" to "primary",
                        "action" to mapOf(
                            "event" to mapOf(
                                "name" to "ask_install_remotion",
                                "context" to mapOf("banner" to mapOf("path" to "/banner"))
                            )
                        )
                    )
                )
            ),
            initialData = mapOf("banner" to "Remotion Ready Check")
        )

        val surface = controller.start()
        setContent {
            AircheckTheme {
                A2uiSurface(
                    surfaceModel = surface,
                    errorContent = { err ->
                        Text(text = "A2UI boundary caught: ${err.code}")
                    }
                )
            }
        }
        controller.waitForIdle()

        onNodeWithText("Remotion Ready Check").assertIsDisplayed()
        onNodeWithText("Ask to Install Remotion").performClick()
        controller.waitForIdle()

        val dispatched = controller.dispatchedActions.filterIsInstance<A2uiEventAction>()
        assertEquals(1, dispatched.size)
        assertEquals("ask_install_remotion", dispatched.first().eventName)
        assertEquals(1, controller.outboundEvents.size)
        assertEquals("ask_install_remotion", controller.outboundEvents.first().type)
        assertEquals("Remotion Ready Check", controller.outboundEvents.first().context["banner"])

        controller.failComponent(
            "root",
            A2uiException.A2uiRuntimeException("Simulated malformed payload")
        )
        controller.waitForIdle()
        onNodeWithText("A2UI boundary caught: RUNTIME_ERROR").assertIsDisplayed()
    }

    @Test
    fun newsroomStudioScreen_whenRigOffline_savesRequestHonestlyWithoutFakeSuccess() = runComposeUiTest {
        val closedPort = ServerSocket(0).use { it.localPort }
        runBlocking {
            app.container.profileStore.setTailscaleRigUrl("http://127.0.0.1:$closedPort")
        }

        val current = runBlocking { app.container.profileStore.current() }
        if (current.remotionUsedCount == 0 && current.providers.packageProviderId != "mac_rig_remotion") {
            var studioVmRef: StudioViewModel? = null
            setContent {
                val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
                val state = studioVm.uiState.collectAsStateWithLifecycle().value
                AircheckTheme {
                    NewsroomStudioScreen(
                        uiState = state,
                        a2uiController = studioVm.a2uiController,
                        onBack = {}
                    )
                }
            }

            waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                onAllNodesWithText("Ask to Install Remotion")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Ask to Install Remotion")
                .performScrollTo()
                .performClick()

            waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                studioVmRef?.uiState?.value?.errorMessage?.contains("unreachable") == true
            }
            val after = runBlocking { app.container.profileStore.current() }
            assertTrue(after.remotionInstallRequested)
            assertEquals(0, after.remotionUsedCount)
        }
    }

    @Test
    fun voiceModelCareScreen_enforcesPhoneOnlyMode_showsExactLocation_importsLora_andRunsRealQualityCheck() = runComposeUiTest {
        val pcm16k = ShortArray(16_000) { i ->
            val t = i.toDouble() / 16_000.0
            (0.5 * sin(2.0 * PI * 440.0 * t) * 32767.0).toInt().toShort()
        }
        val tempLora = File(app.cacheDir, "voice_lora_v009.gguf").apply {
            writeBytes("GGUF_LOCAL_PHONE_ONLY_LORA_WEIGHTS_V009".toByteArray(Charsets.UTF_8))
        }
        runBlocking {
            app.container.voiceModelCareManager.writeWavSampleForCare(pcm16k, "phone_check_440hz.wav")
            app.container.voiceModelCareManager.importLocalModelOrLoraFile(tempLora)
        }

        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val state = studioVm.uiState.collectAsStateWithLifecycle().value
            AircheckTheme {
                VoiceModelCareScreen(
                    uiState = state,
                    onTogglePhoneOnlyMode = studioVm::setPhoneOnlyVoiceMode,
                    onVerifyFiles = { studioVm.refreshVoiceModelCare(verifyChecksums = true) },
                    onStartRecordSample = studioVm::startVoiceCareSampleRecording,
                    onStopRecordSample = studioVm::stopVoiceCareSampleRecording,
                    onDeleteSample = studioVm::deleteVoiceCareSample,
                    onImportModelUri = studioVm::importLocalVoiceModelUri,
                    onRunQualityCheck = studioVm::runOnPhoneVoiceQualityCheck,
                    onBack = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.voiceStorageSnapshot?.importedFiles?.any {
                it.fileName == "voice_lora_v009.gguf"
            } == true
        }

        onNodeWithTag("voice_care_bundles_path_text").assertIsDisplayed()
        onNodeWithTag("voice_care_root_path_text").assertIsDisplayed()

        onNodeWithTag("voice_care_phone_only_switch").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !studioVm.uiState.value.settings.phoneOnlyVoiceMode &&
                runBlocking { !app.container.profileStore.current().phoneOnlyVoiceMode }
        }

        onNodeWithTag("voice_care_phone_only_switch").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.settings.phoneOnlyVoiceMode &&
                runBlocking { app.container.profileStore.current().phoneOnlyVoiceMode }
        }
        val lockedSettings = runBlocking { app.container.profileStore.current() }
        assertTrue(lockedSettings.phoneOnlyVoiceMode)
        assertFalse(lockedSettings.providers.useLanVoice)

        onNodeWithTag("voice_care_lazy_list").performScrollToKey("storage_location_card")
        onNodeWithTag("verify_on_phone_files_button")
            .performScrollTo()
            .performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.voiceStorageSnapshot?.lastVerifiedChecksums == true
        }

        onNodeWithTag("voice_care_lazy_list").performScrollToKey("quality_check_card")
        onNodeWithTag("run_voice_quality_check_button")
            .performScrollTo()
            .performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.voiceQualityReport?.hasAudio == true
        }

        val report = studioVm.uiState.value.voiceQualityReport
        assertNotNull(report)
        assertEquals("phone_check_440hz.wav", report!!.inspectedFileName)
        assertEquals(0, report.clippedSampleCount)
        assertTrue("Expected real peak ~ -6 dBFS, got ${report.peakDbfs}", report.peakDbfs!! in -6.5f..-5.5f)
        assertTrue("Expected real RMS ~ -9 dBFS, got ${report.rmsDbfs}", report.rmsDbfs!! in -9.5f..-8.5f)
        onNodeWithTag("voice_quality_summary_text")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun systemDeckSheet_opensCorePipelineAndOptionalNewsroomAndVoiceCareScreens() = runComposeUiTest {
        var draftVmRef: DraftDeckViewModel? = null
        var studioVmRef: StudioViewModel? = null

        setContent {
            val draftVm = draftVmRef ?: DraftDeckViewModel(app).also { draftVmRef = it }
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            AircheckApp(
                draftViewModel = draftVm,
                studioViewModel = studioVm
            )
        }

        assertTrue(onAllNodesWithText("Voice Model Care").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithText("Create Newsroom Overlays").fetchSemanticsNodes().isEmpty())

        onNodeWithTag("tactile_drafting_deck_root").assertIsDisplayed()
        onNodeWithTag("top_settings_button").performClick()
        waitForIdle()

        onNodeWithTag("open_core_pipeline_button")
            .performScrollTo()
            .performClick()
        waitForIdle()
        onNodeWithTag("core_pipeline_back_button").assertIsDisplayed()
        onNodeWithTag("core_pipeline_back_button").performClick()
        waitForIdle()
        onNodeWithTag("tactile_drafting_deck_root").assertIsDisplayed()

        onNodeWithTag("top_settings_button").performClick()
        waitForIdle()

        onNodeWithTag("open_voice_model_care_button")
            .performScrollTo()
            .performClick()
        waitForIdle()
        onNodeWithTag("voice_care_back_button").assertIsDisplayed()
        onNodeWithTag("voice_care_back_button").performClick()
        waitForIdle()

        onNodeWithTag("open_newsroom_studio_button")
            .performScrollTo()
            .performClick()
        waitForIdle()
        onNodeWithTag("newsroom_studio_back_button").assertIsDisplayed()
        onNodeWithTag("newsroom_studio_back_button").performClick()
        waitForIdle()

        onNodeWithTag("close_system_deck_button").performClick()
        waitForIdle()
    }

    @Test
    fun corePipelineScreen_preservesTrailingWhitespaceWhileTyping_andRefreshesSameSizeClauseEdit() = runComposeUiTest {
        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val st by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme {
                CorePipelineScreen(
                    uiState = st,
                    viewModel = studioVm,
                    onBack = {},
                    onOpenOptionalOverlayBuilder = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)

        runBlocking {
            val base = app.container.corePipelineCoordinator.seedSessionFromStory(
                rawStoryText = "Alpha one. Beta two.",
                headline = "EDIT TEST"
            )
            val withTts = base.copy(ttsVoiceSha = "fake_wav_sha", ttsDurationMs = 2000L)
            app.container.localRepo.saveCorePipelineSession(withTts)
        }
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.clauses.size == 2 &&
                onAllNodesWithTag("core_tts_story_input").fetchSemanticsNodes().isNotEmpty()
        }

        // Typing with a trailing space must not eat whitespace mid-typing
        onNodeWithTag("core_tts_story_input").performTextReplacement("Alpha one. Beta two. ")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        onNodeWithTag("core_tts_story_input").assertTextContains("Alpha one. Beta two. ")

        // Switch to Stage 2 (ITERATE_STORY), edit clause 0 keeping clauses.size == 2, then return to Stage 1
        studioVm.selectCoreStage(CorePipelineStage.ITERATE_STORY)
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("core_clause_input_0").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("core_clause_input_0").performTextReplacement("Gamma updated.")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        studioVm.selectCoreStage(CorePipelineStage.TTS)
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("core_tts_story_input").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("core_tts_story_input").assertTextContains("Gamma updated. Beta two.")
    }

    private val rigTasks = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val rigTaskSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** Speaks the rig's durable async-task protocol (`?async=1` -> 202 task id -> GET /v1/jobs/<id>). */
    private fun respondMaybeAsync(exchange: com.sun.net.httpserver.HttpExchange, resp: ByteArray) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (exchange.requestURI.query?.contains("async=1") == true) {
            val id = "task_${rigTaskSeq.incrementAndGet()}"
            rigTasks[id] = resp.toString(Charsets.UTF_8)
            val accepted = """{"job_id":"$id","status":"RUNNING"}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(202, accepted.size.toLong())
            exchange.responseBody.use { it.write(accepted) }
        } else {
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
    }

    private fun serveRigTask(exchange: com.sun.net.httpserver.HttpExchange) {
        val id = exchange.requestURI.path.substringAfterLast('/')
        val result = rigTasks[id]
        val body = (if (result == null) """{"error":"not_found"}""" else """{"job_id":"$id","status":"READY","result":$result}""")
            .toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(if (result == null) 404 else 200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

}
