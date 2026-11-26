package com.example.engine

import android.app.Application
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ModelDownloadManager.Phase
import com.example.ui.screens.AicoreModelStatus
import com.example.ui.screens.OnDeviceModelsCard
import com.example.ui.theme.AircheckTheme
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Exercises the real [ModelDownloadManager] against a local HTTP issuer + object store that can
 * drop connections, ignore Range, expire URLs and serve corrupt bytes — the partial-download
 * recovery paths — and drives every control of [OnDeviceModelsCard] against the real files on disk.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ModelDownloadManagerTest {
    private val payload = Random(7).nextBytes(3 * 1024 * 1024 + 123)
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private lateinit var server: HttpServer
    private lateinit var manager: ModelDownloadManager
    private lateinit var modelsDir: File
    private val rangeHeaders = CopyOnWriteArrayList<String>()
    private val urlIssues = AtomicInteger(0)

    // Fault switches (applied to the next object request, then cleared).
    @Volatile private var dropAfterBytes: Int = -1
    @Volatile private var ignoreRange = false
    @Volatile private var forbidNext = false
    @Volatile private var manifestSha: String = ""
    @Volatile private var stallObject = false

    @Before
    fun setUp() {
        manifestSha = payloadSha
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/models/manifest") { ex ->
            json(ex, """{"id":"gemma-4-E2B-it","file_name":"gemma-4-E2B-it.litertlm","size":${payload.size},"sha256":"$manifestSha"}""")
        }
        server.createContext("/models/url") { ex ->
            urlIssues.incrementAndGet()
            json(ex, """{"url":"http://127.0.0.1:${server.address.port}/obj","expires_in":3600}""")
        }
        server.createContext("/obj") { ex -> serveObject(ex) }
        server.start()

        val app = ApplicationProvider.getApplicationContext<Application>()
        modelsDir = File(app.filesDir, "models")
        modelsDir.deleteRecursively()
        manager = newManager()
    }

    @After
    fun tearDown() {
        server.stop(0)
        modelsDir.deleteRecursively()
    }

    private fun newManager() = ModelDownloadManager(
        ApplicationProvider.getApplicationContext<Application>(),
        OkHttpClient(),
        issuerBaseUrl = "http://127.0.0.1:${server.address.port}/models",
    )

    private fun json(ex: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun serveObject(ex: HttpExchange) {
        if (forbidNext) {
            forbidNext = false
            ex.sendResponseHeaders(403, -1)
            ex.close()
            return
        }
        val range = ex.requestHeaders.getFirst("Range")
        rangeHeaders.add(range ?: "none")
        val start = if (range != null && !ignoreRange) range.removePrefix("bytes=").substringBefore('-').toInt() else 0
        ignoreRange = false
        if (start >= payload.size) {
            ex.sendResponseHeaders(416, -1)
            ex.close()
            return
        }
        val remaining = payload.size - start
        ex.sendResponseHeaders(if (start > 0) 206 else 200, remaining.toLong())
        val drop = dropAfterBytes
        dropAfterBytes = -1
        try {
            if (drop in 0 until remaining) {
                // Promise `remaining` bytes, send fewer, then cut the connection: a dropped transfer.
                ex.responseBody.write(payload, start, drop)
                ex.responseBody.flush()
                ex.close()
                return
            }
            if (stallObject) {
                ex.responseBody.write(payload, start, 1024 * 1024)
                ex.responseBody.flush()
                while (stallObject) Thread.sleep(20)
                ex.close()
                return
            }
            ex.responseBody.use { it.write(payload, start, remaining) }
        } catch (_: IOException) {
        }
    }

    private fun part() = File(modelsDir, "gemma-4-E2B-it.litertlm.part")

    @Test
    fun cleanDownload_isVerifiedAndReady() = runBlocking {
        assertEquals(Phase.NOT_DOWNLOADED, manager.state.value.phase)
        val file = manager.download()
        assertArrayEquals(payload, file.readBytes())
        assertTrue(manager.isReady())
        assertEquals(Phase.READY, manager.state.value.phase)
        assertEquals(payload.size.toLong(), manager.state.value.totalBytes)
        assertFalse(part().exists())
    }

    @Test
    fun droppedConnection_keepsPartial_andNextAttemptResumesWithRange() = runBlocking {
        dropAfterBytes = 1_000_000
        try {
            manager.download()
            fail("expected the dropped connection to surface as IOException")
        } catch (e: IOException) {
            // transient: the worker maps this to Result.retry()
        }
        val kept = part().length()
        assertTrue("partial bytes must be kept, got $kept", kept in 1 until payload.size.toLong())
        assertFalse(manager.isReady())
        manager.markStopped(error = "Connection lost")
        assertEquals(Phase.PAUSED, manager.state.value.phase)
        assertEquals(kept, manager.state.value.downloadedBytes)

        val file = manager.download()
        assertEquals("bytes=$kept-", rangeHeaders.last())
        assertArrayEquals(payload, file.readBytes())
        assertTrue(manager.isReady())
    }

    @Test
    fun processDeath_newManagerSeesPartialOnDisk_andResumes() = runBlocking {
        dropAfterBytes = 2_000_000
        runCatching { manager.download() }
        val kept = part().length()
        assertTrue(kept > 0)

        // A brand-new manager (fresh process) derives PAUSED + total size from disk alone.
        val reborn = newManager()
        assertEquals(Phase.PAUSED, reborn.state.value.phase)
        assertEquals(kept, reborn.state.value.downloadedBytes)
        assertEquals(payload.size.toLong(), reborn.state.value.totalBytes)

        val file = reborn.download()
        assertEquals("bytes=$kept-", rangeHeaders.last())
        assertArrayEquals(payload, file.readBytes())
        assertEquals(Phase.READY, newManager().state.value.phase)
    }

    @Test
    fun serverIgnoringRange_restartsFromZero_withoutCorruption() = runBlocking {
        dropAfterBytes = 500_000
        runCatching { manager.download() }
        assertTrue(part().length() > 0)
        ignoreRange = true
        val file = manager.download()
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun expiredPresignedUrl_isReissued_andDownloadCompletes() = runBlocking {
        forbidNext = true
        val file = manager.download()
        assertArrayEquals(payload, file.readBytes())
        assertTrue("a fresh URL must be requested after the 403", urlIssues.get() >= 2)
    }

    @Test
    fun checksumMismatch_discardsFile_andNeverReportsReady() = runBlocking {
        manifestSha = "0".repeat(64)
        try {
            manager.download()
            fail("expected a permanent failure")
        } catch (e: ModelDownloadManager.PermanentDownloadException) {
            assertTrue(e.message!!.contains("Checksum"))
        }
        assertFalse(manager.isReady())
        assertFalse(manager.modelFile().exists())
        assertFalse(part().exists())
        manager.markStopped(error = "Checksum mismatch", failed = true)
        assertEquals(Phase.FAILED, manager.state.value.phase)
    }

    @Test
    fun truncatedFinalFile_isNotReady() = runBlocking {
        val file = manager.download()
        file.writeBytes(payload.copyOf(100))
        assertFalse(newManager().isReady())
        assertEquals(Phase.NOT_DOWNLOADED, newManager().state.value.phase)
    }

    @Test
    fun card_everyControlDrivesTheRealDownload() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var job: Job? = null
        val start = {
            job?.cancel()
            job = scope.launch {
                try {
                    manager.download()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    manager.markStopped()
                    throw e
                } catch (e: Exception) {
                    manager.markStopped(error = e.message)
                }
            }
        }
        var draftOnPhone by androidx.compose.runtime.mutableStateOf(false)
        var voiceOnPhone by androidx.compose.runtime.mutableStateOf(false)
        setContent {
            AircheckTheme {
                val state by manager.state.collectAsState()
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier.verticalScroll(
                        androidx.compose.foundation.rememberScrollState()
                    )
                ) {
                    OnDeviceModelsCard(
                        aicore = AicoreModelStatus(checked = true, baseModelName = null),
                        download = state,
                        onDownload = { start() },
                        onPause = { job?.cancel() },
                        onDelete = { manager.delete() },
                        draftOnPhone = draftOnPhone,
                        onDraftOnPhoneChange = { draftOnPhone = it },
                        phoneVoiceInstalled = true,
                        voiceOnPhone = voiceOnPhone,
                        onVoiceOnPhoneChange = { voiceOnPhone = it },
                    )
                }
            }
        }
        onNodeWithTag("aicore_model_label").assertTextEquals("Not available")
        onNodeWithTag("gemma_phase_label").assertTextEquals("Not downloaded")
        onNodeWithTag("gemma_delete_button").assertDoesNotExist()
        // No AICore and no Gemma yet: phone drafting cannot be selected.
        onNodeWithTag("draft_location_label").assertTextEquals("Mac Studio")
        onNodeWithTag("draft_on_phone_button").assertIsNotEnabled()
        onNodeWithTag("draft_location_hint").assertExists()

        // Download -> transfer stalls mid-way -> Pause keeps the partial bytes.
        stallObject = true
        onNodeWithTag("gemma_download_button").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { manager.state.value.phase == Phase.DOWNLOADING && manager.state.value.downloadedBytes >= 1024 * 1024 }
        onNodeWithTag("gemma_phase_label").assertTextEquals("Downloading")
        onNodeWithTag("gemma_progress_bar").assertExists()
        onNodeWithTag("gemma_pause_button").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { manager.state.value.phase == Phase.PAUSED }
        stallObject = false
        val kept = part().length()
        assertTrue(kept >= 1024 * 1024)
        onNodeWithTag("gemma_phase_label").assertTextEquals("Paused")

        // Resume -> completes from the kept bytes and verifies.
        onNodeWithTag("gemma_download_button").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { manager.state.value.phase == Phase.READY }
        assertEquals("bytes=$kept-", rangeHeaders.last())
        assertArrayEquals(payload, manager.modelFile().readBytes())
        onNodeWithTag("gemma_phase_label").assertTextEquals("Ready")
        onNodeWithTag("gemma_download_button").assertDoesNotExist()

        // With verified Gemma on disk the phone can draft: switch there and back.
        onNodeWithTag("draft_location_hint").assertDoesNotExist()
        onNodeWithTag("draft_on_phone_button").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { draftOnPhone }
        onNodeWithTag("draft_location_label").assertTextEquals("This phone")
        onNodeWithTag("draft_on_mac_button").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { !draftOnPhone }
        onNodeWithTag("draft_location_label").assertTextEquals("Mac Studio")

        // Voice scripts location defaults to fast Mac Studio GPU and toggles cleanly to This phone and back.
        onNodeWithTag("voice_location_label").assertTextEquals("Mac Studio")
        onNodeWithTag("voice_on_phone_button").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { voiceOnPhone }
        onNodeWithTag("voice_location_label").assertTextEquals("This phone")
        onNodeWithTag("voice_on_mac_button").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { !voiceOnPhone }
        onNodeWithTag("voice_location_label").assertTextEquals("Mac Studio")

        // Delete -> files are gone and the card is back to Not downloaded.
        onNodeWithTag("gemma_delete_button").performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { manager.state.value.phase == Phase.NOT_DOWNLOADED }
        assertFalse(manager.modelFile().exists())
        onNodeWithTag("gemma_phase_label").assertTextEquals("Not downloaded")
        scope.cancel()
    }
}
