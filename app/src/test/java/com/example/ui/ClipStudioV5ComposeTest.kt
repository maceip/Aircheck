package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.AircheckApplication
import com.example.agent.OnDevicePromptEngine
import com.example.ai.LlmStageRouter
import com.example.data.BrollSegmentMode
import com.example.data.ClipRecord
import com.example.data.CorePipelineSession
import com.example.data.CoreStoryClause
import com.example.data.LlmProviderChoice
import com.example.data.LlmStage
import com.example.data.OverlayObjectKind
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.draft.BrushNudge
import com.example.draft.DraftApiClient
import com.example.draft.DraftTone
import com.example.draft.ShotType
import com.example.graph.GraphContactRecord
import com.example.graph.GraphPostRecord
import com.example.ui.clipstudio.ClipStudioScreen
import com.example.ui.clipstudio.ClipStudioShell
import com.example.ui.draft.DraftDeckViewModel
import com.example.ui.screens.SystemDeckSheet
import com.example.ui.theme.AircheckTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xxhdpi")
class ClipStudioV5ComposeTest {

    private lateinit var app: AircheckApplication
    private lateinit var okHttp: OkHttpClient
    private var httpServer: HttpServer? = null
    private var serverExecutor: ExecutorService? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>() as AircheckApplication
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            androidx.work.Configuration.Builder()
                .setExecutor(androidx.work.testing.SynchronousExecutor())
                .setTaskExecutor(androidx.work.testing.SynchronousExecutor())
                .build()
        )
        okHttp = OkHttpClient()
        app.container.localGraphDatabase.clearAll()
        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(true)
            app.container.profileStore.setLlmStageConfig(LlmStage.DRAFTING, LlmProviderChoice.MAC_RIG_DEFAULT)
            app.container.profileStore.setLlmStageConfig(LlmStage.SCRIPT_EDIT, LlmProviderChoice.MAC_RIG_DEFAULT)
            app.container.profileStore.setLlmStageConfig(LlmStage.NEWSROOM_OVERLAY, LlmProviderChoice.MAC_RIG_DEFAULT)
            app.container.localRepo.listSavedNewsroomOverlays().forEach {
                app.container.localRepo.deleteNewsroomOverlay(it.id)
            }
            app.container.localRepo.saveCorePipelineSession(CorePipelineSession())
        }
    }

    @After
    fun tearDown() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        httpServer?.stop(0)
        httpServer = null
        serverExecutor?.shutdownNow()
        serverExecutor = null
    }

    private fun startTestServer(): HttpServer {
        val exec = Executors.newCachedThreadPool()
        serverExecutor = exec
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = exec
        }
    }

    private fun buildDraftVm(baseUrl: String): DraftDeckViewModel {
        val clientFactory: (String) -> DraftApiClient = { url ->
            val target = if (url == DEFAULT_MAC_RIG_LAN_URL) baseUrl else url
            DraftApiClient(
                configuredRigUrl = target,
                usbLoopbackUrl = baseUrl,
                okHttpClient = okHttp,
                healthProbeTimeoutMs = 500L
            )
        }
        val router = LlmStageRouter(
            profileStore = app.container.profileStore,
            draftApiClientProvider = clientFactory,
            onDeviceEngine = OnDevicePromptEngine(),
            okHttpClient = okHttp
        )
        return DraftDeckViewModel(
            application = app,
            llmRouterOverride = router,
            draftApiClientFactory = clientFactory,
            profileStoreOverride = app.container.profileStore,
            casStoreOverride = app.container.casStore
        )
    }

    private fun realJpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(30, 42, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(28, 42, 58))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun sparkToScriptToCutToVideoLook_fullClipStudioV5Flow_persistsRealBackendState() = runComposeUiTest {
        val recordedArcBodies = CopyOnWriteArrayList<String>()
        val recordedScriptBodies = CopyOnWriteArrayList<String>()
        val recordedBrushBodies = CopyOnWriteArrayList<String>()
        val shotPreviewCount = AtomicInteger(0)
        val jpegBytes = realJpegBytes()

        val server = startTestServer()
        val portHolder = AtomicInteger(0)

        server.createContext("/health") { ex ->
            val resp = """{"status":"ok","lora_version":5,"render_bridge_available":true}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/draft/angles") { ex ->
            val resp = """
                {
                  "extracted_angles": [
                    {"id": "a_local", "label": "offline voice", "preview": "Runs locally on device"},
                    {"id": "a_cost", "label": "zero monthly fee", "preview": "No recurring cloud bill"}
                  ],
                  "blindspots": [
                    {"id": "b_thermal", "label": "thermal headroom", "preview": "Sustained NPU load"}
                  ],
                  "tone": "solarpunk"
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/draft/arc") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            recordedArcBodies += body
            val isReroll = body.contains("\"reroll_index\":1")
            val detailText = if (isReroll) {
                "Rerolled beat: voice, captions, and edits execute straight on the handset."
            } else {
                "Voice, captions, and cuts execute straight on the handset."
            }
            val resp = """
                {
                  "target_sec": 60,
                  "word_budget": 150,
                  "tone": "solarpunk",
                  "beats": [
                    {"label": "HOOK", "start_sec": 0, "end_sec": 8, "text": "Local chips make recurring editing bills obsolete.", "shots": [{"type": "face", "prompt": "anchor"}]},
                    {"label": "DETAIL", "start_sec": 8, "end_sec": 26, "text": "$detailText", "shots": [{"type": "pip", "prompt": "waveform"}, {"type": "broll", "prompt": "handset"}]},
                    {"label": "SHIFT", "start_sec": 26, "end_sec": 45, "text": "Cloud clusters win on batch scale, not personal privacy.", "shots": [{"type": "broll", "prompt": "rack"}]},
                    {"label": "PAYOFF", "start_sec": 45, "end_sec": 60, "text": "Own the silicon once instead of renting servers monthly.", "shots": [{"type": "card", "prompt": "Own the silicon once."}]}
                  ]
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/studio/shot-preview") { ex ->
            val n = shotPreviewCount.incrementAndGet()
            val p = portHolder.get()
            val resp = """
                {
                  "preview_id": "shotprev_$n",
                  "preview_url": "http://127.0.0.1:$p/v1/renders/shotprev_$n.jpg",
                  "shot_type": "broll",
                  "width": 180,
                  "height": 252
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/renders/") { ex ->
            ex.responseHeaders.add("Content-Type", "image/jpeg")
            ex.sendResponseHeaders(200, jpegBytes.size.toLong())
            ex.responseBody.use { it.write(jpegBytes) }
        }
        server.createContext("/v1/draft/synthesize-script") { ex ->
            recordedScriptBodies += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val resp = """
                {
                  "target_sec": 60,
                  "word_budget": 148,
                  "tone": "solarpunk",
                  "candidates": [
                    {"posture_label": "DIRECT", "text": "Local chips make monthly editing subscriptions obsolete.\n\nVoice cloning and cuts run directly on your handset.\n\nCloud clusters win on scale, not personal privacy.\n\nOwn the silicon once instead of renting servers forever."},
                    {"posture_label": "PUNCHY", "text": "Monthly AI bill? Your phone runs it free.\n\nVoice and cuts stay offline.\n\nCloud wins scale. Phone wins privacy.\n\nBuy the chip once."},
                    {"posture_label": "STORY", "text": "Last month I paid twenty dollars to cut one clip.\n\nYesterday I cut the same story offline on a flight.\n\nServers are bigger, yet your pocket is private.\n\nOwn the tool once."}
                  ]
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/draft/brush-swap") { ex ->
            val req = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            recordedBrushBodies += req
            val resp = """
                {
                  "alternatives": ["recurring cloud plans", "twenty-dollar SaaS tiers", ""],
                  "tone": "solarpunk"
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/draft/density") { ex ->
            val resp = """
                {
                  "text": "Local chips make monthly subscriptions obsolete.\n\nVoice cuts run on your handset.\n\nCloud wins scale, not privacy.\n\nOwn silicon once.",
                  "lock_parity_preserved": true
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        val recordedAiAssistBodies = CopyOnWriteArrayList<String>()
        server.createContext("/v1/ai-assist") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            recordedAiAssistBodies += body
            val resp = """
                {
                  "mode": "tweak_video_look",
                  "accent_hex": "#3A86F2",
                  "light": "cool",
                  "blur_percent": 30,
                  "brightness_ev": 0.4,
                  "breaking_style": "developing",
                  "show_brand_bug": true,
                  "corner_bug_text": "TECH DESK",
                  "broll_framing": "split",
                  "summary": "cobalt accent, cool light, TECH DESK logo"
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        httpServer = server
        val port = server.address.port
        portHolder.set(port)
        val rigUrl = "http://127.0.0.1:$port"

        runBlocking {
            app.container.profileStore.setTailscaleRigUrl(rigUrl)
        }

        var draftVmRef: DraftDeckViewModel? = null
        var studioVmRef: StudioViewModel? = null
        setContent {
            val draftVm = draftVmRef ?: buildDraftVm(rigUrl).also { draftVmRef = it }
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val draftState by draftVm.uiState.collectAsStateWithLifecycle()
            val studioState by studioVm.uiState.collectAsStateWithLifecycle()
            var screen by remember { mutableStateOf(ClipStudioScreen.SPARK) }
            AircheckTheme {
                ClipStudioShell(
                    draftState = draftState,
                    draftVm = draftVm,
                    studioState = studioState,
                    studioVm = studioVm,
                    screen = screen,
                    onScreen = { screen = it },
                    onOpenSettings = {}
                )
            }
        }
        val draftVm = checkNotNull(draftVmRef)
        val studioVm = checkNotNull(studioVmRef)

        // 1. 01 SPARK Empty State: verify Continue card hidden when lastCompletedVideo == null
        onNodeWithText("What's the clip about?").assertIsDisplayed()
        if (studioVm.uiState.value.lastCompletedVideo == null) {
            assertTrue(onAllNodesWithTag("cs_continue_card").fetchSemanticsNodes().isEmpty())
        }

        // Select `solarpunk` tone pill and submit typed note
        onNodeWithTag("cs_tone_solarpunk").performScrollTo().performClick()
        assertEquals(DraftTone.SOLARPUNK, draftVm.uiState.value.tone)

        onNodeWithTag("cs_type_instead").performClick()
        onNodeWithTag("cs_voice_note_editor").performTextReplacement(
            "On-device voice and video models make recurring cloud editing subscriptions unnecessary."
        )
        onNodeWithTag("cs_typed_note_submit").performClick()

        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            draftVm.uiState.value.document.arcBeats.size == 4 &&
                !draftVm.uiState.value.isBusy &&
                draftVm.uiState.value.shotPreviews.isNotEmpty()
        }
        assertTrue(recordedArcBodies.first().contains("\"tone\":\"solarpunk\""))

        // Reroll beat 1 directly on 01 SPARK (thumbnails removed from Spark; B-roll lives in B-Roll Studio)
        onNodeWithTag("cs_reroll_beat_1").performScrollTo().performClick()
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            draftVm.uiState.value.document.lastRerolledBeat == 1 && draftVm.uiState.value.rerollingBeatIndices.isEmpty()
        }

        // Open Anchors sheet, toggle an extracted anchor, and apply
        onNodeWithTag("cs_anchors_button").performClick()
        onNodeWithTag("cs_anchors_sheet").assertIsDisplayed()
        onNodeWithTag("cs_keep_a_cost").performClick()
        onNodeWithTag("cs_anchors_done").performClick()
        assertTrue(onAllNodesWithTag("cs_anchors_sheet").fetchSemanticsNodes().isEmpty())
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !draftVm.uiState.value.isBusy && draftVm.uiState.value.document.arcBeats.size == 4
        }

        // Streamlined 2-step flow: tap "make the video" directly on 01 SPARK -> maps beats & shotPlan into 02 CUT!
        onNodeWithTag("cs_create_video").performClick()
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isNotEmpty() &&
                studioVm.uiState.value.corePipelineSession.clauses.isNotEmpty()
        }

        val cutClauses = studioVm.uiState.value.corePipelineSession.clauses
        assertTrue(cutClauses.any { it.segmentMode == BrollSegmentMode.BROLL_CUTAWAY })
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.savedOverlays.any { it.kind == OverlayObjectKind.QUOTE_BRIEF }
        }

        // 3. 03 CUT -> Newsroom: corner bug LED + edit sheet, quote card edit sheet, Backdrop, done
        onNodeWithTag("cs_cut_look").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_newsroom_root").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag("cs_graphic_led_bug").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.renderOptions.showBrandBug
        }
        onNodeWithTag("cs_graphic_card_bug").performScrollTo().performClick()
        onNodeWithTag("cs_bug_input").performTextReplacement("RYAN DESK")
        onNodeWithTag("cs_graphic_save").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.renderOptions.cornerBugText == "RYAN DESK"
        }

        val firstQuoteId = studioVm.uiState.value.savedOverlays.first { it.kind == OverlayObjectKind.QUOTE_BRIEF }.id
        onNodeWithTag("cs_graphic_card_$firstQuoteId").performScrollTo().performClick()
        onNodeWithTag("cs_overlay_text").performTextReplacement("Own silicon once forever")
        onNodeWithTag("cs_graphic_save").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.savedOverlays.first { it.id == firstQuoteId }.headline == "Own silicon once forever"
        }

        // Backdrop: accent swatch & blur ruler edit the default look, Back returns to Newsroom
        onNodeWithTag("cs_newsroom_backdrop").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_backdrop_root").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("cs_accent_amber").performScrollTo().performClick()
        onNodeWithTag("cs_scrub_blur_inc").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val def = studioVm.uiState.value.newsroomLooks.firstOrNull { it.isDefault }
            def != null && def.accentHex.equals(com.example.data.NewsroomLook.ACCENT_AMBER, ignoreCase = true) && def.blurPercent == 5
        }
        onNodeWithTag("cs_backdrop_back").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_newsroom_root").fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag("cs_use_look").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithTag("cs_toast").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Overlays saved · will apply when rendered").assertIsDisplayed()
        onNodeWithTag("cs_toast_ok").performClick()
        assertTrue(onAllNodesWithTag("cs_toast").fetchSemanticsNodes().isEmpty())

        // 4. 03 CUT -> tap a B-roll block -> framing full -> done. Blocks sit on the voiced timeline;
        //    before the voice has timing the timeline says so instead.
        val cutSession = studioVm.uiState.value.corePipelineSession
        val voicedMs = com.example.data.CutTimeline.timelineDurationMs(com.example.data.CutTimeline.effectiveClips(cutSession))
        if (voicedMs <= 0L) {
            onNodeWithText("TIMELINE APPEARS ONCE THE SCRIPT IS VOICED", useUnmergedTree = true).assertExists()
        } else {
            val brollSeg = cutSession.brollSegments.first()
            onNodeWithTag("cs_broll_block_${brollSeg.id}").performScrollTo().performClick()
            onNodeWithTag("cs_broll_framing_full").performClick()
            waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                studioVm.uiState.value.corePipelineSession.renderOptions.brollFraming == "full"
            }
            onNodeWithTag("cs_broll_sheet_done").performClick()
            waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                onAllNodesWithTag("cs_broll_sheet").fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    fun sparkScreen_continueLastVideo_omitsNullTopReply_andLoadsRealFollowupIntoSpark() = runComposeUiTest {
        val server = startTestServer()
        server.createContext("/health") { ex ->
            val resp = """{"status":"ok"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/draft/followup") { ex ->
            val resp = """
                {
                  "angle": "overview",
                  "target_sec": 60,
                  "word_budget": 150,
                  "answer_beats": [
                    {"label": "HOOK", "start_sec": 0, "end_sec": 8, "text": "You asked about battery drain during local edits."},
                    {"label": "TEST", "start_sec": 8, "end_sec": 28, "text": "Cutting three clips offline used four percent battery."},
                    {"label": "RESULT", "start_sec": 28, "end_sec": 46, "text": "Uploading raw footage over cellular used six percent."},
                    {"label": "PAYOFF", "start_sec": 46, "end_sec": 60, "text": "Local silicon saves both battery and subscription fees."}
                  ],
                  "directions": [
                    {"id": "shitpost", "theme": "SHITPOST", "words": "cloud rent is over", "beats": [
                      {"label": "HOOK", "start_sec": 0, "end_sec": 8, "text": "Cloud rent is officially over."},
                      {"label": "BIT", "start_sec": 8, "end_sec": 28, "text": "Your pocket chip already won."},
                      {"label": "TURN", "start_sec": 28, "end_sec": 46, "text": "Stop funding idle GPU queues."},
                      {"label": "PAYOFF", "start_sec": 46, "end_sec": 60, "text": "Keep your twenty dollars."}
                    ]},
                    {"id": "solarpunk", "theme": "SOLARPUNK", "words": "community compute wins", "beats": [
                      {"label": "HOOK", "start_sec": 0, "end_sec": 8, "text": "Shared local tools outlast rented platforms."},
                      {"label": "DETAIL", "start_sec": 8, "end_sec": 28, "text": "Open weights run for years on hardware you own."},
                      {"label": "SHIFT", "start_sec": 28, "end_sec": 46, "text": "Ownership beats metered APIs."},
                      {"label": "PAYOFF", "start_sec": 46, "end_sec": 60, "text": "Build once, share freely."}
                    ]}
                  ]
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/studio/shot-preview") { ex ->
            ex.sendResponseHeaders(404, 0)
            ex.responseBody.close()
        }
        server.start()
        httpServer = server
        val rigUrl = "http://127.0.0.1:${server.address.port}"

        val now = System.currentTimeMillis()
        runBlocking {
            app.container.profileStore.setTailscaleRigUrl(rigUrl)
            app.container.localRepo.upsertClip(
                ClipRecord(
                    id = "ready_clip_v5",
                    scriptId = "",
                    jobId = "job_v5",
                    title = "Local AI versus cloud subscriptions",
                    photoSha = null,
                    voiceSha = null,
                    alignSha = null,
                    lipsyncSha = null,
                    finalSha = null,
                    status = RenderJobStatus.READY,
                    progressFraction = 1f,
                    stageDetail = "Ready",
                    errorMessage = null,
                    durationMs = 58_000L,
                    waveformPeaks = emptyList(),
                    thumbHash = null,
                    alignedWords = emptyList(),
                    chyronHeadline = "Local AI versus cloud subscriptions",
                    chyronSubline = "",
                    remoteLipsyncUrl = null,
                    remoteFinalUrl = null,
                    exportedUri = null,
                    createdAtMillis = now - 172_800_000L,
                    updatedAtMillis = now - 172_800_000L
                )
            )
        }

        var draftVmRef: DraftDeckViewModel? = null
        var studioVmRef: StudioViewModel? = null
        setContent {
            val draftVm = draftVmRef ?: buildDraftVm(rigUrl).also { draftVmRef = it }
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val draftState by draftVm.uiState.collectAsStateWithLifecycle()
            val studioState by studioVm.uiState.collectAsStateWithLifecycle()
            var screen by remember { mutableStateOf(ClipStudioScreen.SPARK) }
            AircheckTheme {
                ClipStudioShell(
                    draftState = draftState,
                    draftVm = draftVm,
                    studioState = studioState,
                    studioVm = studioVm,
                    screen = screen,
                    onScreen = { screen = it },
                    onOpenSettings = {}
                )
            }
        }
        val draftVm = checkNotNull(draftVmRef)
        val studioVm = checkNotNull(studioVmRef)

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.lastCompletedVideo != null &&
                onAllNodesWithTag("cs_continue_card").fetchSemanticsNodes().isNotEmpty()
        }

        // Open Follow-Up sheet directly when topReplyQuestion == null -> verify reply line is omitted
        onNodeWithTag("cs_continue_open_sheet").performClick()
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            draftVm.uiState.value.followupBeats.size == 4 && !draftVm.uiState.value.isDraftingFollowup
        }
        onNodeWithTag("cs_followup_sheet").assertIsDisplayed()
        assertTrue(onAllNodesWithTag("cs_followup_top_reply").fetchSemanticsNodes().isEmpty())
        onNodeWithTag("cs_followup_new_idea").performClick()

        // Seed a real reply question in LocalGraphDatabase, refresh, and verify reply line renders
        app.container.localGraphDatabase.upsertContactsAndPosts(
            contacts = listOf(
                GraphContactRecord(
                    handle = "viewer_q",
                    displayName = "Viewer Q",
                    bio = "Creator",
                    followerCount = 300,
                    mutualRepliesCount = 2,
                    lastInteractionEpochMs = now
                )
            ),
            posts = listOf(
                GraphPostRecord(
                    tweetId = "tw_reply_1",
                    handle = "viewer_q",
                    text = "How much battery does local AI video editing use?",
                    createdAtEpochMs = now - 3_600_000L,
                    isReplyToUser = true
                )
            )
        )
        studioVm.refreshLastCompletedVideo()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.lastCompletedVideo?.topReplyQuestion != null
        }

        // Expand 2-up directions split panel, select SHITPOST direction, and load into SPARK
        onNodeWithTag("cs_continue_card").performClick()
        onNodeWithTag("cs_continue_directions").assertIsDisplayed()
        onNodeWithTag("cs_continue_dir_shitpost").performClick()
        onNodeWithTag("cs_followup_sheet").assertIsDisplayed()
        assertNotNull(onNodeWithTag("cs_followup_top_reply"))

        onNodeWithTag("cs_followup_use").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            draftVm.uiState.value.followupLoaded &&
                draftVm.uiState.value.document.arcBeats.size == 4
        }
        onNodeWithText("FOLLOW-UP:", substring = true).assertIsDisplayed()
    }

    @Test
    fun scriptScreen_newIdeaButton_resetsDraftAndNavigatesToEmptySpark() = runComposeUiTest {
        val server = startTestServer()
        server.createContext("/health") { ex ->
            val resp = """{"status":"ok"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/draft/angles") { ex ->
            val resp = """
                {
                  "extracted_angles": [
                    {"id":"a_edge","label":"edge AI","preview":"Runs locally"}
                  ],
                  "blindspots": [],
                  "tone": "direct"
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/draft/arc") { ex ->
            val resp = """
                {
                  "target_sec": 30,
                  "word_budget": 75,
                  "beats": [
                    {"label":"HOOK","start_sec":0,"end_sec":8,"text":"First idea hook."},
                    {"label":"DETAIL","start_sec":8,"end_sec":16,"text":"First idea detail."},
                    {"label":"SHIFT","start_sec":16,"end_sec":24,"text":"First idea shift."},
                    {"label":"PAYOFF","start_sec":24,"end_sec":30,"text":"First idea payoff."}
                  ]
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/draft/synthesize-script") { ex ->
            val resp = """
                {
                  "target_sec": 30,
                  "word_budget": 75,
                  "tone": "direct",
                  "candidates": [
                    {"posture_label":"DIRECT","text":"First idea hook. First idea detail.\n\nFirst idea payoff."},
                    {"posture_label":"PUNCHY","text":"Short hook.\n\nShort payoff."},
                    {"posture_label":"STORY","text":"Story hook.\n\nStory payoff."}
                  ]
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/studio/shot-preview") { ex ->
            ex.sendResponseHeaders(404, 0)
            ex.responseBody.close()
        }
        server.start()
        httpServer = server
        val rigUrl = "http://127.0.0.1:${server.address.port}"
        runBlocking { app.container.profileStore.setTailscaleRigUrl(rigUrl) }

        var draftVmRef: DraftDeckViewModel? = null
        var studioVmRef: StudioViewModel? = null
        var activeScreen = ClipStudioScreen.SPARK
        setContent {
            val draftVm = draftVmRef ?: buildDraftVm(rigUrl).also { draftVmRef = it }
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val draftState by draftVm.uiState.collectAsStateWithLifecycle()
            val studioState by studioVm.uiState.collectAsStateWithLifecycle()
            var screen by remember { mutableStateOf(ClipStudioScreen.SPARK) }
            activeScreen = screen
            AircheckTheme {
                ClipStudioShell(
                    draftState = draftState,
                    draftVm = draftVm,
                    studioState = studioState,
                    studioVm = studioVm,
                    screen = screen,
                    onScreen = { screen = it },
                    onOpenSettings = {}
                )
            }
        }
        val draftVm = checkNotNull(draftVmRef)

        // Seed first idea -> make the video directly -> land on 02 CUT -> tap "+ new" -> returns cleanly to empty 01 SPARK
        draftVm.submitTypedNote("My first rough idea about edge AI.")
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            draftVm.uiState.value.document.arcBeats.size == 4 && !draftVm.uiState.value.isBusy
        }
        // Verify populated 01 SPARK also shows the quick "+ new" button
        onNodeWithTag("cs_spark_new_idea").assertIsDisplayed()

        onNodeWithTag("cs_create_video").performClick()
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            activeScreen == ClipStudioScreen.CUT &&
                onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isNotEmpty()
        }

        // Tap "+ new" right on 02 CUT -> resets draft and navigates directly to empty 01 SPARK (no noisy toast!)
        onNodeWithTag("cs_cut_new_idea").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            activeScreen == ClipStudioScreen.SPARK &&
                draftVm.uiState.value.document.seedTranscript.isBlank() &&
                draftVm.uiState.value.document.arcBeats.isEmpty() &&
                draftVm.uiState.value.document.candidates.isEmpty()
        }
        onNodeWithText("What's the clip about?").assertIsDisplayed()
        assertTrue(onAllNodesWithTag("cs_toast").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun studioSettings_voiceAndHeygenControls_loadLiveOptionsAndPersistEndToEnd() = runComposeUiTest {
        val server = startTestServer()
        val capturedTtsBodies = CopyOnWriteArrayList<String>()
        val capturedRenderBodies = CopyOnWriteArrayList<String>()
        server.createContext("/health") { ex ->
            val resp = """{"status":"ok","render_bridge":true}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/media/providers") { ex ->
            val resp = """
                {
                  "schema_version": 2,
                  "tts_backend": "auto",
                  "video_backend": "heygen",
                  "voice_backends": [
                    {"id":"auto","label":"Auto (Best Available)","available":true,"detail":"Auto"},
                    {"id":"s2pro_v002","label":"S2-Pro v002 LoRA","available":true,"detail":"Fine-tuned"},
                    {"id":"chatterbox_local","label":"Chatterbox Local","available":false,"detail":"Unconfigured"},
                    {"id":"chatterbox","label":"Chatterbox GPU","available":true,"detail":"Zero-shot"},
                    {"id":"heygen_voice","label":"HeyGen Voice","available":true,"detail":"Instant clone"}
                  ],
                  "video_backends": [
                    {"id":"heygen","label":"HeyGen Avatar","available":true,"detail":"2 live look(s)"},
                    {"id":"musetalk","label":"MuseTalk v1.5","available":false,"detail":"Requires CUDA"}
                  ],
                  "heygen": {
                    "available": true,
                    "looks": [
                      {"id":"28bbc1a0f7217675473054ab02d759d9","name":"Ryan anchor v3","avatar_type":"photo_avatar","preferred_orientation":"portrait","supported_api_engines":["avatar_v","avatar_iv","heygen-avatar-6"]},
                      {"id":"8445adef497a4fbabef51263b2d2bcef","name":"Ryan MacArthur -- 1","avatar_type":"digital_twin","preferred_orientation":"landscape","supported_api_engines":["avatar_v","avatar_iv","heygen-avatar-6"]}
                    ],
                    "engines": [
                      {"id":"heygen-avatar-6","label":"Avatar 6","resolutions":["1080p","2k"]},
                      {"id":"avatar_v","label":"Avatar V","resolutions":["720p","1080p"]}
                    ]
                  },
                  "defaults": {
                    "voice_backend":"auto",
                    "video_backend":"heygen",
                    "heygen_look_id":"28bbc1a0f7217675473054ab02d759d9",
                    "heygen_engine":"heygen-avatar-6",
                    "heygen_resolution":"1080p"
                  }
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/core/tts") { ex ->
            capturedTtsBodies.add(ex.requestBody.bufferedReader().readText())
            val resp = """{"job_id":"task_tts_1","status":"QUEUED"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(202, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/jobs/task_tts_1") { ex ->
            val resp = """
                {
                  "job_id":"task_tts_1",
                  "status":"READY",
                  "progress_fraction":1.0,
                  "result":{
                    "tts_id":"tts_test_1",
                    "audio_url":"/v1/renders/tts_test_1.wav",
                    "duration_ms":2000,
                    "waveform_peaks":[0.5,0.8],
                    "clauses":[{"id":"c0","start_ms":0,"end_ms":2000}],
                    "words":[{"word":"Hello","start_ms":0,"end_ms":500}]
                  }
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/render-video") { ex ->
            capturedRenderBodies.add(ex.requestBody.bufferedReader().readText())
            val resp = """{"job_id":"job_hg_1"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(202, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        httpServer = server
        val rigUrl = "http://127.0.0.1:${server.address.port}"
        runBlocking { app.container.profileStore.setTailscaleRigUrl(rigUrl) }

        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val studioState by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme {
                SystemDeckSheet(
                    uiState = studioState,
                    onSelectChannel = studioVm::setBrandChannel,
                    onClearCasCache = {},
                    onClose = {},
                    onSaveTailscaleRigUrl = studioVm::saveTailscaleRigUrl,
                    onTestRigConnection = studioVm::testMacRigConnection,
                    onLoadStudioOptions = studioVm::loadStudioRigOptions,
                    onSelectVoiceBackend = studioVm::setRigVoiceBackend,
                    onSelectVideoBackend = studioVm::setRigVideoBackend,
                    onSelectHeygenLook = studioVm::setHeygenLookId,
                    onSelectHeygenEngine = studioVm::setHeygenEngine,
                    onSelectHeygenResolution = studioVm::setHeygenResolution,
                    onSaveHeygenMotionPrompt = studioVm::setHeygenMotionPrompt
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)

        // Scroll to the Voice & Video providers card and wait for real live options from /v1/media/providers
        onNodeWithTag("system_deck_lazy_list").performScrollToNode(hasTestTag("voice_heygen_card"))
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.studioRigOptions?.heygenLooks?.size == 2
        }

        // Select S2-Pro v002 LoRA voice backend, Digital Twin look, Avatar V engine, and motion prompt
        onNodeWithTag("voice_backend_s2pro_v002").performClick()
        onNodeWithTag("heygen_look_8445adef497a4fbabef51263b2d2bcef").performClick()
        onNodeWithTag("system_deck_lazy_list").performTouchInput { swipeUp() }
        onNodeWithTag("heygen_engine_avatar_v").performClick()
        onNodeWithTag("system_deck_lazy_list").performTouchInput { swipeUp() }
        onNodeWithTag("heygen_motion_prompt_input").performTextReplacement("calm broadcast anchor delivery")
        onNodeWithTag("heygen_motion_prompt_save").performClick()

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val s = studioVm.uiState.value.settings
            s.rigVoiceBackend == "s2pro_v002" &&
                s.heygenLookId == "8445adef497a4fbabef51263b2d2bcef" &&
                s.heygenEngine == "avatar_v" &&
                s.heygenMotionPrompt == "calm broadcast anchor delivery"
        }

        // Verify persisted settings are actually transmitted by MacRigRenderGateway
        runBlocking {
            val saved = app.container.profileStore.current()
            app.container.macRigGateway.synthesizeCoreStoryTts(
                rigBaseUrl = rigUrl,
                projectId = "proj_test",
                clauses = listOf(CoreStoryClause(id = "c0", index = 0, spokenText = "Hello world.")),
                voiceBackend = saved.rigVoiceBackend
            )
            app.container.macRigGateway.enqueueMacRigRender(
                rigBaseUrl = rigUrl,
                project = ScriptProject(
                    id = "proj_test",
                    title = "Test",
                    voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
                    beats = listOf(ScriptBeat(id = "b0", index = 0, spokenText = "Hello world."))
                ),
                photoSha = null,
                videoBackend = saved.rigVideoBackend,
                heygenLookId = saved.heygenLookId,
                heygenEngine = saved.heygenEngine,
                heygenResolution = saved.heygenResolution,
                heygenMotionPrompt = saved.heygenMotionPrompt
            )
        }
        assertTrue(capturedTtsBodies.single().contains("\"voice_backend\":\"s2pro_v002\""))
        val renderBody = capturedRenderBodies.single()
        assertTrue(renderBody.contains("\"video_backend\":\"heygen\""))
        assertTrue(renderBody.contains("\"heygen_look_id\":\"8445adef497a4fbabef51263b2d2bcef\""))
        assertTrue(renderBody.contains("\"heygen_engine\":\"avatar_v\""))
        assertTrue(renderBody.contains("\"heygen_resolution\":\"1080p\""))
        assertTrue(renderBody.contains("\"heygen_motion_prompt\":\"calm broadcast anchor delivery\""))
    }

    @Test
    fun systemDeckSelectsMlxAvatarBackendsAndTransmitsVideoBackendToMacRig() = runComposeUiTest {
        val server = startTestServer()
        val capturedRenderBodies = CopyOnWriteArrayList<String>()
        server.createContext("/health") { ex ->
            val resp = """{"status":"ok","render_bridge":true}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/media/providers") { ex ->
            val resp = """
                {
                  "schema_version": 2,
                  "tts_backend": "auto",
                  "video_backend": "heygen",
                  "voice_backends": [
                    {"id":"auto","label":"Auto (Best Available)","available":true,"detail":"Auto"}
                  ],
                  "video_backends": [
                    {"id":"heygen","label":"HeyGen Avatar","available":true,"detail":"2 live look(s)"},
                    {"id":"avatarforcing_mlx","label":"AvatarForcing MLX","available":true,"detail":"Mac Studio MLX · fp32 flow / fp16 decode · realtime (CC BY-NC)"},
                    {"id":"longcat_avatar_mlx","label":"LongCat 1.6 MLX","available":true,"detail":"Mac Studio MLX · v1.6-bf16 8-step CFG-free (MIT)"},
                    {"id":"musetalk","label":"MuseTalk v1.5","available":false,"detail":"Requires CUDA"}
                  ],
                  "heygen": {"available": true, "looks": [], "engines": []},
                  "defaults": {"voice_backend":"auto","video_backend":"heygen"}
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/render-video") { ex ->
            capturedRenderBodies.add(ex.requestBody.bufferedReader().readText())
            val resp = """{"job_id":"job_mlx_${capturedRenderBodies.size}"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(202, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        httpServer = server
        val rigUrl = "http://127.0.0.1:${server.address.port}"
        runBlocking { app.container.profileStore.setTailscaleRigUrl(rigUrl) }

        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val studioState by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme {
                SystemDeckSheet(
                    uiState = studioState,
                    onSelectChannel = studioVm::setBrandChannel,
                    onClearCasCache = {},
                    onClose = {},
                    onSaveTailscaleRigUrl = studioVm::saveTailscaleRigUrl,
                    onTestRigConnection = studioVm::testMacRigConnection,
                    onLoadStudioOptions = studioVm::loadStudioRigOptions,
                    onSelectVoiceBackend = studioVm::setRigVoiceBackend,
                    onSelectVideoBackend = studioVm::setRigVideoBackend,
                    onSelectHeygenLook = studioVm::setHeygenLookId,
                    onSelectHeygenEngine = studioVm::setHeygenEngine,
                    onSelectHeygenResolution = studioVm::setHeygenResolution,
                    onSaveHeygenMotionPrompt = studioVm::setHeygenMotionPrompt
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)

        onNodeWithTag("system_deck_lazy_list").performScrollToNode(hasTestTag("voice_heygen_card"))
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.studioRigOptions?.videoBackends?.size == 4
        }

        // Select AvatarForcing MLX and verify real backend persistence + live detail
        onNodeWithTag("video_backend_avatarforcing_mlx").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.settings.rigVideoBackend == "avatarforcing_mlx"
        }
        onNodeWithTag("video_backend_active_detail").assertIsDisplayed()

        runBlocking {
            val savedAf = app.container.profileStore.current()
            assertEquals("avatarforcing_mlx", savedAf.rigVideoBackend)
            app.container.macRigGateway.enqueueMacRigRender(
                rigBaseUrl = rigUrl,
                project = ScriptProject(
                    id = "proj_af",
                    title = "Test",
                    voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
                    beats = listOf(ScriptBeat(id = "b0", index = 0, spokenText = "Hello MLX."))
                ),
                photoSha = null,
                videoBackend = savedAf.rigVideoBackend
            )
        }

        // Select LongCat 1.6 MLX, set a scene prompt, and verify persisted settings + payload
        onNodeWithTag("video_backend_longcat_avatar_mlx").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.settings.rigVideoBackend == "longcat_avatar_mlx"
        }
        onNodeWithTag("longcat_scene_prompt_input").performTextReplacement("warm broadcast desk lighting")
        onNodeWithTag("longcat_scene_prompt_save").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.settings.heygenMotionPrompt == "warm broadcast desk lighting"
        }

        runBlocking {
            val savedLc = app.container.profileStore.current()
            assertEquals("longcat_avatar_mlx", savedLc.rigVideoBackend)
            app.container.macRigGateway.enqueueMacRigRender(
                rigBaseUrl = rigUrl,
                project = ScriptProject(
                    id = "proj_lc",
                    title = "Test",
                    voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
                    beats = listOf(ScriptBeat(id = "b0", index = 0, spokenText = "Hello LongCat."))
                ),
                photoSha = null,
                videoBackend = savedLc.rigVideoBackend,
                heygenMotionPrompt = savedLc.heygenMotionPrompt
            )
        }

        assertEquals(2, capturedRenderBodies.size)
        assertTrue(capturedRenderBodies[0].contains("\"video_backend\":\"avatarforcing_mlx\""))
        assertTrue(capturedRenderBodies[1].contains("\"video_backend\":\"longcat_avatar_mlx\""))
        assertTrue(capturedRenderBodies[1].contains("\"heygen_motion_prompt\":\"warm broadcast desk lighting\""))
    }
}
