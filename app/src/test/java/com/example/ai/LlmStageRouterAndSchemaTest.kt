package com.example.ai

import android.os.Looper
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.AircheckApplication
import com.example.agent.OnDeviceLlmBackend
import com.example.agent.OnDeviceLlmException
import com.example.agent.OnDevicePromptAvailability
import com.example.agent.OnDevicePromptEngine
import com.example.agent.StructuredBroadcastPackaging
import com.example.data.AlignedWord
import com.example.data.BrandChannel
import com.example.data.CoreRenderOptions
import com.example.data.CoreStoryClause
import com.example.data.DEFAULT_REMOTE_LLM_PROVIDER_ID
import com.example.data.LlmProviderChoice
import com.example.data.LlmStage
import com.example.data.LlmStageConfig
import com.example.data.NewsroomLook
import com.example.data.OverlayObjectKind
import com.example.data.SavedNewsroomOverlay
import com.example.data.ScriptProject
import com.example.data.Stage4Mode
import com.example.data.VoiceSource
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
import com.example.data.gateway.RigTaskHandle
import com.example.data.gateway.SilenceGapsResult
import com.example.data.local.AircheckProfileProto
import com.example.data.local.AircheckProfileSerializer
import com.example.data.local.AircheckProfileStore
import com.example.data.local.CasStoredAsset
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.draft.AngleDto
import com.example.draft.AnglesResponse
import com.example.draft.ArcBeatDto
import com.example.draft.ArcResponse
import com.example.draft.BrushNudge
import com.example.draft.BrushSwapResponse
import com.example.draft.DensityResponse
import com.example.draft.DraftApiClient
import com.example.draft.LlmMetaDto
import com.example.draft.LlmStageBindingDto
import com.example.draft.RewriteResponse
import com.example.draft.SeamHealResponse
import com.example.draft.SynthesizeResponse
import com.example.draft.SynthesizeScriptResponse
import com.example.draft.SynthesizedCandidateDto
import com.example.ui.draft.DraftDeckViewModel
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.voicestudio.sync.OtaBundleSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LlmStageRouterAndSchemaTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var app: AircheckApplication
    private lateinit var profileScope: CoroutineScope
    private lateinit var profileStore: AircheckProfileStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        profileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pbFile = tempFolder.newFile("m4_2_profile_${System.nanoTime()}.pb")
        pbFile.delete()
        profileStore = AircheckProfileStore(
            DataStoreFactory.create(
                serializer = AircheckProfileSerializer,
                scope = profileScope,
                produceFile = { pbFile }
            )
        )
    }

    @After
    fun tearDown() {
        profileScope.cancel()
    }

    // -------------------------------------------------------------------------
    // 1. AircheckProfileStore Proto Round-Trip, Backward Compat & Photo SHA
    // -------------------------------------------------------------------------

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class LegacyAircheckProfileProtoV25(
        @ProtoNumber(1) val voiceId: String = "",
        @ProtoNumber(2) val headModelId: String = "",
        @ProtoNumber(3) val defaultPhotoSha: String = "",
        @ProtoNumber(4) val brandChannel: String = BrandChannel.VOLT_LIME.name,
        @ProtoNumber(5) val activeScriptId: String = "",
        @ProtoNumber(6) val voiceSource: String = VoiceSource.MIC_VOICEOVER.name,
        @ProtoNumber(7) val stage4Mode: String = Stage4Mode.PHOTO_HEAD_LORA.name,
        @ProtoNumber(8) val voiceProviderId: String = "voicestudio_s2pro_local",
        @ProtoNumber(9) val alignProviderId: String = "sherpa_onnx_local",
        @ProtoNumber(10) val headProviderId: String = "mac_rig_wan30",
        @ProtoNumber(11) val packageProviderId: String = "mac_rig_ffmpeg",
        @ProtoNumber(12) val lanGatewayUrl: String = DEFAULT_MAC_RIG_LAN_URL,
        @ProtoNumber(13) val useLanVoice: Boolean = true,
        @ProtoNumber(14) val useLanAlign: Boolean = true,
        @ProtoNumber(15) val useLanHead: Boolean = true,
        @ProtoNumber(16) val useLanPackage: Boolean = true,
        @ProtoNumber(17) val trainingJobId: String = "",
        @ProtoNumber(18) val trainingStatus: String = "",
        @ProtoNumber(19) val trainingError: String = "",
        @ProtoNumber(21) val tailscaleRigUrl: String = DEFAULT_MAC_RIG_LAN_URL,
        @ProtoNumber(22) val remotionUsedCount: Int = 0,
        @ProtoNumber(23) val remotionInstallRequested: Boolean = false,
        @ProtoNumber(24) val remotionInstallNote: String = "",
        @ProtoNumber(25) val phoneOnlyVoiceMode: Boolean = true
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun aircheckProfileStore_decodesLegacyProtoV25_andPersistsPerStageLlmAndPhotoSha() = runTest {
        val legacy = LegacyAircheckProfileProtoV25(
            voiceId = "voice_ryan",
            headModelId = "head_ryan",
            defaultPhotoSha = "sha_legacy_01",
            brandChannel = BrandChannel.BROADCAST_COBALT.name,
            tailscaleRigUrl = "http://100.64.0.9:8443",
            phoneOnlyVoiceMode = false
        )
        val legacyBytes = ProtoBuf.encodeToByteArray(legacy)
        val decoded = AircheckProfileSerializer.readFrom(ByteArrayInputStream(legacyBytes))

        assertEquals("voice_ryan", decoded.voiceId)
        assertEquals("sha_legacy_01", decoded.defaultPhotoSha)
        assertEquals("http://100.64.0.9:8443", decoded.tailscaleRigUrl)
        assertFalse(decoded.phoneOnlyVoiceMode)
        assertEquals(LlmProviderChoice.MAC_RIG_DEFAULT.id, decoded.draftingLlmProvider)
        assertEquals(DEFAULT_REMOTE_LLM_PROVIDER_ID, decoded.draftingLlmRemoteProvider)
        assertEquals("", decoded.draftingLlmModel)
        assertEquals(LlmProviderChoice.MAC_RIG_DEFAULT.id, decoded.scriptEditLlmProvider)
        assertEquals(LlmProviderChoice.MAC_RIG_DEFAULT.id, decoded.newsroomOverlayLlmProvider)

        // Round-trip through AircheckProfileStore mutators
        profileStore.setLlmStageConfig(
            stage = LlmStage.DRAFTING,
            provider = LlmProviderChoice.LOCAL_AICORE_NANO,
            modelOverride = "   "
        )
        profileStore.setLlmStageConfig(
            stage = LlmStage.SCRIPT_EDIT,
            provider = LlmProviderChoice.REMOTE_HOSTED,
            modelOverride = "  gpt-4.1-mini  ",
            remoteProviderId = "remote_openai"
        )
        profileStore.setLlmStageConfig(
            stage = LlmStage.NEWSROOM_OVERLAY,
            provider = LlmProviderChoice.LOCAL_MAC_MLX,
            modelOverride = "mlx-community/Qwen3.5-9B"
        )
        profileStore.setDefaultPhotoSha("  portrait_sha_999  ")

        val settings = profileStore.current()
        assertEquals("portrait_sha_999", settings.persona.defaultPhotoSha)

        assertEquals(LlmProviderChoice.LOCAL_AICORE_NANO, settings.llmRouting.drafting.provider)
        assertNull(settings.llmRouting.drafting.resolveWireProviderId())
        assertNull(settings.llmRouting.drafting.normalizedModelOverride())

        assertEquals(LlmProviderChoice.REMOTE_HOSTED, settings.llmRouting.scriptEdit.provider)
        assertEquals("remote_openai", settings.llmRouting.scriptEdit.resolveWireProviderId())
        assertEquals("gpt-4.1-mini", settings.llmRouting.scriptEdit.normalizedModelOverride())

        assertEquals(LlmProviderChoice.LOCAL_MAC_MLX, settings.llmRouting.newsroomOverlay.provider)
        assertEquals("local_mlx", settings.llmRouting.newsroomOverlay.resolveWireProviderId())
        assertEquals("mlx-community/Qwen3.5-9B", settings.llmRouting.newsroomOverlay.normalizedModelOverride())

        // Raw remote provider normalization + OLLAMA + LLAMA_CPP
        profileStore.setLlmStageConfig(LlmStage.DRAFTING, "remote_gemini", "gemini-2.5-flash")
        profileStore.setLlmStageConfig(LlmStage.SCRIPT_EDIT, LlmProviderChoice.LOCAL_MAC_OLLAMA)
        profileStore.setLlmStageConfig(LlmStage.NEWSROOM_OVERLAY, "remote_anthropic", "claude-sonnet-4-20250514")

        val updated = profileStore.current()
        assertEquals(LlmProviderChoice.REMOTE_HOSTED, updated.llmRouting.drafting.provider)
        assertEquals("remote_gemini", updated.llmRouting.drafting.resolveWireProviderId())
        assertEquals("gemini-2.5-flash", updated.llmRouting.drafting.modelOverride)

        assertEquals(LlmProviderChoice.LOCAL_MAC_OLLAMA, updated.llmRouting.scriptEdit.provider)
        assertEquals("local_ollama", updated.llmRouting.scriptEdit.resolveWireProviderId())

        assertEquals(LlmProviderChoice.REMOTE_HOSTED, updated.llmRouting.newsroomOverlay.provider)
        assertEquals("remote_anthropic", updated.llmRouting.newsroomOverlay.resolveWireProviderId())
        assertEquals("claude-sonnet-4-20250514", updated.llmRouting.newsroomOverlay.modelOverride)

        // Verify raw protobuf bytes round-trip through AircheckProfileSerializer
        val protoOut = AircheckProfileProto(
            defaultPhotoSha = "sha_x",
            draftingLlmProvider = LlmProviderChoice.LOCAL_MAC_LLAMA_CPP.id,
            scriptEditLlmProvider = LlmProviderChoice.REMOTE_HOSTED.id,
            scriptEditLlmRemoteProvider = "remote_bailian",
            scriptEditLlmModel = "qwen3.8-max"
        )
        val baos = ByteArrayOutputStream()
        AircheckProfileSerializer.writeTo(protoOut, baos)
        val reDecoded = AircheckProfileSerializer.readFrom(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(protoOut, reDecoded)
    }

    // -------------------------------------------------------------------------
    // 2. DraftApiClient llm_provider / llm_model / llm_meta / 502 / /v1/llm/*
    // -------------------------------------------------------------------------

    @Test
    fun draftApiClient_serializesOverrides_parsesLlmMetaAndErrors_andHandlesLlmConfigEndpoints() = runTest {
        val capturedPayloads = HashMap<String, String>()
        var failWith502 = false

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        server.createContext("/v1/draft/angles") { ex ->
            capturedPayloads["/v1/draft/angles"] = ex.requestBody.bufferedReader().readText()
            if (failWith502) {
                respondJson(
                    ex,
                    502,
                    """{"error":"llm_provider_failed","detail":"OpenAI returned HTTP 502","provider":"remote_openai","stage":"draft.angles"}"""
                )
            } else {
                respondJson(
                    ex,
                    200,
                    """{
                      "extracted_angles":[{"id":"a1","label":"Grid peak","preview":"Peak load rose 42%."}],
                      "blindspots":[],
                      "llm_meta":{"provider":"local_mlx","model":"qwen3-mlx","is_local":true}
                    }"""
                )
            }
        }
        server.createContext("/v1/draft/arc") { ex ->
            capturedPayloads["/v1/draft/arc"] = ex.requestBody.bufferedReader().readText()
            respondJson(
                ex,
                200,
                """{"target_sec":60,"word_budget":150,"beats":[{"label":"HOOK","start_sec":0,"end_sec":15,"text":"Grid load spiked."}],
                    "llm_meta":{"provider":"remote_openai","model":"gpt-4.1-mini","is_local":false}}"""
            )
        }
        server.createContext("/v1/draft/synthesize") { ex ->
            capturedPayloads["/v1/draft/synthesize"] = ex.requestBody.bufferedReader().readText()
            respondJson(
                ex,
                200,
                """{"candidates":[{"posture_label":"DIRECT","text":"Grid load spiked 42%."}],
                    "llm_meta":{"provider":"remote_bailian","model":"qwen3.8-flash","is_local":false}}"""
            )
        }
        server.createContext("/v1/draft/synthesize-script") { ex ->
            capturedPayloads["/v1/draft/synthesize-script"] = ex.requestBody.bufferedReader().readText()
            respondJson(
                ex,
                200,
                """{"target_sec":60,"word_budget":150,"candidates":[{"posture_label":"DIRECT","text":"Tonight at six, grid load spiked."}],
                    "llm_meta":{"provider":"local_ollama","model":"qwen2.5-coder","is_local":true}}"""
            )
        }
        server.createContext("/v1/draft/brush-swap") { ex ->
            capturedPayloads["/v1/draft/brush-swap"] = ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"alternatives":["surged","jumped"],"llm_meta":{"provider":"local_mlx","model":"default_model","is_local":true}}""")
        }
        server.createContext("/v1/draft/seam-heal") { ex ->
            capturedPayloads["/v1/draft/seam-heal"] = ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"text":"Grid load surged.","lock_parity_preserved":true,"llm_meta":{"provider":"local_mlx","model":"default_model","is_local":true}}""")
        }
        server.createContext("/v1/draft/density") { ex ->
            capturedPayloads["/v1/draft/density"] = ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"text":"Grid load surged sharply.","lock_parity_preserved":true,"llm_meta":{"provider":"remote_gemini","model":"gemini-2.5-flash","is_local":false}}""")
        }
        server.createContext("/v1/draft/rewrite") { ex ->
            capturedPayloads["/v1/draft/rewrite"] = ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"text":"Clean lead text.","lock_parity_preserved":true,"llm_meta":{"provider":"remote_anthropic","model":"claude-sonnet-4-20250514","is_local":false}}""")
        }
        server.createContext("/v1/llm/providers") { ex ->
            respondJson(
                ex,
                200,
                """{
                  "providers":[
                    {"id":"local_mlx","label":"Local MLX (Mac)","is_local":true,"available":true,"base_url":"http://127.0.0.1:8080","default_model":"default_model","detail":"ok","models":["default_model"]},
                    {"id":"remote_openai","label":"Remote OpenAI","is_local":false,"available":true,"base_url":null,"default_model":"gpt-4.1-mini","detail":"ok","models":["gpt-4.1-mini"]}
                  ],
                  "stages":{"draft.angles":{"provider":"local_mlx","model":"default_model"}},
                  "provider_urls":{"local_mlx":"http://127.0.0.1:8080"}
                }"""
            )
        }
        server.createContext("/v1/llm/config") { ex ->
            capturedPayloads["/v1/llm/config"] = ex.requestBody.bufferedReader().readText()
            respondJson(
                ex,
                200,
                """{"status":"ok","stages":{"draft.angles":{"provider":"remote_openai","model":"gpt-4.1-mini"}},"provider_urls":{}}"""
            )
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val client = DraftApiClient(configuredRigUrl = baseUrl, usbLoopbackUrl = baseUrl)

            val anglesRes = client.extractAngles("Grid load spiked.", llmProvider = "local_mlx", llmModel = "qwen3-mlx")
            assertEquals("local_mlx", anglesRes.llmMeta?.provider)
            assertEquals(true, anglesRes.llmMeta?.isLocal)
            val anglesJson = Json.parseToJsonElement(capturedPayloads.getValue("/v1/draft/angles")).jsonObject
            assertEquals("local_mlx", anglesJson["llm_provider"]?.jsonPrimitive?.content)
            assertEquals("qwen3-mlx", anglesJson["llm_model"]?.jsonPrimitive?.content)

            // Blank overrides must normalize to JSON null (never "")
            val synthRes = client.synthesize(
                seedTranscript = "Grid load spiked.",
                selectedAngles = listOf("Grid peak"),
                slot = null,
                llmProvider = "   ",
                llmModel = ""
            )
            assertEquals("remote_bailian", synthRes.llmMeta?.provider)
            val synthJson = Json.parseToJsonElement(capturedPayloads.getValue("/v1/draft/synthesize")).jsonObject
            assertEquals(JsonNull, synthJson["slot"])
            assertEquals(JsonNull, synthJson["llm_provider"])
            assertEquals(JsonNull, synthJson["llm_model"])

            assertEquals("remote_openai", client.arc("Seed", emptyList(), emptyList(), llmProvider = "remote_openai", llmModel = "gpt-4.1-mini").llmMeta?.provider)
            assertEquals("local_ollama", client.synthesizeScript("Seed", emptyList(), emptyList(), emptyList(), llmProvider = "local_ollama").llmMeta?.provider)
            assertEquals("local_mlx", client.brushSwap("<replace>spiked</replace>", llmProvider = "local_mlx").llmMeta?.provider)
            assertEquals("local_mlx", client.seamHeal("<lock>Grid</lock> spiked.", "strike_cut", llmProvider = "local_mlx").llmMeta?.provider)
            assertEquals("remote_gemini", client.adjustDensity("<lock>Grid</lock> spiked.", -10, llmProvider = "remote_gemini").llmMeta?.provider)
            assertEquals("remote_anthropic", client.rewrite("Raw seed.", mode = "seed_clean", llmProvider = "remote_anthropic").llmMeta?.provider)

            val providers = client.fetchLlmProviders()
            assertEquals(2, providers.providers.size)
            assertEquals("local_mlx", providers.stages["draft.angles"]?.provider)

            val updatedCfg = client.updateLlmConfig(
                stages = mapOf("draft.angles" to LlmStageBindingDto("remote_openai", "gpt-4.1-mini"))
            )
            assertEquals("ok", updatedCfg.status)
            val cfgJson = Json.parseToJsonElement(capturedPayloads.getValue("/v1/llm/config")).jsonObject
            assertFalse("provider_urls should be omitted when null", cfgJson.containsKey("provider_urls"))

            // HTTP 502 error parsing with provider + stage + errorCode
            failWith502 = true
            try {
                client.extractAngles("Grid load spiked.", llmProvider = "remote_openai")
                fail("Expected HttpStatusException on 502")
            } catch (e: DraftApiClient.HttpStatusException) {
                assertEquals(502, e.statusCode)
                assertEquals("remote_openai", e.provider)
                assertEquals("draft.angles", e.stage)
                assertEquals("llm_provider_failed", e.errorCode)
                assertTrue(e.message!!.contains("OpenAI returned HTTP 502"))
            }
        } finally {
            server.stop(0)
        }
    }

    // -------------------------------------------------------------------------
    // 3. OnDevicePromptEngine Unavailable / JSON Validation / Lock Parity
    // -------------------------------------------------------------------------

    @Test
    fun onDevicePromptEngine_failsExplicitlyWhenUnavailable_andEnforcesJsonAndLockParity() = runTest {
        // A. Unavailable status -> every pass returns explicit Result.failure
        val unavailableModel = fakeGenerativeModel(FeatureStatus.DOWNLOADABLE)
        val unavailableEngine = OnDevicePromptEngine(
            modelProvider = { unavailableModel },
            completionOverride = { """{"extracted_angles":[{"id":"1","label":"Should never run","preview":"x"}]}""" }
        )
        assertFalse(unavailableEngine.checkAvailability().isModelAvailable)
        assertTrue(unavailableEngine.extractAngles("Seed text").isFailure)
        assertTrue(unavailableEngine.arc("Seed text").isFailure)
        assertTrue(unavailableEngine.synthesize("Seed text", emptyList()).isFailure)
        assertTrue(unavailableEngine.synthesizeScript("Seed text").isFailure)
        assertTrue(unavailableEngine.brushSwap("<replace>Seed</replace>").isFailure)
        assertTrue(unavailableEngine.seamHeal("<lock>Seed</lock> text", "strike_cut").isFailure)
        assertTrue(unavailableEngine.adjustDensity("<lock>Seed</lock> text", -10).isFailure)
        assertTrue(unavailableEngine.rewrite("<lock>Seed</lock> text", "cut_polish").isFailure)
        assertTrue(unavailableEngine.generateOverlayBrief("Topic").isFailure)
        unavailableEngine.close()

        // B. Available status -> fenced JSON + ASCII sanitization + llmMeta
        val availableModel = fakeGenerativeModel(FeatureStatus.AVAILABLE)
        val validJsonEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = { prompt ->
                when {
                    prompt.contains("Extract 3 sharp editorial angles") ->
                        """```json
                        {"extracted_angles":[{"id":"a1","label":"Power surge \u2014 evening","preview":"Demand hit 42%."}],"blindspots":[{"id":"b1","label":"Storage cost","preview":"Costs rose."}]}
                        ```"""
                    prompt.contains("Smooth sentence transitions") ->
                        """{"text":"<lock>Texas grid</lock> batteries surged \u2014 fast."}"""
                    else -> """{"text":"Clean output."}"""
                }
            }
        )
        val angles = validJsonEngine.extractAngles("Texas grid batteries surged.")
        assertTrue(angles.isSuccess)
        val anglesVal = angles.getOrThrow()
        assertEquals("Power surge - evening", anglesVal.extractedAngles.first().label)
        assertEquals("local_aicore_nano", anglesVal.llmMeta?.provider)
        assertEquals(true, anglesVal.llmMeta?.isLocal)

        val healed = validJsonEngine.seamHeal("<lock>Texas grid</lock> batteries surged.", "strike_cut")
        assertTrue(healed.isSuccess)
        assertEquals("Texas grid batteries surged - fast.", healed.getOrThrow().text)
        assertTrue(healed.getOrThrow().lockParityPreserved)
        validJsonEngine.close()

        // C. Non-JSON response -> Result.failure (no fabrication)
        val badJsonEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = { "Here are your angles: 1. Grid surge" }
        )
        val badRes = badJsonEngine.extractAngles("Texas grid")
        assertTrue(badRes.isFailure)
        assertTrue(badRes.exceptionOrNull() is OnDeviceLlmException)
        badJsonEngine.close()

        // D. <lock> parity violation -> Result.failure on seamHeal, density, rewrite
        val lockBreakerEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = { """{"text":"State power plants surged."}""" }
        )
        val brokenSeam = lockBreakerEngine.seamHeal("<lock>Texas grid</lock> batteries surged.", "strike_cut")
        assertTrue(brokenSeam.isFailure)
        assertTrue(brokenSeam.exceptionOrNull() is OnDeviceLlmException)

        val brokenDensity = lockBreakerEngine.adjustDensity("<lock>Texas grid</lock> batteries surged.", -15)
        assertTrue(brokenDensity.isFailure)

        val brokenRewrite = lockBreakerEngine.rewrite("<lock>Texas grid</lock> batteries surged.", "cut_polish")
        assertTrue(brokenRewrite.isFailure)
        lockBreakerEngine.close()
    }

    @Test
    fun onDevicePromptEngine_handlesSingleLineAndPreambleFences_rejectsUnfencedProse_andEnforcesExactLockParity() = runTest {
        val availableModel = fakeGenerativeModel(FeatureStatus.AVAILABLE)

        // 1. Single-line markdown fences & preamble prose fences parse cleanly
        val singleLineFenceEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = { """```json {"text":"Cleaned text."}```""" }
        )
        val singleLineRes = singleLineFenceEngine.rewrite("Raw text.", "seed_clean")
        assertTrue(singleLineRes.isSuccess)
        assertEquals("Cleaned text.", singleLineRes.getOrThrow().text)
        singleLineFenceEngine.close()

        val preambleFenceEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = {
                """Here is the JSON:
```json
{"text":"Cleaned text."}
```"""
            }
        )
        val preambleRes = preambleFenceEngine.rewrite("Raw text.", "seed_clean")
        assertTrue(preambleRes.isSuccess)
        assertEquals("Cleaned text.", preambleRes.getOrThrow().text)
        preambleFenceEngine.close()

        // 2. Un-fenced prose around JSON and trailing garbage after fence must still be rejected
        val unfencedProseEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = { """Here is the JSON: {"text":"Cleaned text."}""" }
        )
        val unfencedErr = unfencedProseEngine.rewrite("Raw text.", "seed_clean")
        assertTrue(unfencedErr.isFailure)
        assertTrue(unfencedErr.exceptionOrNull() is OnDeviceLlmException)
        unfencedProseEngine.close()

        val trailingGarbageEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = {
                """```json
{"text":"Cleaned text."}
``` trailing garbage"""
            }
        )
        val trailingErr = trailingGarbageEngine.rewrite("Raw text.", "seed_clean")
        assertTrue(trailingErr.isFailure)
        assertTrue(trailingErr.exceptionOrNull() is OnDeviceLlmException)
        trailingGarbageEngine.close()

        // 3. Substring in unlocked word ("<lock>in</lock> Austin" -> "in Dallas") PASSES
        val inAustinEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = { """{"text":"We are in Dallas tonight."}""" }
        )
        val inAustinRes = inAustinEngine.adjustDensity("We are <lock>in</lock> Austin tonight.", -10)
        assertTrue("Expected <lock>in</lock> Austin -> Dallas to pass, got ${inAustinRes.exceptionOrNull()}", inAustinRes.isSuccess)
        assertEquals("We are in Dallas tonight.", inAustinRes.getOrThrow().text)
        inAustinEngine.close()

        // 4. Unlocked duplicate of locked word ("The <lock>grid</lock> failed because the old grid overloaded." -> "The grid failed under load.") PASSES
        val dupUnlockedEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = { """{"text":"The grid failed under load."}""" }
        )
        val dupUnlockedRes = dupUnlockedEngine.adjustDensity(
            "The <lock>grid</lock> failed because the old grid overloaded.",
            -20
        )
        assertTrue("Expected unlocked duplicate edit to pass, got ${dupUnlockedRes.exceptionOrNull()}", dupUnlockedRes.isSuccess)
        assertEquals("The grid failed under load.", dupUnlockedRes.getOrThrow().text)
        dupUnlockedEngine.close()

        // 5. Word-boundary violation ("<lock>AI</lock> news" -> "AIM news") FAILS
        val wordBoundaryEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = { """{"text":"AIM news"}""" }
        )
        val wordBoundaryRes = wordBoundaryEngine.seamHeal("<lock>AI</lock> news", "strike_cut")
        assertTrue(wordBoundaryRes.isFailure)
        assertTrue(wordBoundaryRes.exceptionOrNull() is OnDeviceLlmException)
        wordBoundaryEngine.close()

        // 6. Duplicate lock drop ("<lock>grid</lock> and <lock>grid</lock>" -> "grid only") FAILS
        val duplicateLockDropEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = { """{"text":"grid only"}""" }
        )
        val duplicateDropRes = duplicateLockDropEngine.rewrite("<lock>grid</lock> and <lock>grid</lock>", "cut_polish")
        assertTrue(duplicateDropRes.isFailure)
        assertTrue(duplicateDropRes.exceptionOrNull() is OnDeviceLlmException)
        duplicateLockDropEngine.close()

        // 7. Unicode inside locked span is preserved without corruption
        val unicodeLockEngine = OnDevicePromptEngine(
            modelProvider = { availableModel },
            completionOverride = { """{"text":"Power \u2014 grid surged."}""" }
        )
        val unicodeLockRes = unicodeLockEngine.rewrite("<lock>Power \u2014 grid</lock> failed.", "cut_polish")
        assertTrue(unicodeLockRes.isSuccess)
        assertEquals("Power \u2014 grid surged.", unicodeLockRes.getOrThrow().text)
        unicodeLockEngine.close()

        // 8. model() initialization is thread-safe under concurrent calls
        val initCount = AtomicInteger(0)
        val concurrentEngine = OnDevicePromptEngine(
            modelProvider = {
                initCount.incrementAndGet()
                Thread.sleep(20)
                availableModel
            },
            completionOverride = { """{"text":"ok"}""" }
        )
        coroutineScope {
            (1..8).map {
                async(Dispatchers.Default) { concurrentEngine.checkAvailability() }
            }.awaitAll()
        }
        assertEquals(1, initCount.get())
        concurrentEngine.close()
    }

    // -------------------------------------------------------------------------
    // 4. LlmStageRouter Mixed Local + Remote Routing & Zero Silent Fallback
    // -------------------------------------------------------------------------

    @Test
    fun llmStageRouter_routesMixedPerStage_andNeverSilentlyFallsBack() = runTest {
        val serverHits = CopyOnWriteArrayList<String>()
        val capturedBodies = HashMap<String, String>()
        var failAiAssistWith503 = false
        var failScriptEditWith502 = false

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        server.createContext("/v1/draft/angles") { ex ->
            serverHits += "/v1/draft/angles"
            capturedBodies["/v1/draft/angles"] = ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"extracted_angles":[{"id":"1","label":"Remote angle","preview":"p"}],"blindspots":[]}""")
        }
        server.createContext("/v1/draft/synthesize-script") { ex ->
            serverHits += "/v1/draft/synthesize-script"
            capturedBodies["/v1/draft/synthesize-script"] = ex.requestBody.bufferedReader().readText()
            if (failScriptEditWith502) {
                respondJson(ex, 502, """{"error":"llm_provider_failed","detail":"OpenAI timeout","provider":"remote_openai","stage":"draft.synthesize_script"}""")
            } else {
                respondJson(
                    ex,
                    200,
                    """{"target_sec":60,"word_budget":150,"candidates":[{"posture_label":"DIRECT","text":"Script body."}],
                        "llm_meta":{"provider":"remote_openai","model":"gpt-4.1-mini","is_local":false}}"""
                )
            }
        }
        server.createContext("/v1/ai-assist") { ex ->
            serverHits += "/v1/ai-assist"
            capturedBodies["/v1/ai-assist"] = ex.requestBody.bufferedReader().readText()
            if (failAiAssistWith503) {
                respondJson(ex, 503, """{"error":"llm_provider_unavailable","detail":"MLX server down","provider":"local_mlx","stage":"ai_assist"}""")
            } else {
                respondJson(
                    ex,
                    200,
                    """{"mode":"generate_overlay_brief","kind":"STAT_CALLOUT","headline":"GRID +42%","subline":"ERCOT REPORT","callout_stat":"42% PEAK","duration_sec":5,
                        "llm_meta":{"provider":"local_mlx","model":"default_model","is_local":true}}"""
                )
            }
        }
        server.createContext("/v1/llm/providers") { ex ->
            serverHits += "/v1/llm/providers"
            respondJson(ex, 200, """{"providers":[],"stages":{},"provider_urls":{}}""")
        }
        server.createContext("/v1/llm/config") { ex ->
            serverHits += "/v1/llm/config"
            respondJson(ex, 200, """{"status":"ok","stages":{},"provider_urls":{}}""")
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            profileStore.setTailscaleRigUrl(baseUrl)
            // Mixed configuration:
            // - drafting -> LOCAL_AICORE_NANO
            // - scriptEdit -> REMOTE_HOSTED (remote_openai, gpt-4.1-mini)
            // - newsroomOverlay -> LOCAL_MAC_MLX
            profileStore.setLlmStageConfig(LlmStage.DRAFTING, LlmProviderChoice.LOCAL_AICORE_NANO)
            profileStore.setLlmStageConfig(LlmStage.SCRIPT_EDIT, LlmProviderChoice.REMOTE_HOSTED, "gpt-4.1-mini", "remote_openai")
            profileStore.setLlmStageConfig(LlmStage.NEWSROOM_OVERLAY, LlmProviderChoice.LOCAL_MAC_MLX)

            val nanoCalls = AtomicInteger(0)
            var failNano = false
            val fakeNano = FakeOnDeviceLlmBackend(
                onAngles = {
                    nanoCalls.incrementAndGet()
                    if (failNano) Result.failure(OnDeviceLlmException("AICore thermal throttle"))
                    else Result.success(
                        AnglesResponse(
                            extractedAngles = listOf(AngleDto("n1", "Nano Angle", "On-device preview")),
                            llmMeta = LlmMetaDto("local_aicore_nano", "gemini-nano", isLocal = true)
                        )
                    )
                },
                onOverlay = {
                    nanoCalls.incrementAndGet()
                    if (failNano) Result.failure(OnDeviceLlmException("AICore unavailable"))
                    else Result.success(GeneratedOverlayBriefResult(OverlayObjectKind.LOWER_THIRD, "NANO HEADLINE", "SUB", "STAT", 4))
                }
            )

            val okHttp = OkHttpClient()
            val client = DraftApiClient(configuredRigUrl = baseUrl, usbLoopbackUrl = baseUrl, okHttpClient = okHttp)
            val router = LlmStageRouter(
                profileStore = profileStore,
                draftApiClientProvider = { client },
                onDeviceEngine = fakeNano,
                okHttpClient = okHttp
            )

            // 1. DRAFTING runs on-device Nano with ZERO :8443 HTTP calls
            val angles = router.extractAngles("Battery storage surged 42%.")
            assertEquals("Nano Angle", angles.extractedAngles.first().label)
            assertEquals("local_aicore_nano", angles.llmMeta?.provider)
            assertEquals(1, nanoCalls.get())
            assertFalse(serverHits.contains("/v1/draft/angles"))

            // 2. SCRIPT_EDIT runs on :8443 with remote_openai + gpt-4.1-mini
            val script = router.synthesizeScript("Battery storage surged 42%.", listOf("Nano Angle"))
            assertEquals("remote_openai", script.llmMeta?.provider)
            val synthPayload = Json.parseToJsonElement(capturedBodies.getValue("/v1/draft/synthesize-script")).jsonObject
            assertEquals("remote_openai", synthPayload["llm_provider"]?.jsonPrimitive?.content)
            assertEquals("gpt-4.1-mini", synthPayload["llm_model"]?.jsonPrimitive?.content)
            assertEquals(1, nanoCalls.get()) // still 1

            // 3. NEWSROOM_OVERLAY runs on :8443 /v1/ai-assist with local_mlx
            val overlay = router.generateOverlayBrief("Texas battery storage")
            assertTrue(overlay.isSuccess)
            val routedOverlay = overlay.getOrThrow()
            assertEquals("GRID +42%", routedOverlay.brief.headline)
            assertEquals(OverlayObjectKind.STAT_CALLOUT, routedOverlay.brief.kind)
            assertEquals("local_mlx", routedOverlay.llmMeta.provider)
            assertTrue(routedOverlay.llmMeta.isLocal)
            val aiAssistPayload = Json.parseToJsonElement(capturedBodies.getValue("/v1/ai-assist")).jsonObject
            assertEquals("local_mlx", aiAssistPayload["llm_provider"]?.jsonPrimitive?.content)
            assertEquals(1, nanoCalls.get())

            // 4. Local Nano failure -> explicit LlmRoutingException, ZERO HTTP calls to :8443!
            failNano = true
            try {
                router.extractAngles("Battery storage surged.")
                fail("Expected LlmRoutingException when Nano fails")
            } catch (e: LlmRoutingException) {
                assertEquals(LlmStage.DRAFTING, e.stage)
                assertEquals("local_aicore_nano", e.provider)
                assertFalse(e.isTransportFailure)
                assertTrue(e.message.contains("thermal throttle"))
            }
            assertFalse(serverHits.contains("/v1/draft/angles"))

            // Also for NEWSROOM_OVERLAY when set to LOCAL_AICORE_NANO
            profileStore.setLlmStageConfig(LlmStage.NEWSROOM_OVERLAY, LlmProviderChoice.LOCAL_AICORE_NANO)
            val hitsBeforeNanoOverlay = serverHits.size
            val failedNanoOverlay = router.generateOverlayBrief("Texas battery storage")
            assertTrue(failedNanoOverlay.isFailure)
            val overlayErr = failedNanoOverlay.exceptionOrNull() as LlmRoutingException
            assertEquals(LlmStage.NEWSROOM_OVERLAY, overlayErr.stage)
            assertEquals("local_aicore_nano", overlayErr.provider)
            assertFalse(overlayErr.isTransportFailure)
            assertEquals(hitsBeforeNanoOverlay, serverHits.size)

            // 5. :8443 HTTP 502 / 503 failure -> explicit LlmRoutingException, ZERO calls to Nano!
            profileStore.setLlmStageConfig(LlmStage.NEWSROOM_OVERLAY, LlmProviderChoice.LOCAL_MAC_MLX)
            val nanoBeforeRemoteErr = nanoCalls.get()
            failScriptEditWith502 = true
            try {
                router.synthesizeScript("Battery storage surged.")
                fail("Expected LlmRoutingException on 502")
            } catch (e: LlmRoutingException) {
                assertEquals(502, e.statusCode)
                assertEquals("remote_openai", e.provider)
                assertEquals("draft.synthesize_script", e.serverStage)
                assertFalse(e.isTransportFailure)
            }
            failAiAssistWith503 = true
            val aiAssistErr = router.generateOverlayBrief("Texas grid").exceptionOrNull() as LlmRoutingException
            assertEquals(503, aiAssistErr.statusCode)
            assertEquals("local_mlx", aiAssistErr.provider)
            assertEquals("ai_assist", aiAssistErr.serverStage)
            assertFalse(aiAssistErr.isTransportFailure)
            assertEquals(nanoBeforeRemoteErr, nanoCalls.get())

            // 6. Provider discovery & push stage config
            assertTrue(router.fetchRigProviders().isSuccess)
            assertTrue(router.pushStageConfig(LlmStage.NEWSROOM_OVERLAY, LlmStageConfig(LlmProviderChoice.LOCAL_MAC_MLX)).isSuccess)
        } finally {
            server.stop(0)
        }
    }

    // -------------------------------------------------------------------------
    // 5. DraftDeckViewModel Routing Through LlmStageRouter & Error Preservation
    // -------------------------------------------------------------------------

    @Test
    fun draftDeckViewModel_routesThroughLlmStageRouter_andPreservesRigOnlineOnLocalOr502Errors() = runTest {
        var failSynthWith502 = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        server.createContext("/v1/draft/synthesize-script") { ex ->
            if (failSynthWith502) {
                respondJson(ex, 502, """{"error":"llm_provider_failed","detail":"Hosted model 502","provider":"remote_openai","stage":"draft.synthesize_script"}""")
            } else {
                respondJson(
                    ex,
                    200,
                    """{"target_sec":60,"word_budget":150,"candidates":[
                        {"posture_label":"DIRECT","text":"Battery storage dispatched 42% more power tonight."}
                    ],"llm_meta":{"provider":"remote_openai","model":"gpt-4.1-mini","is_local":false}}"""
                )
            }
        }
        server.createContext("/v1/draft/brush-swap") { ex ->
            respondJson(ex, 200, """{"alternatives":["delivered","supplied"],"llm_meta":{"provider":"remote_openai","model":"gpt-4.1-mini","is_local":false}}""")
        }
        server.createContext("/v1/draft/seam-heal") { ex ->
            respondJson(ex, 200, """{"text":"Battery storage delivered more power tonight.","lock_parity_preserved":true,"llm_meta":{"provider":"remote_openai","model":"gpt-4.1-mini","is_local":false}}""")
        }
        server.createContext("/v1/draft/density") { ex ->
            respondJson(ex, 200, """{"text":"Battery storage delivered power tonight.","lock_parity_preserved":true,"llm_meta":{"provider":"remote_openai","model":"gpt-4.1-mini","is_local":false}}""")
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            profileStore.setTailscaleRigUrl(baseUrl)
            profileStore.setLlmStageConfig(LlmStage.DRAFTING, LlmProviderChoice.LOCAL_AICORE_NANO)
            profileStore.setLlmStageConfig(LlmStage.SCRIPT_EDIT, LlmProviderChoice.REMOTE_HOSTED, "gpt-4.1-mini", "remote_openai")

            var failNano = false
            val fakeNano = FakeOnDeviceLlmBackend(
                onAngles = {
                    if (failNano) Result.failure(OnDeviceLlmException("Nano unavailable on test"))
                    else Result.success(
                        AnglesResponse(
                            extractedAngles = listOf(AngleDto("n1", "Peak Dispatch", "42% gain")),
                            llmMeta = LlmMetaDto("local_aicore_nano", "gemini-nano", isLocal = true)
                        )
                    )
                },
                onArc = {
                    Result.success(
                        ArcResponse(
                            targetSec = 60,
                            wordBudget = 150,
                            beats = listOf(ArcBeatDto("HOOK", 0, 15, "Batteries surged.")),
                            llmMeta = LlmMetaDto("local_aicore_nano", "gemini-nano", isLocal = true)
                        )
                    )
                }
            )

            val okHttp = OkHttpClient()
            val rigClient = DraftApiClient(configuredRigUrl = baseUrl, usbLoopbackUrl = baseUrl, okHttpClient = okHttp)
            val router = LlmStageRouter(
                profileStore = profileStore,
                draftApiClientProvider = { rigClient },
                onDeviceEngine = fakeNano,
                okHttpClient = okHttp
            )

            val vm = DraftDeckViewModel(
                application = app,
                llmRouterOverride = router,
                draftApiClientFactory = { url ->
                    val target = if (url == DEFAULT_MAC_RIG_LAN_URL) baseUrl else url
                    DraftApiClient(
                        configuredRigUrl = target,
                        usbLoopbackUrl = target,
                        okHttpClient = okHttp,
                        healthProbeTimeoutMs = 500L
                    )
                },
                profileStoreOverride = profileStore
            )
            waitForCondition {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                vm.uiState.value.rigBaseUrl == baseUrl && vm.uiState.value.lastSeedRigOnline
            }
            // First mark rig online via a successful SCRIPT_EDIT pass
            vm.updateSeedTranscriptManual("Battery storage dispatched 42% more power during peak evening load.")
            vm.writeVideoScript()
            waitForCondition {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                !vm.uiState.value.isBusy && vm.uiState.value.document.candidates.isNotEmpty()
            }
            assertTrue(vm.uiState.value.lastSeedRigOnline)
            assertEquals("remote_openai", vm.uiState.value.lastLlmMeta?.provider)

            // Run Nano drafting pass (angles + arc)
            vm.refreshAnglesFromSeed()
            waitForCondition {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                !vm.uiState.value.isExtractingAngles && !vm.uiState.value.isBusy && vm.uiState.value.document.arcBeats.isNotEmpty()
            }
            assertEquals("Peak Dispatch", vm.uiState.value.document.extractedAngles.first().label)
            assertEquals("local_aicore_nano", vm.uiState.value.lastLlmMeta?.provider)
            assertTrue(vm.uiState.value.lastSeedRigOnline)

            // Nano failure must surface errorMessage AND NOT falsely set lastSeedRigOnline = false!
            failNano = true
            vm.refreshAnglesFromSeed()
            waitForCondition {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                !vm.uiState.value.isExtractingAngles && vm.uiState.value.errorMessage != null
            }
            assertTrue(vm.uiState.value.errorMessage!!.contains("Nano unavailable on test"))
            assertTrue("Local Nano error must preserve lastSeedRigOnline", vm.uiState.value.lastSeedRigOnline)

            // HTTP 502 on SCRIPT_EDIT must surface errorMessage AND preserve lastSeedRigOnline = true!
            vm.dismissError()
            failSynthWith502 = true
            vm.writeVideoScript()
            waitForCondition {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                !vm.uiState.value.isBusy && vm.uiState.value.errorMessage != null
            }
            assertTrue(vm.uiState.value.errorMessage!!.contains("Hosted model 502"))
            assertTrue("HTTP 502 must preserve lastSeedRigOnline", vm.uiState.value.lastSeedRigOnline)

            // Transport failure (unreachable port) MUST flip lastSeedRigOnline = false
            val closedSocket = ServerSocket(0)
            val deadPort = closedSocket.localPort
            closedSocket.close()
            val deadBaseUrl = "http://127.0.0.1:$deadPort"
            val deadPbFile = tempFolder.newFile("m4_2_dead_profile_${System.nanoTime()}.pb")
            deadPbFile.delete()
            val deadStore = AircheckProfileStore(
                DataStoreFactory.create(
                    serializer = AircheckProfileSerializer,
                    scope = profileScope,
                    produceFile = { deadPbFile }
                )
            )
            deadStore.setTailscaleRigUrl(deadBaseUrl)
            deadStore.setLlmStageConfig(
                LlmStage.SCRIPT_EDIT,
                LlmProviderChoice.REMOTE_HOSTED,
                "gpt-4.1-mini",
                "remote_openai"
            )
            val deadClient = DraftApiClient(
                configuredRigUrl = deadBaseUrl,
                usbLoopbackUrl = deadBaseUrl,
                okHttpClient = okHttp,
                healthProbeTimeoutMs = 250L
            )
            val deadRouter = LlmStageRouter(deadStore, { deadClient }, fakeNano, okHttp)
            val deadVm = DraftDeckViewModel(
                application = app,
                llmRouterOverride = deadRouter,
                draftApiClientFactory = { _ ->
                    DraftApiClient(
                        configuredRigUrl = deadBaseUrl,
                        usbLoopbackUrl = deadBaseUrl,
                        okHttpClient = okHttp,
                        healthProbeTimeoutMs = 250L
                    )
                },
                profileStoreOverride = deadStore
            )
            waitForCondition {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                deadVm.uiState.value.rigBaseUrl == deadBaseUrl
            }
            deadVm.updateSeedTranscriptManual("Battery storage dispatched 42% more power.")
            deadVm.writeVideoScript()
            waitForCondition {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                !deadVm.uiState.value.isBusy && deadVm.uiState.value.errorMessage != null
            }
            assertFalse(deadVm.uiState.value.lastSeedRigOnline)
        } finally {
            server.stop(0)
        }
    }

    // -------------------------------------------------------------------------
    // 6. AircheckApplication Startup Probe Probes Rig Even When phoneOnlyVoiceMode == true
    // -------------------------------------------------------------------------

    @Test
    fun startupRigProbe_probesHealthWhenPhoneOnlyVoiceTrue_withoutMutatingSettings() = runTest {
        profileStore.setPhoneOnlyVoiceMode(true)
        profileStore.setLlmStageConfig(LlmStage.DRAFTING, LlmProviderChoice.LOCAL_MAC_OLLAMA, "qwen2.5-coder")
        profileStore.setTailscaleRigUrl("http://10.0.0.99:8443")

        val probedUrls = CopyOnWriteArrayList<String>()
        val fakeGateway = object : MacRigRenderGateway {
            override suspend fun resolveAndCheckHealth(configuredUrl: String): MacRigHealthResult {
                probedUrls += configuredUrl
                return MacRigHealthResult(
                    resolvedUrl = configuredUrl,
                    status = "ok",
                    loraVersion = 2,
                    bundleVersion = "v002",
                    pendingPreferences = 0,
                    renderBridgeAvailable = true,
                    activeRenderJobs = 0,
                    remotionWorkerPresent = true,
                    remotionEnabled = true,
                    remotionRendersCompleted = 3,
                    remotionVersion = "4.0.545"
                )
            }

            override suspend fun listRemoteRenders(rigBaseUrl: String): List<RemoteRenderSummary> = emptyList()
            override suspend fun enqueueMacRigRender(rigBaseUrl: String, project: ScriptProject, photoSha: String?, resolution: String, ratio: String, maxDurationSec: Int, words: List<AlignedWord>, alignUrl: String?, useRemotion: Boolean?, brollPlan: List<CoreStoryClause>, brollAspect: String): EnqueuedRenderResult = throw UnsupportedOperationException()
            override suspend fun pollMacRigJob(rigBaseUrl: String, jobId: String): MacRigJobSnapshot = throw UnsupportedOperationException()
            override suspend fun downloadRenderedMp4ToCas(videoUrl: String): CasStoredAsset = throw UnsupportedOperationException()
            override suspend fun downloadAudioWavToCas(audioUrl: String): CasStoredAsset = throw UnsupportedOperationException()
            override suspend fun synthesizeCoreStoryTts(rigBaseUrl: String, projectId: String, clauses: List<CoreStoryClause>, task: RigTaskHandle?): CoreTtsSynthesisResult = throw UnsupportedOperationException()
            override suspend fun chopTalkingHeadIntoBroll(rigBaseUrl: String, scriptId: String, title: String, sourceJobId: String?, sourceMp4Sha: String?, clauses: List<CoreStoryClause>, overlays: List<SavedNewsroomOverlay>, renderOptions: CoreRenderOptions?, look: NewsroomLook?, task: RigTaskHandle?, alignedWords: List<AlignedWord>, alignUrl: String?, useRemotion: Boolean?): ChoppedBrollResult = throw UnsupportedOperationException()
            override suspend fun detectSilenceGaps(rigBaseUrl: String, sourceJobId: String): SilenceGapsResult = throw UnsupportedOperationException()
            override suspend fun generateBackdropPlate(rigBaseUrl: String, prompt: String, aspect: String, task: RigTaskHandle?): BackdropPlateResult = throw UnsupportedOperationException()
            override suspend fun generateAiOverlayBrief(rigBaseUrl: String, briefPrompt: String): GeneratedOverlayBriefResult = throw UnsupportedOperationException()
            override suspend fun requestRemotionInstall(rigBaseUrl: String, userRequest: String, enableBrollPopups: Boolean): RemotionInstallResult = throw UnsupportedOperationException()
        }

        val otaManager = OtaBundleSyncManager(bundlesRootDir = tempFolder.newFolder("startup_bundles"))
        var resolvedHealth: MacRigHealthResult? = null
        val result = AircheckApplication.probeStartupRigHealthAndSync(
            context = app,
            profileStore = profileStore,
            otaBundleSyncManager = otaManager,
            macRigGateway = fakeGateway,
            onHealthResolved = { resolvedHealth = it }
        )

        assertEquals(listOf("http://10.0.0.99:8443"), probedUrls)
        assertNotNull(result)
        assertEquals(true, resolvedHealth?.remotionEnabled)
        assertEquals("4.0.545", resolvedHealth?.remotionVersion)

        // Verify ZERO settings mutation
        val after = profileStore.current()
        assertTrue(after.phoneOnlyVoiceMode)
        assertFalse(after.providers.useLanVoice)
        assertEquals(LlmProviderChoice.LOCAL_MAC_OLLAMA, after.llmRouting.drafting.provider)
        assertEquals("qwen2.5-coder", after.llmRouting.drafting.modelOverride)
    }

    // -------------------------------------------------------------------------
    // 7. LlmStageRouter IOException Force-Refresh Base URL Recovery
    // -------------------------------------------------------------------------

    @Test
    fun llmStageRouter_retriesWithForceRefreshBaseUrlOnIoException() = runTest {
        val usbServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        usbServer.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        usbServer.start()

        val lanServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        lanServer.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        lanServer.createContext("/v1/ai-assist") { ex ->
            respondJson(
                ex,
                200,
                """{"mode":"generate_overlay_brief","kind":"LOWER_THIRD","headline":"LAN RECOVERED","subline":"AFTER USB DROP","callout_stat":"OK","duration_sec":4,
                    "llm_meta":{"provider":"local_mlx","model":"default_model","is_local":true}}"""
            )
        }
        lanServer.createContext("/v1/llm/providers") { ex ->
            respondJson(
                ex,
                200,
                """{"providers":[{"id":"local_mlx","label":"Local MLX","is_local":true,"available":true,"default_model":"default_model"}],"stages":{},"provider_urls":{}}"""
            )
        }
        lanServer.createContext("/v1/llm/config") { ex ->
            respondJson(ex, 200, """{"status":"ok","stages":{"ai_assist":{"provider":"local_mlx","model":"default_model"}},"provider_urls":{}}""")
        }
        lanServer.start()

        try {
            val usbUrl = "http://127.0.0.1:${usbServer.address.port}"
            val lanUrl = "http://127.0.0.1:${lanServer.address.port}"
            profileStore.setTailscaleRigUrl(lanUrl)
            profileStore.setLlmStageConfig(LlmStage.NEWSROOM_OVERLAY, LlmProviderChoice.LOCAL_MAC_MLX)

            val okHttp = OkHttpClient()
            val client = DraftApiClient(
                configuredRigUrl = lanUrl,
                usbLoopbackUrl = usbUrl,
                okHttpClient = okHttp,
                healthProbeTimeoutMs = 400L
            )
            // Warm cache onto usbUrl, then kill usbServer so subsequent cached requests throw IOException
            assertEquals(usbUrl, client.resolveActiveBaseUrl(forceRefresh = true))
            usbServer.stop(0)

            val router = LlmStageRouter(
                profileStore = profileStore,
                draftApiClientProvider = { client },
                onDeviceEngine = FakeOnDeviceLlmBackend(),
                okHttpClient = okHttp
            )

            // 1. generateOverlayBrief recovers via forceRefresh -> lanUrl
            val overlay = router.generateOverlayBrief("Grid recovery")
            assertTrue("Expected overlay brief to recover via LAN URL, got ${overlay.exceptionOrNull()}", overlay.isSuccess)
            assertEquals("LAN RECOVERED", overlay.getOrThrow().brief.headline)

            // 2. Re-cache stale dead URL and test fetchRigProviders recovery
            val usbServer2 = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            usbServer2.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
            usbServer2.start()
            val usbUrl2 = "http://127.0.0.1:${usbServer2.address.port}"
            val client2 = DraftApiClient(configuredRigUrl = lanUrl, usbLoopbackUrl = usbUrl2, okHttpClient = okHttp, healthProbeTimeoutMs = 400L)
            assertEquals(usbUrl2, client2.resolveActiveBaseUrl(forceRefresh = true))
            usbServer2.stop(0)

            val router2 = LlmStageRouter(profileStore, { client2 }, FakeOnDeviceLlmBackend(), okHttp)
            val providers = router2.fetchRigProviders()
            assertTrue("Expected fetchRigProviders to recover via LAN URL, got ${providers.exceptionOrNull()}", providers.isSuccess)
            assertEquals("local_mlx", providers.getOrThrow().providers.first().id)

            // 3. Re-cache stale dead URL and test pushRigStageConfig recovery
            val usbServer3 = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            usbServer3.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
            usbServer3.start()
            val usbUrl3 = "http://127.0.0.1:${usbServer3.address.port}"
            val client3 = DraftApiClient(configuredRigUrl = lanUrl, usbLoopbackUrl = usbUrl3, okHttpClient = okHttp, healthProbeTimeoutMs = 400L)
            assertEquals(usbUrl3, client3.resolveActiveBaseUrl(forceRefresh = true))
            usbServer3.stop(0)

            val router3 = LlmStageRouter(profileStore, { client3 }, FakeOnDeviceLlmBackend(), okHttp)
            val pushed = router3.pushRigStageConfig(
                stages = mapOf("ai_assist" to LlmStageBindingDto("local_mlx", "default_model"))
            )
            assertTrue("Expected pushRigStageConfig to recover via LAN URL, got ${pushed.exceptionOrNull()}", pushed.isSuccess)
            assertEquals("ok", pushed.getOrThrow().status)
        } finally {
            runCatching { usbServer.stop(0) }
            lanServer.stop(0)
        }
    }

    // -------------------------------------------------------------------------
    // 8. DraftDeckViewModel probeRigHealth Cancels Stale In-Flight Probes
    // -------------------------------------------------------------------------

    @Test
    fun draftDeckViewModel_probeRigHealth_cancelsPriorInFlightProbe() = runTest {
        val slowFailServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        slowFailServer.createContext("/health") { ex ->
            Thread.sleep(350)
            respondJson(ex, 503, """{"status":"down"}""")
        }
        slowFailServer.start()

        val fastSuccessServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fastSuccessServer.createContext("/health") { ex -> respondJson(ex, 200, """{"status":"ok"}""") }
        fastSuccessServer.start()

        try {
            val slowUrl = "http://127.0.0.1:${slowFailServer.address.port}"
            val fastUrl = "http://127.0.0.1:${fastSuccessServer.address.port}"
            profileStore.setTailscaleRigUrl(slowUrl)

            val okHttp = OkHttpClient()
            val router = LlmStageRouter(
                profileStore = profileStore,
                draftApiClientProvider = { url ->
                    DraftApiClient(configuredRigUrl = url, usbLoopbackUrl = url, okHttpClient = okHttp, healthProbeTimeoutMs = 1500L)
                },
                onDeviceEngine = FakeOnDeviceLlmBackend(),
                okHttpClient = okHttp
            )
            val vm = DraftDeckViewModel(
                application = app,
                llmRouterOverride = router,
                draftApiClientFactory = { url ->
                    val target = if (url == DEFAULT_MAC_RIG_LAN_URL) slowUrl else url
                    DraftApiClient(configuredRigUrl = target, usbLoopbackUrl = target, okHttpClient = okHttp, healthProbeTimeoutMs = 1500L)
                },
                profileStoreOverride = profileStore
            )
            // Let the slowUrl probe start in-flight, then switch tailscaleRigUrl to fastUrl
            Thread.sleep(50)
            profileStore.setTailscaleRigUrl(fastUrl)
            waitForCondition {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                vm.uiState.value.lastSeedRigOnline && vm.uiState.value.rigBaseUrl == fastUrl
            }
            // Wait beyond slowFailServer's 350ms delay to verify the cancelled slow probe cannot overwrite lastSeedRigOnline = false
            Thread.sleep(450)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Cancelled slow probe must not overwrite lastSeedRigOnline=false", vm.uiState.value.lastSeedRigOnline)
            assertEquals(fastUrl, vm.uiState.value.rigBaseUrl)
        } finally {
            slowFailServer.stop(0)
            fastSuccessServer.stop(0)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun fakeGenerativeModel(featureStatus: Int): GenerativeModel {
        return Proxy.newProxyInstance(
            GenerativeModel::class.java.classLoader,
            arrayOf(GenerativeModel::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "checkStatus" -> Integer.valueOf(featureStatus)
                "isStructuredOutputFeatureAvailable" -> java.lang.Boolean.valueOf(featureStatus == FeatureStatus.AVAILABLE)
                "isCachingFeatureAvailable" -> java.lang.Boolean.FALSE
                "close" -> Unit
                else -> throw UnsupportedOperationException("Unexpected GenerativeModel call: ${method.name}")
            }
        } as GenerativeModel
    }

    private class FakeOnDeviceLlmBackend(
        private val onAngles: suspend () -> Result<AnglesResponse> = { Result.failure(OnDeviceLlmException("unused")) },
        private val onArc: suspend () -> Result<ArcResponse> = { Result.failure(OnDeviceLlmException("unused")) },
        private val onOverlay: suspend () -> Result<GeneratedOverlayBriefResult> = { Result.failure(OnDeviceLlmException("unused")) }
    ) : OnDeviceLlmBackend {
        override suspend fun checkAvailability() = OnDevicePromptAvailability(FeatureStatus.AVAILABLE, true, true, false)
        override suspend fun generateBroadcastPackaging(draftPlainText: String) = Result.failure<StructuredBroadcastPackaging>(OnDeviceLlmException("unused"))
        override suspend fun generateOverlayBrief(briefTopic: String) = onOverlay()
        override suspend fun extractAngles(seedTranscript: String, steeringConstraints: List<String>) = onAngles()
        override suspend fun arc(seedTranscript: String, steeringConstraints: List<String>, selectedAngles: List<String>, targetSec: Int, rerollIndex: Int?, existingBeats: List<String>?) = onArc()
        override suspend fun synthesize(seedTranscript: String, selectedAngles: List<String>, steeringConstraints: List<String>, slot: Int?, existingDrafts: List<String>) = Result.failure<SynthesizeResponse>(OnDeviceLlmException("unused"))
        override suspend fun synthesizeScript(seedTranscript: String, selectedAngles: List<String>, steeringConstraints: List<String>, arcBeats: List<String>, targetSec: Int) = Result.failure<SynthesizeScriptResponse>(OnDeviceLlmException("unused"))
        override suspend fun brushSwap(markedText: String, steeringConstraints: List<String>, nudge: BrushNudge?, excludeAlternatives: List<String>) = Result.failure<BrushSwapResponse>(OnDeviceLlmException("unused"))
        override suspend fun seamHeal(markedText: String, operation: String, steeringConstraints: List<String>) = Result.failure<SeamHealResponse>(OnDeviceLlmException("unused"))
        override suspend fun adjustDensity(markedText: String, deltaPercent: Int, steeringConstraints: List<String>) = Result.failure<DensityResponse>(OnDeviceLlmException("unused"))
        override suspend fun rewrite(text: String, mode: String, steeringConstraints: List<String>) = Result.failure<RewriteResponse>(OnDeviceLlmException("unused"))
        override fun close() {}
    }

    private fun waitForCondition(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("Timed out after ${timeoutMs}ms waiting for condition", condition())
    }

    private fun respondJson(exchange: HttpExchange, statusCode: Int, jsonBody: String) {
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
