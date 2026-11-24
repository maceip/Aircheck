package com.voicestudio.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.ClauseSynthesisPhase
import com.voicestudio.edl.DEFAULT_BASE_GAP_SAMPLES
import com.voicestudio.edl.OtaArtifactFormat
import com.voicestudio.edl.OtaArtifactRole
import com.voicestudio.edl.OtaBundleManifest
import com.voicestudio.edl.OtaModelArtifact
import com.voicestudio.edl.OtaUpdateCadence
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VerificationGateThresholds
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.WordToken
import com.voicestudio.edl.CandidateTake
import com.voicestudio.verifier.AudioDspMetrics
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class VoiceStudioDirectSynthesisTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var bundlesRoot: File
    private lateinit var otaManager: OtaBundleSyncManager
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VoiceStudioRuntimeRegistry.resetForTest()
        bundlesRoot = tempFolder.newFolder("bundles_direct")
        otaManager = OtaBundleSyncManager(bundlesRootDir = bundlesRoot)
    }

    @After
    fun tearDown() {
        VoiceStudioRuntimeRegistry.resetForTest()
    }

    @Test
    fun synthesizeClausesDirectly_stitchesFloat32Pcm_computesWordTimingsAndRealMetrics() = runTest {
        installValidV002Bundle("v002")

        var savedManifest: VoiceProjectManifest? = null
        VoiceStudioRuntimeRegistry.vmRssReader = { 2_100 }
        VoiceStudioRuntimeRegistry.projectStore = object : SyncProjectStore {
            override suspend fun getProjectManifest(projectId: String): VoiceProjectManifest? = savedManifest
            override suspend fun getScriptDraftForSynthesis(projectId: String): ScriptSynthesisDraft? = null
            override suspend fun saveProjectManifest(manifest: VoiceProjectManifest) {
                savedManifest = manifest
            }
        }

        val samplesPerClause = SYNTHESIS_SAMPLE_RATE_HZ // 1.0s @ 44.1kHz
        val zeroCrossIn = 100L
        val zeroCrossOut = 44_000L
        val activeLen = (zeroCrossOut - zeroCrossIn).toInt()

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
                    outputRootDir.mkdirs()
                    val metrics0 = VerificationGateThresholds().evaluate(
                        cer = 0f,
                        maxPhonemeDurationMs = 160f,
                        speakerCosineSim = 0.94f,
                        dnsmosP808 = 4.40f,
                        tailRmsDb = -52f,
                        syllablesPerSec = 4.9f
                    )
                    val metrics1 = VerificationGateThresholds().evaluate(
                        cer = 0f,
                        maxPhonemeDurationMs = 200f,
                        speakerCosineSim = 0.90f,
                        dnsmosP808 = 4.20f,
                        tailRmsDb = -48f,
                        syllablesPerSec = 5.3f
                    )
                    val updated = clauses.mapIndexed { idx, clause ->
                        onClauseProgress(idx, clauses.size, clause.clauseId, ClauseSynthesisPhase.STAGE_A_GGUF_AND_LITERT_DAC)
                        val pcm = FloatArray(samplesPerClause) { i ->
                            val t = i.toDouble() / SYNTHESIS_SAMPLE_RATE_HZ
                            (0.4 * sin(2.0 * PI * (330.0 + idx * 110.0) * t)).toFloat()
                        }
                        val pcmFile = File(outputRootDir, "${clause.clauseId}_t0.pcm")
                        AudioDspMetrics.writeAlignedPcmFile(pcm, pcmFile)
                        val m = if (idx == 0) metrics0 else metrics1
                        val tokens = if (idx == 0) {
                            listOf(
                                WordToken("Aircheck", 100L, 20_000L, zeroCrossIn, 20_000L, 8_000L, null),
                                WordToken("local", 20_500L, 44_000L, 20_500L, zeroCrossOut, 30_000L, null)
                            )
                        } else {
                            listOf(
                                WordToken("Broadcast", 100L, 22_000L, zeroCrossIn, 22_000L, 10_000L, null),
                                WordToken("ready", 22_500L, 44_000L, 22_500L, zeroCrossOut, 32_000L, null)
                            )
                        }
                        clause.copy(
                            candidates = listOf(
                                CandidateTake(
                                    takeId = "${clause.clauseId}_t0",
                                    pcmFilePath = pcmFile.absolutePath,
                                    totalSamples = samplesPerClause.toLong(),
                                    discreteAudioTokens = intArrayOf(1, 2, 3),
                                    seed = 42L + idx,
                                    temperature = 0.7f,
                                    metrics = m,
                                    tokens = tokens
                                )
                            )
                        )
                    }
                    return Result.success(ClauseSynthesisExecutionResult(updatedClauses = updated))
                }
            }
        }

        val coordinator = VoiceStudioSyncCoordinator(context, otaManager)
        val result = coordinator.synthesizeClausesDirectly(
            projectId = "proj_direct_1",
            clauses = listOf(
                DirectSynthesisClauseInput("c_alpha", "Aircheck local"),
                DirectSynthesisClauseInput("c_beta", "Broadcast ready")
            )
        ).getOrThrow()

        // Verify stitched PCM length = 2 * activeLen + 2 * DEFAULT_BASE_GAP_SAMPLES
        val expectedSamples = (activeLen + DEFAULT_BASE_GAP_SAMPLES) * 2
        assertEquals(expectedSamples, result.stitchedPcm44k.size)

        // Verify gap region after clause 0 is digital silence (0.0f) and clause 1 audio follows
        val gapStart = activeLen
        assertEquals(0f, result.stitchedPcm44k[gapStart], 1e-6f)
        assertEquals(0f, result.stitchedPcm44k[gapStart + DEFAULT_BASE_GAP_SAMPLES - 1], 1e-6f)
        assertTrue(
            "Second clause audio should follow gap",
            abs(result.stitchedPcm44k[gapStart + DEFAULT_BASE_GAP_SAMPLES + 100]) > 0.01f
        )

        // Verify clause time bounds and global aligned words
        val bounds0 = result.clauseTimeBoundsMs.getValue("c_alpha")
        val bounds1 = result.clauseTimeBoundsMs.getValue("c_beta")
        assertEquals(0L, bounds0.first)
        assertTrue(bounds0.second in 990L..1005L)
        assertTrue(bounds1.first > bounds0.second) // gap separates clause 0 and clause 1
        assertEquals(listOf("Aircheck", "local", "Broadcast", "ready"), result.alignedWords.map { it.word })
        assertEquals(0L, result.alignedWords[0].startMs)
        assertTrue(result.alignedWords[2].startMs >= bounds1.first)

        // Verify real GateMetrics aggregation (mean speaker/dnsmos/syllables, max cer/tailRms)
        assertEquals(0f, result.summaryMetrics.cer, 1e-5f)
        assertEquals(0.92f, result.summaryMetrics.speakerCosineSim, 1e-4f)
        assertEquals(4.30f, result.summaryMetrics.dnsmosP808, 1e-4f)
        assertEquals(-48f, result.summaryMetrics.tailRmsDb, 1e-4f)
        assertTrue(result.summaryMetrics.passedAllGates)

        // Verify videoAnchorId attached and manifest saved
        val manifest = requireNotNull(savedManifest)
        assertEquals("c_alpha", manifest.clauses[0].selectedCandidate!!.tokens[0].videoAnchorId)
        assertEquals("c_beta", manifest.clauses[1].selectedCandidate!!.tokens[0].videoAnchorId)
    }

    @Test
    fun synthesizeClausesDirectly_failsExplicitlyWhenBundleMissing_orVmRssHigh_orPcmMissing() = runTest {
        var savedManifest: VoiceProjectManifest? = null
        VoiceStudioRuntimeRegistry.projectStore = object : SyncProjectStore {
            override suspend fun getProjectManifest(projectId: String): VoiceProjectManifest? = savedManifest
            override suspend fun getScriptDraftForSynthesis(projectId: String): ScriptSynthesisDraft? = null
            override suspend fun saveProjectManifest(manifest: VoiceProjectManifest) {
                savedManifest = manifest
            }
        }
        val coordinator = VoiceStudioSyncCoordinator(context, otaManager)

        // 1. Missing OTA bundle -> Result.failure, nothing saved
        val missingRes = coordinator.synthesizeClausesDirectly(
            projectId = "proj_missing",
            clauses = listOf(DirectSynthesisClauseInput("c1", "Hello world."))
        )
        assertTrue(missingRes.isFailure)
        assertNull(savedManifest)

        // 2. High VmRSS -> Result.failure citing VmRSS
        VoiceStudioRuntimeRegistry.vmRssReader = { 9_200 }
        val rssRes = coordinator.synthesizeClausesDirectly(
            projectId = "proj_oom",
            clauses = listOf(DirectSynthesisClauseInput("c1", "Hello world."))
        )
        assertTrue(rssRes.isFailure)
        assertTrue(rssRes.exceptionOrNull()!!.message!!.contains("VmRSS"))

        // 3. Installed bundle + executor returns missing PCM path -> Result.failure (no zero-fill fabrication)
        VoiceStudioRuntimeRegistry.vmRssReader = { 2_000 }
        installValidV002Bundle("v002")
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
                    val m = VerificationGateThresholds().evaluate(0f, 180f, 0.9f, 4.3f, -50f, 5.0f)
                    val updated = clauses.map { c ->
                        c.copy(
                            candidates = listOf(
                                CandidateTake(
                                    takeId = "${c.clauseId}_t0",
                                    pcmFilePath = File(outputRootDir, "nonexistent.pcm").absolutePath,
                                    totalSamples = 1000L,
                                    discreteAudioTokens = intArrayOf(1),
                                    seed = 1L,
                                    temperature = 0.7f,
                                    metrics = m
                                )
                            )
                        )
                    }
                    return Result.success(ClauseSynthesisExecutionResult(updatedClauses = updated))
                }
            }
        }
        val missingPcmRes = coordinator.synthesizeClausesDirectly(
            projectId = "proj_missing_pcm",
            clauses = listOf(DirectSynthesisClauseInput("c1", "Hello world."))
        )
        assertTrue(missingPcmRes.isFailure)
        assertTrue(missingPcmRes.exceptionOrNull()!!.message!!.contains("missing", ignoreCase = true))
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
            return OtaModelArtifact(
                role = role,
                filename = filename,
                format = format,
                sha256 = sha,
                sizeBytes = bytes.size.toLong(),
                estimatedRamMb = ramMb,
                updateCadence = cadence
            )
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
                artifact(OtaArtifactRole.G2P_LEXICON, "lexicon.json", OtaArtifactFormat.JSON, writeBlob("lexicon.json", """{"Aircheck":"ˈɛərˌtʃɛk"}""".toByteArray()), 4, OtaUpdateCadence.NIGHTLY_DELTA)
            )
        )
        File(bundleDir, OtaBundleSyncManager.MANIFEST_FILENAME)
            .writeText(json.encodeToString(OtaBundleManifest.serializer(), manifest))
        File(bundlesRoot, OtaBundleSyncManager.ACTIVE_BUNDLE_POINTER_FILENAME).writeText(bundleVer)
    }
}
