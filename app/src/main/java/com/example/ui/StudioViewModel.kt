package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AircheckApplication
import com.example.appfunctions.AppFunctionAvailabilitySync
import com.example.data.BrandChannel
import com.example.data.ClipCardRow
import com.example.data.ClipRecord
import com.example.data.ExportState
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import com.example.data.estimateDurationSeconds
import com.example.data.gateway.MacRigJobSnapshot
import com.example.data.gateway.RemoteRenderSummary
import com.example.data.local.CasMediaStore
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.data.local.StudioSettings
import com.example.engine.AircheckPlayerController
import com.example.engine.VoiceModelCareManager
import com.example.engine.VoiceQualityReport
import com.example.engine.VoiceStorageSnapshot
import com.example.ui.a2ui.AircheckA2uiController
import com.example.ui.a2ui.NewsroomA2uiAction
import com.voicestudio.edl.OtaBundleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    private val vadAudioEngine = container.vadAudioEngine

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    val a2uiController: AircheckA2uiController = AircheckA2uiController(
        context = application,
        scope = viewModelScope,
        onAction = ::handleNewsroomA2uiAction
    )

    private var rigHealthCheckJob: Job? = null
    private val macRigPollingJobs = mutableMapOf<String, Job>()
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
                    !remotionUnused -> "Remotion enabled (${settings.remotionUsedCount} uses)."
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
                    defaultSubline = activeProj.stageConfig.chyronSubline.ifBlank { "AIRCHECK LIVE DESK" },
                    defaultScript = activeProj.ideaSpark
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
    }

    private fun handleNewsroomA2uiAction(action: NewsroomA2uiAction) {
        when (action) {
            is NewsroomA2uiAction.AskInstallRemotion -> {
                requestRemotionInstall(
                    userRequest = action.userPrompt,
                    enableBrollPopups = action.enableBrollPopups
                )
            }

            is NewsroomA2uiAction.GenerateNewsroomOverlayClip -> {
                generateNewsroomClipFromOverlay(
                    headline = action.chyronHeadline,
                    subline = action.chyronSubline,
                    scriptText = action.spokenScript
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
                    defaultSubline = activeProj.stageConfig.chyronSubline.ifBlank { "AIRCHECK LIVE DESK" },
                    defaultScript = activeProj.ideaSpark
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

    fun generateNewsroomClipFromOverlay(
        headline: String,
        subline: String,
        scriptText: String
    ) {
        val cleanScript = scriptText.trim().ifBlank {
            _uiState.value.activeProject.ideaSpark.trim()
        }
        if (cleanScript.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Enter spoken newsroom copy before generating a clip.")
            }
            return
        }
        val cleanSubline = subline.trim().ifBlank { "AIRCHECK LIVE DESK" }
        val project = _uiState.value.activeProject
        persistProject(
            project.copy(
                stageConfig = project.stageConfig.copy(
                    chyronSubline = cleanSubline
                )
            )
        )
        viewModelScope.launch {
            profileStore.incrementRemotionUsedCount()
        }
        renderDraftAsBroadcastClip(
            plainText = cleanScript,
            headline = headline.trim()
        )
    }

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

    fun renderDraftAsBroadcastClip(
        plainText: String,
        headline: String = "",
        pollIntervalMs: Long = MAC_RIG_POLL_INTERVAL_MS
    ) {
        val clean = plainText.trim()
        if (clean.isBlank()) return
        val effectiveHeadline = headline.trim().ifBlank {
            clean.split('.', '!', '?').firstOrNull().orEmpty().trim().take(36).uppercase()
        }
        val project = _uiState.value.activeProject
        persistProject(
            project.copy(
                title = effectiveHeadline.ifBlank { "BREAKING NEWS" },
                ideaSpark = clean,
                voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
                masterAudioSha = null,
                stageConfig = project.stageConfig.copy(
                    chyronHeadline = effectiveHeadline,
                    chyronSubline = project.stageConfig.chyronSubline.ifBlank { "AIRCHECK LIVE DESK" },
                    showBrandBug = true
                ),
                beats = listOf(
                    ScriptBeat(
                        index = 0,
                        spokenText = clean,
                        durationSec = estimateDurationSeconds(clean, 8).coerceIn(4, 15)
                    )
                )
            )
        )
        renderActiveProject(pollIntervalMs = pollIntervalMs)
    }

    fun renderActiveProject(pollIntervalMs: Long = MAC_RIG_POLL_INTERVAL_MS) {
        val current = _uiState.value.activeProject
        val settings = _uiState.value.settings
        val photoSha = current.stageConfig.activePhotoSha ?: settings.persona.defaultPhotoSha.ifBlank { null }

        val effectiveProject = when {
            current.activeBeats.isNotEmpty() -> current.copy(voiceSource = VoiceSource.ENROLLED_VOICE_CLONE)
            current.ideaSpark.isNotBlank() -> current.copy(
                voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
                beats = listOf(ScriptBeat(index = 0, spokenText = current.ideaSpark.trim()))
            )
            else -> current
        }

        if (effectiveProject.activeBeats.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Draft text is empty; write or speak a draft before broadcasting.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isEnqueuingRender = true, errorMessage = null) }
            try {
                val resolvedUrl = resolveRigUrlOrThrow()
                val enqueued = macRigGateway.enqueueMacRigRender(
                    rigBaseUrl = resolvedUrl,
                    project = effectiveProject,
                    photoSha = photoSha,
                    resolution = "720P",
                    ratio = "9:16",
                    maxDurationSec = effectiveProject.totalActiveSeconds.coerceIn(5, 10)
                )
                val now = System.currentTimeMillis()
                val displayTitle = effectiveProject.stageConfig.chyronHeadline
                    .ifBlank { effectiveProject.title }
                    .ifBlank { effectiveProject.ideaSpark.take(36) }
                    .ifBlank { "Breaking News" }
                val clip = ClipRecord(
                    id = enqueued.clipId,
                    scriptId = effectiveProject.id,
                    jobId = enqueued.jobId,
                    title = displayTitle,
                    photoSha = photoSha,
                    voiceSha = effectiveProject.masterAudioSha,
                    alignSha = null,
                    lipsyncSha = null,
                    finalSha = null,
                    status = RenderJobStatus.QUEUED,
                    progressFraction = 0.08f,
                    stageDetail = "Preparing broadcast",
                    errorMessage = null,
                    durationMs = effectiveProject.totalActiveSeconds * 1000L,
                    waveformPeaks = emptyList(),
                    thumbHash = null,
                    alignedWords = emptyList(),
                    chyronHeadline = effectiveProject.stageConfig.chyronHeadline.ifBlank { displayTitle },
                    chyronSubline = effectiveProject.stageConfig.chyronSubline,
                    remoteLipsyncUrl = null,
                    remoteFinalUrl = null,
                    exportedUri = null,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
                localRepo.upsertClip(clip)
                _uiState.update {
                    it.copy(
                        isEnqueuingRender = false,
                        liveNavToClipId = clip.id,
                        statusNotice = "Generating news clip…"
                    )
                }
                startMacRigJobPolling(
                    rigBaseUrl = resolvedUrl,
                    initialClip = clip,
                    pollIntervalMs = pollIntervalMs
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isEnqueuingRender = false,
                        errorMessage = e.localizedMessage ?: "Unable to connect to your Mac studio."
                    )
                }
            }
        }
    }

    private suspend fun resolveRigUrlOrThrow(): String {
        val configured = _uiState.value.settings.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
        val health = macRigGateway.resolveAndCheckHealth(configured)
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
        return health.resolvedUrl
    }

    private fun startMacRigJobPolling(
        rigBaseUrl: String,
        initialClip: ClipRecord,
        pollIntervalMs: Long
    ) {
        macRigPollingJobs.remove(initialClip.id)?.cancel()
        val intervalMs = pollIntervalMs.coerceAtLeast(25L)
        macRigPollingJobs[initialClip.id] = viewModelScope.launch {
            var consecutiveErrors = 0
            try {
                while (isActive) {
                    delay(intervalMs)
                    val pollResult = runCatching {
                        macRigGateway.pollMacRigJob(rigBaseUrl, initialClip.jobId)
                    }
                    if (pollResult.isFailure) {
                        val err = pollResult.exceptionOrNull()
                        if (err is CancellationException) throw err
                        consecutiveErrors++
                        if (consecutiveErrors >= 5) {
                            val latest = localRepo.getClipById(initialClip.id) ?: initialClip
                            val errMsg = err?.localizedMessage ?: "Lost connection to Mac studio while rendering."
                            localRepo.upsertClip(
                                latest.copy(
                                    status = RenderJobStatus.FAILED,
                                    stageDetail = "Connection lost",
                                    errorMessage = errMsg,
                                    updatedAtMillis = System.currentTimeMillis()
                                )
                            )
                            _uiState.update { it.copy(errorMessage = errMsg) }
                            break
                        }
                        continue
                    }

                    consecutiveErrors = 0
                    val snapshot = pollResult.getOrThrow()
                    val latest = localRepo.getClipById(initialClip.id) ?: initialClip

                    when (snapshot.status) {
                        RenderJobStatus.READY -> {
                            val videoUrl = snapshot.videoUrl
                            if (videoUrl.isNullOrBlank()) {
                                val msg = "Render finished without a video URL."
                                localRepo.upsertClip(
                                    latest.copy(
                                        status = RenderJobStatus.FAILED,
                                        stageDetail = "Missing video",
                                        errorMessage = msg,
                                        updatedAtMillis = System.currentTimeMillis()
                                    )
                                )
                                _uiState.update { it.copy(errorMessage = msg) }
                                break
                            }

                            val resolvedDuration = if (snapshot.durationMs > 0L) snapshot.durationMs else latest.durationMs
                            localRepo.upsertClip(
                                latest.copy(
                                    status = RenderJobStatus.PACKAGING,
                                    progressFraction = 0.95f,
                                    stageDetail = "Saving video to phone",
                                    remoteFinalUrl = videoUrl,
                                    durationMs = resolvedDuration,
                                    errorMessage = null,
                                    updatedAtMillis = System.currentTimeMillis()
                                )
                            )

                            val downloadRes = runCatching {
                                macRigGateway.downloadRenderedMp4ToCas(videoUrl)
                            }
                            downloadRes.onSuccess { stored ->
                                val currentClip = localRepo.getClipById(initialClip.id) ?: latest
                                localRepo.upsertClip(
                                    currentClip.copy(
                                        status = RenderJobStatus.READY,
                                        progressFraction = 1f,
                                        stageDetail = "Ready",
                                        finalSha = stored.sha256,
                                        remoteFinalUrl = videoUrl,
                                        durationMs = resolvedDuration,
                                        errorMessage = null,
                                        updatedAtMillis = System.currentTimeMillis()
                                    )
                                )
                                _uiState.update {
                                    it.copy(statusNotice = "News clip ready.")
                                }
                                refreshRemoteClips()
                            }.onFailure { err ->
                                if (err is CancellationException) throw err
                                val msg = err.localizedMessage ?: "Failed to download video."
                                val currentClip = localRepo.getClipById(initialClip.id) ?: latest
                                localRepo.upsertClip(
                                    currentClip.copy(
                                        status = RenderJobStatus.FAILED,
                                        stageDetail = "Download failed",
                                        remoteFinalUrl = videoUrl,
                                        errorMessage = msg,
                                        updatedAtMillis = System.currentTimeMillis()
                                    )
                                )
                                _uiState.update { it.copy(errorMessage = msg) }
                            }
                            break
                        }

                        RenderJobStatus.FAILED -> {
                            val msg = snapshot.errorMessage ?: "Video generation failed."
                            localRepo.upsertClip(
                                latest.copy(
                                    status = RenderJobStatus.FAILED,
                                    progressFraction = snapshot.progressFraction,
                                    stageDetail = snapshot.stageDetail.ifBlank { "Failed" },
                                    errorMessage = msg,
                                    updatedAtMillis = System.currentTimeMillis()
                                )
                            )
                            _uiState.update { it.copy(errorMessage = msg) }
                            break
                        }

                        else -> {
                            localRepo.upsertClip(
                                latest.copy(
                                    status = snapshot.status,
                                    progressFraction = snapshot.progressFraction.coerceIn(0.08f, 0.95f),
                                    stageDetail = friendlyStageDetail(snapshot),
                                    errorMessage = null,
                                    updatedAtMillis = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            } finally {
                macRigPollingJobs.remove(initialClip.id)
            }
        }
    }

    private fun friendlyStageDetail(snapshot: MacRigJobSnapshot): String = when (snapshot.status) {
        RenderJobStatus.QUEUED -> "Starting studio render"
        RenderJobStatus.VOICE -> "Generating anchor voice"
        RenderJobStatus.LIPSYNC -> "Filming news anchor"
        RenderJobStatus.PACKAGING -> "Adding breaking news graphics"
        else -> snapshot.stageDetail.ifBlank { snapshot.status.label }
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
        macRigPollingJobs.values.forEach { it.cancel() }
        macRigPollingJobs.clear()
    }

    companion object {
        const val MAC_RIG_POLL_INTERVAL_MS: Long = 2_500L
    }
}
