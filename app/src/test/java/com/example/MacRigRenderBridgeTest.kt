package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.BrandChannel
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import com.example.data.gateway.CloudRenderJobGateway
import com.example.data.local.AircheckDatabase
import com.example.data.local.CasMediaStore
import com.example.data.local.StudioSettings
import com.example.ui.RigConnectionStatus
import com.example.ui.RigHealthUiState
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import com.example.ui.screens.SystemDeckSheet
import com.example.ui.theme.AircheckTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MacRigRenderBridgeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleMp4Bytes(): ByteArray {
        val header = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x02, 0x00
        )
        val body = ByteArray(2048) { idx -> ((idx * 17 + 5) and 0xFF).toByte() }
        return header + body
    }

    private fun testOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    @Test
    fun cloudRenderJobGatewayPostsScriptPollsTransitionsAndDownloadsMp4IntoCas() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AircheckDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val casStore = CasMediaStore(context, db.casDao())
        val expectedPhotoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x12, 0x34, 0xFF.toByte(), 0xD9.toByte())
        val storedPhoto = casStore.putBytes(expectedPhotoBytes, "jpg")
        val expectedMp4 = sampleMp4Bytes()

        val capturedPostJson = AtomicReference<JSONObject?>()
        val pollCounter = AtomicInteger(0)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            val resp = """{"status":"ok","lora_version":2,"bundle_version":"v002","pending_preferences":4,"render_bridge":true,"active_render_jobs":0}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/render-video") { exchange ->
            val bodyText = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
            capturedPostJson.set(JSONObject(bodyText))
            val resp = """{"job_id":"job_mac_e2e_1","status":"QUEUED","progress_fraction":0.05,"stage_detail":"Queued on Mac Studio rig"}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(202, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/jobs/job_mac_e2e_1") { exchange ->
            val step = pollCounter.incrementAndGet()
            val payload = when (step) {
                1 -> """{"job_id":"job_mac_e2e_1","status":"VOICE","progress_fraction":0.25,"stage_detail":"Synthesizing voice (lora-work-v002)","error_message":null,"video_url":null,"duration_ms":0,"metrics":null}"""
                2 -> """{"job_id":"job_mac_e2e_1","status":"LIPSYNC","progress_fraction":0.65,"stage_detail":"Rendering talking-head video (wan3.0-video)","error_message":null,"video_url":null,"duration_ms":0,"metrics":{"cer":0.0,"wespeaker_cosine":0.875,"dnsmos_p808":4.22}}"""
                else -> """{"job_id":"job_mac_e2e_1","status":"READY","progress_fraction":1.0,"stage_detail":"Ready","error_message":null,"video_url":"/v1/renders/job_mac_e2e_1.mp4","duration_ms":6780,"metrics":{"cer":0.0,"wespeaker_cosine":0.875,"dnsmos_p808":4.22}}"""
            }.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/v1/renders/job_mac_e2e_1.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            exchange.sendResponseHeaders(200, expectedMp4.size.toLong())
            exchange.responseBody.use { it.write(expectedMp4) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val gateway = CloudRenderJobGateway(context, casStore, testOkHttp())

            // 1. Health check
            val health = gateway.checkMacRigHealth(baseUrl)
            assertEquals("ok", health.status)
            assertEquals(2, health.loraVersion)
            assertEquals("v002", health.bundleVersion)
            assertEquals(4, health.pendingPreferences)
            assertTrue(health.renderBridgeAvailable)

            // 2. Enqueue ENROLLED_VOICE_CLONE script with custom photo from CAS
            val project = ScriptProject(
                id = "proj_mac_1",
                title = "Direct Mac Render",
                voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
                beats = listOf(
                    ScriptBeat(id = "b1", index = 0, spokenText = "We wired Aircheck straight into the Mac Studio."),
                    ScriptBeat(id = "b2", index = 1, spokenText = "Zero Firebase setup required.")
                )
            )
            val enqueued = gateway.enqueueMacRigRender(
                rigBaseUrl = baseUrl,
                project = project,
                photoSha = storedPhoto.sha256
            )
            assertEquals("job_mac_e2e_1", enqueued.jobId)
            assertEquals("job_mac_e2e_1", enqueued.clipId)

            val sentJson = capturedPostJson.get()
            assertNotNull(sentJson)
            assertEquals("proj_mac_1", sentJson!!.getString("script_id"))
            assertEquals("Direct Mac Render", sentJson.getString("title"))
            assertEquals(
                "We wired Aircheck straight into the Mac Studio. Zero Firebase setup required.",
                sentJson.getString("spoken_text")
            )
            assertEquals("ENROLLED_VOICE_CLONE", sentJson.getString("voice_source"))
            assertEquals("720P", sentJson.getString("resolution"))
            assertEquals("9:16", sentJson.getString("ratio"))
            assertEquals(10, sentJson.getInt("max_duration"))
            assertArrayEquals(
                expectedPhotoBytes,
                Base64.getDecoder().decode(sentJson.getString("photo_jpeg_base64"))
            )

            // 3. Poll status transitions: VOICE -> LIPSYNC -> READY
            val s1 = gateway.pollMacRigJob(baseUrl, enqueued.jobId)
            assertEquals(RenderJobStatus.VOICE, s1.status)
            assertNull(s1.videoUrl)
            assertNull(s1.errorMessage)

            val s2 = gateway.pollMacRigJob(baseUrl, enqueued.jobId)
            assertEquals(RenderJobStatus.LIPSYNC, s2.status)
            assertEquals(0.0, s2.cer!!, 1e-6)
            assertEquals(0.875, s2.wespeakerCosine!!, 1e-6)

            val s3 = gateway.pollMacRigJob(baseUrl, enqueued.jobId)
            assertEquals(RenderJobStatus.READY, s3.status)
            assertEquals(1.0f, s3.progressFraction, 1e-4f)
            assertEquals("$baseUrl/v1/renders/job_mac_e2e_1.mp4", s3.videoUrl)
            assertEquals(6780L, s3.durationMs)
            assertEquals(4.22, s3.dnsmosP808!!, 1e-6)

            // 4. Download rendered MP4 into CasMediaStore
            val storedMp4 = gateway.downloadRenderedMp4ToCas(s3.videoUrl!!)
            assertEquals("mp4", storedMp4.ext)
            assertEquals(CasMediaStore.computeSha256Hex(expectedMp4), storedMp4.sha256)
            assertTrue(storedMp4.file.exists())
            assertArrayEquals(expectedMp4, storedMp4.file.readBytes())
        } finally {
            server.stop(0)
            db.close()
        }
    }

    @Test
    fun cloudRenderJobGatewaySendsMicWavAndPropagatesErrorsHonestly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AircheckDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val casStore = CasMediaStore(context, db.casDao())
        val expectedWav = ("RIFF" + "0123WAVEfmt " + "MIC_TAKE_PCM_BYTES").toByteArray(Charsets.US_ASCII)
        val storedWav = casStore.putBytes(expectedWav, "wav")

        val capturedPostJson = AtomicReference<JSONObject?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            val err = """{"error":"degraded","detail":"Bundle manifest missing"}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(500, err.size.toLong())
            exchange.responseBody.use { it.write(err) }
        }
        server.createContext("/v1/render-video") { exchange ->
            val bodyText = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
            capturedPostJson.set(JSONObject(bodyText))
            val resp = """{"job_id":"job_mic_fail","status":"QUEUED"}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(202, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/jobs/job_mic_fail") { exchange ->
            val resp = """{"job_id":"job_mic_fail","status":"FAILED","progress_fraction":0.5,"stage_detail":"Failed","error_message":"bl video ref failed (rc=1): upstream error","video_url":null,"duration_ms":0,"metrics":null}"""
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val gateway = CloudRenderJobGateway(context, casStore, testOkHttp())

            // Health 500 throws loudly (zero fake online state)
            try {
                gateway.checkMacRigHealth(baseUrl)
                fail("Expected HTTP 500 health check to throw")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("500"))
            }

            // MIC_VOICEOVER attaches recorded WAV and omits photo when null
            val micProject = ScriptProject(
                id = "proj_mic_2",
                title = "Mic Voiceover Clip",
                voiceSource = VoiceSource.MIC_VOICEOVER,
                masterAudioSha = storedWav.sha256,
                beats = listOf(ScriptBeat(id = "b1", index = 0, spokenText = ""))
            )
            val enqueued = gateway.enqueueMacRigRender(
                rigBaseUrl = baseUrl,
                project = micProject,
                photoSha = null
            )
            val posted = capturedPostJson.get()!!
            assertEquals("MIC_VOICEOVER", posted.getString("voice_source"))
            assertTrue("photo_jpeg_base64 should be omitted when user picked no photo", !posted.has("photo_jpeg_base64"))
            assertArrayEquals(expectedWav, Base64.getDecoder().decode(posted.getString("audio_wav_base64")))

            // FAILED job propagates exact error_message and null videoUrl
            val snap = gateway.pollMacRigJob(baseUrl, enqueued.jobId)
            assertEquals(RenderJobStatus.FAILED, snap.status)
            assertEquals("bl video ref failed (rc=1): upstream error", snap.errorMessage)
            assertNull(snap.videoUrl)
        } finally {
            server.stop(0)
            db.close()
        }
    }

    @Test
    fun studioViewModelRoutesDirectToMacRigOfflineUpdatesRoomAndCachesMp4() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AircheckApplication>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)

        val expectedMp4 = sampleMp4Bytes()
        val pollCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/render-video") { exchange ->
            exchange.requestBody.readBytes()
            val resp = """{"job_id":"job_vm_mac_1","status":"QUEUED"}""".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(202, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/jobs/job_vm_mac_1") { exchange ->
            val n = pollCount.incrementAndGet()
            val resp = if (n == 1) {
                """{"job_id":"job_vm_mac_1","status":"VOICE","progress_fraction":0.3,"stage_detail":"Synthesizing voice","error_message":null,"video_url":null,"duration_ms":0,"metrics":null}"""
            } else {
                """{"job_id":"job_vm_mac_1","status":"READY","progress_fraction":1.0,"stage_detail":"Ready","error_message":null,"video_url":"/v1/renders/job_vm_mac_1.mp4","duration_ms":5400,"metrics":{"cer":0.0,"wespeaker_cosine":0.89,"dnsmos_p808":4.31}}"""
            }.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.createContext("/v1/renders/job_vm_mac_1.mp4") { exchange ->
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.sendResponseHeaders(200, expectedMp4.size.toLong())
            exchange.responseBody.use { it.write(expectedMp4) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            app.container.profileStore.setTailscaleRigUrl(baseUrl)

            val vm = StudioViewModel(app)
            val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
            var initWait = 0
            while ((vm.uiState.value.settings.tailscaleRigUrl != baseUrl || vm.uiState.value.projects.isEmpty()) && initWait < 100) {
                mainLooper.idle()
                Thread.sleep(20)
                initWait++
            }
            assertEquals(baseUrl, vm.uiState.value.settings.tailscaleRigUrl)
            assertTrue("Must work completely offline without Firebase sign-in", !vm.uiState.value.isSignedIn)

            val beatId = vm.uiState.value.activeProject.beats.first().id
            vm.updateTitle("Phone To Mac Clip")
            vm.updateBeatText(beatId, "Rendered straight from my phone on the Mac Studio rig.")
            mainLooper.idle()
            assertTrue(
                "Active beats should be populated before render",
                vm.uiState.value.activeProject.activeBeats.isNotEmpty()
            )

            vm.renderActiveProject(pollIntervalMs = 40L)

            var attempts = 0
            while (attempts < 150) {
                mainLooper.idleFor(50, TimeUnit.MILLISECONDS)
                val clip = app.container.localRepo.getClipById("job_vm_mac_1")
                if (clip != null && clip.status == RenderJobStatus.READY && !clip.finalSha.isNullOrBlank()) {
                    break
                }
                Thread.sleep(20)
                attempts++
            }
            mainLooper.idleFor(50, TimeUnit.MILLISECONDS)

            val finalClip = app.container.localRepo.getClipById("job_vm_mac_1")
            assertNotNull(
                "Clip must be persisted in Room (uiError=${vm.uiState.value.errorMessage})",
                finalClip
            )
            assertEquals(RenderJobStatus.READY, finalClip!!.status)
            assertEquals(5400L, finalClip.durationMs)
            assertEquals("$baseUrl/v1/renders/job_vm_mac_1.mp4", finalClip.remoteFinalUrl)
            assertNotNull("Finished MP4 must be cached in CasMediaStore", finalClip.finalSha)

            val cachedFile = app.container.casStore.fileForSha(finalClip.finalSha, "mp4")
            assertNotNull(cachedFile)
            assertArrayEquals(expectedMp4, cachedFile!!.readBytes())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun systemDeckDefaultHealthCheckButtonHitsLiveHttpServerWithoutCallback() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            val resp = """{"status":"ok","lora_version":2,"bundle_version":"v002","pending_preferences":0,"render_bridge":true,"active_render_jobs":0}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            composeTestRule.setContent {
                AircheckTheme(channel = BrandChannel.VOLT_LIME) {
                    SystemDeckSheet(
                        uiState = StudioUiState(settings = StudioSettings(tailscaleRigUrl = baseUrl)),
                        onSelectChannel = {},
                        onClearCasCache = {},
                        onPreviewAudio = {},
                        onClose = {}
                    )
                }
            }
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("system_deck_lazy_list")
                .performScrollToNode(hasTestTag("test_rig_connection_button"))
            composeTestRule.onNodeWithTag("test_rig_connection_button").performClick()

            composeTestRule.waitUntil(timeoutMillis = 5_000L) {
                runCatching {
                    composeTestRule.onNodeWithText("Online · LoRA v2").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            composeTestRule.onNodeWithText("Online · LoRA v2").assertIsDisplayed()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun systemDeckTestRigConnectionDisplaysRealOnlineAndErrorStates() {
        var testedUrl = ""
        val onlineState = StudioUiState(
            settings = StudioSettings(tailscaleRigUrl = "http://10.0.0.242:8443"),
            rigHealth = RigHealthUiState(
                status = RigConnectionStatus.ONLINE,
                loraVersion = 1,
                bundleVersion = "v001",
                renderBridgeAvailable = true
            )
        )

        composeTestRule.setContent {
            AircheckTheme(channel = BrandChannel.VOLT_LIME) {
                SystemDeckSheet(
                    uiState = onlineState,
                    onSelectChannel = {},
                    onClearCasCache = {},
                    onPreviewAudio = {},
                    onClose = {},
                    onTestRigConnection = { testedUrl = it }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("system_deck_lazy_list")
            .performScrollToNode(hasTestTag("test_rig_connection_button"))
        composeTestRule.onNodeWithTag("test_rig_connection_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rig_connection_status_label").assertIsDisplayed()
        composeTestRule.onNodeWithText("Online · LoRA v1").assertIsDisplayed()

        composeTestRule.onNodeWithTag("test_rig_connection_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals("http://10.0.0.242:8443", testedUrl)
    }

    @Test
    fun systemDeckDisplaysRealErrorWhenRigHealthCheckFails() {
        val errorState = StudioUiState(
            settings = StudioSettings(tailscaleRigUrl = "http://10.0.0.242:8443"),
            rigHealth = RigHealthUiState(
                status = RigConnectionStatus.ERROR,
                errorMessage = "Connection refused to 10.0.0.242:8443"
            )
        )

        composeTestRule.setContent {
            AircheckTheme(channel = BrandChannel.VOLT_LIME) {
                SystemDeckSheet(
                    uiState = errorState,
                    onSelectChannel = {},
                    onClearCasCache = {},
                    onPreviewAudio = {},
                    onClose = {},
                    onTestRigConnection = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("system_deck_lazy_list")
            .performScrollToNode(hasTestTag("rig_connection_error_label"))
        composeTestRule.onNodeWithText("Offline").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connection refused to 10.0.0.242:8443").assertIsDisplayed()
    }
}
