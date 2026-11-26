package com.example.ui.draft

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AircheckApplication
import com.example.ai.LlmRoutingException
import com.example.ai.LlmStageRouter
import com.example.data.LastVideoSummary
import com.example.data.LlmProviderChoice
import com.example.data.LlmStage
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.draft.ArcBeat
import com.example.draft.BrushNudge
import com.example.draft.CandidateDraft
import com.example.draft.DraftApiClient
import com.example.draft.DraftDocument
import com.example.draft.DraftStateReducer
import com.example.draft.DraftTone
import com.example.draft.DraftToneContext
import com.example.draft.EditSource
import com.example.draft.ExistingBeatShotsContext
import com.example.draft.FollowupDirectionCard
import com.example.draft.LlmMetaDto
import com.example.draft.LockedPhrase
import com.example.draft.ShotSpec
import com.example.draft.ShotType
import com.example.draft.Stage
import com.example.draft.VadAudioCaptureEngine
import com.example.draft.XAttachment
import com.example.engine.CoreStoryPipelineCoordinator
import com.example.graph.GraphMatch
import com.example.graph.XShipIntentHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

@Immutable
data class DraftSessionSummary(
    val id: String,
    val title: String,
    val stage: Stage,
    val isThinking: Boolean,
    val charCount: Int
)

@Immutable
data class DraftDeckUiState(
    val document: DraftDocument = DraftDocument(id = UUID.randomUUID().toString()),
    val isRecordingSeed: Boolean = false,
    val isRecordingSteer: Boolean = false,
    val isRecordingSplice: Boolean = false,
    val recordingElapsedMs: Long = 0L,
    val liveRmsLevel: Float = 0f,
    val rawKeyboardMode: Boolean = false,
    val isBusy: Boolean = false,
    val isExtractingAngles: Boolean = false,
    val busyLabel: String? = null,
    val errorMessage: String? = null,
    val statusNotice: String? = null,
    val toastMessage: String? = null,
    val focusedClauseId: String? = null,
    val focusedGapIndex: Int? = null,
    val activeBrushRange: IntRange? = null,
    val brushAlternatives: List<String> = emptyList(),
    val brushActiveAltIndex: Int = 0,
    val activeBrushNudge: BrushNudge? = null,
    val flashRange: IntRange? = null,
    val densityDeltaPercent: Int = 0,
    val graphMatches: List<GraphMatch> = emptyList(),
    val graphContactCount: Int = 0,
    val isRadarExpanded: Boolean = false,
    val isImportingArchive: Boolean = false,
    val lastSeedRigOnline: Boolean = false,
    val rigBaseUrl: String = DraftApiClient.DEFAULT_RIG_LAN_URL,
    val sessions: List<DraftSessionSummary> = emptyList(),
    val isSessionSwitcherOpen: Boolean = false,
    val lastLlmMeta: LlmMetaDto? = null,
    // Clip Studio v5 SPARK / Follow-up / Shot Previews / Script state
    val anchorsSheetOpen: Boolean = false,
    val continueSheetOpen: Boolean = false,
    val directionsOpen: Boolean = false,
    val followupVideo: LastVideoSummary? = null,
    val followupAngle: String = "answer",
    val followupDirection: String? = null,
    val followupDirections: List<FollowupDirectionCard> = emptyList(),
    val followupBeats: List<ArcBeat> = emptyList(),
    val isDraftingFollowup: Boolean = false,
    val shotPreviews: Map<String, String> = emptyMap(),
    val inFlightShotIds: Set<String> = emptySet(),
    val rerollingBeatIndex: Int = -1,
    val lastSeedAudioPath: String? = null,
    val lastSeedDurationSec: Int = 0,
    val isPlayingSeedAudio: Boolean = false,
    val lengthRowOpen: Boolean = false,
    val scriptHintSeen: Boolean = false
) {
    val isAnyRecording: Boolean
        get() = isRecordingSeed || isRecordingSteer || isRecordingSplice

    val isThinkingActive: Boolean
        get() = isBusy || isExtractingAngles

    val hasAnyBackgroundThinking: Boolean
        get() = sessions.any { it.id != document.id && it.isThinking }

    val tone: DraftTone
        get() = document.tone

    val steerSheetOpen: Boolean
        get() = anchorsSheetOpen

    val followupLoaded: Boolean
        get() = document.isFollowupLoaded

    val followupSourceLabel: String
        get() = document.followupSourceLabel

    val scenes: List<List<ShotType>>
        get() = document.arcBeats.map { beat -> beat.shots.map { it.type } }

    val selectedPhrase: IntRange?
        get() = activeBrushRange

    val selectedAnchorCount: Int
        get() = document.extractedAngles.count { it.selected } +
            document.blindspotAngles.count { it.selected }

    val canUndo: Boolean
        get() = if (document.stage == Stage.SEED && document.candidates.isEmpty()) {
            document.canUndoArc
        } else {
            document.activeCandidate?.canUndoGlobal == true
        }

    val activeCharCount: Int
        get() = when (document.stage) {
            Stage.SEED -> document.seedTranscript.length
            Stage.PICK, Stage.CUT, Stage.SHIP ->
                document.activeCandidate?.charCount ?: document.seedTranscript.length
        }

    val activePlainText: String
        get() = when (document.stage) {
            Stage.SEED -> document.seedTranscript
            Stage.PICK, Stage.CUT, Stage.SHIP ->
                document.activeCandidate?.plainText() ?: document.seedTranscript
        }
}

class DraftDeckViewModel internal constructor(
    application: Application,
    private val llmRouterOverride: LlmStageRouter?,
    private val draftApiClientFactory: ((String) -> DraftApiClient)? = null,
    private val profileStoreOverride: com.example.data.local.AircheckProfileStore? = null,
    private val casStoreOverride: com.example.data.local.CasMediaStore? = null
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, llmRouterOverride = null)

    private val container = (application as AircheckApplication).container
    private val profileStore = profileStoreOverride ?: container.profileStore
    private val casStore = casStoreOverride ?: container.casStore
    private val vadAudioEngine: VadAudioCaptureEngine = container.vadAudioEngine
    private val localGraphDatabase = container.localGraphDatabase
    private val graphRadarEngine = container.graphRadarEngine
    private val xArchiveImporter = container.xArchiveImporter
    private val graphAppSearchIndexer = container.graphAppSearchIndexer
    private val llmStageRouter: LlmStageRouter = llmRouterOverride ?: container.llmStageRouter

    @Volatile
    private var configuredRigUrl: String = DEFAULT_MAC_RIG_LAN_URL

    @Volatile
    private var draftApiClient: DraftApiClient =
        draftApiClientFactory?.invoke(DEFAULT_MAC_RIG_LAN_URL) ?: container.draftApiClient

    private val vadCacheDir: File by lazy {
        File(application.cacheDir, "aircheck_vad").apply { mkdirs() }
    }

    private val _uiState = MutableStateFlow(DraftDeckUiState())
    val uiState: StateFlow<DraftDeckUiState> = _uiState.asStateFlow()

    /** Direct 50 Hz mic RMS flow for [com.example.ui.clipstudio.VoiceGlowDitherOverlay] (zero full-screen recomposition). */
    val liveRmsFlow: StateFlow<Float> get() = vadAudioEngine.liveRmsLevel

    private val transcribeMutex = Mutex()
    private val editGeneration = AtomicLong(0L)

    private var seedBeforeLatch: String = ""
    private var openLatchHadIncremental: Boolean = false

    private var brushBaselineDraft: CandidateDraft? = null
    private var brushBaselineRange: IntRange? = null
    private var lastAppliedBrushRange: IntRange? = null

    private var anglesJob: Job? = null
    private var activePassJob: Job? = null
    private var seamHealJob: Job? = null
    private var flashJob: Job? = null
    private var graphRefreshJob: Job? = null
    private var probeJob: Job? = null
    private var followupJob: Job? = null

    private val shotIdCounter = AtomicLong(0L)
    private val shotPreviewJobs = ConcurrentHashMap<String, Job>()
    private val shotPreviewSemaphore = Semaphore(3)
    private val shotPreviewCasMutex = Mutex()
    private val followupAngleBeatCache = ConcurrentHashMap<String, List<ArcBeat>>()
    private var seedMediaPlayer: MediaPlayer? = null
    private val seedPlayerLock = Any()

    // Concurrent multi-draft async session store: switching or pulling down for a new subject
    // NEVER cancels in-flight LLM passes on prior cards.
    private val sessionsLock = Any()
    private val storedSessions = LinkedHashMap<String, DraftDeckUiState>()
    private val sessionJobs = HashMap<String, HashMap<String, Job>>()

    init {
        synchronized(sessionsLock) {
            val initial = _uiState.value
            storedSessions[initial.document.id] = initial
        }
        syncSessionSummariesIntoUi()
        viewModelScope.launch {
            profileStore.settingsFlow.collect { settings ->
                val url = settings.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
                if (url != configuredRigUrl) {
                    configuredRigUrl = url
                    draftApiClient = draftApiClientFactory?.invoke(url) ?: container.draftApiClientFor(url)
                    _uiState.update { it.copy(rigBaseUrl = url) }
                    probeRigHealth()
                }
            }
        }

        viewModelScope.launch {
            vadAudioEngine.totalElapsedMs.collect { elapsed ->
                _uiState.update { state ->
                    if (state.isAnyRecording || elapsed == 0L) {
                        state.copy(recordingElapsedMs = elapsed)
                    } else {
                        state
                    }
                }
            }
        }

        viewModelScope.launch {
            var lastUiEmitMs = 0L
            vadAudioEngine.liveRmsLevel.collect { rms ->
                val nowMs = System.currentTimeMillis()
                if (rms != 0f && nowMs - lastUiEmitMs < 100L) return@collect
                lastUiEmitMs = nowMs
                _uiState.update { state ->
                    if (state.isAnyRecording || rms == 0f) {
                        state.copy(liveRmsLevel = rms)
                    } else {
                        state
                    }
                }
            }
        }

        refreshGraphMatches()
        probeRigHealth()
    }

    override fun onCleared() {
        super.onCleared()
        releaseSeedMediaPlayer()
        if (_uiState.value.isAnyRecording) {
            vadAudioEngine.release()
        }
        anglesJob?.cancel()
        activePassJob?.cancel()
        seamHealJob?.cancel()
        flashJob?.cancel()
        graphRefreshJob?.cancel()
        probeJob?.cancel()
        followupJob?.cancel()
        shotPreviewJobs.values.forEach { it.cancel() }
        shotPreviewJobs.clear()
    }

    fun probeRigHealth() {
        probeJob?.cancel()
        val targetClient = draftApiClient
        var launchedJob: Job? = null
        launchedJob = viewModelScope.launch {
            val resolved = try {
                targetClient.resolveActiveBaseUrl(forceRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (probeJob === launchedJob && draftApiClient === targetClient) {
                    _uiState.update { it.copy(lastSeedRigOnline = false) }
                }
                return@launch
            }
            if (probeJob === launchedJob && draftApiClient === targetClient) {
                _uiState.update {
                    it.copy(lastSeedRigOnline = true, rigBaseUrl = resolved)
                }
            }
        }
        probeJob = launchedJob
    }

    fun currentDraftPlainText(): String {
        val state = _uiState.value
        val raw = state.document.activeCandidate?.plainText()
            ?.takeIf { it.isNotBlank() }
            ?: state.document.seedTranscript
        return XShipIntentHelper.sanitizeAscii(raw)
    }

    // =========================================================================
    // STAGE 1: 01 SEED
    // =========================================================================

    fun startSeedLatch() {
        if (!_uiState.value.isRecordingSeed) toggleLatchMic()
    }

    fun stopSeedLatch() {
        if (_uiState.value.isRecordingSeed) toggleLatchMic()
    }

    fun toggleLatchMic() {
        val current = _uiState.value
        if (current.isRecordingSteer || current.isRecordingSplice) return

        if (!current.isRecordingSeed) {
            releaseSeedMediaPlayer()
            seedBeforeLatch = current.document.seedTranscript.trim()
            openLatchHadIncremental = false
            _uiState.update {
                it.copy(
                    isRecordingSeed = true,
                    isPlayingSeedAudio = false,
                    recordingElapsedMs = 0L,
                    liveRmsLevel = 0f,
                    rawKeyboardMode = false,
                    errorMessage = null
                )
            }
            vadAudioEngine.startOpenLatch(
                scope = viewModelScope,
                onUtteranceReady = { chunkFile ->
                    transcribeOpenLatchChunk(chunkFile)
                },
                onError = { err ->
                    _uiState.update {
                        it.copy(isRecordingSeed = false, errorMessage = err)
                    }
                }
            )
            if (!vadAudioEngine.isCapturing.value) {
                _uiState.update { it.copy(isRecordingSeed = false) }
            }
        } else {
            _uiState.update { it.copy(isRecordingSeed = false) }
            viewModelScope.launch {
                val finalFile = File(vadCacheDir, "seed_final_${System.currentTimeMillis()}.wav")
                val distilledWav = withContext(Dispatchers.IO) {
                    vadAudioEngine.stopOpenLatch(finalFile)
                }
                transcribeMutex.withLock {
                    if (distilledWav != null) {
                        val playbackFile = persistSeedPlaybackWav(distilledWav, _uiState.value.document.id)
                        val wavDurationMs = runCatching {
                            CoreStoryPipelineCoordinator.parseWavDurationAndPeaks(playbackFile).durationMs
                        }.getOrDefault(0L)
                        _uiState.update {
                            it.copy(
                                lastSeedAudioPath = playbackFile.absolutePath,
                                lastSeedDurationSec = ((wavDurationMs + 999L) / 1000L).toInt().coerceAtLeast(1),
                                isBusy = true,
                                busyLabel = "Transcribing voice seed...",
                                errorMessage = null
                            )
                        }
                        try {
                            val response = draftApiClient.transcribe(playbackFile)
                            val durationSec = (((response.durationMs.takeIf { it > 0L } ?: wavDurationMs) + 999L) / 1000L)
                                .toInt()
                                .coerceAtLeast(1)
                            val cleanChunk = DraftStateReducer.sanitizeAscii(response.transcript).trim()
                            if (cleanChunk.isNotEmpty()) {
                                val combined = joinTranscriptParts(seedBeforeLatch, cleanChunk)
                                _uiState.update { state ->
                                    state.copy(
                                        document = state.document.copy(
                                            seedTranscript = combined,
                                            isFollowupLoaded = false,
                                            followupSourceLabel = ""
                                        ),
                                        lastSeedAudioPath = playbackFile.absolutePath,
                                        lastSeedDurationSec = durationSec,
                                        lastSeedRigOnline = true,
                                        isBusy = false,
                                        busyLabel = null
                                    )
                                }
                                refreshAnglesFromSeedInternal(silentOnError = false)
                            } else {
                                _uiState.update {
                                    it.copy(
                                        lastSeedAudioPath = playbackFile.absolutePath,
                                        lastSeedDurationSec = durationSec,
                                        lastSeedRigOnline = true,
                                        isBusy = false,
                                        busyLabel = null
                                    )
                                }
                                val existingSeed = _uiState.value.document.seedTranscript.trim()
                                if (existingSeed.isNotEmpty() && _uiState.value.document.extractedAngles.isEmpty()) {
                                    refreshAnglesFromSeedInternal(silentOnError = true)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            handleApiFailure("Transcription failed", e)
                        } finally {
                            if (distilledWav.absolutePath != playbackFile.absolutePath) {
                                runCatching { distilledWav.delete() }
                            }
                        }
                    } else if (openLatchHadIncremental) {
                        val currentSeed = _uiState.value.document.seedTranscript.trim()
                        if (currentSeed.isNotBlank() && _uiState.value.document.extractedAngles.isEmpty()) {
                            refreshAnglesFromSeedInternal(silentOnError = true)
                        }
                    } else {
                        _uiState.update {
                            it.copy(statusNotice = "No speech detected in mic take.")
                        }
                    }
                }
            }
        }
    }

    private suspend fun transcribeOpenLatchChunk(chunkFile: File) {
        transcribeMutex.withLock {
            try {
                val response = draftApiClient.transcribe(chunkFile)
                val clean = DraftStateReducer.sanitizeAscii(response.transcript).trim()
                if (clean.isNotEmpty()) {
                    openLatchHadIncremental = true
                    var updatedSeed = ""
                    _uiState.update { state ->
                        updatedSeed = joinTranscriptParts(state.document.seedTranscript, clean)
                        state.copy(
                            document = state.document.copy(seedTranscript = updatedSeed),
                            lastSeedRigOnline = true,
                            errorMessage = null
                        )
                    }
                    if (updatedSeed.split(Regex("\\s+")).size >= 5) {
                        refreshAnglesFromSeedInternal(silentOnError = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        lastSeedRigOnline = if (isRigTransportFailure(e)) false else it.lastSeedRigOnline,
                        errorMessage = e.localizedMessage ?: "Live chunk transcription failed."
                    )
                }
            } finally {
                runCatching { chunkFile.delete() }
            }
        }
    }

    fun distillLast15Seconds() {
        viewModelScope.launch {
            val trailingFile = File(vadCacheDir, "seed_distill_15s_${System.currentTimeMillis()}.wav")
            val extractedWav = withContext(Dispatchers.IO) {
                vadAudioEngine.extractTrailingWindowWav(
                    outputFile = trailingFile,
                    windowMs = VadAudioCaptureEngine.DEFAULT_TRAILING_WINDOW_MS
                )
            }

            if (_uiState.value.isRecordingSeed) {
                _uiState.update { it.copy(isRecordingSeed = false) }
                val discardFile = File(vadCacheDir, "seed_discard_${System.currentTimeMillis()}.wav")
                withContext(Dispatchers.IO) {
                    vadAudioEngine.stopOpenLatch(discardFile)
                    runCatching { discardFile.delete() }
                }
            }

            if (extractedWav == null) {
                _uiState.update {
                    it.copy(errorMessage = "No voiced speech found in the last 15 seconds.")
                }
                return@launch
            }

            releaseSeedMediaPlayer()
            val playbackFile = persistSeedPlaybackWav(extractedWav, _uiState.value.document.id)
            _uiState.update {
                it.copy(
                    isPlayingSeedAudio = false,
                    isBusy = true,
                    busyLabel = "Distilling last 15s...",
                    errorMessage = null
                )
            }
            try {
                val response = draftApiClient.transcribe(playbackFile)
                val distilledText = DraftStateReducer.sanitizeAscii(response.transcript).trim()
                if (distilledText.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            busyLabel = null,
                            statusNotice = "No speech recognized in the last 15 seconds."
                        )
                    }
                    return@launch
                }
                val durationSecs = (response.durationMs / 1000L).coerceAtLeast(1L)
                _uiState.update { state ->
                    state.copy(
                        document = state.document.copy(
                            seedTranscript = distilledText,
                            isFollowupLoaded = false,
                            followupSourceLabel = ""
                        ),
                        lastSeedAudioPath = playbackFile.absolutePath,
                        lastSeedDurationSec = durationSecs.toInt(),
                        lastSeedRigOnline = true,
                        isBusy = false,
                        busyLabel = null,
                        statusNotice = "Distilled last 15s (${durationSecs}s voiced)."
                    )
                }
                refreshAnglesFromSeedInternal(silentOnError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleApiFailure("Distill last 15s failed", e)
            } finally {
                if (extractedWav.absolutePath != playbackFile.absolutePath) {
                    runCatching { extractedWav.delete() }
                }
            }
        }
    }

    fun startSteerClutch() {
        if (_uiState.value.isAnyRecording) return
        _uiState.update {
            it.copy(
                isRecordingSteer = true,
                recordingElapsedMs = 0L,
                liveRmsLevel = 0f,
                errorMessage = null
            )
        }
        vadAudioEngine.startMomentaryClutch(
            scope = viewModelScope,
            onError = { err ->
                _uiState.update { it.copy(isRecordingSteer = false, errorMessage = err) }
            }
        )
        if (!vadAudioEngine.isCapturing.value) {
            _uiState.update { it.copy(isRecordingSteer = false) }
        }
    }

    fun stopSteerClutch() {
        if (!_uiState.value.isRecordingSteer) return
        _uiState.update { it.copy(isRecordingSteer = false) }
        viewModelScope.launch {
            val steerFile = File(vadCacheDir, "steer_clutch_${System.currentTimeMillis()}.wav")
            val wavFile = withContext(Dispatchers.IO) {
                vadAudioEngine.stopMomentaryClutch(steerFile)
            }
            if (wavFile == null) {
                _uiState.update { it.copy(statusNotice = "No steering speech detected.") }
                return@launch
            }
            _uiState.update {
                it.copy(isBusy = true, busyLabel = "Applying steering...", errorMessage = null)
            }
            try {
                val response = draftApiClient.transcribe(wavFile)
                val constraint = DraftStateReducer.sanitizeAscii(response.transcript).trim()
                if (constraint.isNotEmpty()) {
                    _uiState.update { state ->
                        val existing = state.document.steeringConstraints
                        val updated = if (existing.any { it.equals(constraint, ignoreCase = true) }) {
                            existing
                        } else {
                            existing + constraint
                        }
                        state.copy(
                            document = state.document.copy(steeringConstraints = updated),
                            lastSeedRigOnline = true,
                            isBusy = false,
                            busyLabel = null,
                            statusNotice = "Steer added: $constraint"
                        )
                    }
                    if (_uiState.value.document.seedTranscript.isNotBlank()) {
                        refreshAnglesFromSeedInternal(silentOnError = true)
                    }
                } else {
                    _uiState.update {
                        it.copy(isBusy = false, busyLabel = null, statusNotice = "Empty steering transcript.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleApiFailure("Steering transcription failed", e)
            } finally {
                runCatching { wavFile.delete() }
            }
        }
    }

    fun updateSeedTranscriptManual(text: String) {
        updateSession(_uiState.value.document.id) { state ->
            state.copy(document = state.document.copy(seedTranscript = text))
        }
    }

    fun toggleAngleChip(chipId: String) {
        _uiState.update { state ->
            val doc = state.document
            val updatedExtracted = doc.extractedAngles.map { chip ->
                if (chip.id == chipId) chip.copy(selected = !chip.selected) else chip
            }
            val updatedBlindspots = doc.blindspotAngles.map { chip ->
                if (chip.id == chipId) chip.copy(selected = !chip.selected) else chip
            }
            state.copy(
                document = doc.copy(
                    extractedAngles = updatedExtracted,
                    blindspotAngles = updatedBlindspots
                )
            )
        }
    }

    fun removeSteeringConstraint(index: Int) {
        _uiState.update { state ->
            val list = state.document.steeringConstraints
            if (index !in list.indices) return@update state
            val updated = list.filterIndexed { i, _ -> i != index }
            state.copy(document = state.document.copy(steeringConstraints = updated))
        }
    }

    fun spinUpNewDraftSession() {
        val current = _uiState.value
        val freshDoc = DraftDocument(id = UUID.randomUUID().toString(), stage = Stage.SEED)
        val freshState = DraftDeckUiState(
            document = freshDoc,
            graphMatches = emptyList(),
            graphContactCount = current.graphContactCount,
            lastSeedRigOnline = current.lastSeedRigOnline,
            rigBaseUrl = current.rigBaseUrl,
            statusNotice = if (current.isThinkingActive) {
                "New draft ready · prior card still thinking"
            } else {
                "New draft ready"
            },
            isSessionSwitcherOpen = false
        )
        val summaries = synchronized(sessionsLock) {
            storedSessions[current.document.id] = current.copy(isSessionSwitcherOpen = false)
            storedSessions[freshDoc.id] = freshState
            buildSessionSummariesLocked(freshState)
        }
        clearBrushBaseline()
        _uiState.value = freshState.copy(sessions = summaries)
        refreshGraphMatches()
    }

    fun switchToSession(sessionId: String) {
        val current = _uiState.value
        if (sessionId == current.document.id) {
            _uiState.update { it.copy(isSessionSwitcherOpen = false) }
            return
        }
        var summaries: List<DraftSessionSummary> = emptyList()
        val restored: DraftDeckUiState? = synchronized(sessionsLock) {
            storedSessions[current.document.id] = current.copy(isSessionSwitcherOpen = false)
            val target = storedSessions[sessionId]
            if (target != null) {
                summaries = buildSessionSummariesLocked(target)
            }
            target
        }
        if (restored == null) return
        clearBrushBaseline()
        _uiState.value = restored.copy(
            graphContactCount = current.graphContactCount,
            lastSeedRigOnline = current.lastSeedRigOnline || restored.lastSeedRigOnline,
            rigBaseUrl = current.rigBaseUrl,
            isSessionSwitcherOpen = false,
            sessions = summaries
        )
        refreshGraphMatches()
    }

    fun toggleSessionSwitcher() {
        setSessionSwitcherOpen(!_uiState.value.isSessionSwitcherOpen)
    }

    fun setSessionSwitcherOpen(open: Boolean) {
        val current = _uiState.value
        val updated = current.copy(isSessionSwitcherOpen = open)
        val summaries = synchronized(sessionsLock) {
            storedSessions[current.document.id] = updated
            buildSessionSummariesLocked(updated)
        }
        _uiState.update {
            it.copy(
                isSessionSwitcherOpen = open,
                sessions = summaries
            )
        }
    }

    fun aiRewriteCurrentInput() {
        val snapshot = _uiState.value
        val targetSessionId = snapshot.document.id
        val stage = snapshot.document.stage

        if (stage == Stage.SEED) {
            val cleanSeed = DraftStateReducer.sanitizeAscii(snapshot.document.seedTranscript).trim()
            if (cleanSeed.isEmpty()) {
                updateSession(targetSessionId) {
                    it.copy(errorMessage = "Record or type a rough idea before tapping AI rewrite.")
                }
                return
            }
            launchSessionJob(targetSessionId, "pass") {
                updateSession(targetSessionId) {
                    it.copy(isBusy = true, busyLabel = "Rewriting seed...", errorMessage = null)
                }
                try {
                    val response = llmStageRouter.rewrite(
                        text = cleanSeed,
                        mode = "seed_clean",
                        steeringConstraints = snapshot.document.steeringConstraints
                    )
                    val cleanOut = DraftStateReducer.sanitizeAscii(response.text).trim()
                    if (cleanOut.isBlank()) {
                        throw IllegalStateException("LLM returned an empty rewrite.")
                    }
                    updateSession(targetSessionId) { cur ->
                        cur.copy(
                            document = cur.document.copy(seedTranscript = cleanOut),
                            lastSeedRigOnline = resolveRigOnlineAfterSuccess(cur.lastSeedRigOnline, response.llmMeta),
                            lastLlmMeta = response.llmMeta ?: cur.lastLlmMeta,
                            isBusy = false,
                            busyLabel = null,
                            statusNotice = "Seed sharpened."
                        )
                    }
                    refreshAnglesFromSeedInternal(
                        silentOnError = true,
                        targetSessionId = targetSessionId
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleSessionApiFailure(targetSessionId, "AI rewrite failed", e)
                }
            }
        } else {
            val activeCandidate = snapshot.document.activeCandidate ?: return
            val marked = DraftStateReducer.renderMarkedTextWithLocks(activeCandidate.clauses)
            if (marked.isBlank()) return
            launchSessionJob(targetSessionId, "pass") {
                updateSession(targetSessionId) {
                    it.copy(isBusy = true, busyLabel = "Sharpening draft...", errorMessage = null)
                }
                try {
                    val response = llmStageRouter.rewrite(
                        text = marked,
                        mode = "cut_polish",
                        steeringConstraints = snapshot.document.steeringConstraints
                    )
                    val parity = DraftStateReducer.verifyLockParity(
                        originalClauses = activeCandidate.clauses,
                        mutatedText = response.text,
                        source = EditSource.BRUSH_SWAP
                    )
                    val updated = if (parity.preserved) {
                        DraftStateReducer.applyVerifiedMutation(
                            draft = activeCandidate,
                            mutatedText = response.text,
                            source = EditSource.BRUSH_SWAP
                        )
                    } else {
                        throw IllegalStateException("AI rewrite altered locked words; kept your draft unchanged.")
                    }
                    updateSession(targetSessionId) { cur ->
                        val idx = cur.document.activeCandidateIndex
                        val newCandidates = cur.document.candidates.mapIndexed { i, c ->
                            if (i == idx) updated else c
                        }
                        cur.copy(
                            document = cur.document.copy(candidates = newCandidates),
                            lastSeedRigOnline = resolveRigOnlineAfterSuccess(cur.lastSeedRigOnline, response.llmMeta),
                            lastLlmMeta = response.llmMeta ?: cur.lastLlmMeta,
                            isBusy = false,
                            busyLabel = null,
                            rawKeyboardMode = false,
                            statusNotice = "Draft sharpened."
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleSessionApiFailure(targetSessionId, "AI rewrite failed", e)
                }
            }
        }
    }

    private fun launchSessionJob(
        sessionId: String,
        slotKey: String,
        block: suspend () -> Unit
    ): Job {
        val job = viewModelScope.launch { block() }
        synchronized(sessionsLock) {
            val map = sessionJobs.getOrPut(sessionId) { HashMap() }
            map[slotKey]?.cancel()
            map[slotKey] = job
        }
        return job
    }

    private fun updateSession(
        sessionId: String,
        transform: (DraftDeckUiState) -> DraftDeckUiState
    ) {
        val isActive = _uiState.value.document.id == sessionId
        if (isActive) {
            _uiState.update { cur ->
                val next = transform(cur)
                val summaries = synchronized(sessionsLock) {
                    storedSessions[sessionId] = next
                    buildSessionSummariesLocked(next)
                }
                next.copy(sessions = summaries)
            }
        } else {
            val updatedSummaries = synchronized(sessionsLock) {
                val existing = storedSessions[sessionId] ?: return@synchronized null
                storedSessions[sessionId] = transform(existing)
                buildSessionSummariesLocked(_uiState.value)
            }
            if (updatedSummaries != null) {
                _uiState.update { cur -> cur.copy(sessions = updatedSummaries) }
            }
        }
    }

    private fun handleSessionApiFailure(sessionId: String, prefix: String, error: Exception) {
        val offline = isRigTransportFailure(error)
        val detail = error.message?.trim().takeUnless { it.isNullOrBlank() } ?: "Unknown error"
        updateSession(sessionId) { state ->
            state.copy(
                isBusy = false,
                isExtractingAngles = false,
                busyLabel = null,
                densityDeltaPercent = 0,
                lastSeedRigOnline = if (offline) false else state.lastSeedRigOnline,
                errorMessage = if (offline || com.example.engine.userFacingStageError(error, detail) == com.example.engine.MacStudioOfflineException().message) {
                    com.example.engine.MacStudioOfflineException().message
                } else {
                    "$prefix: $detail"
                }
            )
        }
    }

    private fun syncSessionSummariesIntoUi() {
        val summaries = buildSessionSummariesLocked(_uiState.value)
        _uiState.update { it.copy(sessions = summaries) }
    }

    private fun buildSessionSummariesLocked(
        activeOverride: DraftDeckUiState? = null
    ): List<DraftSessionSummary> =
        synchronized(sessionsLock) {
            val current = activeOverride ?: _uiState.value
            storedSessions[current.document.id] = current
            storedSessions.values.mapIndexed { idx, s ->
                val rawPreview = s.activePlainText.trim()
                val title = if (rawPreview.isNotBlank()) {
                    rawPreview.lineSequence().firstOrNull().orEmpty().take(28)
                } else {
                    "Draft ${idx + 1}"
                }
                DraftSessionSummary(
                    id = s.document.id,
                    title = title,
                    stage = s.document.stage,
                    isThinking = s.isThinkingActive,
                    charCount = s.activeCharCount
                )
            }
        }

    fun refreshAnglesFromSeed() {
        val seed = _uiState.value.document.seedTranscript.trim()
        if (seed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Record or type a seed thought first.") }
            return
        }
        refreshAnglesFromSeedInternal(silentOnError = false)
    }

    private fun refreshAnglesFromSeedInternal(
        silentOnError: Boolean,
        targetSessionId: String = _uiState.value.document.id
    ) {
        val snapshotState = if (targetSessionId == _uiState.value.document.id) {
            _uiState.value
        } else {
            synchronized(sessionsLock) { storedSessions[targetSessionId] } ?: _uiState.value
        }
        val snapshotDoc = snapshotState.document
        val cleanSeed = DraftStateReducer.sanitizeAscii(snapshotDoc.seedTranscript).trim()
        if (cleanSeed.isEmpty()) return

        anglesJob = launchSessionJob(targetSessionId, "angles") {
            updateSession(targetSessionId) {
                it.copy(
                    isExtractingAngles = true,
                    isBusy = if (!silentOnError) true else it.isBusy,
                    busyLabel = if (!silentOnError) "Extracting angles..." else it.busyLabel,
                    errorMessage = if (!silentOnError) null else it.errorMessage
                )
            }
            try {
                val response = withContext(DraftToneContext(snapshotDoc.tone)) {
                    llmStageRouter.extractAngles(
                        seedTranscript = cleanSeed,
                        steeringConstraints = snapshotDoc.steeringConstraints
                    )
                }
                // Row A (Extracted Angles) ON by default; Row B (Blindspots) OFF by default.
                val extracted = response.toExtractedAngleChips(defaultSelected = true)
                val blindspots = response.toBlindspotAngleChips(defaultSelected = false)
                updateSession(targetSessionId) { state ->
                    state.copy(
                        document = state.document.copy(
                            extractedAngles = extracted,
                            blindspotAngles = blindspots
                        ),
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(state.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: state.lastLlmMeta,
                        isExtractingAngles = false,
                        isBusy = if (!silentOnError) false else state.isBusy,
                        busyLabel = if (!silentOnError) null else state.busyLabel
                    )
                }
                generateArcInternal(targetSessionId, rerollIndex = null)
            } catch (e: CancellationException) {
                updateSession(targetSessionId) { state ->
                    state.copy(isExtractingAngles = false)
                }
                throw e
            } catch (e: Exception) {
                if (!silentOnError) {
                    handleSessionApiFailure(targetSessionId, "Angle extraction failed", e)
                } else {
                    updateSession(targetSessionId) {
                        it.copy(
                            isExtractingAngles = false,
                            lastSeedRigOnline = if (isRigTransportFailure(e)) false else it.lastSeedRigOnline
                        )
                    }
                }
            }
        }
    }

    fun synthesizeThreeCandidates() {
        val snapshot = _uiState.value
        val targetSessionId = snapshot.document.id
        val doc = snapshot.document
        val cleanSeed = DraftStateReducer.sanitizeAscii(doc.seedTranscript).trim()
        if (cleanSeed.isEmpty()) {
            updateSession(targetSessionId) {
                it.copy(errorMessage = "Record or type a seed thought before synthesizing.")
            }
            return
        }

        val selectedAngles = collectSelectedAngleSpecs(doc)
        activePassJob = launchSessionJob(targetSessionId, "pass") {
            updateSession(targetSessionId) {
                it.copy(
                    isBusy = true,
                    busyLabel = "Synthesizing 3 candidates...",
                    errorMessage = null,
                    rawKeyboardMode = false
                )
            }
            try {
                val response = withContext(DraftToneContext(doc.tone)) {
                    llmStageRouter.synthesize(
                        seedTranscript = cleanSeed,
                        selectedAngles = selectedAngles,
                        steeringConstraints = doc.steeringConstraints,
                        slot = null,
                        existingDrafts = emptyList()
                    )
                }
                val candidates = response.candidates.take(3).map { dto ->
                    DraftStateReducer.createCandidateDraft(
                        postureLabel = dto.postureLabel,
                        text = dto.text,
                        source = EditSource.MODEL_INIT
                    )
                }
                if (candidates.isEmpty()) {
                    throw IllegalStateException("LLM returned zero draft candidates.")
                }
                if (_uiState.value.document.id == targetSessionId) {
                    clearBrushBaseline()
                    editGeneration.incrementAndGet()
                }
                updateSession(targetSessionId) { state ->
                    state.copy(
                        document = state.document.copy(
                            stage = Stage.PICK,
                            candidates = candidates,
                            activeCandidateIndex = 0
                        ),
                        focusedClauseId = null,
                        focusedGapIndex = null,
                        activeBrushRange = null,
                        brushAlternatives = emptyList(),
                        brushActiveAltIndex = 0,
                        flashRange = null,
                        densityDeltaPercent = 0,
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(state.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: state.lastLlmMeta,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                if (_uiState.value.document.id == targetSessionId) {
                    refreshGraphMatches()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleSessionApiFailure(targetSessionId, "Synthesis failed", e)
            }
        }
    }

    // =========================================================================
    // CLIP STUDIO v4: 60s ARC + VIDEO SCRIPT
    // =========================================================================

    /** Regenerates the proposed 4-beat arc from the current seed, steering, and selected angles. */
    fun generateArc() {
        val doc = _uiState.value.document
        if (doc.seedTranscript.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Record or type a voice note first.") }
            return
        }
        generateArcInternal(doc.id, rerollIndex = null)
    }

    /** Rewrites only beat [index] of the current arc on the rig; every other beat is kept verbatim. */
    fun rerollArcBeat(index: Int) {
        val doc = _uiState.value.document
        if (index !in doc.arcBeats.indices) return
        generateArcInternal(doc.id, rerollIndex = index)
    }

    /** Directly updates the spoken text of beat [index] in place (preserving shots & undo history). */
    fun updateArcBeatText(index: Int, newText: String) {
        val clean = DraftStateReducer.sanitizeAscii(newText).trim()
        if (clean.isEmpty()) return
        val sessionId = _uiState.value.document.id
        var changed = false
        updateSession(sessionId) { state ->
            val doc = state.document
            val current = doc.arcBeats.getOrNull(index) ?: return@updateSession state
            if (current.text.trim() == clean) return@updateSession state
            changed = true
            val updatedBeats = doc.arcBeats.mapIndexed { i, b ->
                if (i == index) b.copy(text = clean) else b
            }
            val nextUndo = (doc.arcUndoStack + listOf(doc.arcBeats)).takeLast(MAX_ARC_UNDO)
            state.copy(
                document = doc.copy(
                    arcBeats = updatedBeats,
                    arcUndoStack = nextUndo
                ),
                errorMessage = null
            )
        }
        if (changed) showToast("beat updated")
    }

    private fun generateArcInternal(targetSessionId: String, rerollIndex: Int?) {
        val snapshot = if (targetSessionId == _uiState.value.document.id) {
            _uiState.value
        } else {
            synchronized(sessionsLock) { storedSessions[targetSessionId] } ?: return
        }
        val doc = snapshot.document
        val cleanSeed = DraftStateReducer.sanitizeAscii(doc.seedTranscript).trim()
        if (cleanSeed.isEmpty()) return
        val existing = if (rerollIndex != null) doc.arcBeats.map { it.text } else null
        val existingShots = doc.arcBeats.map { it.shots }
        launchSessionJob(targetSessionId, if (rerollIndex == null) "arc" else "arc_$rerollIndex") {
            updateSession(targetSessionId) {
                it.copy(
                    isBusy = true,
                    rerollingBeatIndex = rerollIndex ?: -1,
                    busyLabel = if (rerollIndex == null) "Proposing 60s arc..." else "Re-rolling beat...",
                    errorMessage = null
                )
            }
            try {
                val response = withContext(
                    DraftToneContext(doc.tone) + ExistingBeatShotsContext(existingShots)
                ) {
                    llmStageRouter.arc(
                        seedTranscript = cleanSeed,
                        steeringConstraints = doc.steeringConstraints,
                        selectedAngles = collectSelectedAngleSpecs(doc),
                        targetSec = ARC_TARGET_SEC,
                        rerollIndex = rerollIndex,
                        existingBeats = existing
                    )
                }
                val beats = response.beats.mapIndexed { bIdx, dto ->
                    val preservedBeat = if (
                        rerollIndex != null &&
                        bIdx != rerollIndex &&
                        bIdx in doc.arcBeats.indices &&
                        doc.arcBeats[bIdx].shots.isNotEmpty()
                    ) {
                        doc.arcBeats[bIdx]
                    } else {
                        null
                    }
                    preservedBeat ?: dto.toArcBeat(
                        beatIndex = bIdx,
                        shotIdFactory = ::nextShotId
                    )
                }
                if (beats.isEmpty()) throw IllegalStateException("LLM returned an empty arc.")
                updateSession(targetSessionId) { state ->
                    val nextUndoStack = if (rerollIndex != null && state.document.arcBeats.isNotEmpty()) {
                        (state.document.arcUndoStack + listOf(state.document.arcBeats)).takeLast(MAX_ARC_UNDO)
                    } else {
                        state.document.arcUndoStack
                    }
                    state.copy(
                        document = state.document.copy(
                            arcBeats = beats,
                            arcUndoStack = nextUndoStack,
                            arcTargetSec = response.targetSec,
                            arcWordBudget = response.wordBudget,
                            lastRerolledBeat = rerollIndex ?: -1
                        ),
                        rerollingBeatIndex = -1,
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(state.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: state.lastLlmMeta,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                requestShotPreviewsForBeats(
                    sessionId = targetSessionId,
                    beats = beats,
                    headline = beats.firstOrNull()?.text.orEmpty().take(40)
                )
            } catch (e: CancellationException) {
                updateSession(targetSessionId) { it.copy(rerollingBeatIndex = -1) }
                throw e
            } catch (e: Exception) {
                updateSession(targetSessionId) { it.copy(rerollingBeatIndex = -1) }
                handleSessionApiFailure(targetSessionId, "Arc pass failed", e)
            }
        }
    }

    /** `write 60s script ->`: three spoken-video versions (DIRECT / PUNCHY / STORY) built on the arc. */
    fun writeVideoScript() {
        val snapshot = _uiState.value
        val targetSessionId = snapshot.document.id
        val doc = snapshot.document
        val cleanSeed = DraftStateReducer.sanitizeAscii(doc.seedTranscript).trim()
        if (cleanSeed.isEmpty()) {
            updateSession(targetSessionId) {
                it.copy(errorMessage = "Record or type a voice note before writing the script.")
            }
            return
        }
        activePassJob = launchSessionJob(targetSessionId, "pass") {
            updateSession(targetSessionId) {
                it.copy(isBusy = true, busyLabel = "Writing 60s script...", errorMessage = null, rawKeyboardMode = false)
            }
            try {
                val response = withContext(DraftToneContext(doc.tone)) {
                    llmStageRouter.synthesizeScript(
                        seedTranscript = cleanSeed,
                        selectedAngles = collectSelectedAngleSpecs(doc),
                        steeringConstraints = doc.steeringConstraints,
                        arcBeats = doc.arcBeats.map { it.text },
                        targetSec = doc.arcTargetSec.takeIf { it > 0 } ?: ARC_TARGET_SEC
                    )
                }
                val candidates = response.candidates.take(3).map { dto ->
                    DraftStateReducer.createCandidateDraft(
                        postureLabel = dto.postureLabel,
                        text = dto.text,
                        source = EditSource.MODEL_INIT
                    )
                }
                if (candidates.isEmpty()) throw IllegalStateException("LLM returned zero script versions.")
                if (_uiState.value.document.id == targetSessionId) {
                    clearBrushBaseline()
                    editGeneration.incrementAndGet()
                }
                updateSession(targetSessionId) { state ->
                    state.copy(
                        document = state.document.copy(
                            stage = Stage.CUT,
                            candidates = candidates,
                            activeCandidateIndex = 0,
                            arcTargetSec = response.targetSec,
                            arcWordBudget = response.wordBudget,
                            isVideoScript = true
                        ),
                        focusedClauseId = null,
                        focusedGapIndex = null,
                        activeBrushRange = null,
                        brushAlternatives = emptyList(),
                        brushActiveAltIndex = 0,
                        flashRange = null,
                        densityDeltaPercent = 0,
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(state.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: state.lastLlmMeta,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                if (_uiState.value.document.id == targetSessionId) refreshGraphMatches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleSessionApiFailure(targetSessionId, "Script synthesis failed", e)
            }
        }
    }

    /**
     * Re-asks the rig for replacements of the currently brushed phrase with a tone [nudge]
     * (`simpler` / `sharper` / `less formal`) or 3 fresh options ([BrushNudge.MORE]).
     */
    fun performBrushNudge(nudge: BrushNudge) {
        val state = _uiState.value
        val baseline = brushBaselineDraft ?: return
        val baseRange = brushBaselineRange ?: return
        val markedText = DraftStateReducer.renderMarkedTextWithLocks(
            clauses = baseline.clauses,
            replaceRange = baseRange
        )
        if (!markedText.contains("<replace>")) return
        val exclude = if (nudge == BrushNudge.MORE) state.brushAlternatives.filter { it.isNotBlank() } else emptyList()
        val requestGen = editGeneration.get()
        val targetSessionId = state.document.id
        activePassJob = launchSessionJob(targetSessionId, "pass") {
            updateSession(targetSessionId) {
                it.copy(isBusy = true, busyLabel = "Nudging: ${nudge.label}...", errorMessage = null)
            }
            try {
                val response = withContext(DraftToneContext(state.document.tone)) {
                    llmStageRouter.brushSwap(
                        markedText = markedText,
                        steeringConstraints = state.document.steeringConstraints,
                        nudge = nudge,
                        excludeAlternatives = exclude
                    )
                }
                if (response.alternatives.isEmpty()) throw IllegalStateException("LLM returned no nudged alternatives.")
                if (_uiState.value.document.id == targetSessionId && editGeneration.get() != requestGen) {
                    updateSession(targetSessionId) { it.copy(isBusy = false, busyLabel = null) }
                    return@launchSessionJob
                }
                updateSession(targetSessionId) {
                    it.copy(
                        brushAlternatives = response.alternatives,
                        activeBrushNudge = nudge,
                        isBusy = false,
                        busyLabel = null,
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(it.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: it.lastLlmMeta
                    )
                }
                selectBrushAlternative(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleSessionApiFailure(targetSessionId, "Nudge failed", e)
            }
        }
    }

    /** Verbatim-locks (or unlocks) every word inside [charRange] of the active script ("pin phrase"). */
    fun togglePhraseLock(charRange: IntRange) {
        val candidate = _uiState.value.document.activeCandidate ?: return
        if (charRange.isEmpty()) return
        val hits = DraftStateReducer.flattenClauses(candidate.clauses).second
            .filter { it.textStart <= charRange.last && it.textEndExclusive - 1 >= charRange.first }
        if (hits.isEmpty()) return
        val lockAll = hits.any { !it.token.verbatimLocked }
        var updated = candidate
        if (lockAll) {
            hits.filter { !it.token.verbatimLocked }.forEach { ft ->
                updated = DraftStateReducer.toggleWordLock(updated, ft.textStart)
            }
        } else {
            // Unlocking expands across the whole contiguous locked run.
            updated = DraftStateReducer.toggleWordLock(updated, hits.first().textStart)
        }
        if (updated == candidate) return
        // A pinned phrase must never be swapped by a pending brush baseline.
        clearBrushBaseline()
        replaceActiveCandidate(updated, dismissBrush = true)
    }

    /** True when every word overlapping [charRange] in the active script is verbatim-locked. */
    fun isRangeLocked(charRange: IntRange): Boolean {
        val candidate = _uiState.value.document.activeCandidate ?: return false
        val hits = DraftStateReducer.flattenClauses(candidate.clauses).second
            .filter { it.textStart <= charRange.last && it.textEndExclusive - 1 >= charRange.first }
        return hits.isNotEmpty() && hits.all { it.token.verbatimLocked }
    }

    // =========================================================================
    // STAGE 2: 02 PICK
    // =========================================================================

    fun selectCandidateSlot(index: Int) {
        val candidates = _uiState.value.document.candidates
        if (index !in candidates.indices) return
        clearBrushBaseline()
        editGeneration.incrementAndGet()
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(activeCandidateIndex = index),
                focusedClauseId = null,
                focusedGapIndex = null,
                activeBrushRange = null,
                brushAlternatives = emptyList(),
                brushActiveAltIndex = 0,
                flashRange = null,
                densityDeltaPercent = 0,
                rawKeyboardMode = false
            )
        }
        refreshGraphMatches()
    }

    fun salvageClauseFromCandidate(candidateIdx: Int, clauseId: String) {
        val doc = _uiState.value.document
        val candidate = doc.candidates.getOrNull(candidateIdx) ?: return
        val clause = candidate.clauses.firstOrNull { it.id == clauseId } ?: return
        val text = DraftStateReducer.sanitizeAscii(clause.plainText()).trim()
        if (text.isEmpty()) return

        if (doc.salvagedPhrases.any { it.text.equals(text, ignoreCase = true) }) {
            _uiState.update { it.copy(statusNotice = "Phrase already pinned in Salvage Tray.") }
            return
        }

        val locked = LockedPhrase(
            id = "salv_${UUID.randomUUID().toString().take(8)}",
            text = text,
            sourcePosture = candidate.postureLabel
        )
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    salvagedPhrases = state.document.salvagedPhrases + locked
                ),
                statusNotice = "Pinned phrase with Verbatim Lock."
            )
        }
    }

    fun removeSalvagedPhrase(id: String) {
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    salvagedPhrases = state.document.salvagedPhrases.filterNot { it.id == id }
                )
            )
        }
    }

    fun killAndRegenSingleSlot(slotIdx: Int) {
        val doc = _uiState.value.document
        if (slotIdx !in doc.candidates.indices) return
        val cleanSeed = DraftStateReducer.sanitizeAscii(doc.seedTranscript).trim()
        if (cleanSeed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Seed transcript is empty; cannot regenerate slot.") }
            return
        }

        val existingTexts = doc.candidates.map { it.plainText() }.filter { it.isNotBlank() }
        val selectedAngles = collectSelectedAngleSpecs(doc)
        val targetSessionId = doc.id

        activePassJob = launchSessionJob(targetSessionId, "pass") {
            updateSession(targetSessionId) {
                it.copy(
                    isBusy = true,
                    busyLabel = "Refreshing v${slotIdx + 1}...",
                    errorMessage = null
                )
            }
            try {
                val response = withContext(DraftToneContext(doc.tone)) {
                    llmStageRouter.synthesize(
                        seedTranscript = cleanSeed,
                        selectedAngles = selectedAngles,
                        steeringConstraints = doc.steeringConstraints,
                        slot = slotIdx,
                        existingDrafts = existingTexts
                    )
                }
                val replacementDto = response.candidates.firstOrNull()
                    ?: throw IllegalStateException("LLM returned no replacement draft for slot $slotIdx.")
                val replacement = DraftStateReducer.createCandidateDraft(
                    postureLabel = replacementDto.postureLabel,
                    text = replacementDto.text,
                    source = EditSource.MODEL_INIT
                )
                if (_uiState.value.document.id == targetSessionId) {
                    clearBrushBaseline()
                    editGeneration.incrementAndGet()
                }
                updateSession(targetSessionId) { state ->
                    val updatedList = state.document.candidates.mapIndexed { idx, existing ->
                        if (idx == slotIdx) replacement else existing
                    }
                    state.copy(
                        document = state.document.copy(candidates = updatedList),
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(state.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: state.lastLlmMeta,
                        isBusy = false,
                        busyLabel = null,
                        statusNotice = "Regenerated v${slotIdx + 1} (${replacement.postureLabel})."
                    )
                }
                if (_uiState.value.document.id == targetSessionId) {
                    refreshGraphMatches()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleSessionApiFailure(targetSessionId, "Slot regeneration failed", e)
            }
        }
    }

    fun regenAllCandidates() {
        val doc = _uiState.value.document
        val cleanSeed = DraftStateReducer.sanitizeAscii(doc.seedTranscript).trim()
        if (cleanSeed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Seed transcript is empty; cannot regenerate drafts.") }
            return
        }

        val existingTexts = doc.candidates.map { it.plainText() }.filter { it.isNotBlank() }
        val selectedAngles = collectSelectedAngleSpecs(doc)
        val targetSessionId = doc.id

        activePassJob = launchSessionJob(targetSessionId, "pass") {
            updateSession(targetSessionId) {
                it.copy(
                    isBusy = true,
                    busyLabel = "Regenerating all 3 drafts...",
                    errorMessage = null
                )
            }
            try {
                val response = withContext(DraftToneContext(doc.tone)) {
                    llmStageRouter.synthesize(
                        seedTranscript = cleanSeed,
                        selectedAngles = selectedAngles,
                        steeringConstraints = doc.steeringConstraints,
                        slot = null,
                        existingDrafts = existingTexts
                    )
                }
                val candidates = response.candidates.take(3).map { dto ->
                    DraftStateReducer.createCandidateDraft(
                        postureLabel = dto.postureLabel,
                        text = dto.text,
                        source = EditSource.MODEL_INIT
                    )
                }
                if (candidates.isEmpty()) {
                    throw IllegalStateException("LLM returned zero regenerated candidates.")
                }
                if (_uiState.value.document.id == targetSessionId) {
                    clearBrushBaseline()
                    editGeneration.incrementAndGet()
                }
                updateSession(targetSessionId) { state ->
                    state.copy(
                        document = state.document.copy(
                            candidates = candidates,
                            activeCandidateIndex = 0
                        ),
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(state.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: state.lastLlmMeta,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                if (_uiState.value.document.id == targetSessionId) {
                    refreshGraphMatches()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleSessionApiFailure(targetSessionId, "Regenerate all failed", e)
            }
        }
    }

    fun commitCandidateToCut(slotIdx: Int) {
        val candidates = _uiState.value.document.candidates
        if (slotIdx !in candidates.indices) return
        clearBrushBaseline()
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(
                    activeCandidateIndex = slotIdx,
                    stage = Stage.CUT
                ),
                focusedClauseId = null,
                focusedGapIndex = null,
                activeBrushRange = null,
                brushAlternatives = emptyList(),
                brushActiveAltIndex = 0,
                flashRange = null,
                densityDeltaPercent = 0,
                rawKeyboardMode = false
            )
        }
        refreshGraphMatches()
    }

    // =========================================================================
    // STAGE 3: 03 CUT
    // =========================================================================

    fun focusClause(clauseId: String?) {
        _uiState.update {
            it.copy(focusedClauseId = clauseId, focusedGapIndex = null)
        }
    }

    fun focusGap(gapIndex: Int?) {
        _uiState.update {
            it.copy(focusedGapIndex = gapIndex, focusedClauseId = null)
        }
    }

    fun toggleWordLock(charOffset: Int) {
        val candidate = _uiState.value.document.activeCandidate ?: return
        val updated = DraftStateReducer.toggleWordLock(candidate, charOffset)
        if (updated == candidate) return
        clearBrushBaseline()
        replaceActiveCandidate(updated, dismissBrush = true)
    }

    fun performBrushSwap(charRange: IntRange) {
        if (charRange.isEmpty()) return
        val state = _uiState.value
        val activeCandidate = state.document.activeCandidate ?: return

        // Re-brushing the exact active brushed span deterministically cycles to the next alternative.
        val existingLastRange = lastAppliedBrushRange
        if (state.brushAlternatives.size >= 2 &&
            brushBaselineDraft != null &&
            brushBaselineRange != null &&
            existingLastRange != null &&
            rangesOverlap(charRange, existingLastRange)
        ) {
            val nextIdx = (state.brushActiveAltIndex + 1) % state.brushAlternatives.size
            selectBrushAlternative(nextIdx)
            return
        }

        val markedText = DraftStateReducer.renderMarkedTextWithLocks(
            clauses = activeCandidate.clauses,
            replaceRange = charRange
        )
        if (!markedText.contains("<replace>")) return

        val baselineSnapshot = activeCandidate
        val requestGen = editGeneration.get()
        val targetSessionId = state.document.id

        activePassJob = launchSessionJob(targetSessionId, "pass") {
            updateSession(targetSessionId) {
                it.copy(
                    activeBrushRange = charRange,
                    scriptHintSeen = true,
                    isBusy = true,
                    busyLabel = "Swapping phrase...",
                    errorMessage = null
                )
            }
            try {
                val response = withContext(DraftToneContext(state.document.tone)) {
                    llmStageRouter.brushSwap(
                        markedText = markedText,
                        steeringConstraints = state.document.steeringConstraints
                    )
                }
                val alternatives = response.alternatives
                if (alternatives.isEmpty()) {
                    throw IllegalStateException("LLM returned no brush-swap alternatives.")
                }
                if (_uiState.value.document.id == targetSessionId && editGeneration.get() != requestGen) {
                    updateSession(targetSessionId) { it.copy(isBusy = false, busyLabel = null) }
                    return@launchSessionJob
                }

                val firstAlt = alternatives[0]
                val swapped = DraftStateReducer.applyBrushSwap(
                    draft = baselineSnapshot,
                    charRange = charRange,
                    replacementText = firstAlt,
                    source = EditSource.BRUSH_SWAP
                )
                val appliedRange = computeAppliedReplacementRange(
                    baseline = baselineSnapshot,
                    baseRange = charRange,
                    updated = swapped,
                    replacement = firstAlt
                )
                if (_uiState.value.document.id == targetSessionId) {
                    brushBaselineDraft = baselineSnapshot
                    brushBaselineRange = charRange
                    lastAppliedBrushRange = appliedRange
                    editGeneration.incrementAndGet()
                }

                updateSession(targetSessionId) { cur ->
                    val idx = cur.document.activeCandidateIndex
                    val newCandidates = cur.document.candidates.mapIndexed { i, c ->
                        if (i == idx) swapped else c
                    }
                    cur.copy(
                        document = cur.document.copy(candidates = newCandidates),
                        activeBrushRange = appliedRange ?: charRange,
                        brushAlternatives = alternatives,
                        brushActiveAltIndex = 0,
                        flashRange = appliedRange,
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(cur.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: cur.lastLlmMeta,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                if (_uiState.value.document.id == targetSessionId) {
                    scheduleFlashClear(appliedRange)
                    refreshGraphMatches()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleSessionApiFailure(targetSessionId, "Brush swap failed", e)
            }
        }
    }

    fun selectBrushAlternative(altIdx: Int) {
        val state = _uiState.value
        val alternatives = state.brushAlternatives
        if (altIdx !in alternatives.indices) return
        val baseline = brushBaselineDraft ?: return
        val baseRange = brushBaselineRange ?: return

        val replacement = alternatives[altIdx]
        val swapped = DraftStateReducer.applyBrushSwap(
            draft = baseline,
            charRange = baseRange,
            replacementText = replacement,
            source = EditSource.BRUSH_SWAP
        )
        val appliedRange = computeAppliedReplacementRange(
            baseline = baseline,
            baseRange = baseRange,
            updated = swapped,
            replacement = replacement
        )
        lastAppliedBrushRange = appliedRange
        editGeneration.incrementAndGet()

        _uiState.update { cur ->
            val idx = cur.document.activeCandidateIndex
            val newCandidates = cur.document.candidates.mapIndexed { i, c ->
                if (i == idx) swapped else c
            }
            cur.copy(
                document = cur.document.copy(candidates = newCandidates),
                activeBrushRange = appliedRange ?: baseRange,
                brushActiveAltIndex = altIdx,
                flashRange = appliedRange
            )
        }
        scheduleFlashClear(appliedRange)
        refreshGraphMatches()
    }

    fun dismissBrushStrip() {
        clearBrushBaseline()
        _uiState.update {
            it.copy(
                activeBrushRange = null,
                brushAlternatives = emptyList(),
                brushActiveAltIndex = 0
            )
        }
    }

    fun strikeCutClause(clauseId: String) {
        val before = _uiState.value.document.activeCandidate ?: return
        val locallyCut = DraftStateReducer.strikeCutClause(before, clauseId)
        if (locallyCut == before) return

        clearBrushBaseline()
        val gen = replaceActiveCandidate(
            updated = locallyCut,
            dismissBrush = true,
            clearFocusedClauseIfId = clauseId
        )
        refreshGraphMatches()

        if (locallyCut.clauses.isNotEmpty()) {
            runSeamHealPass(
                baseCandidate = locallyCut,
                operation = "strike_cut",
                source = EditSource.STRIKE_CUT,
                expectedGeneration = gen,
                busyLabel = "Healing cut seam..."
            )
        }
    }

    fun reorderClause(fromIdx: Int, toIdx: Int) {
        val before = _uiState.value.document.activeCandidate ?: return
        val locallyReordered = DraftStateReducer.reorderClause(before, fromIdx, toIdx)
        if (locallyReordered == before) return

        clearBrushBaseline()
        val gen = replaceActiveCandidate(locallyReordered, dismissBrush = true)
        refreshGraphMatches()

        runSeamHealPass(
            baseCandidate = locallyReordered,
            operation = "reorder",
            source = EditSource.REORDER,
            expectedGeneration = gen,
            busyLabel = "Healing sentence order..."
        )
    }

    fun startVoiceSplice() {
        if (_uiState.value.isAnyRecording) return
        dismissBrushStrip()
        _uiState.update {
            it.copy(
                isRecordingSplice = true,
                recordingElapsedMs = 0L,
                liveRmsLevel = 0f,
                errorMessage = null
            )
        }
        vadAudioEngine.startMomentaryClutch(
            scope = viewModelScope,
            onError = { err ->
                _uiState.update { it.copy(isRecordingSplice = false, errorMessage = err) }
            }
        )
        if (!vadAudioEngine.isCapturing.value) {
            _uiState.update { it.copy(isRecordingSplice = false) }
        }
    }

    fun stopVoiceSplice() {
        if (!_uiState.value.isRecordingSplice) return
        _uiState.update { it.copy(isRecordingSplice = false) }

        viewModelScope.launch {
            val spliceFile = File(vadCacheDir, "voice_splice_${System.currentTimeMillis()}.wav")
            val wavFile = withContext(Dispatchers.IO) {
                vadAudioEngine.stopMomentaryClutch(spliceFile)
            }
            if (wavFile == null) {
                _uiState.update { it.copy(statusNotice = "No voice splice detected.") }
                return@launch
            }
            _uiState.update {
                it.copy(isBusy = true, busyLabel = "Transcribing voice splice...", errorMessage = null)
            }
            try {
                val response = draftApiClient.transcribe(wavFile)
                val stripped = DraftStateReducer.stripVoiceDirective(response.transcript)
                if (stripped.isBlank()) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            busyLabel = null,
                            statusNotice = "Empty voice splice transcript."
                        )
                    }
                    return@launch
                }

                val currentState = _uiState.value
                val activeCandidate = currentState.document.activeCandidate ?: run {
                    _uiState.update { it.copy(isBusy = false, busyLabel = null) }
                    return@launch
                }

                val spliced = DraftStateReducer.spliceVerbatimPhrase(
                    draft = activeCandidate,
                    targetClauseId = currentState.focusedClauseId,
                    insertGapIndex = currentState.focusedGapIndex,
                    spokenPhrase = stripped,
                    source = EditSource.VOICE_SPLICE
                )
                clearBrushBaseline()
                val gen = replaceActiveCandidate(spliced, dismissBrush = true)
                _uiState.update {
                    it.copy(lastSeedRigOnline = true, isBusy = false, busyLabel = null)
                }
                refreshGraphMatches()

                runSeamHealPass(
                    baseCandidate = spliced,
                    operation = "voice_splice",
                    source = EditSource.VOICE_SPLICE,
                    expectedGeneration = gen,
                    busyLabel = "Healing voice splice..."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleApiFailure("Voice splice failed", e)
            } finally {
                runCatching { wavFile.delete() }
            }
        }
    }

    fun spliceSalvagePhrase(phrase: LockedPhrase) {
        val state = _uiState.value
        val activeCandidate = state.document.activeCandidate ?: return
        val spliced = DraftStateReducer.spliceVerbatimPhrase(
            draft = activeCandidate,
            targetClauseId = state.focusedClauseId,
            insertGapIndex = state.focusedGapIndex,
            spokenPhrase = phrase.text,
            source = EditSource.SALVAGE_SPLICE
        )
        if (spliced == activeCandidate) return

        clearBrushBaseline()
        val gen = replaceActiveCandidate(spliced, dismissBrush = true)
        refreshGraphMatches()

        runSeamHealPass(
            baseCandidate = spliced,
            operation = "voice_splice",
            source = EditSource.SALVAGE_SPLICE,
            expectedGeneration = gen,
            busyLabel = "Healing salvaged phrase..."
        )
    }

    fun stepClauseVersion(clauseId: String, delta: Int) {
        val activeCandidate = _uiState.value.document.activeCandidate ?: return
        val stepped = DraftStateReducer.stepClauseVersion(activeCandidate, clauseId, delta)
        if (stepped == activeCandidate) return
        clearBrushBaseline()
        replaceActiveCandidate(stepped, dismissBrush = true)
        refreshGraphMatches()
    }

    fun stepGlobalSnapshot(delta: Int) {
        val current = _uiState.value
        if (delta < 0 && current.document.stage == Stage.SEED && current.document.candidates.isEmpty() && current.document.canUndoArc) {
            undoArcShotEdit()
            return
        }
        val activeCandidate = current.document.activeCandidate ?: return
        val stepped = DraftStateReducer.stepGlobalSnapshot(activeCandidate, delta)
        if (stepped == activeCandidate) return
        clearBrushBaseline()
        replaceActiveCandidate(stepped, dismissBrush = true)
        refreshGraphMatches()
    }

    fun previewDensityDelta(delta: Int) {
        val clamped = delta.coerceIn(DraftApiClient.MIN_DENSITY_DELTA, DraftApiClient.MAX_DENSITY_DELTA)
        _uiState.update { it.copy(densityDeltaPercent = clamped) }
    }

    fun commitDensityScrub(delta: Int) {
        val clamped = delta.coerceIn(DraftApiClient.MIN_DENSITY_DELTA, DraftApiClient.MAX_DENSITY_DELTA)
        if (clamped == 0) {
            _uiState.update { it.copy(densityDeltaPercent = 0) }
            return
        }
        val state = _uiState.value
        val before = state.document.activeCandidate ?: return
        val markedText = DraftStateReducer.renderMarkedTextWithLocks(before.clauses)
        if (markedText.isBlank()) return

        dismissBrushStrip()
        val requestGen = editGeneration.get()
        val targetSessionId = state.document.id

        activePassJob = launchSessionJob(targetSessionId, "pass") {
            updateSession(targetSessionId) {
                it.copy(
                    densityDeltaPercent = clamped,
                    isBusy = true,
                    busyLabel = if (clamped < 0) "Tightening $clamped%..." else "Expanding +$clamped%...",
                    errorMessage = null
                )
            }
            try {
                val response = llmStageRouter.adjustDensity(
                    markedText = markedText,
                    deltaPercent = clamped,
                    steeringConstraints = state.document.steeringConstraints
                )
                if (_uiState.value.document.id == targetSessionId && editGeneration.get() != requestGen) {
                    updateSession(targetSessionId) { it.copy(densityDeltaPercent = 0, isBusy = false, busyLabel = null) }
                    return@launchSessionJob
                }

                val parity = DraftStateReducer.verifyLockParity(
                    originalClauses = before.clauses,
                    mutatedText = response.text,
                    source = EditSource.DENSITY_SCRUB
                )
                if (!parity.preserved) {
                    updateSession(targetSessionId) {
                        it.copy(
                            densityDeltaPercent = 0,
                            lastSeedRigOnline = resolveRigOnlineAfterSuccess(it.lastSeedRigOnline, response.llmMeta),
                            lastLlmMeta = response.llmMeta ?: it.lastLlmMeta,
                            isBusy = false,
                            busyLabel = null,
                            errorMessage = "Density change rejected: locked words (${parity.missingPhrases.joinToString()}) were altered."
                        )
                    }
                    return@launchSessionJob
                }

                val updated = DraftStateReducer.applyVerifiedMutation(
                    draft = before,
                    mutatedText = response.text,
                    source = EditSource.DENSITY_SCRUB
                )
                updateSession(targetSessionId) { cur ->
                    val idx = cur.document.activeCandidateIndex
                    val newCandidates = cur.document.candidates.mapIndexed { i, c ->
                        if (i == idx) updated else c
                    }
                    cur.copy(
                        document = cur.document.copy(candidates = newCandidates),
                        densityDeltaPercent = 0,
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(cur.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: cur.lastLlmMeta,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                if (_uiState.value.document.id == targetSessionId) {
                    editGeneration.incrementAndGet()
                    refreshGraphMatches()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleSessionApiFailure(targetSessionId, "Density scrub failed", e)
            }
        }
    }

    fun commitRawKeyboardEdit(text: String) {
        val state = _uiState.value
        if (state.document.stage == Stage.SEED) {
            val clean = DraftStateReducer.sanitizeAscii(text).trim()
            _uiState.update { cur ->
                cur.copy(
                    document = cur.document.copy(seedTranscript = clean),
                    rawKeyboardMode = false
                )
            }
            if (clean.isNotEmpty()) {
                refreshAnglesFromSeedInternal(silentOnError = false)
            }
            return
        }

        val activeCandidate = state.document.activeCandidate ?: run {
            _uiState.update { it.copy(rawKeyboardMode = false) }
            return
        }
        val edited = DraftStateReducer.applyRawKeyboardEdit(activeCandidate, text)
        clearBrushBaseline()
        replaceActiveCandidate(edited, dismissBrush = true)
        _uiState.update { it.copy(rawKeyboardMode = false) }
        refreshGraphMatches()
    }

    fun splitOverflowThread() {
        val activeCandidate = _uiState.value.document.activeCandidate ?: return
        val split = DraftStateReducer.splitOverflowThread(activeCandidate)
        replaceActiveCandidate(split, dismissBrush = false)
        if (split.linkedSplitCards.size >= 2) {
            _uiState.update {
                it.copy(statusNotice = "Split into ${split.linkedSplitCards.size} linked cards (locks preserved).")
            }
        }
    }

    // =========================================================================
    // STAGE 4: 04 SHIP + LOCAL NETWORK GRAPH RADAR
    // =========================================================================

    fun setStage(stage: Stage) {
        val currentDoc = _uiState.value.document
        if (stage != Stage.SEED && currentDoc.candidates.isEmpty()) {
            _uiState.update {
                it.copy(statusNotice = "Synthesize drafts in 01 SEED first.")
            }
            return
        }
        // Stepping backward or forward preserves ALL downstream candidates, locks, salvage tray, and steering.
        dismissBrushStrip()
        _uiState.update { state ->
            state.copy(
                document = state.document.copy(stage = stage),
                rawKeyboardMode = false,
                densityDeltaPercent = 0
            )
        }
        if (stage == Stage.SHIP) {
            refreshGraphMatches()
        }
    }

    fun toggleRadarDrawer() {
        setRadarExpanded(!_uiState.value.isRadarExpanded)
    }

    fun setRadarExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isRadarExpanded = expanded) }
        if (expanded) {
            refreshGraphMatches()
        }
    }

    fun refreshGraphMatches() {
        val draftText = currentDraftPlainText()
        graphRefreshJob?.cancel()
        graphRefreshJob = viewModelScope.launch {
            val (count, matches) = withContext(Dispatchers.IO) {
                val c = localGraphDatabase.contactCount()
                val m = if (c > 0 && draftText.isNotBlank()) {
                    graphRadarEngine.findMatches(draftText = draftText, limit = 8)
                } else {
                    emptyList()
                }
                c to m
            }
            _uiState.update {
                it.copy(graphContactCount = count, graphMatches = matches)
            }
        }
    }

    fun importXArchiveUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImportingArchive = true, errorMessage = null)
            }
            try {
                val appContext = getApplication<Application>()
                val result = withContext(Dispatchers.IO) {
                    val fileName = queryDisplayName(appContext, uri)
                        ?: uri.lastPathSegment?.substringAfterLast('/')
                        ?: "x_archive.json"
                    val stream = appContext.contentResolver.openInputStream(uri)
                        ?: throw IOException("Unable to open selected archive stream.")
                    stream.use { input ->
                        xArchiveImporter.importStream(
                            inputStream = input,
                            fileName = fileName,
                            database = localGraphDatabase
                        )
                    }.also {
                        runCatching {
                            val posts = localGraphDatabase.getAllPosts().map { stored -> stored.record }
                            graphAppSearchIndexer.indexPosts(posts)
                        }
                    }
                }
                val draftText = currentDraftPlainText()
                val (count, matches) = withContext(Dispatchers.IO) {
                    val c = localGraphDatabase.contactCount()
                    val m = if (c > 0 && draftText.isNotBlank()) {
                        graphRadarEngine.findMatches(draftText = draftText, limit = 8)
                    } else {
                        emptyList()
                    }
                    c to m
                }
                _uiState.update {
                    it.copy(
                        graphContactCount = count,
                        graphMatches = matches,
                        isImportingArchive = false,
                        statusNotice = "Imported ${result.contactsImported} contacts, ${result.postsImported} posts."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImportingArchive = false,
                        errorMessage = e.localizedMessage ?: "Failed to import X archive."
                    )
                }
            }
        }
    }

    fun toggleQuoteAttachment(match: GraphMatch) {
        _uiState.update { state ->
            val current = state.document.attachment
            val next = if (current is XAttachment.QuotePost &&
                current.handle == match.handle &&
                current.tweetId == match.tweetId
            ) {
                null
            } else {
                XAttachment.QuotePost(
                    handle = match.handle,
                    tweetId = match.tweetId,
                    url = match.postUrl,
                    snippet = match.snippet
                )
            }
            state.copy(document = state.document.copy(attachment = next))
        }
    }

    fun toggleReplyAttachment(match: GraphMatch) {
        if (match.tweetId.isBlank()) {
            _uiState.update {
                it.copy(statusNotice = "No tweet ID available for @${match.handle} reply.")
            }
            return
        }
        _uiState.update { state ->
            val current = state.document.attachment
            val next = if (current is XAttachment.ReplyTarget &&
                current.handle == match.handle &&
                current.tweetId == match.tweetId
            ) {
                null
            } else {
                XAttachment.ReplyTarget(
                    handle = match.handle,
                    tweetId = match.tweetId,
                    snippet = match.snippet
                )
            }
            state.copy(document = state.document.copy(attachment = next))
        }
    }

    fun clearAttachment() {
        _uiState.update { state ->
            state.copy(document = state.document.copy(attachment = null))
        }
    }

    fun weaveMention(match: GraphMatch) {
        val activeCandidate = _uiState.value.document.activeCandidate ?: return
        val currentText = activeCandidate.plainText()
        val woven = graphRadarEngine.weaveHandleIntoDraft(
            draftText = currentText,
            handle = match.handle,
            matchContext = match.snippet
        )
        if (woven == currentText) {
            _uiState.update { it.copy(statusNotice = "@${match.handle} is already in the draft.") }
            return
        }

        val parity = DraftStateReducer.verifyLockParity(
            originalClauses = activeCandidate.clauses,
            mutatedText = woven,
            source = EditSource.WEAVE_MENTION
        )
        val locallyWoven = if (parity.preserved) {
            DraftStateReducer.applyVerifiedMutation(
                draft = activeCandidate,
                mutatedText = woven,
                source = EditSource.WEAVE_MENTION
            )
        } else {
            DraftStateReducer.spliceVerbatimPhrase(
                draft = activeCandidate,
                targetClauseId = null,
                insertGapIndex = activeCandidate.clauses.size,
                spokenPhrase = "@${match.handle}",
                source = EditSource.WEAVE_MENTION
            )
        }

        clearBrushBaseline()
        val gen = replaceActiveCandidate(locallyWoven, dismissBrush = true)
        _uiState.update { it.copy(statusNotice = "Wove @${match.handle} into draft.") }
        refreshGraphMatches()

        runSeamHealPass(
            baseCandidate = locallyWoven,
            operation = "weave_mention",
            source = EditSource.WEAVE_MENTION,
            expectedGeneration = gen,
            busyLabel = "Healing @${match.handle} mention..."
        )
    }

    fun muteGraphMatch(match: GraphMatch) {
        val draftText = currentDraftPlainText()
        viewModelScope.launch {
            val updatedMatches = withContext(Dispatchers.IO) {
                graphRadarEngine.muteHandleForDraft(handle = match.handle, draftText = draftText)
                if (draftText.isNotBlank()) {
                    graphRadarEngine.findMatches(draftText = draftText, limit = 8)
                } else {
                    emptyList()
                }
            }
            _uiState.update { state ->
                val clearedAttachment = when (val att = state.document.attachment) {
                    is XAttachment.QuotePost -> if (att.handle == match.handle) null else att
                    is XAttachment.ReplyTarget -> if (att.handle == match.handle) null else att
                    null -> null
                }
                state.copy(
                    document = state.document.copy(attachment = clearedAttachment),
                    graphMatches = updatedMatches,
                    statusNotice = "Muted @${match.handle} for this topic."
                )
            }
        }
    }

    fun copySanitizedAscii(context: Context) {
        val text = currentDraftPlainText()
        if (text.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Draft is empty.") }
            return
        }
        val copied = XShipIntentHelper.copySanitizedToClipboard(context, text)
        _uiState.update {
            it.copy(statusNotice = "Copied ${copied.length} ASCII chars to clipboard.")
        }
    }

    fun openInX(context: Context) {
        val text = currentDraftPlainText()
        if (text.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Draft is empty.") }
            return
        }
        val attachment = _uiState.value.document.attachment
        val quoteUrl = (attachment as? XAttachment.QuotePost)?.url
        val replyTweetId = (attachment as? XAttachment.ReplyTarget)?.tweetId

        val intent = XShipIntentHelper.buildOpenInXIntent(
            context = context,
            plainText = text,
            quoteUrl = quoteUrl,
            replyTweetId = replyTweetId
        )
        runCatching { context.startActivity(intent) }
            .onSuccess {
                val targetLabel = if (XShipIntentHelper.isXAppInstalled(context)) "X app" else "X web composer"
                _uiState.update { it.copy(statusNotice = "Opened in $targetLabel.") }
            }
            .onFailure { err ->
                _uiState.update {
                    it.copy(errorMessage = "Unable to open X composer: ${err.localizedMessage ?: "No handler"}")
                }
            }
    }

    // =========================================================================
    // GLOBAL ACTIONS
    // =========================================================================

    fun toggleRawKeyboardMode() {
        val current = _uiState.value
        val entering = !current.rawKeyboardMode
        if (entering) {
            dismissBrushStrip()
        }
        updateSession(current.document.id) { it.copy(rawKeyboardMode = entering) }
        if (!entering &&
            current.document.stage == Stage.SEED &&
            current.document.seedTranscript.trim().length >= 8
        ) {
            refreshAnglesFromSeedInternal(
                silentOnError = true,
                targetSessionId = current.document.id
            )
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

    // =========================================================================
    // CLIP STUDIO v5: TONE, SHOT PLAN + REAL PREVIEWS, NOTE AUDIO, FOLLOW-UP,
    // SINGLE-TAP PHRASE, LENGTH ±12W, & FULL-SCRIPT KEYBOARD EDIT
    // =========================================================================

    /** Sets and persists [tone] on the active [DraftDocument] and default [DraftApiClient]. */
    fun setTone(tone: DraftTone) {
        draftApiClient.activeTone = tone
        updateSession(_uiState.value.document.id) { state ->
            state.copy(document = state.document.copy(tone = tone))
        }
    }

    fun toggleAnchorsSheet() {
        setAnchorsSheetOpen(!_uiState.value.anchorsSheetOpen)
    }

    fun setAnchorsSheetOpen(open: Boolean) {
        _uiState.update { it.copy(anchorsSheetOpen = open) }
    }

    /** Cycles `FACE -> PIP -> BROLL -> CARD` on [beatIndex], [shotIndex] with undo + real preview fetch. */
    fun cycleBeatShot(beatIndex: Int, shotIndex: Int) {
        val sessionId = _uiState.value.document.id
        var createdShot: ShotSpec? = null
        var beatText = ""
        var headline = ""
        updateSession(sessionId) { state ->
            val doc = state.document
            val beat = doc.arcBeats.getOrNull(beatIndex) ?: return@updateSession state
            val oldShot = beat.shots.getOrNull(shotIndex) ?: return@updateSession state
            val nextType = oldShot.type.nextInCycle()
            val updatedShot = ShotSpec(
                id = nextShotId(beatIndex, shotIndex, nextType),
                type = nextType,
                prompt = oldShot.prompt.ifBlank { beat.text.take(72) }
            )
            createdShot = updatedShot
            beatText = beat.text
            headline = doc.arcBeats.firstOrNull()?.text.orEmpty().take(40)
            val updatedBeats = doc.arcBeats.mapIndexed { bIdx, b ->
                if (bIdx != beatIndex) b
                else b.copy(
                    shots = b.shots.mapIndexed { sIdx, s -> if (sIdx == shotIndex) updatedShot else s }
                )
            }
            val nextUndo = (doc.arcUndoStack + listOf(doc.arcBeats)).takeLast(MAX_ARC_UNDO)
            state.copy(
                document = doc.copy(
                    arcBeats = updatedBeats,
                    arcUndoStack = nextUndo
                )
            )
        }
        createdShot?.let { shot ->
            requestShotPreviewForSingleShot(
                sessionId = sessionId,
                shot = shot,
                fallbackPrompt = beatText,
                headline = headline
            )
        }
    }

    /** Appends a `BROLL` [ShotSpec] to [beatIndex] with undo + real preview fetch. */
    fun addBeatBrollShot(beatIndex: Int, prompt: String = "") {
        val sessionId = _uiState.value.document.id
        var createdShot: ShotSpec? = null
        var beatText = ""
        var headline = ""
        updateSession(sessionId) { state ->
            val doc = state.document
            val beat = doc.arcBeats.getOrNull(beatIndex) ?: return@updateSession state
            val cleanPrompt = prompt.trim().ifBlank { beat.text.take(72) }
            val newShot = ShotSpec(
                id = nextShotId(beatIndex, beat.shots.size, ShotType.BROLL),
                type = ShotType.BROLL,
                prompt = cleanPrompt
            )
            createdShot = newShot
            beatText = beat.text
            headline = doc.arcBeats.firstOrNull()?.text.orEmpty().take(40)
            val updatedBeats = doc.arcBeats.mapIndexed { bIdx, b ->
                if (bIdx != beatIndex) b else b.copy(shots = b.shots + newShot)
            }
            val nextUndo = (doc.arcUndoStack + listOf(doc.arcBeats)).takeLast(MAX_ARC_UNDO)
            state.copy(
                document = doc.copy(
                    arcBeats = updatedBeats,
                    arcUndoStack = nextUndo
                )
            )
        }
        createdShot?.let { shot ->
            requestShotPreviewForSingleShot(
                sessionId = sessionId,
                shot = shot,
                fallbackPrompt = beatText,
                headline = headline
            )
        }
    }

    /** Undoes the last SPARK beat/shot modification from [DraftDocument.arcUndoStack]. */
    fun undoArcShotEdit() {
        val sessionId = _uiState.value.document.id
        var restoredBeats: List<ArcBeat>? = null
        updateSession(sessionId) { state ->
            val doc = state.document
            val prev = doc.arcUndoStack.lastOrNull() ?: return@updateSession state
            restoredBeats = prev
            state.copy(
                document = doc.copy(
                    arcBeats = prev,
                    arcUndoStack = doc.arcUndoStack.dropLast(1)
                ),
                toastMessage = "undid last change"
            )
        }
        restoredBeats?.let { beats ->
            requestShotPreviewsForBeats(
                sessionId = sessionId,
                beats = beats,
                headline = beats.firstOrNull()?.text.orEmpty().take(40)
            )
        }
    }

    /** Plays or pauses the real recorded SPARK voice note WAV via Android [MediaPlayer]. */
    fun togglePlaySeedAudio() {
        val current = _uiState.value
        if (current.isPlayingSeedAudio) {
            releaseSeedMediaPlayer()
            _uiState.update { it.copy(isPlayingSeedAudio = false) }
            return
        }
        val audioPath = current.lastSeedAudioPath?.trim().orEmpty()
        val audioFile = if (audioPath.isNotEmpty()) File(audioPath) else null
        if (audioFile == null || !audioFile.isFile || audioFile.length() <= 0L) {
            showToast("no recorded audio for this note")
            return
        }
        releaseSeedMediaPlayer()
        try {
            val player = MediaPlayer()
            player.setDataSource(audioFile.absolutePath)
            player.setOnCompletionListener {
                releaseSeedMediaPlayer()
                _uiState.update { it.copy(isPlayingSeedAudio = false) }
            }
            player.setOnErrorListener { _, _, _ ->
                releaseSeedMediaPlayer()
                _uiState.update {
                    it.copy(
                        isPlayingSeedAudio = false,
                        errorMessage = "Unable to play recorded voice note."
                    )
                }
                true
            }
            player.prepare()
            synchronized(seedPlayerLock) {
                seedMediaPlayer = player
            }
            player.start()
            _uiState.update { it.copy(isPlayingSeedAudio = true, errorMessage = null) }
        } catch (e: Exception) {
            releaseSeedMediaPlayer()
            _uiState.update {
                it.copy(
                    isPlayingSeedAudio = false,
                    errorMessage = "Audio playback failed: ${e.localizedMessage ?: "invalid audio file"}"
                )
            }
        }
    }

    /** Submits a typed or edited SPARK note (`"type it instead"` / note edit sheet), extracting angles & proposing the 4-beat arc. */
    fun submitTypedNote(text: String) {
        val clean = DraftStateReducer.sanitizeAscii(text).trim()
        if (clean.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Type an idea before continuing.") }
            return
        }
        releaseSeedMediaPlayer()
        val sessionId = _uiState.value.document.id
        updateSession(sessionId) { state ->
            state.copy(
                document = state.document.copy(
                    seedTranscript = clean,
                    isFollowupLoaded = false,
                    followupSourceLabel = ""
                ),
                isPlayingSeedAudio = false,
                rawKeyboardMode = false,
                errorMessage = null
            )
        }
        refreshAnglesFromSeedInternal(silentOnError = false, targetSessionId = sessionId)
    }

    fun toggleDirectionsPanel(video: LastVideoSummary? = null) {
        setDirectionsOpen(!_uiState.value.directionsOpen, video)
    }

    fun setDirectionsOpen(open: Boolean, video: LastVideoSummary? = null) {
        val effectiveVideo = video ?: _uiState.value.followupVideo
        _uiState.update {
            it.copy(
                directionsOpen = open,
                followupVideo = effectiveVideo ?: it.followupVideo
            )
        }
        if (open && effectiveVideo != null && _uiState.value.followupDirections.isEmpty() && !_uiState.value.isDraftingFollowup) {
            requestFollowupOverview(effectiveVideo)
        }
    }

    fun openContinueSheet(video: LastVideoSummary? = null) {
        val effectiveVideo = video ?: _uiState.value.followupVideo ?: return
        _uiState.update {
            it.copy(
                continueSheetOpen = true,
                followupVideo = effectiveVideo,
                followupDirection = null
            )
        }
        if (_uiState.value.followupBeats.isEmpty() && !_uiState.value.isDraftingFollowup) {
            requestFollowupOverview(effectiveVideo)
        }
    }

    fun closeContinueSheet() {
        _uiState.update { it.copy(continueSheetOpen = false) }
    }

    /** Calls real `POST /v1/draft/followup` (`angle = "overview"`) for [video]. */
    fun requestFollowupOverview(video: LastVideoSummary) {
        followupJob?.cancel()
        val sessionId = _uiState.value.document.id
        val tone = _uiState.value.document.tone
        _uiState.update {
            it.copy(
                followupVideo = video,
                followupAngle = "answer",
                followupDirection = null,
                isDraftingFollowup = true,
                errorMessage = null
            )
        }
        followupJob = viewModelScope.launch {
            try {
                val stageCfg = profileStore.current().llmRouting.forStage(LlmStage.DRAFTING)
                val response = draftApiClient.followup(
                    lastTitle = video.title,
                    lastTranscript = video.transcript,
                    topReply = video.topReplyQuestion,
                    angle = "overview",
                    targetSec = ARC_TARGET_SEC,
                    tone = tone.wireValue,
                    llmProvider = stageCfg.resolveWireProviderId(),
                    llmModel = stageCfg.normalizedModelOverride()
                )
                val rawAnswer = response.answerBeats.ifEmpty { response.beats }
                val answerBeats = rawAnswer.mapIndexed { idx, dto ->
                    dto.toArcBeat(beatIndex = idx, shotIdFactory = ::nextShotId)
                }
                val directions = response.directions.map { dirDto ->
                    val dirBeats = dirDto.beats.mapIndexed { idx, bDto ->
                        bDto.toArcBeat(beatIndex = idx, shotIdFactory = ::nextShotId)
                    }
                    if (dirBeats.isNotEmpty()) {
                        followupAngleBeatCache["${video.clipId}:${dirDto.id.lowercase()}"] = dirBeats
                    }
                    FollowupDirectionCard(
                        id = dirDto.id.trim().lowercase(),
                        theme = dirDto.theme.trim().uppercase(),
                        words = dirDto.words.trim(),
                        beats = dirBeats
                    )
                }
                if (answerBeats.isNotEmpty()) {
                    followupAngleBeatCache["${video.clipId}:answer"] = answerBeats
                }
                updateSession(sessionId) { state ->
                    state.copy(
                        followupVideo = video,
                        followupDirections = directions,
                        followupBeats = if (state.followupDirection != null) {
                            directions.firstOrNull { it.id == state.followupDirection }?.beats?.ifEmpty { answerBeats }
                                ?: answerBeats
                        } else {
                            answerBeats
                        },
                        isDraftingFollowup = false,
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(state.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: state.lastLlmMeta
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val offline = isRigTransportFailure(e)
                _uiState.update { state ->
                    state.copy(
                        isDraftingFollowup = false,
                        lastSeedRigOnline = if (offline) false else state.lastSeedRigOnline,
                        errorMessage = com.example.engine.userFacingStageError(e, "unknown error").let { m -> if (m == com.example.engine.MacStudioOfflineException().message) m else "Follow-up draft failed: $m" }
                    )
                }
            }
        }
    }

    /** Switches Follow-Up Sheet angle (`"answer" | "part_2" | "update"`), calling real `POST /v1/draft/followup`. */
    fun selectFollowupAngle(angle: String) {
        val normalized = angle.trim().lowercase().replace(Regex("[\\s-]+"), "_")
        require(normalized in setOf("answer", "part_2", "update")) {
            "Unsupported follow-up angle '$angle'"
        }
        val video = _uiState.value.followupVideo ?: return
        val cached = followupAngleBeatCache["${video.clipId}:$normalized"]
        if (cached != null && cached.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    continueSheetOpen = true,
                    followupAngle = normalized,
                    followupDirection = null,
                    followupBeats = cached,
                    isDraftingFollowup = false,
                    errorMessage = null
                )
            }
            return
        }
        fetchFollowupSpecificBeats(video = video, wireAngle = normalized, isDirection = false)
    }

    /** Selects a themed follow-up direction (`"shitpost" | "solarpunk"`), opening the Follow-Up Sheet. */
    fun selectFollowupDirection(dir: String) {
        val normalized = dir.trim().lowercase()
        require(normalized in setOf("shitpost", "solarpunk")) {
            "Unsupported follow-up direction '$dir'"
        }
        val state = _uiState.value
        val video = state.followupVideo ?: return
        val preloaded = state.followupDirections.firstOrNull { it.id == normalized }?.beats
            ?.takeIf { it.isNotEmpty() }
            ?: followupAngleBeatCache["${video.clipId}:$normalized"]
        if (preloaded != null && preloaded.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    continueSheetOpen = true,
                    followupDirection = normalized,
                    followupBeats = preloaded,
                    isDraftingFollowup = false,
                    errorMessage = null
                )
            }
            return
        }
        fetchFollowupSpecificBeats(video = video, wireAngle = normalized, isDirection = true)
    }

    private fun fetchFollowupSpecificBeats(
        video: LastVideoSummary,
        wireAngle: String,
        isDirection: Boolean
    ) {
        followupJob?.cancel()
        val sessionId = _uiState.value.document.id
        val tone = _uiState.value.document.tone
        _uiState.update {
            it.copy(
                continueSheetOpen = true,
                followupAngle = if (isDirection) it.followupAngle else wireAngle,
                followupDirection = if (isDirection) wireAngle else null,
                isDraftingFollowup = true,
                errorMessage = null
            )
        }
        followupJob = viewModelScope.launch {
            try {
                val stageCfg = profileStore.current().llmRouting.forStage(LlmStage.DRAFTING)
                val response = draftApiClient.followup(
                    lastTitle = video.title,
                    lastTranscript = video.transcript,
                    topReply = video.topReplyQuestion,
                    angle = wireAngle,
                    targetSec = ARC_TARGET_SEC,
                    tone = tone.wireValue,
                    llmProvider = stageCfg.resolveWireProviderId(),
                    llmModel = stageCfg.normalizedModelOverride()
                )
                val beats = response.beats.ifEmpty { response.answerBeats }.mapIndexed { idx, dto ->
                    dto.toArcBeat(beatIndex = idx, shotIdFactory = ::nextShotId)
                }
                if (beats.isNotEmpty()) {
                    followupAngleBeatCache["${video.clipId}:$wireAngle"] = beats
                }
                updateSession(sessionId) { st ->
                    st.copy(
                        followupBeats = beats,
                        isDraftingFollowup = false,
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(st.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: st.lastLlmMeta
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val offline = isRigTransportFailure(e)
                _uiState.update { st ->
                    st.copy(
                        isDraftingFollowup = false,
                        lastSeedRigOnline = if (offline) false else st.lastSeedRigOnline,
                        errorMessage = com.example.engine.userFacingStageError(e, "unknown error").let { m -> if (m == com.example.engine.MacStudioOfflineException().message) m else "Follow-up draft failed: $m" }
                    )
                }
            }
        }
    }

    /**
     * Loads the active follow-up beats + shot plan into 01 SPARK, sets `isFollowupLoaded = true`
     * and `followupSourceLabel = "${video.title} · $angleLabel"`, and immediately starts real shot previews.
     */
    fun loadFollowupIntoSpark(
        videoOverride: LastVideoSummary? = null,
        beatsOverride: List<ArcBeat>? = null
    ) {
        val current = _uiState.value
        val video = videoOverride ?: current.followupVideo ?: return
        val beats = (beatsOverride ?: current.followupBeats).takeIf { it.isNotEmpty() } ?: return
        val angleLabel = when (val dir = current.followupDirection) {
            null -> when (current.followupAngle) {
                "answer" -> {
                    val reply = video.topReplyQuestion?.trim().orEmpty()
                    if (reply.isNotEmpty()) "answering \"$reply\"" else "answer"
                }
                "part_2" -> "part 2"
                "update" -> "update"
                else -> current.followupAngle.replace('_', ' ')
            }
            else -> {
                val dirCard = current.followupDirections.firstOrNull { it.id == dir }
                if (dirCard != null && dirCard.words.isNotBlank()) {
                    "${dirCard.theme.lowercase()}: ${dirCard.words}"
                } else {
                    dir
                }
            }
        }
        val sourceLabel = "${video.title} · $angleLabel"
        val synthesizedSeed = buildString {
            append("Follow-up to \"").append(video.title).append("\" (").append(angleLabel).append("): ")
            append(beats.joinToString(" ") { it.text.trim() })
        }.trim()
        val targetTone = when (current.followupDirection) {
            "shitpost" -> DraftTone.SHITPOST
            "solarpunk" -> DraftTone.SOLARPUNK
            else -> current.document.tone
        }
        draftApiClient.activeTone = targetTone
        val sessionId = current.document.id
        updateSession(sessionId) { st ->
            st.copy(
                document = st.document.copy(
                    stage = Stage.SEED,
                    tone = targetTone,
                    seedTranscript = synthesizedSeed,
                    arcBeats = beats,
                    arcUndoStack = emptyList(),
                    arcTargetSec = ARC_TARGET_SEC,
                    arcWordBudget = (ARC_TARGET_SEC * 2.5).roundToInt(),
                    isFollowupLoaded = true,
                    followupSourceLabel = sourceLabel
                ),
                continueSheetOpen = false,
                directionsOpen = false,
                toastMessage = "follow-up loaded · edit any beat",
                errorMessage = null
            )
        }
        requestShotPreviewsForBeats(
            sessionId = sessionId,
            beats = beats,
            headline = video.title.take(40)
        )
    }

    /**
     * 02 SCRIPT single-tap phrase selection: selects a natural 3–6 word grammatical span around
     * [charOffset], sets `activeBrushRange`, and immediately triggers [performBrushSwap].
     */
    fun selectPhraseSingleTap(charOffset: Int) {
        val candidate = _uiState.value.document.activeCandidate ?: return
        val range = DraftStateReducer.selectNaturalPhraseRange(candidate, charOffset) ?: return
        selectPhraseSpan(range)
    }

    /**
     * Explicitly selects [range] in the active script candidate, sets `activeBrushRange`, and
     * immediately requests real replacement alternatives via [performBrushSwap].
     */
    fun selectPhraseSpan(range: IntRange) {
        if (range.isEmpty()) return
        val current = _uiState.value
        if (current.activeBrushRange == range && current.brushAlternatives.isNotEmpty()) {
            keepSelectedPhrase()
            return
        }
        clearBrushBaseline()
        _uiState.update {
            it.copy(
                activeBrushRange = range,
                brushAlternatives = emptyList(),
                brushActiveAltIndex = 0,
                activeBrushNudge = null,
                scriptHintSeen = true
            )
        }
        performBrushSwap(range)
    }

    /** Commits/keeps the currently previewed phrase choice and closes the phrase sheet. */
    fun keepSelectedPhrase() {
        dismissBrushStrip()
        _uiState.update { it.copy(activeBrushNudge = null) }
    }

    /** Requests fresh replacement options (`more` nudge) for the currently selected phrase. */
    fun refreshPhraseAlternatives() {
        if (brushBaselineDraft != null && brushBaselineRange != null) {
            performBrushNudge(BrushNudge.MORE)
        } else {
            val range = _uiState.value.activeBrushRange ?: return
            performBrushSwap(range)
        }
    }

    fun toggleLengthRow() {
        setLengthRowOpen(!_uiState.value.lengthRowOpen)
    }

    fun setLengthRowOpen(open: Boolean) {
        _uiState.update { it.copy(lengthRowOpen = open) }
    }

    /**
     * Steps script length by [deltaWords] (e.g. `-12` for shorter, `+12` for longer), mapping
     * proportionally to [commitDensityScrub].
     */
    fun stepScriptLength(deltaWords: Int) {
        if (deltaWords == 0) return
        val candidate = _uiState.value.document.activeCandidate ?: return
        val currentWords = candidate.plainText()
            .trim()
            .split(Regex("\\s+"))
            .count { it.isNotBlank() }
            .coerceAtLeast(1)
        val rawPercent = ((deltaWords.toFloat() / currentWords.toFloat()) * 100f).roundToInt()
        val effectivePercent = if (deltaWords < 0) {
            rawPercent.coerceIn(DraftApiClient.MIN_DENSITY_DELTA, -5)
        } else {
            rawPercent.coerceIn(5, DraftApiClient.MAX_DENSITY_DELTA)
        }
        commitDensityScrub(effectivePercent)
    }

    /** Saves an undo snapshot and applies a full-script manual keyboard edit to the active candidate. */
    fun applyFullScriptKeyboardEdit(newText: String) {
        val state = _uiState.value
        val activeCandidate = state.document.activeCandidate
        if (activeCandidate == null) {
            val clean = DraftStateReducer.sanitizeAscii(newText).trim()
            if (clean.isEmpty()) return
            val created = DraftStateReducer.createCandidateDraft(
                postureLabel = "DIRECT",
                text = clean,
                source = EditSource.MANUAL_RAW
            )
            updateSession(state.document.id) { cur ->
                cur.copy(
                    document = cur.document.copy(
                        candidates = listOf(created),
                        activeCandidateIndex = 0
                    ),
                    rawKeyboardMode = false,
                    toastMessage = "script updated"
                )
            }
            refreshGraphMatches()
            return
        }
        val edited = DraftStateReducer.applyRawKeyboardEdit(activeCandidate, newText)
        clearBrushBaseline()
        replaceActiveCandidate(edited, dismissBrush = true)
        _uiState.update { it.copy(rawKeyboardMode = false, toastMessage = "script updated") }
        refreshGraphMatches()
    }

    /**
     * Restores a handed-off or AppFunction-prepared plain-text draft and stage cleanly without
     * altering any UI structure.
     */
    fun restoreHandoffState(stageRaw: String?, seedText: String?) {
        val cleanText = seedText?.trim().orEmpty()
        val parsedStage = stageRaw?.trim()?.let { raw ->
            Stage.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
        if (cleanText.isNotEmpty()) {
            updateSeedTranscriptManual(cleanText)
            if (parsedStage == Stage.CUT || parsedStage == Stage.SHIP) {
                val cand = DraftStateReducer.createCandidateDraft(
                    postureLabel = "DIRECT",
                    text = cleanText
                )
                _uiState.update { state ->
                    state.copy(
                        document = state.document.copy(
                            seedTranscript = cleanText,
                            candidates = listOf(cand),
                            activeCandidateIndex = 0,
                            stage = parsedStage
                        )
                    )
                }
                return
            }
        }
        if (parsedStage != null) {
            setStage(parsedStage)
        }
    }

    fun resetNewDraft() {
        releaseSeedMediaPlayer()
        if (_uiState.value.isAnyRecording) {
            vadAudioEngine.release()
        }
        anglesJob?.cancel()
        activePassJob?.cancel()
        seamHealJob?.cancel()
        flashJob?.cancel()
        followupJob?.cancel()
        shotPreviewJobs.values.forEach { it.cancel() }
        shotPreviewJobs.clear()
        clearBrushBaseline()
        editGeneration.incrementAndGet()
        seedBeforeLatch = ""
        openLatchHadIncremental = false

        _uiState.update { state ->
            DraftDeckUiState(
                document = DraftDocument(
                    id = UUID.randomUUID().toString(),
                    tone = state.document.tone
                ),
                graphContactCount = state.graphContactCount,
                lastSeedRigOnline = state.lastSeedRigOnline,
                rigBaseUrl = state.rigBaseUrl
            )
        }
    }

    // =========================================================================
    // INTERNAL HELPERS
    // =========================================================================

    private fun runSeamHealPass(
        baseCandidate: CandidateDraft,
        operation: String,
        source: EditSource,
        expectedGeneration: Long,
        busyLabel: String
    ) {
        val markedText = DraftStateReducer.renderMarkedTextWithLocks(baseCandidate.clauses)
        if (markedText.isBlank()) return
        val steering = _uiState.value.document.steeringConstraints

        seamHealJob?.cancel()
        seamHealJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, busyLabel = busyLabel) }
            try {
                val response = llmStageRouter.seamHeal(
                    markedText = markedText,
                    operation = operation,
                    steeringConstraints = steering
                )
                if (editGeneration.get() != expectedGeneration) {
                    _uiState.update { it.copy(isBusy = false, busyLabel = null) }
                    return@launch
                }

                val parity = DraftStateReducer.verifyLockParity(
                    originalClauses = baseCandidate.clauses,
                    mutatedText = response.text,
                    source = source
                )
                if (parity.preserved && parity.clauses != baseCandidate.clauses) {
                    val healed = DraftStateReducer.applyVerifiedMutation(
                        draft = baseCandidate,
                        mutatedText = response.text,
                        source = source
                    )
                    replaceActiveCandidate(healed, dismissBrush = false)
                }
                _uiState.update {
                    it.copy(
                        lastSeedRigOnline = resolveRigOnlineAfterSuccess(it.lastSeedRigOnline, response.llmMeta),
                        lastLlmMeta = response.llmMeta ?: it.lastLlmMeta,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                refreshGraphMatches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Local edit already succeeded; surface honest seam-heal error without reverting local edit.
                _uiState.update {
                    it.copy(
                        lastSeedRigOnline = if (isRigTransportFailure(e)) false else it.lastSeedRigOnline,
                        isBusy = false,
                        busyLabel = null,
                        errorMessage = "Seam heal unavailable (kept local edit): ${e.localizedMessage ?: "network error"}"
                    )
                }
            }
        }
    }

    private fun replaceActiveCandidate(
        updated: CandidateDraft,
        dismissBrush: Boolean,
        clearFocusedClauseIfId: String? = null
    ): Long {
        val nextGen = editGeneration.incrementAndGet()
        _uiState.update { state ->
            val idx = state.document.activeCandidateIndex
            if (idx !in state.document.candidates.indices) return@update state
            val updatedList = state.document.candidates.mapIndexed { i, c ->
                if (i == idx) updated else c
            }
            state.copy(
                document = state.document.copy(candidates = updatedList),
                focusedClauseId = if (clearFocusedClauseIfId != null && state.focusedClauseId == clearFocusedClauseIfId) {
                    null
                } else {
                    state.focusedClauseId
                },
                activeBrushRange = if (dismissBrush) null else state.activeBrushRange,
                brushAlternatives = if (dismissBrush) emptyList() else state.brushAlternatives,
                brushActiveAltIndex = if (dismissBrush) 0 else state.brushActiveAltIndex
            )
        }
        return nextGen
    }

    private fun scheduleFlashClear(range: IntRange?) {
        flashJob?.cancel()
        if (range == null) return
        flashJob = viewModelScope.launch {
            delay(FLASH_DURATION_MS)
            _uiState.update { it.copy(flashRange = null) }
        }
    }

    private fun clearBrushBaseline() {
        brushBaselineDraft = null
        brushBaselineRange = null
        lastAppliedBrushRange = null
    }

    private fun computeAppliedReplacementRange(
        baseline: CandidateDraft,
        baseRange: IntRange,
        updated: CandidateDraft,
        replacement: String
    ): IntRange? {
        val clean = DraftStateReducer.sanitizeAscii(replacement).trim()
        if (clean.isEmpty()) return null
        val updatedText = updated.plainText()
        if (updatedText.isEmpty()) return null

        val (_, baseFlat) = DraftStateReducer.flattenClauses(baseline.clauses)
        val firstAffectedStart = baseFlat.firstOrNull { ft ->
            ft.textStart <= baseRange.last && (ft.textEndExclusive - 1) >= baseRange.first
        }?.textStart ?: baseRange.first

        val searchStart = (firstAffectedStart - 4).coerceIn(0, updatedText.lastIndex)
        val foundIdx = updatedText.indexOf(clean, startIndex = searchStart, ignoreCase = true)
        return if (foundIdx >= 0) {
            foundIdx..(foundIdx + clean.length - 1).coerceAtMost(updatedText.lastIndex)
        } else {
            val start = firstAffectedStart.coerceIn(0, updatedText.lastIndex)
            val end = (start + clean.length - 1).coerceIn(start, updatedText.lastIndex)
            start..end
        }
    }

    private fun rangesOverlap(a: IntRange, b: IntRange): Boolean =
        !a.isEmpty() && !b.isEmpty() && a.first <= b.last && b.first <= a.last

    private fun collectSelectedAngleSpecs(doc: DraftDocument): List<String> {
        val selected = doc.extractedAngles.filter { it.selected } +
            doc.blindspotAngles.filter { it.selected }
        return selected.map { chip ->
            if (chip.preview.isNotBlank()) "${chip.label}: ${chip.preview}" else chip.label
        }
    }

    private fun joinTranscriptParts(existing: String, addition: String): String {
        val a = existing.trim()
        val b = addition.trim()
        return when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            else -> "$a $b"
        }
    }

    private fun resolveRigOnlineAfterSuccess(currentOnline: Boolean, meta: LlmMetaDto?): Boolean =
        if (meta?.provider == LlmProviderChoice.LOCAL_AICORE_NANO.id) currentOnline else true

    private fun isRigTransportFailure(error: Throwable): Boolean = when (error) {
        is LlmRoutingException -> error.isTransportFailure
        is DraftApiClient.HttpStatusException -> false
        is IOException -> true
        else -> false
    }

    private fun handleApiFailure(prefix: String, error: Throwable) {
        val isTransportFailure = isRigTransportFailure(error)
        _uiState.update { state ->
            state.copy(
                lastSeedRigOnline = if (isTransportFailure) false else state.lastSeedRigOnline,
                isBusy = false,
                busyLabel = null,
                errorMessage = com.example.engine.userFacingStageError(error, error::class.java.simpleName).let { m ->
                    if (isTransportFailure || m == com.example.engine.MacStudioOfflineException().message) {
                        com.example.engine.MacStudioOfflineException().message
                    } else {
                        "$prefix: $m"
                    }
                }
            )
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun nextShotId(beatIndex: Int, shotIndex: Int, type: ShotType): String =
        "shot_b${beatIndex}_s${shotIndex}_${type.wireValue}_${shotIdCounter.incrementAndGet()}"

    private fun persistSeedPlaybackWav(sourceWav: File, sessionId: String): File {
        val safeSession = sessionId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24)
        val out = File(vadCacheDir, "seed_playback_${safeSession.ifBlank { "active" }}.wav")
        return if (sourceWav.absolutePath == out.absolutePath) {
            out
        } else {
            runCatching { sourceWav.copyTo(out, overwrite = true) }.getOrDefault(sourceWav)
        }
    }

    private fun releaseSeedMediaPlayer() {
        synchronized(seedPlayerLock) {
            val player = seedMediaPlayer
            seedMediaPlayer = null
            if (player != null) {
                runCatching { if (player.isPlaying) player.stop() }
                runCatching { player.release() }
            }
        }
    }

    private fun requestShotPreviewsForBeats(
        sessionId: String,
        beats: List<ArcBeat>,
        headline: String
    ) {
        beats.forEach { beat ->
            beat.shots.forEach { shot ->
                requestShotPreviewForSingleShot(
                    sessionId = sessionId,
                    shot = shot,
                    fallbackPrompt = beat.text,
                    headline = headline
                )
            }
        }
    }

    private fun requestShotPreviewForSingleShot(
        sessionId: String,
        shot: ShotSpec,
        fallbackPrompt: String,
        headline: String
    ) {
        val currentState = if (sessionId == _uiState.value.document.id) {
            _uiState.value
        } else {
            synchronized(sessionsLock) { storedSessions[sessionId] } ?: return
        }
        if (currentState.shotPreviews.containsKey(shot.id) || shot.id in currentState.inFlightShotIds) {
            return
        }
        shotPreviewJobs[shot.id]?.cancel()
        updateSession(sessionId) { st ->
            st.copy(inFlightShotIds = st.inFlightShotIds + shot.id)
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val stored = shotPreviewSemaphore.withPermit {
                    val photoB64 = if (shot.type == ShotType.FACE || shot.type == ShotType.PIP) {
                        val photoSha = runCatching { profileStore.current().persona.defaultPhotoSha }.getOrNull()
                        photoSha?.takeIf { it.isNotBlank() }
                            ?.let { sha -> casStore.fileForSha(sha, "jpg") }
                            ?.let { file -> Base64.getEncoder().encodeToString(file.readBytes()) }
                    } else {
                        null
                    }
                    val prompt = shot.prompt.trim().ifBlank { fallbackPrompt.trim().take(72) }
                    val resp = draftApiClient.requestShotPreview(
                        shotType = shot.type.wireValue,
                        prompt = prompt,
                        headline = headline.trim().ifBlank { null },
                        photoJpegBase64 = photoB64
                    )
                    val bytes = draftApiClient.downloadPreviewBytes(resp.previewUrl)
                    shotPreviewCasMutex.withLock {
                        casStore.putBytes(bytes, "jpg")
                    }
                }
                updateSession(sessionId) { st ->
                    st.copy(
                        shotPreviews = st.shotPreviews + (shot.id to stored.file.absolutePath),
                        inFlightShotIds = st.inFlightShotIds - shot.id,
                        lastSeedRigOnline = true
                    )
                }
            } catch (e: CancellationException) {
                updateSession(sessionId) { st ->
                    st.copy(inFlightShotIds = st.inFlightShotIds - shot.id)
                }
                throw e
            } catch (_: Throwable) {
                updateSession(sessionId) { st ->
                    st.copy(inFlightShotIds = st.inFlightShotIds - shot.id)
                }
            } finally {
                shotPreviewJobs.remove(shot.id)
            }
        }
        shotPreviewJobs[shot.id] = job
    }

    companion object {
        private const val FLASH_DURATION_MS: Long = 180L
        private const val MAX_ARC_UNDO: Int = 32
        const val ARC_TARGET_SEC: Int = 60
    }
}
