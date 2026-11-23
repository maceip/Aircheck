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
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.BrandChannel
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.local.AircheckDatabase
import com.example.data.local.AircheckLocalRepository
import com.example.data.local.AircheckProfileSerializer
import com.example.data.local.AircheckProfileStore
import com.example.data.local.StudioSettings
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import com.example.ui.screens.ScriptScreen
import com.example.ui.screens.SystemDeckSheet
import com.example.ui.theme.AircheckTheme
import com.voicestudio.edl.CandidateTake
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.DEFAULT_BASE_GAP_SAMPLES
import com.voicestudio.edl.OtaBundleState
import com.voicestudio.edl.OtaBundleStatus
import com.voicestudio.edl.PreferenceTelemetryRecord
import com.voicestudio.edl.PreferenceUserAction
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VerificationGateThresholds
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.WordToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceStudioIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleManifest(projectId: String): VoiceProjectManifest {
        val pass = VerificationGateThresholds().evaluate(
            cer = 0.0f,
            maxPhonemeDurationMs = 190.0f,
            speakerCosineSim = 0.92f,
            dnsmosP808 = 4.35f,
            tailRmsDb = -52.0f,
            syllablesPerSec = 5.0f
        )
        return VoiceProjectManifest(
            projectId = projectId,
            sampleRate = SYNTHESIS_SAMPLE_RATE_HZ,
            globalGapScale = 1.0f,
            activeLoraVersion = 3,
            clauses = listOf(
                ClauseNode(
                    clauseId = "c_001",
                    index = 0,
                    rawText = "Bare metal studio audio.",
                    normalizedPhonemes = "b ɛər m ɛ t ə l",
                    selectedTakeIndex = 0,
                    baseGapAfterSamples = DEFAULT_BASE_GAP_SAMPLES,
                    candidates = listOf(
                        CandidateTake(
                            takeId = "c_001_t0",
                            pcmFilePath = "takes/c_001_t0.pcm",
                            totalSamples = 44_100L,
                            discreteAudioTokens = intArrayOf(10, 20, 30),
                            seed = 1001L,
                            temperature = 0.7f,
                            metrics = pass,
                            tokens = listOf(
                                WordToken("Bare", 0L, 12_000L, 0L, 12_000L, 6_000L, null),
                                WordToken("metal", 12_000L, 24_000L, 12_000L, 24_000L, 18_000L, null)
                            )
                        ),
                        CandidateTake(
                            takeId = "c_001_t1",
                            pcmFilePath = "takes/c_001_t1.pcm",
                            totalSamples = 44_100L,
                            discreteAudioTokens = intArrayOf(11, 22, 33),
                            seed = 1002L,
                            temperature = 0.75f,
                            metrics = pass,
                            tokens = listOf(
                                WordToken("Bare", 0L, 12_000L, 0L, 12_000L, 6_000L, null),
                                WordToken("metal", 12_000L, 24_000L, 12_000L, 24_000L, 18_000L, null)
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun roomV3PersistsVoiceProjectManifestsAndPreferencesAndRunsMigration2To3() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify MIGRATION_2_3 SQL cleanly creates both v3 tables on a real SQLite v2 database.
        val tempDir = Files.createTempDirectory("aircheck_mig_test_").toFile()
        val dbFile = File(tempDir, "mig_test.db")
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbFile.absolutePath)
                    .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL("CREATE TABLE IF NOT EXISTS scripts (id TEXT NOT NULL PRIMARY KEY)")
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build()
            )
            val writable = helper.writableDatabase
            AircheckDatabase.MIGRATION_2_3.migrate(writable)
            writable.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                val tables = mutableSetOf<String>()
                while (cursor.moveToNext()) tables += cursor.getString(0)
                assertTrue("scripts v2 table must remain intact", "scripts" in tables)
                assertTrue("voice_project_manifests must be created", "voice_project_manifests" in tables)
                assertTrue("voice_preferences must be created", "voice_preferences" in tables)
            }
            helper.close()
        } finally {
            tempDir.deleteRecursively()
        }

        // 2. Verify Room v3 DAOs & AircheckLocalRepository round-trip manifests and preferences.
        val db = Room.inMemoryDatabaseBuilder(context, AircheckDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = AircheckLocalRepository(db)
            val manifest = sampleManifest("proj_room_v3")
            repo.saveProjectManifest(manifest)

            val restored = repo.getProjectManifest("proj_room_v3")
            assertEquals(manifest, restored)
            assertEquals(manifest, repo.observeVoiceManifest("proj_room_v3").first())

            val rec1 = PreferenceTelemetryRecord(
                timestamp = 1795410001L,
                clauseId = "c_001",
                loraVersion = 3,
                rawText = "Bare metal studio audio.",
                normalizedPhonemes = "b ɛər m ɛ t ə l",
                userAction = PreferenceUserAction.TAKE_SWAP,
                userGapScale = 0.8f,
                chosenTokens = listOf(11, 22, 33),
                rejectedTokens = listOf(10, 20, 30)
            )
            val rec2 = rec1.copy(
                timestamp = 1795410001L, // Same ms timestamp -> repo bumps monotonically, zero overwrite!
                userAction = PreferenceUserAction.PACE_EDIT,
                userGapScale = 0.55f
            )
            repo.appendPreference(rec1)
            repo.appendPreference(rec2)

            assertEquals(2, repo.unsyncedPreferenceCount.first())
            val pending = repo.listUnsyncedPreferences(10)
            assertEquals(2, pending.size)
            assertEquals(PreferenceUserAction.TAKE_SWAP, pending[0].userAction)
            assertEquals(PreferenceUserAction.PACE_EDIT, pending[1].userAction)

            repo.markPreferencesSynced(listOf(pending[0].timestamp))
            assertEquals(1, repo.unsyncedPreferenceCount.first())
            assertEquals(1, repo.listUnsyncedPreferences(10).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun protoDataStorePersistsTailscaleRigUrl() = runTest {
        val tempDir = Files.createTempDirectory("aircheck_proto_test_").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val pbFile = File(tempDir, "profile.pb")
            val dataStore = DataStoreFactory.create(
                serializer = AircheckProfileSerializer,
                scope = scope,
                produceFile = { pbFile }
            )
            val store = AircheckProfileStore(dataStore)
            assertEquals("", store.current().tailscaleRigUrl)

            store.setTailscaleRigUrl("http://100.88.12.4:8443")
            assertEquals("http://100.88.12.4:8443", store.current().tailscaleRigUrl)
        } finally {
            scope.cancel()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun scriptScreenExposesHairlineClipperButtonWithoutRegressingExistingTags() {
        var clipperOpened = false
        val state = StudioUiState(
            activeProject = ScriptProject(
                id = "proj_clipper_btn",
                title = "Live Segment",
                beats = listOf(ScriptBeat(id = "b1", index = 0, spokenText = "Opening line."))
            )
        )

        composeTestRule.setContent {
            AircheckTheme(channel = BrandChannel.VOLT_LIME) {
                ScriptScreen(
                    uiState = state,
                    onUpdateTitle = {},
                    onUpdateSpark = {},
                    onSetTargetDuration = {},
                    onToggleHoneSpark = {},
                    onUpdateBeatText = { _, _ -> },
                    onUpdateBeatMetadata = { _, _, _, _, _, _, _ -> },
                    onToggleExpandBeat = {},
                    onFactCheckBeat = {},
                    onShorten20Percent = {},
                    onAcceptShortProposal = {},
                    onRejectShortProposal = {},
                    onToggleCutBeat = {},
                    onToggleBeatMicRecord = {},
                    onAddBeat = {},
                    onDeleteBeat = {},
                    onDismissError = {},
                    onPrimaryAction = {},
                    onOpenClipper = { clipperOpened = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("script_lazy_list")
            .performScrollToNode(hasTestTag("open_clipper_button"))
        composeTestRule.onNodeWithTag("open_clipper_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_clipper_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Tapping Hairline Clipper button must invoke onOpenClipper", clipperOpened)
    }

    @Test
    fun systemDeckDisplaysRealOtaRigControlsLoraV0PendingPrefsAndVmRss() {
        var savedUrl = ""
        var syncedOtaUrl = ""
        var syncedPrefsUrl = ""
        var refreshedVmRss = false

        val uiState = StudioUiState(
            settings = StudioSettings(tailscaleRigUrl = ""),
            otaBundleState = OtaBundleState(
                status = OtaBundleStatus.NOT_INSTALLED,
                activeLoraVersion = 0
            ),
            unsyncedPreferenceCount = 3,
            measuredVmRssMb = 3_120
        )

        composeTestRule.setContent {
            AircheckTheme(channel = BrandChannel.VOLT_LIME) {
                SystemDeckSheet(
                    uiState = uiState,
                    onSelectChannel = {},
                    onClearCasCache = {},
                    onPreviewAudio = {},
                    onClose = {},
                    onSaveTailscaleRigUrl = { savedUrl = it },
                    onSyncOtaBundle = { syncedOtaUrl = it },
                    onSyncPreferences = { syncedPrefsUrl = it },
                    onRefreshVmRss = { refreshedVmRss = true }
                )
            }
        }
        composeTestRule.waitForIdle()
        assertTrue(refreshedVmRss)

        composeTestRule.onNodeWithTag("system_deck_lazy_list")
            .performScrollToNode(hasTestTag("sync_ota_button"))

        composeTestRule.onNodeWithText("LoRA v0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not installed").assertIsDisplayed()
        composeTestRule.onNodeWithText("VmRSS 3120 / 9500 MB").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pending_preferences_count_label").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()

        composeTestRule.onNodeWithTag("tailscale_rig_url_input")
            .performTextInput("http://100.64.1.9:8443")
        composeTestRule.onNodeWithTag("save_tailscale_url_button").performClick()
        composeTestRule.onNodeWithTag("sync_ota_button").performClick()
        composeTestRule.onNodeWithTag("sync_preferences_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("http://100.64.1.9:8443", savedUrl)
        assertEquals("http://100.64.1.9:8443", syncedOtaUrl)
        assertEquals("http://100.64.1.9:8443", syncedPrefsUrl)
    }

    @Test
    fun studioViewModelClipperMutationsPersistManifestAndEmitAllPreferenceTelemetryActions() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AircheckApplication>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)

        val projectId = "proj_vm_clipper_${System.nanoTime()}"
        val initialManifest = sampleManifest(projectId)
        app.container.localRepo.saveProjectManifest(initialManifest)

        val vm = StudioViewModel(app)
        vm.loadClipperForProject(projectId)

        // Pump Robolectric main looper while Room Flow + Dispatchers.IO populate clipperState.manifest.
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        var attempts = 0
        while (vm.uiState.value.clipperState.manifest == null && attempts < 100) {
            mainLooper.idle()
            Thread.sleep(20)
            attempts++
        }
        assertNotNull("Clipper manifest must load from Room", vm.uiState.value.clipperState.manifest)

        // 1. WordPill take swap -> persists selectedTakeIndex=1 + emits TAKE_SWAP
        vm.onClipperSwapTake("c_001", 1)
        // 2. GapCompressionRail edit -> persists globalGapScale=0.55f + emits PACE_EDIT
        vm.onClipperGlobalGapScaleChange(0.55f)
        // 3. Video anchor toggle -> persists videoAnchorId + emits VIDEO_ANCHOR_SNAP
        vm.onClipperToggleVideoAnchor("c_001", 0, "vid_c_001_w0")
        // 4. Inline text/phoneme override -> persists override + emits PHONEME_OVERRIDE + queues resynthesis
        vm.onClipperOverrideClauseText("c_001", "Bare metal Aircheck.", "b ɛər m ɛ t ə l ˈɛərˌtʃɛk")

        // Pump main looper & allow IO coroutines to flush Room WAL writes.
        var flushAttempts = 0
        while (flushAttempts < 100) {
            mainLooper.idle()
            val curPrefs = app.container.localRepo.listUnsyncedPreferences(50)
                .filter { it.clauseId == "c_001" }
                .map { it.userAction }
                .toSet()
            if (curPrefs.containsAll(
                    setOf(
                        PreferenceUserAction.TAKE_SWAP,
                        PreferenceUserAction.PACE_EDIT,
                        PreferenceUserAction.VIDEO_ANCHOR_SNAP,
                        PreferenceUserAction.PHONEME_OVERRIDE
                    )
                )
            ) {
                break
            }
            Thread.sleep(20)
            flushAttempts++
        }
        mainLooper.idle()

        val persisted = app.container.localRepo.getProjectManifest(projectId)
        assertNotNull(persisted)
        assertEquals(0.55f, persisted!!.globalGapScale, 1e-4f)
        val clause = persisted.clauses.single()
        assertEquals(1, clause.selectedTakeIndex)
        assertEquals("Bare metal Aircheck.", clause.rawText)
        assertEquals("vid_c_001_w0", clause.selectedCandidate!!.tokens[0].videoAnchorId)

        val telemetry = app.container.localRepo.listUnsyncedPreferences(50)
            .filter { it.clauseId == "c_001" }
        val actions = telemetry.map { it.userAction }.toSet()
        assertTrue("Expected TAKE_SWAP telemetry, got $actions", PreferenceUserAction.TAKE_SWAP in actions)
        assertTrue("Expected PACE_EDIT telemetry, got $actions", PreferenceUserAction.PACE_EDIT in actions)
        assertTrue("Expected VIDEO_ANCHOR_SNAP telemetry, got $actions", PreferenceUserAction.VIDEO_ANCHOR_SNAP in actions)
        assertTrue("Expected PHONEME_OVERRIDE telemetry, got $actions", PreferenceUserAction.PHONEME_OVERRIDE in actions)

        val takeSwapRecord = telemetry.first { it.userAction == PreferenceUserAction.TAKE_SWAP }
        assertEquals(listOf(11, 22, 33), takeSwapRecord.chosenTokens)
        assertEquals(listOf(10, 20, 30), takeSwapRecord.rejectedTokens)
    }

    @Test
    fun appAssetsContainZeroBundledModelBlobs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val forbiddenExts = setOf(".gguf", ".tflite", ".onnx", ".bin", ".pcm")
        fun scanAssetDir(path: String) {
            val entries = context.assets.list(path).orEmpty()
            for (name in entries) {
                val full = if (path.isEmpty()) name else "$path/$name"
                val lower = name.lowercase()
                assertTrue(
                    "Forbidden model blob bundled in APK assets: $full",
                    forbiddenExts.none { lower.endsWith(it) }
                )
                val children = context.assets.list(full).orEmpty()
                if (children.isNotEmpty()) {
                    scanAssetDir(full)
                }
            }
        }
        scanAssetDir("")
    }
}
