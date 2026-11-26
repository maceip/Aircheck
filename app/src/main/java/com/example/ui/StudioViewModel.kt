package com.example.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AircheckApplication
import com.example.appfunctions.AppFunctionAvailabilitySync
import com.example.data.BrandChannel
import com.example.data.BreakingNewsStyle
import com.example.data.BrollSegmentMode
import com.example.data.ClipCardRow
import com.example.data.ClipRecord
import com.example.data.CorePipelineSession
import com.example.data.CoreRenderOptions
import com.example.data.NewsroomLook
import com.example.engine.CoreStageWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.data.CorePipelineStage
import com.example.data.CoreStoryClause
import com.example.data.ExportState
import com.example.data.LastVideoSummary
import com.example.data.OverlayObjectKind
import com.example.data.RenderJobStatus
import com.example.data.SavedNewsroomOverlay
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import com.example.data.estimateDurationSeconds
import com.example.data.gateway.MacRigRenderGateway
import com.example.data.gateway.RemoteRenderSummary
import com.example.data.local.AircheckLocalRepository
import com.example.data.local.AircheckProfileStore
import com.example.data.local.CasMediaStore
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.data.local.StudioSettings
import com.example.draft.ShotSpec
import com.example.draft.ShotType
import com.example.engine.AircheckPlayerController
import com.example.engine.CoreStoryPipelineCoordinator
import com.example.engine.VoiceModelCareManager
import com.example.engine.VoiceQualityReport
import com.example.engine.VoiceStorageSnapshot
import com.example.graph.LocalGraphDatabase
import com.example.ui.a2ui.AircheckA2uiController
import com.example.ui.a2ui.NewsroomA2uiAction
import com.voicestudio.edl.OtaBundleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class RigConnectionStatus {
    IDLE,
    CHECKING,
    ONLINE,
    ERROR
}

data class RigHealthUiState(
    val status: RigConnectionStatus = RigConnectionStatus.IDLE,
    val resolvedUrl: String = DEFAULT_MAC_RIG_LAN_URL,
    val loraVersion: Int = 0,
    val bundleVersion: String? = null,
    val pendingPreferences: Int = 0,
    val renderBridgeAvailable: Boolean = false,
    val errorMessage: String? = null
)

data class StudioUiState(
    val projects: List<ScriptProject> = emptyList(),
    val activeProject: ScriptProject = ScriptProject(voiceSource = VoiceSource.ENROLLED_VOICE_CLONE),
    val clipCards: List<ClipCardRow> = emptyList(),
    val remoteThumbUrls: Map<String, String> = emptyMap(),
    val settings: StudioSettings = StudioSettings(),
    val casTotalBytes: Long = 0L,
    val isEnqueuingRender: Boolean = false,
    val exportState: ExportState = ExportState.IDLE,
    val pendingShareIntent: Intent? = null,
    val liveNavToClipId: String? = null,
    val openCorePipelineEvent: Boolean = false,
    val corePipelineSession: CorePipelineSession = CorePipelineSession(),
    val savedOverlays: List<SavedNewsroomOverlay> = emptyList(),
    val isCoreStageBusy: Boolean = false,
    val coreStageProgress: Float = 0f,
    val coreStageStatusLabel: String? = null,
    val coreStageOp: String? = null,
    val isRecordingCoreVoiceover: Boolean = false,
    val coreVoiceoverRmsLevel: Float = 0f,
    val newsroomLooks: List<NewsroomLook> = emptyList(),
    val isGeneratingBackdrop: Boolean = false,
    val isGeneratingOverlaySet: Boolean = false,
    val isDetectingSilence: Boolean = false,
    val coreUndoDepth: Int = 0,
    val errorMessage: String? = null,
    val statusNotice: String? = null,
    val toastMessage: String? = null,
    val lastCompletedVideo: LastVideoSummary? = null,
    val openClauseIndex: Int? = null,
    val isRefreshingHeadline: Boolean = false,
    val isTweakingLook: Boolean = false,
    val otaBundleState: OtaBundleState = OtaBundleState(),
    val rigHealth: RigHealthUiState = RigHealthUiState(),
    val voiceStorageSnapshot: VoiceStorageSnapshot? = null,
    val voiceQualityReport: VoiceQualityReport? = null,
    val isCheckingVoiceQuality: Boolean = false,
    val isVerifyingVoiceFiles: Boolean = false,
    val isRecordingVoiceCareSample: Boolean = false,
    val isInstallingRemotion: Boolean = false,
    val remotionStatusMessage: String = "Remotion has not been used yet."
) {
    val isRemotionUnused: Boolean
        get() = settings.remotionUsedCount <= 0 &&
            settings.providers.packageProviderId != "mac_rig_remotion"

    val openClause: Int?
        get() = openClauseIndex
}

/** An X post chosen with "show post" in 02 SCRIPT, burned into the cut as a quote card. */
data class PostCardSource(val handle: String, val tweetId: String, val snippet: String)

/** Derives the tiny 4-LED status cluster (RIG · VOX · HEAD · CUT) from live pipeline state. */
fun pipelineLedStateFor(state: StudioUiState): com.example.ui.clipstudio.PipelineLedState {
    val session = state.corePipelineSession
    val busy = state.isCoreStageBusy
    val op = state.coreStageOp
    fun stage(done: Boolean, vararg ops: String): com.example.ui.clipstudio.LedState = when {
        busy && op in ops -> com.example.ui.clipstudio.LedState.WORKING
        done -> com.example.ui.clipstudio.LedState.READY
        else -> com.example.ui.clipstudio.LedState.IDLE
    }
    val rig = when (state.rigHealth.status) {
        RigConnectionStatus.ONLINE -> com.example.ui.clipstudio.LedState.READY
        RigConnectionStatus.CHECKING -> com.example.ui.clipstudio.LedState.WORKING
        RigConnectionStatus.ERROR -> com.example.ui.clipstudio.LedState.ERROR
        RigConnectionStatus.IDLE -> com.example.ui.clipstudio.LedState.IDLE
    }
    return com.example.ui.clipstudio.PipelineLedState(
        rig = rig,
        voice = stage(!session.ttsVoiceSha.isNullOrBlank(), CoreStageWorker.OP_TTS, CoreStageWorker.OP_RESYNC),
        head = stage(!session.talkingHeadSha.isNullOrBlank(), CoreStageWorker.OP_HEAD, CoreStageWorker.OP_RESYNC),
        cut = stage(!session.finalVideoSha.isNullOrBlank(), CoreStageWorker.OP_CUT)
    )
}

class StudioViewModel internal constructor(
    application: Application,
    localRepoOverride: AircheckLocalRepository? = null,
    profileStoreOverride: AircheckProfileStore? = null,
    casStoreOverride: CasMediaStore? = null,
    macRigGatewayOverride: MacRigRenderGateway? = null,
    localGraphDatabaseOverride: LocalGraphDatabase? = null
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        localRepoOverride = null
    )

    private val container = (application as AircheckApplication).container
    private val localRepo: AircheckLocalRepository = localRepoOverride ?: container.localRepo
    private val profileStore: AircheckProfileStore = profileStoreOverride ?: container.profileStore
    val casStore: CasMediaStore = casStoreOverride ?: container.casStore
    val playerController: AircheckPlayerController = container.playerController
    private val clipExporter = container.clipExporter
    private val macRigGateway: MacRigRenderGateway = macRigGatewayOverride ?: container.macRigGateway
    private val localGraphDatabase: LocalGraphDatabase = localGraphDatabaseOverride ?: container.localGraphDatabase
    private val otaBundleSyncManager = container.otaBundleSyncManager
    val voiceModelCareManager: VoiceModelCareManager = container.voiceModelCareManager
    private val pipelineLogger = container.pipelineLogger
    private val corePipelineCoordinator: CoreStoryPipelineCoordinator =
        if (localRepoOverride != null || casStoreOverride != null || macRigGatewayOverride != null || profileStoreOverride != null) {
            CoreStoryPipelineCoordinator(
                localRepo = localRepo,
                casStore = casStore,
                macRigGateway = macRigGateway,
                profileStore = profileStore
            )
        } else {
            container.corePipelineCoordinator
        }
    private val onDevicePromptEngine = container.onDevicePromptEngine
    private val vadAudioEngine = container.vadAudioEngine

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    val a2uiController: AircheckA2uiController = AircheckA2uiController(
        context = application,
        scope = viewModelScope,
        onAction = ::handleNewsroomA2uiAction
    )

    private var rigHealthCheckJob: Job? = null
    private val scriptSaveMutex = Mutex()
    private var initializedProject = false
    private var didInitialAutoHealthCheck = false

    init {
        a2uiController.syncNewsroomSurface(
            remotionUnused = true,
            statusMessage = _uiState.value.remotionStatusMessage
        )

        viewModelScope.launch {
            profileStore.settingsFlow.collect { settings ->
                val remotionUnused = settings.remotionUsedCount <= 0 &&
                    settings.providers.packageProviderId != "mac_rig_remotion"
                val nextRemotionMsg = when {
                    !remotionUnused -> "Remotion active (${settings.remotionUsedCount} uses). Save reusable overlays for News Briefs."
                    settings.remotionInstallRequested -> "Install requested (${settings.remotionInstallNote}); waiting on studio rig."
                    else -> "Remotion has not been used yet."
                }
                _uiState.update {
                    it.copy(
                        settings = settings,
                        remotionStatusMessage = nextRemotionMsg
                    )
                }
                val activeProj = _uiState.value.activeProject
                a2uiController.syncNewsroomSurface(
                    remotionUnused = remotionUnused,
                    statusMessage = nextRemotionMsg,
                    defaultHeadline = activeProj.stageConfig.chyronHeadline.ifBlank { activeProj.title },
                    defaultSubline = activeProj.stageConfig.chyronSubline.ifBlank { "AIRCHECK NEWS BRIEF" },
                    defaultBriefTopic = activeProj.ideaSpark
                )
                if (!didInitialAutoHealthCheck) {
                    didInitialAutoHealthCheck = true
                    testMacRigConnection(settings.tailscaleRigUrl)
                }
            }
        }

        viewModelScope.launch {
            casStore.totalBytesFlow.collect { bytes ->
                _uiState.update { it.copy(casTotalBytes = bytes) }
            }
        }

        viewModelScope.launch {
            var lastUiEmitMs = 0L
            vadAudioEngine.liveRmsLevel.collect { rms ->
                val nowMs = System.currentTimeMillis()
                if (rms != 0f && nowMs - lastUiEmitMs < 100L) return@collect
                lastUiEmitMs = nowMs
                _uiState.update { state ->
                    if (state.isRecordingCoreVoiceover || rms == 0f) {
                        state.copy(coreVoiceoverRmsLevel = rms)
                    } else {
                        state
                    }
                }
            }
        }

        viewModelScope.launch {
            localRepo.clipCards.collect { cards ->
                _uiState.update { it.copy(clipCards = cards) }
                recomputeLastCompletedVideo(cards)
            }
        }

        viewModelScope.launch {
            localRepo.savedNewsroomOverlays.collect { overlays ->
                _uiState.update { it.copy(savedOverlays = overlays) }
            }
        }

        viewModelScope.launch {
            localRepo.newsroomLooks.collect { looks ->
                _uiState.update { it.copy(newsroomLooks = looks) }
            }
        }

        viewModelScope.launch {
            localRepo.latestCorePipelineSession.collect { session ->
                if (session != null) {
                    _uiState.update { current ->
                        if (session.updatedAtMillis >= current.corePipelineSession.updatedAtMillis ||
                            current.corePipelineSession.clauses.isEmpty()
                        ) {
                            current.copy(corePipelineSession = session)
                        } else {
                            current
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            localRepo.scripts.collect { savedProjects ->
                if (!initializedProject) {
                    initializedProject = true
                    if (savedProjects.isEmpty()) {
                        val initial = ScriptProject(voiceSource = VoiceSource.ENROLLED_VOICE_CLONE)
                        localRepo.saveScript(initial)
                        _uiState.update { it.copy(projects = listOf(initial), activeProject = initial) }
                    } else {
                        val preferredId = _uiState.value.settings.activeScriptId
                        val chosen = savedProjects.firstOrNull { it.id == preferredId } ?: savedProjects.first()
                        _uiState.update { it.copy(projects = savedProjects, activeProject = chosen) }
                    }
                } else {
                    val currentActive = _uiState.value.activeProject
                    val fromRoom = savedProjects.firstOrNull { it.id == currentActive.id }
                    val updatedActive = when {
                        fromRoom != null && fromRoom.updatedAtMillis >= currentActive.updatedAtMillis -> fromRoom
                        fromRoom != null -> currentActive
                        else -> savedProjects.firstOrNull() ?: currentActive
                    }
                    _uiState.update { it.copy(projects = savedProjects, activeProject = updatedActive) }
                }
            }
        }

        viewModelScope.launch {
            otaBundleSyncManager.bundleState.collect { otaState ->
                _uiState.update { it.copy(otaBundleState = otaState) }
                AppFunctionAvailabilitySync.syncDynamicFunctionStates(
                    context = getApplication(),
                    isMacRigOnline = _uiState.value.rigHealth.status == RigConnectionStatus.ONLINE,
                    isLocalVoiceBundleReady = otaState.status == com.voicestudio.edl.OtaBundleStatus.READY
                )
            }
        }

        refreshVoiceModelCare(verifyChecksums = false)
        observeBackgroundStages()
    }

    private fun handleNewsroomA2uiAction(action: NewsroomA2uiAction) {
        when (action) {
            is NewsroomA2uiAction.AskInstallRemotion -> {
                requestRemotionInstall(
                    userRequest = action.userPrompt,
                    enableBrollPopups = action.enableBrollPopups
                )
            }

            is NewsroomA2uiAction.GenerateAiOverlayBrief -> {
                generateAiOverlayOnNewsroomPage(action.briefTopic)
            }

            is NewsroomA2uiAction.SaveOverlayObject -> {
                saveNewsroomOverlayObject(
                    kind = action.kind,
                    headline = action.headline,
                    subline = action.subline,
                    calloutStat = action.calloutStat,
                    durationSec = action.durationSec,
                    a2uiComponentsJson = action.a2uiComponentsJson
                )
            }
        }
    }

    fun requestRemotionInstall(
        userRequest: String,
        enableBrollPopups: Boolean = true
    ) {
        val cleanPrompt = userRequest.trim().ifBlank {
            AircheckA2uiController.DEFAULT_REMOTION_INSTALL_PROMPT
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isInstallingRemotion = true,
                    errorMessage = null,
                    remotionStatusMessage = "Requesting Remotion setup…"
                )
            }
            a2uiController.syncNewsroomSurface(
                remotionUnused = _uiState.value.isRemotionUnused,
                statusMessage = "Requesting Remotion setup…"
            )
            val persisted = runCatching { profileStore.current() }.getOrDefault(_uiState.value.settings)
            val rigUrl = persisted.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
            val result = runCatching {
                macRigGateway.requestRemotionInstall(
                    rigBaseUrl = rigUrl,
                    userRequest = cleanPrompt,
                    enableBrollPopups = enableBrollPopups
                )
            }
            result.onSuccess { res ->
                profileStore.recordRemotionInstallRequest(
                    userNote = cleanPrompt,
                    installedOnRig = res.installed
                )
                val updatedSettings = runCatching { profileStore.current() }.getOrDefault(_uiState.value.settings)
                val statusMsg = if (res.installed) {
                    "Remotion ${res.remotionVersion ?: "ready"} installed for speaking pop-ups & B-roll."
                } else {
                    "Remotion install request recorded; Remotion worker not yet installed on rig."
                }
                _uiState.update {
                    it.copy(
                        settings = updatedSettings,
                        isInstallingRemotion = false,
                        statusNotice = statusMsg,
                        remotionStatusMessage = statusMsg
                    )
                }
                val activeProj = _uiState.value.activeProject
                a2uiController.syncNewsroomSurface(
                    remotionUnused = !res.installed,
                    statusMessage = statusMsg,
                    defaultHeadline = activeProj.stageConfig.chyronHeadline.ifBlank { activeProj.title },
                    defaultSubline = activeProj.stageConfig.chyronSubline.ifBlank { "AIRCHECK NEWS BRIEF" },
                    defaultBriefTopic = activeProj.ideaSpark
                )
            }.onFailure { err ->
                if (err is CancellationException) throw err
                profileStore.recordRemotionInstallRequest(
                    userNote = cleanPrompt,
                    installedOnRig = false
                )
                val updatedSettings = runCatching { profileStore.current() }.getOrDefault(_uiState.value.settings)
                val honestMsg = "Install request saved on phone; studio server unreachable (${err.localizedMessage ?: "offline"})."
                _uiState.update {
                    it.copy(
                        settings = updatedSettings,
                        isInstallingRemotion = false,
                        errorMessage = honestMsg,
                        remotionStatusMessage = honestMsg
                    )
                }
                a2uiController.syncNewsroomSurface(
                    remotionUnused = true,
                    statusMessage = honestMsg
                )
            }
        }
    }

    /**
     * Generates AI-authored overlay fields on the optional Newsroom Overlay Studio page:
     * tries on-device Gemini Nano (`OnDevicePromptEngine`) first if installed, otherwise queries
     * Mac Studio `POST /v1/ai-assist` (`mode = "generate_overlay_brief"`). Reports honest status if offline.
     */
    fun generateAiOverlayOnNewsroomPage(briefTopic: String) {
        val cleanTopic = briefTopic.trim().ifBlank {
            _uiState.value.corePipelineSession.fullStoryText.ifBlank {
                _uiState.value.activeProject.ideaSpark.trim()
            }
        }
        if (cleanTopic.isEmpty()) {
            val msg = "Enter a news brief topic before generating an AI overlay."
            _uiState.update { it.copy(errorMessage = msg) }
            a2uiController.syncNewsroomSurface(remotionUnused = false, statusMessage = msg)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(errorMessage = null) }
            a2uiController.syncNewsroomSurface(
                remotionUnused = false,
                statusMessage = "Generating AI overlay draft…"
            )

            val onDeviceResult = onDevicePromptEngine.generateBroadcastPackaging(cleanTopic)
            if (onDeviceResult.isSuccess) {
                val pkg = onDeviceResult.getOrThrow()
                val msg = "Generated on-device AI overlay — review and click Save Overlay."
                a2uiController.updateOverlayDraftFields(
                    headline = pkg.chyronHeadline,
                    subline = pkg.chyronSubline,
                    calloutStat = pkg.primaryAngleLabel,
                    statusMessage = msg
                )
                _uiState.update { it.copy(statusNotice = msg) }
                return@launch
            }

            val persisted = runCatching { profileStore.current() }.getOrDefault(_uiState.value.settings)
            val rigUrl = persisted.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
            val remoteRes = runCatching {
                val health = macRigGateway.resolveAndCheckHealth(rigUrl)
                macRigGateway.generateAiOverlayBrief(health.resolvedUrl, cleanTopic)
            }
            remoteRes.onSuccess { brief ->
                val msg = "AI overlay draft generated — review and save for News Briefs."
                a2uiController.updateOverlayDraftFields(
                    headline = brief.headline,
                    subline = brief.subline,
                    calloutStat = brief.calloutStat,
                    statusMessage = msg
                )
                _uiState.update { it.copy(statusNotice = msg) }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val honestMsg = "AI overlay service offline (${err.localizedMessage ?: "unreachable"}) — edit overlay fields directly and save."
                _uiState.update { it.copy(errorMessage = honestMsg) }
                a2uiController.syncNewsroomSurface(remotionUnused = false, statusMessage = honestMsg)
            }
        }
    }

    fun saveNewsroomOverlayObject(
        kind: OverlayObjectKind,
        headline: String,
        subline: String,
        calloutStat: String,
        durationSec: Int = 4,
        a2uiComponentsJson: String = "",
        breakingStyle: BreakingNewsStyle = _uiState.value.corePipelineSession.renderOptions.breakingStyle
    ) {
        val cleanHeadline = headline.trim()
        if (cleanHeadline.isEmpty()) {
            val msg = "Enter an overlay headline before saving."
            _uiState.update { it.copy(errorMessage = msg) }
            a2uiController.syncNewsroomSurface(remotionUnused = false, statusMessage = msg)
            return
        }
        val cleanSubline = subline.trim()
        val cleanStat = calloutStat.trim()
        val jsonPayload = a2uiComponentsJson.ifBlank {
            AircheckA2uiController.serializeOverlayAsA2uiComponentsJson(
                headline = cleanHeadline,
                subline = cleanSubline,
                calloutStat = cleanStat
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = SavedNewsroomOverlay(
                kind = kind,
                breakingStyle = breakingStyle,
                headline = cleanHeadline,
                subline = cleanSubline,
                calloutStat = cleanStat,
                durationSec = durationSec.coerceIn(2, 15),
                a2uiComponentsJson = jsonPayload
            )
            localRepo.saveNewsroomOverlay(saved)
            profileStore.incrementRemotionUsedCount()
            val msg = "Saved '${saved.headline}' for News Briefs."
            _uiState.update {
                it.copy(
                    errorMessage = null,
                    statusNotice = msg,
                    remotionStatusMessage = msg
                )
            }
            a2uiController.syncNewsroomSurface(remotionUnused = false, statusMessage = msg)
        }
    }

    /**
     * Generates a cohesive 3-piece broadcast overlay set (`LOWER_THIRD`, `STAT_CALLOUT`, `QUOTE_BRIEF`)
     * on the studio rig (`POST /v1/ai-assist`, `mode = "generate_overlay_set"`) styled with the active
     * [BreakingNewsStyle], persists all overlays in Room, and attaches them to the active studio cut.
     */
    fun generateNewsroomOverlaySet(briefTopicOverride: String = "") {
        if (_uiState.value.isGeneratingOverlaySet) return
        val cleanTopic = briefTopicOverride.trim().ifBlank {
            _uiState.value.corePipelineSession.fullStoryText.ifBlank {
                _uiState.value.activeProject.ideaSpark.trim()
            }
        }
        if (cleanTopic.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Enter a brief topic or load a story before generating overlays.")
            }
            return
        }
        val activeStyle = _uiState.value.corePipelineSession.renderOptions.breakingStyle
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    isGeneratingOverlaySet = true,
                    errorMessage = null,
                    statusNotice = "Generating ${activeStyle.chipLabel} overlay set on rig…"
                )
            }
            val persisted = runCatching { profileStore.current() }.getOrDefault(_uiState.value.settings)
            val rigUrl = persisted.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
            val remoteRes = runCatching {
                val health = macRigGateway.resolveAndCheckHealth(rigUrl)
                macRigGateway.generateAiOverlaySet(
                    rigBaseUrl = health.resolvedUrl,
                    briefPrompt = cleanTopic,
                    breakingStyle = activeStyle
                )
            }
            remoteRes.onSuccess { generatedList ->
                val savedItems = generatedList.map { item ->
                    SavedNewsroomOverlay(
                        kind = item.kind,
                        breakingStyle = item.breakingStyle,
                        headline = item.headline.trim(),
                        subline = item.subline.trim(),
                        calloutStat = item.calloutStat.trim(),
                        durationSec = item.durationSec.coerceIn(2, 15),
                        a2uiComponentsJson = AircheckA2uiController.serializeOverlayAsA2uiComponentsJson(
                            headline = item.headline.trim(),
                            subline = item.subline.trim(),
                            calloutStat = item.calloutStat.trim()
                        )
                    )
                }
                savedItems.forEach { localRepo.saveNewsroomOverlay(it) }
                profileStore.incrementRemotionUsedCount()
                val currentSession = _uiState.value.corePipelineSession
                commitCoreEdit(
                    currentSession.copy(
                        selectedOverlayIds = savedItems.map { it.id }
                    )
                )
                val primary = savedItems.firstOrNull()
                val msg = "Generated ${savedItems.size} ${activeStyle.chipLabel} overlays & attached to cut."
                if (primary != null) {
                    a2uiController.updateOverlayDraftFields(
                        headline = primary.headline,
                        subline = primary.subline,
                        calloutStat = primary.calloutStat,
                        statusMessage = msg
                    )
                }
                _uiState.update {
                    it.copy(
                        isGeneratingOverlaySet = false,
                        errorMessage = null,
                        statusNotice = msg,
                        remotionStatusMessage = msg
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val honestMsg = "Overlay set generation failed: ${err.localizedMessage ?: "rig unreachable"}."
                _uiState.update {
                    it.copy(
                        isGeneratingOverlaySet = false,
                        errorMessage = honestMsg
                    )
                }
            }
        }
    }

    fun deleteSavedNewsroomOverlay(overlayId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            localRepo.deleteNewsroomOverlay(overlayId)
            val current = _uiState.value.corePipelineSession
            if (overlayId in current.selectedOverlayIds) {
                val updated = current.copy(
                    selectedOverlayIds = current.selectedOverlayIds - overlayId,
                    updatedAtMillis = System.currentTimeMillis()
                )
                localRepo.saveCorePipelineSession(updated)
                _uiState.update { it.copy(corePipelineSession = updated) }
            }
        }
    }

    // =========================================================================
    // Single Canonical 5-Stage Core Pipeline Actions
    // (`TTS -> Iterate on Story -> Speech to Talking Head -> Chop into B-Roll -> Final Review`)
    // =========================================================================

    /**
     * Routes any drafted story into Stage 1 (`01 TTS`) of the single canonical Core Pipeline
     * instead of bypassing straight to a one-shot clip render, mapping any SPARK/SCRIPT
     * [shotPlan] (`PIP`/`BROLL` -> `BROLL_CUTAWAY`, `CARD` -> real Room `QUOTE_BRIEF` overlay).
     */
    fun openStoryInCorePipeline(
        plainText: String,
        headline: String = "",
        postCard: PostCardSource? = null,
        shotPlan: List<List<ShotSpec>> = emptyList(),
        autoStartTts: Boolean = false
    ) {
        val clean = plainText.trim()
        if (clean.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Write or speak a story draft before entering the Core Pipeline.")
            }
            return
        }
        val effectiveHeadline = headline.trim().ifBlank {
            clean.split('.', '!', '?').firstOrNull().orEmpty().trim().take(40)
        }
        val project = _uiState.value.activeProject
        persistProject(
            project.copy(
                title = effectiveHeadline.ifBlank { "Story Draft" },
                ideaSpark = clean,
                stageConfig = project.stageConfig.copy(
                    chyronHeadline = effectiveHeadline.ifBlank { project.stageConfig.chyronHeadline }
                ),
                beats = listOf(
                    ScriptBeat(
                        index = 0,
                        spokenText = clean,
                        durationSec = estimateDurationSeconds(clean, 8).coerceIn(4, 30)
                    )
                )
            )
        )
        viewModelScope.launch(Dispatchers.IO) {
            val rawClauses = CoreStoryPipelineCoordinator.splitStoryIntoClauses(clean)
            val mappedClauses = applyShotPlanToClauses(rawClauses, shotPlan)
            var selectedOverlayIds = emptyList<String>()
            var newlyCreatedOverlay: SavedNewsroomOverlay? = null

            if (postCard != null) {
                // "show post" in 02 SCRIPT: burn the quoted X post as a QUOTE_BRIEF card in the cut.
                val overlay = SavedNewsroomOverlay(
                    id = "ovl_x_" + postCard.tweetId.filter { it.isLetterOrDigit() }.take(24),
                    kind = OverlayObjectKind.QUOTE_BRIEF,
                    headline = "@${postCard.handle}",
                    subline = postCard.snippet.take(64),
                    calloutStat = "POST",
                    durationSec = 5,
                    a2uiComponentsJson = AircheckA2uiController.serializeOverlayAsA2uiComponentsJson(
                        headline = "@${postCard.handle}",
                        subline = postCard.snippet.take(64),
                        calloutStat = "POST"
                    )
                )
                localRepo.saveNewsroomOverlay(overlay)
                newlyCreatedOverlay = overlay
                selectedOverlayIds = listOf(overlay.id)
            } else {
                val cardShot = shotPlan.flatten().firstOrNull { it.type == ShotType.CARD }
                if (cardShot != null) {
                    val fallbackQuote = mappedClauses.lastOrNull()?.spokenText ?: effectiveHeadline
                    val quoteHeadline = sanitizeCardQuoteHeadline(
                        rawPrompt = cardShot.prompt,
                        fallbackText = fallbackQuote,
                        maxChars = 56
                    )
                    val quoteSubline = truncateOnWordBoundary(effectiveHeadline, maxChars = 56)
                    val existingOverlays = localRepo.listSavedNewsroomOverlays()
                    val existingQuote = existingOverlays.firstOrNull {
                        it.kind == OverlayObjectKind.QUOTE_BRIEF &&
                            it.headline.equals(quoteHeadline, ignoreCase = true)
                    }
                    if (existingQuote != null) {
                        selectedOverlayIds = listOf(existingQuote.id)
                    } else if (quoteHeadline.isNotEmpty()) {
                        val cardOverlay = SavedNewsroomOverlay(
                            kind = OverlayObjectKind.QUOTE_BRIEF,
                            breakingStyle = BreakingNewsStyle.CLEAN,
                            headline = quoteHeadline,
                            subline = quoteSubline,
                            calloutStat = "QUOTE",
                            durationSec = 4,
                            a2uiComponentsJson = AircheckA2uiController.serializeOverlayAsA2uiComponentsJson(
                                headline = quoteHeadline,
                                subline = quoteSubline,
                                calloutStat = "QUOTE"
                            )
                        )
                        localRepo.saveNewsroomOverlay(cardOverlay)
                        newlyCreatedOverlay = cardOverlay
                        selectedOverlayIds = listOf(cardOverlay.id)
                    }
                }
            }

            val nextStamp = maxOf(
                System.currentTimeMillis(),
                _uiState.value.corePipelineSession.updatedAtMillis + 1L
            )
            val baseOptions = CoreRenderOptions(chyronHeadline = effectiveHeadline)
            val session = CorePipelineSession(
                scriptId = project.id,
                title = effectiveHeadline,
                stage = CorePipelineStage.TTS,
                clauses = mappedClauses,
                selectedOverlayIds = selectedOverlayIds,
                renderOptions = baseOptions,
                updatedAtMillis = nextStamp
            )
            localRepo.saveCorePipelineSession(session)

            _uiState.update { state ->
                val mergedOverlays = if (newlyCreatedOverlay != null && state.savedOverlays.none { it.id == newlyCreatedOverlay.id }) {
                    listOf(newlyCreatedOverlay) + state.savedOverlays
                } else {
                    state.savedOverlays
                }
                state.copy(
                    corePipelineSession = session,
                    savedOverlays = mergedOverlays,
                    openClauseIndex = null,
                    openCorePipelineEvent = true,
                    errorMessage = null,
                    statusNotice = "Story loaded in Core Pipeline (01 TTS)."
                )
            }
            if (autoStartTts) {
                runCoreStage1Tts()
            }
        }
    }

    /**
     * Retained as a forwarding alias so any existing caller routes strictly into [openStoryInCorePipeline].
     */
    fun renderDraftAsBroadcastClip(
        plainText: String,
        headline: String = ""
    ) {
        openStoryInCorePipeline(plainText = plainText, headline = headline)
    }

    fun consumeOpenCorePipelineEvent() {
        _uiState.update { it.copy(openCorePipelineEvent = false) }
    }

    fun updateCoreStoryRawText(rawText: String) {
        val current = _uiState.value.corePipelineSession
        val split = CoreStoryPipelineCoordinator.splitStoryIntoClauses(rawText, current.clauses)
        val optimistic = current.copy(
            clauses = split,
            title = current.title.ifBlank { split.firstOrNull()?.spokenText?.take(40).orEmpty() },
            updatedAtMillis = System.currentTimeMillis()
        )
        _uiState.update { it.copy(corePipelineSession = optimistic) }
        viewModelScope.launch(Dispatchers.IO) {
            localRepo.saveCorePipelineSession(optimistic)
        }
    }

    fun updateCoreClauseText(index: Int, updatedText: String) {
        val current = _uiState.value.corePipelineSession
        if (index !in current.clauses.indices) return
        val nextClauses = current.clauses.mapIndexed { idx, clause ->
            if (idx == index) clause.copy(spokenText = updatedText) else clause
        }
        val optimistic = current.copy(
            clauses = nextClauses,
            updatedAtMillis = System.currentTimeMillis()
        )
        _uiState.update { it.copy(corePipelineSession = optimistic) }
        viewModelScope.launch(Dispatchers.IO) {
            corePipelineCoordinator.updateStoryClauses(optimistic, nextClauses)
        }
    }

    fun addCoreClause(initialText: String = "") {
        val current = _uiState.value.corePipelineSession
        val nextClauses = current.clauses + CoreStoryClause(
            index = current.clauses.size,
            spokenText = initialText,
            segmentMode = BrollSegmentMode.TALKING_HEAD
        )
        val optimistic = current.copy(
            clauses = nextClauses,
            updatedAtMillis = System.currentTimeMillis()
        )
        _uiState.update { it.copy(corePipelineSession = optimistic) }
        viewModelScope.launch(Dispatchers.IO) {
            corePipelineCoordinator.updateStoryClauses(optimistic, nextClauses)
        }
    }

    fun removeCoreClause(index: Int) {
        val current = _uiState.value.corePipelineSession
        if (current.clauses.size <= 1 || index !in current.clauses.indices) return
        val nextClauses = current.clauses.filterIndexed { idx, _ -> idx != index }
            .mapIndexed { idx, c -> c.copy(index = idx) }
        val optimistic = current.copy(
            clauses = nextClauses,
            updatedAtMillis = System.currentTimeMillis()
        )
        _uiState.update { it.copy(corePipelineSession = optimistic) }
        viewModelScope.launch(Dispatchers.IO) {
            corePipelineCoordinator.updateStoryClauses(optimistic, nextClauses)
        }
    }

    fun startCoreVoiceoverRecording() {
        if (_uiState.value.isRecordingCoreVoiceover) return
        _uiState.update { it.copy(isRecordingCoreVoiceover = true, coreVoiceoverRmsLevel = 0f, errorMessage = null) }
        vadAudioEngine.startMomentaryClutch(
            scope = viewModelScope,
            onError = { err ->
                _uiState.update {
                    it.copy(
                        isRecordingCoreVoiceover = false,
                        coreVoiceoverRmsLevel = 0f,
                        errorMessage = err
                    )
                }
            }
        )
    }

    fun stopCoreVoiceoverRecordingAndRunTts() {
        if (!_uiState.value.isRecordingCoreVoiceover) return
        viewModelScope.launch(Dispatchers.IO) {
            val scratchWav = casStore.createTempScratchFile("core_voiceover_", "wav")
            val saved = vadAudioEngine.stopMomentaryClutch(scratchWav)
            _uiState.update { it.copy(isRecordingCoreVoiceover = false, coreVoiceoverRmsLevel = 0f) }
            if (saved == null || !saved.exists()) {
                _uiState.update { it.copy(errorMessage = "No voiced speech captured from microphone.") }
                return@launch
            }
            runCoreStage1Tts(localMicWavFile = saved)
        }
    }

    /** Stage 1 TTS as a background job ([CoreStageWorker]); survives leaving the app. */
    fun runCoreStage1Tts(localMicWavFile: File? = null) {
        if (_uiState.value.isCoreStageBusy) return
        startCoreStage(CoreStageWorker.OP_TTS, mapOf(CoreStageWorker.KEY_MIC_WAV to localMicWavFile?.absolutePath))
    }

    fun playCoreTtsPreview() {
        val session = _uiState.value.corePipelineSession
        val localWav = casStore.fileForSha(session.ttsVoiceSha, "wav")
        val uri = when {
            localWav != null -> Uri.fromFile(localWav)
            !session.ttsAudioUrl.isNullOrBlank() -> Uri.parse(session.ttsAudioUrl)
            else -> null
        } ?: return
        playerController.prepareUri(uri, autoPlay = true)
    }

    fun selectCoreStage(targetStage: CorePipelineStage) {
        val current = _uiState.value.corePipelineSession
        viewModelScope.launch(Dispatchers.IO) {
            val res = corePipelineCoordinator.advanceToStage(current, targetStage)
            res.onSuccess { updated ->
                _uiState.update {
                    it.copy(
                        corePipelineSession = updated,
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(errorMessage = err.localizedMessage ?: "Complete the current stage first.")
                }
            }
        }
    }

    fun lockStoryAndAdvanceToHead() {
        selectCoreStage(CorePipelineStage.SPEECH_TO_TALKING_HEAD)
    }

    /** Stage 3 talking head as a background job; the rig render keeps going if the app is closed. */
    @Suppress("UNUSED_PARAMETER")
    fun runCoreStage3SpeechToHead(pollIntervalMs: Long = 300L) {
        if (_uiState.value.isCoreStageBusy) return
        startCoreStage(CoreStageWorker.OP_HEAD, mapOf(CoreStageWorker.KEY_PHOTO_SHA to activePhotoSha()))
    }

    fun toggleClauseBrollMode(index: Int) {
        val current = _uiState.value.corePipelineSession
        if (index !in current.clauses.indices) return
        val updatedClauses = current.clauses.mapIndexed { idx, clause ->
            if (idx != index) {
                clause
            } else {
                val nextMode = if (clause.segmentMode == BrollSegmentMode.TALKING_HEAD) {
                    BrollSegmentMode.BROLL_CUTAWAY
                } else {
                    BrollSegmentMode.TALKING_HEAD
                }
                clause.copy(
                    segmentMode = nextMode,
                    brollCaption = if (nextMode == BrollSegmentMode.BROLL_CUTAWAY && clause.brollCaption.isBlank()) {
                        clause.spokenText.take(48)
                    } else {
                        clause.brollCaption
                    }
                )
            }
        }
        val optimistic = current.copy(
            clauses = updatedClauses,
            updatedAtMillis = System.currentTimeMillis()
        )
        _uiState.update { it.copy(corePipelineSession = optimistic) }
        viewModelScope.launch(Dispatchers.IO) {
            corePipelineCoordinator.updateStoryClauses(optimistic, updatedClauses)
        }
    }

    fun updateClauseBrollCaption(index: Int, caption: String) {
        val current = _uiState.value.corePipelineSession
        if (index !in current.clauses.indices) return
        val updatedClauses = current.clauses.mapIndexed { idx, clause ->
            if (idx == index) clause.copy(brollCaption = caption) else clause
        }
        val optimistic = current.copy(
            clauses = updatedClauses,
            updatedAtMillis = System.currentTimeMillis()
        )
        _uiState.update { it.copy(corePipelineSession = optimistic) }
        viewModelScope.launch(Dispatchers.IO) {
            corePipelineCoordinator.updateStoryClauses(optimistic, updatedClauses)
        }
    }

    fun toggleSavedOverlayForPipeline(overlayId: String) {
        val current = _uiState.value.corePipelineSession
        val nextIds = if (overlayId in current.selectedOverlayIds) {
            current.selectedOverlayIds - overlayId
        } else {
            current.selectedOverlayIds + overlayId
        }
        val updated = current.copy(
            selectedOverlayIds = nextIds,
            updatedAtMillis = System.currentTimeMillis()
        )
        _uiState.update { it.copy(corePipelineSession = updated) }
        viewModelScope.launch(Dispatchers.IO) {
            localRepo.saveCorePipelineSession(updated)
        }
    }

    /**
     * Runs the Stage 4 studio cut with the session's render options and a Newsroom look:
     * [lookIdOverride] (from "test on clip"), otherwise the saved default look, otherwise none.
     */
    fun runCoreStage4ChopBroll(lookIdOverride: String? = null) {
        if (_uiState.value.isCoreStageBusy) return
        startCoreStage(
            CoreStageWorker.OP_CUT,
            mapOf(CoreStageWorker.KEY_LOOK_ID to lookIdOverride, CoreStageWorker.KEY_PHOTO_SHA to activePhotoSha())
        )
    }

    /** Direct 50 Hz mic RMS flow for [com.example.ui.clipstudio.VoiceGlowDitherOverlay] (zero full-screen recomposition). */
    val liveRmsFlow: StateFlow<Float> get() = vadAudioEngine.liveRmsLevel

    private fun activePhotoSha(): String? = _uiState.value.activeProject.stageConfig.activePhotoSha
        ?: _uiState.value.settings.persona.defaultPhotoSha.ifBlank { null }

    /** Resolves the currently selected presenter portrait JPEG in CAS, or `null` if using the rig default. */
    fun resolveAnchorPhotoFile(): File? = casStore.fileForSha(activePhotoSha(), "jpg")

    /** Imports a user-picked portrait image from the device into CAS and sets it as the active presenter photo. */
    fun importAnchorPhoto(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val source = ImageDecoder.createSource(resolver, uri)
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val maxDim = maxOf(info.size.width, info.size.height)
                    if (maxDim > 1280) {
                        val ratio = 1280f / maxDim.toFloat()
                        decoder.setTargetSize(
                            (info.size.width * ratio).toInt().coerceAtLeast(1),
                            (info.size.height * ratio).toInt().coerceAtLeast(1)
                        )
                    }
                }
                val jpegBytes = ByteArrayOutputStream().use { baos ->
                    decoded.compress(Bitmap.CompressFormat.JPEG, 92, baos)
                    decoded.recycle()
                    baos.toByteArray()
                }
                if (jpegBytes.isEmpty()) throw IllegalStateException("Selected photo could not be encoded.")
                val stored = casStore.putBytes(jpegBytes, "jpg")
                profileStore.setDefaultPhotoSha(stored.sha256)
                val curProj = _uiState.value.activeProject
                persistProject(
                    curProj.copy(
                        stageConfig = curProj.stageConfig.copy(photoSha = stored.sha256)
                    )
                )
                _uiState.update {
                    it.copy(
                        errorMessage = null,
                        toastMessage = "presenter photo updated"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Could not load photo: ${e.localizedMessage ?: "unsupported image"}")
                }
            }
        }
    }

    /** Clears the custom device presenter photo so the rig uses its default avatar portrait. */
    fun clearAnchorPhoto() {
        viewModelScope.launch(Dispatchers.IO) {
            profileStore.setDefaultPhotoSha("")
            val curProj = _uiState.value.activeProject
            persistProject(
                curProj.copy(
                    stageConfig = curProj.stageConfig.copy(photoSha = null)
                )
            )
            _uiState.update { it.copy(toastMessage = "using default presenter photo") }
        }
    }

    private fun startCoreStage(op: String, extras: Map<String, String?> = emptyMap()) {
        CoreStageWorker.ensureChannel(getApplication())
        _uiState.update {
            it.copy(
                isCoreStageBusy = true,
                coreStageProgress = 0f,
                coreStageStatusLabel = CoreStageWorker.labelFor(op) + "…",
                coreStageOp = op,
                errorMessage = null
            )
        }
        pipelineLogger.info("stage", "start $op")
        CoreStageWorker.enqueue(getApplication(), op, extras)
    }

    /** Tails the durable generation log for the Activity panel: rig log first, on-phone fallback. */
    suspend fun loadActivityLog(): List<String> {
        val rigUrl = _uiState.value.rigHealth.resolvedUrl
        val rigLines = macRigGateway.fetchRigLogs(rigUrl, 200)
        return rigLines.ifEmpty { pipelineLogger.recentLocal(80) }
    }

    /** Mirrors background pipeline jobs (WorkManager) into UI state; works across VM/process restarts. */
    private fun observeBackgroundStages() {
        val wm = runCatching { WorkManager.getInstance(getApplication()) }.getOrNull() ?: return
        val handled = mutableSetOf<java.util.UUID>()
        var primed = false
        fun consume(infos: List<WorkInfo>, onRunning: (WorkInfo?) -> Unit) {
            if (!primed) {
                infos.filter { it.state.isFinished }.forEach { handled += it.id }
                primed = true
            }
            onRunning(infos.firstOrNull { !it.state.isFinished })
            infos.filter { it.state.isFinished && it.id !in handled }.forEach { info ->
                handled += info.id
                val err = info.outputData.getString(CoreStageWorker.KEY_ERROR)
                val notice = info.outputData.getString(CoreStageWorker.KEY_NOTICE)
                if (info.state == WorkInfo.State.FAILED) {
                    pipelineLogger.error("stage", "failed: ${err ?: "unknown error"}")
                } else if (info.state == WorkInfo.State.SUCCEEDED) {
                    pipelineLogger.info("stage", "done: ${notice ?: "ok"}")
                }
                _uiState.update {
                    when {
                        info.state == WorkInfo.State.FAILED -> it.copy(errorMessage = err ?: "Pipeline stage failed.")
                        info.state == WorkInfo.State.SUCCEEDED && notice != null -> it.copy(statusNotice = notice)
                        else -> it
                    }
                }
            }
        }
        viewModelScope.launch {
            wm.getWorkInfosForUniqueWorkFlow(CoreStageWorker.UNIQUE_CORE).collect { infos ->
                consume(infos) { running ->
                    val op = running?.progress?.getString(CoreStageWorker.KEY_OP)
                    _uiState.update {
                        it.copy(
                            isCoreStageBusy = running != null,
                            coreStageProgress = running?.progress?.getFloat(CoreStageWorker.KEY_PROGRESS, 0f) ?: 0f,
                            coreStageStatusLabel = running?.let { r ->
                                r.progress.getString(CoreStageWorker.KEY_LABEL) ?: "Working in background…"
                            },
                            coreStageOp = op,
                            isDetectingSilence = running != null && op == CoreStageWorker.OP_SILENCE
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            wm.getWorkInfosForUniqueWorkFlow(CoreStageWorker.UNIQUE_BACKDROP).collect { infos ->
                consume(infos) { running -> _uiState.update { it.copy(isGeneratingBackdrop = running != null) } }
            }
        }
    }

    // =========================================================================
    // Clip Studio v4: 03 CUT render options, undo, voice re-sync
    // =========================================================================

    private val coreUndoStack = ArrayDeque<CorePipelineSession>()

    /** Persists [next] as the new session and remembers the previous one for [undoCoreEdit]. */
    private fun commitCoreEdit(next: CorePipelineSession) {
        val previous = _uiState.value.corePipelineSession
        synchronized(coreUndoStack) {
            coreUndoStack.addLast(previous)
            while (coreUndoStack.size > MAX_CORE_UNDO) coreUndoStack.removeFirst()
        }
        val stamped = next.copy(updatedAtMillis = System.currentTimeMillis())
        _uiState.update { it.copy(corePipelineSession = stamped, coreUndoDepth = coreUndoStack.size) }
        viewModelScope.launch(Dispatchers.IO) { localRepo.saveCorePipelineSession(stamped) }
    }

    /** Restores and persists the session as it was before the last Cut edit. */
    fun undoCoreEdit() {
        val restored = synchronized(coreUndoStack) { coreUndoStack.removeLastOrNull() } ?: return
        val stamped = restored.copy(updatedAtMillis = System.currentTimeMillis())
        _uiState.update {
            it.copy(
                corePipelineSession = stamped,
                coreUndoDepth = coreUndoStack.size,
                toastMessage = "undid last change"
            )
        }
        viewModelScope.launch(Dispatchers.IO) { localRepo.saveCorePipelineSession(stamped) }
    }

    /** Seeks to clause [index] and toggles its inline action tray (`edit words` / `b-roll here`). */
    fun toggleOpenCutClause(index: Int) {
        val clauses = _uiState.value.corePipelineSession.clauses
        if (index !in clauses.indices) return
        val opening = _uiState.value.openClauseIndex != index
        _uiState.update { it.copy(openClauseIndex = if (opening) index else null) }
        if (opening) {
            val startMs = clauses[index].startMs
            if (startMs >= 0L) {
                runCatching { playerController.seekTo(startMs) }
            }
        }
    }

    fun setOpenCutClause(index: Int?) {
        _uiState.update { it.copy(openClauseIndex = index) }
    }

    fun setCoreStripSilence(enabled: Boolean) = updateRenderOptions { it.copy(stripSilence = enabled) }

    fun setCorePunchIn(enabled: Boolean) = updateRenderOptions { it.copy(punchIn = enabled) }

    fun setCoreCaptions(enabled: Boolean) = updateRenderOptions { it.copy(captions = enabled) }

    fun setCoreBreakingStyle(style: BreakingNewsStyle) = updateRenderOptions { it.copy(breakingStyle = style) }

    fun setCoreBrandBug(show: Boolean, text: String? = null) = updateRenderOptions { cur ->
        cur.copy(
            showBrandBug = show,
            cornerBugText = text?.trim()?.take(24)?.ifBlank { cur.cornerBugText } ?: cur.cornerBugText
        )
    }

    fun setCoreBrollFraming(framing: String) {
        val valid = framing.trim().lowercase().takeIf {
            it in setOf(
                CoreRenderOptions.BROLL_FRAMING_PIP,
                CoreRenderOptions.BROLL_FRAMING_FULL,
                CoreRenderOptions.BROLL_FRAMING_SPLIT
            )
        } ?: CoreRenderOptions.BROLL_FRAMING_PIP
        updateRenderOptions { it.copy(brollFraming = valid) }
    }

    /** Edits an existing saved cut-scene overlay card inline from `Video look` and persists to Room. */
    fun updateSavedOverlayInline(
        overlayId: String,
        headline: String,
        subline: String? = null,
        calloutStat: String? = null,
        breakingStyle: BreakingNewsStyle? = null
    ) {
        val existing = _uiState.value.savedOverlays.firstOrNull { it.id == overlayId } ?: return
        val updated = existing.copy(
            headline = headline.trim().ifBlank { existing.headline }.take(64),
            subline = subline?.trim()?.take(80) ?: existing.subline,
            calloutStat = calloutStat?.trim()?.take(28) ?: existing.calloutStat,
            breakingStyle = breakingStyle ?: existing.breakingStyle
        )
        _uiState.update { st ->
            st.copy(
                savedOverlays = st.savedOverlays.map { if (it.id == overlayId) updated else it },
                toastMessage = "card updated"
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            localRepo.saveNewsroomOverlay(updated)
        }
    }

    /**
     * Calls real `POST /v1/ai-assist` (`mode = "tweak_video_look"`) on the Mac rig to apply
     * natural-language design/color/logo/framing/headline tweaks directly to Room & live preview.
     */
    fun tweakVideoLookWithAi(
        instruction: String,
        currentLook: NewsroomLook,
        onLookUpdated: (NewsroomLook) -> Unit
    ) {
        val clean = instruction.trim()
        if (clean.isEmpty() || _uiState.value.isTweakingLook) return
        val session = _uiState.value.corePipelineSession
        val storyContext = session.fullStoryText.trim()
            .ifBlank { _uiState.value.activeProject.ideaSpark.trim() }
            .ifBlank { session.title.trim() }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isTweakingLook = true, errorMessage = null) }
            val persisted = runCatching { profileStore.current() }.getOrDefault(_uiState.value.settings)
            val rigUrl = persisted.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
            val result = runCatching {
                val health = macRigGateway.resolveAndCheckHealth(rigUrl)
                macRigGateway.tweakVideoLook(
                    rigBaseUrl = health.resolvedUrl,
                    instruction = clean,
                    currentLook = currentLook,
                    currentOptions = session.renderOptions,
                    storyContext = storyContext
                )
            }
            result.onSuccess { tweak ->
                val updatedLook = currentLook.copy(
                    accentHex = tweak.accentHex ?: currentLook.accentHex,
                    light = tweak.light ?: currentLook.light,
                    blurPercent = tweak.blurPercent ?: currentLook.blurPercent,
                    brightnessEv = tweak.brightnessEv ?: currentLook.brightnessEv,
                    updatedAtMillis = System.currentTimeMillis()
                )
                if (updatedLook != currentLook) {
                    updateNewsroomLook(updatedLook)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onLookUpdated(updatedLook)
                    }
                }
                val latest = _uiState.value.corePipelineSession
                val nextOpts = latest.renderOptions.copy(
                    breakingStyle = tweak.breakingStyle ?: latest.renderOptions.breakingStyle,
                    showBrandBug = tweak.showBrandBug ?: latest.renderOptions.showBrandBug,
                    cornerBugText = tweak.cornerBugText?.ifBlank { null } ?: latest.renderOptions.cornerBugText,
                    brollFraming = tweak.brollFraming ?: latest.renderOptions.brollFraming,
                    chyronHeadline = tweak.chyronHeadline?.ifBlank { null } ?: latest.renderOptions.chyronHeadline
                )
                if (nextOpts != latest.renderOptions) {
                    commitCoreEdit(
                        latest.copy(
                            title = tweak.chyronHeadline?.ifBlank { null } ?: latest.title,
                            renderOptions = nextOpts
                        )
                    )
                }
                if (!tweak.backdropPrompt.isNullOrBlank()) {
                    generateBackdropForLook(updatedLook.id, tweak.backdropPrompt)
                }
                _uiState.update {
                    it.copy(
                        isTweakingLook = false,
                        toastMessage = tweak.summary.ifBlank { "look tweaked with AI" },
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        isTweakingLook = false,
                        errorMessage = "AI look tweak failed: ${err.localizedMessage ?: "rig unreachable"}."
                    )
                }
            }
        }
    }

    fun setCoreAspect(aspect: String) {
        require(aspect == CoreRenderOptions.ASPECT_VERTICAL || aspect == CoreRenderOptions.ASPECT_HORIZONTAL) {
            "Unsupported aspect $aspect"
        }
        updateRenderOptions { it.copy(aspect = aspect) }
    }

    private fun updateRenderOptions(transform: (CoreRenderOptions) -> CoreRenderOptions) {
        val current = _uiState.value.corePipelineSession
        val next = transform(current.renderOptions)
        if (next == current.renderOptions) return
        commitCoreEdit(current.copy(renderOptions = next))
    }

    /** Edits clause [index] in Cut; the clause becomes "unsynced" until [resyncEditedVoice] runs. */
    fun editCutClauseText(index: Int, updatedText: String) {
        val current = _uiState.value.corePipelineSession
        if (index !in current.clauses.indices) return
        val clean = updatedText.trim()
        if (clean.isEmpty() || clean == current.clauses[index].spokenText.trim()) return
        commitCoreEdit(
            current.copy(
                clauses = current.clauses.mapIndexed { i, c -> if (i == index) c.copy(spokenText = clean) else c }
            )
        )
    }

    /** Toggles clause [index] between talking head and B-roll cutaway, with undo. */
    fun toggleCutClauseBroll(index: Int) {
        val current = _uiState.value.corePipelineSession
        if (index !in current.clauses.indices) return
        val willBeBroll = current.clauses[index].segmentMode == BrollSegmentMode.TALKING_HEAD
        commitCoreEdit(
            current.copy(
                clauses = current.clauses.mapIndexed { i, c ->
                    if (i != index) {
                        c
                    } else if (willBeBroll) {
                        c.copy(
                            segmentMode = BrollSegmentMode.BROLL_CUTAWAY,
                            brollCaption = c.brollCaption.ifBlank { c.spokenText.take(48) }
                        )
                    } else {
                        c.copy(segmentMode = BrollSegmentMode.TALKING_HEAD)
                    }
                }
            )
        )
        showToast(if (willBeBroll) "b-roll added" else "b-roll removed")
    }

    /** Asks the rig which pauses `strip_silence` would remove from the current talking-head render. */
    fun refreshSilenceGaps() {
        val session = _uiState.value.corePipelineSession
        if (session.talkingHeadJobId.isNullOrBlank() || _uiState.value.isCoreStageBusy) return
        startCoreStage(CoreStageWorker.OP_SILENCE)
    }

    /**
     * `sync voice` / `re-voice edited words`: re-runs Stage 1 TTS on the edited clauses, then
     * Stage 3 so the talking head matches the new audio.
     */
    fun resyncEditedVoice() {
        if (_uiState.value.isCoreStageBusy) return
        if (_uiState.value.corePipelineSession.unsyncedClauseIndices.isEmpty()) return
        startCoreStage(CoreStageWorker.OP_RESYNC, mapOf(CoreStageWorker.KEY_PHOTO_SHA to activePhotoSha()))
    }

    // =========================================================================
    // Clip Studio v4: 04 NEWSROOM looks
    // =========================================================================

    private val lookSaveMutex = Mutex()

    fun createNewsroomLook(name: String): String {
        val look = NewsroomLook(
            name = name.trim().ifBlank { "LOOK_${_uiState.value.newsroomLooks.size + 1}" },
            isDefault = _uiState.value.newsroomLooks.isEmpty()
        )
        _uiState.update { it.copy(newsroomLooks = listOf(look) + it.newsroomLooks) }
        viewModelScope.launch {
            lookSaveMutex.withLock {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    localRepo.saveNewsroomLook(look)
                }
            }
        }
        return look.id
    }

    fun updateNewsroomLook(look: NewsroomLook) {
        val clamped = look.copy(
            blurPercent = look.blurPercent.coerceIn(0, 100),
            brightnessEv = (Math.round(look.brightnessEv.coerceIn(-3.0, 3.0) * 10.0) / 10.0),
            light = look.light.takeIf {
                it in setOf(NewsroomLook.LIGHT_WARM, NewsroomLook.LIGHT_COOL, NewsroomLook.LIGHT_NEUTRAL)
            } ?: NewsroomLook.LIGHT_NEUTRAL
        )
        _uiState.update { st -> st.copy(newsroomLooks = st.newsroomLooks.map { if (it.id == clamped.id) clamped else it }) }
        viewModelScope.launch {
            lookSaveMutex.withLock {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    localRepo.saveNewsroomLook(clamped)
                }
            }
        }
    }

    fun duplicateNewsroomLook(id: String): String? {
        val source = _uiState.value.newsroomLooks.firstOrNull { it.id == id } ?: return null
        val copy = source.copy(
            id = NewsroomLook(name = "x").id,
            name = source.name.take(40) + "_COPY",
            isDefault = false,
            createdAtMillis = maxOf(System.currentTimeMillis(), source.createdAtMillis + 1L)
        )
        _uiState.update { it.copy(newsroomLooks = listOf(copy) + it.newsroomLooks) }
        viewModelScope.launch {
            lookSaveMutex.withLock {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    localRepo.saveNewsroomLook(copy)
                }
            }
        }
        return copy.id
    }

    fun setDefaultNewsroomLook(id: String) {
        _uiState.update { st -> st.copy(newsroomLooks = st.newsroomLooks.map { it.copy(isDefault = it.id == id) }) }
        viewModelScope.launch {
            lookSaveMutex.withLock {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    localRepo.setDefaultNewsroomLook(id)
                }
            }
            _uiState.update { it.copy(statusNotice = "Default look set — applied to the next cut.") }
        }
    }

    /**
     * Clip Studio v5 `Video look` primary action (`"use this look"`):
     * saves any pending look edits, marks [lookId] as the persisted default in Room, and posts
     * toast `"look applied · render to see it"`.
     */
    fun useLookAndReturn(lookId: String, draftOverride: NewsroomLook? = null) {
        val baseLook = draftOverride
            ?: _uiState.value.newsroomLooks.firstOrNull { it.id == lookId }
            ?: NewsroomLook(
                id = lookId.trim().ifBlank { "look_default" },
                name = "LOOK_01",
                isDefault = true
            )
        val clamped = baseLook.copy(
            blurPercent = baseLook.blurPercent.coerceIn(0, 100),
            brightnessEv = (Math.round(baseLook.brightnessEv.coerceIn(-3.0, 3.0) * 10.0) / 10.0),
            light = baseLook.light.takeIf {
                it in setOf(NewsroomLook.LIGHT_WARM, NewsroomLook.LIGHT_COOL, NewsroomLook.LIGHT_NEUTRAL)
            } ?: NewsroomLook.LIGHT_NEUTRAL,
            isDefault = true,
            updatedAtMillis = System.currentTimeMillis()
        )
        _uiState.update { st ->
            val exists = st.newsroomLooks.any { it.id == clamped.id }
            val updatedList = if (exists) {
                st.newsroomLooks.map { if (it.id == clamped.id) clamped else it.copy(isDefault = false) }
            } else {
                listOf(clamped) + st.newsroomLooks.map { it.copy(isDefault = false) }
            }
            st.copy(
                newsroomLooks = updatedList,
                toastMessage = "look applied · render to see it"
            )
        }
        viewModelScope.launch {
            lookSaveMutex.withLock {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    localRepo.saveNewsroomLook(clamped)
                    localRepo.setDefaultNewsroomLook(clamped.id)
                }
            }
        }
    }

    /**
     * Updates the `TAG ABOVE TITLE` style (`"none"` -> `CLEAN`, `"breaking"` -> `BREAKING`,
     * `"developing"` -> `DEVELOPING`, `"explainer"` -> `EXCLUSIVE`) and persists to Room.
     */
    fun setTitleTagStyle(tag: String) {
        val style = BreakingNewsStyle.fromTitleTag(tag)
        setCoreBreakingStyle(style)
    }

    /** Updates the on-screen title in `Video look` and persists to Room. */
    fun updateTitleOnScreen(newTitle: String) {
        val clean = newTitle.trim()
        val current = _uiState.value.corePipelineSession
        commitCoreEdit(
            current.copy(
                title = clean.ifBlank { current.title },
                renderOptions = current.renderOptions.copy(chyronHeadline = clean)
            )
        )
        if (clean.isNotEmpty()) {
            val proj = _uiState.value.activeProject
            persistProject(
                proj.copy(
                    title = clean,
                    stageConfig = proj.stageConfig.copy(chyronHeadline = clean)
                )
            )
        }
    }

    /**
     * Calls real `POST /v1/ai-assist` (`mode = "polish_headline"`) on the Mac rig to rewrite
     * the active on-screen title, persisting the result to Room.
     */
    fun refreshHeadlineWithAi() {
        if (_uiState.value.isRefreshingHeadline) return
        val session = _uiState.value.corePipelineSession
        val activeProj = _uiState.value.activeProject
        val currentHeadline = session.renderOptions.chyronHeadline.trim()
            .ifBlank { session.title.trim() }
            .ifBlank { activeProj.stageConfig.chyronHeadline.trim() }
            .ifBlank { activeProj.title.trim() }
        val storyContext = session.fullStoryText.trim()
            .ifBlank { activeProj.ideaSpark.trim() }
            .ifBlank { currentHeadline }
        if (currentHeadline.isEmpty() && storyContext.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Load or write a story before refreshing the title.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isRefreshingHeadline = true, errorMessage = null) }
            val persisted = runCatching { profileStore.current() }.getOrDefault(_uiState.value.settings)
            val rigUrl = persisted.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
            val result = runCatching {
                val health = macRigGateway.resolveAndCheckHealth(rigUrl)
                macRigGateway.polishHeadline(
                    rigBaseUrl = health.resolvedUrl,
                    currentHeadline = currentHeadline,
                    storyContext = storyContext
                )
            }
            result.onSuccess { refreshed ->
                val clean = refreshed.trim()
                val latestSession = _uiState.value.corePipelineSession
                commitCoreEdit(
                    latestSession.copy(
                        title = clean,
                        renderOptions = latestSession.renderOptions.copy(chyronHeadline = clean)
                    )
                )
                val proj = _uiState.value.activeProject
                persistProject(
                    proj.copy(
                        title = clean,
                        stageConfig = proj.stageConfig.copy(chyronHeadline = clean)
                    )
                )
                _uiState.update {
                    it.copy(
                        isRefreshingHeadline = false,
                        toastMessage = "title refreshed",
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        isRefreshingHeadline = false,
                        errorMessage = "Title refresh failed: ${err.localizedMessage ?: "rig unreachable"}."
                    )
                }
            }
        }
    }

    /** Generates a backdrop plate for look [lookId] from [prompt] on the rig and attaches it. */
    fun generateBackdropForLook(lookId: String, prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Describe the backdrop before generating.") }
            return
        }
        if (_uiState.value.isGeneratingBackdrop) return
        CoreStageWorker.ensureChannel(getApplication())
        _uiState.update { it.copy(isGeneratingBackdrop = true, errorMessage = null) }
        CoreStageWorker.enqueue(
            getApplication(),
            CoreStageWorker.OP_BACKDROP,
            mapOf(CoreStageWorker.KEY_LOOK_ID to lookId, CoreStageWorker.KEY_PROMPT to clean),
            unique = CoreStageWorker.UNIQUE_BACKDROP
        )
    }

    // =========================================================================
    // Phone-Only Voice Model Care & Studio Settings
    // =========================================================================

    fun setPhoneOnlyVoiceMode(enabled: Boolean) {
        if (enabled) {
            rigHealthCheckJob?.cancel()
        }
        _uiState.update { current ->
            current.copy(
                settings = current.settings.copy(
                    phoneOnlyVoiceMode = enabled,
                    providers = current.settings.providers.copy(useLanVoice = !enabled)
                ),
                statusNotice = if (enabled) {
                    "Phone-only voice mode active: zero Mac voice sync."
                } else {
                    "Network sync unlocked."
                }
            )
        }
        container.appScope.launch {
            profileStore.setPhoneOnlyVoiceMode(enabled)
        }
    }

    fun refreshVoiceModelCare(verifyChecksums: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (verifyChecksums) {
                _uiState.update { it.copy(isVerifyingVoiceFiles = true, errorMessage = null) }
            }
            val snapshot = runCatching {
                voiceModelCareManager.inspectLocalVoiceStorage(verifyChecksums = verifyChecksums)
            }.getOrNull()
            _uiState.update {
                it.copy(
                    voiceStorageSnapshot = snapshot ?: it.voiceStorageSnapshot,
                    isVerifyingVoiceFiles = false,
                    statusNotice = if (verifyChecksums && snapshot != null) {
                        "Verified ${snapshot.presentArtifactCount}/${snapshot.totalExpectedArtifacts} on-phone voice artifacts."
                    } else {
                        it.statusNotice
                    }
                )
            }
        }
    }

    fun startVoiceCareSampleRecording() {
        if (_uiState.value.isRecordingVoiceCareSample) return
        _uiState.update { it.copy(isRecordingVoiceCareSample = true, errorMessage = null) }
        vadAudioEngine.startMomentaryClutch(
            scope = viewModelScope,
            onError = { err ->
                _uiState.update {
                    it.copy(
                        isRecordingVoiceCareSample = false,
                        errorMessage = err
                    )
                }
            }
        )
    }

    fun stopVoiceCareSampleRecording() {
        if (!_uiState.value.isRecordingVoiceCareSample) return
        viewModelScope.launch {
            val outFile = voiceModelCareManager.nextSampleOutputFile()
            val saved = vadAudioEngine.stopMomentaryClutch(outFile)
            val snapshot = voiceModelCareManager.inspectLocalVoiceStorage(verifyChecksums = false)
            _uiState.update {
                it.copy(
                    isRecordingVoiceCareSample = false,
                    voiceStorageSnapshot = snapshot,
                    statusNotice = if (saved != null) {
                        "Saved voice sample ${saved.name} on phone."
                    } else {
                        null
                    },
                    errorMessage = if (saved == null) {
                        "No voiced speech detected in recording."
                    } else {
                        it.errorMessage
                    }
                )
            }
        }
    }

    fun addVoiceSamplePcmForCare(pcm16k: ShortArray, fileName: String? = null) {
        viewModelScope.launch {
            val saved = runCatching {
                voiceModelCareManager.writeWavSampleForCare(pcm16k, fileName)
            }
            val snapshot = voiceModelCareManager.inspectLocalVoiceStorage(verifyChecksums = false)
            saved.onSuccess { file ->
                _uiState.update {
                    it.copy(
                        voiceStorageSnapshot = snapshot,
                        statusNotice = "Saved ${file.name} on phone."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        voiceStorageSnapshot = snapshot,
                        errorMessage = err.localizedMessage ?: "Failed writing voice sample."
                    )
                }
            }
        }
    }

    fun deleteVoiceCareSample(fileName: String) {
        viewModelScope.launch {
            voiceModelCareManager.deleteVoiceSample(fileName)
            val snapshot = voiceModelCareManager.inspectLocalVoiceStorage(verifyChecksums = false)
            _uiState.update {
                it.copy(
                    voiceStorageSnapshot = snapshot,
                    statusNotice = "Removed $fileName from phone."
                )
            }
        }
    }

    fun importLocalVoiceModelUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = voiceModelCareManager.importLocalModelOrLoraUri(uri)
            val snapshot = voiceModelCareManager.inspectLocalVoiceStorage(verifyChecksums = false)
            result.onSuccess { imported ->
                _uiState.update {
                    it.copy(
                        voiceStorageSnapshot = snapshot,
                        statusNotice = "Imported ${imported.fileName} (${imported.sha256Hex.take(8)}) on phone."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        voiceStorageSnapshot = snapshot,
                        errorMessage = err.localizedMessage ?: "Failed importing model file."
                    )
                }
            }
        }
    }

    fun importLocalVoiceModelFile(file: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val result = voiceModelCareManager.importLocalModelOrLoraFile(file)
            val snapshot = voiceModelCareManager.inspectLocalVoiceStorage(verifyChecksums = false)
            result.onSuccess { imported ->
                _uiState.update {
                    it.copy(
                        voiceStorageSnapshot = snapshot,
                        statusNotice = "Imported ${imported.fileName} (${imported.sha256Hex.take(8)}) on phone."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        voiceStorageSnapshot = snapshot,
                        errorMessage = err.localizedMessage ?: "Failed importing model file."
                    )
                }
            }
        }
    }

    fun runOnPhoneVoiceQualityCheck() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isCheckingVoiceQuality = true, errorMessage = null) }
            val report = runCatching { voiceModelCareManager.runOnPhoneQualityCheck() }
            report.onSuccess { r ->
                _uiState.update {
                    it.copy(
                        isCheckingVoiceQuality = false,
                        voiceQualityReport = r,
                        statusNotice = r.summaryMessage
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isCheckingVoiceQuality = false,
                        errorMessage = err.localizedMessage ?: "Quality check failed."
                    )
                }
            }
        }
    }

    fun observeClip(clipId: String): Flow<ClipRecord?> = localRepo.observeClip(clipId)

    private fun persistProject(updated: ScriptProject) {
        val stamped = updated.copy(updatedAtMillis = System.currentTimeMillis())
        _uiState.update { state ->
            val updatedList = if (state.projects.any { it.id == stamped.id }) {
                state.projects.map { if (it.id == stamped.id) stamped else it }
            } else {
                listOf(stamped) + state.projects
            }
            state.copy(activeProject = stamped, projects = updatedList)
        }
        viewModelScope.launch {
            scriptSaveMutex.withLock {
                localRepo.saveScript(stamped)
                profileStore.setActiveScriptId(stamped.id)
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(statusNotice = null) }
    }

    fun showToast(message: String) {
        val clean = message.trim()
        if (clean.isEmpty()) return
        _uiState.update { it.copy(toastMessage = clean) }
    }

    fun dismissToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /** Re-queries Room `clips` (`status == READY`) and `LocalGraphDatabase` replies to refresh `lastCompletedVideo`. */
    fun refreshLastCompletedVideo() {
        recomputeLastCompletedVideo(_uiState.value.clipCards)
    }

    private fun recomputeLastCompletedVideo(cards: List<ClipCardRow>) {
        val latestReady = cards
            .filter { it.status == RenderJobStatus.READY }
            .maxByOrNull { it.updatedAtMillis }
        if (latestReady == null) {
            _uiState.update { it.copy(lastCompletedVideo = null) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val clip = localRepo.getClipById(latestReady.id)
            if (clip == null || clip.status != RenderJobStatus.READY) {
                _uiState.update { it.copy(lastCompletedVideo = null) }
                return@launch
            }
            val latestSession = localRepo.getLatestCorePipelineSession()
            val scriptTranscript = if (clip.scriptId.isNotBlank()) {
                localRepo.getScriptById(clip.scriptId)?.let { proj ->
                    proj.activeBeats.joinToString(" ") { it.spokenText.trim() }
                        .trim()
                        .ifBlank { proj.ideaSpark.trim() }
                }?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val resolvedTranscript = when {
                latestSession != null &&
                    latestSession.finalClipId == clip.id &&
                    latestSession.fullStoryText.isNotBlank() -> latestSession.fullStoryText.trim()
                !scriptTranscript.isNullOrBlank() -> scriptTranscript
                clip.alignedWords.isNotEmpty() -> clip.alignedWords.joinToString(" ") { it.word }.trim()
                clip.chyronSubline.isNotBlank() -> clip.chyronSubline.trim()
                else -> clip.title.trim()
            }
            val cleanTitle = clip.title.trim()
                .ifBlank { clip.chyronHeadline.trim() }
                .ifBlank { "Untitled clip" }
            val thumbPath = casStore.fileForSha(clip.thumbHash, "jpg")?.absolutePath
                ?: casStore.fileForSha(clip.photoSha, "jpg")?.absolutePath
            val topReply = resolveTopReplyQuestionFromGraph(cleanTitle, resolvedTranscript)
            val summary = LastVideoSummary(
                clipId = clip.id,
                title = cleanTitle,
                durationMs = clip.durationMs,
                updatedAtMillis = clip.updatedAtMillis,
                thumbFilePath = thumbPath,
                transcript = resolvedTranscript,
                topReplyQuestion = topReply,
                remoteThumbUrl = _uiState.value.remoteThumbUrls[clip.id]?.ifBlank { null },
                remoteVideoUrl = clip.remoteFinalUrl?.ifBlank { null }
            )
            _uiState.update { state ->
                val stillHasReady = state.clipCards.any { it.id == clip.id && it.status == RenderJobStatus.READY }
                if (stillHasReady) {
                    state.copy(lastCompletedVideo = summary)
                } else {
                    state.copy(lastCompletedVideo = null)
                }
            }
        }
    }

    private fun resolveTopReplyQuestionFromGraph(title: String, transcript: String): String? {
        val replies = runCatching {
            localGraphDatabase.getAllPosts()
                .map { it.record }
                .filter { it.isReplyToUser && it.text.isNotBlank() }
        }.getOrDefault(emptyList())
        if (replies.isEmpty()) return null

        val topicWords = Regex("[a-z0-9]{4,}")
            .findAll("$title $transcript".lowercase())
            .map { it.value }
            .toSet()

        val ranked = replies.sortedWith(
            compareByDescending<com.example.graph.GraphPostRecord> { it.text.contains('?') }
                .thenByDescending { post ->
                    val words = Regex("[a-z0-9]{4,}").findAll(post.text.lowercase()).map { it.value }.toSet()
                    words.count { it in topicWords }
                }
                .thenByDescending { it.createdAtEpochMs }
        )
        val best = ranked.firstOrNull() ?: return null
        return best.text
            .replace(Regex("^(?:@[A-Za-z0-9_]+\\s+)+"), "")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    fun clearPendingShareIntent() {
        _uiState.update { it.copy(pendingShareIntent = null) }
    }

    fun consumeLiveNavToClip() {
        _uiState.update { it.copy(liveNavToClipId = null) }
    }

    fun refreshRemoteClips() {
        viewModelScope.launch {
            val baseUrl = _uiState.value.rigHealth.resolvedUrl.trim()
            if (baseUrl.isEmpty()) return@launch
            val renders = runCatching { macRigGateway.listRemoteRenders(baseUrl) }.getOrNull() ?: return@launch
            mergeRemoteRendersIntoRoom(renders)
        }
    }

    private suspend fun mergeRemoteRendersIntoRoom(renders: List<RemoteRenderSummary>) {
        if (renders.isEmpty()) return
        val thumbMap = renders.associate { it.id to it.thumbUrl }
        _uiState.update { it.copy(remoteThumbUrls = it.remoteThumbUrls + thumbMap) }
        val now = System.currentTimeMillis()
        renders.forEachIndexed { idx, item ->
            val existing = localRepo.getClipById(item.id)
            if (existing == null) {
                localRepo.upsertClip(
                    ClipRecord(
                        id = item.id,
                        scriptId = "",
                        jobId = item.id,
                        title = item.title,
                        photoSha = null,
                        voiceSha = null,
                        alignSha = null,
                        lipsyncSha = null,
                        finalSha = null,
                        status = RenderJobStatus.READY,
                        progressFraction = 1f,
                        stageDetail = "Ready",
                        errorMessage = null,
                        durationMs = item.durationMs,
                        waveformPeaks = emptyList(),
                        thumbHash = null,
                        alignedWords = emptyList(),
                        chyronHeadline = item.title,
                        chyronSubline = "",
                        remoteLipsyncUrl = null,
                        remoteFinalUrl = item.videoUrl,
                        exportedUri = null,
                        createdAtMillis = now - idx * 1_000L,
                        updatedAtMillis = now - idx * 1_000L
                    )
                )
            } else if (existing.remoteFinalUrl.isNullOrBlank()) {
                localRepo.upsertClip(existing.copy(remoteFinalUrl = item.videoUrl))
            }
        }
    }

    fun exportClip(clip: ClipRecord, openShareSheet: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(exportState = ExportState.EXPORTING, errorMessage = null) }
            val result = clipExporter.exportClip(
                finalSha = clip.finalSha,
                remoteFinalUrl = clip.remoteFinalUrl,
                projectTitle = clip.title
            )
            result.onSuccess { exported ->
                localRepo.markClipExported(clip.id, exported.mediaStoreUri.toString())
                _uiState.update {
                    it.copy(
                        exportState = ExportState.EXPORTED,
                        pendingShareIntent = if (openShareSheet) exported.shareIntent else null,
                        statusNotice = "Saved to Photos."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        exportState = ExportState.IDLE,
                        errorMessage = err.localizedMessage ?: "Export failed."
                    )
                }
            }
        }
    }

    fun setBrandChannel(channel: BrandChannel) {
        viewModelScope.launch { profileStore.setBrandChannel(channel) }
    }

    fun clearCasCache() {
        viewModelScope.launch {
            casStore.clearAllCache()
            _uiState.update { it.copy(statusNotice = "Cache cleared.") }
        }
    }

    fun saveTailscaleRigUrl(url: String) {
        val trimmed = url.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
        viewModelScope.launch {
            profileStore.setTailscaleRigUrl(trimmed)
            _uiState.update {
                it.copy(
                    rigHealth = RigHealthUiState(resolvedUrl = trimmed),
                    statusNotice = "Studio server URL saved."
                )
            }
            testMacRigConnection(trimmed)
        }
    }

    fun testMacRigConnection(overrideUrl: String? = null) {
        val candidate = overrideUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: _uiState.value.settings.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
        rigHealthCheckJob?.cancel()
        rigHealthCheckJob = viewModelScope.launch {
            _uiState.update {
                it.copy(rigHealth = RigHealthUiState(status = RigConnectionStatus.CHECKING, resolvedUrl = candidate))
            }
            val result = runCatching { macRigGateway.resolveAndCheckHealth(candidate) }
            result.onSuccess { health ->
                _uiState.update {
                    it.copy(
                        rigHealth = RigHealthUiState(
                            status = RigConnectionStatus.ONLINE,
                            resolvedUrl = health.resolvedUrl,
                            loraVersion = health.loraVersion,
                            bundleVersion = health.bundleVersion,
                            pendingPreferences = health.pendingPreferences,
                            renderBridgeAvailable = health.renderBridgeAvailable,
                            errorMessage = null
                        )
                    )
                }
                AppFunctionAvailabilitySync.syncDynamicFunctionStates(
                    context = getApplication(),
                    isMacRigOnline = true,
                    isLocalVoiceBundleReady = _uiState.value.otaBundleState.status == com.voicestudio.edl.OtaBundleStatus.READY
                )
                val renders = runCatching { macRigGateway.listRemoteRenders(health.resolvedUrl) }.getOrNull()
                if (renders != null) {
                    mergeRemoteRendersIntoRoom(renders)
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        rigHealth = RigHealthUiState(
                            status = RigConnectionStatus.ERROR,
                            resolvedUrl = candidate,
                            errorMessage = err.localizedMessage ?: "Studio server unreachable."
                        )
                    )
                }
                AppFunctionAvailabilitySync.syncDynamicFunctionStates(
                    context = getApplication(),
                    isMacRigOnline = false,
                    isLocalVoiceBundleReady = _uiState.value.otaBundleState.status == com.voicestudio.edl.OtaBundleStatus.READY
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        rigHealthCheckJob?.cancel()
    }

    companion object {
        const val MAC_RIG_POLL_INTERVAL_MS: Long = 2_500L
        private const val MAX_CORE_UNDO: Int = 40
        private val CARD_PROMPT_PREFIX_REGEX = Regex(
            "(?i)^(?:(?:text|quote|stat|title)\\s+card(?:\\s+(?:reading|saying|showing|with))?|" +
                "on[-\\s]screen\\s+(?:text|quote|card)(?:\\s+(?:reading|saying|showing|with))?|" +
                "card\\s+(?:reading|saying|showing|text)|" +
                "card\\s*[:\\-–—])\\s*[:\\-–—]?\\s*"
        )
        private val QUOTE_CHARS = charArrayOf('"', '\'', '“', '”', '‘', '’')

        internal fun sanitizeCardQuoteHeadline(
            rawPrompt: String,
            fallbackText: String = "",
            maxChars: Int = 56
        ): String {
            val primary = stripCardPromptChrome(rawPrompt)
            val chosen = primary.ifEmpty { stripCardPromptChrome(fallbackText) }
            return truncateOnWordBoundary(chosen, maxChars = maxChars)
        }

        private fun stripCardPromptChrome(raw: String): String {
            var current = raw.trim().replace(Regex("\\s+"), " ")
            repeat(3) {
                val unquoted = current.trim(*QUOTE_CHARS).trim()
                val stripped = CARD_PROMPT_PREFIX_REGEX.replaceFirst(unquoted, "").trim(*QUOTE_CHARS).trim()
                if (stripped == current) return@repeat
                current = stripped
            }
            return current
        }

        internal fun truncateOnWordBoundary(text: String, maxChars: Int = 56): String {
            val normalized = text.trim().replace(Regex("\\s+"), " ")
            if (normalized.length <= maxChars) return normalized
            val budget = (maxChars - 1).coerceAtLeast(1)
            val slice = normalized.substring(0, budget)
            val lastSpace = slice.lastIndexOf(' ')
            val cut = if (lastSpace >= 12) slice.substring(0, lastSpace) else slice
            return cut.trimEnd(' ', ',', ';', ':', '-', '–', '—') + "…"
        }

        internal fun applyShotPlanToClauses(
            clauses: List<CoreStoryClause>,
            shotPlan: List<List<ShotSpec>>
        ): List<CoreStoryClause> {
            val nonEmptyBeats = shotPlan.filter { it.isNotEmpty() }
            if (clauses.isEmpty() || nonEmptyBeats.isEmpty()) return clauses

            val beatIndexByClause = IntArray(clauses.size) { cIdx ->
                ((cIdx * nonEmptyBeats.size) / clauses.size).coerceIn(0, nonEmptyBeats.lastIndex)
            }
            val clausesPerBeat = IntArray(nonEmptyBeats.size)
            val seenPerBeat = IntArray(nonEmptyBeats.size)
            for (bIdx in beatIndexByClause) {
                clausesPerBeat[bIdx]++
            }

            return clauses.mapIndexed { cIdx, clause ->
                val bIdx = beatIndexByClause[cIdx]
                val localOffset = seenPerBeat[bIdx]++
                val bucketSize = clausesPerBeat[bIdx].coerceAtLeast(1)
                val beatShots = nonEmptyBeats[bIdx]
                val chosenShot = if (bucketSize == 1 && beatShots.size > 1) {
                    val defaultCount = ShotType.defaultTypesForBeat(bIdx).size
                    if (beatShots.size > defaultCount) {
                        beatShots.last()
                    } else {
                        beatShots.first()
                    }
                } else {
                    val sIdx = ((localOffset * beatShots.size) / bucketSize).coerceIn(0, beatShots.lastIndex)
                    beatShots[sIdx]
                }

                when (chosenShot.type) {
                    ShotType.PIP, ShotType.BROLL -> {
                        val cue = chosenShot.prompt.trim().ifBlank { clause.spokenText.take(48) }
                        clause.copy(
                            segmentMode = BrollSegmentMode.BROLL_CUTAWAY,
                            brollCaption = cue,
                            bRollPrompt = chosenShot.prompt.trim().ifBlank { clause.spokenText.take(72) },
                            shotTypeWire = chosenShot.type.wireValue
                        )
                    }
                    ShotType.FACE, ShotType.CARD -> {
                        clause.copy(
                            segmentMode = BrollSegmentMode.TALKING_HEAD,
                            shotTypeWire = chosenShot.type.wireValue
                        )
                    }
                }
            }
        }
    }
}
