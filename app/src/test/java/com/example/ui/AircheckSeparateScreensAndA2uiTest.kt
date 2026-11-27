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
import com.example.ui.clipstudio.CutScreen
import com.example.ui.clipstudio.NewsroomLookScreen
import com.example.ui.draft.DraftDeckViewModel
import com.example.ui.screens.NewsroomStudioScreen
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
    fun clipStudioCutScreen_exercisesAllControlsAndPersistsRealRoomState() = runComposeUiTest {
        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val st by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme {
                CutScreen(
                    state = st,
                    vm = studioVm,
                    onOpenSettings = {},
                    onOpenNewsroom = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)
        studioVm.openStoryInCorePipeline("First broadcast line. Second evidence line.", headline = "CUT TEST")

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.clauses.size == 2 &&
                onAllNodesWithText("Story loaded in Core Pipeline (01 TTS).").fetchSemanticsNodes().isNotEmpty()
        }

        // Dismiss status notice by tapping it
        onNodeWithText("Story loaded in Core Pipeline (01 TTS).").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.statusNotice == null
        }

        // Exercise v5 monitor, play key, captions, aspect, punch-in, trim pauses, undo, tap-to-expand clause edit, and b-roll mode
        onNodeWithTag("cs_cut_monitor").assertIsDisplayed()
        onNodeWithTag("cs_cut_play").assertIsDisplayed()
        onNodeWithTag("cs_cut_captions").performClick()
        onNodeWithTag("cs_cut_aspect").performClick()
        onNodeWithTag("cs_cut_punch_in").performClick()
        onNodeWithTag("cs_cut_strip_silence").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val saved = runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
            saved != null && saved.renderOptions.captions && saved.renderOptions.punchIn &&
                saved.renderOptions.stripSilence && saved.renderOptions.aspect == com.example.data.CoreRenderOptions.ASPECT_HORIZONTAL
        }

        // Undo reverts the last render option toggle (stripSilence)
        onNodeWithTag("cs_cut_undo").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val saved = runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
            saved != null && !saved.renderOptions.stripSilence
        }

        // Single tap on clause 0 expands inline actions (`edit words` & `b-roll here`) without long-press
        onNodeWithTag("cs_clause_0").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_clause_edit_btn_0").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("cs_clause_edit_btn_0").performClick()
        onNodeWithTag("cs_clause_editor_0").performTextReplacement("Updated first broadcast line.")
        onNodeWithTag("cs_clause_apply_0").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val saved = runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
            saved?.clauses?.firstOrNull()?.spokenText == "Updated first broadcast line."
        }

        // Switch to B-roll mode and toggle clause 0 to B-roll cutaway
        onNodeWithTag("cs_cut_mode_timeline").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_timeline_toggle_0").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("cs_timeline_toggle_0").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val saved = runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
            saved?.clauses?.firstOrNull()?.segmentMode == com.example.data.BrollSegmentMode.BROLL_CUTAWAY
        }
    }

    @Test
    fun clipStudioNewsroomLookScreen_exercisesTickRulersPillsSaveDuplicateAndDefault() = runComposeUiTest {
        var studioVmRef: StudioViewModel? = null
        var returnedToCut = false
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val st by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme {
                NewsroomLookScreen(
                    state = st,
                    vm = studioVm,
                    onBack = { returnedToCut = true },
                    onTestOnClip = {},
                    onOpenOverlayBuilder = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)
        val initialCount = runBlocking { app.container.localRepo.newsroomLooksOnce().size }

        // Tab 2 (`Look`) hosts look presets, blur/brightness rulers, and lighting controls
        onNodeWithTag("cs_newsroom_tab_2").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_look_new").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag("cs_look_new").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.newsroomLooksOnce().size } == initialCount + 1
        }

        // Duplicate look from preset header key
        onNodeWithTag("cs_look_duplicate").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.newsroomLooksOnce().size } == initialCount + 2
        }

        // Adjust BLUR (±5%) + BRIGHTNESS (±0.2 EV) + 3-way LIGHT (`warm`)
        onNodeWithTag("cs_scrub_blur_inc").performScrollTo().performClick()
        onNodeWithTag("cs_scrub_blur_inc").performClick()
        onNodeWithTag("cs_scrub_ev_inc").performScrollTo().performClick()
        onNodeWithTag("cs_light_warm").performScrollTo().performClick()

        // Switch to `Overlays` tab (`cs_newsroom_tab_1`) and verify custom overlay creator key
        onNodeWithTag("cs_newsroom_tab_1").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_new_overlay_toggle").fetchSemanticsNodes().isNotEmpty()
        }

        // Primary dock key saves edits, sets default in Room, posts toast, and navigates back
        onNodeWithTag("cs_use_look").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val def = runBlocking { app.container.localRepo.getDefaultNewsroomLook() }
            returnedToCut && def != null && def.blurPercent == 10 && def.brightnessEv == 0.2 &&
                def.light == com.example.data.NewsroomLook.LIGHT_WARM &&
                studioVm.uiState.value.toastMessage?.contains("Overlays saved") == true
        }
    }

    @Test
    fun newsroomLookScreen_breakingNewsStyleChips_andGenerateOverlaySet_persistAndPreviewLive() = runComposeUiTest {
        val aiBodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            val resp = """{"status":"ok","lora_version":2,"render_bridge":true}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/ai-assist") { exchange ->
            val req = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            aiBodies.add(req)
            val resp = if (req.contains("\"polish_headline\"")) {
                """{"headline":"Log video fixes default phone HDR"}""".toByteArray(Charsets.UTF_8)
            } else {
                """
                    {
                      "mode": "generate_overlay_set",
                      "breaking_style": "developing",
                      "overlays": [
                        {
                          "kind": "QUOTE_BRIEF",
                          "breaking_style": "developing",
                          "headline": "Default tone-mapping crushes highlight roll-off.",
                          "subline": "@CAMERA_LAB",
                          "callout_stat": "FIELD BRIEF",
                          "duration_sec": 5
                        }
                      ]
                    }
                """.trimIndent().toByteArray(Charsets.UTF_8)
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.start()
        httpServer = server
        val port = server.address.port

        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(false)
            app.container.profileStore.setTailscaleRigUrl("http://127.0.0.1:$port")
        }

        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val st by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme {
                NewsroomLookScreen(
                    state = st,
                    vm = studioVm,
                    onBack = {},
                    onTestOnClip = {},
                    onOpenOverlayBuilder = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)
        studioVm.openStoryInCorePipeline(
            plainText = "Auto-HDR flattens skin tones and bakes edge halos on phone video.",
            headline = "PHONE VIDEO HDR"
        )
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.clauses.isNotEmpty()
        }

        // Tab 0 (`Labels`) opens directly with lower-third style chips & AI title polish
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_tag_breaking").fetchSemanticsNodes().isNotEmpty()
        }

        // Cycle TAG ABOVE TITLE pills (`exclusive`, `clean`, `developing`) and assert real Room persistence
        onNodeWithTag("cs_tag_exclusive").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
                ?.renderOptions?.breakingStyle == com.example.data.BreakingNewsStyle.EXCLUSIVE
        }

        onNodeWithTag("cs_tag_clean").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
                ?.renderOptions?.breakingStyle == com.example.data.BreakingNewsStyle.CLEAN &&
                onAllNodesWithTag("cs_canvas_breaking_kicker", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }

        onNodeWithTag("cs_tag_developing").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
                ?.renderOptions?.breakingStyle == com.example.data.BreakingNewsStyle.DEVELOPING &&
                onAllNodesWithTag("cs_canvas_breaking_kicker", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Refresh title with AI (`polish_headline` on Mac rig) and verify Room persistence + live preview
        onNodeWithTag("cs_title_refresh").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            aiBodies.any { it.contains("\"polish_headline\"") } &&
                runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
                    ?.renderOptions?.chyronHeadline == "Log video fixes default phone HDR"
        }
        onNodeWithText("DEVELOPING", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            2,
            onAllNodesWithText("Log video fixes default phone HDR", useUnmergedTree = true).fetchSemanticsNodes().size
        )
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
