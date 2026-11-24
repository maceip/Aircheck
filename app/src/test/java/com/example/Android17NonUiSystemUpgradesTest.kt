package com.example

import android.content.Context
import android.os.ProfilingTrigger
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import com.example.agent.HitlConfirmationTool
import com.example.agent.OnDevicePromptEngine
import com.example.appfunctions.SetStringField
import com.example.engine.AircheckPlayerController
import com.example.engine.AircheckProfilingCoordinator
import com.example.graph.GraphAppSearchIndexer
import com.example.graph.GraphPostRecord
import com.google.ai.edge.litert.Accelerator
import com.voicestudio.edl.CandidateTake
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.ClipperManifestRegistry
import com.voicestudio.edl.ClipperManifestStore
import com.voicestudio.edl.GateMetrics
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.engine.LiteRtCodecDecoder
import com.voicestudio.ui.appfunctions.HairlineClipperAppFunctionService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class Android17NonUiSystemUpgradesTest {

    @After
    fun tearDown() {
        ClipperManifestRegistry.resetForTest()
    }

    @Test
    fun liteRtCodecDecoder_resolvesNpuToGpuToCpuCascade() {
        assertEquals(
            listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU),
            LiteRtCodecDecoder.resolveAcceleratorCascade(Accelerator.NPU)
        )
        assertEquals(
            listOf(Accelerator.GPU, Accelerator.CPU),
            LiteRtCodecDecoder.resolveAcceleratorCascade(Accelerator.GPU)
        )
        assertEquals(
            listOf(Accelerator.CPU),
            LiteRtCodecDecoder.resolveAcceleratorCascade(Accelerator.CPU)
        )
    }

    @Test
    fun playerController_buildsMediaAndAndroid17AssistantAudioAttributes() {
        val mediaAttrs = AircheckPlayerController.buildMediaAudioAttributes()
        assertEquals(C.USAGE_MEDIA, mediaAttrs.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, mediaAttrs.contentType)

        val assistantAttrs = AircheckPlayerController.buildAssistantAudioAttributes()
        assertEquals(C.USAGE_ASSISTANT, assistantAttrs.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_SPEECH, assistantAttrs.contentType)
    }

    @Test
    fun hitlConfirmationTool_gatesSensitiveAppFunctionsOnly() {
        assertTrue(HitlConfirmationTool.shouldGateTool("enqueueMacRigRender"))
        assertTrue(HitlConfirmationTool.shouldGateTool("com.example#triggerLocalVoiceSynthesis"))
        assertTrue(HitlConfirmationTool.shouldGateTool("swapClauseTake"))
        assertFalse(HitlConfirmationTool.shouldGateTool("seedVoiceDraft"))
        assertFalse(HitlConfirmationTool.shouldGateTool("queryGraphPeers"))
        assertFalse(HitlConfirmationTool.shouldGateTool("inspectOfflineVoiceBundle"))
    }

    @Test
    fun onDevicePromptEngine_reportsHonestUnavailableWithoutFabricatingData() = runTest {
        val engine = OnDevicePromptEngine {
            throw IllegalStateException("AICore service unavailable in JVM test")
        }
        val availability = engine.checkAvailability()
        assertFalse(availability.isModelAvailable)
        assertFalse(availability.isStructuredOutputSupported)

        val packagingResult = engine.generateBroadcastPackaging("Breaking news from the studio desk.")
        assertTrue(packagingResult.isFailure)
        engine.close()
    }

    @Test
    fun graphAppSearchIndexer_buildsNormalizedEmbeddingDocumentsAndHandlesHostJvmCleanly() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val posts = listOf(
            GraphPostRecord(
                tweetId = "tw_101",
                handle = "@Audio_Dsp_Eng",
                text = "Zero-crossing Oboe audio crossfades eliminate clicks at 44.1 kHz.",
                createdAtEpochMs = 1_700_000_100_000L,
                isReplyToUser = true
            ),
            GraphPostRecord(
                tweetId = "tw_102",
                handle = "gardening_daily",
                text = "Watering heirloom tomatoes early in the morning prevents leaf blight.",
                createdAtEpochMs = 1_700_000_200_000L,
                isReplyToUser = false
            )
        )
        val docs = GraphAppSearchIndexer.buildDocuments(posts)
        assertEquals(2, docs.size)
        assertEquals("audio_dsp_eng", docs[0].handle)
        assertEquals(128, docs[0].embedding.values.size)
        assertEquals("aircheck_lexical_v1_128d", docs[0].embedding.modelSignature)

        GraphAppSearchIndexer(
            context = context,
            databaseName = "test_graph_appsearch_${System.nanoTime()}",
            forceLocalStorage = true
        ).use { indexer ->
            // Host JVM lacks Android's native libicing.so; verify graceful non-crashing behavior
            // while full PlatformStorage AppSearchManager indexing is verified in androidTest on API 37.
            val count = indexer.indexPosts(posts)
            assertTrue(count == 0 || count == 2)
        }
    }

    @Test
    fun clipperAppFunctionEntryPoint_readsAndMutatesRealManifestTakesAndGapScale() = runTest {
        val stored = mutableMapOf(
            "proj_1" to VoiceProjectManifest(
                projectId = "proj_1",
                globalGapScale = 1.0f,
                clauses = listOf(
                    ClauseNode(
                        clauseId = "c_001",
                        index = 0,
                        rawText = "Live from studio desk.",
                        normalizedPhonemes = "laɪv",
                        selectedTakeIndex = 0,
                        candidates = listOf(
                            stubTake("t0"),
                            stubTake("t1")
                        )
                    )
                )
            )
        )
        ClipperManifestRegistry.store = object : ClipperManifestStore {
            override suspend fun getManifest(projectId: String): VoiceProjectManifest? = stored[projectId]
            override suspend fun saveManifest(manifest: VoiceProjectManifest) {
                stored[manifest.projectId] = manifest
            }
        }

        val service = HairlineClipperAppFunctionService()
        val initial = service.inspectProjectClauses("proj_1")
        assertEquals(1, initial.clauses.size)
        assertEquals(0, initial.clauses.first().selectedTakeIndex)

        val swapped = service.swapClauseTake("proj_1", "c_001", 1)
        assertEquals(1, swapped.clauses.first().selectedTakeIndex)

        val compressed = service.updateGlobalGapScale("proj_1", 0.5f)
        assertEquals(0.5f, compressed.globalGapScale, 0.0001f)
        assertEquals(0.5f, stored.getValue("proj_1").globalGapScale, 0.0001f)
    }

    @Test
    fun setStringField_distinguishesUnchangedNullFromExplicitClear() {
        val unchanged: SetStringField? = null
        val explicitClear = SetStringField(value = null)
        val explicitUpdate = SetStringField(value = "BROADCAST_COBALT")

        assertEquals(null, unchanged)
        assertEquals(null, explicitClear.value)
        assertEquals("BROADCAST_COBALT", explicitUpdate.value)
    }

    @Test
    fun profilingCoordinator_buildsRateLimitedAndroid17Triggers() {
        val triggers = AircheckProfilingCoordinator.buildDefaultTriggers()
        assertEquals(4, triggers.size)
        val types = triggers.map { it.triggerType }.toSet()
        assertTrue(types.contains(ProfilingTrigger.TRIGGER_TYPE_COLD_START))
        assertTrue(types.contains(ProfilingTrigger.TRIGGER_TYPE_ANOMALY))
        assertTrue(types.contains(ProfilingTrigger.TRIGGER_TYPE_OOM))
        assertTrue(types.contains(ProfilingTrigger.TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE))
    }

    private fun stubTake(id: String): CandidateTake = CandidateTake(
        takeId = id,
        pcmFilePath = "/tmp/$id.pcm",
        totalSamples = 44_100L,
        discreteAudioTokens = intArrayOf(1, 2, 3),
        seed = 42L,
        temperature = 0.7f,
        metrics = GateMetrics(
            cer = 0.0f,
            maxPhonemeDurationMs = 180f,
            speakerCosineSim = 0.91f,
            dnsmosP808 = 4.25f,
            tailRmsDb = -52f,
            syllablesPerSec = 4.9f,
            passedAllGates = true
        )
    )
}
