package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.AircheckApplication
import com.example.agent.OnDevicePromptEngine
import com.example.ai.LlmStageRouter
import com.example.data.BrollSegmentMode
import com.example.data.ClipTransitionType
import com.example.data.CorePipelineSession
import com.example.data.CorePipelineStage
import com.example.data.CoreRenderOptions
import com.example.data.CoreStoryClause
import com.example.data.LlmProviderChoice
import com.example.data.LlmStage
import com.example.data.NewsroomLook
import com.example.data.SilenceGap
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.draft.DraftApiClient
import com.example.ui.clipstudio.BrollStudioScreen
import com.example.ui.clipstudio.ClipStudioScreen
import com.example.ui.clipstudio.ClipStudioShell
import com.example.ui.clipstudio.NewsroomLookScreen
import com.example.ui.draft.DraftDeckViewModel
import com.example.ui.theme.AircheckTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End-to-end automated Compose UI + Room persistence tests for the full Aircheck Studio Overhaul:
 * 1. Non-blocking concurrent beat reroll & direct inline beat editing on `01 SPARK`.
 * 2. Working Anchors: toggling off an anchor, adding a custom anchor, applying -> sends both
 *    `selected_angles` and `excluded_angles` and regenerates beats.
 * 3. Revamped full-view Newsroom Overlays Editor (`Labels` / `Overlays` / `Look` tabs, custom
 *    overlay creation, inline editing, chyron toggle, subline edit, Undo, cutSignature invalidation).
 * 4. Brand-new full-view B-Roll & Transition Studio (default framing/transition, apply-to-all,
 *    per-cut transition + duration picker, B-roll prompt edit, real `/v1/core/broll-preview`
 *    generation persisted to Room, Undo).
 * 5. Overhauled `03 CUT` (Pipeline Progress Strip, stale banner, live `9:16` / `16:9` aspect
 *    frame toggle, `Trim (N)`, `Auto Zoom`, `Overlays`, `B-Roll & Cuts`) + safe Back navigation
 *    history and root Double-Back-to-Exit guard.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w412dp-h915dp-xxhdpi")
class StudioOverhaulE2ETest {

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

    private fun realPngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(48, 84, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.rgb(34, 150, 210))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun spark_nonBlockingBeatReroll_concurrentInlineEdit_andWorkingAnchors() = runComposeUiTest {
        val arcBodies = CopyOnWriteArrayList<String>()
        val reroll0Started = CountDownLatch(1)
        val allowReroll0Finish = CountDownLatch(1)
        val pngBytes = realPngBytes()

        val server = startTestServer()
        server.createContext("/health") { ex ->
            val resp = """{"status":"ok","render_bridge_available":true}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/draft/angles") { ex ->
            val resp = """
                {
                  "extracted_angles": [
                    {"id": "ang_fast", "label": "local speed", "preview": "Zero upload delay on phone."},
                    {"id": "ang_price", "label": "cloud fees", "preview": "Monthly SaaS bills add up."}
                  ],
                  "blindspots": [
                    {"id": "bs_battery", "label": "battery draw", "preview": "Watch power use on long clips."}
                  ]
                }
            """.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/draft/arc") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            arcBodies += body
            val respStr = when {
                body.contains("\"reroll_index\":0") -> {
                    reroll0Started.countDown()
                    allowReroll0Finish.await(6, TimeUnit.SECONDS)
                    """
                        {
                          "target_sec": 60,
                          "word_budget": 150,
                          "beats": [
                            {"label":"HOOK","start_sec":0,"end_sec":8,"text":"Fresh rewritten hook while beat two was edited!","shots":[{"type":"face","prompt":"tight lens"}]},
                            {"label":"DETAIL","start_sec":8,"end_sec":26,"text":"STALE SERVER BEAT TWO THAT MUST NOT OVERWRITE MANUAL EDIT","shots":[{"type":"broll","prompt":"old"}]},
                            {"label":"SHIFT","start_sec":26,"end_sec":45,"text":"Teams still batch on desktops.","shots":[{"type":"broll","prompt":"desk"}]},
                            {"label":"PAYOFF","start_sec":45,"end_sec":60,"text":"Cut where you shoot.","shots":[{"type":"card","prompt":"Cut on device"}]}
                          ]
                        }
                    """.trimIndent()
                }
                body.contains("custom privacy angle") -> {
                    """
                        {
                          "target_sec": 60,
                          "word_budget": 150,
                          "beats": [
                            {"label":"HOOK","start_sec":0,"end_sec":8,"text":"Your footage never leaves the phone with custom privacy angle.","shots":[{"type":"face","prompt":"anchor"}]},
                            {"label":"DETAIL","start_sec":8,"end_sec":26,"text":"Local speed finishes captions before Wi-Fi connects.","shots":[{"type":"pip","prompt":"speed meter"}]},
                            {"label":"SHIFT","start_sec":26,"end_sec":45,"text":"Only archival backups need a server.","shots":[{"type":"broll","prompt":"drive"}]},
                            {"label":"PAYOFF","start_sec":45,"end_sec":60,"text":"Own your camera roll and edits.","shots":[{"type":"card","prompt":"100% private"}]}
                          ]
                        }
                    """.trimIndent()
                }
                else -> {
                    """
                        {
                          "target_sec": 60,
                          "word_budget": 150,
                          "beats": [
                            {"label":"HOOK","start_sec":0,"end_sec":8,"text":"Initial hook sentence.","shots":[{"type":"face","prompt":"anchor"}]},
                            {"label":"DETAIL","start_sec":8,"end_sec":26,"text":"Initial detail sentence.","shots":[{"type":"broll","prompt":"handset"}]},
                            {"label":"SHIFT","start_sec":26,"end_sec":45,"text":"Teams still batch on desktops.","shots":[{"type":"broll","prompt":"desk"}]},
                            {"label":"PAYOFF","start_sec":45,"end_sec":60,"text":"Cut where you shoot.","shots":[{"type":"card","prompt":"Cut on device"}]}
                          ]
                        }
                    """.trimIndent()
                }
            }
            val bytes = respStr.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/studio/shot-preview") { ex ->
            val resp = """{"preview_id":"sp1","preview_url":"/v1/renders/sp1.png","shot_type":"broll"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/renders/") { ex ->
            ex.responseHeaders.add("Content-Type", "image/png")
            ex.sendResponseHeaders(200, pngBytes.size.toLong())
            ex.responseBody.use { it.write(pngBytes) }
        }
        server.start()
        httpServer = server
        val rigUrl = "http://127.0.0.1:${server.address.port}"
        runBlocking { app.container.profileStore.setTailscaleRigUrl(rigUrl) }

        var draftVmRef: DraftDeckViewModel? = null
        setContent {
            val draftVm = draftVmRef ?: buildDraftVm(rigUrl).also { draftVmRef = it }
            val studioVm = remember { StudioViewModel(app) }
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

        // 1. Submit typed note -> generates 4 beats + extracts anchors
        onNodeWithTag("cs_type_instead").performClick()
        onNodeWithTag("cs_voice_note_editor").performTextReplacement("Phone editing is instant and private.")
        onNodeWithTag("cs_typed_note_submit").performClick()
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            draftVm.uiState.value.document.arcBeats.size == 4 &&
                draftVm.uiState.value.document.extractedAngles.size == 2 &&
                !draftVm.uiState.value.isBusy
        }

        // 2. Start rerolling beat 0; while beat 0 is HELD IN FLIGHT, edit beat 1 directly!
        onNodeWithTag("cs_reroll_beat_0").performClick()
        waitUntil(timeoutMillis = 6_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            reroll0Started.count == 0L
        }
        assertTrue(0 in draftVm.uiState.value.rerollingBeatIndices)
        assertFalse("Rerolling beat 0 must NOT globally block UI", draftVm.uiState.value.isBusy)

        onNodeWithTag("cs_beat_text_1").performScrollTo().performClick()
        onNodeWithTag("cs_beat_editor_1").performScrollTo().performTextReplacement("Ryan hand-edited beat two live.")
        onNodeWithTag("cs_beat_save_1").performScrollTo().performClick()
        assertEquals("Ryan hand-edited beat two live.", draftVm.uiState.value.document.arcBeats[1].text)

        // Release beat 0 reroll -> verify beat 0 updated AND beat 1 manual edit was NOT overwritten!
        allowReroll0Finish.countDown()
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            draftVm.uiState.value.rerollingBeatIndices.isEmpty() &&
                draftVm.uiState.value.document.arcBeats[0].text.startsWith("Fresh rewritten hook")
        }
        assertEquals("Ryan hand-edited beat two live.", draftVm.uiState.value.document.arcBeats[1].text)

        // 3. Open Anchors sheet, turn OFF "cloud fees", add custom anchor, tap "apply ... & update beats"
        onNodeWithTag("cs_anchors_button").performClick()
        onNodeWithTag("cs_anchors_sheet").assertIsDisplayed()
        onNodeWithTag("cs_keep_ang_price").performClick()
        onNodeWithTag("cs_anchor_custom_input").performTextReplacement("custom privacy angle")
        onNodeWithTag("cs_anchor_custom_add").performClick()
        onNodeWithTag("cs_anchors_done").performClick()

        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            draftVm.uiState.value.document.arcBeats[0].text.contains("custom privacy angle")
        }
        val anchorArcPayload = arcBodies.last()
        assertTrue("Expected custom anchor in selected_angles: $anchorArcPayload", anchorArcPayload.contains("custom privacy angle"))
        assertTrue("Expected turned-off anchor in excluded_angles: $anchorArcPayload", anchorArcPayload.contains("excluded_angles") && anchorArcPayload.contains("cloud fees"))
    }

    @Test
    fun newsroomOverlaysEditor_createEditToggleChyronUndo_andInvalidatesCutSignature() = runComposeUiTest {
        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val st by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme {
                NewsroomLookScreen(
                    state = st,
                    vm = studioVm,
                    onBack = {},
                    onTestOnClip = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)
        studioVm.openStoryInCorePipeline("First sentence.\n\nSecond sentence.", headline = "INITIAL CHYRON")
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.clauses.isNotEmpty()
        }

        // Toggle chyron OFF and back ON, and edit subline on Tab 0 (Labels)
        onNodeWithTag("cs_chyron_toggle").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !studioVm.uiState.value.corePipelineSession.renderOptions.showLowerThird
        }
        onNodeWithTag("cs_chyron_toggle").performScrollTo().performClick()
        onNodeWithTag("cs_subline_input").performScrollTo().performTextReplacement("LIVE FROM AUSTIN")
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.renderOptions.chyronSubline == "LIVE FROM AUSTIN"
        }

        // Switch to Tab 1 (`Overlays`), create a brand-new custom stat overlay
        onNodeWithTag("cs_newsroom_tab_1").performClick()
        onNodeWithTag("cs_new_overlay_toggle").performScrollTo().performClick()
        onNodeWithTag("cs_new_ovl_headline").performScrollTo().performTextReplacement("Zero monthly fees")
        onNodeWithTag("cs_new_ovl_subline").performScrollTo().performTextReplacement("Local phone studio")
        onNodeWithTag("cs_new_ovl_stat").performScrollTo().performTextReplacement("\$0/MO")
        onNodeWithTag("cs_new_ovl_create").performScrollTo().performClick()

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.savedOverlays.any { it.headline == "Zero monthly fees" }
        }
        val created = studioVm.uiState.value.savedOverlays.first { it.headline == "Zero monthly fees" }
        assertTrue(created.id in studioVm.uiState.value.corePipelineSession.selectedOverlayIds)

        // Verify cutSignature changes when overlay content is edited inline!
        val defaultLook = studioVm.uiState.value.newsroomLooks.firstOrNull { it.isDefault } ?: NewsroomLook(name = "LOOK_01")
        val sigBefore = studioVm.uiState.value.corePipelineSession.cutSignature(defaultLook, studioVm.uiState.value.savedOverlays)
        studioVm.updateSavedOverlayInline(created.id, headline = "Zero monthly cloud fees")
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.savedOverlays.first { it.id == created.id }.headline == "Zero monthly cloud fees"
        }
        val sigAfter = studioVm.uiState.value.corePipelineSession.cutSignature(defaultLook, studioVm.uiState.value.savedOverlays)
        assertNotEquals("Editing overlay text MUST invalidate cutSignature", sigBefore, sigAfter)
    }

    @Test
    fun brollStudio_transitionsPromptPreviewGenerationAndUndo_persistToRoom() = runComposeUiTest {
        val pngBytes = realPngBytes()
        val previewPrompts = CopyOnWriteArrayList<String>()
        val server = startTestServer()
        server.createContext("/health") { ex ->
            val resp = """{"status":"ok","render_bridge_available":true}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/core/broll-preview") { ex ->
            previewPrompts += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val resp = """{"media_id":"bmedia_gen001.png","url":"/v1/broll-media/bmedia_gen001.png","ext":"png","is_video":false}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/broll-media/") { ex ->
            ex.responseHeaders.add("Content-Type", "image/png")
            ex.sendResponseHeaders(200, pngBytes.size.toLong())
            ex.responseBody.use { it.write(pngBytes) }
        }
        server.start()
        httpServer = server
        val rigUrl = "http://127.0.0.1:${server.address.port}"
        runBlocking {
            app.container.profileStore.setTailscaleRigUrl(rigUrl)
            app.container.localRepo.saveCorePipelineSession(
                CorePipelineSession(
                    id = "pipe_broll",
                    scriptId = "scr_broll",
                    stage = CorePipelineStage.CHOP_INTO_BROLL,
                    clauses = listOf(
                        CoreStoryClause(id = "c0", index = 0, spokenText = "Opening talking head.", startMs = 0, endMs = 2000),
                        CoreStoryClause(
                            id = "c1",
                            index = 1,
                            spokenText = "B-roll cutaway line.",
                            startMs = 2000,
                            endMs = 4500,
                            segmentMode = BrollSegmentMode.BROLL_CUTAWAY,
                            bRollPrompt = "Desk macro"
                        )
                    ),
                    talkingHeadJobId = "head_1",
                    talkingHeadVideoUrl = "$rigUrl/v1/renders/head.mp4"
                )
            )
        }

        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val st by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme {
                BrollStudioScreen(
                    state = st,
                    vm = studioVm,
                    onBack = {},
                    onRenderStarted = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.clauses.size == 2
        }

        // 1. Default framing + default transition + apply to all
        onNodeWithTag("cs_broll_framing_split").performClick()
        onNodeWithTag("cs_default_trans_wipeleft").performScrollTo().performClick()
        onNodeWithTag("cs_apply_all_transitions").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.clauses[1].transitionType == ClipTransitionType.WIPE_LEFT.wire
        }

        // 2. Per-cut transition selector + speed change
        onNodeWithTag("cs_clause_trans_chip_1").performScrollTo().performClick()
        onNodeWithTag("cs_clause_trans_opt_1_slideup").performScrollTo().performClick()
        onNodeWithTag("cs_clause_trans_dur_1_600").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val c1 = studioVm.uiState.value.corePipelineSession.clauses[1]
            c1.transitionType == ClipTransitionType.SLIDE_UP.wire && c1.transitionDurationMs == 600
        }

        // 3. Edit B-roll prompt & generate real B-roll still preview from rig
        onNodeWithTag("cs_broll_prompt_1").performScrollTo().performTextReplacement("Close-up of studio audio console")
        onNodeWithTag("cs_broll_generate_1").performScrollTo().performClick()
        waitUntil(timeoutMillis = 8_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.clauses[1].brollMediaId == "bmedia_gen001.png"
        }
        assertTrue(previewPrompts.any { it.contains("Close-up of studio audio console") })

        // 4. Undo restores prior state with descriptive toast
        onNodeWithTag("cs_broll_studio_undo").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.toastMessage?.startsWith("Undid:") == true
        }
    }

    @Test
    fun cutScreen_pipelineStripDirtyBannerLiveAspectFrame_andDoubleBackGuard() = runComposeUiTest {
        runBlocking {
            app.container.localRepo.saveCorePipelineSession(
                CorePipelineSession(
                    id = "pipe_cut_ux",
                    scriptId = "scr_cut_ux",
                    stage = CorePipelineStage.FINAL_REVIEW,
                    clauses = listOf(
                        CoreStoryClause(id = "c0", index = 0, spokenText = "First broadcast line.", startMs = 0, endMs = 2000),
                        CoreStoryClause(id = "c1", index = 1, spokenText = "Second cutaway line.", startMs = 2000, endMs = 4000, segmentMode = BrollSegmentMode.BROLL_CUTAWAY)
                    ),
                    voicedClauseTexts = listOf("First broadcast line.", "Second cutaway line."),
                    ttsAudioUrl = "http://127.0.0.1:9/voice.wav",
                    talkingHeadJobId = "head_cut",
                    talkingHeadVideoUrl = "http://127.0.0.1:9/head.mp4",
                    finalVideoUrl = "http://127.0.0.1:9/final.mp4",
                    lastCutSignature = "stale_sig_from_older_render",
                    silenceGapsJobId = "head_cut",
                    silenceGaps = listOf(SilenceGap(500, 900), SilenceGap(2100, 2600)),
                    renderOptions = CoreRenderOptions(aspect = CoreRenderOptions.ASPECT_VERTICAL)
                )
            )
        }

        var studioVmRef: StudioViewModel? = null
        var draftVmRef: DraftDeckViewModel? = null
        var activeScreen by mutableStateOf(ClipStudioScreen.CUT)
        setContent {
            val draftVm = draftVmRef ?: buildDraftVm("http://127.0.0.1:9").also { draftVmRef = it }
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val draftState by draftVm.uiState.collectAsStateWithLifecycle()
            val studioState by studioVm.uiState.collectAsStateWithLifecycle()
            AircheckTheme {
                ClipStudioShell(
                    draftState = draftState,
                    draftVm = draftVm,
                    studioState = studioState,
                    studioVm = studioVm,
                    screen = activeScreen,
                    onScreen = { activeScreen = it },
                    onOpenSettings = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.corePipelineSession.clauses.size == 2
        }

        // Pipeline progress strip + explicit stale re-render banner visible
        onNodeWithTag("cs_cut_pipeline_strip").assertIsDisplayed()
        onNodeWithTag("cs_cut_dirty_banner").assertIsDisplayed()
        onNodeWithText("Trim (2)").assertIsDisplayed()
        onNodeWithText("Auto Zoom").assertIsDisplayed()

        // Toggling aspect 9:16 -> 16:9 immediately reshapes the monitor viewport frame!
        onNodeWithTag("cs_cut_frame_vertical", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithTag("cs_cut_aspect").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithTag("cs_cut_frame_horizontal", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("cs_cut_frame_horizontal", useUnmergedTree = true).assertIsDisplayed()

        // Studio entry buttons navigate cleanly into Overlays and B-Roll studios
        onNodeWithTag("cs_cut_broll_studio").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            activeScreen == ClipStudioScreen.BROLL_STUDIO
        }
        onNodeWithTag("cs_broll_studio_back").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            activeScreen == ClipStudioScreen.CUT
        }
    }

    @Test
    fun settings_selectingHeygenLook_clearsStaleCustomPhotoAndUpdatesImmediately() = runComposeUiTest {
        runBlocking {
            app.container.profileStore.setDefaultPhotoSha("stale_custom_photo_sha_12345")
            app.container.profileStore.setHeygenLookId("old_look_id")
        }
        val studioVm = StudioViewModel(app)
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.settings.persona.defaultPhotoSha == "stale_custom_photo_sha_12345"
        }

        // Selecting a HeyGen Look must update settings immediately AND clear stale defaultPhotoSha
        // so HeyGen actually renders the chosen avatar look instead of falling back to custom photo!
        studioVm.setHeygenLookId("3659b8a71a9142e18a6ae193d3e8f3c6")
        assertEquals("3659b8a71a9142e18a6ae193d3e8f3c6", studioVm.uiState.value.settings.heygenLookId)
        assertEquals("", studioVm.uiState.value.settings.persona.defaultPhotoSha)
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val persisted = runBlocking { app.container.profileStore.current() }
            persisted.heygenLookId == "3659b8a71a9142e18a6ae193d3e8f3c6" &&
                persisted.persona.defaultPhotoSha.isEmpty()
        }
    }
}
