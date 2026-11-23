package com.voicestudio.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.sun.net.httpserver.HttpServer
import com.voicestudio.edl.CandidateTake
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.ClauseSynthesisPhase
import com.voicestudio.edl.GateFailureRecord
import com.voicestudio.edl.OtaArtifactFormat
import com.voicestudio.edl.OtaArtifactRole
import com.voicestudio.edl.OtaBundleManifest
import com.voicestudio.edl.OtaBundleStatus
import com.voicestudio.edl.OtaModelArtifact
import com.voicestudio.edl.OtaUpdateCadence
import com.voicestudio.edl.PreferenceTelemetryRecord
import com.voicestudio.edl.PreferenceUserAction
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VerificationGateThresholds
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.WordToken
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreferenceSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var tempRoot: File
    private lateinit var bundlesRoot: File
    private var server: HttpServer? = null
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempRoot = java.nio.file.Files.createTempDirectory("voicestudio_sync_test_").toFile()
        bundlesRoot = File(tempRoot, "bundles").apply { mkdirs() }
        VoiceStudioRuntimeRegistry.resetForTest()
    }

    @After
    fun tearDown() {
        server?.stop(0)
        server = null
        VoiceStudioRuntimeRegistry.resetForTest()
        tempRoot.deleteRecursively()
    }

    private class LocalOtaTestServer(
        private var currentManifest: OtaBundleManifest,
        private val artifactsByKey: MutableMap<String, ByteArray> = linkedMapOf()
    ) {
        val requestedPaths = CopyOnWriteArrayList<String>()
        val rangeHeadersSeen = CopyOnWriteArrayList<String>()
        val uploadedPreferenceBodies = CopyOnWriteArrayList<String>()
        var preferencesStatusCode: Int = 200
        private val json = Json { encodeDefaults = true }

        val httpServer: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()

            createContext("/v1/preferences") { exchange ->
                requestedPaths.add("POST ${exchange.requestURI.path}")
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                uploadedPreferenceBodies.add(body)
                val respBytes = """{"status":"accepted"}""".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(preferencesStatusCode, respBytes.size.toLong())
                exchange.responseBody.use { it.write(respBytes) }
            }

            createContext("/v1/bundles/") { exchange ->
                val path = exchange.requestURI.path
                requestedPaths.add("GET $path")
                if (path == "/v1/bundles/latest/manifest.json") {
                    val bytes = json.encodeToString(OtaBundleManifest.serializer(), currentManifest)
                        .toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    return@createContext
                }

                val remainder = path.removePrefix("/v1/bundles/")
                val payload = artifactsByKey[remainder]
                if (payload == null) {
                    val notFound = """{"error":"not_found"}""".toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(404, notFound.size.toLong())
                    exchange.responseBody.use { it.write(notFound) }
                    return@createContext
                }

                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    rangeHeadersSeen.add(rangeHeader)
                    val spec = rangeHeader.removePrefix("bytes=")
                    val parts = spec.split("-")
                    val start = parts[0].toInt()
                    val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                        parts[1].toInt().coerceAtMost(payload.size - 1)
                    } else {
                        payload.size - 1
                    }
                    if (start >= payload.size || end < start) {
                        exchange.responseHeaders.add("Content-Range", "bytes */${payload.size}")
                        exchange.sendResponseHeaders(416, -1)
                        exchange.close()
                        return@createContext
                    }
                    val slice = payload.copyOfRange(start, end + 1)
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                    exchange.sendResponseHeaders(206, slice.size.toLong())
                    exchange.responseBody.use { it.write(slice) }
                } else {
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
            }
        }

        val baseUrl: String
            get() = "http://127.0.0.1:${httpServer.address.port}"

        fun start(): LocalOtaTestServer {
            httpServer.start()
            return this
        }

        fun updateManifest(manifest: OtaBundleManifest) {
            currentManifest = manifest
        }

        fun putArtifact(bundleVersion: String, filename: String, bytes: ByteArray) {
            artifactsByKey["$bundleVersion/$filename"] = bytes
        }
    }

    private fun artifact(
        role: OtaArtifactRole,
        filename: String,
        format: OtaArtifactFormat,
        bytes: ByteArray,
        ramMb: Int,
        cadence: OtaUpdateCadence,
        shaOverride: String? = null
    ) = OtaModelArtifact(
        role = role,
        filename = filename,
        format = format,
        sha256 = shaOverride ?: OtaBundleSyncManager.computeSha256Hex(bytes),
        sizeBytes = bytes.size.toLong(),
        estimatedRamMb = ramMb,
        updateCadence = cadence
    )

    @Test
    fun otaBundleSyncManager_supportsHttpRangeResumeAndSha256Verification() = runTest {
        val basePayload = ByteArray(16_384) { idx -> ((idx * 37) and 0xFF).toByte() }
        val loraPayload = ByteArray(4_096) { idx -> ((idx * 13 + 7) and 0xFF).toByte() }
        val bundleVer = "2026.11.23-lora-v001"

        val manifest = OtaBundleManifest(
            bundleVersion = bundleVer,
            loraVersion = 1,
            createdAtUtc = "2026-11-23T00:00:00Z",
            sampleRateHz = SYNTHESIS_SAMPLE_RATE_HZ,
            foregroundRamCeilingMb = 9_500,
            artifacts = listOf(
                artifact(
                    role = OtaArtifactRole.S2_PRO_BASE_GGUF,
                    filename = "s2_pro_4b_q4_k_m.gguf",
                    format = OtaArtifactFormat.GGUF,
                    bytes = basePayload,
                    ramMb = 3_200,
                    cadence = OtaUpdateCadence.STATIC_ONCE
                ),
                artifact(
                    role = OtaArtifactRole.VOICE_LORA_GGUF,
                    filename = "voice_lora_v001.gguf",
                    format = OtaArtifactFormat.GGUF,
                    bytes = loraPayload,
                    ramMb = 64,
                    cadence = OtaUpdateCadence.NIGHTLY_DELTA
                )
            )
        )

        val testServer = LocalOtaTestServer(manifest).apply {
            putArtifact(bundleVer, "s2_pro_4b_q4_k_m.gguf", basePayload)
            putArtifact(bundleVer, "voice_lora_v001.gguf", loraPayload)
            start()
        }
        server = testServer.httpServer

        // Pre-seed a truncated .part file (first 6,000 bytes) to prove HTTP Range resume works.
        val bundleDir = File(bundlesRoot, bundleVer).apply { mkdirs() }
        val preseededPart = File(bundleDir, "s2_pro_4b_q4_k_m.gguf.part")
        preseededPart.writeBytes(basePayload.copyOfRange(0, 6_000))

        val manager = OtaBundleSyncManager(bundlesRootDir = bundlesRoot)
        val result = manager.syncFromRig(testServer.baseUrl)
        assertTrue("Sync must succeed: ${result.exceptionOrNull()}", result.isSuccess)

        // Verify Range header resumed strictly at byte 6000.
        assertTrue(
            "Expected Range: bytes=6000- header, saw ${testServer.rangeHeadersSeen}",
            testServer.rangeHeadersSeen.contains("bytes=6000-")
        )

        val installedBase = File(bundleDir, "s2_pro_4b_q4_k_m.gguf")
        val installedLora = File(bundleDir, "voice_lora_v001.gguf")
        assertTrue(installedBase.isFile)
        assertTrue(installedLora.isFile)
        assertFalse(".part file must be atomically promoted and gone", preseededPart.exists())
        assertArrayEquals(basePayload, installedBase.readBytes())
        assertArrayEquals(loraPayload, installedLora.readBytes())

        val state = manager.bundleState.value
        assertEquals(OtaBundleStatus.READY, state.status)
        assertEquals(1, state.activeLoraVersion)
        assertEquals((basePayload.size + loraPayload.size).toLong(), state.verifiedBytes)
    }

    @Test
    fun otaBundleSyncManager_rejectsCorruptSha256AndDeletesPartFile() = runTest {
        val expectedBytes = "valid-lora-weights-payload-bytes".toByteArray()
        val tamperedBytes = "CORRUPT-lora-weights-payload-XXX".toByteArray()
        assertEquals(expectedBytes.size, tamperedBytes.size)
        val bundleVer = "2026.11.23-corrupt"

        val manifest = OtaBundleManifest(
            bundleVersion = bundleVer,
            loraVersion = 2,
            createdAtUtc = "2026-11-23T00:00:00Z",
            sampleRateHz = SYNTHESIS_SAMPLE_RATE_HZ,
            foregroundRamCeilingMb = 9_500,
            artifacts = listOf(
                artifact(
                    role = OtaArtifactRole.VOICE_LORA_GGUF,
                    filename = "voice_lora_v002.gguf",
                    format = OtaArtifactFormat.GGUF,
                    bytes = expectedBytes,
                    ramMb = 64,
                    cadence = OtaUpdateCadence.NIGHTLY_DELTA
                )
            )
        )

        val testServer = LocalOtaTestServer(manifest).apply {
            // Serve tampered bytes with identical length so SHA-256 verification must catch it.
            putArtifact(bundleVer, "voice_lora_v002.gguf", tamperedBytes)
            start()
        }
        server = testServer.httpServer

        val manager = OtaBundleSyncManager(bundlesRootDir = bundlesRoot)
        val result = manager.syncFromRig(testServer.baseUrl)
        assertTrue("Corrupt SHA-256 artifact must fail verification", result.isFailure)
        assertEquals(OtaBundleStatus.CORRUPT_OR_INCOMPATIBLE, manager.bundleState.value.status)

        val bundleDir = File(bundlesRoot, bundleVer)
        assertFalse("Final artifact must NOT exist on SHA mismatch", File(bundleDir, "voice_lora_v002.gguf").exists())
        assertFalse("Corrupt .part file must be cleaned up", File(bundleDir, "voice_lora_v002.gguf.part").exists())
    }

    @Test
    fun otaBundleSyncManager_rejectsWrongSampleRateOrOverBudgetRam() = runTest {
        val bytes = ByteArray(128) { 1 }
        val badSampleRate = OtaBundleManifest(
            bundleVersion = "bad-sr",
            loraVersion = 1,
            createdAtUtc = "2026-11-23T00:00:00Z",
            sampleRateHz = 48_000,
            foregroundRamCeilingMb = 9_500,
            artifacts = listOf(
                artifact(OtaArtifactRole.VOICE_LORA_GGUF, "voice_lora_v001.gguf", OtaArtifactFormat.GGUF, bytes, 64, OtaUpdateCadence.NIGHTLY_DELTA)
            )
        )
        assertNotNull(OtaBundleSyncManager.validateManifest(badSampleRate))

        val badRamCeiling = badSampleRate.copy(
            bundleVersion = "bad-ram",
            sampleRateHz = SYNTHESIS_SAMPLE_RATE_HZ,
            foregroundRamCeilingMb = 12_000
        )
        assertNotNull(OtaBundleSyncManager.validateManifest(badRamCeiling))
    }

    @Test
    fun preferenceSyncWorker_uploadsNdjsonMarksSyncedOn2xxPullsDeltaAndHotSwapsLora() = runTest {
        val basePayload = ByteArray(2_048) { (it % 127).toByte() }
        val loraV1Payload = "GGUF_LORA_V1_WEIGHTS".toByteArray()
        val loraV2Payload = "GGUF_LORA_V2_UPDATED_WEIGHTS_35MB_DELTA".toByteArray()
        val lexiconV2Payload = """{"Aircheck":"ˈɛərˌtʃɛk"}""".toByteArray()

        val v1Manifest = OtaBundleManifest(
            bundleVersion = "2026.11.22-lora-v001",
            loraVersion = 1,
            createdAtUtc = "2026-11-22T22:00:00Z",
            artifacts = listOf(
                artifact(OtaArtifactRole.S2_PRO_BASE_GGUF, "s2_pro_4b_q4_k_m.gguf", OtaArtifactFormat.GGUF, basePayload, 3200, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.VOICE_LORA_GGUF, "voice_lora_v001.gguf", OtaArtifactFormat.GGUF, loraV1Payload, 64, OtaUpdateCadence.NIGHTLY_DELTA)
            )
        )

        val testServer = LocalOtaTestServer(v1Manifest).apply {
            putArtifact(v1Manifest.bundleVersion, "s2_pro_4b_q4_k_m.gguf", basePayload)
            putArtifact(v1Manifest.bundleVersion, "voice_lora_v001.gguf", loraV1Payload)
            start()
        }
        server = testServer.httpServer

        // 1. Initial base sync installs v1 (base GGUF + LoRA v1).
        val manager = OtaBundleSyncManager(bundlesRootDir = bundlesRoot)
        assertTrue(manager.syncFromRig(testServer.baseUrl).isSuccess)
        testServer.requestedPaths.clear()

        // 2. Rig publishes v2 with identical static base + updated LoRA v2 + lexicon.json.
        val v2Manifest = OtaBundleManifest(
            bundleVersion = "2026.11.23-lora-v002",
            loraVersion = 2,
            createdAtUtc = "2026-11-23T06:00:00Z",
            artifacts = listOf(
                artifact(OtaArtifactRole.S2_PRO_BASE_GGUF, "s2_pro_4b_q4_k_m.gguf", OtaArtifactFormat.GGUF, basePayload, 3200, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.VOICE_LORA_GGUF, "voice_lora_v002.gguf", OtaArtifactFormat.GGUF, loraV2Payload, 64, OtaUpdateCadence.NIGHTLY_DELTA),
                artifact(OtaArtifactRole.G2P_LEXICON, "lexicon.json", OtaArtifactFormat.JSON, lexiconV2Payload, 4, OtaUpdateCadence.NIGHTLY_DELTA)
            )
        )
        testServer.updateManifest(v2Manifest)
        testServer.putArtifact(v2Manifest.bundleVersion, "voice_lora_v002.gguf", loraV2Payload)
        testServer.putArtifact(v2Manifest.bundleVersion, "lexicon.json", lexiconV2Payload)

        // 3. Seed 2 unsynced PreferenceTelemetryRecords in store and capture HotSwapLora callback.
        val pref1 = PreferenceTelemetryRecord(
            timestamp = 1795409001L,
            clauseId = "c_001",
            loraVersion = 1,
            rawText = "We ripped out the cloud wrapper.",
            normalizedPhonemes = "w iː ɹ ˈɪ p t",
            userAction = PreferenceUserAction.TAKE_SWAP,
            userGapScale = 0.60f,
            chosenTokens = listOf(101, 202, 303),
            rejectedTokens = listOf(101, 999, 888),
            gateFailures = listOf(
                GateFailureRecord(seed = 42L, cer = 0.08f, reason = "SKIPPED_WORD_OR_CER", tokens = listOf(101, 777))
            )
        )
        val pref2 = PreferenceTelemetryRecord(
            timestamp = 1795409002L,
            clauseId = "c_002",
            loraVersion = 1,
            rawText = "Bare metal forty four point one kilohertz.",
            normalizedPhonemes = "b ɛər m ɛ t ə l",
            userAction = PreferenceUserAction.PACE_EDIT,
            userGapScale = 0.45f,
            chosenTokens = listOf(404, 505),
            rejectedTokens = emptyList(),
            gateFailures = emptyList()
        )

        val unsyncedList = mutableListOf(pref1, pref2)
        val syncedTimestamps = mutableListOf<Long>()
        val hotSwappedVersion = AtomicInteger(-1)
        var hotSwappedFile: File? = null

        VoiceStudioRuntimeRegistry.otaBundleSyncManager = manager
        VoiceStudioRuntimeRegistry.preferenceStore = object : SyncPreferenceStore {
            override suspend fun listUnsyncedPreferences(limit: Int): List<PreferenceTelemetryRecord> =
                unsyncedList.filterNot { it.timestamp in syncedTimestamps }.take(limit)

            override suspend fun markPreferencesSynced(timestamps: List<Long>) {
                syncedTimestamps.addAll(timestamps)
            }

            override suspend fun appendPreference(record: PreferenceTelemetryRecord) {
                unsyncedList.add(record)
            }
        }
        VoiceStudioRuntimeRegistry.loraHotSwapBridge = object : LoraHotSwapBridge {
            override suspend fun onLoraHotSwapped(loraVersion: Int, loraFile: File): Result<Boolean> {
                hotSwappedVersion.set(loraVersion)
                hotSwappedFile = loraFile
                return Result.success(true)
            }
        }

        val worker = TestListenableWorkerBuilder<PreferenceSyncWorker>(context)
            .setInputData(workDataOf(PreferenceSyncWorker.KEY_TAILSCALE_BASE_URL to testServer.baseUrl))
            .build()

        val result = worker.doWork()
        assertTrue("Expected Result.Success, got $result", result is ListenableWorker.Result.Success)
        val out = (result as ListenableWorker.Result.Success).outputData
        assertEquals(2, out.getInt(PreferenceSyncWorker.KEY_UPLOADED_COUNT, -1))
        assertEquals(2, out.getInt(PreferenceSyncWorker.KEY_NEW_LORA_VERSION, -1))
        assertTrue(out.getBoolean(PreferenceSyncWorker.KEY_LORA_HOT_SWAPPED, false))

        // Verify exact NDJSON payload format matching preference_tuple.schema.json.
        assertEquals(1, testServer.uploadedPreferenceBodies.size)
        val lines = testServer.uploadedPreferenceBodies.single().trim().lines()
        assertEquals(2, lines.size)
        val firstObj = json.parseToJsonElement(lines[0]).jsonObject
        assertEquals("c_001", firstObj["clause_id"]?.jsonPrimitive?.content)
        assertEquals("TAKE_SWAP", firstObj["user_action"]?.jsonPrimitive?.content)
        assertTrue(firstObj.containsKey("gate_failures"))

        // Verify both rows marked synced after HTTP 200.
        assertEquals(listOf(1795409001L, 1795409002L), syncedTimestamps)

        // Verify ONLY delta files downloaded — ZERO HTTP GET for static s2_pro_4b_q4_k_m.gguf!
        assertFalse(
            "Static base GGUF must NOT be re-downloaded during nightly delta pull: ${testServer.requestedPaths}",
            testServer.requestedPaths.any { it.contains("s2_pro_4b_q4_k_m.gguf") }
        )
        assertTrue(testServer.requestedPaths.any { it.contains("voice_lora_v002.gguf") })
        assertTrue(testServer.requestedPaths.any { it.contains("lexicon.json") })

        // Verify live HotSwapLora callback received verified v2 file!
        assertEquals(2, hotSwappedVersion.get())
        assertNotNull(hotSwappedFile)
        assertArrayEquals(loraV2Payload, hotSwappedFile!!.readBytes())
        // Verify static base artifact was reused into v2 directory.
        val reusedBase = File(bundlesRoot, "${v2Manifest.bundleVersion}/s2_pro_4b_q4_k_m.gguf")
        assertTrue(reusedBase.isFile)
        assertArrayEquals(basePayload, reusedBase.readBytes())
    }

    @Test
    fun preferenceSyncWorker_doesNotMarkSyncedWhenServerRejectsPayload() = runTest {
        val dummyManifest = OtaBundleManifest(
            bundleVersion = "v1",
            loraVersion = 0,
            createdAtUtc = "2026-11-23T00:00:00Z",
            artifacts = listOf(
                artifact(OtaArtifactRole.G2P_LEXICON, "lexicon.json", OtaArtifactFormat.JSON, "{}".toByteArray(), 1, OtaUpdateCadence.NIGHTLY_DELTA)
            )
        )
        val testServer = LocalOtaTestServer(dummyManifest).apply {
            preferencesStatusCode = 400
            start()
        }
        server = testServer.httpServer

        val synced = mutableListOf<Long>()
        val rec = PreferenceTelemetryRecord(
            timestamp = 1795409999L,
            clauseId = "c_001",
            loraVersion = 0,
            rawText = "Test",
            normalizedPhonemes = "t ɛ s t",
            userAction = PreferenceUserAction.TAKE_SWAP,
            userGapScale = 1.0f,
            chosenTokens = listOf(1),
            rejectedTokens = listOf(2)
        )
        VoiceStudioRuntimeRegistry.otaBundleSyncManager = OtaBundleSyncManager(bundlesRootDir = bundlesRoot)
        VoiceStudioRuntimeRegistry.preferenceStore = object : SyncPreferenceStore {
            override suspend fun listUnsyncedPreferences(limit: Int) = listOf(rec)
            override suspend fun markPreferencesSynced(timestamps: List<Long>) {
                synced.addAll(timestamps)
            }
            override suspend fun appendPreference(record: PreferenceTelemetryRecord) {}
        }

        val worker = TestListenableWorkerBuilder<PreferenceSyncWorker>(context)
            .setInputData(workDataOf(PreferenceSyncWorker.KEY_TAILSCALE_BASE_URL to testServer.baseUrl))
            .build()

        val result = worker.doWork()
        assertTrue("HTTP 400 must fail worker cleanly", result is ListenableWorker.Result.Failure)
        assertTrue("Records MUST NOT be marked synced on non-2xx", synced.isEmpty())
    }

    @Test
    fun voiceSynthesisWorker_failsCleanlyWhenOtaBundleMissingWithZeroFabrication() = runTest {
        var persistedManifest: VoiceProjectManifest? = null
        VoiceStudioRuntimeRegistry.otaBundleSyncManager = OtaBundleSyncManager(bundlesRootDir = bundlesRoot)
        VoiceStudioRuntimeRegistry.projectStore = object : SyncProjectStore {
            override suspend fun getProjectManifest(projectId: String): VoiceProjectManifest? = persistedManifest
            override suspend fun getScriptDraftForSynthesis(projectId: String) =
                ScriptSynthesisDraft(projectId, "Zero fabrication check for local voice synthesis.")
            override suspend fun saveProjectManifest(manifest: VoiceProjectManifest) {
                persistedManifest = manifest
            }
        }

        val worker = TestListenableWorkerBuilder<VoiceSynthesisWorker>(context)
            .setInputData(workDataOf(VoiceSynthesisWorker.KEY_PROJECT_ID to "proj_missing_ota"))
            .build()

        val result = worker.doWork()
        assertTrue("Worker must fail cleanly when OTA bundle is absent", result is ListenableWorker.Result.Failure)
        val failureData = (result as ListenableWorker.Result.Failure).outputData
        val errorMsg = failureData.getString(VoiceSynthesisWorker.KEY_ERROR_MESSAGE)
        assertNotNull(errorMsg)
        assertTrue("Error must explain missing OTA bundle: $errorMsg", errorMsg!!.contains("OTA", ignoreCase = true))
        assertNull("ZERO fabricated manifest may be persisted when OTA bundle is absent", persistedManifest)
        assertEquals(ClauseSynthesisPhase.FAILED, VoiceStudioRuntimeRegistry.synthesisProgressFlow.value.phase)
    }

    @Test
    fun voiceSynthesisWorker_abortsCleanlyWhenVmRssApproachesCeiling() = runTest {
        // 9,400 MB exceeds safety threshold (9,500 - 512 = 8,988 MB).
        VoiceStudioRuntimeRegistry.vmRssReader = { 9_400 }
        VoiceStudioRuntimeRegistry.otaBundleSyncManager = OtaBundleSyncManager(bundlesRootDir = bundlesRoot)

        val worker = TestListenableWorkerBuilder<VoiceSynthesisWorker>(context)
            .setInputData(workDataOf(VoiceSynthesisWorker.KEY_PROJECT_ID to "proj_oom_guard"))
            .build()

        val result = worker.doWork()
        assertTrue("Worker must abort cleanly near 9,500 MB ceiling", result is ListenableWorker.Result.Failure)
        val err = (result as ListenableWorker.Result.Failure).outputData
            .getString(VoiceSynthesisWorker.KEY_ERROR_MESSAGE).orEmpty()
        assertTrue("Error must cite VmRSS ceiling: $err", err.contains("VmRSS") && err.contains("9500"))
    }

    @Test
    fun voiceSynthesisWorker_normalizesExecutesEvictsAndPersistsManifest() = runTest {
        val bundleVer = "2026.11.23-full"
        val bundleDir = File(bundlesRoot, bundleVer).apply { mkdirs() }
        val sherpaDir = File(bundleDir, OtaBundleSyncManager.SHERPA_CTC_EXTRACTED_DIR).apply { mkdirs() }
        File(sherpaDir, "tokens.txt").writeText("<blk> 0\na 1\n")
        File(sherpaDir, "model.int8.onnx").writeBytes(ByteArray(32) { 1 })

        fun writeBlob(name: String, content: ByteArray = ByteArray(64) { 2 }): ByteArray {
            File(bundleDir, name).writeBytes(content)
            return content
        }

        val manifest = OtaBundleManifest(
            bundleVersion = bundleVer,
            loraVersion = 5,
            createdAtUtc = "2026-11-23T06:00:00Z",
            artifacts = listOf(
                artifact(OtaArtifactRole.S2_PRO_BASE_GGUF, "s2_pro_4b_q4_k_m.gguf", OtaArtifactFormat.GGUF, writeBlob("s2_pro_4b_q4_k_m.gguf"), 3200, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.VOICE_LORA_GGUF, "voice_lora_v005.gguf", OtaArtifactFormat.GGUF, writeBlob("voice_lora_v005.gguf"), 64, OtaUpdateCadence.NIGHTLY_DELTA),
                artifact(OtaArtifactRole.CODEC_DECODER_TFLITE, "codec_decoder_fp16.tflite", OtaArtifactFormat.TFLITE, writeBlob("codec_decoder_fp16.tflite"), 420, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.SHERPA_CTC_ALIGNER, "sherpa_ctc.tar.gz", OtaArtifactFormat.TAR_GZ, writeBlob("sherpa_ctc.tar.gz"), 96, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.WESPEAKER_RESNET34_ONNX, "wespeaker_resnet34.onnx", OtaArtifactFormat.ONNX, writeBlob("wespeaker_resnet34.onnx"), 80, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.DNSMOS_P808_ONNX, "dnsmos_p808.onnx", OtaArtifactFormat.ONNX, writeBlob("dnsmos_p808.onnx"), 24, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.MASTER_CENTROID_BIN, "master_centroid.bin", OtaArtifactFormat.BIN, writeBlob("master_centroid.bin", ByteArray(1024) { 1 }), 4, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.ROOM_IR_PCM, "room_ir_44k.pcm", OtaArtifactFormat.PCM, writeBlob("room_ir_44k.pcm"), 4, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.ROOM_TONE_PCM, "room_tone_44k.pcm", OtaArtifactFormat.PCM, writeBlob("room_tone_44k.pcm"), 4, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.G2P_LEXICON, "lexicon.json", OtaArtifactFormat.JSON, writeBlob("lexicon.json", """{"Aircheck":"ˈɛərˌtʃɛk"}""".toByteArray()), 4, OtaUpdateCadence.NIGHTLY_DELTA)
            )
        )
        File(bundleDir, OtaBundleSyncManager.MANIFEST_FILENAME)
            .writeText(json.encodeToString(OtaBundleManifest.serializer(), manifest))
        File(bundlesRoot, OtaBundleSyncManager.ACTIVE_BUNDLE_POINTER_FILENAME).writeText(bundleVer)

        var savedManifest: VoiceProjectManifest? = null
        val executorInvoked = AtomicBoolean(false)

        VoiceStudioRuntimeRegistry.vmRssReader = { 2_450 }
        VoiceStudioRuntimeRegistry.otaBundleSyncManager = OtaBundleSyncManager(bundlesRootDir = bundlesRoot)
        VoiceStudioRuntimeRegistry.projectStore = object : SyncProjectStore {
            override suspend fun getProjectManifest(projectId: String): VoiceProjectManifest? = savedManifest
            override suspend fun getScriptDraftForSynthesis(projectId: String) = ScriptSynthesisDraft(
                projectId = projectId,
                rawDraftText = "Aircheck runs locally at 44.1 kHz. Zero cloud wrappers remain."
            )
            override suspend fun saveProjectManifest(manifest: VoiceProjectManifest) {
                savedManifest = manifest
            }
        }
        VoiceStudioRuntimeRegistry.synthesisExecutorFactory = {
            object : VoiceSynthesisExecutor {
                override suspend fun synthesizeClauses(
                    projectId: String,
                    clauses: List<ClauseNode>,
                    bundle: ResolvedOtaBundlePaths,
                    outputRootDir: File,
                    numCandidates: Int,
                    onClauseProgress: suspend (Int, Int, String, ClauseSynthesisPhase) -> Unit
                ): Result<ClauseSynthesisExecutionResult> {
                    executorInvoked.set(true)
                    assertEquals(2, clauses.size)
                    assertTrue(clauses[0].normalizedPhonemes.contains("ˈɛərˌtʃɛk"))
                    val passMetrics = VerificationGateThresholds().evaluate(
                        cer = 0f,
                        maxPhonemeDurationMs = 190f,
                        speakerCosineSim = 0.92f,
                        dnsmosP808 = 4.35f,
                        tailRmsDb = -54f,
                        syllablesPerSec = 5.0f
                    )
                    val updated = clauses.mapIndexed { idx, clause ->
                        onClauseProgress(idx, clauses.size, clause.clauseId, ClauseSynthesisPhase.STAGE_A_GGUF_AND_LITERT_DAC)
                        clause.copy(
                            candidates = listOf(
                                CandidateTake(
                                    takeId = "${clause.clauseId}_t0",
                                    pcmFilePath = File(outputRootDir, "${clause.clauseId}_t0.pcm").absolutePath,
                                    totalSamples = 44_100L,
                                    discreteAudioTokens = intArrayOf(11, 22, 33),
                                    seed = 100L + idx,
                                    temperature = 0.7f,
                                    metrics = passMetrics,
                                    tokens = listOf(
                                        WordToken("Aircheck", 0L, 22_050L, 0L, 22_050L, 11_000L, null)
                                    )
                                )
                            )
                        )
                    }
                    return Result.success(ClauseSynthesisExecutionResult(updatedClauses = updated, evictedBytes = 4096L))
                }
            }
        }

        val worker = TestListenableWorkerBuilder<VoiceSynthesisWorker>(context)
            .setInputData(workDataOf(VoiceSynthesisWorker.KEY_PROJECT_ID to "proj_ok"))
            .build()

        val result = worker.doWork()
        assertTrue("Expected Result.Success, got $result", result is ListenableWorker.Result.Success)
        assertTrue(executorInvoked.get())
        assertNotNull(savedManifest)
        assertEquals(5, savedManifest!!.activeLoraVersion)
        assertEquals(2, savedManifest!!.clauses.size)
        assertEquals(ClauseSynthesisPhase.READY, VoiceStudioRuntimeRegistry.synthesisProgressFlow.value.phase)
        assertEquals(2_450, VoiceStudioRuntimeRegistry.synthesisProgressFlow.value.measuredRssMb)
    }

    @Test
    fun processMemoryReader_parsesProcSelfStatusVmRssAccurately() {
        val sampleProcStatus = """
            Name:   com.aistudio.aircheck
            VmPeak:  6120448 kB
            VmSize:  5900120 kB
            VmRSS:   3145728 kB
            Threads: 42
        """.trimIndent()
        assertEquals(3_072, ProcessMemoryReader.parseVmRssMb(sampleProcStatus))
        assertFalse(ProcessMemoryReader.isApproachingCeiling(3_072))
        assertTrue(ProcessMemoryReader.isApproachingCeiling(9_100))
        assertFalse(ProcessMemoryReader.isApproachingCeiling(null))
    }
}
