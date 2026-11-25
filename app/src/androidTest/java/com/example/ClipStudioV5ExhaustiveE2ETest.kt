package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.ClipRecord
import com.example.data.CorePipelineSession
import com.example.data.ExportState
import com.example.data.LlmProviderChoice
import com.example.data.LlmStage
import com.example.data.RenderJobStatus
import com.example.draft.ShotSpec
import com.example.draft.ShotType
import com.example.graph.GraphContactRecord
import com.example.graph.GraphPostRecord
import com.example.ui.StudioViewModel
import com.example.ui.draft.DraftDeckViewModel
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * Exhaustive Android 17 Instrumentation E2E Suite for Clip Studio v5 covering every control
 * and state transition not already exercised in [AircheckRunningAppE2ETest]:
 * 1. Empty-DB rule (`lastCompletedVideo == null` -> Continue card strictly absent) + seeded
 *    `READY` `ClipRecord` (first without replies -> reply line omitted; then with real
 *    `LocalGraphDatabase` reply -> `94dp` 2-up `SHITPOST` / `SOLARPUNK` split panel +
 *    Follow-Up sheet `answer` / `part 2` / `update` + `"use this follow-up"`).
 * 2. `01 SPARK` Voice Note Strip (`MediaPlayer` real PCM WAV play/pause) & Note Edit sheet
 *    (`"keep last 15s"` ring-buffer distillation + manual note edit & re-outline).
 * 3. Full `03 CUT` 5-stage sequential primary button state machine (`"voice the script"` ->
 *    inline clause edit -> amber `"1 LINE NEEDS RE-VOICE"` badge + `"edited · voice not synced"` +
 *    amber `"re-voice 1 edited line"` -> `"render talking head"` -> `"render the video"` ->
 *    `ExoPlayer` monitor Play/Pause -> `"export & share"` -> `"share again"`).
 */
@RunWith(AndroidJUnit4::class)
class ClipStudioV5ExhaustiveE2ETest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val draftVm: DraftDeckViewModel
        get() = ViewModelProvider(composeRule.activity)[DraftDeckViewModel::class.java]
    private val studioVm: StudioViewModel
        get() = ViewModelProvider(composeRule.activity)[StudioViewModel::class.java]
    private val app: AircheckApplication
        get() = composeRule.activity.application as AircheckApplication

    private var httpServer: HttpServer? = null
    private val rigTasks = ConcurrentHashMap<String, String>()
    private val rigTaskSeq = AtomicInteger(0)

    @Before
    fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        runCatching { studioVm.playerController.pause() }
        httpServer?.stop(0)
        httpServer = null
        // Restore default live rig URL cleanly for subsequent manual/automated sessions
        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(false)
            app.container.profileStore.setTailscaleRigUrl("http://127.0.0.1:8443")
        }
    }

    @Test
    fun sparkEmptyDb_hidesContinueCard_andSeededReadyClipExercisesDirectionsAndFollowupSheet() {
        val screenshotDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "e2e_v5_screenshots").apply { mkdirs() }
        val previewJpeg = buildRealPreviewJpegBytes()

        val server = startLoopbackServer { port, srv ->
            srv.createContext("/v1/draft/followup") { ex ->
                val raw = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val angle = runCatching {
                    Json.parseToJsonElement(raw).jsonObject["angle"]?.jsonPrimitive?.content
                }.getOrNull() ?: "overview"

                val body = when (angle) {
                    "overview" -> """
                        {
                          "angle": "overview",
                          "target_sec": 60,
                          "word_budget": 150,
                          "answer_beats": [
                            {"label": "HOOK", "window": "0–8s", "text": "You asked whether offline AI video editing drains battery.", "shots": [{"type": "face", "prompt": "Direct studio camera"}]},
                            {"label": "TEST", "window": "8–28s", "text": "Three 60-second local cuts used 4% battery on NPU.", "shots": [{"type": "pip", "prompt": "Battery telemetry"}]},
                            {"label": "RESULT", "window": "28–46s", "text": "Uploading raw 4K clips over 5G used 7% battery.", "shots": [{"type": "broll", "prompt": "Cellular upload meter"}]},
                            {"label": "PAYOFF", "window": "46–60s", "text": "Local silicon saves both battery life and monthly fees.", "shots": [{"type": "card", "prompt": "4% offline vs 7% upload"}]}
                          ],
                          "directions": [
                            {
                              "id": "shitpost",
                              "theme": "SHITPOST",
                              "words": "calling it, cloud rent is dead",
                              "beats": [
                                {"label": "HOOK", "window": "0–8s", "text": "Calling it: monthly cloud editing rent is dead.", "shots": [{"type": "face", "prompt": "Deadpan camera"}]},
                                {"label": "BIT", "window": "8–28s", "text": "My phone cuts 4K in airplane mode while SaaS apps buffer.", "shots": [{"type": "broll", "prompt": "Airplane window"}]},
                                {"label": "TURN", "window": "28–46s", "text": "Paying twenty bucks to wait in a render queue is comedy.", "shots": [{"type": "pip", "prompt": "Queue spinner"}]},
                                {"label": "PAYOFF", "window": "46–60s", "text": "Cancel the subscription and buy lunch.", "shots": [{"type": "card", "prompt": "$0/month locally"}]}
                              ]
                            },
                            {
                              "id": "solarpunk",
                              "theme": "SOLARPUNK",
                              "words": "shared local tools win",
                              "beats": [
                                {"label": "HOOK", "window": "0–8s", "text": "Open local models turn every handset into a permanent studio.", "shots": [{"type": "face", "prompt": "Warm sunlit desk"}]},
                                {"label": "DETAIL", "window": "8–28s", "text": "Weights you own keep working offline without permission.", "shots": [{"type": "broll", "prompt": "Local model weights"}]},
                                {"label": "SHIFT", "window": "28–46s", "text": "Durable tools beat metered cloud gatekeepers.", "shots": [{"type": "pip", "prompt": "Solar bench"}]},
                                {"label": "PAYOFF", "window": "46–60s", "text": "Own the tool once and create anywhere.", "shots": [{"type": "card", "prompt": "Own your studio."}]}
                              ]
                            }
                          ]
                        }
                    """.trimIndent()
                    else -> """
                        {
                          "angle": "$angle",
                          "target_sec": 60,
                          "word_budget": 150,
                          "beats": [
                            {"label": "HOOK", "window": "0–8s", "text": "Follow-up ($angle): what changed after going offline.", "shots": [{"type": "face", "prompt": "Anchor"}]},
                            {"label": "DETAIL", "window": "8–28s", "text": "Angle $angle deep dive on local mobile editing.", "shots": [{"type": "pip", "prompt": "Timeline"}]},
                            {"label": "SHIFT", "window": "28–46s", "text": "Angle $angle comparison between NPU and cloud.", "shots": [{"type": "broll", "prompt": "Chip die"}]},
                            {"label": "PAYOFF", "window": "46–60s", "text": "Angle $angle takeaway for creators.", "shots": [{"type": "card", "prompt": "Keep editing locally."}]}
                          ]
                        }
                    """.trimIndent()
                }
                respondJson(ex, 200, body)
            }
            srv.createContext("/v1/studio/shot-preview") { ex ->
                ex.requestBody.readBytes()
                respondJson(
                    ex,
                    200,
                    """{"preview_id":"shotprev_fu","preview_url":"http://127.0.0.1:$port/v1/renders/shotprev_fu.jpg","shot_type":"broll","width":180,"height":252}"""
                )
            }
            srv.createContext("/v1/renders/shotprev_fu.jpg") { ex ->
                respondBytes(ex, 200, "image/jpeg", previewJpeg)
            }
        }
        val rigUrl = "http://127.0.0.1:${server.address.port}"

        // Clear all clips in Room & all graph posts -> Continue card MUST be strictly absent (Law 2)
        app.container.localGraphDatabase.clearAll()
        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(false)
            app.container.profileStore.setTailscaleRigUrl(rigUrl)
            app.container.profileStore.setLlmStageConfig(LlmStage.DRAFTING, LlmProviderChoice.MAC_RIG_DEFAULT)
            clearAllRoomClips()
        }

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("cs_spark_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread {
            draftVm.probeRigHealth()
            draftVm.resetNewDraft()
            studioVm.refreshLastCompletedVideo()
        }
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            studioVm.uiState.value.lastCompletedVideo == null &&
                composeRule.onAllNodesWithTag("cs_continue_card").fetchSemanticsNodes().isEmpty()
        }
        assertNull("Empty Room DB must keep lastCompletedVideo strictly null", studioVm.uiState.value.lastCompletedVideo)
        assertTrue(
            "Continue card must be absent when Room has zero READY clips",
            composeRule.onAllNodesWithTag("cs_continue_card").fetchSemanticsNodes().isEmpty()
        )

        // Seed a real READY ClipRecord in Room (with real CAS JPEG thumb, still zero replies in LocalGraphDatabase)
        val now = System.currentTimeMillis()
        val thumbStored = runBlocking { app.container.casStore.putBytes(previewJpeg, "jpg") }
        runBlocking {
            app.container.localRepo.upsertClip(
                ClipRecord(
                    id = "ready_clip_exhaustive",
                    scriptId = "",
                    jobId = "job_exhaustive",
                    title = "Your phone just killed a subscription",
                    photoSha = thumbStored.sha256,
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
                    thumbHash = thumbStored.sha256,
                    alignedWords = emptyList(),
                    chyronHeadline = "Your phone just killed a subscription",
                    chyronSubline = "",
                    remoteLipsyncUrl = null,
                    remoteFinalUrl = null,
                    exportedUri = null,
                    createdAtMillis = now - 2L * 86_400_000L,
                    updatedAtMillis = now - 2L * 86_400_000L
                )
            )
        }

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            studioVm.uiState.value.lastCompletedVideo?.clipId == "ready_clip_exhaustive" &&
                composeRule.onAllNodesWithTag("cs_continue_card").fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(
            "topReplyQuestion must be null before any replies exist",
            studioVm.uiState.value.lastCompletedVideo?.topReplyQuestion
        )

        // Open Follow-Up sheet directly -> verify reply line omitted & exercise answer / part 2 / update segments
        composeRule.onNodeWithTag("cs_continue_open_sheet").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("cs_followup_sheet").fetchSemanticsNodes().isNotEmpty() &&
                draftVm.uiState.value.followupBeats.size == 4 &&
                !draftVm.uiState.value.isDraftingFollowup
        }
        assertTrue(
            "Reply line must be omitted when topReplyQuestion == null",
            composeRule.onAllNodesWithTag("cs_followup_top_reply").fetchSemanticsNodes().isEmpty()
        )

        composeRule.onNodeWithTag("cs_followup_angle_part_2").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            !draftVm.uiState.value.isDraftingFollowup &&
                draftVm.uiState.value.followupAngle == "part_2" &&
                draftVm.uiState.value.followupBeats.firstOrNull()?.text?.contains("part_2") == true
        }

        composeRule.onNodeWithTag("cs_followup_angle_update").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            !draftVm.uiState.value.isDraftingFollowup &&
                draftVm.uiState.value.followupAngle == "update" &&
                draftVm.uiState.value.followupBeats.firstOrNull()?.text?.contains("update") == true
        }

        composeRule.onNodeWithTag("cs_followup_angle_answer").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            !draftVm.uiState.value.isDraftingFollowup &&
                draftVm.uiState.value.followupAngle == "answer"
        }

        composeRule.onNodeWithTag("cs_followup_new_idea").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_followup_sheet").fetchSemanticsNodes().isEmpty()
        }

        // Now seed a real reply question in LocalGraphDatabase -> verify 2-up SHITPOST/SOLARPUNK split panel + reply line
        app.container.localGraphDatabase.upsertContactsAndPosts(
            contacts = listOf(
                GraphContactRecord(
                    handle = "camera_nerd",
                    displayName = "Camera Nerd",
                    bio = "Mobile filmmaker",
                    followerCount = 1200,
                    mutualRepliesCount = 4,
                    lastInteractionEpochMs = now - 3_600_000L
                )
            ),
            posts = listOf(
                GraphPostRecord(
                    tweetId = "tw_battery_q",
                    handle = "camera_nerd",
                    text = "@creator Does local AI editing kill battery life on flights?",
                    createdAtEpochMs = now - 1_800_000L,
                    isReplyToUser = true
                )
            )
        )
        composeRule.runOnUiThread { studioVm.refreshLastCompletedVideo() }
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            studioVm.uiState.value.lastCompletedVideo?.topReplyQuestion?.contains("battery life") == true
        }

        // Tap Continue card to expand 94dp 2-up SHITPOST / SOLARPUNK split panel
        composeRule.onNodeWithTag("cs_continue_card").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("cs_continue_directions").fetchSemanticsNodes().isNotEmpty() &&
                draftVm.uiState.value.followupDirections.size == 2 &&
                !draftVm.uiState.value.isDraftingFollowup
        }
        composeRule.onNodeWithTag("cs_continue_dir_shitpost").assertIsDisplayed()
        composeRule.onNodeWithTag("cs_continue_dir_solarpunk").assertIsDisplayed()
        captureScreenshot(screenshotDir, "14_spark_continue_directions_v5.png")

        // Tap SOLARPUNK split card -> opens Follow-Up sheet with topReplyQuestion + SOLARPUNK beats
        composeRule.onNodeWithTag("cs_continue_dir_solarpunk").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_followup_sheet").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("cs_followup_top_reply").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_followup_top_reply").assertIsDisplayed()
        captureScreenshot(screenshotDir, "15_spark_followup_sheet_v5.png")

        // Tap "use this follow-up" -> loads 4-beat outline + previews into 01 SPARK
        composeRule.onNodeWithTag("cs_followup_use").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            draftVm.uiState.value.followupLoaded &&
                draftVm.uiState.value.document.arcBeats.size == 4 &&
                draftVm.uiState.value.shotPreviews.isNotEmpty()
        }
        composeRule.onNodeWithText("FOLLOW-UP:", substring = true).assertIsDisplayed()
        composeRule.runOnUiThread { draftVm.dismissToast() }
    }

    @Test
    fun sparkVoiceNoteAudioStrip_playsRealPcmWavViaMediaPlayer_andNoteEditSheetDistillsLast15sAndSaves() {
        val previewJpeg = buildRealPreviewJpegBytes()
        val transcribeCalls = AtomicInteger(0)

        val server = startLoopbackServer { port, srv ->
            srv.createContext("/v1/draft/transcribe") { ex ->
                ex.requestBody.readBytes()
                val n = transcribeCalls.incrementAndGet()
                respondJson(
                    ex,
                    200,
                    """{"transcript":"Distilled last 15 seconds take #$n: offline silicon beats cloud subscriptions.","duration_ms":3000}"""
                )
            }
            srv.createContext("/v1/draft/angles") { ex ->
                ex.requestBody.readBytes()
                respondJson(
                    ex,
                    200,
                    """{"extracted_angles":[{"id":"a1","label":"offline silicon","preview":"Runs locally"}],"blindspots":[{"id":"b1","label":"battery","preview":"Low power"}]}"""
                )
            }
            srv.createContext("/v1/draft/arc") { ex ->
                val raw = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val edited = raw.contains("Manually refined note")
                val hook = if (edited) {
                    "Manually refined hook: own the silicon in your pocket."
                } else {
                    "Distilled hook: offline silicon beats cloud subscriptions."
                }
                respondJson(
                    ex,
                    200,
                    """
                    {
                      "target_sec": 60,
                      "word_budget": 150,
                      "beats": [
                        {"label": "HOOK", "window": "0–8s", "text": "$hook", "shots": [{"type": "face", "prompt": "Anchor"}]},
                        {"label": "DETAIL", "window": "8–28s", "text": "Voice and captions stay on device.", "shots": [{"type": "pip", "prompt": "Waveform"}]},
                        {"label": "SHIFT", "window": "28–46s", "text": "Zero upload wait times.", "shots": [{"type": "broll", "prompt": "Phone chip"}]},
                        {"label": "PAYOFF", "window": "46–60s", "text": "Skip the monthly bill.", "shots": [{"type": "card", "prompt": "Skip the monthly bill."}]}
                      ]
                    }
                    """.trimIndent()
                )
            }
            srv.createContext("/v1/studio/shot-preview") { ex ->
                ex.requestBody.readBytes()
                respondJson(
                    ex,
                    200,
                    """{"preview_id":"shotprev_n","preview_url":"http://127.0.0.1:$port/v1/renders/shotprev_n.jpg","shot_type":"face","width":180,"height":252}"""
                )
            }
            srv.createContext("/v1/renders/shotprev_n.jpg") { ex ->
                respondBytes(ex, 200, "image/jpeg", previewJpeg)
            }
        }
        val rigUrl = "http://127.0.0.1:${server.address.port}"

        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(false)
            app.container.profileStore.setTailscaleRigUrl(rigUrl)
            app.container.profileStore.setLlmStageConfig(LlmStage.DRAFTING, LlmProviderChoice.MAC_RIG_DEFAULT)
        }

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("cs_spark_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread {
            draftVm.probeRigHealth()
            draftVm.resetNewDraft()
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_type_instead").fetchSemanticsNodes().isNotEmpty()
        }

        // Submit initial rough note to enter populated SPARK outline state
        composeRule.onNodeWithTag("cs_type_instead").performClick()
        composeRule.onNodeWithTag("cs_voice_note_editor").performTextReplacement("Initial rough idea before trailing audio distill.")
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("cs_typed_note_submit").performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            draftVm.uiState.value.document.arcBeats.size == 4 && !draftVm.uiState.value.isBusy
        }

        // Feed 3.0 seconds (30 x 100ms frames) of real 16kHz 440Hz PCM audio into VadAudioCaptureEngine
        app.container.vadAudioEngine.release()
        val frameSamples = 1_600 // 100ms @ 16kHz
        for (frameIdx in 0 until 30) {
            val pcm = ShortArray(frameSamples) { s ->
                val globalSample = frameIdx * frameSamples + s
                val t = globalSample.toDouble() / 16_000.0
                (0.45 * sin(2.0 * PI * 440.0 * t) * 32767.0).toInt().toShort()
            }
            app.container.vadAudioEngine.ingestOpenLatchFrame(pcm)
        }

        // Open Note Edit sheet (`cs_note_edit`) and tap "keep last 15s" (`cs_keep_15s`)
        composeRule.onNodeWithTag("cs_note_edit").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_note_edit_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_keep_15s").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            transcribeCalls.get() >= 1 &&
                !draftVm.uiState.value.isBusy &&
                !draftVm.uiState.value.lastSeedAudioPath.isNullOrBlank() &&
                draftVm.uiState.value.document.seedTranscript.contains("Distilled last 15 seconds")
        }
        val seedAudioFile = File(checkNotNull(draftVm.uiState.value.lastSeedAudioPath))
        assertTrue("Distilled seed WAV must exist on disk", seedAudioFile.isFile && seedAudioFile.length() > 1_000L)

        // Tap Play (`cs_note_play`) on the 01 SPARK Note Strip -> verify real Android 17 MediaPlayer playback!
        composeRule.onNodeWithTag("cs_note_play").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            draftVm.uiState.value.isPlayingSeedAudio
        }
        assertTrue("MediaPlayer must transition isPlayingSeedAudio to true", draftVm.uiState.value.isPlayingSeedAudio)

        // Tap Pause (`cs_note_play`) -> verify real MediaPlayer stops cleanly
        composeRule.onNodeWithTag("cs_note_play").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            !draftVm.uiState.value.isPlayingSeedAudio
        }
        assertFalse(draftVm.uiState.value.isPlayingSeedAudio)

        // Re-open Note Edit sheet -> edit text -> tap "save & re-outline" (`cs_note_save`)
        composeRule.onNodeWithTag("cs_note_edit").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_note_edit_sheet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_voice_note_editor").performTextReplacement(
            "Manually refined note: own the silicon in your pocket and skip recurring cloud fees."
        )
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("cs_note_save").performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            !draftVm.uiState.value.isBusy &&
                draftVm.uiState.value.document.seedTranscript.contains("Manually refined note") &&
                draftVm.uiState.value.document.arcBeats.firstOrNull()?.text?.contains("Manually refined hook") == true
        }
    }

    @Test
    fun cutSequentialPipelineStateMachine_voicesEditsRevoicesRendersPlaysAndExportsEndToEnd() {
        val screenshotDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "e2e_v5_screenshots").apply { mkdirs() }
        val realMp4File = File(app.cacheDir, "exhaustive_v5_valid.mp4")
        buildRealH264Mp4File(realMp4File, durationFrames = 27, fps = 15)
        val realMp4Bytes = realMp4File.readBytes()
        assertTrue("Generated H.264 MP4 must be non-empty", realMp4Bytes.size > 512)

        val ttsCalls = AtomicInteger(0)
        val renderCalls = AtomicInteger(0)
        val chopCalls = AtomicInteger(0)

        val server = startLoopbackServer { port, srv ->
            srv.createContext("/v1/core/tts") { ex ->
                ex.requestBody.readBytes()
                val pass = ttsCalls.incrementAndGet()
                val spoken0 = if (pass == 1) {
                    "Your phone runs neural voice models locally."
                } else {
                    "Re-voiced clause: flagship NPUs run voice models locally."
                }
                val payload = """
                    {
                      "tts_id": "v5_tts_$pass",
                      "audio_url": "http://127.0.0.1:$port/v1/renders/v5_tts_$pass.wav",
                      "duration_ms": 2400,
                      "waveform_peaks": [0.4, 0.75, 0.62, 0.88],
                      "clauses": [
                        {"id": "c0", "index": 0, "spoken_text": "$spoken0", "start_ms": 0, "end_ms": 1200},
                        {"id": "c1", "index": 1, "spoken_text": "Pay once for silicon instead of monthly cloud rent.", "start_ms": 1200, "end_ms": 2400}
                      ]
                    }
                """.trimIndent().toByteArray(Charsets.UTF_8)
                respondMaybeAsync(ex, payload)
            }
            srv.createContext("/v1/render-video") { ex ->
                ex.requestBody.readBytes()
                renderCalls.incrementAndGet()
                respondJson(
                    ex,
                    200,
                    """{"job_id":"v5_head_01","clip_id":"clip_v5_head_01","input_hash":"abcd5678"}"""
                )
            }
            srv.createContext("/v1/jobs/v5_head_01") { ex ->
                respondJson(
                    ex,
                    200,
                    """{"job_id":"v5_head_01","status":"READY","progress_fraction":1.0,"stage_detail":"Ready","video_url":"http://127.0.0.1:$port/v1/renders/v5_head_01.mp4","duration_ms":1800}"""
                )
            }
            srv.createContext("/v1/core/silence-gaps") { ex ->
                ex.requestBody.readBytes()
                respondJson(ex, 200, """{"gaps":[],"stripped_ms":0}""")
            }
            srv.createContext("/v1/core/chop-broll") { ex ->
                ex.requestBody.readBytes()
                val n = chopCalls.incrementAndGet()
                val payload = """
                    {
                      "job_id": "v5_final_$n",
                      "status": "READY",
                      "video_url": "http://127.0.0.1:$port/v1/renders/v5_final_$n.mp4",
                      "duration_ms": 1800,
                      "broll_cut_count": 1,
                      "applied_overlay_count": 1
                    }
                """.trimIndent().toByteArray(Charsets.UTF_8)
                respondMaybeAsync(ex, payload)
            }
            srv.createContext("/v1/renders/") { ex ->
                val path = ex.requestURI.path
                if (path.endsWith(".wav")) {
                    val freq = if (path.contains("_1.wav")) 360.0 else 480.0
                    respondBytes(ex, 200, "audio/wav", buildRealPcmWavBytes(freqHz = freq, durationMs = 2_400))
                } else {
                    respondBytes(ex, 200, "video/mp4", realMp4Bytes)
                }
            }
        }
        val rigUrl = "http://127.0.0.1:${server.address.port}"

        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(false)
            app.container.profileStore.setTailscaleRigUrl(rigUrl)
            app.container.localRepo.listSavedNewsroomOverlays().forEach {
                app.container.localRepo.deleteNewsroomOverlay(it.id)
            }
            app.container.localRepo.saveCorePipelineSession(CorePipelineSession())
        }

        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("cs_spark_root").fetchSemanticsNodes().isNotEmpty()
        }

        // Open a 2-clause story with a CARD shot into 03 CUT
        composeRule.runOnUiThread {
            studioVm.openStoryInCorePipeline(
                plainText = "Your phone runs neural voice models locally. Pay once for silicon instead of monthly cloud rent.",
                headline = "Own The Silicon",
                shotPlan = listOf(
                    listOf(ShotSpec(id = "s0", type = ShotType.BROLL, prompt = "Mobile NPU die")),
                    listOf(ShotSpec(id = "s1", type = ShotType.CARD, prompt = "Text card reading: 'Pay once for silicon.'"))
                )
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isNotEmpty() &&
                studioVm.uiState.value.corePipelineSession.clauses.size == 2
        }
        composeRule.runOnUiThread { studioVm.dismissNotice() }

        // Stage A: Primary key starts at "voice the script" -> tap to synthesize Stage 1 TTS
        composeRule.onNodeWithText("voice the script").assertIsDisplayed()
        composeRule.onNodeWithTag("cs_cut_primary").performClick()
        composeRule.waitUntil(timeoutMillis = 12_000L) {
            ttsCalls.get() == 1 &&
                studioVm.uiState.value.corePipelineSession.hasTtsAudio &&
                !studioVm.uiState.value.isCoreStageBusy
        }
        composeRule.onNodeWithText("render talking head").assertIsDisplayed()

        // Verify Video look `cards` tab displays real voiced startMs timestamp ("appears at 0:01 for 4 seconds.")
        composeRule.onNodeWithTag("cs_cut_look").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_newsroom_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_newsroom_tab_2").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("\"Pay once for silicon.\" appears at 0:01 for 4 seconds.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_newsroom_back").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isNotEmpty()
        }

        // Stage B: Edit clause 0 inline -> verify amber "1 LINE NEEDS RE-VOICE" badge + "edited · voice not synced" + amber primary key
        composeRule.onNodeWithTag("cs_clause_0").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("cs_clause_edit_btn_0").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("cs_clause_edit_btn_0").performClick()
        composeRule.onNodeWithTag("cs_clause_editor_0").performTextReplacement(
            "Re-voiced clause: flagship NPUs run voice models locally."
        )
        Espresso.closeSoftKeyboard()
        composeRule.onNodeWithTag("cs_clause_apply_0").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            studioVm.uiState.value.corePipelineSession.unsyncedClauseIndices == listOf(0) &&
                composeRule.onAllNodesWithTag("cs_cut_badge").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("1 LINE NEEDS RE-VOICE").assertIsDisplayed()
        composeRule.onNodeWithText("edited · voice not synced", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("re-voice 1 edited line").assertIsDisplayed()
        captureScreenshot(screenshotDir, "16_cut_dirty_revoice_v5.png")

        // Stage C: Tap amber "re-voice 1 edited line" -> clears unsynced state & advances back to "render talking head"
        composeRule.onNodeWithTag("cs_cut_primary").performClick()
        composeRule.waitUntil(timeoutMillis = 12_000L) {
            ttsCalls.get() == 2 &&
                studioVm.uiState.value.corePipelineSession.unsyncedClauseIndices.isEmpty() &&
                !studioVm.uiState.value.isCoreStageBusy
        }
        assertTrue(composeRule.onAllNodesWithTag("cs_cut_badge").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("render talking head").assertIsDisplayed()

        // Stage D: Tap "render talking head" -> runs Stage 3 & advances to "render the video"
        composeRule.onNodeWithTag("cs_cut_primary").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            renderCalls.get() == 1 &&
                studioVm.uiState.value.corePipelineSession.hasTalkingHead &&
                !studioVm.uiState.value.isCoreStageBusy
        }
        composeRule.onNodeWithText("render the video").assertIsDisplayed()

        // Stage E: Tap "render the video" -> runs Stage 4 B-roll + card chop & advances to "export & share"
        composeRule.onNodeWithTag("cs_cut_primary").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            chopCalls.get() == 1 &&
                studioVm.uiState.value.corePipelineSession.hasFinalVideo &&
                !studioVm.uiState.value.isCoreStageBusy &&
                composeRule.onAllNodesWithText("export & share").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread { studioVm.dismissNotice() }
        composeRule.waitForIdle()
        captureScreenshot(screenshotDir, "17_cut_rendered_export_v5.png")

        // Verify real ExoPlayer monitor playback (`cs_cut_play` Play -> Pause) on the valid H.264 MP4
        composeRule.onNodeWithTag("cs_cut_play").performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            studioVm.playerController.isPlaying.value
        }
        assertTrue("ExoPlayer must enter real playing state on rendered MP4", studioVm.playerController.isPlaying.value)
        Thread.sleep(350L)
        composeRule.onNodeWithTag("cs_cut_play").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            !studioVm.playerController.isPlaying.value
        }

        // Stage F: Tap "export & share" -> writes MP4 to Android 17 MediaStore & advances to "share again"
        val finalClipId = checkNotNull(studioVm.uiState.value.corePipelineSession.finalClipId)
        composeRule.onNodeWithTag("cs_cut_primary").performClick()

        val deadline = System.currentTimeMillis() + 12_000L
        while (System.currentTimeMillis() < deadline && studioVm.uiState.value.exportState != ExportState.EXPORTED) {
            Thread.sleep(150L)
        }
        assertEquals(ExportState.EXPORTED, studioVm.uiState.value.exportState)

        // Dismiss system share chooser if opened over MainActivity so Compose assertions can inspect "share again"
        Thread.sleep(500L)
        val uiAuto = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (composeRule.onAllNodesWithTag("cs_cut_root").fetchSemanticsNodes().isEmpty()) {
            uiAuto.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(400L)
        }
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithText("share again").fetchSemanticsNodes().isNotEmpty()
        }
        val exportedClip = runBlocking { app.container.localRepo.getClipById(finalClipId) }
        assertNotNull(exportedClip)
        assertTrue(
            "Exported clip must persist MediaStore content:// URI in Room",
            exportedClip!!.exportedUri?.startsWith("content://") == true
        )
    }

    private suspend fun clearAllRoomClips() {
        val dao = app.container.database.clipDao()
        dao.listReadyClips(500).forEach { dao.deleteById(it.id) }
        studioVm.uiState.value.clipCards.forEach { dao.deleteById(it.id) }
    }

    private fun startLoopbackServer(configure: (Int, HttpServer) -> Unit): HttpServer {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.executor = Executors.newCachedThreadPool()
        val port = srv.address.port
        srv.createContext("/health") { ex ->
            respondJson(ex, 200, """{"status":"ok","lora_version":5,"render_bridge":true}""")
        }
        srv.createContext("/v1/renders") { ex ->
            respondJson(ex, 200, """{"renders":[]}""")
        }
        srv.createContext("/v1/jobs/task_") { ex ->
            val id = ex.requestURI.path.substringAfterLast('/')
            val res = rigTasks[id]
            if (res == null) {
                respondJson(ex, 404, """{"error":"not_found"}""")
            } else {
                respondJson(ex, 200, """{"job_id":"$id","status":"READY","result":$res}""")
            }
        }
        configure(port, srv)
        srv.start()
        httpServer = srv
        return srv
    }

    private fun respondMaybeAsync(ex: HttpExchange, payloadBytes: ByteArray) {
        if (ex.requestURI.query?.contains("async=1") == true) {
            val id = "task_${rigTaskSeq.incrementAndGet()}"
            rigTasks[id] = payloadBytes.toString(Charsets.UTF_8)
            respondJson(ex, 202, """{"job_id":"$id","status":"RUNNING"}""")
        } else {
            respondBytes(ex, 200, "application/json; charset=utf-8", payloadBytes)
        }
    }

    private fun respondJson(ex: HttpExchange, statusCode: Int, json: String) {
        respondBytes(ex, statusCode, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }

    private fun respondBytes(ex: HttpExchange, statusCode: Int, contentType: String, bytes: ByteArray) {
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(statusCode, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun buildRealPreviewJpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(180, 252, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(24, 34, 48))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(215, 25, 33) }
        canvas.drawCircle(90f, 110f, 36f, paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun buildRealPcmWavBytes(freqHz: Double, durationMs: Int): ByteArray {
        val sampleRate = 16_000
        val totalSamples = (sampleRate * durationMs) / 1_000
        val pcmDataSize = totalSamples * 2
        val out = ByteArrayOutputStream(44 + pcmDataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcmDataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmDataSize)
        }
        out.write(header.array())
        val pcmBuf = ByteBuffer.allocate(pcmDataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate.toDouble()
            val sample = (0.45 * sin(2.0 * PI * freqHz * t) * 32767.0).toInt().toShort()
            pcmBuf.putShort(sample)
        }
        out.write(pcmBuf.array())
        return out.toByteArray()
    }

    private fun buildRealH264Mp4File(outFile: File, durationFrames: Int = 27, fps: Int = 15) {
        if (outFile.exists()) outFile.delete()
        val width = 320
        val height = 576
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false

        fun drain(endOfStream: Boolean) {
            if (endOfStream) {
                encoder.signalEndOfInputStream()
            }
            while (true) {
                val outStatus = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) break
                } else if (outStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outStatus >= 0) {
                    val encodedData = encoder.getOutputBuffer(outStatus)
                    if (encodedData != null && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outStatus, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }

        try {
            encoder.start()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (frame in 0 until durationFrames) {
                val canvas = inputSurface.lockCanvas(null)
                canvas.drawColor(Color.rgb(20, 24, 34))
                paint.color = Color.rgb(215, 25, 33)
                canvas.drawCircle(160f, 180f + (frame * 6f), 42f, paint)
                inputSurface.unlockCanvasAndPost(canvas)
                drain(endOfStream = false)
                Thread.sleep((1000L / fps).coerceAtLeast(15L))
            }
            drain(endOfStream = true)
        } finally {
            runCatching { inputSurface.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

    private fun captureScreenshot(dir: File, filename: String) {
        composeRule.waitForIdle()
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        if (bmp != null) {
            FileOutputStream(File(dir, filename)).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 92, out)
            }
            bmp.recycle()
        }
    }
}
