package com.example

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicestudio.edl.CandidateTake
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.PreferenceTelemetryRecord
import com.voicestudio.edl.PreferenceUserAction
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VerificationGateThresholds
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.WordToken
import com.voicestudio.sync.ProcessMemoryReader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * End-to-end Android instrumentation test for the rectified VoiceStudio pipeline on real Android:
 * 1. ScriptScreen -> open_clipper_button -> ClipperScreen honest missing-OTA state (zero fake pills/waveforms).
 * 2. ClipperScreen CTA -> SystemDeckSheet VoiceStudio Local Rig & OTA card (LoRA v0, Tailscale URL persistence
 *    in Proto DataStore, sync_ota_button, sync_preferences_button, live /proc/self/status VmRSS MB > 0).
 * 3. Seed real 44.1 kHz Float32 64-byte-aligned .pcm files + VoiceProjectManifest into Room WAL ->
 *    exercise WordPill take swap, GapCompressionRail mutation, and VernierScrubBar scrub ->
 *    assert AircheckDatabase persists updated selectedTakeIndex/globalGapScale AND valid
 *    PreferenceTelemetryRecord JSONL rows in voice_preferences.
 */
@RunWith(AndroidJUnit4::class)
class VoiceStudioEndToEndTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun fullVoiceStudioClipperOtaAndRoomPersistenceEndToEnd() {
        val app = composeRule.activity.application as AircheckApplication
        val db = app.container.database

        // Resolve the active project ID displayed on ScriptScreen and ensure a clean starting state.
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking {
                db.scriptDao().observeAllWithBeats().first().isNotEmpty()
            }
        }
        val activeProjectId = runBlocking {
            val preferred = app.container.profileStore.current().activeScriptId
            if (preferred.isNotBlank() && db.scriptDao().getWithBeats(preferred) != null) {
                preferred
            } else {
                db.scriptDao().observeAllWithBeats().first().first().script.id
            }
        }
        runBlocking {
            db.voiceProjectManifestDao().deleteByProjectId(activeProjectId)
            app.container.otaBundleSyncManager.refreshLocalState("")
        }
        composeRule.waitForIdle()

        // 1. Navigate from ScriptScreen -> ClipperScreen via open_clipper_button
        composeRule.onNodeWithTag("script_lazy_list")
            .performScrollToNode(hasTestTag("open_clipper_button"))
        composeRule.onNodeWithTag("open_clipper_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 2. Verify honest missing-OTA state (Constitutional Law 2: zero fake waveform or pills)
        composeRule.onNodeWithTag("clipper_screen_root").assertIsDisplayed()
        composeRule.onNodeWithTag("clipper_empty_ota_state").assertIsDisplayed()
        composeRule.onNodeWithText("Voice OTA bundle not synced").assertIsDisplayed()
        composeRule.onNodeWithTag("word_pill_row").assertDoesNotExist()
        composeRule.onNodeWithTag("vernier_waveform_canvas").assertDoesNotExist()
        composeRule.onNodeWithTag("gap_compression_rail").assertDoesNotExist()
        composeRule.onNodeWithTag("clipper_export_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("clipper_open_ota_sync_button").assertIsDisplayed().assertIsEnabled()

        captureFullWindowScreenshot(app, "01_clipper_missing_ota.png")

        // 3. Tap Sync OTA CTA -> opens SystemDeckSheet -> verify VoiceStudio Local Rig & OTA card
        composeRule.onNodeWithTag("clipper_open_ota_sync_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("system_deck_lazy_list")
            .performScrollToNode(hasTestTag("sync_ota_button"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("VoiceStudio Local Rig & OTA").assertIsDisplayed()
        composeRule.onNodeWithTag("ota_lora_version_label")
            .assertIsDisplayed()
            .assertTextContains("LoRA v0")
        composeRule.onNodeWithTag("ota_status_label").assertIsDisplayed()
        composeRule.onNodeWithTag("sync_ota_button").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag("sync_preferences_button").assertIsDisplayed().assertIsEnabled()

        // Verify live /proc/self/status VmRSS MB > 0 both from ProcessMemoryReader and rendered label
        val liveVmRssMb = ProcessMemoryReader.readVmRssMb()
        assertNotNull("Android /proc/self/status must expose VmRSS", liveVmRssMb)
        assertTrue("Measured VmRSS MB must be > 0, got $liveVmRssMb", liveVmRssMb!! > 0)
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            val labelText = composeRule.onNodeWithTag("vm_rss_label")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
            labelText.startsWith("VmRSS ") && !labelText.contains("unavailable") && !labelText.startsWith("VmRSS 0 ")
        }
        composeRule.onNodeWithTag("vm_rss_label").assertIsDisplayed()

        // Persist real Tailscale Rig URL into Proto DataStore
        val testTailscaleUrl = "http://100.64.0.42:8443"
        composeRule.onNodeWithTag("tailscale_rig_url_input").performTextClearance()
        composeRule.onNodeWithTag("tailscale_rig_url_input").performTextInput(testTailscaleUrl)
        composeRule.onNodeWithTag("save_tailscale_url_button").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            runBlocking { app.container.profileStore.current().tailscaleRigUrl == testTailscaleUrl }
        }
        assertEquals(
            testTailscaleUrl,
            runBlocking { app.container.profileStore.current().tailscaleRigUrl }
        )
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("system_deck_lazy_list")
            .performScrollToNode(hasTestTag("sync_ota_button"))
        composeRule.waitForIdle()

        captureFullWindowScreenshot(app, "02_system_deck_ota_rig.png")

        // Close SystemDeckSheet -> returns to ClipperScreen
        composeRule.onNodeWithTag("close_system_deck_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("clipper_screen_root").assertIsDisplayed()

        // 4. Seed real 44.1 kHz Float32 64-byte-aligned .pcm takes + VoiceProjectManifest into Room WAL
        val takesDir = File(app.noBackupFilesDir, "e2e_takes/$activeProjectId").apply { mkdirs() }
        val pcmC0T0 = writeRealHarmonicPcm44k(File(takesDir, "c_000_t0.pcm"), 33_075, f0Hz = 165.0, peak = 0.72f)
        val pcmC0T1 = writeRealHarmonicPcm44k(File(takesDir, "c_000_t1.pcm"), 35_280, f0Hz = 182.0, peak = 0.80f)
        val pcmC1T0 = writeRealHarmonicPcm44k(File(takesDir, "c_001_t0.pcm"), 33_075, f0Hz = 172.0, peak = 0.68f)
        val pcmC1T1 = writeRealHarmonicPcm44k(File(takesDir, "c_001_t1.pcm"), 37_485, f0Hz = 190.0, peak = 0.76f)

        val passMetrics = VerificationGateThresholds().evaluate(
            cer = 0.0f,
            maxPhonemeDurationMs = 195.0f,
            speakerCosineSim = 0.92f,
            dnsmosP808 = 4.42f,
            tailRmsDb = -54.0f,
            syllablesPerSec = 5.1f
        )

        val seededManifest = VoiceProjectManifest(
            projectId = activeProjectId,
            sampleRate = SYNTHESIS_SAMPLE_RATE_HZ,
            globalGapScale = 1.0f,
            activeLoraVersion = 3,
            clauses = listOf(
                ClauseNode(
                    clauseId = "c_000",
                    index = 0,
                    rawText = "Bare metal studio",
                    normalizedPhonemes = "b ˈɛ ɹ m ˈɛ ɾ ə l s t ˈuː d i oʊ",
                    selectedTakeIndex = 0,
                    baseGapAfterSamples = 8_820, // 200 ms @ 44.1 kHz
                    candidates = listOf(
                        CandidateTake(
                            takeId = "c_000_t0",
                            pcmFilePath = pcmC0T0.absolutePath,
                            totalSamples = 33_075L,
                            discreteAudioTokens = intArrayOf(1042, 2219, 3104, 4091),
                            seed = 1001L,
                            temperature = 0.70f,
                            metrics = passMetrics,
                            tokens = listOf(
                                WordToken("Bare", 0L, 11_025L, 0L, 11_025L, 5_512L, null),
                                WordToken("metal", 11_025L, 22_050L, 11_025L, 22_050L, 16_537L, null),
                                WordToken("studio", 22_050L, 33_075L, 22_050L, 33_075L, 27_562L, null)
                            )
                        ),
                        CandidateTake(
                            takeId = "c_000_t1",
                            pcmFilePath = pcmC0T1.absolutePath,
                            totalSamples = 35_280L,
                            discreteAudioTokens = intArrayOf(5042, 6219, 7104, 8091),
                            seed = 1002L,
                            temperature = 0.75f,
                            metrics = passMetrics,
                            tokens = listOf(
                                WordToken("Bare", 0L, 11_760L, 0L, 11_760L, 5_880L, null),
                                WordToken("metal", 11_760L, 23_520L, 11_760L, 23_520L, 17_640L, null),
                                WordToken("studio", 23_520L, 35_280L, 23_520L, 35_280L, 29_400L, null)
                            )
                        )
                    )
                ),
                ClauseNode(
                    clauseId = "c_001",
                    index = 1,
                    rawText = "voice deck live",
                    normalizedPhonemes = "v ˈɔɪ s d ˈɛ k l ˈaɪ v",
                    selectedTakeIndex = 0,
                    baseGapAfterSamples = 8_820, // 200 ms @ 44.1 kHz
                    candidates = listOf(
                        CandidateTake(
                            takeId = "c_001_t0",
                            pcmFilePath = pcmC1T0.absolutePath,
                            totalSamples = 33_075L,
                            discreteAudioTokens = intArrayOf(1101, 1202, 1303),
                            seed = 2001L,
                            temperature = 0.70f,
                            metrics = passMetrics,
                            tokens = listOf(
                                WordToken("voice", 0L, 11_025L, 0L, 11_025L, 5_512L, "vid_cut_1"),
                                WordToken("deck", 11_025L, 22_050L, 11_025L, 22_050L, 16_537L, null),
                                WordToken("live", 22_050L, 33_075L, 22_050L, 33_075L, 27_562L, null)
                            )
                        ),
                        CandidateTake(
                            takeId = "c_001_t1",
                            pcmFilePath = pcmC1T1.absolutePath,
                            totalSamples = 37_485L,
                            discreteAudioTokens = intArrayOf(2101, 2202, 2303),
                            seed = 2002L,
                            temperature = 0.74f,
                            metrics = passMetrics,
                            tokens = listOf(
                                WordToken("voice", 0L, 12_495L, 0L, 12_495L, 6_247L, "vid_cut_1"),
                                WordToken("deck", 12_495L, 24_990L, 12_495L, 24_990L, 18_742L, null),
                                WordToken("live", 24_990L, 37_485L, 24_990L, 37_485L, 31_237L, null)
                            )
                        )
                    )
                )
            )
        )

        val prefCountBefore = runBlocking { db.voicePreferenceDao().getUnsyncedCount() }
        runBlocking {
            app.container.localRepo.saveProjectManifest(seededManifest)
        }

        // Wait for live Room WAL Flow -> ClipperScreen active timeline rendering
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithTag("word_pill_row").assertIsDisplayed()
                composeRule.onNodeWithTag("vernier_waveform_canvas").assertIsDisplayed()
                composeRule.onNodeWithTag("vernier_scrub_bar").assertIsDisplayed()
                composeRule.onNodeWithTag("gap_compression_rail").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }

        // 5. Exercise WordPill take swap (T1/2 -> T2/2 on c_000)
        composeRule.onNodeWithTag("word_pill_swap_c_000_0")
            .assertIsDisplayed()
            .assertTextContains("T1/2")
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("word_pill_swap_c_000_0").assertTextContains("T2/2")

        // 6. Exercise GapCompressionRail mutation (100% -> 60% = -160ms across 400ms total gaps)
        composeRule.onNodeWithTag("gap_compression_rail")
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.60f)
            }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("gap_scale_percent").assertTextContains("60%")
        composeRule.onNodeWithTag("gap_duration_delta").assertTextContains("-160ms")

        // 7. Exercise coarse VernierScrubCanvas + fine VernierScrubBar scrub so playhead advances
        //    into active speech and splits Volt Lime (played) vs Broadcast Cobalt (upcoming) across center blade
        composeRule.onNodeWithTag("vernier_waveform_canvas").performTouchInput {
            swipeLeft(startX = right - 24f, endX = left + 24f)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vernier_scrub_bar").performTouchInput {
            swipeLeft(startX = right - 24f, endX = left + 24f)
        }
        composeRule.waitForIdle()

        captureFullWindowScreenshot(app, "03_clipper_active_timeline.png")

        // 8. Assert Room WAL persisted BOTH updated VoiceProjectManifest AND PreferenceTelemetryRecord JSONL rows
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runBlocking {
                val savedEntity = db.voiceProjectManifestDao().getByProjectId(activeProjectId)
                val prefs = db.voicePreferenceDao().listUnsynced(50)
                if (savedEntity == null || prefs.size < prefCountBefore + 2) {
                    false
                } else {
                    val decoded = json.decodeFromString(VoiceProjectManifest.serializer(), savedEntity.manifestJson)
                    val c0 = decoded.clauses.firstOrNull { it.clauseId == "c_000" }
                    c0?.selectedTakeIndex == 1 && kotlin.math.abs(decoded.globalGapScale - 0.60f) < 1e-3f
                }
            }
        }

        val savedManifestEntity = runBlocking { db.voiceProjectManifestDao().getByProjectId(activeProjectId) }
        assertNotNull("VoiceProjectManifest must persist in Room WAL", savedManifestEntity)
        val savedManifest = json.decodeFromString(
            VoiceProjectManifest.serializer(),
            savedManifestEntity!!.manifestJson
        )
        assertEquals(1, savedManifest.clauses.first { it.clauseId == "c_000" }.selectedTakeIndex)
        assertEquals(0.60f, savedManifest.globalGapScale, 1e-3f)
        assertEquals(0.60f, savedManifestEntity.globalGapScale, 1e-3f)

        val unsyncedEntities = runBlocking { db.voicePreferenceDao().listUnsynced(50) }
        assertTrue(
            "Expected at least 2 new preference rows in voice_preferences, got ${unsyncedEntities.size - prefCountBefore}",
            unsyncedEntities.size >= prefCountBefore + 2
        )
        val decodedRecords = unsyncedEntities.map { entity ->
            json.decodeFromString(PreferenceTelemetryRecord.serializer(), entity.recordJson)
        }
        val takeSwapRecord = decodedRecords.lastOrNull {
            it.clauseId == "c_000" && it.userAction == PreferenceUserAction.TAKE_SWAP
        }
        assertNotNull("Expected TAKE_SWAP row in voice_preferences", takeSwapRecord)
        assertEquals(listOf(5042, 6219, 7104, 8091), takeSwapRecord!!.chosenTokens)
        assertEquals(listOf(1042, 2219, 3104, 4091), takeSwapRecord.rejectedTokens)

        val paceEditRecord = decodedRecords.lastOrNull {
            it.userAction == PreferenceUserAction.PACE_EDIT
        }
        assertNotNull("Expected PACE_EDIT row in voice_preferences", paceEditRecord)
        assertEquals(0.60f, paceEditRecord!!.userGapScale, 1e-3f)
    }

    private fun writeRealHarmonicPcm44k(
        targetFile: File,
        totalSamples: Int,
        f0Hz: Double,
        peak: Float
    ): File {
        val alignedSamples = ((totalSamples + 15) / 16) * 16 // 64-byte alignment (16 float32s)
        val bytes = ByteArray(alignedSamples * 4)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until alignedSamples) {
            val t = i.toDouble() / SYNTHESIS_SAMPLE_RATE_HZ.toDouble()
            // Speech-like syllable amplitude envelope with clean zero crossings
            val wordEnv = 0.25 + 0.75 * kotlin.math.abs(sin(PI * 4.0 * t))
            val wave = 0.62 * sin(2.0 * PI * f0Hz * t) +
                0.26 * sin(2.0 * PI * 2.0 * f0Hz * t) +
                0.12 * sin(2.0 * PI * 3.0 * f0Hz * t)
            val sample = if (i < totalSamples) (peak * wordEnv * wave).toFloat() else 0.0f
            bb.putFloat(sample)
        }
        targetFile.writeBytes(bytes)
        return targetFile
    }

    private fun captureFullWindowScreenshot(app: AircheckApplication, filename: String) {
        composeRule.waitForIdle()
        Thread.sleep(350)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(app.getExternalFilesDir(null), "e2e_screenshots").apply { mkdirs() }
        val outFile = File(dir, filename)
        FileOutputStream(outFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()
    }
}
