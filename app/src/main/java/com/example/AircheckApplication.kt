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
import com.example.agent.OnDevicePromptEngine
import com.example.appfunctions.AppFunctionAvailabilitySync
import com.example.data.gateway.DirectMacRigGateway
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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
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
    val agentOrchestrator: AircheckAgentOrchestrator by lazy {
        AircheckAgentOrchestrator(appContext)
    }
    val voiceModelCareManager: VoiceModelCareManager by lazy {
        VoiceModelCareManager(appContext, otaBundleSyncManager)
    }

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
            container.profileStore.preload()
            val settings = runCatching { container.profileStore.current() }.getOrNull()
            val phoneOnlyVoice = settings?.phoneOnlyVoiceMode ?: true
            // Scan local on-phone storage ONLY (zero network sync!).
            container.otaBundleSyncManager.refreshLocalState("")
            val rigOnline = if (phoneOnlyVoice) {
                false
            } else {
                val rigUrl = settings?.tailscaleRigUrl ?: DEFAULT_MAC_RIG_LAN_URL
                runCatching { container.macRigGateway.resolveAndCheckHealth(rigUrl) }.isSuccess
            }
            val bundleReady = container.otaBundleSyncManager.bundleState.value.status == OtaBundleStatus.READY
            AppFunctionAvailabilitySync.syncDynamicFunctionStates(
                context = this@AircheckApplication,
                isMacRigOnline = rigOnline,
                isLocalVoiceBundleReady = bundleReady
            )
        }
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttpClient }))
            }
            .allowHardware(true)
            .bitmapConfig(Bitmap.Config.HARDWARE)
            .build()
    }
}
