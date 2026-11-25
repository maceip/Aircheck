package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import com.example.data.OverlayObjectKind
import com.example.data.RenderJobStatus
import com.example.data.SavedNewsroomOverlay
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import com.example.data.estimateDurationSeconds
import com.example.data.gateway.RemoteRenderSummary
import com.example.data.local.CasMediaStore
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.data.local.StudioSettings
import com.example.engine.AircheckPlayerController
import com.example.engine.CoreStoryPipelineCoordinator
import com.example.engine.VoiceModelCareManager
import com.example.engine.VoiceQualityReport
import com.example.engine.VoiceStorageSnapshot
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
    val isRecordingCoreVoiceover: Boolean = false,
    val newsroomLooks: List<NewsroomLook> = emptyList(),
    val isGeneratingBackdrop: Boolean = false,
    val isGeneratingOverlaySet: Boolean = false,
    val isDetectingSilence: Boolean = false,
    val coreUndoDepth: Int = 0,
    val errorMessage: String? = null,
    val statusNotice: String? = null,
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
}

/** An X post chosen with "show post" in 02 SCRIPT, burned into the cut as a quote card. */
data class PostCardSource(val handle: String, val tweetId: String, val snippet: String)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AircheckApplication).container
    private val localRepo = container.localRepo
    private val profileStore = container.profileStore
    val casStore: CasMediaStore = container.casStore
    val playerController: AircheckPlayerController = container.playerController
    private val clipExporter = container.clipExporter
    private val macRigGateway = container.macRigGateway
    private val otaBundleSyncManager = container.otaBundleSyncManager
    val voiceModelCareManager: VoiceModelCareManager = container.voiceModelCareManager
    private val corePipelineCoordinator: CoreStoryPipelineCoordinator = container.corePipelineCoordinator
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
                    if (!settings.phoneOnlyVoiceMode) {
                        testMacRigConnection(settings.tailscaleRigUrl)
                    }
                }
            }
        }

        viewModelScope.launch {
            casStore.totalBytesFlow.collect { bytes ->
                _uiState.update { it.copy(casTotalBytes = bytes) }
            }
        }

        viewModelScope.launch {
            localRepo.clipCards.collect { cards ->
                _uiState.update { it.copy(clipCards = cards) }
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
     * instead of bypassing straight to a one-shot clip render.
     */
    fun openStoryInCorePipeline(
        plainText: String,
        headline: String = "",
        postCard: PostCardSource? = null
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
            val seeded = corePipelineCoordinator.seedSessionFromStory(
                rawStoryText = clean,
                headline = effectiveHeadline,
                scriptId = project.id
            )
            val session = if (postCard != null) {
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
                seeded.copy(selectedOverlayIds = (seeded.selectedOverlayIds + overlay.id).distinct()).also {
                    localRepo.saveCorePipelineSession(it)
                }
            } else {
                seeded
            }
            _uiState.update {
                it.copy(
                    corePipelineSession = session,
                    openCorePipelineEvent = true,
                    errorMessage = null,
                    statusNotice = "Story loaded in Core Pipeline (01 TTS)."
                )
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
        _uiState.update { it.copy(isRecordingCoreVoiceover = true, errorMessage = null) }
        vadAudioEngine.startMomentaryClutch(
            scope = viewModelScope,
            onError = { err ->
                _uiState.update {
                    it.copy(
                        isRecordingCoreVoiceover = false,
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
            _uiState.update { it.copy(isRecordingCoreVoiceover = false) }
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

    private fun activePhotoSha(): String? = _uiState.value.activeProject.stageConfig.activePhotoSha
        ?: _uiState.value.settings.persona.defaultPhotoSha.ifBlank { null }

    private fun startCoreStage(op: String, extras: Map<String, String?> = emptyMap()) {
        CoreStageWorker.ensureChannel(getApplication())
        _uiState.update {
            it.copy(
                isCoreStageBusy = true,
                coreStageProgress = 0f,
                coreStageStatusLabel = CoreStageWorker.labelFor(op) + "…",
                errorMessage = null
            )
        }
        CoreStageWorker.enqueue(getApplication(), op, extras)
    }

    /** Mirrors background pipeline jobs (WorkManager) into UI state; works across VM/process restarts. */
    private fun observeBackgroundStages() {
        val wm = WorkManager.getInstance(getApplication())
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
        _uiState.update { it.copy(corePipelineSession = stamped, coreUndoDepth = coreUndoStack.size) }
        viewModelScope.launch(Dispatchers.IO) { localRepo.saveCorePipelineSession(stamped) }
    }

    fun setCoreStripSilence(enabled: Boolean) = updateRenderOptions { it.copy(stripSilence = enabled) }

    fun setCorePunchIn(enabled: Boolean) = updateRenderOptions { it.copy(punchIn = enabled) }

    fun setCoreCaptions(enabled: Boolean) = updateRenderOptions { it.copy(captions = enabled) }

    fun setCoreBreakingStyle(style: BreakingNewsStyle) = updateRenderOptions { it.copy(breakingStyle = style) }

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
        commitCoreEdit(
            current.copy(
                clauses = current.clauses.mapIndexed { i, c ->
                    if (i != index) {
                        c
                    } else if (c.segmentMode == BrollSegmentMode.TALKING_HEAD) {
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

    fun createNewsroomLook(name: String): String {
        val look = NewsroomLook(
            name = name.trim().ifBlank { "LOOK_${_uiState.value.newsroomLooks.size + 1}" },
            isDefault = _uiState.value.newsroomLooks.isEmpty()
        )
        viewModelScope.launch(Dispatchers.IO) { localRepo.saveNewsroomLook(look) }
        _uiState.update { it.copy(newsroomLooks = listOf(look) + it.newsroomLooks) }
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
        viewModelScope.launch(Dispatchers.IO) { localRepo.saveNewsroomLook(clamped) }
    }

    fun duplicateNewsroomLook(id: String): String? {
        val source = _uiState.value.newsroomLooks.firstOrNull { it.id == id } ?: return null
        val copy = source.copy(
            id = NewsroomLook(name = "x").id,
            name = source.name.take(40) + "_COPY",
            isDefault = false,
            createdAtMillis = System.currentTimeMillis()
        )
        _uiState.update { it.copy(newsroomLooks = listOf(copy) + it.newsroomLooks) }
        viewModelScope.launch(Dispatchers.IO) { localRepo.saveNewsroomLook(copy) }
        return copy.id
    }

    fun setDefaultNewsroomLook(id: String) {
        _uiState.update { st -> st.copy(newsroomLooks = st.newsroomLooks.map { it.copy(isDefault = it.id == id) }) }
        viewModelScope.launch(Dispatchers.IO) {
            localRepo.setDefaultNewsroomLook(id)
            _uiState.update { it.copy(statusNotice = "Default look set — applied to the next cut.") }
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
            if (!_uiState.value.settings.phoneOnlyVoiceMode) {
                testMacRigConnection(trimmed)
            }
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
    }
}
