package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AircheckApplication
import com.example.data.BackdropPreset
import com.example.data.BrandChannel
import com.example.data.ClipCardRow
import com.example.data.ClipRecord
import com.example.data.ExportState
import com.example.data.NewsroomStageConfig
import com.example.data.PoseGateResult
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.Stage4Mode
import com.example.data.StageProviderSettings
import com.example.data.VoiceSource
import com.example.data.estimateDurationSeconds
import com.example.data.local.CasMediaStore
import com.example.data.local.StudioSettings
import com.example.data.remote.ActiveModels
import com.example.data.remote.ModelRegistry
import com.example.engine.AircheckPlayerController
import com.example.engine.AudioEngine
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class StudioUiState(
    val projects: List<ScriptProject> = emptyList(),
    val activeProject: ScriptProject = ScriptProject(),
    val clipCards: List<ClipCardRow> = emptyList(),
    val settings: StudioSettings = StudioSettings(),
    val casTotalBytes: Long = 0L,
    val pendingOutboxCount: Int = 0,
    val activeShimmerKey: String? = null,
    val shimmerLabel: String = "",
    val isCheckingPose: Boolean = false,
    val isNormalizingPose: Boolean = false,
    val generatingBRollBeatId: String? = null,
    val isEnqueuingRender: Boolean = false,
    val exportState: ExportState = ExportState.IDLE,
    val pendingShareIntent: Intent? = null,
    val liveNavToClipId: String? = null,
    val waveformPeaks: List<Float> = emptyList(),
    val isPlayingAudio: Boolean = false,
    val playbackProgress: Float = 0f,
    val isRecordingMic: Boolean = false,
    val recordingTargetBeatId: String? = null,
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val userDisplayName: String? = null,
    val isSyncingCloud: Boolean = false,
    val lastSyncMessage: String? = null,
    val errorMessage: String? = null,
    val statusNotice: String? = null,
    val activeModels: ActiveModels = ActiveModels()
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AircheckApplication).container
    private val localRepo = container.localRepo
    private val profileStore = container.profileStore
    val casStore: CasMediaStore = container.casStore
    val playerController: AircheckPlayerController = container.playerController
    private val audioEngine: AudioEngine = container.audioEngine
    private val clipExporter = container.clipExporter
    private val firestoreRepo = container.firestoreRepo
    private val scriptGateway = container.scriptGateway
    private val ingestGateway = container.ingestGateway
    private val poseGateGateway = container.poseGateGateway
    private val renderJobGateway = container.renderJobGateway
    private val personaTrainingGateway = container.personaTrainingGateway

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var activeAiJob: Job? = null
    private var preRunProjectSnapshot: ScriptProject? = null
    private var cloudSyncJob: Job? = null
    private var jobsObserverJob: Job? = null
    private var personaObserverJob: Job? = null
    private var activeRecordingFile: File? = null
    private var initializedProject = false

    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        _uiState.update {
            it.copy(
                isSignedIn = user != null,
                userEmail = user?.email,
                userDisplayName = user?.displayName
            )
        }
        if (user != null) {
            startCloudObservations(user.uid)
        } else {
            cloudSyncJob?.cancel()
            jobsObserverJob?.cancel()
            personaObserverJob?.cancel()
        }
    }

    init {
        runCatching { Firebase.auth.addAuthStateListener(authListener) }

        viewModelScope.launch {
            ModelRegistry.models.collect { models ->
                _uiState.update { it.copy(activeModels = models) }
            }
        }
        viewModelScope.launch {
            runCatching { ModelRegistry.verifyStartupHealth() }
        }

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
            localRepo.pendingOutboxCount.collect { count ->
                _uiState.update { it.copy(pendingOutboxCount = count) }
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
                        val initial = ScriptProject()
                        localRepo.saveScript(initial)
                        _uiState.update { it.copy(projects = listOf(initial), activeProject = initial) }
                    } else {
                        val preferredId = _uiState.value.settings.activeScriptId
                        val chosen = savedProjects.firstOrNull { it.id == preferredId } ?: savedProjects.first()
                        _uiState.update { it.copy(projects = savedProjects, activeProject = chosen) }
                        loadWaveformForProject(chosen)
                    }
                } else {
                    val currentId = _uiState.value.activeProject.id
                    val updatedActive = savedProjects.firstOrNull { it.id == currentId }
                        ?: savedProjects.firstOrNull()
                        ?: _uiState.value.activeProject
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
    }

    fun observeClip(clipId: String): Flow<ClipRecord?> = localRepo.observeClip(clipId)

    private fun startCloudObservations(uid: String) {
        cloudSyncJob?.cancel()
        cloudSyncJob = viewModelScope.launch {
            runCatching {
                firestoreRepo.observeUserScripts().collectLatest { remoteProjects ->
                    if (Firebase.auth.currentUser?.uid != uid) return@collectLatest
                    remoteProjects.forEach { remote ->
                        val local = localRepo.getScriptById(remote.id)
                        if (local == null) {
                            localRepo.saveScript(remote)
                        } else if (remote.updatedAtMillis > local.updatedAtMillis) {
                            val merged = remote.copy(
                                stageConfig = remote.stageConfig.copy(
                                    photoSha = remote.stageConfig.photoSha ?: local.stageConfig.photoSha,
                                    normalizedPhotoSha = remote.stageConfig.normalizedPhotoSha
                                        ?: local.stageConfig.normalizedPhotoSha,
                                    poseGate = local.stageConfig.poseGate
                                ),
                                masterAudioSha = local.masterAudioSha
                            )
                            localRepo.saveScript(merged)
                        }
                    }
                }
            }
        }

        jobsObserverJob?.cancel()
        jobsObserverJob = viewModelScope.launch {
            runCatching {
                renderJobGateway.observeRenderJobs().collectLatest { remoteJobs ->
                    if (Firebase.auth.currentUser?.uid != uid) return@collectLatest
                    remoteJobs.forEach { job ->
                        val local = localRepo.getClipById(job.id)
                        val merged = job.copy(
                            exportedUri = local?.exportedUri,
                            waveformPeaks = job.waveformPeaks.ifEmpty { local?.waveformPeaks.orEmpty() },
                            thumbHash = job.thumbHash ?: local?.thumbHash,
                            alignedWords = job.alignedWords.ifEmpty { local?.alignedWords.orEmpty() }
                        )
                        localRepo.upsertClip(merged)
                    }
                }
            }
        }

        personaObserverJob?.cancel()
        personaObserverJob = viewModelScope.launch {
            runCatching {
                personaTrainingGateway.observePersonaProfile().collectLatest { remotePersona ->
                    if (Firebase.auth.currentUser?.uid != uid || remotePersona == null) return@collectLatest
                    profileStore.updatePersona(
                        headModelId = remotePersona.headModelId.ifBlank { null },
                        voiceId = remotePersona.voiceId.ifBlank { null },
                        defaultPhotoSha = remotePersona.defaultPhotoSha.ifBlank { null },
                        trainingJobId = remotePersona.trainingJobId,
                        trainingStatus = remotePersona.trainingStatus,
                        trainingError = remotePersona.trainingError
                    )
                }
            }
        }
    }

    private fun persistProject(updated: ScriptProject, pushCloud: Boolean = false) {
        val currentUid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
        val stamped = updated.copy(
            userId = currentUid ?: updated.userId,
            updatedAtMillis = System.currentTimeMillis()
        )
        _uiState.update { state ->
            val updatedList = if (state.projects.any { it.id == stamped.id }) {
                state.projects.map { if (it.id == stamped.id) stamped else it }
            } else {
                listOf(stamped) + state.projects
            }
            state.copy(activeProject = stamped, projects = updatedList)
        }
        viewModelScope.launch {
            localRepo.saveScript(stamped)
            profileStore.setActiveScriptId(stamped.id)
            if (pushCloud && currentUid != null) {
                val res = firestoreRepo.syncProject(stamped)
                if (res.isFailure) {
                    localRepo.enqueueOutbox("script", stamped.id, "UPSERT", "{}")
                }
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

    fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
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

    // --- Project Lifecycle ---

    fun createNewProject() {
        cancelActiveAiAndRestore(restore = false)
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

    fun switchProject(projectId: String) {
        val target = _uiState.value.projects.firstOrNull { it.id == projectId } ?: return
        cancelActiveAiAndRestore(restore = false)
        _uiState.update { it.copy(activeProject = target, exportState = ExportState.IDLE) }
        viewModelScope.launch { profileStore.setActiveScriptId(projectId) }
        loadWaveformForProject(target)
    }

    // --- Stage 0 / Stage 1: Spark & URL → Script ---

    fun updateTitle(title: String) {
        persistProject(_uiState.value.activeProject.copy(title = title))
    }

    fun updateSpark(spark: String) {
        val trimmed = spark.trim()
        val detectedUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed.substringBefore(" ")
        } else {
            _uiState.value.activeProject.sourceUrl
        }
        persistProject(_uiState.value.activeProject.copy(ideaSpark = spark, sourceUrl = detectedUrl))
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

    fun updateBeatMetadata(
        beatId: String,
        mentionTag: String? = null,
        depthTextTag: String? = null,
        anchorWord: String? = null,
        evidenceSource: String? = null,
        sfxTag: String? = null,
        phoneticOverride: String? = null,
        bRollPrompt: String? = null
    ) {
        val current = _uiState.value.activeProject
        val updatedBeats = current.beats.map { beat ->
            if (beat.id == beatId) {
                beat.copy(
                    mentionTag = mentionTag ?: beat.mentionTag,
                    depthTextTag = depthTextTag ?: beat.depthTextTag,
                    anchorWord = anchorWord ?: beat.anchorWord,
                    evidenceSource = evidenceSource ?: beat.evidenceSource,
                    sfxTag = sfxTag ?: beat.sfxTag,
                    phoneticOverride = phoneticOverride ?: beat.phoneticOverride,
                    bRollPrompt = bRollPrompt ?: beat.bRollPrompt
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

    fun cancelActiveAiAndRestore(restore: Boolean = true) {
        activeAiJob?.cancel()
        activeAiJob = null
        val snapshot = preRunProjectSnapshot
        preRunProjectSnapshot = null
        if (restore && snapshot != null) {
            persistProject(snapshot)
        }
        _uiState.update { it.copy(activeShimmerKey = null, shimmerLabel = "") }
    }

    fun toggleHoneSpark() {
        if (_uiState.value.activeShimmerKey == "spark") {
            cancelActiveAiAndRestore(restore = true)
            return
        }
        cancelActiveAiAndRestore(restore = false)

        val current = _uiState.value.activeProject
        val seed = current.ideaSpark.ifBlank { current.title }.trim()
        if (seed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Paste a URL or type a spark first, then tap ✨.") }
            return
        }

        val isUrlIngest = seed.startsWith("http://") || seed.startsWith("https://")
        preRunProjectSnapshot = current
        _uiState.update {
            it.copy(
                activeShimmerKey = "spark",
                shimmerLabel = if (isUrlIngest) "Ingesting URL…" else "Honing script…",
                errorMessage = null
            )
        }

        activeAiJob = viewModelScope.launch {
            try {
                val result = if (isUrlIngest) {
                    ingestGateway.ingestUrlToScript(
                        sourceUrl = seed.substringBefore(" "),
                        targetSeconds = current.targetDurationSec,
                        providers = _uiState.value.settings.providers
                    )
                } else {
                    scriptGateway.honeSparkToScript(
                        spark = seed,
                        targetSeconds = current.targetDurationSec,
                        onStreamChunk = { streamed ->
                            _uiState.update { state ->
                                val proj = state.activeProject
                                val firstId = proj.beats.firstOrNull()?.id ?: "beat_stream_0"
                                val updatedBeats = if (proj.beats.isEmpty()) {
                                    listOf(ScriptBeat(id = firstId, index = 0, spokenText = streamed))
                                } else {
                                    proj.beats.mapIndexed { idx, b ->
                                        if (idx == 0) b.copy(spokenText = streamed) else b
                                    }
                                }
                                state.copy(activeProject = proj.copy(beats = updatedBeats))
                            }
                        }
                    )
                }
                val finalProject = _uiState.value.activeProject.copy(
                    title = result.title.ifBlank { current.title },
                    beats = result.beats,
                    stageConfig = current.stageConfig.copy(
                        chyronHeadline = current.stageConfig.chyronHeadline.ifBlank { result.chyronHeadline },
                        chyronSubline = current.stageConfig.chyronSubline.ifBlank { result.chyronSubline }
                    )
                )
                preRunProjectSnapshot = null
                persistProject(finalProject, pushCloud = true)
                _uiState.update {
                    it.copy(
                        activeShimmerKey = null,
                        shimmerLabel = "",
                        statusNotice = "Script honed (${result.beats.size} beats)."
                    )
                }
            } catch (_: CancellationException) {
                // Restored by cancelActiveAiAndRestore
            } catch (e: Exception) {
                val fallback = preRunProjectSnapshot
                preRunProjectSnapshot = null
                if (fallback != null) persistProject(fallback)
                _uiState.update {
                    it.copy(
                        activeShimmerKey = null,
                        shimmerLabel = "",
                        errorMessage = e.localizedMessage ?: "Unable to hone script right now."
                    )
                }
            }
        }
    }

    fun toggleExpandBeat(beatId: String) {
        val shimmerKey = "beat_$beatId"
        if (_uiState.value.activeShimmerKey == shimmerKey) {
            cancelActiveAiAndRestore(restore = true)
            return
        }
        cancelActiveAiAndRestore(restore = false)

        val current = _uiState.value.activeProject
        val beat = current.beats.firstOrNull { it.id == beatId } ?: return
        val seed = beat.spokenText.ifBlank { current.ideaSpark.ifBlank { current.title } }.trim()
        if (seed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Write a line in this beat before tapping ✨.") }
            return
        }

        preRunProjectSnapshot = current
        _uiState.update {
            it.copy(activeShimmerKey = shimmerKey, shimmerLabel = "Sharpening beat…", errorMessage = null)
        }

        activeAiJob = viewModelScope.launch {
            try {
                val refined = scriptGateway.streamExpandBeat(
                    projectContext = current.ideaSpark.ifBlank { current.title },
                    beatText = seed,
                    onStreamChunk = { streamed ->
                        _uiState.update { state ->
                            val proj = state.activeProject
                            state.copy(
                                activeProject = proj.copy(
                                    beats = proj.beats.map { b ->
                                        if (b.id == beatId) b.copy(spokenText = streamed) else b
                                    }
                                )
                            )
                        }
                    }
                )
                val finalProject = _uiState.value.activeProject.copy(
                    beats = _uiState.value.activeProject.beats.map { b ->
                        if (b.id == beatId) {
                            b.copy(
                                spokenText = refined,
                                durationSec = estimateDurationSeconds(refined, b.durationSec),
                                proposedShortText = null
                            )
                        } else b
                    }
                )
                preRunProjectSnapshot = null
                persistProject(finalProject, pushCloud = true)
                _uiState.update { it.copy(activeShimmerKey = null, shimmerLabel = "") }
            } catch (_: CancellationException) {
                // Restored
            } catch (e: Exception) {
                val fallback = preRunProjectSnapshot
                preRunProjectSnapshot = null
                if (fallback != null) persistProject(fallback)
                _uiState.update {
                    it.copy(
                        activeShimmerKey = null,
                        shimmerLabel = "",
                        errorMessage = e.localizedMessage ?: "Unable to refine this beat."
                    )
                }
            }
        }
    }

    fun toggleShorten20Percent() {
        if (_uiState.value.activeShimmerKey == "shorten") {
            cancelActiveAiAndRestore(restore = true)
            return
        }
        cancelActiveAiAndRestore(restore = false)

        val current = _uiState.value.activeProject
        if (current.activeBeats.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Write at least one spoken beat before shortening.") }
            return
        }

        preRunProjectSnapshot = current
        _uiState.update {
            it.copy(activeShimmerKey = "shorten", shimmerLabel = "Tightening 20%…", errorMessage = null)
        }

        activeAiJob = viewModelScope.launch {
            try {
                val proposals = scriptGateway.proposeShorten20Percent(current.beats)
                val updated = current.copy(
                    beats = current.beats.map { beat ->
                        val shorter = proposals[beat.id]
                        if (shorter != null && shorter != beat.spokenText) {
                            beat.copy(proposedShortText = shorter)
                        } else beat
                    }
                )
                preRunProjectSnapshot = null
                persistProject(updated)
                _uiState.update {
                    it.copy(
                        activeShimmerKey = null,
                        shimmerLabel = "",
                        statusNotice = "Review shorter versions inline below."
                    )
                }
            } catch (_: CancellationException) {
                // Restored
            } catch (e: Exception) {
                preRunProjectSnapshot = null
                _uiState.update {
                    it.copy(
                        activeShimmerKey = null,
                        shimmerLabel = "",
                        errorMessage = e.localizedMessage ?: "Shorten 20% failed."
                    )
                }
            }
        }
    }

    fun acceptShortProposal(beatId: String) {
        val current = _uiState.value.activeProject
        val updated = current.copy(
            beats = current.beats.map { b ->
                val proposed = b.proposedShortText
                if (b.id == beatId && proposed != null) {
                    b.copy(
                        spokenText = proposed,
                        durationSec = estimateDurationSeconds(proposed, 3),
                        proposedShortText = null
                    )
                } else b
            }
        )
        persistProject(updated, pushCloud = true)
    }

    fun rejectShortProposal(beatId: String) {
        val current = _uiState.value.activeProject
        persistProject(
            current.copy(beats = current.beats.map { if (it.id == beatId) it.copy(proposedShortText = null) else it })
        )
    }

    fun factCheckBeat(beatId: String) {
        val shimmerKey = "beat_$beatId"
        if (_uiState.value.activeShimmerKey == shimmerKey) {
            cancelActiveAiAndRestore(restore = true)
            return
        }
        cancelActiveAiAndRestore(restore = false)

        val current = _uiState.value.activeProject
        val beat = current.beats.firstOrNull { it.id == beatId } ?: return
        if (beat.spokenText.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Write a line first to fact-check it.") }
            return
        }

        preRunProjectSnapshot = current
        _uiState.update {
            it.copy(activeShimmerKey = shimmerKey, shimmerLabel = "Checking facts…", errorMessage = null)
        }

        activeAiJob = viewModelScope.launch {
            try {
                val res = scriptGateway.factCheckBeat(
                    topic = current.ideaSpark.ifBlank { current.title },
                    beatText = beat.spokenText
                )
                val updated = current.copy(
                    beats = current.beats.map { b ->
                        if (b.id == beatId) {
                            val newText = res.tightenedText ?: b.spokenText
                            b.copy(
                                spokenText = newText,
                                durationSec = estimateDurationSeconds(newText, b.durationSec),
                                evidenceSource = res.citationLabel
                            )
                        } else b
                    }
                )
                preRunProjectSnapshot = null
                persistProject(updated, pushCloud = true)
                _uiState.update {
                    it.copy(
                        activeShimmerKey = null,
                        shimmerLabel = "",
                        statusNotice = res.note.ifBlank { "Citation added: ${res.citationLabel}" }
                    )
                }
            } catch (_: CancellationException) {
                // Restored
            } catch (e: Exception) {
                preRunProjectSnapshot = null
                _uiState.update {
                    it.copy(
                        activeShimmerKey = null,
                        shimmerLabel = "",
                        errorMessage = e.localizedMessage ?: "Fact check failed."
                    )
                }
            }
        }
    }

    // --- Photo Stage (1 Phone Photo + Cloud Pose Gate + Stage 4 Contract) ---

    fun importPhoto(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, isCheckingPose = true) }
            try {
                val stored = casStore.importImageFromUri(uri)
                profileStore.updatePersona(defaultPhotoSha = stored.sha256)
                val current = _uiState.value.activeProject
                val baseStage = current.stageConfig.copy(
                    photoSha = stored.sha256,
                    normalizedPhotoSha = null,
                    poseGate = PoseGateResult(checked = false, note = "Photo cached (${stored.sha256.take(8)}).")
                )
                persistProject(current.copy(stageConfig = baseStage))

                if (_uiState.value.isSignedIn) {
                    val pose = runCatching {
                        poseGateGateway.validatePhotoPose(
                            photoSha = stored.sha256,
                            headModelId = _uiState.value.settings.persona.headModelId
                        )
                    }
                    pose.onSuccess { result ->
                        val latest = _uiState.value.activeProject
                        persistProject(
                            latest.copy(stageConfig = latest.stageConfig.copy(poseGate = result))
                        )
                        _uiState.update {
                            it.copy(
                                isCheckingPose = false,
                                statusNotice = if (result.valid) result.note else null,
                                errorMessage = if (!result.valid) result.note else null
                            )
                        }
                    }.onFailure { err ->
                        _uiState.update {
                            it.copy(
                                isCheckingPose = false,
                                errorMessage = err.localizedMessage ?: "Cloud pose gate failed."
                            )
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isCheckingPose = false,
                            statusNotice = "Photo stored in SHA-256 CAS (${stored.sha256.take(8)}). Sign in to run Cloud Pose Gate."
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCheckingPose = false,
                        errorMessage = e.localizedMessage ?: "Could not import photo."
                    )
                }
            }
        }
    }

    fun normalizePhotoPose() {
        val current = _uiState.value.activeProject
        val photoSha = current.stageConfig.photoSha
        if (photoSha.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "Pick a photo first.") }
            return
        }
        val headModelId = _uiState.value.settings.persona.headModelId
        if (headModelId.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "No enrolled head_model_id found. Complete Phase A Persona Training in Account settings.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isNormalizingPose = true, errorMessage = null) }
            try {
                val preset = BackdropPreset.fromId(current.stageConfig.backdropPresetId)
                val prompt = current.stageConfig.customBackdropPrompt.ifBlank { preset.promptHint }
                val normalizedSha = poseGateGateway.normalizePhotoPose(
                    photoSha = photoSha,
                    headModelId = headModelId,
                    backdropPrompt = prompt
                )
                val latest = _uiState.value.activeProject
                persistProject(
                    latest.copy(
                        stageConfig = latest.stageConfig.copy(
                            normalizedPhotoSha = normalizedSha,
                            poseGate = PoseGateResult(
                                checked = true,
                                valid = true,
                                note = "Normalized via $headModelId (${normalizedSha.take(8)})."
                            )
                        )
                    ),
                    pushCloud = true
                )
                _uiState.update {
                    it.copy(isNormalizingPose = false, statusNotice = "Eye-level portrait normalized.")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isNormalizingPose = false,
                        errorMessage = e.localizedMessage ?: "Pose normalization failed."
                    )
                }
            }
        }
    }

    fun updateStageConfig(transform: (NewsroomStageConfig) -> NewsroomStageConfig) {
        val current = _uiState.value.activeProject
        persistProject(current.copy(stageConfig = transform(current.stageConfig)))
    }

    fun setStage4Mode(mode: Stage4Mode) {
        viewModelScope.launch { profileStore.setStage4Mode(mode) }
        updateStageConfig { it.copy(stage4Mode = mode) }
    }

    fun setVoiceSource(source: VoiceSource) {
        viewModelScope.launch { profileStore.setVoiceSource(source) }
        persistProject(_uiState.value.activeProject.copy(voiceSource = source))
    }

    fun generateBeatBRoll(beatId: String) {
        val current = _uiState.value.activeProject
        val beat = current.beats.firstOrNull { it.id == beatId } ?: return
        val prompt = beat.bRollPrompt.ifBlank { beat.spokenText }.trim()
        if (prompt.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Add a beat line or B-roll cue first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(generatingBRollBeatId = beatId, errorMessage = null) }
            try {
                val bmp = scriptGateway.generateBRollPlate(
                    bRollPrompt = prompt,
                    topicContext = current.title.ifBlank { current.ideaSpark }
                )
                val stored = casStore.saveBitmap(bmp)
                val latest = _uiState.value.activeProject
                persistProject(
                    latest.copy(
                        beats = latest.beats.map { if (it.id == beatId) it.copy(bRollSha = stored.sha256) else it }
                    )
                )
                _uiState.update {
                    it.copy(generatingBRollBeatId = null, statusNotice = "B-roll plate cached (${stored.sha256.take(8)}).")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        generatingBRollBeatId = null,
                        errorMessage = e.localizedMessage ?: "B-roll generation failed."
                    )
                }
            }
        }
    }

    // --- Mic Recording & WAV Preview (Explicit User Choice Only) ---

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
                            statusNotice = "Mic WAV cached (${"%.1f".format(durSec)}s · ${stored.sha256.take(8)})."
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
            _uiState.update { it.copy(errorMessage = "No recorded WAV audio cached for this script.") }
            return
        }
        audioEngine.playWavFile(viewModelScope, wavFile)
    }

    // --- Cloud Render Enqueue (Firebase Cloud Functions v2 -> Cloud Tasks DAG) ---

    fun enqueueCloudRender() {
        val current = _uiState.value.activeProject
        val settings = _uiState.value.settings
        val photoSha = current.stageConfig.activePhotoSha ?: settings.persona.defaultPhotoSha.ifBlank { null }

        if (current.activeBeats.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Write at least one spoken beat before rendering.") }
            return
        }
        if (photoSha.isNullOrBlank() && current.stageConfig.stage4Mode == Stage4Mode.PHOTO_HEAD_LORA) {
            _uiState.update { it.copy(errorMessage = "Pick a photo in the Photo tab before rendering.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isEnqueuingRender = true, errorMessage = null) }
            try {
                val enqueued = renderJobGateway.enqueueClipRender(
                    project = current,
                    persona = settings.persona,
                    providers = settings.providers,
                    photoSha = photoSha.orEmpty()
                )
                val now = System.currentTimeMillis()
                val clip = ClipRecord(
                    id = enqueued.clipId,
                    scriptId = current.id,
                    jobId = enqueued.jobId,
                    title = current.title.ifBlank { "Untitled Aircheck" },
                    photoSha = photoSha,
                    voiceSha = current.masterAudioSha,
                    alignSha = null,
                    lipsyncSha = null,
                    finalSha = null,
                    status = RenderJobStatus.QUEUED,
                    progressFraction = 0.05f,
                    stageDetail = "Queued on Cloud Tasks",
                    errorMessage = null,
                    durationMs = current.totalActiveSeconds * 1000L,
                    waveformPeaks = _uiState.value.waveformPeaks,
                    thumbHash = null,
                    alignedWords = emptyList(),
                    chyronHeadline = current.stageConfig.chyronHeadline.ifBlank { current.title },
                    chyronSubline = current.stageConfig.chyronSubline,
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
                        statusNotice = "Render queued (${enqueued.jobId.take(8)})."
                    )
                }
            } catch (e: Exception) {
                // Zero silent fallback: surface exact actionable error
                _uiState.update {
                    it.copy(
                        isEnqueuingRender = false,
                        errorMessage = e.localizedMessage ?: "Unable to enqueue cloud render."
                    )
                }
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

    // --- Persisted Settings, Persona Enrollment & Cloud Sync ---

    fun setBrandChannel(channel: BrandChannel) {
        viewModelScope.launch { profileStore.setBrandChannel(channel) }
    }

    fun savePersonaIds(headModelId: String, voiceId: String) {
        viewModelScope.launch {
            profileStore.updatePersona(headModelId = headModelId, voiceId = voiceId)
            _uiState.update { it.copy(statusNotice = "Persona IDs saved to Proto DataStore.") }
        }
    }

    fun saveProviderSettings(providers: StageProviderSettings) {
        viewModelScope.launch {
            profileStore.updateProviders(providers)
            _uiState.update { it.copy(statusNotice = "Stage providers updated.") }
        }
    }

    fun startPersonaTraining(gcsUrisCsv: String) {
        val uris = gcsUrisCsv.split(",", "\n").map { it.trim() }.filter { it.startsWith("gs://") }
        if (uris.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Provide one or more gs:// reference video URIs to start Phase A training.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            try {
                val jobId = personaTrainingGateway.startPersonaTraining(
                    trainingVideoGcsUris = uris,
                    voiceProviderId = _uiState.value.settings.providers.voiceProviderId
                )
                profileStore.updatePersona(trainingJobId = jobId, trainingStatus = "RUNNING", trainingError = "")
                _uiState.update { it.copy(statusNotice = "Phase A training started ($jobId).") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.localizedMessage ?: "Phase A training trigger failed.") }
            }
        }
    }

    fun clearCasCache() {
        viewModelScope.launch {
            casStore.clearAllCache()
            _uiState.update { it.copy(statusNotice = "Local CAS media cache cleared.") }
        }
    }

    fun triggerManualCloudSync() {
        if (runCatching { Firebase.auth.currentUser }.getOrNull() == null) {
            _uiState.update { it.copy(errorMessage = "Sign in with Google to sync scripts.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingCloud = true, errorMessage = null) }
            val result = firestoreRepo.syncProject(_uiState.value.activeProject)
            result.onSuccess {
                _uiState.update {
                    it.copy(isSyncingCloud = false, lastSyncMessage = "Synced to Aircheck Cloud.")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncingCloud = false,
                        errorMessage = err.localizedMessage ?: "Cloud sync failed."
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { Firebase.auth.removeAuthStateListener(authListener) }
        activeAiJob?.cancel()
        cloudSyncJob?.cancel()
        jobsObserverJob?.cancel()
        personaObserverJob?.cancel()
        audioEngine.stopPlayback()
    }
}
