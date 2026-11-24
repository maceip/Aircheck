package com.example.ui.draft

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.AircheckApplication
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.draft.CandidateDraft
import com.example.draft.DraftApiClient
import com.example.draft.DraftDocument
import com.example.draft.DraftStateReducer
import com.example.draft.EditSource
import com.example.draft.LockedPhrase
import com.example.draft.Stage
import com.example.draft.VadAudioCaptureEngine
import com.example.draft.XAttachment
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

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
    val busyLabel: String? = null,
    val errorMessage: String? = null,
    val statusNotice: String? = null,
    val focusedClauseId: String? = null,
    val focusedGapIndex: Int? = null,
    val activeBrushRange: IntRange? = null,
    val brushAlternatives: List<String> = emptyList(),
    val brushActiveAltIndex: Int = 0,
    val flashRange: IntRange? = null,
    val densityDeltaPercent: Int = 0,
    val graphMatches: List<GraphMatch> = emptyList(),
    val graphContactCount: Int = 0,
    val isRadarExpanded: Boolean = false,
    val isImportingArchive: Boolean = false,
    val lastSeedRigOnline: Boolean = false,
    val rigBaseUrl: String = DraftApiClient.DEFAULT_RIG_LAN_URL
) {
    val isAnyRecording: Boolean
        get() = isRecordingSeed || isRecordingSteer || isRecordingSplice

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

class DraftDeckViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AircheckApplication).container
    private val profileStore = container.profileStore
    private val vadAudioEngine: VadAudioCaptureEngine = container.vadAudioEngine
    private val localGraphDatabase = container.localGraphDatabase
    private val graphRadarEngine = container.graphRadarEngine
    private val xArchiveImporter = container.xArchiveImporter
    private val okHttpClient = container.okHttpClient

    @Volatile
    private var configuredRigUrl: String = DEFAULT_MAC_RIG_LAN_URL

    @Volatile
    private var draftApiClient: DraftApiClient = container.draftApiClient

    private val vadCacheDir: File by lazy {
        File(application.cacheDir, "aircheck_vad").apply { mkdirs() }
    }

    private val _uiState = MutableStateFlow(DraftDeckUiState())
    val uiState: StateFlow<DraftDeckUiState> = _uiState.asStateFlow()

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

    init {
        viewModelScope.launch {
            profileStore.settingsFlow.collect { settings ->
                val url = settings.tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
                if (url != configuredRigUrl) {
                    configuredRigUrl = url
                    draftApiClient = if (url == DEFAULT_MAC_RIG_LAN_URL) {
                        container.draftApiClient
                    } else {
                        DraftApiClient(
                            configuredRigUrl = url,
                            usbLoopbackUrl = DraftApiClient.DEFAULT_RIG_USB_URL,
                            okHttpClient = okHttpClient
                        )
                    }
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
            vadAudioEngine.liveRmsLevel.collect { rms ->
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
        if (_uiState.value.isAnyRecording) {
            vadAudioEngine.release()
        }
        anglesJob?.cancel()
        activePassJob?.cancel()
        seamHealJob?.cancel()
        flashJob?.cancel()
        graphRefreshJob?.cancel()
    }

    fun probeRigHealth() {
        viewModelScope.launch {
            runCatching { draftApiClient.resolveActiveBaseUrl(forceRefresh = true) }
                .onSuccess { resolved ->
                    _uiState.update {
                        it.copy(lastSeedRigOnline = true, rigBaseUrl = resolved)
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(lastSeedRigOnline = false) }
                }
        }
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

    fun toggleLatchMic() {
        val current = _uiState.value
        if (current.isRecordingSteer || current.isRecordingSplice) return

        if (!current.isRecordingSeed) {
            seedBeforeLatch = current.document.seedTranscript.trim()
            openLatchHadIncremental = false
            _uiState.update {
                it.copy(
                    isRecordingSeed = true,
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
                        _uiState.update {
                            it.copy(isBusy = true, busyLabel = "Transcribing voice seed...", errorMessage = null)
                        }
                        try {
                            val response = draftApiClient.transcribe(distilledWav)
                            val cleanChunk = DraftStateReducer.sanitizeAscii(response.transcript).trim()
                            if (cleanChunk.isNotEmpty()) {
                                val combined = joinTranscriptParts(seedBeforeLatch, cleanChunk)
                                _uiState.update { state ->
                                    state.copy(
                                        document = state.document.copy(seedTranscript = combined),
                                        lastSeedRigOnline = true,
                                        isBusy = false,
                                        busyLabel = null
                                    )
                                }
                                refreshAnglesFromSeedInternal(silentOnError = false)
                            } else {
                                _uiState.update {
                                    it.copy(lastSeedRigOnline = true, isBusy = false, busyLabel = null)
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
                            runCatching { distilledWav.delete() }
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
                        lastSeedRigOnline = if (e is IOException && e !is DraftApiClient.HttpStatusException) false else it.lastSeedRigOnline,
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

            _uiState.update {
                it.copy(isBusy = true, busyLabel = "Distilling last 15s...", errorMessage = null)
            }
            try {
                val response = draftApiClient.transcribe(extractedWav)
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
                        document = state.document.copy(seedTranscript = distilledText),
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
                runCatching { extractedWav.delete() }
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
        _uiState.update { state ->
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

    fun refreshAnglesFromSeed() {
        val seed = _uiState.value.document.seedTranscript.trim()
        if (seed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Record or type a seed thought first.") }
            return
        }
        refreshAnglesFromSeedInternal(silentOnError = false)
    }

    private fun refreshAnglesFromSeedInternal(silentOnError: Boolean) {
        val snapshotDoc = _uiState.value.document
        val cleanSeed = DraftStateReducer.sanitizeAscii(snapshotDoc.seedTranscript).trim()
        if (cleanSeed.isEmpty()) return

        anglesJob?.cancel()
        anglesJob = viewModelScope.launch {
            if (!silentOnError) {
                _uiState.update {
                    it.copy(isBusy = true, busyLabel = "Extracting angles...", errorMessage = null)
                }
            }
            try {
                val response = draftApiClient.extractAngles(
                    seedTranscript = cleanSeed,
                    steeringConstraints = snapshotDoc.steeringConstraints
                )
                // Row A (Extracted Angles) ON by default; Row B (Blindspots) OFF by default.
                val extracted = response.toExtractedAngleChips(defaultSelected = true)
                val blindspots = response.toBlindspotAngleChips(defaultSelected = false)
                _uiState.update { state ->
                    state.copy(
                        document = state.document.copy(
                            extractedAngles = extracted,
                            blindspotAngles = blindspots
                        ),
                        lastSeedRigOnline = true,
                        isBusy = if (!silentOnError) false else state.isBusy,
                        busyLabel = if (!silentOnError) null else state.busyLabel
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silentOnError) {
                    handleApiFailure("Angle extraction failed", e)
                } else {
                    _uiState.update {
                        it.copy(
                            lastSeedRigOnline = if (e is IOException && e !is DraftApiClient.HttpStatusException) false else it.lastSeedRigOnline
                        )
                    }
                }
            }
        }
    }

    fun synthesizeThreeCandidates() {
        val doc = _uiState.value.document
        val cleanSeed = DraftStateReducer.sanitizeAscii(doc.seedTranscript).trim()
        if (cleanSeed.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Record or type a seed thought before synthesizing.") }
            return
        }

        val selectedAngles = collectSelectedAngleSpecs(doc)
        activePassJob?.cancel()
        activePassJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyLabel = "Synthesizing 3 candidates...",
                    errorMessage = null,
                    rawKeyboardMode = false
                )
            }
            try {
                val response = draftApiClient.synthesize(
                    seedTranscript = cleanSeed,
                    selectedAngles = selectedAngles,
                    steeringConstraints = doc.steeringConstraints,
                    slot = null,
                    existingDrafts = emptyList()
                )
                val candidates = response.candidates.take(3).map { dto ->
                    DraftStateReducer.createCandidateDraft(
                        postureLabel = dto.postureLabel,
                        text = dto.text,
                        source = EditSource.MODEL_INIT
                    )
                }
                if (candidates.isEmpty()) {
                    throw IOException("Mac rig returned zero draft candidates.")
                }
                clearBrushBaseline()
                editGeneration.incrementAndGet()
                _uiState.update { state ->
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
                        lastSeedRigOnline = true,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                refreshGraphMatches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleApiFailure("Synthesis failed", e)
            }
        }
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

        activePassJob?.cancel()
        activePassJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyLabel = "Refreshing v${slotIdx + 1}...",
                    errorMessage = null
                )
            }
            try {
                val response = draftApiClient.synthesize(
                    seedTranscript = cleanSeed,
                    selectedAngles = selectedAngles,
                    steeringConstraints = doc.steeringConstraints,
                    slot = slotIdx,
                    existingDrafts = existingTexts
                )
                val replacementDto = response.candidates.firstOrNull()
                    ?: throw IOException("Server returned no replacement draft for slot $slotIdx.")
                val replacement = DraftStateReducer.createCandidateDraft(
                    postureLabel = replacementDto.postureLabel,
                    text = replacementDto.text,
                    source = EditSource.MODEL_INIT
                )
                clearBrushBaseline()
                editGeneration.incrementAndGet()
                _uiState.update { state ->
                    val updatedList = state.document.candidates.mapIndexed { idx, existing ->
                        if (idx == slotIdx) replacement else existing
                    }
                    state.copy(
                        document = state.document.copy(candidates = updatedList),
                        lastSeedRigOnline = true,
                        isBusy = false,
                        busyLabel = null,
                        statusNotice = "Regenerated v${slotIdx + 1} (${replacement.postureLabel})."
                    )
                }
                refreshGraphMatches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleApiFailure("Slot regeneration failed", e)
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

        activePassJob?.cancel()
        activePassJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyLabel = "Regenerating all 3 drafts...",
                    errorMessage = null
                )
            }
            try {
                val response = draftApiClient.synthesize(
                    seedTranscript = cleanSeed,
                    selectedAngles = selectedAngles,
                    steeringConstraints = doc.steeringConstraints,
                    slot = null,
                    existingDrafts = existingTexts
                )
                val candidates = response.candidates.take(3).map { dto ->
                    DraftStateReducer.createCandidateDraft(
                        postureLabel = dto.postureLabel,
                        text = dto.text,
                        source = EditSource.MODEL_INIT
                    )
                }
                if (candidates.isEmpty()) {
                    throw IOException("Mac rig returned zero regenerated candidates.")
                }
                clearBrushBaseline()
                editGeneration.incrementAndGet()
                _uiState.update { state ->
                    state.copy(
                        document = state.document.copy(
                            candidates = candidates,
                            activeCandidateIndex = 0
                        ),
                        lastSeedRigOnline = true,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                refreshGraphMatches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleApiFailure("Regenerate all failed", e)
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

        activePassJob?.cancel()
        activePassJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isBusy = true, busyLabel = "Swapping phrase...", errorMessage = null)
            }
            try {
                val response = draftApiClient.brushSwap(
                    markedText = markedText,
                    steeringConstraints = state.document.steeringConstraints
                )
                val alternatives = response.alternatives
                if (alternatives.isEmpty()) {
                    throw IOException("Server returned no brush-swap alternatives.")
                }
                if (editGeneration.get() != requestGen) {
                    _uiState.update { it.copy(isBusy = false, busyLabel = null) }
                    return@launch
                }

                brushBaselineDraft = baselineSnapshot
                brushBaselineRange = charRange

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
                lastAppliedBrushRange = appliedRange
                editGeneration.incrementAndGet()

                _uiState.update { cur ->
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
                        lastSeedRigOnline = true,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                scheduleFlashClear(appliedRange)
                refreshGraphMatches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleApiFailure("Brush swap failed", e)
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
        val activeCandidate = _uiState.value.document.activeCandidate ?: return
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

        activePassJob?.cancel()
        activePassJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    densityDeltaPercent = clamped,
                    isBusy = true,
                    busyLabel = if (clamped < 0) "Tightening $clamped%..." else "Expanding +$clamped%...",
                    errorMessage = null
                )
            }
            try {
                val response = draftApiClient.adjustDensity(
                    markedText = markedText,
                    deltaPercent = clamped,
                    steeringConstraints = state.document.steeringConstraints
                )
                if (editGeneration.get() != requestGen) {
                    _uiState.update { it.copy(densityDeltaPercent = 0, isBusy = false, busyLabel = null) }
                    return@launch
                }

                val parity = DraftStateReducer.verifyLockParity(
                    originalClauses = before.clauses,
                    mutatedText = response.text,
                    source = EditSource.DENSITY_SCRUB
                )
                if (!parity.preserved) {
                    _uiState.update {
                        it.copy(
                            densityDeltaPercent = 0,
                            lastSeedRigOnline = true,
                            isBusy = false,
                            busyLabel = null,
                            errorMessage = "Density change rejected: locked words (${parity.missingPhrases.joinToString()}) were altered."
                        )
                    }
                    return@launch
                }

                val updated = DraftStateReducer.applyVerifiedMutation(
                    draft = before,
                    mutatedText = response.text,
                    source = EditSource.DENSITY_SCRUB
                )
                replaceActiveCandidate(updated, dismissBrush = true)
                _uiState.update {
                    it.copy(
                        densityDeltaPercent = 0,
                        lastSeedRigOnline = true,
                        isBusy = false,
                        busyLabel = null
                    )
                }
                refreshGraphMatches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(densityDeltaPercent = 0) }
                handleApiFailure("Density scrub failed", e)
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
        _uiState.update { it.copy(rawKeyboardMode = entering) }
        if (!entering &&
            current.document.stage == Stage.SEED &&
            current.document.seedTranscript.trim().length >= 8
        ) {
            refreshAnglesFromSeed()
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(statusNotice = null) }
    }

    fun resetNewDraft() {
        if (_uiState.value.isAnyRecording) {
            vadAudioEngine.release()
        }
        anglesJob?.cancel()
        activePassJob?.cancel()
        seamHealJob?.cancel()
        flashJob?.cancel()
        clearBrushBaseline()
        editGeneration.incrementAndGet()
        seedBeforeLatch = ""
        openLatchHadIncremental = false

        _uiState.update { state ->
            DraftDeckUiState(
                document = DraftDocument(id = UUID.randomUUID().toString()),
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
                val response = draftApiClient.seamHeal(
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
                        lastSeedRigOnline = true,
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
                        lastSeedRigOnline = if (e is IOException && e !is DraftApiClient.HttpStatusException) false else it.lastSeedRigOnline,
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

    private fun handleApiFailure(prefix: String, error: Throwable) {
        val isTransportFailure = error is IOException && error !is DraftApiClient.HttpStatusException
        _uiState.update { state ->
            state.copy(
                lastSeedRigOnline = if (isTransportFailure) false else state.lastSeedRigOnline,
                isBusy = false,
                busyLabel = null,
                errorMessage = "$prefix: ${error.localizedMessage ?: error::class.java.simpleName}"
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

    companion object {
        private const val FLASH_DURATION_MS: Long = 180L
    }
}
