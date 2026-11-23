package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import com.example.data.gateway.CloudIngestGateway
import com.example.data.gateway.CloudPersonaTrainingGateway
import com.example.data.gateway.CloudPoseGateGateway
import com.example.data.gateway.CloudRenderJobGateway
import com.example.data.gateway.CloudScriptGateway
import com.example.data.gateway.IngestGateway
import com.example.data.gateway.PersonaTrainingGateway
import com.example.data.gateway.PoseGateGateway
import com.example.data.gateway.RenderJobGateway
import com.example.data.gateway.ScriptGateway
import com.example.data.local.AircheckDatabase
import com.example.data.local.AircheckLocalRepository
import com.example.data.local.AircheckProfileStore
import com.example.data.local.CasMediaStore
import com.example.data.remote.AircheckAiRepository
import com.example.data.remote.FirestoreSyncRepository
import com.example.engine.AircheckPlayerController
import com.example.engine.AudioEngine
import com.example.engine.ClipExporter
import com.voicestudio.edl.EdlController
import com.voicestudio.sync.OtaBundleSyncManager
import com.voicestudio.sync.SyncConfigProvider
import com.voicestudio.sync.VoiceStudioRuntimeRegistry
import com.voicestudio.sync.VoiceStudioSyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AircheckContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val database: AircheckDatabase by lazy { AircheckDatabase.getInstance(appContext) }
    val localRepo: AircheckLocalRepository by lazy { AircheckLocalRepository(database) }
    val profileStore: AircheckProfileStore by lazy { AircheckProfileStore(appContext, appScope) }
    val casStore: CasMediaStore by lazy { CasMediaStore(appContext, database.casDao()) }
    val audioEngine: AudioEngine by lazy { AudioEngine() }
    val playerController: AircheckPlayerController by lazy { AircheckPlayerController(appContext, okHttpClient) }
    val clipExporter: ClipExporter by lazy { ClipExporter(appContext, casStore, okHttpClient) }
    val firestoreRepo: FirestoreSyncRepository by lazy { FirestoreSyncRepository(appContext) }

    val aiRepo: AircheckAiRepository by lazy { AircheckAiRepository() }
    val scriptGateway: ScriptGateway by lazy { CloudScriptGateway(aiRepo) }
    val ingestGateway: IngestGateway by lazy { CloudIngestGateway(okHttpClient) }
    val poseGateGateway: PoseGateGateway by lazy { CloudPoseGateGateway(casStore, okHttpClient) }
    val renderJobGateway: RenderJobGateway by lazy { CloudRenderJobGateway(appContext, casStore) }
    val personaTrainingGateway: PersonaTrainingGateway by lazy { CloudPersonaTrainingGateway(appContext) }

    // VoiceStudio Bare-Metal Local Synthesis, Wait-Free Oboe EDL & Tailscale OTA Sync
    val otaBundleSyncManager: OtaBundleSyncManager by lazy {
        OtaBundleSyncManager(appContext, okHttpClient)
    }
    val edlController: EdlController by lazy { EdlController() }
    val voiceStudioCoordinator: VoiceStudioSyncCoordinator by lazy {
        VoiceStudioSyncCoordinator(appContext, otaBundleSyncManager)
    }

    fun bindVoiceStudioRuntime() {
        VoiceStudioRuntimeRegistry.otaBundleSyncManager = otaBundleSyncManager
        VoiceStudioRuntimeRegistry.preferenceStore = localRepo
        VoiceStudioRuntimeRegistry.projectStore = localRepo
        VoiceStudioRuntimeRegistry.configProvider = object : SyncConfigProvider {
            override suspend fun getTailscaleBaseUrl(): String =
                profileStore.current().tailscaleRigUrl
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
        // Preload binary Proto DataStore & local OTA bundle state off main thread (<200 ms cold start).
        container.appScope.launch {
            container.profileStore.preload()
            val rigUrl = runCatching { container.profileStore.current().tailscaleRigUrl }.getOrDefault("")
            container.otaBundleSyncManager.refreshLocalState(rigUrl)
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
