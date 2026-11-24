package com.example

import android.Manifest
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
import com.example.graph.GraphPostRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test
    fun android17SeparateVoiceCareAndStockA2uiRemotionEndToEnd() {
        val app = composeRule.activity.application as AircheckApplication
        File(app.noBackupFilesDir, "voice_care").deleteRecursively()

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
        assertTrue(composeRule.onAllNodesWithText("Newsroom & Overlay Studio").fetchSemanticsNodes().isEmpty())

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

        // 3. Open Studio Settings -> open separate Voice Model Care page
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
        composeRule.onNodeWithTag("voice_care_bundles_path_text").assertIsDisplayed()
        composeRule.onNodeWithTag("voice_care_root_path_text").assertIsDisplayed()

        // Exercise Phone-Only switch -> persists in real Proto DataStore
        composeRule.onNodeWithTag("voice_care_phone_only_switch").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { !app.container.profileStore.current().phoneOnlyVoiceMode }
        }
        composeRule.onNodeWithTag("voice_care_phone_only_switch").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking { app.container.profileStore.current().phoneOnlyVoiceMode }
        }
        assertFalse(runBlocking { app.container.profileStore.current().providers.useLanVoice })

        // Verify files & run on-phone quality check
        composeRule.onNodeWithTag("voice_care_lazy_list").performScrollToKey("storage_location_card")
        composeRule.onNodeWithTag("verify_on_phone_files_button")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("voice_care_lazy_list").performScrollToKey("quality_check_card")
        composeRule.onNodeWithTag("run_voice_quality_check_button")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("voice_quality_summary_text").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("voice_quality_summary_text")
            .performScrollTo()
            .assertIsDisplayed()

        // Navigate back to Studio Settings
        composeRule.onNodeWithTag("voice_care_back_button").performClick()
        composeRule.waitForIdle()

        // 4. Start embedded loopback server for Remotion install & open separate Newsroom Studio screen
        val receivedBodies = CopyOnWriteArrayList<String>()
        val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        serverRunning.set(true)
        val port = socket.localPort

        thread(name = "remotion-e2e-http", isDaemon = true) {
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
                    if (requestLine.startsWith("POST /v1/remotion/install")) {
                        receivedBodies.add(reqBody)
                    }
                    val jsonResp = """{"status":"installed","remotion_enabled":true,"remotion_version":"4.0.242","package_provider_id":"mac_rig_remotion","user_request":"Install Remotion on rig","enable_broll_popups":true}"""
                    val respBytes = jsonResp.toByteArray(Charsets.UTF_8)
                    val httpResp = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${respBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.UTF_8) + respBytes
                    conn.getOutputStream().write(httpResp)
                    conn.getOutputStream().flush()
                }
            }
        }

        runBlocking {
            app.container.profileStore.setTailscaleRigUrl("http://127.0.0.1:$port")
        }

        composeRule.onNodeWithTag("open_newsroom_studio_button")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("Remotion Video Overlays Not Installed Yet")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Remotion Video Overlays Not Installed Yet").assertIsDisplayed()

        val promptField = composeRule.onNode(hasSetTextAction())
        promptField.performClick()
        promptField.performTextClearance()
        promptField.performTextInput("Please install Remotion for speaking pop-ups and B-roll")
        Espresso.closeSoftKeyboard()

        composeRule.onNodeWithText("Ask to Install Remotion").performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            receivedBodies.isNotEmpty() &&
                composeRule.onAllNodesWithText("Remotion News Pop-Ups & B-Roll Cue Sheet")
                    .fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(receivedBodies.first().contains("Please install Remotion for speaking pop-ups and B-roll"))
        val updatedSettings = runBlocking { app.container.profileStore.current() }
        assertEquals("mac_rig_remotion", updatedSettings.providers.packageProviderId)
        assertTrue(updatedSettings.remotionUsedCount >= 1)

        // Back out cleanly to main script deck
        composeRule.onNodeWithTag("newsroom_studio_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("close_system_deck_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tactile_drafting_deck_root").assertIsDisplayed()
    }
}
