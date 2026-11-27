package com.example.engine

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AlignedWord
import com.example.data.BrollSegmentMode
import com.example.data.CorePipelineSession
import com.example.data.CorePipelineStage
import com.example.data.CoreRenderOptions
import com.example.data.CoreStoryClause
import com.example.data.ClipTransitionType
import com.example.data.NewsroomLook
import com.example.data.RenderJobStatus
import com.example.data.RigQualityMetrics
import com.example.data.SavedNewsroomOverlay
import com.example.data.ScriptProject
import com.example.data.SilenceGap
import com.example.data.StudioCutStats
import com.example.data.gateway.BackdropPlateResult
import com.example.data.gateway.ChoppedBrollResult
import com.example.data.gateway.CoreTtsSynthesisResult
import com.example.data.gateway.EnqueuedRenderResult
import com.example.data.gateway.GeneratedOverlayBriefResult
import com.example.data.gateway.MacRigHealthResult
import com.example.data.gateway.MacRigJobSnapshot
import com.example.data.gateway.MacRigRenderGateway
import com.example.data.gateway.RemoteRenderSummary
import com.example.data.gateway.RemotionInstallResult
import com.example.data.gateway.RigJobNotFoundException
import com.example.data.gateway.RigTaskHandle
import com.example.data.gateway.SilenceGapsResult
import com.example.data.local.AircheckDatabase
import com.example.data.local.AircheckLocalRepository
import com.example.data.local.AircheckProfileSerializer
import com.example.data.local.AircheckProfileStore
import com.example.data.local.CasMediaStore
import com.example.data.local.CasStoredAsset
import com.voicestudio.edl.CandidateTake
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.ClauseSynthesisPhase
import com.voicestudio.edl.GateMetrics
import com.voicestudio.edl.OtaArtifactFormat
import com.voicestudio.edl.OtaArtifactRole
import com.voicestudio.edl.OtaBundleManifest
import com.voicestudio.edl.OtaModelArtifact
import com.voicestudio.edl.OtaUpdateCadence
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VerificationGateThresholds
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.WordToken
import com.voicestudio.sync.ClauseSynthesisExecutionResult
import com.voicestudio.sync.OtaBundleSyncManager
import com.voicestudio.sync.ResolvedOtaBundlePaths
import com.voicestudio.sync.VoiceStudioRuntimeRegistry
import com.voicestudio.sync.VoiceStudioSyncCoordinator
import com.voicestudio.sync.VoiceSynthesisExecutor
import com.voicestudio.verifier.AudioDspMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class CoreStoryPipelineCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AircheckDatabase
    private lateinit var localRepo: AircheckLocalRepository
    private lateinit var casStore: CasMediaStore
    private lateinit var profileScope: CoroutineScope
    private lateinit var profileStore: AircheckProfileStore
    private lateinit var bundlesRoot: File
    private lateinit var otaManager: OtaBundleSyncManager
    private lateinit var voiceCoordinator: VoiceStudioSyncCoordinator
    private lateinit var fakeGateway: FakeMacRigGateway
    private val manifestJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VoiceStudioRuntimeRegistry.resetForTest()
        db = Room.inMemoryDatabaseBuilder(context, AircheckDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        localRepo = AircheckLocalRepository(db)
        casStore = CasMediaStore(context, db.casDao())
        profileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pbFile = tempFolder.newFile("test_profile_${System.nanoTime()}.pb")
        pbFile.delete()
        profileStore = AircheckProfileStore(
            DataStoreFactory.create(
                serializer = AircheckProfileSerializer,
                scope = profileScope,
                produceFile = { pbFile }
            )
        )
        bundlesRoot = tempFolder.newFolder("bundles_coord")
        otaManager = OtaBundleSyncManager(bundlesRootDir = bundlesRoot)
        voiceCoordinator = VoiceStudioSyncCoordinator(context, otaManager)
        VoiceStudioRuntimeRegistry.otaBundleSyncManager = otaManager
        VoiceStudioRuntimeRegistry.projectStore = localRepo
        VoiceStudioRuntimeRegistry.preferenceStore = localRepo
        fakeGateway = FakeMacRigGateway(casStore, tempFolder)
    }

    @After
    fun tearDown() {
        VoiceStudioRuntimeRegistry.resetForTest()
        profileScope.cancel()
        db.close()
    }

    private fun buildCoordinator(
        localCtcAligner: (suspend (File, String) -> List<AlignedWord>)? = null
    ): CoreStoryPipelineCoordinator = CoreStoryPipelineCoordinator(
        localRepo = localRepo,
        casStore = casStore,
        macRigGateway = fakeGateway,
        profileStore = profileStore,
        otaBundleSyncManager = otaManager,
        voiceStudioCoordinator = voiceCoordinator,
        localCtcAligner = localCtcAligner
    )

    @Test
    fun stage3_allows60PlusSeconds_upTo75Seconds() = runTest {
        profileStore.setPhoneOnlyVoiceMode(false)
        val coordinator = buildCoordinator()
        val wavFile = tempFolder.newFile("voice_62s.wav")
        CoreStoryPipelineCoordinator.writeFloat32PcmAs16BitMonoWav(
            FloatArray(SYNTHESIS_SAMPLE_RATE_HZ),
            wavFile
        )
        val storedWav = casStore.putFile(wavFile, "wav")
        val session = CorePipelineSession(
            id = "pipe_60s",
            scriptId = "script_60s",
            title = "60s Broadcast",
            stage = CorePipelineStage.ITERATE_STORY,
            clauses = listOf(
                CoreStoryClause(id = "c0", index = 0, spokenText = "First thirty seconds.", startMs = 0L, endMs = 31_000L),
                CoreStoryClause(id = "c1", index = 1, spokenText = "Second thirty seconds.", startMs = 31_000L, endMs = 62_500L)
            ),
            ttsVoiceSha = storedWav.sha256,
            ttsDurationMs = 62_500L,
            voicedClauseTexts = listOf("First thirty seconds.", "Second thirty seconds.")
        )

        val res = coordinator.matchSpeechToTalkingHeadStage3(
            session = session,
            rigBaseUrl = "http://127.0.0.1:8443",
            photoSha = null,
            pollIntervalMs = 25L
        )
        assertTrue(res.isSuccess)
        assertEquals(63, fakeGateway.lastEnqueuedMaxDurationSec)
    }

    @Test
    fun stage3_recoversOnceOn404AndRigRestarted_andFailsCleanlyOnSecond404() = runTest {
        profileStore.setPhoneOnlyVoiceMode(false)
        val coordinator = buildCoordinator()
        val wavFile = tempFolder.newFile("voice_rec.wav")
        CoreStoryPipelineCoordinator.writeFloat32PcmAs16BitMonoWav(FloatArray(22_050), wavFile)
        val storedWav = casStore.putFile(wavFile, "wav")

        val baseSession = CorePipelineSession(
            id = "pipe_rec",
            scriptId = "script_rec",
            stage = CorePipelineStage.ITERATE_STORY,
            clauses = listOf(CoreStoryClause(id = "c0", index = 0, spokenText = "Hello.", startMs = 0L, endMs = 1_000L)),
            ttsVoiceSha = storedWav.sha256,
            ttsDurationMs = 1_000L,
            pendingHeadJobId = "stale_404_job"
        )
        localRepo.saveCorePipelineSession(baseSession)

        // Case A: stale_404_job throws RigJobNotFoundException -> clears pendingHeadJobId, re-enqueues once, succeeds
        fakeGateway.pollResponder = { jobId ->
            if (jobId == "stale_404_job") throw RigJobNotFoundException("404 lost")
            fakeGateway.defaultReadySnapshot(jobId)
        }
        val resA = coordinator.matchSpeechToTalkingHeadStage3(baseSession, "http://127.0.0.1:8443", null, pollIntervalMs = 25L)
        assertTrue(resA.isSuccess)
        assertEquals(1, fakeGateway.enqueueCount)
        assertNull(resA.getOrThrow().pendingHeadJobId)

        // Case B: stale_restarted_job returns FAILED with errorKind="rig_restarted" -> re-enqueues once, succeeds
        fakeGateway.enqueueCount = 0
        val restartedSession = baseSession.copy(pendingHeadJobId = "stale_restarted_job")
        fakeGateway.pollResponder = { jobId ->
            if (jobId == "stale_restarted_job") {
                MacRigJobSnapshot(
                    jobId = jobId,
                    status = RenderJobStatus.FAILED,
                    progressFraction = 0f,
                    stageDetail = "restarted",
                    errorMessage = "rig restarted",
                    videoUrl = null,
                    durationMs = 0L,
                    errorKind = "rig_restarted"
                )
            } else {
                fakeGateway.defaultReadySnapshot(jobId)
            }
        }
        val resB = coordinator.matchSpeechToTalkingHeadStage3(restartedSession, "http://127.0.0.1:8443", null, pollIntervalMs = 25L)
        assertTrue(resB.isSuccess)
        assertEquals(1, fakeGateway.enqueueCount)

        // Case C: both initial and retried job throw RigJobNotFoundException -> clears pendingHeadJobId in Room & fails
        fakeGateway.enqueueCount = 0
        fakeGateway.pollResponder = { jobId -> throw RigJobNotFoundException("404 $jobId") }
        val resC = coordinator.matchSpeechToTalkingHeadStage3(baseSession, "http://127.0.0.1:8443", null, pollIntervalMs = 25L)
        assertTrue(resC.isFailure)
        assertEquals(1, fakeGateway.enqueueCount)
        assertNull(localRepo.getLatestCorePipelineSession()!!.pendingHeadJobId)
    }

    @Test
    fun stage1_clearsStaleDownstreamState_andPopulatesVoicedClauseTexts() = runTest {
        profileStore.setPhoneOnlyVoiceMode(false)
        val coordinator = buildCoordinator()
        val staleSession = CorePipelineSession(
            id = "pipe_stale",
            scriptId = "script_stale",
            stage = CorePipelineStage.FINAL_REVIEW,
            clauses = listOf(CoreStoryClause(id = "c0", index = 0, spokenText = "Updated line.")),
            talkingHeadJobId = "old_head",
            pendingHeadJobId = "old_pending_head",
            talkingHeadSha = "old_head_sha",
            talkingHeadVideoUrl = "http://127.0.0.1:8443/old.mp4",
            silenceGaps = listOf(SilenceGap(100L, 400L)),
            silenceGapsJobId = "old_head",
            finalClipId = "old_clip",
            finalVideoSha = "old_final_sha",
            finalVideoUrl = "http://127.0.0.1:8443/final.mp4",
            lastCutStats = StudioCutStats(brollCutCount = 2),
            lastCutSignature = "old_sig",
            headMetrics = RigQualityMetrics(cer = 0.01)
        )

        val res = coordinator.synthesizeStage1Tts(staleSession, "http://127.0.0.1:8443")
        assertTrue(res.isSuccess)
        val updated = res.getOrThrow()
        assertNull(updated.talkingHeadJobId)
        assertNull(updated.pendingHeadJobId)
        assertNull(updated.talkingHeadSha)
        assertNull(updated.talkingHeadVideoUrl)
        assertTrue(updated.silenceGaps.isEmpty())
        assertNull(updated.silenceGapsJobId)
        assertNull(updated.finalClipId)
        assertNull(updated.finalVideoSha)
        assertNull(updated.finalVideoUrl)
        assertNull(updated.lastCutStats)
        assertNull(updated.lastCutSignature)
        assertNull(updated.headMetrics)
        assertEquals(listOf("Updated line."), updated.voicedClauseTexts)
    }

    @Test
    fun stage3_remoteWithoutWords_neverFabricatesUniformWordsOrFakeMetrics() = runTest {
        profileStore.setPhoneOnlyVoiceMode(false)
        val coordinator = buildCoordinator()
        val wavFile = tempFolder.newFile("remote_tts.wav")
        CoreStoryPipelineCoordinator.writeFloat32PcmAs16BitMonoWav(FloatArray(44_100), wavFile)
        val storedWav = casStore.putFile(wavFile, "wav")

        val session = CorePipelineSession(
            id = "pipe_no_words",
            scriptId = "script_no_words",
            stage = CorePipelineStage.ITERATE_STORY,
            clauses = listOf(CoreStoryClause(id = "c0", index = 0, spokenText = "One two three four.", startMs = 0L, endMs = 2000L)),
            ttsVoiceSha = storedWav.sha256,
            ttsDurationMs = 2000L
        )
        fakeGateway.pollResponder = { jobId ->
            MacRigJobSnapshot(
                jobId = jobId,
                status = RenderJobStatus.READY,
                progressFraction = 1f,
                stageDetail = "Done",
                errorMessage = null,
                videoUrl = "http://127.0.0.1:8443/v1/renders/$jobId.mp4",
                durationMs = 2000L,
                words = emptyList(),
                metrics = null
            )
        }

        val res = coordinator.matchSpeechToTalkingHeadStage3(session, "http://127.0.0.1:8443", null, pollIntervalMs = 25L)
        assertTrue(res.isSuccess)
        val updated = res.getOrThrow()
        assertTrue("Must not fabricate uniform word timings", updated.alignedWords.isEmpty())
        val manifest = localRepo.getProjectManifest("script_no_words")!!
        val take = manifest.clauses.first().selectedCandidate!!
        assertEquals("", take.pcmFilePath) // Never MP4 path!
        assertFalse(take.metrics.passedAllGates) // Never hardcoded true
        assertNotEquals(0.92f, take.metrics.speakerCosineSim, 1e-3f)
        assertNotEquals(4.25f, take.metrics.dnsmosP808, 1e-3f)
    }

    @Test
    fun micWav_leavesAlignedWordsEmptyWithoutV002_andUsesLocalCtcWhenPresent() = runTest {
        val micWav = tempFolder.newFile("mic.wav")
        CoreStoryPipelineCoordinator.writeFloat32PcmAs16BitMonoWav(FloatArray(22_050), micWav)

        // Without localCtcAligner / v002: mic succeeds and leaves alignedWords empty
        val coordNoCtc = buildCoordinator(localCtcAligner = null)
        val base = CorePipelineSession(
            id = "pipe_mic",
            scriptId = "script_mic",
            clauses = listOf(CoreStoryClause(id = "c0", index = 0, spokenText = "Mic check."))
        )
        val noCtcRes = coordNoCtc.synthesizeStage1Tts(base, "http://127.0.0.1:8443", localMicWavFile = micWav).getOrThrow()
        assertTrue(noCtcRes.alignedWords.isEmpty())

        // With localCtcAligner: stores real CTC AlignedWords
        val coordWithCtc = buildCoordinator(
            localCtcAligner = { _, _ ->
                listOf(AlignedWord("Mic", 20L, 220L), AlignedWord("check", 240L, 480L))
            }
        )
        val ctcRes = coordWithCtc.synthesizeStage1Tts(base, "http://127.0.0.1:8443", localMicWavFile = micWav).getOrThrow()
        assertEquals(listOf("Mic", "check"), ctcRes.alignedWords.map { it.word })
    }

    @Test
    fun stage4_passesAlignedWordsAndUseRemotion_andCoreStageRunnerFormatsSubSecondAnd60sDurations() = runTest {
        profileStore.setPhoneOnlyVoiceMode(false)
        profileStore.recordRemotionInstallRequest("enable remotion", installedOnRig = true)
        val coordinator = buildCoordinator()
        val headFile = tempFolder.newFile("head.mp4").apply { writeBytes("MP4".toByteArray()) }
        val headSha = casStore.putFile(headFile, "mp4").sha256

        val stage4Session = CorePipelineSession(
            id = "pipe_s4",
            scriptId = "script_s4",
            stage = CorePipelineStage.CHOP_INTO_BROLL,
            clauses = listOf(
                CoreStoryClause(
                    id = "c0",
                    index = 0,
                    spokenText = "Lead story.",
                    startMs = 0L,
                    endMs = 2000L,
                    segmentMode = BrollSegmentMode.BROLL_CUTAWAY,
                    bRollPrompt = "Newsroom crane shot"
                )
            ),
            talkingHeadJobId = "head_job_1",
            talkingHeadSha = headSha,
            alignedWords = listOf(AlignedWord("Lead", 0L, 400L), AlignedWord("story", 420L, 900L)),
            alignUrl = "http://127.0.0.1:8443/v1/align.json"
        )
        val chopRes = coordinator.chopTalkingHeadIntoBrollStage4(
            session = stage4Session,
            rigBaseUrl = "http://127.0.0.1:8443",
            allSavedOverlays = emptyList(),
            look = null
        )
        assertTrue(chopRes.isSuccess)
        // Default Stage 4 cut renders on the rig's Remotion worker.
        assertEquals(true, fakeGateway.lastChopUseRemotion)
        assertEquals("http://127.0.0.1:8443/v1/align.json", fakeGateway.lastChopAlignUrl)
        assertEquals(2, fakeGateway.lastChopWords.size)

        // Features only the FFmpeg studio_cut compositor implements route the cut to FFmpeg.
        suspend fun chopWith(session: CorePipelineSession, look: NewsroomLook? = null): Boolean? {
            val res = coordinator.chopTalkingHeadIntoBrollStage4(
                session = session,
                rigBaseUrl = "http://127.0.0.1:8443",
                allSavedOverlays = emptyList(),
                look = look
            )
            assertTrue(res.isSuccess)
            return fakeGateway.lastChopUseRemotion
        }
        val opts = stage4Session.renderOptions
        assertEquals(false, chopWith(stage4Session.copy(renderOptions = opts.copy(stripSilence = true))))
        assertEquals(
            false,
            chopWith(stage4Session.copy(renderOptions = opts.copy(defaultTransition = ClipTransitionType.FADE.wire)))
        )
        assertEquals(
            false,
            chopWith(
                stage4Session.copy(
                    clauses = stage4Session.clauses.map { it.copy(transitionType = ClipTransitionType.FADE.wire) }
                )
            )
        )
        assertEquals(false, chopWith(stage4Session, look = NewsroomLook(name = "Grainy", grain = true)))
        // An accent-only look is rendered by Remotion, so it stays on the default.
        assertEquals(true, chopWith(stage4Session, look = NewsroomLook(name = "Blue", accentHex = "#3A86F2")))
        // Toggles the Remotion template honors (captions, auto zoom) stay on Remotion.
        assertEquals(true, chopWith(stage4Session.copy(renderOptions = opts.copy(captions = true, punchIn = true))))
        // renderOptions.useRemotion forces either renderer.
        assertEquals(false, chopWith(stage4Session.copy(renderOptions = opts.copy(useRemotion = false))))
        assertEquals(
            true,
            chopWith(stage4Session.copy(renderOptions = opts.copy(useRemotion = true, stripSilence = true)))
        )

        // CoreStageRunner.tts duration formatting (< 1000ms -> ms, >= 1000ms -> %.1fs)
        val runner = CoreStageRunner(localRepo, profileStore, coordinator, fakeGateway)
        localRepo.saveCorePipelineSession(
            CorePipelineSession(
                id = "pipe_fmt_ms",
                scriptId = "script_fmt_ms",
                clauses = listOf(CoreStoryClause(id = "c0", index = 0, spokenText = "Fast."))
            )
        )
        fakeGateway.nextTtsDurationMs = 500L
        assertEquals("Voice ready (500ms).", runner.tts(null) { _, _ -> })

        fakeGateway.nextTtsDurationMs = 60_400L
        assertEquals("Voice ready (60.4s).", runner.tts(null) { _, _ -> })
    }

    @Test
    fun stage1_clearsPendingRigTasksIncludingChopAndTts() = runTest {
        profileStore.setPhoneOnlyVoiceMode(false)
        val coordinator = buildCoordinator()
        val staleSession = CorePipelineSession(
            id = "pipe_tasks",
            scriptId = "script_tasks",
            clauses = listOf(CoreStoryClause(id = "c_0", index = 0, spokenText = "Revoiced line.")),
            pendingRigTasks = mapOf(
                CoreStageRunner.KEY_TTS to "task_old_tts",
                CoreStageRunner.KEY_CUT to "task_old_chop"
            )
        )
        val updated = coordinator.synthesizeStage1Tts(staleSession, "http://127.0.0.1:8443").getOrThrow()
        assertTrue(updated.pendingRigTasks.isEmpty())
    }

    @Test
    fun stage3_remoteWithLeadingAndTrailingSilence_preservesContinuousClauseStartMs() = runTest {
        profileStore.setPhoneOnlyVoiceMode(false)
        val coordinator = buildCoordinator()
        val wavFile = tempFolder.newFile("remote_silence.wav")
        CoreStoryPipelineCoordinator.writeFloat32PcmAs16BitMonoWav(FloatArray(44_100), wavFile)
        val storedWav = casStore.putFile(wavFile, "wav")

        val session = CorePipelineSession(
            id = "pipe_silence",
            scriptId = "script_silence",
            stage = CorePipelineStage.ITERATE_STORY,
            clauses = listOf(
                CoreStoryClause(id = "c_0", index = 0, spokenText = "Opening beat.", startMs = 0L, endMs = 3_000L),
                CoreStoryClause(id = "c_1", index = 1, spokenText = "Second beat.", startMs = 3_000L, endMs = 6_000L)
            ),
            ttsVoiceSha = storedWav.sha256,
            ttsAudioUrl = "http://127.0.0.1:8443/v1/renders/tts.wav",
            ttsDurationMs = 6_000L,
            ttsSource = CoreStoryPipelineCoordinator.TTS_SOURCE_REMOTE_RIG
        )
        val wordsWithPauses = listOf(
            AlignedWord("Opening", 200L, 1_100L),
            AlignedWord("beat", 1_200L, 2_500L),
            AlignedWord("Second", 3_100L, 4_200L),
            AlignedWord("beat", 4_300L, 5_800L)
        )
        fakeGateway.pollResponder = { jobId ->
            fakeGateway.defaultReadySnapshot(jobId).copy(
                durationMs = 6_000L,
                words = wordsWithPauses
            )
        }

        val headRes = coordinator.matchSpeechToTalkingHeadStage3(
            session,
            "http://127.0.0.1:8443",
            photoSha = null,
            pollIntervalMs = 25L
        ).getOrThrow()

        assertEquals(0L, headRes.clauses[0].startMs)
        assertEquals(3_000L, headRes.clauses[0].endMs)
        assertEquals(3_000L, headRes.clauses[1].startMs)
        assertEquals(6_000L, headRes.clauses[1].endMs)
    }

    @Test
    fun wavCodec_sanitizesNaNAndInfinityWithoutThrowing() = runTest {
        val wavFile = tempFolder.newFile("nan_guard.wav")
        val input = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.5f, -0.5f)
        CoreStoryPipelineCoordinator.writeFloat32PcmAs16BitMonoWav(input, wavFile)

        val decoded = CoreStoryPipelineCoordinator.readWavAsFloat44kMono(wavFile)
        assertNotNull(decoded)
        assertEquals(5, decoded!!.size)
        assertTrue(decoded.all { it.isFinite() })
        assertEquals(0f, decoded[0], 1e-4f)
        assertEquals(0f, decoded[1], 1e-4f)
        assertEquals(0f, decoded[2], 1e-4f)
        assertEquals(0.5f, decoded[3], 1e-3f)
        assertEquals(-0.5f, decoded[4], 1e-3f)

        // 32-bit IEEE float WAV with NaN sample is also sanitized on read
        val floatWav = tempFolder.newFile("float32_nan.wav")
        val buf = java.nio.ByteBuffer.allocate(44 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(44)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(3) // IEEE Float
            putShort(1) // Mono
            putInt(SYNTHESIS_SAMPLE_RATE_HZ)
            putInt(SYNTHESIS_SAMPLE_RATE_HZ * 4)
            putShort(4)
            putShort(32)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(8)
            putFloat(Float.NaN)
            putFloat(0.25f)
        }
        floatWav.writeBytes(buf.array())
        val decodedFloat = CoreStoryPipelineCoordinator.readWavAsFloat44kMono(floatWav)
        assertNotNull(decodedFloat)
        assertTrue(decodedFloat!!.all { it.isFinite() })
        assertEquals(0f, decodedFloat[0], 1e-6f)
        assertEquals(0.25f, decodedFloat[1], 1e-6f)
    }

    @Test
    fun stage1Remote_fallsBackToLocalCtcWhenAlignUrlFetchFails() = runTest {
        profileStore.setPhoneOnlyVoiceMode(false)
        var ctcCalledWithText: String? = null
        val coordinator = buildCoordinator(
            localCtcAligner = { _, text ->
                ctcCalledWithText = text
                listOf(AlignedWord("Fallback", 10L, 400L), AlignedWord("words", 420L, 900L))
            }
        )
        fakeGateway.nextTtsWords = emptyList()
        fakeGateway.nextTtsAlignUrl = "http://127.0.0.1:8443/v1/renders/broken_align.json"
        fakeGateway.failFetchAlignedWords = true

        val session = CorePipelineSession(
            id = "pipe_align_fallback",
            scriptId = "script_align_fallback",
            clauses = listOf(CoreStoryClause(id = "c_0", index = 0, spokenText = "Fallback words"))
        )
        val updated = coordinator.synthesizeStage1Tts(session, "http://127.0.0.1:8443").getOrThrow()
        assertEquals("Fallback words", ctcCalledWithText)
        assertEquals(listOf("Fallback", "words"), updated.alignedWords.map { it.word })
    }

    private fun installValidV002Bundle(bundleVer: String) {
        val bundleDir = File(bundlesRoot, bundleVer).apply { mkdirs() }
        val sherpaDir = File(bundleDir, OtaBundleSyncManager.SHERPA_CTC_EXTRACTED_DIR).apply { mkdirs() }
        File(sherpaDir, "tokens.txt").writeText("<blk> 0\na 1\n")
        File(sherpaDir, "model.int8.onnx").writeBytes(ByteArray(32) { 1 })

        fun writeBlob(name: String, content: ByteArray = ByteArray(64) { 2 }): ByteArray {
            File(bundleDir, name).writeBytes(content)
            return content
        }

        fun artifact(
            role: OtaArtifactRole,
            filename: String,
            format: OtaArtifactFormat,
            bytes: ByteArray,
            ramMb: Int,
            cadence: OtaUpdateCadence
        ): OtaModelArtifact {
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            return OtaModelArtifact(role, filename, format, sha, bytes.size.toLong(), ramMb, cadence)
        }

        val manifest = OtaBundleManifest(
            bundleVersion = bundleVer,
            loraVersion = 2,
            createdAtUtc = "2026-11-24T06:00:00Z",
            artifacts = listOf(
                artifact(OtaArtifactRole.S2_PRO_BASE_GGUF, "s2_pro_4b_q4_k_m.gguf", OtaArtifactFormat.GGUF, writeBlob("s2_pro_4b_q4_k_m.gguf"), 3200, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.VOICE_LORA_GGUF, "voice_lora_v002.gguf", OtaArtifactFormat.GGUF, writeBlob("voice_lora_v002.gguf"), 64, OtaUpdateCadence.NIGHTLY_DELTA),
                artifact(OtaArtifactRole.CODEC_DECODER_TFLITE, "codec_decoder_fp16.tflite", OtaArtifactFormat.TFLITE, writeBlob("codec_decoder_fp16.tflite"), 420, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.SHERPA_CTC_ALIGNER, "sherpa_ctc_aligner.tar.gz", OtaArtifactFormat.TAR_GZ, writeBlob("sherpa_ctc_aligner.tar.gz"), 96, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.WESPEAKER_RESNET34_ONNX, "wespeaker_resnet34.onnx", OtaArtifactFormat.ONNX, writeBlob("wespeaker_resnet34.onnx"), 80, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.DNSMOS_P808_ONNX, "dnsmos_p808.onnx", OtaArtifactFormat.ONNX, writeBlob("dnsmos_p808.onnx"), 24, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.MASTER_CENTROID_BIN, "master_centroid.bin", OtaArtifactFormat.BIN, writeBlob("master_centroid.bin", ByteArray(1024) { 1 }), 4, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.ROOM_IR_PCM, "room_ir_44k.pcm", OtaArtifactFormat.PCM, writeBlob("room_ir_44k.pcm"), 4, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.ROOM_TONE_PCM, "room_tone_44k.pcm", OtaArtifactFormat.PCM, writeBlob("room_tone_44k.pcm"), 4, OtaUpdateCadence.STATIC_ONCE),
                artifact(OtaArtifactRole.G2P_LEXICON, "lexicon.json", OtaArtifactFormat.JSON, writeBlob("lexicon.json", "{}".toByteArray()), 4, OtaUpdateCadence.NIGHTLY_DELTA)
            )
        )
        File(bundleDir, OtaBundleSyncManager.MANIFEST_FILENAME)
            .writeText(manifestJson.encodeToString(OtaBundleManifest.serializer(), manifest))
        File(bundlesRoot, OtaBundleSyncManager.ACTIVE_BUNDLE_POINTER_FILENAME).writeText(bundleVer)
    }

    private class FakeMacRigGateway(
        private val casStore: CasMediaStore,
        private val tempFolder: TemporaryFolder
    ) : MacRigRenderGateway {
        var ttsCallCount = 0
        var enqueueCount = 0
        var lastEnqueuedMaxDurationSec = 0
        var nextTtsDurationMs = 2_000L
        var nextTtsWords: List<AlignedWord> = emptyList()
        var nextTtsAlignUrl: String? = null
        var nextTtsMetrics: RigQualityMetrics? = null
        var nextTtsTimedClauses: List<CoreStoryClause>? = null
        var failFetchAlignedWords = false
        var lastChopWords: List<AlignedWord> = emptyList()
        var lastChopAlignUrl: String? = null
        var lastChopUseRemotion: Boolean? = null
        var pollResponder: ((String) -> MacRigJobSnapshot)? = null

        fun defaultReadySnapshot(jobId: String) = MacRigJobSnapshot(
            jobId = jobId,
            status = RenderJobStatus.READY,
            progressFraction = 1f,
            stageDetail = "Ready",
            errorMessage = null,
            videoUrl = "http://127.0.0.1:8443/v1/renders/$jobId.mp4",
            durationMs = 2_000L
        )

        override suspend fun resolveAndCheckHealth(configuredUrl: String) = MacRigHealthResult(
            resolvedUrl = "http://127.0.0.1:8443",
            status = "ok",
            loraVersion = 2,
            bundleVersion = "v002",
            pendingPreferences = 0,
            renderBridgeAvailable = true,
            activeRenderJobs = 0,
            remotionWorkerPresent = true,
            remotionEnabled = true
        )

        override suspend fun listRemoteRenders(rigBaseUrl: String): List<RemoteRenderSummary> = emptyList()

        override suspend fun enqueueMacRigRender(
            rigBaseUrl: String,
            project: ScriptProject,
            photoSha: String?,
            resolution: String,
            ratio: String,
            maxDurationSec: Int,
            words: List<AlignedWord>,
            alignUrl: String?,
            useRemotion: Boolean?,
            brollPlan: List<CoreStoryClause>,
            brollAspect: String,
            videoBackend: String?,
            heygenLookId: String?,
            heygenEngine: String?,
            heygenResolution: String?,
            heygenMotionPrompt: String?
        ): EnqueuedRenderResult {
            enqueueCount++
            lastEnqueuedMaxDurationSec = maxDurationSec
            val id = "head_job_$enqueueCount"
            return EnqueuedRenderResult(id, id, "hash")
        }

        override suspend fun pollMacRigJob(rigBaseUrl: String, jobId: String): MacRigJobSnapshot {
            return pollResponder?.invoke(jobId) ?: defaultReadySnapshot(jobId)
        }

        override suspend fun downloadRenderedMp4ToCas(videoUrl: String): CasStoredAsset {
            val f = tempFolder.newFile("dl_${System.nanoTime()}.mp4").apply { writeBytes("MP4_BYTES".toByteArray()) }
            return casStore.putFile(f, "mp4")
        }

        override suspend fun downloadAudioWavToCas(audioUrl: String): CasStoredAsset {
            val f = tempFolder.newFile("dl_${System.nanoTime()}.wav")
            CoreStoryPipelineCoordinator.writeFloat32PcmAs16BitMonoWav(FloatArray(22_050), f)
            return casStore.putFile(f, "wav")
        }

        override suspend fun synthesizeCoreStoryTts(
            rigBaseUrl: String,
            projectId: String,
            clauses: List<CoreStoryClause>,
            task: RigTaskHandle?,
            voiceBackend: String
        ): CoreTtsSynthesisResult {
            ttsCallCount++
            return CoreTtsSynthesisResult(
                ttsId = "tts_$ttsCallCount",
                audioUrl = "http://127.0.0.1:8443/v1/renders/tts_$ttsCallCount.wav",
                durationMs = nextTtsDurationMs,
                waveformPeaks = listOf(0.5f, 0.8f),
                timedClauses = nextTtsTimedClauses ?: clauses.mapIndexed { idx, c ->
                    c.copy(index = idx, startMs = 0L, endMs = nextTtsDurationMs)
                },
                words = nextTtsWords,
                alignUrl = nextTtsAlignUrl,
                metrics = nextTtsMetrics
            )
        }

        override suspend fun fetchAlignedWords(alignUrl: String): List<AlignedWord> {
            if (failFetchAlignedWords) throw IllegalStateException("Simulated align_url HTTP 500")
            return emptyList()
        }

        override suspend fun chopTalkingHeadIntoBroll(
            rigBaseUrl: String,
            scriptId: String,
            title: String,
            sourceJobId: String?,
            sourceMp4Sha: String?,
            clauses: List<CoreStoryClause>,
            overlays: List<SavedNewsroomOverlay>,
            renderOptions: CoreRenderOptions?,
            look: NewsroomLook?,
            task: RigTaskHandle?,
            alignedWords: List<AlignedWord>,
            alignUrl: String?,
            useRemotion: Boolean?
        ): ChoppedBrollResult {
            lastChopWords = alignedWords
            lastChopAlignUrl = alignUrl
            lastChopUseRemotion = useRemotion
            return ChoppedBrollResult(
                jobId = "cut_job_1",
                videoUrl = "http://127.0.0.1:8443/v1/renders/cut_job_1.mp4",
                durationMs = 2_000L,
                brollCutCount = 1,
                appliedOverlayCount = overlays.size,
                studioStats = StudioCutStats(brollCutCount = 1, remotionUsed = useRemotion == true)
            )
        }

        override suspend fun detectSilenceGaps(rigBaseUrl: String, sourceJobId: String) =
            SilenceGapsResult(2000L, emptyList(), 0L)

        override suspend fun generateBackdropPlate(
            rigBaseUrl: String,
            prompt: String,
            aspect: String,
            task: RigTaskHandle?
        ) = BackdropPlateResult("plate_1", "http://127.0.0.1:8443/plate.png", prompt)

        override suspend fun generateAiOverlayBrief(rigBaseUrl: String, briefPrompt: String) =
            throw UnsupportedOperationException()

        override suspend fun requestRemotionInstall(
            rigBaseUrl: String,
            userRequest: String,
            enableBrollPopups: Boolean
        ) = RemotionInstallResult(true, "4.0.545", "mac_rig_remotion", userRequest, enableBrollPopups)
    }
}
