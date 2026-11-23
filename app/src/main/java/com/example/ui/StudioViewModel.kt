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
import com.example.data.gateway.RemoteRenderSummary
import com.example.data.local.CasMediaStore
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.data.local.StudioSettings
import com.example.engine.AircheckPlayerController
import com.example.engine.AudioEngine
import com.voicestudio.edl.OtaBundleState
import com.voicestudio.sync.OtaBundleSyncManager
import com.voicestudio.sync.ProcessMemoryReader
import com.voicestudio.sync.VoiceStudioSyncCoordinator
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
    val activeAiFieldKey: String? = null,
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
    val unsyncedPreferenceCount: Int = 0,
    val measuredVmRssMb: Int? = null
) {
    val defaultAvatarUrl: String
        get() = "${rigHealth.resolvedUrl.trimEnd('/')}/v1/default-avatar"
}

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
    private val voiceStudioCoordinator: VoiceStudioSyncCoordinator = container.voiceStudioCoordinator

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var activeAiJob: Job? = null
    private var rigHealthCheckJob: Job? = null
    private val macRigPollingJobs = mutableMapOf<String, Job>()
    private val scriptSaveMutex = Mutex()
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
                        val initial = ScriptProject(voiceSource = VoiceSource.ENROLLED_VOICE_CLONE)
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
                _uiState.update { it.copy(otaBundleState = otaState) }
            }
        }

        viewModelScope.launch {
            localRepo.unsyncedPreferenceCount.collect { count ->
                _uiState.update { it.copy(unsyncedPreferenceCount = count) }
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

    // --- Story & Segment Editing ---

    fun createNewProject() {
        activeAiJob?.cancel()
        val settings = _uiState.value.settings
        val fresh = ScriptProject(
            voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
            stageConfig = NewsroomStageConfig(
                photoSha = settings.persona.defaultPhotoSha.ifBlank { null },
                showBrandBug = true
            )
        )
        persistProject(fresh)
        _uiState.update {
            it.copy(
                activeAiFieldKey = null,
                waveformPeaks = emptyList(),
                exportState = ExportState.IDLE,
                statusNotice = "Started a new broadcast."
            )
        }
    }

    fun updateStoryIdea(idea: String) {
        val cur = _uiState.value.activeProject
        persistProject(cur.copy(ideaSpark = idea, title = if (cur.title.isBlank()) idea.take(48) else cur.title))
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

    fun updateStageConfig(transform: (NewsroomStageConfig) -> NewsroomStageConfig) {
        val current = _uiState.value.activeProject
        val updatedStage = transform(current.stageConfig)
        persistProject(
            current.copy(
                title = updatedStage.chyronHeadline.ifBlank { current.title },
                stageConfig = updatedStage
            )
        )
    }

    // --- Real AI Writing Assist (Backed by Live Mac Rig `/v1/ai-assist`, Zero Firebase) ---

    fun aiFleshOutStory() {
        val key = "story_idea"
        if (_uiState.value.activeAiFieldKey == key) {
            activeAiJob?.cancel()
            _uiState.update { it.copy(activeAiFieldKey = null) }
            return
        }
        val current = _uiState.value.activeProject
        val seed = current.ideaSpark.ifBlank {
            current.activeBeats.joinToString(" ") { it.spokenText }.trim()
        }.ifBlank { current.stageConfig.chyronHeadline }.trim()

        if (seed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Type a quick news idea first, then tap ✨.") }
            return
        }

        activeAiJob?.cancel()
        _uiState.update { it.copy(activeAiFieldKey = key, errorMessage = null) }
        activeAiJob = viewModelScope.launch {
            try {
                val resolvedUrl = resolveRigUrlOrThrow()
                val draft = macRigGateway.aiFleshOutStory(
                    rigBaseUrl = resolvedUrl,
                    idea = seed,
                    targetSegments = 2
                )
                val latest = _uiState.value.activeProject
                val newBeats = draft.segments.mapIndexed { idx, line ->
                    ScriptBeat(
                        index = idx,
                        spokenText = line,
                        durationSec = estimateDurationSeconds(line, 5)
                    )
                }
                persistProject(
                    latest.copy(
                        title = draft.headline,
                        beats = newBeats,
                        voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
                        stageConfig = latest.stageConfig.copy(
                            chyronHeadline = draft.headline,
                            chyronSubline = draft.subline
                        )
                    )
                )
                _uiState.update {
                    it.copy(
                        activeAiFieldKey = null,
                        statusNotice = "AI drafted headline & ${newBeats.size} segments."
                    )
                }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(activeAiFieldKey = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        activeAiFieldKey = null,
                        errorMessage = e.localizedMessage ?: "AI assist failed."
                    )
                }
            }
        }
    }

    fun aiPolishSegment(beatId: String) {
        val key = "segment_$beatId"
        if (_uiState.value.activeAiFieldKey == key) {
            activeAiJob?.cancel()
            _uiState.update { it.copy(activeAiFieldKey = null) }
            return
        }
        val current = _uiState.value.activeProject
        val beat = current.beats.firstOrNull { it.id == beatId } ?: return
        val storyContext = buildStoryContext(current)
        if (beat.spokenText.isBlank() && storyContext.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Type an idea or rough line first, then tap ✨.") }
            return
        }

        activeAiJob?.cancel()
        _uiState.update { it.copy(activeAiFieldKey = key, errorMessage = null) }
        activeAiJob = viewModelScope.launch {
            try {
                val resolvedUrl = resolveRigUrlOrThrow()
                val polished = macRigGateway.aiPolishField(
                    rigBaseUrl = resolvedUrl,
                    mode = "polish_segment",
                    text = beat.spokenText,
                    context = storyContext
                )
                val latest = _uiState.value.activeProject
                persistProject(
                    latest.copy(
                        beats = latest.beats.map { b ->
                            if (b.id == beatId) {
                                b.copy(
                                    spokenText = polished,
                                    durationSec = estimateDurationSeconds(polished, b.durationSec)
                                )
                            } else b
                        }
                    )
                )
                _uiState.update { it.copy(activeAiFieldKey = null) }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(activeAiFieldKey = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        activeAiFieldKey = null,
                        errorMessage = e.localizedMessage ?: "AI assist failed."
                    )
                }
            }
        }
    }

    fun aiPolishHeadline() {
        val key = "chyron_headline"
        if (_uiState.value.activeAiFieldKey == key) {
            activeAiJob?.cancel()
            _uiState.update { it.copy(activeAiFieldKey = null) }
            return
        }
        val current = _uiState.value.activeProject
        val draftText = current.stageConfig.chyronHeadline
        val storyContext = buildStoryContext(current)
        if (draftText.isBlank() && storyContext.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Type a headline or story idea first, then tap ✨.") }
            return
        }

        activeAiJob?.cancel()
        _uiState.update { it.copy(activeAiFieldKey = key, errorMessage = null) }
        activeAiJob = viewModelScope.launch {
            try {
                val resolvedUrl = resolveRigUrlOrThrow()
                val polished = macRigGateway.aiPolishField(
                    rigBaseUrl = resolvedUrl,
                    mode = "polish_headline",
                    text = draftText,
                    context = storyContext
                )
                updateStageConfig { it.copy(chyronHeadline = polished) }
                _uiState.update { it.copy(activeAiFieldKey = null) }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(activeAiFieldKey = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        activeAiFieldKey = null,
                        errorMessage = e.localizedMessage ?: "AI assist failed."
                    )
                }
            }
        }
    }

    fun aiPolishSubline() {
        val key = "chyron_subline"
        if (_uiState.value.activeAiFieldKey == key) {
            activeAiJob?.cancel()
            _uiState.update { it.copy(activeAiFieldKey = null) }
            return
        }
        val current = _uiState.value.activeProject
        val draftText = current.stageConfig.chyronSubline
        val storyContext = buildStoryContext(current)
        if (draftText.isBlank() && storyContext.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Type a subline or story idea first, then tap ✨.") }
            return
        }

        activeAiJob?.cancel()
        _uiState.update { it.copy(activeAiFieldKey = key, errorMessage = null) }
        activeAiJob = viewModelScope.launch {
            try {
                val resolvedUrl = resolveRigUrlOrThrow()
                val polished = macRigGateway.aiPolishField(
                    rigBaseUrl = resolvedUrl,
                    mode = "polish_subline",
                    text = draftText,
                    context = storyContext
                )
                updateStageConfig { it.copy(chyronSubline = polished) }
                _uiState.update { it.copy(activeAiFieldKey = null) }
            } catch (_: CancellationException) {
                _uiState.update { it.copy(activeAiFieldKey = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        activeAiFieldKey = null,
                        errorMessage = e.localizedMessage ?: "AI assist failed."
                    )
                }
            }
        }
    }

    private fun buildStoryContext(project: ScriptProject): String = buildList {
        if (project.ideaSpark.isNotBlank()) add("Idea: ${project.ideaSpark.trim()}")
        if (project.stageConfig.chyronHeadline.isNotBlank()) add("Headline: ${project.stageConfig.chyronHeadline.trim()}")
        val spoken = project.activeBeats.joinToString(" ") { it.spokenText.trim() }.trim()
        if (spoken.isNotBlank()) add("Script: $spoken")
    }.joinToString("\n")

    // --- Presenter Photo (Local CAS, Default Avatar Served Live from Mac Rig) ---

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
                    poseGate = PoseGateResult(checked = true, valid = true, note = "Anchor photo updated.")
                )
                persistProject(current.copy(stageConfig = baseStage))
                _uiState.update { it.copy(statusNotice = "Anchor photo updated.") }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = e.localizedMessage ?: "Could not load photo.")
                }
            }
        }
    }

    // --- Local Mic Recording & WAV Preview (Single Optional Mic Per Segment) ---

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
                            statusNotice = "Recorded ${"%.1f".format(durSec)}s voice take."
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

    fun clearRecordedMicTake() {
        audioEngine.stopPlayback()
        val current = _uiState.value.activeProject
        persistProject(
            current.copy(
                masterAudioSha = null,
                voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
                beats = current.beats.map { it.copy(beatAudioSha = null) }
            )
        )
        _uiState.update { it.copy(waveformPeaks = emptyList(), statusNotice = "Switched back to AI voice.") }
    }

    fun togglePreviewMasterWav() {
        if (_uiState.value.isPlayingAudio) {
            audioEngine.stopPlayback()
            return
        }
        val wavFile = casStore.fileForSha(_uiState.value.activeProject.masterAudioSha, "wav")
        if (wavFile == null) {
            _uiState.update { it.copy(errorMessage = "Record a voice take first to preview.") }
            return
        }
        audioEngine.playWavFile(viewModelScope, wavFile)
    }

    // --- Direct Video Generation & Live Gallery Sync ---

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

    fun renderActiveProject(pollIntervalMs: Long = MAC_RIG_POLL_INTERVAL_MS) {
        val current = _uiState.value.activeProject
        val settings = _uiState.value.settings
        val photoSha = current.stageConfig.activePhotoSha ?: settings.persona.defaultPhotoSha.ifBlank { null }

        val hasRecordedWav = casStore.fileForSha(current.masterAudioSha, "wav") != null
        val effectiveProject = when {
            hasRecordedWav && current.voiceSource == VoiceSource.MIC_VOICEOVER -> current
            current.activeBeats.isNotEmpty() -> current.copy(voiceSource = VoiceSource.ENROLLED_VOICE_CLONE)
            current.ideaSpark.isNotBlank() -> current.copy(
                voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
                beats = listOf(ScriptBeat(index = 0, spokenText = current.ideaSpark.trim()))
            )
            else -> current
        }

        if (effectiveProject.voiceSource == VoiceSource.ENROLLED_VOICE_CLONE && effectiveProject.activeBeats.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Type a news idea or segment (or record your voice) before generating.")
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
                    waveformPeaks = _uiState.value.waveformPeaks,
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

    // --- Settings & Server Connection ---

    fun setBrandChannel(channel: BrandChannel) {
        viewModelScope.launch { profileStore.setBrandChannel(channel) }
    }

    fun clearCasCache() {
        viewModelScope.launch {
            casStore.clearAllCache()
            _uiState.update { it.copy(statusNotice = "Cache cleared.") }
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
            val result = voiceStudioCoordinator.syncOtaBundleNow(resolvedUrl)
            refreshVmRssTelemetry()
            result.onSuccess { manifest ->
                _uiState.update {
                    it.copy(statusNotice = "Offline models ready (${manifest.bundleVersion}).")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(errorMessage = err.localizedMessage ?: "Model sync failed.")
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
            voiceStudioCoordinator.enqueuePreferenceSyncAndLoraCheck(
                tailscaleBaseUrl = resolvedUrl,
                requireUnmetered = false
            )
            _uiState.update { it.copy(statusNotice = "Synced preferences.") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeAiJob?.cancel()
        rigHealthCheckJob?.cancel()
        macRigPollingJobs.values.forEach { it.cancel() }
        macRigPollingJobs.clear()
        audioEngine.stopPlayback()
    }

    companion object {
        const val MAC_RIG_POLL_INTERVAL_MS: Long = 2_500L
    }
}
