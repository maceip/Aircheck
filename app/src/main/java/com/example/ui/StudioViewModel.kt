package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.speech.tts.Voice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BackdropPreset
import com.example.data.BrandChannel
import com.example.data.ExportState
import com.example.data.NewsroomStageConfig
import com.example.data.RenderProgress
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.StudioTab
import com.example.data.VoiceSource
import com.example.data.estimateDurationSeconds
import com.example.data.local.AircheckDatabase
import com.example.data.local.LocalProjectRepository
import com.example.data.local.MediaStorageManager
import com.example.data.local.StudioPreferences
import com.example.data.local.StudioSettings
import com.example.data.remote.ActiveModels
import com.example.data.remote.AircheckAiRepository
import com.example.data.remote.FirestoreSyncRepository
import com.example.data.remote.ModelRegistry
import com.example.engine.AudioEngine
import com.example.engine.FaceValidator
import com.example.engine.OnDeviceVideoComposer
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class StudioUiState(
    val selectedTab: StudioTab = StudioTab.SCRIPT,
    val projects: List<ScriptProject> = emptyList(),
    val activeProject: ScriptProject = ScriptProject(),
    val settings: StudioSettings = StudioSettings(),
    val activeShimmerKey: String? = null,
    val shimmerLabel: String = "",
    val isComposingAnchor: Boolean = false,
    val generatingBRollBeatId: String? = null,
    val renderProgress: RenderProgress = RenderProgress(),
    val exportState: ExportState = ExportState.IDLE,
    val pendingShareIntent: Intent? = null,
    val waveformPeaks: List<Float> = emptyList(),
    val isPlayingAudio: Boolean = false,
    val playbackProgress: Float = 0f,
    val isRecordingMic: Boolean = false,
    val recordingTargetBeatId: String? = null,
    val lastMeasuredPitchHz: Float? = null,
    val availableMaleVoices: List<Voice> = emptyList(),
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val userDisplayName: String? = null,
    val isSyncingCloud: Boolean = false,
    val lastSyncMessage: String? = null,
    val showSystemDeck: Boolean = false,
    val showAccountSheet: Boolean = false,
    val errorMessage: String? = null,
    val statusNotice: String? = null,
    val activeModels: ActiveModels = ActiveModels()
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val localRepo = LocalProjectRepository(
        AircheckDatabase.getInstance(application).scriptProjectDao()
    )
    private val preferences = StudioPreferences(application)
    val mediaStorage = MediaStorageManager(application)
    private val aiRepo = AircheckAiRepository()
    private val faceValidator = FaceValidator()
    private val audioEngine = AudioEngine(application)
    private val videoComposer = OnDeviceVideoComposer(application)
    private val firestoreRepo = FirestoreSyncRepository(application)

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var activeAiJob: Job? = null
    private var preRunProjectSnapshot: ScriptProject? = null
    private var cloudSyncJob: Job? = null
    private var activeRecordingFile: File? = null
    private var activeRecordingKey: String? = null
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
            startCloudObservation(user.uid)
        } else {
            cloudSyncJob?.cancel()
            cloudSyncJob = null
        }
    }

    init {
        Firebase.auth.addAuthStateListener(authListener)

        viewModelScope.launch {
            ModelRegistry.models.collect { models ->
                _uiState.update { it.copy(activeModels = models) }
            }
        }
        viewModelScope.launch {
            ModelRegistry.verifyStartupHealth()
        }

        viewModelScope.launch {
            preferences.settingsFlow.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }

        viewModelScope.launch {
            localRepo.projects.collect { savedProjects ->
                if (!initializedProject) {
                    initializedProject = true
                    if (savedProjects.isEmpty()) {
                        val initial = ScriptProject()
                        localRepo.save(initial)
                        _uiState.update { it.copy(projects = listOf(initial), activeProject = initial) }
                    } else {
                        val preferredId = _uiState.value.settings.activeProjectId
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
        viewModelScope.launch {
            val voices = audioEngine.getAdultMaleVoices()
            _uiState.update { it.copy(availableMaleVoices = voices) }
        }
    }

    private fun startCloudObservation(uid: String) {
        cloudSyncJob?.cancel()
        cloudSyncJob = viewModelScope.launch {
            runCatching {
                firestoreRepo.observeUserScripts().collectLatest { remoteProjects ->
                    if (Firebase.auth.currentUser?.uid != uid) return@collectLatest
                    remoteProjects.forEach { remote ->
                        val local = localRepo.getById(remote.id)
                        if (local == null) {
                            localRepo.save(remote)
                        } else if (remote.updatedAtMillis > local.updatedAtMillis) {
                            // Preserve device-local binary asset keys while syncing text/stage metadata
                            val merged = remote.copy(
                                stageConfig = remote.stageConfig.copy(
                                    selfieImageKey = local.stageConfig.selfieImageKey,
                                    compositedAnchorKey = local.stageConfig.compositedAnchorKey,
                                    faceValidated = local.stageConfig.faceValidated,
                                    faceValidationNote = local.stageConfig.faceValidationNote,
                                    mouthCenterX = local.stageConfig.mouthCenterX,
                                    mouthCenterY = local.stageConfig.mouthCenterY,
                                    mouthWidthNorm = local.stageConfig.mouthWidthNorm
                                ),
                                renderedVideoKey = local.renderedVideoKey,
                                masterAudioKey = local.masterAudioKey,
                                exportedUri = local.exportedUri
                            )
                            localRepo.save(merged)
                        }
                    }
                }
            }
        }
    }

    private fun persistProject(updated: ScriptProject, pushCloud: Boolean = false) {
        val stamped = updated.copy(
            userId = Firebase.auth.currentUser?.uid ?: updated.userId,
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
            localRepo.save(stamped)
            preferences.setActiveProjectId(stamped.id)
            if (pushCloud && Firebase.auth.currentUser != null) {
                firestoreRepo.syncProject(stamped)
            }
        }
    }

    private fun loadWaveformForProject(project: ScriptProject) {
        val wavFile = mediaStorage.fileForKey(project.masterAudioKey)
        if (wavFile != null) {
            viewModelScope.launch {
                val peaks = AudioEngine.decodeWavPeaks(wavFile, 72)
                _uiState.update { it.copy(waveformPeaks = peaks) }
            }
        } else {
            _uiState.update { it.copy(waveformPeaks = emptyList()) }
        }
    }

    // --- Navigation & Modals ---

    fun selectTab(tab: StudioTab) {
        _uiState.update { it.copy(selectedTab = tab, errorMessage = null) }
    }

    fun setShowSystemDeck(show: Boolean) {
        _uiState.update { it.copy(showSystemDeck = show) }
    }

    fun setShowAccountSheet(show: Boolean) {
        _uiState.update { it.copy(showAccountSheet = show) }
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

    // --- Project Management ---

    fun createNewProject() {
        cancelActiveAiAndRestore(restore = false)
        val fresh = ScriptProject(
            voiceSource = _uiState.value.settings.voiceSource,
            voiceSelectionId = _uiState.value.settings.voiceSelectionId
        )
        persistProject(fresh)
        _uiState.update {
            it.copy(
                waveformPeaks = emptyList(),
                exportState = ExportState.IDLE,
                selectedTab = StudioTab.SCRIPT
            )
        }
    }

    fun switchProject(projectId: String) {
        val target = _uiState.value.projects.firstOrNull { it.id == projectId } ?: return
        cancelActiveAiAndRestore(restore = false)
        _uiState.update { it.copy(activeProject = target, exportState = ExportState.IDLE) }
        viewModelScope.launch { preferences.setActiveProjectId(projectId) }
        loadWaveformForProject(target)
    }

    // --- Core Script Writing (80% Product Focus) ---

    fun updateTitle(title: String) {
        persistProject(_uiState.value.activeProject.copy(title = title))
    }

    fun updateSpark(spark: String) {
        persistProject(_uiState.value.activeProject.copy(ideaSpark = spark))
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
            } else {
                beat
            }
        }
        persistProject(current.copy(beats = updatedBeats))
    }

    fun updateBeatMetadata(
        beatId: String,
        mentionTag: String? = null,
        depthTextTag: String? = null,
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
                    evidenceSource = evidenceSource ?: beat.evidenceSource,
                    sfxTag = sfxTag ?: beat.sfxTag,
                    phoneticOverride = phoneticOverride ?: beat.phoneticOverride,
                    bRollPrompt = bRollPrompt ?: beat.bRollPrompt
                )
            } else {
                beat
            }
        }
        persistProject(current.copy(beats = updatedBeats))
    }

    fun toggleCutBeat(beatId: String) {
        val current = _uiState.value.activeProject
        val updatedBeats = current.beats.map { beat ->
            if (beat.id == beatId) beat.copy(cut = !beat.cut) else beat
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

    // --- Weightless Inline ✨ AI (Shimmer, Stream, Instant Cancel & Restore) ---

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
            _uiState.update { it.copy(errorMessage = "Type a spark or headline first, then tap ✨.") }
            return
        }

        preRunProjectSnapshot = current
        _uiState.update {
            it.copy(
                activeShimmerKey = "spark",
                shimmerLabel = "Honing script…",
                errorMessage = null
            )
        }

        activeAiJob = viewModelScope.launch {
            try {
                val result = aiRepo.honeSparkToScript(
                    spark = seed,
                    targetSeconds = current.targetDurationSec,
                    onStreamChunk = { streamed ->
                        // Stream live preview directly into the first beat while shimmering
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
            it.copy(
                activeShimmerKey = shimmerKey,
                shimmerLabel = "Sharpening beat…",
                errorMessage = null
            )
        }

        activeAiJob = viewModelScope.launch {
            try {
                val refined = aiRepo.streamExpandBeat(
                    projectContext = current.ideaSpark.ifBlank { current.title },
                    beatText = seed,
                    onStreamChunk = { streamed ->
                        _uiState.update { state ->
                            val proj = state.activeProject
                            val updatedBeats = proj.beats.map { b ->
                                if (b.id == beatId) b.copy(spokenText = streamed) else b
                            }
                            state.copy(activeProject = proj.copy(beats = updatedBeats))
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
                // Handled by cancel
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
            it.copy(
                activeShimmerKey = "shorten",
                shimmerLabel = "Tightening 20%…",
                errorMessage = null
            )
        }

        activeAiJob = viewModelScope.launch {
            try {
                val proposals = aiRepo.proposeShorten20Percent(current.beats)
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
        val updated = current.copy(
            beats = current.beats.map { b ->
                if (b.id == beatId) b.copy(proposedShortText = null) else b
            }
        )
        persistProject(updated)
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
            it.copy(
                activeShimmerKey = shimmerKey,
                shimmerLabel = "Checking facts…",
                errorMessage = null
            )
        }

        activeAiJob = viewModelScope.launch {
            try {
                val res = aiRepo.factCheckBeat(
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

    // --- Stage: Selfie Likeness, Newsroom Compositing, B-Roll, Chyron & Voice ---

    fun importSelfie(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, statusNotice = "Checking frontal portrait…") }
            try {
                val (key, bitmap) = mediaStorage.importImageFromUri(uri, prefix = "selfie")
                val validation = faceValidator.validatePortrait(bitmap)
                val current = _uiState.value.activeProject
                val updatedStage = current.stageConfig.copy(
                    selfieImageKey = key,
                    compositedAnchorKey = null,
                    faceValidated = validation.valid,
                    faceValidationNote = validation.note,
                    mouthCenterX = validation.mouthCenterX,
                    mouthCenterY = validation.mouthCenterY,
                    mouthWidthNorm = validation.mouthWidthNorm
                )
                persistProject(current.copy(stageConfig = updatedStage))
                _uiState.update {
                    it.copy(
                        statusNotice = if (validation.valid) validation.note else null,
                        errorMessage = if (!validation.valid) validation.note else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        statusNotice = null,
                        errorMessage = e.localizedMessage ?: "Could not import portrait."
                    )
                }
            }
        }
    }

    fun updateStageConfig(transform: (NewsroomStageConfig) -> NewsroomStageConfig) {
        val current = _uiState.value.activeProject
        persistProject(current.copy(stageConfig = transform(current.stageConfig)))
    }

    fun composeStudioAnchor() {
        val current = _uiState.value.activeProject
        val selfieKey = current.stageConfig.selfieImageKey
        if (selfieKey.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "Import a frontal selfie portrait first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isComposingAnchor = true, errorMessage = null) }
            try {
                val selfieBmp = mediaStorage.loadBitmap(selfieKey)
                    ?: throw IllegalStateException("Saved selfie file could not be loaded.")
                val preset = BackdropPreset.fromId(current.stageConfig.backdropPresetId)
                val refBmp = preset.bundledDrawable?.let { mediaStorage.loadDrawableBitmap(it, 1024) }
                val prompt = current.stageConfig.customBackdropPrompt.ifBlank { preset.promptHint }

                val composited = aiRepo.composeIdentityLockedAnchor(
                    selfieBitmap = selfieBmp,
                    backdropReference = refBmp,
                    backdropPrompt = prompt
                )
                // Re-detect exact mouth landmarks on the newly composited broadcast portrait
                val landmarks = faceValidator.validatePortrait(composited)
                val anchorKey = mediaStorage.saveBitmap(composited, prefix = "anchor")

                val updatedStage = current.stageConfig.copy(
                    compositedAnchorKey = anchorKey,
                    mouthCenterX = landmarks.mouthCenterX,
                    mouthCenterY = landmarks.mouthCenterY,
                    mouthWidthNorm = landmarks.mouthWidthNorm
                )
                persistProject(current.copy(stageConfig = updatedStage), pushCloud = true)
                _uiState.update {
                    it.copy(
                        isComposingAnchor = false,
                        statusNotice = "Identity-locked anchor frame ready."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isComposingAnchor = false,
                        errorMessage = e.localizedMessage ?: "Studio compositing failed."
                    )
                }
            }
        }
    }

    fun generateBeatBRoll(beatId: String) {
        val current = _uiState.value.activeProject
        val beat = current.beats.firstOrNull { it.id == beatId } ?: return
        val prompt = beat.bRollPrompt.ifBlank { beat.spokenText }.trim()
        if (prompt.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Add a beat line or B-roll description first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(generatingBRollBeatId = beatId, errorMessage = null) }
            try {
                val bmp = aiRepo.generateBRollPlate(
                    bRollPrompt = prompt,
                    topicContext = current.title.ifBlank { current.ideaSpark }
                )
                val key = mediaStorage.saveBitmap(bmp, prefix = "broll")
                val latest = _uiState.value.activeProject
                val updated = latest.copy(
                    beats = latest.beats.map { b ->
                        if (b.id == beatId) b.copy(bRollImageKey = key) else b
                    }
                )
                persistProject(updated)
                _uiState.update {
                    it.copy(
                        generatingBRollBeatId = null,
                        statusNotice = "B-roll cutaway generated."
                    )
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

    // --- Voice, Audio Synthesis, Mic Recording & Real WAV Playback ---

    fun setVoiceSource(source: VoiceSource) {
        viewModelScope.launch { preferences.setVoiceSource(source) }
        persistProject(_uiState.value.activeProject.copy(voiceSource = source))
    }

    fun setVoiceSelectionId(voiceId: String) {
        viewModelScope.launch { preferences.setVoiceSelectionId(voiceId) }
        persistProject(_uiState.value.activeProject.copy(voiceSelectionId = voiceId))
    }

    fun setVoiceCloneCredentials(endpoint: String, apiKey: String) {
        viewModelScope.launch { preferences.setVoiceCloneCredentials(endpoint, apiKey) }
        _uiState.update { it.copy(statusNotice = "Voice clone settings saved.") }
    }

    fun setBrandChannel(channel: BrandChannel) {
        viewModelScope.launch { preferences.setBrandChannel(channel) }
    }

    fun previewMasterOrSynthesize() {
        if (_uiState.value.isPlayingAudio) {
            audioEngine.stopPlayback()
            return
        }
        val current = _uiState.value.activeProject
        val existingWav = mediaStorage.fileForKey(current.masterAudioKey)
        if (existingWav != null) {
            audioEngine.playWavFile(viewModelScope, existingWav)
            return
        }
        synthesizeMasterVoiceTrack(autoPlay = true)
    }

    fun synthesizeMasterVoiceTrack(autoPlay: Boolean = true) {
        val current = _uiState.value.activeProject
        val fullSpoken = current.activeBeats.joinToString(" ") { it.spokenText.trim() }.trim()
        if (fullSpoken.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Write at least one spoken beat before generating audio.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null, statusNotice = "Synthesizing broadcast WAV…") }
            val (key, file) = mediaStorage.createMediaFile("master_vo", "wav")
            val settings = _uiState.value.settings
            val res = audioEngine.synthesizeToWav(
                text = fullSpoken,
                outputFile = file,
                voiceSource = current.voiceSource,
                preferredVoiceId = current.voiceSelectionId.ifBlank { settings.voiceSelectionId },
                voiceCloneEndpoint = settings.voiceCloneEndpoint,
                voiceCloneApiKey = settings.voiceCloneApiKey
            )
            res.onSuccess { audio ->
                persistProject(current.copy(masterAudioKey = key))
                _uiState.update {
                    it.copy(
                        waveformPeaks = audio.peaks,
                        lastMeasuredPitchHz = audio.medianPitchHz,
                        statusNotice = "WAV ready (${"%.1f".format(audio.durationSec)}s · ${audio.medianPitchHz.toInt()} Hz)"
                    )
                }
                if (autoPlay) {
                    audioEngine.playWavFile(viewModelScope, audio.file)
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        statusNotice = null,
                        errorMessage = err.localizedMessage ?: "Audio synthesis failed."
                    )
                }
            }
        }
    }

    fun toggleMicRecording(beatId: String? = null) {
        if (_uiState.value.isRecordingMic) {
            val targetFile = activeRecordingFile
            val targetKey = activeRecordingKey
            val targetBeat = _uiState.value.recordingTargetBeatId
            viewModelScope.launch {
                if (targetFile != null && targetKey != null) {
                    val (durSec, peaks) = audioEngine.stopMicRecording(targetFile)
                    val current = _uiState.value.activeProject
                    val updated = if (targetBeat != null) {
                        current.copy(
                            masterAudioKey = targetKey,
                            voiceSource = VoiceSource.MIC_VOICEOVER,
                            beats = current.beats.map { b ->
                                if (b.id == targetBeat) b.copy(beatAudioKey = targetKey) else b
                            }
                        )
                    } else {
                        current.copy(
                            masterAudioKey = targetKey,
                            voiceSource = VoiceSource.MIC_VOICEOVER
                        )
                    }
                    persistProject(updated)
                    _uiState.update {
                        it.copy(
                            waveformPeaks = peaks,
                            recordingTargetBeatId = null,
                            statusNotice = "Studio mic take saved (${"%.1f".format(durSec)}s)."
                        )
                    }
                }
                activeRecordingFile = null
                activeRecordingKey = null
            }
        } else {
            val (key, file) = mediaStorage.createMediaFile("mic_take", "wav")
            activeRecordingKey = key
            activeRecordingFile = file
            _uiState.update { it.copy(recordingTargetBeatId = beatId, errorMessage = null) }
            audioEngine.startMicRecording(
                scope = viewModelScope,
                outputFile = file,
                onError = { msg ->
                    activeRecordingFile = null
                    activeRecordingKey = null
                    _uiState.update { it.copy(recordingTargetBeatId = null, errorMessage = msg) }
                }
            )
        }
    }

    // --- Real MediaCodec + MediaMuxer MP4 Talking-Head Render & Export ---

    fun renderTalkingHeadMp4() {
        val current = _uiState.value.activeProject
        if (current.activeBeats.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Add at least one spoken beat before rendering.") }
            return
        }

        _uiState.update {
            it.copy(
                selectedTab = StudioTab.REVIEW,
                errorMessage = null,
                exportState = ExportState.IDLE,
                renderProgress = RenderProgress(active = true, fraction = 0.04f, statusLabel = "Preparing voice track…")
            )
        }

        viewModelScope.launch {
            try {
                // 1. Ensure genuine 16-bit RIFF WAV exists
                var workingProject = _uiState.value.activeProject
                var wavFile = mediaStorage.fileForKey(workingProject.masterAudioKey)
                if (wavFile == null || workingProject.voiceSource != VoiceSource.MIC_VOICEOVER) {
                    val fullText = workingProject.activeBeats.joinToString(" ") { it.spokenText.trim() }
                    val (wavKey, newWavFile) = mediaStorage.createMediaFile("master_vo", "wav")
                    val settings = _uiState.value.settings
                    val audioRes = audioEngine.synthesizeToWav(
                        text = fullText,
                        outputFile = newWavFile,
                        voiceSource = workingProject.voiceSource,
                        preferredVoiceId = workingProject.voiceSelectionId.ifBlank { settings.voiceSelectionId },
                        voiceCloneEndpoint = settings.voiceCloneEndpoint,
                        voiceCloneApiKey = settings.voiceCloneApiKey
                    ).getOrThrow()
                    wavFile = audioRes.file
                    workingProject = workingProject.copy(masterAudioKey = wavKey)
                    persistProject(workingProject)
                    _uiState.update {
                        it.copy(
                            waveformPeaks = audioRes.peaks,
                            lastMeasuredPitchHz = audioRes.medianPitchHz
                        )
                    }
                }

                // 2. Load real anchor portrait (composited anchor -> imported selfie -> bundled stage plate)
                val anchorBitmap = mediaStorage.loadBitmap(workingProject.stageConfig.compositedAnchorKey)
                    ?: mediaStorage.loadBitmap(workingProject.stageConfig.selfieImageKey)
                    ?: run {
                        val preset = BackdropPreset.fromId(workingProject.stageConfig.backdropPresetId)
                        val fallbackRes = preset.bundledDrawable ?: com.example.R.drawable.stage_truss_globe_lime
                        mediaStorage.loadDrawableBitmap(fallbackRes, 1280)
                    }
                    ?: throw IllegalStateException("No studio portrait or stage backdrop available to render.")

                // 3. Load any generated beat B-roll plates
                val bRollMap = buildMap {
                    workingProject.beats.forEach { beat ->
                        val bmp = mediaStorage.loadBitmap(beat.bRollImageKey)
                        if (bmp != null) put(beat.id, bmp)
                    }
                }

                val (mp4Key, mp4File) = mediaStorage.createMediaFile("aircheck", "mp4")
                val renderRes = videoComposer.composeTalkingHeadMp4(
                    project = workingProject,
                    anchorBitmap = anchorBitmap,
                    bRollByBeatId = bRollMap,
                    wavAudioFile = wavFile!!,
                    channel = _uiState.value.settings.brandChannel,
                    outputMp4File = mp4File,
                    onProgress = { frac, label ->
                        _uiState.update {
                            it.copy(renderProgress = RenderProgress(active = true, fraction = frac, statusLabel = label))
                        }
                    }
                )

                renderRes.onSuccess {
                    persistProject(workingProject.copy(renderedVideoKey = mp4Key), pushCloud = true)
                    _uiState.update {
                        it.copy(
                            renderProgress = RenderProgress(active = false, fraction = 1f, statusLabel = ""),
                            statusNotice = "Broadcast MP4 ready to review."
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            renderProgress = RenderProgress(active = false),
                            errorMessage = err.localizedMessage ?: "MP4 render failed."
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        renderProgress = RenderProgress(active = false),
                        errorMessage = e.localizedMessage ?: "Render pipeline failed."
                    )
                }
            }
        }
    }

    fun exportRenderedMp4(openShareSheet: Boolean = false) {
        val current = _uiState.value.activeProject
        val mp4File = mediaStorage.fileForKey(current.renderedVideoKey)
        if (mp4File == null) {
            _uiState.update { it.copy(errorMessage = "Render an Aircheck video before exporting.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(exportState = ExportState.EXPORTING, errorMessage = null) }
            val result = videoComposer.exportToGalleryAndBuildShareIntent(
                mp4File = mp4File,
                projectTitle = current.title
            )
            result.onSuccess { exported ->
                persistProject(current.copy(exportedUri = exported.mediaStoreUri.toString()))
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

    fun triggerManualCloudSync() {
        if (Firebase.auth.currentUser == null) {
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
        Firebase.auth.removeAuthStateListener(authListener)
        activeAiJob?.cancel()
        cloudSyncJob?.cancel()
        audioEngine.release()
    }
}
