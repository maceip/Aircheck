package com.example.draft

import android.os.Looper
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.AircheckApplication
import com.example.agent.OnDevicePromptEngine
import com.example.ai.LlmStageRouter
import com.example.data.BreakingNewsStyle
import com.example.data.BrollSegmentMode
import com.example.data.ClipRecord
import com.example.data.CorePipelineSession
import com.example.data.CorePipelineStage
import com.example.data.CoreStoryClause
import com.example.data.LlmProviderChoice
import com.example.data.LlmStage
import com.example.data.NewsroomLook
import com.example.data.OverlayObjectKind
import com.example.data.RenderJobStatus
import com.example.data.gateway.DirectMacRigGateway
import com.example.data.local.AircheckDatabase
import com.example.data.local.AircheckLocalRepository
import com.example.data.local.AircheckProfileSerializer
import com.example.data.local.AircheckProfileStore
import com.example.data.local.CasMediaStore
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.engine.CoreStoryPipelineCoordinator
import com.example.graph.GraphContactRecord
import com.example.graph.GraphPostRecord
import com.example.graph.LocalGraphDatabase
import com.example.ui.StudioViewModel
import com.example.ui.draft.DraftDeckViewModel
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ClipStudioV5ViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var app: AircheckApplication
    private lateinit var db: AircheckDatabase
    private lateinit var localRepo: AircheckLocalRepository
    private lateinit var casStore: CasMediaStore
    private lateinit var graphDb: LocalGraphDatabase
    private lateinit var profileScope: CoroutineScope
    private lateinit var profileStore: AircheckProfileStore
    private lateinit var okHttp: OkHttpClient
    private var server: HttpServer? = null
    private var serverExecutor: ExecutorService? = null

    private val realJpegBytes: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0x01, 0x01, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00,
        0xFF.toByte(), 0xD9.toByte()
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            androidx.work.Configuration.Builder()
                .setExecutor(androidx.work.testing.SynchronousExecutor())
                .setTaskExecutor(androidx.work.testing.SynchronousExecutor())
                .build()
        )
        db = Room.inMemoryDatabaseBuilder(app, AircheckDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localRepo = AircheckLocalRepository(db)
        casStore = CasMediaStore(app, db.casDao())
        graphDb = LocalGraphDatabase(app, databaseName = null)
        profileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pbFile = tempFolder.newFile("v5_profile_${System.nanoTime()}.pb").also { it.delete() }
        profileStore = AircheckProfileStore(
            DataStoreFactory.create(
                serializer = AircheckProfileSerializer,
                scope = profileScope,
                produceFile = { pbFile }
            )
        )
        runBlocking {
            profileStore.setPhoneOnlyVoiceMode(true)
            profileStore.setLlmStageConfig(LlmStage.DRAFTING, LlmProviderChoice.MAC_RIG_DEFAULT)
            profileStore.setLlmStageConfig(LlmStage.SCRIPT_EDIT, LlmProviderChoice.MAC_RIG_DEFAULT)
            profileStore.setLlmStageConfig(LlmStage.NEWSROOM_OVERLAY, LlmProviderChoice.MAC_RIG_DEFAULT)
        }
        okHttp = OkHttpClient()
    }

    @After
    fun tearDown() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        server?.stop(0)
        server = null
        serverExecutor?.shutdownNow()
        serverExecutor = null
        graphDb.close()
        db.close()
        profileScope.cancel()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun newHttpServer(): HttpServer {
        val exec = Executors.newCachedThreadPool()
        serverExecutor = exec
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = exec
        }
    }

    private fun buildDraftVm(baseUrl: String): DraftDeckViewModel {
        runBlocking { profileStore.setTailscaleRigUrl(baseUrl) }
        val clientFactory: (String) -> DraftApiClient = { url ->
            val target = if (url == DEFAULT_MAC_RIG_LAN_URL) baseUrl else url
            DraftApiClient(
                configuredRigUrl = target,
                usbLoopbackUrl = target,
                okHttpClient = okHttp,
                healthProbeTimeoutMs = 500L
            )
        }
        val router = LlmStageRouter(
            profileStore = profileStore,
            draftApiClientProvider = clientFactory,
            onDeviceEngine = OnDevicePromptEngine(),
            okHttpClient = okHttp
        )
        return DraftDeckViewModel(
            application = app,
            llmRouterOverride = router,
            draftApiClientFactory = clientFactory,
            profileStoreOverride = profileStore,
            casStoreOverride = casStore
        )
    }

    private fun buildStudioVm(baseUrl: String = DEFAULT_MAC_RIG_LAN_URL): StudioViewModel {
        runBlocking { profileStore.setTailscaleRigUrl(baseUrl) }
        return StudioViewModel(
            application = app,
            localRepoOverride = localRepo,
            profileStoreOverride = profileStore,
            casStoreOverride = casStore,
            macRigGatewayOverride = DirectMacRigGateway(casStore, okHttp),
            localGraphDatabaseOverride = graphDb
        )
    }

    @Test
    fun tonePropagation_persistsAcrossEdits_andSendsWireToneInAllDraftPasses() = runTest {
        val capturedByPath = CopyOnWriteArrayList<Pair<String, String>>()
        val srv = newHttpServer()
        srv.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        srv.createContext("/v1/draft/angles") { ex ->
            capturedByPath += "/v1/draft/angles" to ex.requestBody.bufferedReader().readText()
            respondJson(
                ex,
                200,
                """{"extracted_angles":[{"id":"ex_1","label":"offline voice","preview":"Runs locally on NPU."}],"blindspots":[{"id":"bs_1","label":"battery cost","preview":"4% per render."}]}"""
            )
        }
        srv.createContext("/v1/draft/arc") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            capturedByPath += "/v1/draft/arc" to body
            respondJson(
                ex,
                200,
                """{
                  "target_sec": 60,
                  "word_budget": 150,
                  "beats": [
                    {"label":"HOOK","start_sec":0,"end_sec":8,"text":"Subscriptions are over.","shots":[{"type":"face","prompt":"Direct camera"}]},
                    {"label":"DETAIL","start_sec":8,"end_sec":28,"text":"On-device AI edits locally.","shots":[{"type":"pip","prompt":"Phone NPU benchmark"}]},
                    {"label":"SHIFT","start_sec":28,"end_sec":48,"text":"Cloud only wins on massive batches.","shots":[{"type":"broll","prompt":"Server rack"}]},
                    {"label":"PAYOFF","start_sec":48,"end_sec":60,"text":"Buy the chip once.","shots":[{"type":"card","prompt":"$0/month"}]}
                  ]
                }"""
            )
        }
        srv.createContext("/v1/draft/synthesize-script") { ex ->
            capturedByPath += "/v1/draft/synthesize-script" to ex.requestBody.bufferedReader().readText()
            respondJson(
                ex,
                200,
                """{
                  "target_sec": 60,
                  "word_budget": 150,
                  "candidates": [
                    {"posture_label":"DIRECT","text":"Your phone just made a monthly subscription pointless.\n\nVoice cloning and cuts run offline on the chip."},
                    {"posture_label":"PUNCHY","text":"Cancel the cloud bill today.\n\nEverything runs on your phone."},
                    {"posture_label":"STORY","text":"Last week on a flight I cut an entire video offline.\n\nZero signal needed."}
                  ]
                }"""
            )
        }
        srv.createContext("/v1/draft/brush-swap") { ex ->
            capturedByPath += "/v1/draft/brush-swap" to ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"alternatives":["cloud editing tax","recurring SaaS fee",""]}""")
        }
        srv.createContext("/v1/studio/shot-preview") { ex ->
            respondJson(ex, 200, """{"preview_id":"shotprev_1","preview_url":"/v1/renders/shotprev_1.jpg","shot_type":"face"}""")
        }
        srv.createContext("/v1/renders/shotprev_1.jpg") { ex -> respondBytes(ex, 200, "image/jpeg", realJpegBytes) }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"

        val vm = buildDraftVm(baseUrl)
        assertEquals(DraftTone.HOT_TAKE, vm.uiState.value.tone)
        assertEquals("hot_take", vm.uiState.value.tone.wireValue)

        vm.setTone(DraftTone.SOLARPUNK)
        assertEquals(DraftTone.SOLARPUNK, vm.uiState.value.document.tone)

        vm.submitTypedNote("Local on-device AI replaces cloud subscriptions.")
        waitFor {
            !vm.uiState.value.isBusy &&
                !vm.uiState.value.isExtractingAngles &&
                vm.uiState.value.document.arcBeats.size == 4
        }

        val anglesJson = Json.parseToJsonElement(capturedByPath.first { it.first == "/v1/draft/angles" }.second).jsonObject
        val arcJson = Json.parseToJsonElement(capturedByPath.first { it.first == "/v1/draft/arc" }.second).jsonObject
        assertEquals("solarpunk", anglesJson["tone"]?.jsonPrimitive?.content)
        assertEquals("solarpunk", arcJson["tone"]?.jsonPrimitive?.content)

        // Switch to SHITPOST and verify reroll, script synthesis, single-tap swap, and nudge all carry "shitpost"
        vm.setTone(DraftTone.SHITPOST)
        vm.rerollArcBeat(1)
        waitFor { !vm.uiState.value.isBusy && vm.uiState.value.document.lastRerolledBeat == 1 }

        val rerollJson = Json.parseToJsonElement(capturedByPath.last { it.first == "/v1/draft/arc" }.second).jsonObject
        assertEquals("shitpost", rerollJson["tone"]?.jsonPrimitive?.content)
        assertEquals(1, rerollJson["reroll_index"]?.jsonPrimitive?.int)
        assertNotNull("existing_beat_shots should be sent on reroll", rerollJson["existing_beat_shots"])

        vm.writeVideoScript()
        waitFor { !vm.uiState.value.isBusy && vm.uiState.value.document.candidates.size == 3 }
        val scriptJson = Json.parseToJsonElement(capturedByPath.last { it.first == "/v1/draft/synthesize-script" }.second).jsonObject
        assertEquals("shitpost", scriptJson["tone"]?.jsonPrimitive?.content)
        assertEquals(DraftTone.SHITPOST, vm.uiState.value.document.tone)

        val scriptText = vm.uiState.value.document.activeCandidate!!.plainText()
        vm.selectPhraseSingleTap(scriptText.indexOf("monthly"))
        waitFor { !vm.uiState.value.isBusy && vm.uiState.value.brushAlternatives.isNotEmpty() }
        val swapJson = Json.parseToJsonElement(capturedByPath.last { it.first == "/v1/draft/brush-swap" }.second).jsonObject
        assertEquals("shitpost", swapJson["tone"]?.jsonPrimitive?.content)

        vm.performBrushNudge(BrushNudge.SHARPER)
        waitFor { !vm.uiState.value.isBusy && vm.uiState.value.activeBrushNudge == BrushNudge.SHARPER }
        val nudgeJson = Json.parseToJsonElement(capturedByPath.last { it.first == "/v1/draft/brush-swap" }.second).jsonObject
        assertEquals("shitpost", nudgeJson["tone"]?.jsonPrimitive?.content)
        assertEquals("sharper", nudgeJson["nudge"]?.jsonPrimitive?.content)
    }

    @Test
    fun shotPlan_defaultsWhenOmitted_cyclesAndAddsBrollWithUndo_andDownloadsRealRigPreviews() = runTest {
        val previewRequests = CopyOnWriteArrayList<String>()
        var includeExplicitShots = false
        val srv = newHttpServer()
        srv.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        srv.createContext("/v1/draft/arc") { ex ->
            ex.requestBody.bufferedReader().readText()
            if (!includeExplicitShots) {
                respondJson(
                    ex,
                    200,
                    """{
                      "target_sec": 60,
                      "word_budget": 150,
                      "beats": [
                        {"label":"HOOK","start_sec":0,"end_sec":8,"text":"Beat one."},
                        {"label":"DETAIL","start_sec":8,"end_sec":28,"text":"Beat two."},
                        {"label":"SHIFT","start_sec":28,"end_sec":48,"text":"Beat three."},
                        {"label":"PAYOFF","start_sec":48,"end_sec":60,"text":"Beat four."}
                      ]
                    }"""
                )
            } else {
                respondJson(
                    ex,
                    200,
                    """{
                      "target_sec": 60,
                      "word_budget": 150,
                      "beats": [
                        {"label":"HOOK","window":"0-8s","text":"Hook text.","shots":[{"type":"face","prompt":"Anchor closeup"}]},
                        {"label":"DETAIL","window":"8-28s","text":"Detail text.","shots":[{"type":"broll","prompt":"Silicon wafer"}]},
                        {"label":"SHIFT","window":"28-48s","text":"Shift text.","shots":[{"type":"pip","prompt":"Latency chart"}]},
                        {"label":"PAYOFF","window":"48-60s","text":"Payoff text.","shots":[{"type":"card","prompt":"Own the chip"}]}
                      ]
                    }"""
                )
            }
        }
        srv.createContext("/v1/studio/shot-preview") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            previewRequests += body
            val idx = previewRequests.size
            respondJson(
                ex,
                200,
                """{"preview_id":"shotprev_$idx","preview_url":"/v1/renders/shotprev_$idx.jpg","shot_type":"broll"}"""
            )
        }
        srv.createContext("/v1/renders/") { ex ->
            respondBytes(ex, 200, "image/jpeg", realJpegBytes)
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"

        val vm = buildDraftVm(baseUrl)
        vm.updateSeedTranscriptManual("Testing default v5 beat shot initialization.")
        vm.generateArc()
        waitFor { !vm.uiState.value.isBusy && vm.uiState.value.document.arcBeats.size == 4 }

        // 1. Verify default v5 shot plan when mock omits shots: [FACE, PIP], [PIP, BROLL, BROLL], [BROLL, PIP], [FACE, CARD]
        assertEquals(
            listOf(
                listOf(ShotType.FACE, ShotType.PIP),
                listOf(ShotType.PIP, ShotType.BROLL, ShotType.BROLL),
                listOf(ShotType.BROLL, ShotType.PIP),
                listOf(ShotType.FACE, ShotType.CARD)
            ),
            vm.uiState.value.scenes
        )

        // Wait for all 9 initial shot previews to download into CAS
        val initialShotIds = vm.uiState.value.document.arcBeats.flatMap { it.shots }.map { it.id }
        assertEquals(9, initialShotIds.size)
        waitFor {
            initialShotIds.all { id -> vm.uiState.value.shotPreviews[id]?.let { File(it).isFile } == true }
        }

        // 2. Verify explicit shots + window ("0-8s") parsing
        includeExplicitShots = true
        vm.generateArc()
        waitFor {
            !vm.uiState.value.isBusy &&
                vm.uiState.value.document.arcBeats.first().text == "Hook text."
        }
        assertEquals(0, vm.uiState.value.document.arcBeats[0].startSec)
        assertEquals(8, vm.uiState.value.document.arcBeats[0].endSec)
        assertEquals("Silicon wafer", vm.uiState.value.document.arcBeats[1].shots.single().prompt)

        // 3. Cycle shot 0 of beat 0 through FACE -> PIP -> BROLL -> CARD -> FACE
        assertEquals(ShotType.FACE, vm.uiState.value.document.arcBeats[0].shots[0].type)
        vm.cycleBeatShot(0, 0)
        assertEquals(ShotType.PIP, vm.uiState.value.document.arcBeats[0].shots[0].type)
        vm.cycleBeatShot(0, 0)
        assertEquals(ShotType.BROLL, vm.uiState.value.document.arcBeats[0].shots[0].type)
        vm.cycleBeatShot(0, 0)
        assertEquals(ShotType.CARD, vm.uiState.value.document.arcBeats[0].shots[0].type)
        vm.cycleBeatShot(0, 0)
        assertEquals(ShotType.FACE, vm.uiState.value.document.arcBeats[0].shots[0].type)

        // 4. Add B-roll shot to beat 2 and verify undo restores prior shot list
        assertEquals(1, vm.uiState.value.document.arcBeats[2].shots.size)
        vm.addBeatBrollShot(2, prompt = "Close-up thermal camera")
        assertEquals(2, vm.uiState.value.document.arcBeats[2].shots.size)
        val addedShot = vm.uiState.value.document.arcBeats[2].shots.last()
        assertEquals(ShotType.BROLL, addedShot.type)
        assertEquals("Close-up thermal camera", addedShot.prompt)
        waitFor { vm.uiState.value.shotPreviews[addedShot.id]?.let { File(it).length() > 0L } == true }

        assertTrue(vm.uiState.value.canUndo)
        vm.stepGlobalSnapshot(-1)
        assertEquals(1, vm.uiState.value.document.arcBeats[2].shots.size)
        assertTrue(
            "Expected descriptive undo toast, got ${vm.uiState.value.toastMessage}",
            vm.uiState.value.toastMessage?.startsWith("Undid:") == true
        )
    }

    @Test
    fun lastCompletedVideo_isStrictlyNullOnEmptyOrNonReadyDb_andResolvesReadyClipPlusGraphReply() = runTest {
        val studioVm = buildStudioVm()
        waitFor { studioVm.uiState.value.projects.isNotEmpty() }

        // Constitutional Law 2: empty Room DB -> strictly null!
        assertNull("lastCompletedVideo must be null when Room has no clips", studioVm.uiState.value.lastCompletedVideo)

        // Non-READY (QUEUED / FAILED) clip -> still strictly null!
        localRepo.upsertClip(
            sampleClip(
                id = "clip_queued",
                status = RenderJobStatus.QUEUED,
                title = "Queued render"
            )
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull("lastCompletedVideo must remain null when no READY clip exists", studioVm.uiState.value.lastCompletedVideo)

        // Insert READY clip + CorePipelineSession transcript (still zero replies in LocalGraphDatabase)
        val session = CorePipelineSession(
            id = "pipe_ready_1",
            scriptId = "proj_ready_1",
            title = "Your phone just killed a subscription",
            stage = CorePipelineStage.FINAL_REVIEW,
            clauses = listOf(
                CoreStoryClause(index = 0, spokenText = "Your phone just made a $20 subscription pointless."),
                CoreStoryClause(index = 1, spokenText = "Voice cloning and captions run offline.")
            ),
            finalClipId = "clip_ready_1"
        )
        localRepo.saveCorePipelineSession(session)
        localRepo.upsertClip(
            sampleClip(
                id = "clip_ready_1",
                scriptId = "proj_ready_1",
                status = RenderJobStatus.READY,
                title = "Your phone just killed a subscription",
                durationMs = 58_000L
            )
        )

        waitFor { studioVm.uiState.value.lastCompletedVideo?.clipId == "clip_ready_1" }
        val withoutReplies = studioVm.uiState.value.lastCompletedVideo!!
        assertEquals("Your phone just killed a subscription", withoutReplies.title)
        assertEquals(58_000L, withoutReplies.durationMs)
        assertTrue(withoutReplies.transcript.contains("Voice cloning and captions run offline."))
        assertNull("topReplyQuestion must be null when LocalGraphDatabase has no replies", withoutReplies.topReplyQuestion)

        // Import real reply into LocalGraphDatabase -> topReplyQuestion resolves without @handle prefix
        graphDb.upsertContactsAndPosts(
            contacts = listOf(
                GraphContactRecord(
                    handle = "viewer_1",
                    displayName = "Viewer",
                    bio = "Mobile AI",
                    followerCount = 400,
                    mutualRepliesCount = 2,
                    lastInteractionEpochMs = 1_700_000_000_000L
                )
            ),
            posts = listOf(
                GraphPostRecord(
                    tweetId = "tw_reply_1",
                    handle = "viewer_1",
                    text = "@creator what about battery life when offline?",
                    createdAtEpochMs = 1_700_000_100_000L,
                    isReplyToUser = true
                )
            )
        )
        studioVm.refreshLastCompletedVideo()
        waitFor { studioVm.uiState.value.lastCompletedVideo?.topReplyQuestion != null }
        assertEquals(
            "what about battery life when offline?",
            studioVm.uiState.value.lastCompletedVideo?.topReplyQuestion
        )
    }

    @Test
    fun followupFlow_overviewAnglesDirections_andLoadIntoSpark_populatesBeatsAndPreviews() = runTest {
        val followupBodies = CopyOnWriteArrayList<String>()
        val srv = newHttpServer()
        srv.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        srv.createContext("/v1/draft/followup") { ex ->
            val raw = ex.requestBody.bufferedReader().readText()
            followupBodies += raw
            val angle = Json.parseToJsonElement(raw).jsonObject["angle"]?.jsonPrimitive?.content ?: "overview"
            when (angle) {
                "overview" -> respondJson(
                    ex,
                    200,
                    """{
                      "angle": "overview",
                      "target_sec": 60,
                      "word_budget": 150,
                      "answer_beats": [
                        {"label":"HOOK","window":"0-8s","text":"You asked about battery life.","shots":[{"type":"face","prompt":"Face cam"}]},
                        {"label":"TEST","window":"8-28s","text":"I ran three offline cuts back to back.","shots":[{"type":"pip","prompt":"Battery stats"}]},
                        {"label":"RESULT","window":"28-48s","text":"Offline used 4% vs 6% uploading.","shots":[{"type":"broll","prompt":"Battery graph"}]},
                        {"label":"PAYOFF","window":"48-60s","text":"Battery is fine; cancel the fee.","shots":[{"type":"card","prompt":"4% vs 6%"}]}
                      ],
                      "directions": [
                        {
                          "id": "shitpost",
                          "theme": "SHITPOST",
                          "words": "calling it, subscriptions dead",
                          "beats": [
                            {"label":"HOOK","window":"0-8s","text":"Calling it: subscriptions are dead.","shots":[{"type":"face","prompt":"Deadpan"}]},
                            {"label":"BIT","window":"8-28s","text":"My phone edits on airplane mode.","shots":[{"type":"broll","prompt":"Airplane window"}]},
                            {"label":"ESCALATE","window":"28-46s","text":"Cloud rent seekers in shambles.","shots":[{"type":"pip","prompt":"Chart"}]},
                            {"label":"PAYOFF","window":"46-60s","text":"Keep your twenty bucks.","shots":[{"type":"card","prompt":"$20 saved"}]}
                          ]
                        },
                        {
                          "id": "solarpunk",
                          "theme": "SOLARPUNK",
                          "words": "plot twist: subs good",
                          "beats": [
                            {"label":"HOOK","window":"0-8s","text":"Plot twist: shared compute can be green.","shots":[{"type":"face","prompt":"Warm studio"}]},
                            {"label":"DETAIL","window":"8-28s","text":"Community servers pool solar surplus.","shots":[{"type":"broll","prompt":"Solar microgrid"}]},
                            {"label":"SHIFT","window":"28-46s","text":"Ownership matters more than hardware location.","shots":[{"type":"pip","prompt":"Co-op node"}]},
                            {"label":"PAYOFF","window":"46-60s","text":"Fund co-ops, not landlords.","shots":[{"type":"card","prompt":"Co-op compute"}]}
                          ]
                        }
                      ]
                    }"""
                )
                else -> respondJson(
                    ex,
                    200,
                    """{
                      "angle": "$angle",
                      "target_sec": 60,
                      "word_budget": 150,
                      "beats": [
                        {"label":"HOOK","window":"0-8s","text":"Angle $angle hook.","shots":[{"type":"face","prompt":"Hook"}]},
                        {"label":"DETAIL","window":"8-28s","text":"Angle $angle detail.","shots":[{"type":"broll","prompt":"Detail"}]},
                        {"label":"SHIFT","window":"28-48s","text":"Angle $angle shift.","shots":[{"type":"pip","prompt":"Shift"}]},
                        {"label":"PAYOFF","window":"48-60s","text":"Angle $angle payoff.","shots":[{"type":"card","prompt":"Payoff"}]}
                      ]
                    }"""
                )
            }
        }
        srv.createContext("/v1/studio/shot-preview") { ex ->
            ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"preview_id":"shotprev_fu","preview_url":"/v1/renders/shotprev_fu.jpg","shot_type":"face"}""")
        }
        srv.createContext("/v1/renders/shotprev_fu.jpg") { ex ->
            respondBytes(ex, 200, "image/jpeg", realJpegBytes)
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"

        val vm = buildDraftVm(baseUrl)
        val lastVideo = com.example.data.LastVideoSummary(
            clipId = "clip_01",
            title = "Your phone just killed a subscription",
            durationMs = 58_000L,
            updatedAtMillis = 1_700_000_000_000L,
            thumbFilePath = null,
            transcript = "Your phone runs voice and cuts offline now.",
            topReplyQuestion = "what about battery life?"
        )

        vm.requestFollowupOverview(lastVideo)
        waitFor { !vm.uiState.value.isDraftingFollowup && vm.uiState.value.followupDirections.size == 2 }
        assertEquals(4, vm.uiState.value.followupBeats.size)
        assertEquals("You asked about battery life.", vm.uiState.value.followupBeats[0].text)

        // Switch to "part_2" -> hits /v1/draft/followup with angle="part_2"
        vm.selectFollowupAngle("part_2")
        waitFor { !vm.uiState.value.isDraftingFollowup && vm.uiState.value.followupBeats.firstOrNull()?.text == "Angle part_2 hook." }
        assertEquals("part_2", vm.uiState.value.followupAngle)
        assertNull(vm.uiState.value.followupDirection)

        // Select direction "shitpost" -> uses overview-preloaded direction beats & sets followupDirection
        vm.selectFollowupDirection("shitpost")
        assertEquals("shitpost", vm.uiState.value.followupDirection)
        assertEquals("Calling it: subscriptions are dead.", vm.uiState.value.followupBeats[0].text)

        // Load follow-up into SPARK -> populates 4 beats, sets isFollowupLoaded & followupSourceLabel, downloads previews
        vm.loadFollowupIntoSpark()
        assertTrue(vm.uiState.value.followupLoaded)
        assertEquals(
            "Your phone just killed a subscription · shitpost: calling it, subscriptions dead",
            vm.uiState.value.followupSourceLabel
        )
        assertEquals(DraftTone.SHITPOST, vm.uiState.value.tone)
        assertEquals(4, vm.uiState.value.document.arcBeats.size)
        assertFalse(vm.uiState.value.continueSheetOpen)
        assertTrue(vm.uiState.value.document.seedTranscript.isNotBlank())

        val loadedShotIds = vm.uiState.value.document.arcBeats.flatMap { it.shots }.map { it.id }
        waitFor { loadedShotIds.all { vm.uiState.value.shotPreviews.containsKey(it) } }
    }

    @Test
    fun scriptControls_singleTapPhrase_lengthPlusMinus12Words_andFullKeyboardEditUndo() = runTest {
        val densityDeltas = CopyOnWriteArrayList<Int>()
        val swapRequests = CopyOnWriteArrayList<String>()
        val srv = newHttpServer()
        srv.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        srv.createContext("/v1/draft/brush-swap") { ex ->
            val raw = ex.requestBody.bufferedReader().readText()
            swapRequests += raw
            val isMore = Json.parseToJsonElement(raw).jsonObject["nudge"]?.jsonPrimitive?.content == "more"
            if (isMore) {
                respondJson(ex, 200, """{"alternatives":["zero cloud rent","no server tax","skip the monthly fee",""]}""")
            } else {
                respondJson(ex, 200, """{"alternatives":["monthly cloud bill","SaaS subscription",""]}""")
            }
        }
        srv.createContext("/v1/draft/density") { ex ->
            val raw = ex.requestBody.bufferedReader().readText()
            val pct = Json.parseToJsonElement(raw).jsonObject["delta_percent"]!!.jsonPrimitive.int
            densityDeltas += pct
            val text = if (pct < 0) {
                "Your phone runs offline now. Pay once for the silicon chip."
            } else {
                "Your phone runs voice cloning, captions, and cuts completely offline now. Pay once for the silicon chip in your pocket instead of renting servers."
            }
            respondJson(ex, 200, """{"text":"$text","lock_parity_preserved":true}""")
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"

        val vm = buildDraftVm(baseUrl)
        val initialScript = "Your phone just made a twenty dollar monthly subscription pointless today. " +
            "Voice cloning, captions, and cuts now run completely offline on a Pixel."
        vm.restoreHandoffState("CUT", initialScript)
        assertEquals(Stage.CUT, vm.uiState.value.document.stage)

        // 1. Single-tap phrase selection selects natural 3–6 word chunk & immediately calls brush-swap
        val tapOffset = initialScript.indexOf("monthly")
        val naturalSpan = DraftStateReducer.selectNaturalPhraseRange(
            vm.uiState.value.document.activeCandidate!!,
            tapOffset
        )
        assertNotNull(naturalSpan)
        val spanWords = initialScript.substring(naturalSpan!!).trim().split(Regex("\\s+"))
        assertTrue("Expected 3..6 words in single-tap phrase, got ${spanWords.size}: $spanWords", spanWords.size in 3..6)

        vm.selectPhraseSingleTap(tapOffset)
        waitFor { !vm.uiState.value.isBusy && vm.uiState.value.brushAlternatives.isNotEmpty() }
        assertNotNull(vm.uiState.value.selectedPhrase)
        assertTrue(vm.uiState.value.scriptHintSeen)

        // Refresh ("more") fetches fresh alternatives excluding prior ones
        vm.refreshPhraseAlternatives()
        waitFor { !vm.uiState.value.isBusy && vm.uiState.value.activeBrushNudge == BrushNudge.MORE }
        assertTrue(vm.uiState.value.brushAlternatives.contains("zero cloud rent"))

        // Keep selected phrase closes the phrase sheet while keeping swapped text
        vm.keepSelectedPhrase()
        assertNull(vm.uiState.value.selectedPhrase)
        assertTrue(vm.uiState.value.document.activeCandidate!!.plainText().contains("zero cloud rent"))

        // 2. Length row toggle + ±12 words maps cleanly to commitDensityScrub
        assertFalse(vm.uiState.value.lengthRowOpen)
        vm.toggleLengthRow()
        assertTrue(vm.uiState.value.lengthRowOpen)

        vm.stepScriptLength(-12)
        waitFor { !vm.uiState.value.isBusy && densityDeltas.size == 1 }
        assertTrue("Shorter -12w must send negative delta_percent, got ${densityDeltas[0]}", densityDeltas[0] < 0)

        vm.stepScriptLength(+12)
        waitFor { !vm.uiState.value.isBusy && densityDeltas.size == 2 }
        assertTrue("Longer +12w must send positive delta_percent, got ${densityDeltas[1]}", densityDeltas[1] > 0)

        // 3. Full-script manual keyboard edit saves undo snapshot & updates text
        val beforeManual = vm.uiState.value.document.activeCandidate!!.plainText()
        val manualText = "Manual edit line one. Manual edit line two."
        vm.applyFullScriptKeyboardEdit(manualText)
        assertEquals(manualText, vm.uiState.value.document.activeCandidate!!.plainText())
        assertEquals("script updated", vm.uiState.value.toastMessage)
        assertTrue(vm.uiState.value.document.activeCandidate!!.canUndoGlobal)

        vm.stepGlobalSnapshot(-1)
        assertEquals(beforeManual, vm.uiState.value.document.activeCandidate!!.plainText())
    }

    @Test
    fun studioHandoffAndVideoLook_mapsShotPlan_createsCardOverlay_refreshesHeadline_andAppliesLookToast() = runTest {
        val aiAssistPayloads = CopyOnWriteArrayList<String>()
        val srv = newHttpServer()
        srv.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok","render_bridge":true}""") }
        srv.createContext("/v1/renders") { ex -> respondJson(ex, 200, """{"renders":[]}""") }
        srv.createContext("/v1/ai-assist") { ex ->
            aiAssistPayloads += ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"mode":"polish_headline","text":"PHONE KILLS MONTHLY AI BILL"}""")
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"

        val studioVm = buildStudioVm(baseUrl)
        waitFor { studioVm.uiState.value.projects.isNotEmpty() }

        val shotPlan = listOf(
            listOf(ShotSpec(id = "s0", type = ShotType.FACE, prompt = "Direct camera")),
            listOf(ShotSpec(id = "s1", type = ShotType.PIP, prompt = "Offline NPU benchmark")),
            listOf(ShotSpec(id = "s2", type = ShotType.BROLL, prompt = "Data center racks")),
            listOf(ShotSpec(id = "s3", type = ShotType.CARD, prompt = "Pay once for the chip."))
        )
        studioVm.openStoryInCorePipeline(
            plainText = "First hook sentence. Second detail sentence. Third shift sentence. Fourth payoff sentence.",
            headline = "Initial Headline",
            shotPlan = shotPlan
        )
        waitFor {
            studioVm.uiState.value.openCorePipelineEvent &&
                studioVm.uiState.value.corePipelineSession.clauses.size == 4
        }

        val clauses = studioVm.uiState.value.corePipelineSession.clauses
        assertEquals(BrollSegmentMode.TALKING_HEAD, clauses[0].segmentMode)
        assertEquals(BrollSegmentMode.BROLL_CUTAWAY, clauses[1].segmentMode)
        assertEquals("Offline NPU benchmark", clauses[1].brollCaption)
        assertEquals(BrollSegmentMode.BROLL_CUTAWAY, clauses[2].segmentMode)
        assertEquals("Data center racks", clauses[2].brollCaption)
        assertEquals(BrollSegmentMode.TALKING_HEAD, clauses[3].segmentMode)

        // CARD shot created and attached a real QUOTE_BRIEF SavedNewsroomOverlay in Room
        val savedOverlays = localRepo.listSavedNewsroomOverlays()
        assertEquals(1, savedOverlays.size)
        assertEquals(OverlayObjectKind.QUOTE_BRIEF, savedOverlays.single().kind)
        assertEquals("Pay once for the chip.", savedOverlays.single().headline)
        assertTrue(savedOverlays.single().id in studioVm.uiState.value.corePipelineSession.selectedOverlayIds)

        // Video look: setTitleTagStyle maps none/breaking/developing/explainer -> BreakingNewsStyle
        studioVm.setTitleTagStyle("none")
        assertEquals(BreakingNewsStyle.CLEAN, studioVm.uiState.value.corePipelineSession.renderOptions.breakingStyle)
        studioVm.setTitleTagStyle("developing")
        assertEquals(BreakingNewsStyle.DEVELOPING, studioVm.uiState.value.corePipelineSession.renderOptions.breakingStyle)
        studioVm.setTitleTagStyle("explainer")
        assertEquals(BreakingNewsStyle.EXCLUSIVE, studioVm.uiState.value.corePipelineSession.renderOptions.breakingStyle)
        studioVm.setTitleTagStyle("breaking")
        assertEquals(BreakingNewsStyle.BREAKING, studioVm.uiState.value.corePipelineSession.renderOptions.breakingStyle)

        // Video look: refreshHeadlineWithAi hits real /v1/ai-assist polish_headline
        studioVm.refreshHeadlineWithAi()
        waitFor {
            !studioVm.uiState.value.isRefreshingHeadline &&
                studioVm.uiState.value.corePipelineSession.renderOptions.chyronHeadline == "PHONE KILLS MONTHLY AI BILL"
        }
        assertEquals("title refreshed", studioVm.uiState.value.toastMessage)
        val polishPayload = Json.parseToJsonElement(aiAssistPayloads.single()).jsonObject
        assertEquals("polish_headline", polishPayload["mode"]?.jsonPrimitive?.content)

        // Video look: useLookAndReturn saves edits, sets default in Room, and posts exact v5 toast
        val lookId = studioVm.createNewsroomLook("STUDIO_DARK")
        val editedLook = NewsroomLook(
            id = lookId,
            name = "STUDIO_DARK",
            blurPercent = 35,
            brightnessEv = -0.4,
            light = NewsroomLook.LIGHT_COOL
        )
        studioVm.useLookAndReturn(lookId, draftOverride = editedLook)
        assertEquals("Overlays saved · will apply when rendered", studioVm.uiState.value.toastMessage)

        waitFor {
            runBlocking {
                localRepo.getDefaultNewsroomLook()?.let { it.id == lookId && it.blurPercent == 35 } == true
            }
        }
        val persistedDefault = localRepo.getDefaultNewsroomLook()!!
        assertEquals(35, persistedDefault.blurPercent)
        assertEquals(-0.4, persistedDefault.brightnessEv, 1e-6)
        assertEquals(NewsroomLook.LIGHT_COOL, persistedDefault.light)
        assertTrue(persistedDefault.isDefault)

        studioVm.dismissToast()
        assertNull(studioVm.uiState.value.toastMessage)
    }

    @Test
    fun brollSegmentSheet_editsLinkedSentenceOrSegment_andFramingPinsOthers() = runTest {
        val srv = newHttpServer()
        srv.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        srv.createContext("/v1/renders") { ex -> respondJson(ex, 200, """{"renders":[]}""") }
        srv.start()
        server = srv
        val studioVm = buildStudioVm("http://127.0.0.1:${srv.address.port}")
        waitFor { studioVm.uiState.value.projects.isNotEmpty() }

        studioVm.openStoryInCorePipeline(
            plainText = "First hook sentence. Second detail sentence. Third shift sentence.",
            headline = "Segments",
            shotPlan = listOf(
                listOf(ShotSpec(id = "s0", type = ShotType.FACE, prompt = "Direct camera")),
                listOf(ShotSpec(id = "s1", type = ShotType.BROLL, prompt = "Server racks")),
                listOf(ShotSpec(id = "s2", type = ShotType.BROLL, prompt = "City at night"))
            )
        )
        waitFor {
            val cur = studioVm.uiState.value.corePipelineSession
            cur.clauses.size == 3 && cur.brollSegments.any { it.originClauseId == cur.clauses[1].id }
        }
        val start = studioVm.uiState.value.corePipelineSession
        val linked = start.brollSegments.first { it.originClauseId == start.clauses[1].id }

        // A sentence-linked block edits its sentence; the segment mirrors it.
        studioVm.updateBrollSegmentPrompt(linked.id, "Empty meeting room")
        var s = studioVm.uiState.value.corePipelineSession
        assertEquals("Empty meeting room", s.clauses[1].bRollPrompt)
        assertEquals("Empty meeting room", s.brollSegments.first { it.id == linked.id }.prompt)

        // Framing: the chosen block changes, the render default follows, other blocks keep the old default.
        val oldDefault = s.renderOptions.brollFraming
        studioVm.setBrollSegmentFraming(linked.id, "full")
        s = studioVm.uiState.value.corePipelineSession
        assertEquals("full", s.clauses[1].brollFramingOverride)
        assertEquals("full", s.brollSegments.first { it.id == linked.id }.framingOverride)
        assertEquals("full", s.renderOptions.brollFraming)
        assertEquals(oldDefault, s.clauses[2].brollFramingOverride)

        // A free block (not linked to a sentence) is edited directly. Let the VM's own saves land first.
        waitFor { runBlocking { localRepo.getLatestCorePipelineSession() }?.renderOptions?.brollFraming == "full" }
        val free = s.copy(
            brollSegments = s.brollSegments + com.example.data.BrollSegment(id = "seg_free", startMs = 0L, durationMs = 500L)
        )
        localRepo.saveCorePipelineSession(free)
        waitFor { studioVm.uiState.value.corePipelineSession.brollSegments.any { it.id == "seg_free" } }
        studioVm.updateBrollSegmentPrompt("seg_free", "Rain on a window")
        studioVm.setBrollSegmentFraming("seg_free", "split")
        val seg = studioVm.uiState.value.corePipelineSession.brollSegments.first { it.id == "seg_free" }
        assertEquals("Rain on a window", seg.prompt)
        assertEquals("split", seg.framingOverride)
        assertNull(seg.originClauseId)
        assertTrue(studioVm.uiState.value.coreUndoDepth > 0)
        // Let the VM's background saves land before tearDown closes the database. (Each edit is saved
        // on its own IO launch, so the two saves can finish in either order; both carry the prompt.)
        waitFor(timeoutMs = 20_000L) {
            runBlocking { localRepo.getLatestCorePipelineSession() }
                ?.brollSegments?.any { it.id == "seg_free" && it.prompt == "Rain on a window" } == true
        }
    }

    @Test
    fun backToBackStudioEdits_persistInOrder_latestStateWins() = runTest {
        val srv = newHttpServer()
        srv.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        srv.createContext("/v1/renders") { ex -> respondJson(ex, 200, """{"renders":[]}""") }
        srv.start()
        server = srv
        val studioVm = buildStudioVm("http://127.0.0.1:${srv.address.port}")
        waitFor { studioVm.uiState.value.projects.isNotEmpty() }
        studioVm.openStoryInCorePipeline(plainText = "One line. Two line.", headline = "Order")
        waitFor { studioVm.uiState.value.corePipelineSession.clauses.size == 2 }
        val sessionId = studioVm.uiState.value.corePipelineSession.id

        // Fire edits back to back without waiting: the saved session must end on the last one.
        repeat(25) { i ->
            studioVm.updateChyronSubline("subline $i")
            studioVm.setCoreCaptions(i % 2 == 0)
        }
        assertEquals("subline 24", studioVm.uiState.value.corePipelineSession.renderOptions.chyronSubline)
        waitFor(timeoutMs = 20_000L) {
            val saved = runBlocking { localRepo.getLatestCorePipelineSession() }
            saved?.id == sessionId && saved.renderOptions.chyronSubline == "subline 24" && saved.renderOptions.captions
        }
        // No earlier save may land afterwards and roll it back.
        Thread.sleep(500)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val final = runBlocking { localRepo.getLatestCorePipelineSession() }!!
        assertEquals("subline 24", final.renderOptions.chyronSubline)
        assertTrue(final.renderOptions.captions)
        assertEquals("subline 24", studioVm.uiState.value.corePipelineSession.renderOptions.chyronSubline)
    }

    @Test
    fun openStoryInCorePipeline_sanitizesCardPromptChrome_truncatesAtWordBoundary_andCreatesDistinctCardPerStory() = runTest {
        val studioVm = buildStudioVm("http://127.0.0.1:9")
        waitFor { studioVm.uiState.value.projects.isNotEmpty() }

        assertEquals(
            "Stop paying. Start editing locally.",
            StudioViewModel.sanitizeCardQuoteHeadline("Text card reading: 'Stop paying. Start editing locally.'")
        )
        assertEquals(
            "Own the silicon once.",
            StudioViewModel.sanitizeCardQuoteHeadline("Quote card reading: \"Own the silicon once.\"")
        )
        assertEquals(
            "Card fees drop 50% locally.",
            StudioViewModel.sanitizeCardQuoteHeadline("Card fees drop 50% locally.")
        )
        val truncated = StudioViewModel.sanitizeCardQuoteHeadline(
            "On-screen text: Stop paying monthly for cloud servers when your phone edits locally on silicon."
        )
        assertTrue("Expected <= 56 chars, got ${truncated.length}: $truncated", truncated.length <= 56)
        assertTrue("Expected word-boundary ellipsis, got: $truncated", truncated.endsWith("…"))
        assertFalse("Must not contain prompt prefix: $truncated", truncated.lowercase().contains("on-screen text"))

        // Story 1: creates clean QUOTE_BRIEF without "Text card reading:" prefix
        studioVm.openStoryInCorePipeline(
            plainText = "Hook one. Detail two. Shift three. Payoff four.",
            headline = "Story One",
            shotPlan = listOf(
                listOf(ShotSpec(id = "a0", type = ShotType.FACE, prompt = "Anchor")),
                listOf(ShotSpec(id = "a1", type = ShotType.CARD, prompt = "Text card reading: 'Stop paying. Start editing locally.'"))
            )
        )
        waitFor {
            studioVm.uiState.value.corePipelineSession.title == "Story One" &&
                studioVm.uiState.value.corePipelineSession.selectedOverlayIds.isNotEmpty()
        }
        val firstOverlayId = studioVm.uiState.value.corePipelineSession.selectedOverlayIds.single()
        val firstOverlay = localRepo.listSavedNewsroomOverlays().first { it.id == firstOverlayId }
        assertEquals("Stop paying. Start editing locally.", firstOverlay.headline)

        // Story 2 with a DIFFERENT card prompt: must create and select a NEW overlay, never reuse Story 1's card!
        studioVm.consumeOpenCorePipelineEvent()
        studioVm.openStoryInCorePipeline(
            plainText = "Second story hook. Second story payoff.",
            headline = "Story Two",
            shotPlan = listOf(
                listOf(ShotSpec(id = "b0", type = ShotType.FACE, prompt = "Anchor")),
                listOf(ShotSpec(id = "b1", type = ShotType.CARD, prompt = "Title card: \"Buy the silicon once.\""))
            )
        )
        waitFor {
            studioVm.uiState.value.openCorePipelineEvent &&
                studioVm.uiState.value.corePipelineSession.title == "Story Two"
        }
        val secondOverlayId = studioVm.uiState.value.corePipelineSession.selectedOverlayIds.single()
        assertFalse("New story card must not blindly reuse prior story's overlay", firstOverlayId == secondOverlayId)
        val allSaved = localRepo.listSavedNewsroomOverlays()
        assertEquals(2, allSaved.size)
        assertEquals("Buy the silicon once.", allSaved.first { it.id == secondOverlayId }.headline)

        // Re-opening with identical card headline reuses the matching overlay without duplicating
        studioVm.consumeOpenCorePipelineEvent()
        studioVm.openStoryInCorePipeline(
            plainText = "Second story revised.",
            headline = "Story Two Revised",
            shotPlan = listOf(
                listOf(ShotSpec(id = "c0", type = ShotType.CARD, prompt = "Buy the silicon once."))
            )
        )
        waitFor { studioVm.uiState.value.openCorePipelineEvent }
        assertEquals(secondOverlayId, studioVm.uiState.value.corePipelineSession.selectedOverlayIds.single())
        assertEquals(2, localRepo.listSavedNewsroomOverlays().size)
    }

    @Test
    fun sparkSeedAudioPlayback_showsHonestToastWhenMissing_andPlaysRealWavFileCleanly() = runTest {
        val vm = buildDraftVm("http://127.0.0.1:9")
        assertFalse(vm.uiState.value.isPlayingSeedAudio)

        // Without a recorded WAV file, togglePlaySeedAudio must NOT fake playback
        vm.togglePlaySeedAudio()
        assertFalse(vm.uiState.value.isPlayingSeedAudio)
        assertEquals("no recorded audio for this note", vm.uiState.value.toastMessage)
        vm.dismissToast()
        assertNull(vm.uiState.value.toastMessage)

        // Write a real 16-bit PCM WAV file into the VAD cache and verify MediaPlayer lifecycle
        val wavFile = tempFolder.newFile("seed_real.wav")
        CoreStoryPipelineCoordinator.writeFloat32PcmAs16BitMonoWav(FloatArray(22_050), wavFile)
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(wavFile.absolutePath),
            ShadowMediaPlayer.MediaInfo(1_000, 0)
        )
        val field = DraftDeckViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<com.example.ui.draft.DraftDeckUiState>
        flow.value = flow.value.copy(
            lastSeedAudioPath = wavFile.absolutePath,
            lastSeedDurationSec = 1
        )

        vm.togglePlaySeedAudio()
        assertTrue(vm.uiState.value.isPlayingSeedAudio)
        vm.togglePlaySeedAudio()
        assertFalse(vm.uiState.value.isPlayingSeedAudio)
    }

    private fun sampleClip(
        id: String,
        scriptId: String = "",
        status: RenderJobStatus,
        title: String,
        durationMs: Long = 30_000L
    ) = ClipRecord(
        id = id,
        scriptId = scriptId,
        jobId = "job_$id",
        title = title,
        photoSha = null,
        voiceSha = null,
        alignSha = null,
        lipsyncSha = null,
        finalSha = null,
        status = status,
        progressFraction = if (status == RenderJobStatus.READY) 1f else 0.2f,
        stageDetail = status.label,
        errorMessage = null,
        durationMs = durationMs,
        waveformPeaks = emptyList(),
        thumbHash = null,
        alignedWords = emptyList(),
        chyronHeadline = title,
        chyronSubline = "",
        remoteLipsyncUrl = null,
        remoteFinalUrl = null,
        exportedUri = null,
        createdAtMillis = System.currentTimeMillis(),
        updatedAtMillis = System.currentTimeMillis()
    )

    private fun waitFor(timeoutMs: Long = 8_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(20)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition timed out after ${timeoutMs}ms", predicate())
    }

    private fun respondJson(exchange: HttpExchange, statusCode: Int, jsonBody: String) {
        respondBytes(exchange, statusCode, "application/json; charset=utf-8", jsonBody.toByteArray(Charsets.UTF_8))
    }

    private fun respondBytes(exchange: HttpExchange, statusCode: Int, contentType: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
