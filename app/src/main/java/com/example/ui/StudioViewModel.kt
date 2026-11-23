package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AircheckApplication
import com.example.data.BrandChannel
import com.example.data.ClipCardRow
import com.example.data.ClipRecord
import com.example.data.ExportState
import com.example.data.NewsroomStageConfig
import com.example.data.PoseGateResult
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import com.example.data.estimateDurationSeconds
import com.example.data.gateway.MacRigJobSnapshot
import com.example.data.local.CasMediaStore
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.data.local.StudioSettings
import com.example.engine.AircheckPlayerController
import com.example.engine.AudioEngine
import com.voicestudio.edl.ClauseSynthesisPhase
import com.voicestudio.edl.ClauseSynthesisProgress
import com.voicestudio.edl.EdlController
import com.voicestudio.edl.EdlTimelineCalculator
import com.voicestudio.edl.OtaArtifactRole
import com.voicestudio.edl.OtaBundleState
import com.voicestudio.edl.OtaBundleStatus
import com.voicestudio.edl.PreferenceTelemetryRecord
import com.voicestudio.edl.PreferenceUserAction
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.sync.OtaBundleSyncManager
import com.voicestudio.sync.ProcessMemoryReader
import com.voicestudio.sync.VoiceStudioSyncCoordinator
import com.voicestudio.ui.ClipperUiState
import com.voicestudio.ui.MAX_GLOBAL_GAP_SCALE
import com.voicestudio.ui.MIN_GLOBAL_GAP_SCALE
import com.voicestudio.ui.Media3BeatExporter
import com.voicestudio.ui.PcmWaveformExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    val activeProject: ScriptProject = ScriptProject(voiceSource = VoiceSource.MIC_VOICEOVER),
    val clipCards: List<ClipCardRow> = emptyList(),
    val settings: StudioSettings = StudioSettings(),
    val casTotalBytes: Long = 0L,
    val isEnqueuingRender: Boolean = false,
    val exportState: ExportState = ExportState.IDLE,
    val pendingShareIntent: Intent? = null,
    val liveNavToClipId: String? = null,
    val waveformPeaks: List<Float> = emptyList(),
    val isPlayingAudio: Boolean = false,
    val playbackProgress: Float = 0f,
    val isRecordingMic: Boolean = false,
    val recordingTargetBeatId: String? = null,
    val errorMessage: String? = null,
    val statusNotice: String? = null,
    val otaBundleState: OtaBundleState = OtaBundleState(),
    val rigHealth: RigHealthUiState = RigHealthUiState(),
    val synthesisProgress: ClauseSynthesisProgress = ClauseSynthesisProgress(),
    val unsyncedPreferenceCount: Int = 0,
    val measuredVmRssMb: Int? = null,
    val clipperState: ClipperUiState = ClipperUiState()
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AircheckApplication).container
    private val localRepo = container.localRepo
    private val profileStore = container.profileStore
    val casStore: CasMediaStore = container.casStore
    val playerController: AircheckPlayerController = container.playerController
    private val audioEngine: AudioEngine = container.audioEngine
    private val clipExporter = container.clipExporter
    private val macRigGateway = container.macRigGateway
    private val otaBundleSyncManager: OtaBundleSyncManager = container.otaBundleSyncManager
    private val edlController: EdlController = container.edlController
    private val voiceStudioCoordinator: VoiceStudioSyncCoordinator = container.voiceStudioCoordinator

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var voiceManifestObserverJob: Job? = null
    private var rigHealthCheckJob: Job? = null
    private val macRigPollingJobs = mutableMapOf<String, Job>()
    private val scriptSaveMutex = Mutex()
    private val clipperSaveMutex = Mutex()
    private var activeClipperProjectId: String? = null
    private var edlPlayerInitialized: Boolean = false
    private var activeRecordingFile: File? = null
    private var initializedProject = false

    init {
        viewModelScope.launch {
            profileStore.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
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
                        val initial = ScriptProject(voiceSource = VoiceSource.MIC_VOICEOVER)
                        localRepo.saveScript(initial)
                        _uiState.update { it.copy(projects = listOf(initial), activeProject = initial) }
                    } else {
                        val preferredId = _uiState.value.settings.activeScriptId
                        val chosen = savedProjects.firstOrNull { it.id == preferredId } ?: savedProjects.first()
                        _uiState.update { it.copy(projects = savedProjects, activeProject = chosen) }
                        loadWaveformForProject(chosen)
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
            audioEngine.isPlaying.collect { playing ->
                _uiState.update { it.copy(isPlayingAudio = playing) }
            }
        }
        viewModelScope.launch {
            audioEngine.playbackProgress.collect { progress ->
                _uiState.update { it.copy(playbackProgress = progress) }
            }
        }
        viewModelScope.launch {
            audioEngine.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecordingMic = recording) }
            }
        }
        viewModelScope.launch {
            audioEngine.liveMicPeaks.collect { livePeaks ->
                if (_uiState.value.isRecordingMic && livePeaks.isNotEmpty()) {
                    _uiState.update { it.copy(waveformPeaks = livePeaks) }
                }
            }
        }

        viewModelScope.launch {
            otaBundleSyncManager.bundleState.collect { otaState ->
                _uiState.update { cur ->
                    val statusLabel = formatOtaStatusSummary(otaState)
                    cur.copy(
                        otaBundleState = otaState,
                        clipperState = cur.clipperState.copy(
                            otaReady = otaState.status == OtaBundleStatus.READY,
                            otaStatusText = statusLabel
                        )
                    )
                }
            }
        }

        viewModelScope.launch {
            voiceStudioCoordinator.progress.collect { synthProgress ->
                _uiState.update { cur ->
                    cur.copy(
                        synthesisProgress = synthProgress,
                        measuredVmRssMb = synthProgress.measuredRssMb ?: cur.measuredVmRssMb,
                        clipperState = cur.clipperState.copy(
                            synthesisError = if (synthProgress.phase == ClauseSynthesisPhase.FAILED) {
                                synthProgress.errorMessage
                            } else {
                                null
                            }
                        )
                    )
                }
            }
        }

        viewModelScope.launch {
            localRepo.unsyncedPreferenceCount.collect { count ->
                _uiState.update { it.copy(unsyncedPreferenceCount = count) }
            }
        }

        viewModelScope.launch {
            edlController.isPlaying.collect { playing ->
                _uiState.update { cur ->
                    cur.copy(clipperState = cur.clipperState.copy(isPlaying = playing))
                }
            }
        }

        viewModelScope.launch {
            edlController.playheadSample.collect { sample ->
                _uiState.update { cur ->
                    cur.copy(clipperState = cur.clipperState.copy(playheadSample = sample))
                }
            }
        }

        refreshVmRssTelemetry()
        testMacRigConnection()
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

    private fun loadWaveformForProject(project: ScriptProject) {
        val wavFile = casStore.fileForSha(project.masterAudioSha, "wav")
        if (wavFile != null) {
            viewModelScope.launch {
                val peaks = AudioEngine.decodeWavPeaks(wavFile, 64)
                _uiState.update { it.copy(waveformPeaks = peaks) }
            }
        } else {
            _uiState.update { it.copy(waveformPeaks = emptyList()) }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissStatusNotice() {
        _uiState.update { it.copy(statusNotice = null) }
    }

    fun clearPendingShareIntent() {
        _uiState.update { it.copy(pendingShareIntent = null) }
    }

    fun consumeLiveNavToClip() {
        _uiState.update { it.copy(liveNavToClipId = null) }
    }

    // --- Project & Script Editing (100% Local Room Storage) ---

    fun createNewProject() {
        val settings = _uiState.value.settings
        val fresh = ScriptProject(
            voiceSource = settings.voiceSource,
            stageConfig = NewsroomStageConfig(
                photoSha = settings.persona.defaultPhotoSha.ifBlank { null },
                stage4Mode = settings.stage4Mode
            )
        )
        persistProject(fresh)
        _uiState.update {
            it.copy(
                waveformPeaks = emptyList(),
                exportState = ExportState.IDLE
            )
        }
    }

    fun updateTitle(title: String) {
        persistProject(_uiState.value.activeProject.copy(title = title))
    }

    fun setTargetDuration(seconds: Int) {
        persistProject(_uiState.value.activeProject.copy(targetDurationSec = seconds))
    }

    fun updateBeatText(beatId: String, text: String) {
        val current = _uiState.value.activeProject
        val updatedBeats = current.beats.map { beat ->
            if (beat.id == beatId) {
                beat.copy(
                    spokenText = text,
                    durationSec = estimateDurationSeconds(text, beat.durationSec)
                )
            } else beat
        }
        persistProject(current.copy(beats = updatedBeats))
    }

    fun toggleCutBeat(beatId: String) {
        val current = _uiState.value.activeProject
        persistProject(
            current.copy(
                beats = current.beats.map { if (it.id == beatId) it.copy(cut = !it.cut) else it }
            )
        )
    }

    fun addBeat() {
        val current = _uiState.value.activeProject
        val next = ScriptBeat(index = current.beats.size, spokenText = "", durationSec = 5)
        persistProject(current.copy(beats = current.beats + next))
    }

    fun deleteBeat(beatId: String) {
        val current = _uiState.value.activeProject
        if (current.beats.size <= 1) {
            updateBeatText(beatId, "")
            return
        }
        val remaining = current.beats.filterNot { it.id == beatId }
            .mapIndexed { idx, beat -> beat.copy(index = idx) }
        persistProject(current.copy(beats = remaining))
    }

    // --- Optional Custom Presenter Photo (100% Local CAS) ---

    fun importPhoto(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            try {
                val stored = casStore.importImageFromUri(uri)
                profileStore.updatePersona(defaultPhotoSha = stored.sha256)
                val current = _uiState.value.activeProject
                val baseStage = current.stageConfig.copy(
                    photoSha = stored.sha256,
                    normalizedPhotoSha = null,
                    poseGate = PoseGateResult(
                        checked = true,
                        valid = true,
                        note = "Local photo ready (${stored.sha256.take(8)})."
                    )
                )
                persistProject(current.copy(stageConfig = baseStage))
                _uiState.update {
                    it.copy(statusNotice = "Presenter photo cached locally (${stored.sha256.take(8)}).")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = e.localizedMessage ?: "Could not import photo.")
                }
            }
        }
    }

    fun updateStageConfig(transform: (NewsroomStageConfig) -> NewsroomStageConfig) {
        val current = _uiState.value.activeProject
        persistProject(current.copy(stageConfig = transform(current.stageConfig)))
    }

    fun setVoiceSource(source: VoiceSource) {
        viewModelScope.launch { profileStore.setVoiceSource(source) }
        persistProject(_uiState.value.activeProject.copy(voiceSource = source))
    }

    // --- Local Mic Recording & WAV Preview ---

    fun toggleMicRecording(beatId: String? = null) {
        if (_uiState.value.isRecordingMic) {
            val targetFile = activeRecordingFile
            val targetBeat = _uiState.value.recordingTargetBeatId
            viewModelScope.launch {
                if (targetFile != null) {
                    val (durSec, peaks) = audioEngine.stopMicRecording(targetFile)
                    val stored = casStore.putFile(targetFile, "wav")
                    targetFile.delete()
                    val current = _uiState.value.activeProject
                    val updated = if (targetBeat != null) {
                        current.copy(
                            masterAudioSha = stored.sha256,
                            voiceSource = VoiceSource.MIC_VOICEOVER,
                            beats = current.beats.map { b ->
                                if (b.id == targetBeat) b.copy(beatAudioSha = stored.sha256) else b
                            }
                        )
                    } else {
                        current.copy(
                            masterAudioSha = stored.sha256,
                            voiceSource = VoiceSource.MIC_VOICEOVER
                        )
                    }
                    persistProject(updated)
                    _uiState.update {
                        it.copy(
                            waveformPeaks = peaks,
                            recordingTargetBeatId = null,
                            statusNotice = "Recorded ${"%.1f".format(durSec)}s WAV locally."
                        )
                    }
                }
                activeRecordingFile = null
            }
        } else {
            viewModelScope.launch {
                val scratch = casStore.createTempScratchFile("mic_take", "wav")
                activeRecordingFile = scratch
                _uiState.update { it.copy(recordingTargetBeatId = beatId, errorMessage = null) }
                audioEngine.startMicRecording(
                    scope = viewModelScope,
                    outputFile = scratch,
                    onError = { msg ->
                        activeRecordingFile = null
                        _uiState.update { it.copy(recordingTargetBeatId = null, errorMessage = msg) }
                    }
                )
            }
        }
    }

    fun togglePreviewMasterWav() {
        if (_uiState.value.isPlayingAudio) {
            audioEngine.stopPlayback()
            return
        }
        val wavFile = casStore.fileForSha(_uiState.value.activeProject.masterAudioSha, "wav")
        if (wavFile == null) {
            _uiState.update { it.copy(errorMessage = "Record your voice first to preview audio.") }
            return
        }
        audioEngine.playWavFile(viewModelScope, wavFile)
    }

    // --- Direct Mac Studio Rig Render Bridge (Zero Firebase, Zero Sign-In) ---

    fun renderActiveProject(pollIntervalMs: Long = MAC_RIG_POLL_INTERVAL_MS) {
        val current = _uiState.value.activeProject
        val settings = _uiState.value.settings
        val photoSha = current.stageConfig.activePhotoSha ?: settings.persona.defaultPhotoSha.ifBlank { null }

        val hasRecordedWav = casStore.fileForSha(current.masterAudioSha, "wav") != null
        val effectiveProject = when {
            current.voiceSource == VoiceSource.MIC_VOICEOVER && !hasRecordedWav && current.activeBeats.isNotEmpty() ->
                current.copy(voiceSource = VoiceSource.ENROLLED_VOICE_CLONE)
            else -> current
        }

        if (effectiveProject.voiceSource == VoiceSource.ENROLLED_VOICE_CLONE && effectiveProject.activeBeats.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Tap Mic to record your voice or type a spoken line before rendering.")
            }
            return
        }
        if (effectiveProject.voiceSource == VoiceSource.MIC_VOICEOVER && !hasRecordedWav) {
            _uiState.update {
                it.copy(errorMessage = "Tap Mic to record your voice before rendering.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isEnqueuingRender = true, errorMessage = null) }
            try {
                val health = macRigGateway.resolveAndCheckHealth(settings.tailscaleRigUrl)
                val resolvedUrl = health.resolvedUrl
                _uiState.update {
                    it.copy(
                        rigHealth = RigHealthUiState(
                            status = RigConnectionStatus.ONLINE,
                            resolvedUrl = resolvedUrl,
                            loraVersion = health.loraVersion,
                            bundleVersion = health.bundleVersion,
                            pendingPreferences = health.pendingPreferences,
                            renderBridgeAvailable = health.renderBridgeAvailable,
                            errorMessage = null
                        )
                    )
                }

                val enqueued = macRigGateway.enqueueMacRigRender(
                    rigBaseUrl = resolvedUrl,
                    project = effectiveProject,
                    photoSha = photoSha,
                    resolution = "720P",
                    ratio = "9:16",
                    maxDurationSec = effectiveProject.targetDurationSec.coerceIn(5, 10)
                )
                val now = System.currentTimeMillis()
                val clip = ClipRecord(
                    id = enqueued.clipId,
                    scriptId = effectiveProject.id,
                    jobId = enqueued.jobId,
                    title = effectiveProject.title.ifBlank { "Untitled Aircheck" },
                    photoSha = photoSha,
                    voiceSha = effectiveProject.masterAudioSha,
                    alignSha = null,
                    lipsyncSha = null,
                    finalSha = null,
                    status = RenderJobStatus.QUEUED,
                    progressFraction = 0.05f,
                    stageDetail = "Queued on Mac Studio rig",
                    errorMessage = null,
                    durationMs = effectiveProject.totalActiveSeconds * 1000L,
                    waveformPeaks = _uiState.value.waveformPeaks,
                    thumbHash = null,
                    alignedWords = emptyList(),
                    chyronHeadline = effectiveProject.stageConfig.chyronHeadline.ifBlank { effectiveProject.title },
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
                        statusNotice = "Render queued on Mac Rig (${enqueued.jobId.take(8)})."
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
                        errorMessage = e.localizedMessage ?: "Unable to reach Mac Studio rig."
                    )
                }
            }
        }
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
                            val errMsg = err?.localizedMessage ?: "Lost connection to Mac Rig while polling."
                            localRepo.upsertClip(
                                latest.copy(
                                    status = RenderJobStatus.FAILED,
                                    stageDetail = "Rig unreachable",
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
                                val msg = "Mac Rig reported READY without a video URL."
                                localRepo.upsertClip(
                                    latest.copy(
                                        status = RenderJobStatus.FAILED,
                                        stageDetail = "Missing video URL",
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
                                    stageDetail = "Downloading MP4 locally",
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
                                        stageDetail = formatMacRigReadyDetail(snapshot),
                                        finalSha = stored.sha256,
                                        remoteFinalUrl = videoUrl,
                                        durationMs = resolvedDuration,
                                        errorMessage = null,
                                        updatedAtMillis = System.currentTimeMillis()
                                    )
                                )
                                _uiState.update {
                                    it.copy(statusNotice = "Clip ready (${currentClip.title.ifBlank { "Aircheck" }}).")
                                }
                            }.onFailure { err ->
                                if (err is CancellationException) throw err
                                val msg = err.localizedMessage ?: "Failed to download rendered MP4."
                                val currentClip = localRepo.getClipById(initialClip.id) ?: latest
                                localRepo.upsertClip(
                                    currentClip.copy(
                                        status = RenderJobStatus.FAILED,
                                        stageDetail = "MP4 download failed",
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
                            val msg = snapshot.errorMessage ?: "Mac Rig render failed."
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
                                    progressFraction = snapshot.progressFraction.coerceIn(0.05f, 0.95f),
                                    stageDetail = snapshot.stageDetail.ifBlank { snapshot.status.label },
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

    private fun formatMacRigReadyDetail(snapshot: MacRigJobSnapshot): String {
        val parts = mutableListOf<String>()
        snapshot.cer?.let { parts += "CER ${"%.2f".format(it)}" }
        snapshot.wespeakerCosine?.let { parts += "Sim ${"%.2f".format(it)}" }
        snapshot.dnsmosP808?.let { parts += "MOS ${"%.2f".format(it)}" }
        return if (parts.isEmpty()) "Ready" else "Ready · ${parts.joinToString(" · ")}"
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
                        statusNotice = "Saved to Movies/Aircheck."
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

    // --- Persisted Settings, Local Rig Health & Hairline Clipper ---

    fun setBrandChannel(channel: BrandChannel) {
        viewModelScope.launch { profileStore.setBrandChannel(channel) }
    }

    fun clearCasCache() {
        viewModelScope.launch {
            casStore.clearAllCache()
            _uiState.update { it.copy(statusNotice = "Local media cache cleared.") }
        }
    }

    fun refreshVmRssTelemetry() {
        viewModelScope.launch(Dispatchers.IO) {
            val rssMb = ProcessMemoryReader.readVmRssMb()
            _uiState.update { it.copy(measuredVmRssMb = rssMb) }
        }
    }

    fun saveTailscaleRigUrl(url: String) {
        val trimmed = url.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
        viewModelScope.launch {
            profileStore.setTailscaleRigUrl(trimmed)
            otaBundleSyncManager.refreshLocalState(trimmed)
            _uiState.update {
                it.copy(
                    rigHealth = RigHealthUiState(resolvedUrl = trimmed),
                    statusNotice = "Mac Rig URL saved."
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
            }.onFailure { err ->
                if (err is CancellationException) throw err
                _uiState.update {
                    it.copy(
                        rigHealth = RigHealthUiState(
                            status = RigConnectionStatus.ERROR,
                            resolvedUrl = candidate,
                            errorMessage = err.localizedMessage ?: "Mac Rig connection failed."
                        )
                    )
                }
            }
        }
    }

    fun syncOtaBundleFromRig(overrideUrl: String? = null) {
        val candidate = overrideUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: _uiState.value.settings.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            val resolvedUrl = runCatching { macRigGateway.resolveAndCheckHealth(candidate).resolvedUrl }
                .getOrDefault(candidate)
            if (resolvedUrl != _uiState.value.settings.tailscaleRigUrl) {
                profileStore.setTailscaleRigUrl(resolvedUrl)
            }
            val result = voiceStudioCoordinator.syncOtaBundleNow(resolvedUrl)
            refreshVmRssTelemetry()
            result.onSuccess { manifest ->
                _uiState.update {
                    it.copy(
                        statusNotice = "OTA ready: ${manifest.bundleVersion} (LoRA v${manifest.loraVersion})."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(errorMessage = err.localizedMessage ?: "OTA bundle sync failed.")
                }
            }
        }
    }

    fun syncPreferencesNow(overrideUrl: String? = null) {
        val candidate = overrideUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: _uiState.value.settings.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
        viewModelScope.launch {
            val resolvedUrl = runCatching { macRigGateway.resolveAndCheckHealth(candidate).resolvedUrl }
                .getOrDefault(candidate)
            if (resolvedUrl != _uiState.value.settings.tailscaleRigUrl) {
                profileStore.setTailscaleRigUrl(resolvedUrl)
            }
            voiceStudioCoordinator.enqueuePreferenceSyncAndLoraCheck(
                tailscaleBaseUrl = resolvedUrl,
                requireUnmetered = false
            )
            _uiState.update {
                it.copy(statusNotice = "Queued preferences.jsonl upload & LoRA delta check.")
            }
        }
    }

    fun loadClipperForProject(projectId: String) {
        if (activeClipperProjectId == projectId && voiceManifestObserverJob?.isActive == true) {
            return
        }
        activeClipperProjectId = projectId
        voiceManifestObserverJob?.cancel()
        voiceManifestObserverJob = viewModelScope.launch {
            otaBundleSyncManager.refreshLocalState(_uiState.value.settings.tailscaleRigUrl)
            ensureEdlPlayerInitializedIfPossible()
            localRepo.observeVoiceManifest(projectId).collectLatest { manifest ->
                val peaks = if (manifest != null) {
                    withContext(Dispatchers.IO) {
                        PcmWaveformExtractor.extractMinMaxPeaks(manifest)
                    }
                } else {
                    FloatArray(0)
                }
                if (manifest != null && edlPlayerInitialized) {
                    withContext(Dispatchers.IO) {
                        runCatching { edlController.preloadManifestFiles(manifest) }
                        runCatching { edlController.updateFromManifest(manifest) }
                    }
                }
                _uiState.update { cur ->
                    cur.copy(
                        clipperState = cur.clipperState.copy(
                            manifest = manifest,
                            waveformMinMaxPeaks = peaks
                        )
                    )
                }
            }
        }
    }

    fun toggleClipperPlayPause() {
        if (edlController.isPlaying.value) {
            edlController.pause()
            return
        }
        val manifest = _uiState.value.clipperState.manifest
        if (manifest == null || manifest.clauses.isEmpty()) {
            _uiState.update { cur ->
                cur.copy(
                    clipperState = cur.clipperState.copy(
                        synthesisError = "No synthesized 44.1 kHz clauses ready for EDL playback."
                    )
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val initErr = ensureEdlPlayerInitializedIfPossible()
            if (initErr != null) {
                _uiState.update { cur ->
                    cur.copy(clipperState = cur.clipperState.copy(synthesisError = initErr))
                }
                return@launch
            }
            val updateRes = edlController.updateFromManifest(manifest)
            if (updateRes.isFailure) {
                _uiState.update { cur ->
                    cur.copy(
                        clipperState = cur.clipperState.copy(
                            synthesisError = updateRes.exceptionOrNull()?.message ?: "EDL snapshot update failed."
                        )
                    )
                }
                return@launch
            }
            val startRes = edlController.start()
            if (startRes.isFailure) {
                _uiState.update { cur ->
                    cur.copy(
                        clipperState = cur.clipperState.copy(
                            synthesisError = startRes.exceptionOrNull()?.message ?: "Oboe EDL start failed."
                        )
                    )
                }
            } else {
                _uiState.update { cur ->
                    cur.copy(clipperState = cur.clipperState.copy(synthesisError = null))
                }
            }
        }
    }

    fun seekClipperSample(sample: Long) {
        val clamped = sample.coerceAtLeast(0L)
        edlController.seekToSample(clamped)
        _uiState.update { cur ->
            cur.copy(clipperState = cur.clipperState.copy(playheadSample = clamped))
        }
    }

    fun setClipperEditingClause(clauseId: String?) {
        _uiState.update { cur ->
            cur.copy(clipperState = cur.clipperState.copy(editingClauseId = clauseId))
        }
    }

    fun onClipperSwapTake(clauseId: String, nextTakeIndex: Int) {
        val currentManifest = _uiState.value.clipperState.manifest ?: return
        val clause = currentManifest.clauses.firstOrNull { it.clauseId == clauseId } ?: return
        if (nextTakeIndex !in clause.candidates.indices || nextTakeIndex == clause.selectedTakeIndex) return

        val rejectedTake = clause.selectedCandidate
        val chosenTake = clause.candidates[nextTakeIndex]
        val updatedManifest = currentManifest.copy(
            clauses = currentManifest.clauses.map { node ->
                if (node.clauseId == clauseId) node.copy(selectedTakeIndex = nextTakeIndex) else node
            }
        )

        val telemetry = PreferenceTelemetryRecord(
            timestamp = System.currentTimeMillis(),
            clauseId = clause.clauseId,
            loraVersion = updatedManifest.activeLoraVersion,
            rawText = clause.rawText,
            normalizedPhonemes = clause.normalizedPhonemes,
            userAction = PreferenceUserAction.TAKE_SWAP,
            userGapScale = updatedManifest.globalGapScale.coerceIn(MIN_GLOBAL_GAP_SCALE, MAX_GLOBAL_GAP_SCALE),
            chosenTokens = chosenTake.discreteAudioTokens.toList(),
            rejectedTokens = rejectedTake?.discreteAudioTokens?.toList().orEmpty()
        )

        applyClipperManifestMutation(updatedManifest, telemetry)
    }

    fun onClipperOverrideClauseText(clauseId: String, newText: String, newPhonemes: String) {
        val currentManifest = _uiState.value.clipperState.manifest ?: return
        val clause = currentManifest.clauses.firstOrNull { it.clauseId == clauseId } ?: return
        val trimmedText = newText.trim()
        if (trimmedText.isEmpty()) return
        val trimmedPhonemes = newPhonemes.trim()

        val updatedManifest = currentManifest.copy(
            clauses = currentManifest.clauses.map { node ->
                if (node.clauseId == clauseId) {
                    node.copy(rawText = trimmedText, normalizedPhonemes = trimmedPhonemes)
                } else {
                    node
                }
            }
        )

        val currentTokens = clause.selectedCandidate?.discreteAudioTokens?.toList().orEmpty()
        val telemetry = PreferenceTelemetryRecord(
            timestamp = System.currentTimeMillis(),
            clauseId = clause.clauseId,
            loraVersion = updatedManifest.activeLoraVersion,
            rawText = trimmedText,
            normalizedPhonemes = trimmedPhonemes,
            userAction = PreferenceUserAction.PHONEME_OVERRIDE,
            userGapScale = updatedManifest.globalGapScale.coerceIn(MIN_GLOBAL_GAP_SCALE, MAX_GLOBAL_GAP_SCALE),
            chosenTokens = emptyList(),
            rejectedTokens = currentTokens
        )

        applyClipperManifestMutation(updatedManifest, telemetry)
        voiceStudioCoordinator.enqueueSingleClauseResynthesis(updatedManifest.projectId, clauseId)
        _uiState.update { it.copy(statusNotice = "Queued single-clause resynthesis for $clauseId.") }
    }

    fun onClipperGlobalGapScaleChange(scale: Float) {
        val currentManifest = _uiState.value.clipperState.manifest ?: return
        val clamped = scale.coerceIn(MIN_GLOBAL_GAP_SCALE, MAX_GLOBAL_GAP_SCALE)
        val updatedManifest = currentManifest.copy(globalGapScale = clamped)

        val pos = EdlTimelineCalculator.mapSampleToPosition(updatedManifest, _uiState.value.clipperState.playheadSample)
        val referenceClause = updatedManifest.clauses.firstOrNull { it.clauseId == pos.clauseId }
            ?: updatedManifest.clauses.firstOrNull()

        val telemetry = referenceClause?.let { clause ->
            PreferenceTelemetryRecord(
                timestamp = System.currentTimeMillis(),
                clauseId = clause.clauseId,
                loraVersion = updatedManifest.activeLoraVersion,
                rawText = clause.rawText,
                normalizedPhonemes = clause.normalizedPhonemes,
                userAction = PreferenceUserAction.PACE_EDIT,
                userGapScale = clamped,
                chosenTokens = clause.selectedCandidate?.discreteAudioTokens?.toList().orEmpty(),
                rejectedTokens = emptyList()
            )
        }

        applyClipperManifestMutation(updatedManifest, telemetry)
    }

    fun onClipperToggleVideoAnchor(clauseId: String, wordIndex: Int, videoAnchorId: String?) {
        val currentManifest = _uiState.value.clipperState.manifest ?: return
        val clause = currentManifest.clauses.firstOrNull { it.clauseId == clauseId } ?: return
        val selected = clause.selectedCandidate ?: return
        if (wordIndex !in selected.tokens.indices) return

        val updatedTokens = selected.tokens.mapIndexed { idx, token ->
            if (idx == wordIndex) token.copy(videoAnchorId = videoAnchorId) else token
        }
        val updatedCandidates = clause.candidates.mapIndexed { idx, candidate ->
            if (idx == clause.selectedTakeIndex) candidate.copy(tokens = updatedTokens) else candidate
        }
        val updatedManifest = currentManifest.copy(
            clauses = currentManifest.clauses.map { node ->
                if (node.clauseId == clauseId) node.copy(candidates = updatedCandidates) else node
            }
        )

        val telemetry = PreferenceTelemetryRecord(
            timestamp = System.currentTimeMillis(),
            clauseId = clause.clauseId,
            loraVersion = updatedManifest.activeLoraVersion,
            rawText = clause.rawText,
            normalizedPhonemes = clause.normalizedPhonemes,
            userAction = PreferenceUserAction.VIDEO_ANCHOR_SNAP,
            userGapScale = updatedManifest.globalGapScale.coerceIn(MIN_GLOBAL_GAP_SCALE, MAX_GLOBAL_GAP_SCALE),
            chosenTokens = selected.discreteAudioTokens.toList(),
            rejectedTokens = emptyList()
        )

        applyClipperManifestMutation(updatedManifest, telemetry)
    }

    fun onClipperExport() {
        val manifest = _uiState.value.clipperState.manifest
        if (manifest == null || manifest.clauses.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "No synthesized clauses to export.") }
            return
        }
        val anchorsUs = Media3BeatExporter.computeAnchorTimestampsUs(manifest)
        val totalDurationUs = Media3BeatExporter.computeTotalTimelineDurationUs(manifest)
        val firstClause = manifest.clauses.first()

        val telemetry = PreferenceTelemetryRecord(
            timestamp = System.currentTimeMillis(),
            clauseId = firstClause.clauseId,
            loraVersion = manifest.activeLoraVersion,
            rawText = firstClause.rawText,
            normalizedPhonemes = firstClause.normalizedPhonemes,
            userAction = PreferenceUserAction.PROJECT_EXPORT,
            userGapScale = manifest.globalGapScale.coerceIn(MIN_GLOBAL_GAP_SCALE, MAX_GLOBAL_GAP_SCALE),
            chosenTokens = firstClause.selectedCandidate?.discreteAudioTokens?.toList().orEmpty(),
            rejectedTokens = emptyList()
        )

        viewModelScope.launch {
            localRepo.appendPreference(telemetry)
            val durationSec = totalDurationUs / 1_000_000.0
            _uiState.update {
                it.copy(
                    statusNotice = "Beat timeline locked: ${anchorsUs.size} cuts · ${"%.2f".format(durationSec)}s."
                )
            }
        }
    }

    private fun applyClipperManifestMutation(
        updatedManifest: VoiceProjectManifest,
        telemetry: PreferenceTelemetryRecord?
    ) {
        _uiState.update { cur ->
            cur.copy(clipperState = cur.clipperState.copy(manifest = updatedManifest))
        }
        viewModelScope.launch {
            clipperSaveMutex.withLock {
                withContext(Dispatchers.IO) {
                    localRepo.saveProjectManifest(updatedManifest)
                    if (telemetry != null) {
                        localRepo.appendPreference(telemetry)
                    }
                    if (edlPlayerInitialized) {
                        runCatching { edlController.updateFromManifest(updatedManifest) }
                    }
                    val peaks = PcmWaveformExtractor.extractMinMaxPeaks(updatedManifest)
                    _uiState.update { cur ->
                        if (cur.clipperState.manifest?.projectId == updatedManifest.projectId) {
                            cur.copy(clipperState = cur.clipperState.copy(waveformMinMaxPeaks = peaks))
                        } else {
                            cur
                        }
                    }
                }
            }
        }
    }

    private suspend fun ensureEdlPlayerInitializedIfPossible(): String? {
        if (edlPlayerInitialized) return null
        val roomTone = otaBundleSyncManager.resolveArtifactFile(OtaArtifactRole.ROOM_TONE_PCM)
            ?: return "Missing room_tone_44k.pcm in verified OTA bundle. Sync OTA bundle in System Deck."
        val res = edlController.initPlayer(roomTone.absolutePath)
        return if (res.isSuccess) {
            edlPlayerInitialized = true
            null
        } else {
            res.exceptionOrNull()?.message ?: "Failed initializing Oboe EDL stream."
        }
    }

    private fun formatOtaStatusSummary(state: OtaBundleState): String = when (state.status) {
        OtaBundleStatus.NOT_INSTALLED -> state.errorMessage
            ?: "Not installed. Tap Sync OTA Bundle in System Deck to pull models from your Mac Rig."
        OtaBundleStatus.SYNCING -> {
            val verifiedMb = state.verifiedBytes / (1024.0 * 1024.0)
            val totalMb = state.totalBytes / (1024.0 * 1024.0)
            "Syncing ${"%.1f".format(verifiedMb)} / ${"%.1f".format(totalMb)} MB"
        }
        OtaBundleStatus.READY -> {
            val ver = state.activeManifest?.bundleVersion ?: "verified"
            "Ready ($ver · LoRA v${state.activeLoraVersion})"
        }
        OtaBundleStatus.CORRUPT_OR_INCOMPATIBLE -> state.errorMessage ?: "Corrupt or incompatible OTA bundle."
        OtaBundleStatus.SYNC_FAILED -> state.errorMessage ?: "OTA sync failed."
    }

    override fun onCleared() {
        super.onCleared()
        voiceManifestObserverJob?.cancel()
        rigHealthCheckJob?.cancel()
        macRigPollingJobs.values.forEach { it.cancel() }
        macRigPollingJobs.clear()
        audioEngine.stopPlayback()
        edlController.pause()
    }

    companion object {
        const val MAC_RIG_POLL_INTERVAL_MS: Long = 2_500L
    }
}
