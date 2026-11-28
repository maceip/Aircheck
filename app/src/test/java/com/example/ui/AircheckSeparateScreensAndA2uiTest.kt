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
import com.example.ui.clipstudio.BackdropScreen
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTouchInput
import com.example.ui.draft.DraftDeckViewModel
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

        // Monitor, flat play key, captions toggle persisted to Room, undo, and the drag grip resizes the player
        onNodeWithTag("cs_cut_monitor").assertIsDisplayed()
        onNodeWithTag("cs_cut_play").assertIsDisplayed()
        onNodeWithTag("cs_cut_captions").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.getLatestCorePipelineSession() }?.renderOptions?.captions == true
        }
        onNodeWithTag("cs_cut_undo").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.getLatestCorePipelineSession() }?.renderOptions?.captions == false
        }
        val before = onNodeWithTag("cs_cut_monitor").fetchSemanticsNode().size.height
        onNodeWithTag("cs_cut_player_grip", useUnmergedTree = true).performTouchInput { swipeDown(startY = centerY, endY = centerY + 300f) }
        val after = onNodeWithTag("cs_cut_monitor").fetchSemanticsNode().size.height
        assertTrue("grip drag should grow the player ($before -> $after)", after > before)
    }

    @Test
    fun backdropScreen_looksRulersLight_editTheDefaultLook_andNewsroomDoneSaves() = runComposeUiTest {
        var studioVmRef: StudioViewModel? = null
        var screen by mutableStateOf("backdrop")
        var returnedToCut = false
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val st by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme {
                if (screen == "backdrop") {
                    BackdropScreen(state = st, vm = studioVm, onBack = { screen = "newsroom" })
                } else {
                    NewsroomLookScreen(state = st, vm = studioVm, onBack = {}, onDone = { returnedToCut = true }, onOpenBackdrop = { screen = "backdrop" })
                }
            }
        }
        val studioVm = checkNotNull(studioVmRef)
        val initialCount = runBlocking { app.container.localRepo.newsroomLooksOnce().size }
        onNodeWithTag("cs_backdrop_preview").assertExists()

        onNodeWithTag("cs_look_new").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.newsroomLooksOnce().size } == initialCount + 1
        }
        onNodeWithTag("cs_look_duplicate").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.newsroomLooksOnce().size } == initialCount + 2
        }

        // BLUR (+5% x2), EXPOSURE (+0.2 EV), light warm: saved straight to the default look
        onNodeWithTag("cs_scrub_blur_inc").performScrollTo().performClick()
        onNodeWithTag("cs_scrub_blur_inc").performClick()
        onNodeWithTag("cs_scrub_ev_inc").performScrollTo().performClick()
        onNodeWithTag("cs_light_warm").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val def = studioVm.uiState.value.newsroomLooks.firstOrNull { it.isDefault }
            def != null && def.blurPercent == 10 && def.brightnessEv == 0.2 && def.light == com.example.data.NewsroomLook.LIGHT_WARM
        }
        // generate set needs a room prompt
        onNodeWithTag("cs_backdrop_generate").assertIsNotEnabled()

        // Back -> Newsroom; done saves the look as default in Room and returns to Cut
        onNodeWithTag("cs_backdrop_back").performClick()
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
                NewsroomLookScreen(state = st, vm = studioVm, onBack = {}, onDone = {}, onOpenBackdrop = {})
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

        // Kicker styles live in the chyron's edit sheet; save persists to Room and the preview follows.
        fun pickStyle(tag: String) {
            onNodeWithTag("cs_graphic_card_chyron").performScrollTo().performClick()
            onNodeWithTag(tag).performClick()
            onNodeWithTag("cs_graphic_save").performClick()
        }
        pickStyle("cs_tag_exclusive")
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
                ?.renderOptions?.breakingStyle == com.example.data.BreakingNewsStyle.EXCLUSIVE
        }
        pickStyle("cs_tag_clean")
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
                ?.renderOptions?.breakingStyle == com.example.data.BreakingNewsStyle.CLEAN &&
                onAllNodesWithTag("cs_canvas_breaking_kicker", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        pickStyle("cs_tag_developing")
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
                ?.renderOptions?.breakingStyle == com.example.data.BreakingNewsStyle.DEVELOPING &&
                onAllNodesWithTag("cs_canvas_breaking_kicker", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        // AI polish (`polish_headline` on the rig) from the headline field; Room + preview + sheet follow
        onNodeWithTag("cs_graphic_card_chyron").performScrollTo().performClick()
        onNodeWithTag("cs_title_refresh").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            aiBodies.any { it.contains("\"polish_headline\"") } &&
                runBlocking { app.container.localRepo.getLatestCorePipelineSession() }
                    ?.renderOptions?.chyronHeadline == "Log video fixes default phone HDR"
        }
        onNode(hasTestTag("cs_title_input") and hasText("Log video fixes default phone HDR")).assertExists()
        onNode(hasText("Log video fixes default phone HDR") and hasAnyAncestor(hasTestTag("cs_look_canvas")), useUnmergedTree = true).assertExists()
        onNodeWithTag("cs_graphic_sheet_close").performClick()

        // AI 3-pack: the generated quote card gets its own card; its LED attaches/detaches it; its sheet edits timing and deletes
        onNodeWithTag("cs_generate_overlay_set").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.savedOverlays.any { it.headline == "Default tone-mapping crushes highlight roll-off." }
        }
        val quote = studioVm.uiState.value.savedOverlays.first { it.headline == "Default tone-mapping crushes highlight roll-off." }
        val wasAttached = quote.id in studioVm.uiState.value.corePipelineSession.selectedOverlayIds
        onNodeWithTag("cs_graphic_led_${quote.id}").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            (quote.id in studioVm.uiState.value.corePipelineSession.selectedOverlayIds) != wasAttached
        }
        onNodeWithTag("cs_graphic_card_${quote.id}").performScrollTo().performClick()
        onNodeWithTag("cs_overlay_secs_8").performClick()
        onNodeWithTag("cs_graphic_save").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.savedOverlays.first { it.id == quote.id }.durationSec == 8
        }
        onNodeWithTag("cs_graphic_card_${quote.id}").performScrollTo().performClick()
        onNodeWithTag("cs_graphic_delete").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.savedOverlays.none { it.id == quote.id }
        }
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
