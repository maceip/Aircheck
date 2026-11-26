package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.gateway.DirectMacRigGateway
import com.example.data.gateway.RigJobNotFoundException
import com.example.data.gateway.RigTaskHandle
import com.example.data.local.AircheckDatabase
import com.example.data.local.AircheckLocalRepository
import com.example.data.local.CasMediaStore
import com.example.data.local.CorePipelineSessionEntity
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class MacRigGatewayAndSessionContractTest {

    private lateinit var context: Context
    private lateinit var db: AircheckDatabase
    private lateinit var localRepo: AircheckLocalRepository
    private lateinit var casStore: CasMediaStore
    private lateinit var gateway: DirectMacRigGateway
    private var server: HttpServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AircheckDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localRepo = AircheckLocalRepository(db)
        casStore = CasMediaStore(context, db.casDao())
        gateway = DirectMacRigGateway(casStore, OkHttpClient())
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
        db.close()
    }

    private val rigTasks = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val rigTaskSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private fun respondAsyncTask(exchange: com.sun.net.httpserver.HttpExchange, resultJson: ByteArray) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (exchange.requestURI.query?.contains("async=1") == true) {
            val id = "task_${rigTaskSeq.incrementAndGet()}"
            rigTasks[id] = resultJson.toString(Charsets.UTF_8)
            val accepted = """{"job_id":"$id","status":"RUNNING"}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(202, accepted.size.toLong())
            exchange.responseBody.use { it.write(accepted) }
        } else {
            exchange.sendResponseHeaders(200, resultJson.size.toLong())
            exchange.responseBody.use { it.write(resultJson) }
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

    @Test
    fun synthesizeCoreStoryTts_parsesSnakeAndCamelWords_alignUrl_andMetrics() = runTest {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/jobs/task_") { serveRigTask(it) }
        srv.createContext("/v1/core/tts") { ex ->
            val p = srv.address.port
            val json = """
                {
                  "tts_id": "tts_101",
                  "audio_url": "http://127.0.0.1:$p/v1/renders/tts_101.wav",
                  "duration_ms": 59800,
                  "waveform_peaks": [0.2, 0.8],
                  "clauses": [
                    {"id": "c0", "index": 0, "spoken_text": "Breaking news.", "start_ms": 0, "end_ms": 59800}
                  ],
                  "words": [
                    {"word": "Breaking", "start_ms": 10, "end_ms": 420},
                    {"word": "news", "startMs": 430, "endMs": 890}
                  ],
                  "align_url": "/v1/renders/align_101.json",
                  "metrics": {
                    "cer": 0.0,
                    "wespeaker_cosine": 0.93,
                    "dnsmos_p808": 4.41,
                    "tail_rms_dbfs": -53.2,
                    "mastered_lufs": -16.0,
                    "syllables_per_sec": 5.1,
                    "max_phoneme_duration_ms": 175.0,
                    "passed_all_strict_gates": true
                  }
                }
            """.trimIndent().toByteArray()
            respondAsyncTask(ex, json)
        }
        srv.createContext("/v1/renders/align_101.json") { ex ->
            val json = """{"words":[{"word":"Fetched","start_ms":5,"end_ms":210}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"

        val res = gateway.synthesizeCoreStoryTts(
            rigBaseUrl = baseUrl,
            projectId = "proj_101",
            clauses = listOf(CoreStoryClause(id = "c0", spokenText = "Breaking news."))
        )
        assertEquals("tts_101", res.ttsId)
        assertEquals(59800L, res.durationMs)
        assertEquals("$baseUrl/v1/renders/align_101.json", res.alignUrl)
        assertEquals(2, res.words.size)
        assertEquals(AlignedWord("Breaking", 10L, 420L), res.words[0])
        assertEquals(AlignedWord("news", 430L, 890L), res.words[1])
        assertNotNull(res.metrics)
        assertEquals(0.0, res.metrics!!.cer!!, 1e-6)
        assertEquals(0.93, res.metrics!!.wespeakerCosine!!, 1e-6)
        assertEquals(4.41, res.metrics!!.dnsmosP808!!, 1e-6)
        assertEquals(-53.2, res.metrics!!.tailRmsDbfs!!, 1e-6)
        assertEquals(-16.0, res.metrics!!.masteredLufs!!, 1e-6)
        assertEquals(5.1, res.metrics!!.syllablesPerSec!!, 1e-6)
        assertEquals(175.0, res.metrics!!.maxPhonemeDurationMs!!, 1e-6)
        assertEquals(true, res.metrics!!.passedAllStrictGates)

        val fetched = gateway.fetchAlignedWords(res.alignUrl!!)
        assertEquals(listOf(AlignedWord("Fetched", 5L, 210L)), fetched)
    }

    @Test
    fun pollMacRigJob_throwsRigJobNotFoundOn404_andParsesErrorKindWordsAndMetrics() = runTest {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/jobs/missing_404") { ex ->
            val json = """{"error":"not_found"}""".toByteArray()
            ex.sendResponseHeaders(404, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.createContext("/v1/jobs/restarted_job") { ex ->
            val json = """
                {
                  "job_id": "restarted_job",
                  "status": "FAILED",
                  "error_kind": "rig_restarted",
                  "error_message": "Server restarted"
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.createContext("/v1/jobs/ready_job") { ex ->
            val json = """
                {
                  "job_id": "ready_job",
                  "status": "READY",
                  "progress_fraction": 1.0,
                  "stage_detail": "Complete",
                  "video_url": "/v1/renders/ready.mp4",
                  "duration_ms": 60120,
                  "align_url": "/v1/renders/ready_align.json",
                  "words": [{"word": "Headline", "start_ms": 0, "end_ms": 340}],
                  "metrics": {
                    "cer": 0.0,
                    "wespeaker_cosine": 0.91,
                    "dnsmos_p808": 4.32,
                    "av_sync_drift_ms": 12.5,
                    "tail_rms_dbfs": -51.0,
                    "mastered_lufs": -16.1,
                    "syllables_per_sec": 4.9,
                    "max_phoneme_duration_ms": 190.0,
                    "passed_all_strict_gates": true
                  }
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"

        val err404 = runCatching { gateway.pollMacRigJob(baseUrl, "missing_404") }.exceptionOrNull()
        assertTrue("Expected RigJobNotFoundException on 404, got $err404", err404 is RigJobNotFoundException)

        val restarted = gateway.pollMacRigJob(baseUrl, "restarted_job")
        assertEquals(RenderJobStatus.FAILED, restarted.status)
        assertEquals("rig_restarted", restarted.errorKind)

        val ready = gateway.pollMacRigJob(baseUrl, "ready_job")
        assertEquals(RenderJobStatus.READY, ready.status)
        assertEquals("$baseUrl/v1/renders/ready.mp4", ready.videoUrl)
        assertEquals("$baseUrl/v1/renders/ready_align.json", ready.alignUrl)
        assertEquals(listOf(AlignedWord("Headline", 0L, 340L)), ready.words)
        assertEquals(12.5, ready.metrics!!.avSyncDriftMs!!, 1e-6)
        assertEquals(0.91, ready.wespeakerCosine!!, 1e-6)
        assertEquals(4.32, ready.dnsmosP808!!, 1e-6)
    }

    @Test
    fun enqueueMacRigRender_allowsUpTo120s_andSerializesOptionalFields() = runTest {
        val capturedBodies = CopyOnWriteArrayList<String>()
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/render-video") { ex ->
            capturedBodies.add(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val json = """{"job_id":"head_120s"}""".toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"

        val project = ScriptProject(
            id = "proj_60s",
            title = "60s Report",
            voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
            beats = listOf(ScriptBeat(spokenText = "Sixty seconds of local news.", durationSec = 62)),
            stageConfig = NewsroomStageConfig(
                chyronHeadline = "LIVE UPDATE",
                chyronSubline = "STUDIO A",
                showBrandBug = true
            )
        )

        gateway.enqueueMacRigRender(
            rigBaseUrl = baseUrl,
            project = project,
            photoSha = null,
            maxDurationSec = 99,
            words = listOf(AlignedWord("Sixty", 0L, 300L)),
            alignUrl = "$baseUrl/v1/align.json",
            useRemotion = true
        )
        gateway.enqueueMacRigRender(
            rigBaseUrl = baseUrl,
            project = project,
            photoSha = null,
            maxDurationSec = 180
        )

        val first = JSONObject(capturedBodies[0])
        assertEquals(99, first.getInt("max_duration"))
        assertEquals("LIVE UPDATE", first.getString("chyron_headline"))
        assertEquals("STUDIO A", first.getString("chyron_subline"))
        assertTrue(first.getBoolean("show_brand_bug"))
        assertEquals("$baseUrl/v1/align.json", first.getString("align_url"))
        assertTrue(first.getBoolean("use_remotion"))
        assertEquals("Sixty", first.getJSONArray("words").getJSONObject(0).getString("word"))

        val second = JSONObject(capturedBodies[1])
        assertEquals(120, second.getInt("max_duration"))
    }

    @Test
    fun chopTalkingHeadIntoBroll_sendsExtendedMetadata_andAiAssistPreserved() = runTest {
        val chopBodies = CopyOnWriteArrayList<String>()
        val aiBodies = CopyOnWriteArrayList<String>()
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/jobs/task_") { serveRigTask(it) }
        srv.createContext("/v1/core/chop-broll") { ex ->
            chopBodies.add(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val p = srv.address.port
            val json = """
                {
                  "job_id": "cut_01",
                  "video_url": "http://127.0.0.1:$p/v1/renders/cut_01.mp4",
                  "duration_ms": 60000,
                  "broll_cut_count": 2,
                  "applied_overlay_count": 0,
                  "caption_count": 24,
                  "aspect": "9:16",
                  "remotion_used": true,
                  "renderer": "remotion-4.0.545"
                }
            """.trimIndent().toByteArray()
            respondAsyncTask(ex, json)
        }
        srv.createContext("/v1/ai-assist") { ex ->
            aiBodies.add(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val json = """
                {
                  "kind": "LOWER_THIRD",
                  "headline": "CITY GRID",
                  "subline": "LIVE REPORT",
                  "callout_stat": "+18%",
                  "duration_sec": 5
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"

        val chopRes = gateway.chopTalkingHeadIntoBroll(
            rigBaseUrl = baseUrl,
            scriptId = "s_1",
            title = "Evening Brief",
            sourceJobId = "head_1",
            sourceMp4Sha = null,
            clauses = listOf(
                CoreStoryClause(
                    id = "c_0",
                    index = 0,
                    spokenText = "Power demand climbed.",
                    startMs = 0L,
                    endMs = 3000L,
                    segmentMode = BrollSegmentMode.BROLL_CUTAWAY,
                    brollCaption = "Power lines",
                    bRollPrompt = "Aerial drone shot of electrical substation at dusk",
                    bRollSha = "deadbeef01",
                    depthTextTag = "+18% LOAD"
                )
            ),
            renderOptions = CoreRenderOptions(
                stripSilence = true,
                punchIn = true,
                captions = true,
                aspect = CoreRenderOptions.ASPECT_VERTICAL,
                chyronHeadline = "GRID ALERT",
                chyronSubline = "ERCOT DESK",
                showBrandBug = true,
                cornerBugText = "LIVE",
                useRemotion = true
            ),
            alignedWords = listOf(AlignedWord("Power", 0L, 450L)),
            alignUrl = "$baseUrl/v1/align.json",
            useRemotion = true
        )

        val payload = JSONObject(chopBodies.single())
        val clauseJson = payload.getJSONArray("clauses").getJSONObject(0)
        assertEquals(0, clauseJson.getInt("index"))
        assertEquals("Aerial drone shot of electrical substation at dusk", clauseJson.getString("b_roll_prompt"))
        assertEquals("deadbeef01", clauseJson.getString("b_roll_sha"))
        assertEquals("+18% LOAD", clauseJson.getString("depth_text_tag"))
        assertEquals("$baseUrl/v1/align.json", payload.getString("align_url"))
        assertEquals("Power", payload.getJSONArray("words").getJSONObject(0).getString("word"))
        val opts = payload.getJSONObject("render_options")
        assertEquals("GRID ALERT", opts.getString("chyron_headline"))
        assertEquals("ERCOT DESK", opts.getString("chyron_subline"))
        assertTrue(opts.getBoolean("show_brand_bug"))
        assertEquals("LIVE", opts.getString("corner_bug_text"))
        assertTrue(opts.getBoolean("use_remotion"))

        assertTrue(chopRes.studioStats!!.remotionUsed)
        assertEquals("remotion-4.0.545", chopRes.studioStats!!.renderer)

        val brief = gateway.generateAiOverlayBrief(baseUrl, "Grid load report")
        assertEquals(OverlayObjectKind.LOWER_THIRD, brief.kind)
        assertEquals("CITY GRID", brief.headline)
        assertEquals("generate_overlay_brief", JSONObject(aiBodies.single()).getString("mode"))
    }

    @Test
    fun corePipelineSession_legacyV5JsonBackwardCompatAndRoundTrip() = runTest {
        // Legacy Room v5 JSON lacking all new M4-1 defaulted fields
        val legacyJson = """
            {
              "id": "pipe_legacy01",
              "scriptId": "script_legacy01",
              "title": "Legacy Story",
              "stage": "ITERATE_STORY",
              "clauses": [
                {
                  "id": "c_0",
                  "index": 0,
                  "spokenText": "Legacy clause one.",
                  "startMs": 0,
                  "endMs": 2000,
                  "segmentMode": "TALKING_HEAD",
                  "brollCaption": ""
                }
              ],
              "renderOptions": {
                "stripSilence": false,
                "punchIn": true,
                "captions": true,
                "aspect": "9:16"
              },
              "updatedAtMillis": 1700000000000
            }
        """.trimIndent()
        db.corePipelineSessionDao().upsert(
            CorePipelineSessionEntity(
                id = "pipe_legacy01",
                scriptId = "script_legacy01",
                stage = "ITERATE_STORY",
                sessionJson = legacyJson,
                updatedAtMillis = 1700000000000L
            )
        )

        val decoded = localRepo.getLatestCorePipelineSession()
        assertNotNull(decoded)
        assertEquals("pipe_legacy01", decoded!!.id)
        assertEquals("", decoded.clauses[0].bRollPrompt)
        assertNull(decoded.clauses[0].bRollSha)
        assertEquals("", decoded.clauses[0].depthTextTag)
        assertEquals("", decoded.renderOptions.chyronHeadline)
        assertFalse(decoded.renderOptions.showBrandBug)
        assertEquals("LIVE", decoded.renderOptions.cornerBugText)
        assertNull(decoded.renderOptions.useRemotion)
        assertNull(decoded.alignUrl)
        assertNull(decoded.ttsMetrics)
        assertNull(decoded.headMetrics)

        // Round-trip extended session
        val extended = decoded.copy(
            alignUrl = "http://127.0.0.1:8443/v1/align.json",
            ttsMetrics = RigQualityMetrics(cer = 0.0, wespeakerCosine = 0.94, passedAllStrictGates = true),
            clauses = listOf(
                decoded.clauses[0].copy(
                    bRollPrompt = "Studio desk",
                    depthTextTag = "60S"
                )
            ),
            renderOptions = decoded.renderOptions.copy(
                chyronHeadline = "LIVE REPORT",
                useRemotion = true
            )
        )
        localRepo.saveCorePipelineSession(extended)
        val reloaded = localRepo.getLatestCorePipelineSession()!!
        assertEquals("http://127.0.0.1:8443/v1/align.json", reloaded.alignUrl)
        assertEquals(0.94, reloaded.ttsMetrics!!.wespeakerCosine!!, 1e-6)
        assertEquals("Studio desk", reloaded.clauses[0].bRollPrompt)
        assertEquals("LIVE REPORT", reloaded.renderOptions.chyronHeadline)
        assertEquals(true, reloaded.renderOptions.useRemotion)
    }

    @Test
    fun runRigTask_failsImmediatelyOnSecondConsecutive404WithoutSleeping() = runTest {
        val jobPollPaths = CopyOnWriteArrayList<String>()
        var postCount = 0
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/jobs/") { ex ->
            jobPollPaths.add(ex.requestURI.path)
            val body = """{"error":"not_found"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(404, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        srv.createContext("/v1/core/tts") { ex ->
            postCount++
            val accepted = """{"job_id":"resubmitted_task_1","status":"RUNNING"}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(202, accepted.size.toLong())
            ex.responseBody.use { it.write(accepted) }
        }
        srv.start()
        server = srv
        val baseUrl = "http://127.0.0.1:${srv.address.port}"

        val submittedIds = CopyOnWriteArrayList<String>()
        val startMs = System.currentTimeMillis()
        val result = runCatching {
            gateway.synthesizeCoreStoryTts(
                rigBaseUrl = baseUrl,
                projectId = "proj_404",
                clauses = listOf(CoreStoryClause(id = "c0", spokenText = "Fast fail.")),
                task = RigTaskHandle(
                    resumeTaskId = "stale_task_0",
                    onSubmitted = { id -> submittedIds.add(id) }
                )
            )
        }
        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue(result.isFailure)
        assertEquals(1, postCount)
        assertEquals(listOf("resubmitted_task_1"), submittedIds)
        assertEquals(listOf("/v1/jobs/stale_task_0", "/v1/jobs/resubmitted_task_1"), jobPollPaths)
        assertTrue("Expected fast-fail on 2nd 404 (<5s), took ${elapsedMs}ms", elapsedMs < 5_000L)
    }
}
