package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import com.example.agent.AircheckAgentOrchestrator
import com.example.agent.FallbackOnDeviceLlmBackend
import com.example.agent.LiteRtDraftingEngine
import com.example.agent.OnDeviceLlmBackend
import com.example.agent.OnDevicePromptEngine
import com.example.agent.liteRtAvailability
import com.example.ai.LlmStageRouter
import com.example.engine.ModelDownloadManager
import com.google.mlkit.genai.prompt.Generation
import com.example.appfunctions.AppFunctionAvailabilitySync
import com.example.data.gateway.DirectMacRigGateway
import com.example.data.gateway.MacRigHealthResult
import com.example.data.gateway.MacRigRenderGateway
import com.example.data.local.AircheckDatabase
import com.example.data.local.AircheckLocalRepository
import com.example.data.local.AircheckProfileStore
import com.example.data.local.CasMediaStore
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.draft.DraftApiClient
import com.example.draft.VadAudioCaptureEngine
import com.example.engine.AircheckPlayerController
import com.example.engine.AircheckProfilingCoordinator
import com.example.engine.ClipExporter
import com.example.engine.CoreStoryPipelineCoordinator
import com.example.engine.OnDeviceVoice
import com.example.engine.VoiceModelCareManager
import com.example.graph.GraphAppSearchIndexer
import com.example.graph.GraphRadarEngine
import com.example.graph.LocalGraphDatabase
import com.example.graph.XArchiveImporter
import com.voicestudio.edl.ClipperManifestRegistry
import com.voicestudio.edl.ClipperManifestStore
import com.voicestudio.edl.EdlController
import com.voicestudio.edl.OtaBundleStatus
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.sync.OtaBundleSyncManager
import com.voicestudio.sync.SyncConfigProvider
import com.voicestudio.sync.VoiceStudioRuntimeRegistry
import com.voicestudio.sync.VoiceStudioSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AircheckContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    val database: AircheckDatabase by lazy { AircheckDatabase.getInstance(appContext) }
    val localRepo: AircheckLocalRepository by lazy { AircheckLocalRepository(database) }
    val profileStore: AircheckProfileStore by lazy { AircheckProfileStore(appContext, appScope) }
    val casStore: CasMediaStore by lazy { CasMediaStore(appContext, database.casDao()) }
    val playerController: AircheckPlayerController by lazy { AircheckPlayerController(appContext, okHttpClient) }
    val clipExporter: ClipExporter by lazy { ClipExporter(appContext, casStore, okHttpClient) }
    val macRigGateway: MacRigRenderGateway by lazy { DirectMacRigGateway(casStore, okHttpClient) }
    val pipelineLogger: com.example.engine.PipelineLogger by lazy {
        com.example.engine.PipelineLogger(appContext, okHttpClient, appScope)
    }

    // VoiceStudio Local Synthesis, Wait-Free Oboe EDL & Mac Rig OTA Sync
    val otaBundleSyncManager: OtaBundleSyncManager by lazy {
        OtaBundleSyncManager(
            appContext,
            okHttpClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        )
    }
    val edlController: EdlController by lazy { EdlController() }
    val voiceStudioCoordinator: VoiceStudioSyncCoordinator by lazy {
        VoiceStudioSyncCoordinator(appContext, otaBundleSyncManager)
    }

    // Tactile Mobile Plain-Text Drafting Engine & Stage 4 Local Network Graph Radar
    val draftApiClient: DraftApiClient by lazy {
        DraftApiClient(
            configuredRigUrl = DEFAULT_MAC_RIG_LAN_URL,
            usbLoopbackUrl = DraftApiClient.DEFAULT_RIG_USB_URL,
            okHttpClient = okHttpClient
        )
    }
    private val draftClientsByUrl = ConcurrentHashMap<String, DraftApiClient>()

    fun draftApiClientFor(rigUrl: String): DraftApiClient {
        val normalized = rigUrl.trim().trimEnd('/').ifBlank { DEFAULT_MAC_RIG_LAN_URL }
        if (normalized == DEFAULT_MAC_RIG_LAN_URL.trimEnd('/')) {
            return draftApiClient
        }
        return draftClientsByUrl.computeIfAbsent(normalized) { url ->
            val usbUrl = if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
                url
            } else {
                DraftApiClient.DEFAULT_RIG_USB_URL
            }
            DraftApiClient(
                configuredRigUrl = url,
                usbLoopbackUrl = usbUrl,
                okHttpClient = okHttpClient,
                lanFallbackUrl = if (url.startsWith("https://")) DEFAULT_MAC_RIG_LAN_URL else null
            )
        }
    }

    private val _startupRigHealth = MutableStateFlow<MacRigHealthResult?>(null)
    val startupRigHealth: StateFlow<MacRigHealthResult?> = _startupRigHealth.asStateFlow()

    internal fun updateStartupRigHealth(health: MacRigHealthResult?) {
        _startupRigHealth.value = health
    }
    val vadAudioEngine: VadAudioCaptureEngine by lazy {
        VadAudioCaptureEngine(
            chunkCacheDir = File(appContext.cacheDir, "aircheck_vad"),
            attributionContext = appContext
        )
    }
    val localGraphDatabase: LocalGraphDatabase by lazy {
        LocalGraphDatabase(appContext)
    }
    val graphAppSearchIndexer: GraphAppSearchIndexer by lazy {
        GraphAppSearchIndexer(appContext)
    }
    val graphRadarEngine: GraphRadarEngine by lazy {
        GraphRadarEngine(localGraphDatabase)
    }
    val xArchiveImporter: XArchiveImporter by lazy { XArchiveImporter }
    val profilingCoordinator: AircheckProfilingCoordinator by lazy {
        AircheckProfilingCoordinator(appContext)
    }
    val onDevicePromptEngine: OnDevicePromptEngine by lazy {
        OnDevicePromptEngine()
    }

    // On-device drafting fallback: AICore Gemini Nano (TPU/NPU) primary, bundled LiteRT Gemma when
    // AICore is absent. Weights download from Spaces via the resumable ModelDownloadManager.
    val modelDownloadManager: ModelDownloadManager by lazy {
        ModelDownloadManager(appContext, okHttpClient)
    }
    val liteRtDraftingEngine: LiteRtDraftingEngine by lazy {
        LiteRtDraftingEngine(appContext)
    }
    private val liteRtBackedPromptEngine: OnDevicePromptEngine by lazy {
        OnDevicePromptEngine(
            modelProvider = { OnDevicePromptEngine.aicoreFullModelClient() }, // unused: completion is overridden
            // Weights load lazily on the first fallback pass, only once the verified file exists.
            completionOverride = { prompt ->
                liteRtDraftingEngine.generate(modelDownloadManager.modelFile(), prompt)
            },
            availabilityOverride = { liteRtAvailability(modelDownloadManager.isReady()) },
            engineLlmMeta = OnDevicePromptEngine.LITERT_GEMMA_LLM_META,
        )
    }
    val onDeviceLlmBackend: OnDeviceLlmBackend by lazy {
        FallbackOnDeviceLlmBackend(
            primary = onDevicePromptEngine,
            fallback = liteRtBackedPromptEngine,
            fallbackReady = { modelDownloadManager.isReady() },
        )
    }
    /**
     * Route Spark drafting and Script editing to this phone (AICore, Gemma fallback) or back to the
     * Mac rig default. Persisted; [LlmStageRouter] reads it on every pass.
     */
    fun setDraftOnPhone(onPhone: Boolean) {
        val provider = if (onPhone) {
            com.example.data.LlmProviderChoice.LOCAL_AICORE_NANO
        } else {
            com.example.data.LlmProviderChoice.MAC_RIG_DEFAULT
        }
        appScope.launch {
            profileStore.setLlmStageConfig(com.example.data.LlmStage.DRAFTING, provider)
            profileStore.setLlmStageConfig(com.example.data.LlmStage.SCRIPT_EDIT, provider)
            pipelineLogger.info("model", "drafting location set to ${provider.id}")
        }
    }

    /** Stop any transfer, unload the engine, then remove the Gemma weights and partial files. */
    fun deleteGemmaFallback() {
        com.example.engine.ModelDownloadWorker.pause(appContext)
        appScope.launch {
            runCatching { liteRtDraftingEngine.release() }
            modelDownloadManager.delete()
            pipelineLogger.info("model", "gemma fallback deleted")
        }
    }

    val llmStageRouter: LlmStageRouter by lazy {
        LlmStageRouter(
            profileStore = profileStore,
            draftApiClientProvider = ::draftApiClientFor,
            onDeviceEngine = onDeviceLlmBackend,
            okHttpClient = okHttpClient
        )
    }
    val agentOrchestrator: AircheckAgentOrchestrator by lazy {
        AircheckAgentOrchestrator(appContext)
    }
    val voiceModelCareManager: VoiceModelCareManager by lazy {
        VoiceModelCareManager(appContext, otaBundleSyncManager)
    }
    val coreStageRunner: com.example.engine.CoreStageRunner by lazy {
        com.example.engine.CoreStageRunner(localRepo, profileStore, corePipelineCoordinator, macRigGateway)
    }

    val corePipelineCoordinator: CoreStoryPipelineCoordinator by lazy {
        CoreStoryPipelineCoordinator(
            localRepo = localRepo,
            casStore = casStore,
            macRigGateway = macRigGateway,
            profileStore = profileStore,
            otaBundleSyncManager = otaBundleSyncManager,
            voiceStudioCoordinator = voiceStudioCoordinator,
            localCtcAligner = { wavFile, _ ->
                // Word timings for the clip voice come from Deepgram Nova-3 (one STT system).
                com.example.data.gateway.DeepgramClient(okHttpClient).transcribe(wavFile.readBytes()).words
            },
            onDeviceVoice = onDeviceVoice
        )
    }

    val onDeviceVoice: OnDeviceVoice by lazy { OnDeviceVoice(appContext) }

    fun bindVoiceStudioRuntime() {
        VoiceStudioRuntimeRegistry.otaBundleSyncManager = otaBundleSyncManager
        VoiceStudioRuntimeRegistry.preferenceStore = localRepo
        VoiceStudioRuntimeRegistry.projectStore = localRepo
        VoiceStudioRuntimeRegistry.configProvider = object : SyncConfigProvider {
            override suspend fun getTailscaleBaseUrl(): String =
                profileStore.current().tailscaleRigUrl.ifBlank { DEFAULT_MAC_RIG_LAN_URL }
        }
        ClipperManifestRegistry.store = object : ClipperManifestStore {
            override suspend fun getManifest(projectId: String): VoiceProjectManifest? =
                localRepo.getProjectManifest(projectId)

            override suspend fun saveManifest(manifest: VoiceProjectManifest) {
                localRepo.saveProjectManifest(manifest)
            }
        }
    }
}

class AircheckApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AircheckContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AircheckContainer(this)
        container.bindVoiceStudioRuntime()
        container.appScope.launch {
            container.profilingCoordinator.registerDefaultTriggers()
            val health = probeStartupRigHealthAndSync(
                context = this@AircheckApplication,
                profileStore = container.profileStore,
                otaBundleSyncManager = container.otaBundleSyncManager,
                macRigGateway = container.macRigGateway,
                onHealthResolved = container::updateStartupRigHealth
            )
            health?.resolvedUrl?.let { container.pipelineLogger.rigBaseUrl = it }
            container.pipelineLogger.info(
                "rig",
                if (health != null) "startup rig online at ${health.resolvedUrl}" else "startup rig offline"
            )
        }
    }

    internal suspend fun probeStartupRigHealthAndSyncForTest(
        macRigGatewayOverride: MacRigRenderGateway? = null
    ): MacRigHealthResult? = probeStartupRigHealthAndSync(
        context = this,
        profileStore = container.profileStore,
        otaBundleSyncManager = container.otaBundleSyncManager,
        macRigGateway = macRigGatewayOverride ?: container.macRigGateway,
        onHealthResolved = container::updateStartupRigHealth
    )

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttpClient }))
            }
            .allowHardware(true)
            .bitmapConfig(Bitmap.Config.HARDWARE)
            .build()
    }

    companion object {
        internal suspend fun probeStartupRigHealthAndSync(
            context: Context,
            profileStore: AircheckProfileStore,
            otaBundleSyncManager: OtaBundleSyncManager,
            macRigGateway: MacRigRenderGateway,
            onHealthResolved: (MacRigHealthResult?) -> Unit
        ): MacRigHealthResult? {
            profileStore.preload()
            val settings = runCatching { profileStore.current() }.getOrNull()
            // Scan local on-phone storage ONLY (zero network sync!).
            otaBundleSyncManager.refreshLocalState("")
            // Always probe Mac rig /health on startup (even when phoneOnlyVoiceMode == true)
            // without mutating phoneOnlyVoiceMode or any user provider setting.
            val rigUrl = settings?.tailscaleRigUrl?.trim()?.ifBlank { null } ?: DEFAULT_MAC_RIG_LAN_URL
            val health = runCatching { macRigGateway.resolveAndCheckHealth(rigUrl) }.getOrNull()
            onHealthResolved(health)
            val bundleReady = otaBundleSyncManager.bundleState.value.status == OtaBundleStatus.READY
            AppFunctionAvailabilitySync.syncDynamicFunctionStates(
                context = context,
                isMacRigOnline = health != null,
                isLocalVoiceBundleReady = bundleReady
            )
            return health
        }
    }
}
