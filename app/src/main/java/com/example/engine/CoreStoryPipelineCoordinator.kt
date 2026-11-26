package com.example.engine

import com.example.data.AlignedWord
import com.example.data.BrollSegmentMode
import com.example.data.ClipRecord
import com.example.data.CorePipelineSession
import com.example.data.NewsroomLook
import com.example.data.RigQualityMetrics
import com.example.data.gateway.MacRigJobSnapshot
import com.example.data.gateway.RigJobNotFoundException
import com.example.data.gateway.RigTaskHandle
import com.example.data.CorePipelineStage
import com.example.data.CoreStoryClause
import com.example.data.NewsroomStageConfig
import com.example.data.RenderJobStatus
import com.example.data.SavedNewsroomOverlay
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import com.example.data.gateway.MacRigRenderGateway
import com.example.data.local.AircheckLocalRepository
import com.example.data.local.AircheckProfileStore
import com.example.data.local.CasMediaStore
import com.voicestudio.edl.CandidateTake
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.GateMetrics
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.WordToken
import com.voicestudio.sync.DirectSynthesisClauseInput
import com.voicestudio.sync.OtaBundleSyncManager
import com.voicestudio.sync.VoiceStudioSyncCoordinator
import com.voicestudio.ui.Media3BeatExporter
import com.voicestudio.ui.VideoAnchorBinding
import com.voicestudio.verifier.OrtCtcAlignerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class WavInspection(
    val durationMs: Long,
    val sampleRateHz: Int,
    val peaks: List<Float>
)

/**
 * Coordinates the single canonical 5-stage Aircheck Core Pipeline:
 * 1. `TTS` (`01 TTS`) -> Synthesize or capture voice WAV + waveform peaks + clause timings.
 * 2. `ITERATE_STORY` (`02 STORY`) -> Audition TTS, edit/split/add/remove clauses, re-run TTS until locked.
 * 3. `SPEECH_TO_TALKING_HEAD` (`03 HEAD`) -> Match locked TTS WAV (`ttsVoiceSha`) to clean talking-head MP4.
 * 4. `CHOP_INTO_BROLL` (`04 B-ROLL`) -> Chop talking head at clause boundaries into B-roll cutaway windows
 *    (plus 0 or more optional `SavedNewsroomOverlay` objects for news briefs).
 * 5. `FINAL_REVIEW` (`05 REVIEW`) -> Final 9:16 playback, Export to Photos, and Share.
 */
class CoreStoryPipelineCoordinator(
    private val localRepo: AircheckLocalRepository,
    private val casStore: CasMediaStore,
    private val macRigGateway: MacRigRenderGateway,
    private val profileStore: AircheckProfileStore? = null,
    private val otaBundleSyncManager: OtaBundleSyncManager? = null,
    private val voiceStudioCoordinator: VoiceStudioSyncCoordinator? = null,
    private val localCtcAligner: (suspend (File, String) -> List<AlignedWord>)? = null,
    private val onDeviceVoice: OnDeviceVoice? = null
) {

    suspend fun seedSessionFromStory(
        rawStoryText: String,
        headline: String = "",
        scriptId: String = ""
    ): CorePipelineSession = withContext(Dispatchers.IO) {
        val cleanText = rawStoryText.trim()
        val effectiveTitle = headline.trim().ifBlank {
            cleanText.split('.', '!', '?', '\n')
                .firstOrNull()
                .orEmpty()
                .trim()
                .take(40)
                .ifBlank { "Story Draft" }
        }
        val clauses = splitStoryIntoClauses(cleanText)
        val session = CorePipelineSession(
            scriptId = scriptId,
            title = effectiveTitle,
            stage = CorePipelineStage.TTS,
            clauses = clauses,
            selectedOverlayIds = emptyList()
        )
        localRepo.saveCorePipelineSession(session)
        session
    }

    suspend fun updateStoryRawText(
        session: CorePipelineSession,
        rawStoryText: String
    ): CorePipelineSession = withContext(Dispatchers.IO) {
        val updatedClauses = splitStoryIntoClauses(rawStoryText, existing = session.clauses)
        val effectiveTitle = session.title.ifBlank {
            updatedClauses.firstOrNull()?.spokenText?.take(40).orEmpty()
        }
        val updated = session.copy(
            title = effectiveTitle,
            clauses = updatedClauses,
            updatedAtMillis = System.currentTimeMillis()
        )
        localRepo.saveCorePipelineSession(updated)
        updated
    }

    suspend fun updateStoryClauses(
        session: CorePipelineSession,
        updatedClauses: List<CoreStoryClause>
    ): CorePipelineSession = withContext(Dispatchers.IO) {
        val reindexed = updatedClauses.mapIndexed { idx, clause ->
            clause.copy(index = idx)
        }
        val updated = session.copy(
            clauses = reindexed,
            updatedAtMillis = System.currentTimeMillis()
        )
        localRepo.saveCorePipelineSession(updated)
        updated
    }

    suspend fun advanceToStage(
        session: CorePipelineSession,
        targetStage: CorePipelineStage
    ): Result<CorePipelineSession> = withContext(Dispatchers.IO) {
        when (targetStage) {
            CorePipelineStage.TTS -> Unit
            CorePipelineStage.ITERATE_STORY -> {
                if (!session.hasTtsAudio) {
                    return@withContext Result.failure(
                        IllegalStateException("Synthesize or record TTS audio in Stage 1 first.")
                    )
                }
            }
            CorePipelineStage.SPEECH_TO_TALKING_HEAD -> {
                if (!session.hasTtsAudio) {
                    return@withContext Result.failure(
                        IllegalStateException("Synthesize TTS audio before matching speech to talking head.")
                    )
                }
                if (session.clauses.none { it.spokenText.isNotBlank() }) {
                    return@withContext Result.failure(
                        IllegalStateException("Add at least one spoken story clause before Stage 3.")
                    )
                }
            }
            CorePipelineStage.CHOP_INTO_BROLL -> {
                if (!session.hasTalkingHead) {
                    return@withContext Result.failure(
                        IllegalStateException("Match speech to talking head in Stage 3 before chopping into B-roll.")
                    )
                }
            }
            CorePipelineStage.FINAL_REVIEW -> {
                if (!session.hasFinalVideo || session.finalClipId.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Chop talking head into B-roll in Stage 4 before Final Review.")
                    )
                }
            }
        }
        val updated = session.copy(
            stage = targetStage,
            updatedAtMillis = System.currentTimeMillis()
        )
        localRepo.saveCorePipelineSession(updated)
        Result.success(updated)
    }

    /**
     * Stage 1 (`01 TTS`) & Stage 2 (`02 STORY` re-run):
     * Synthesizes spoken voice WAV from `session.clauses` (either from a real on-phone recorded WAV
     * or via Mac Studio `POST /v1/core/tts`), stores the WAV in `CasMediaStore`, updates clause
     * millisecond timings + 64-bin waveform peaks, increments `ttsIterationCount`, and transitions
     * to `CorePipelineStage.ITERATE_STORY`.
     */
    suspend fun synthesizeStage1Tts(
        session: CorePipelineSession,
        rigBaseUrl: String,
        localMicWavFile: File? = null,
        task: RigTaskHandle? = null
    ): Result<CorePipelineSession> = withContext(Dispatchers.IO) {
        try {
            val activeClauses = session.clauses
                .map { it.copy(spokenText = it.spokenText.trim()) }
                .filter { it.spokenText.isNotBlank() }
                .mapIndexed { idx, c -> c.copy(index = idx) }
            if (activeClauses.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Enter story text before running TTS.")
                )
            }

            val effectiveProjectId = session.scriptId.ifBlank { session.id }
            // Voice priority: a recorded mic take, then Ryan's run9 voice on this phone, then the Mac studio.
            val onDeviceWav: File? = if (localMicWavFile == null && onDeviceVoice?.isInstalled() == true) {
                onDeviceVoice.synthesize(activeClauses.joinToString(" ") { it.spokenText }) { msg ->
                    task?.onProgress?.invoke(msg)
                }
            } else {
                null
            }
            val localWav = localMicWavFile ?: onDeviceWav
            val updated = if (localWav != null && localWav.exists() && localWav.length() > 44L) {
                val stored = casStore.putFile(localWav, "wav")
                if (onDeviceWav != null) onDeviceWav.delete()
                val wavInfo = parseWavDurationAndPeaks(stored.file)
                val fullText = activeClauses.joinToString(" ") { it.spokenText }
                val micAlignedWords = runLocalCtcAlignment(stored.file, fullText)
                val timedClauses = distributeClauseTimings(activeClauses, wavInfo.durationMs)
                clearStaleLocalPcmManifest(effectiveProjectId)
                session.copy(
                    title = session.title.ifBlank { timedClauses.first().spokenText.take(40) },
                    stage = CorePipelineStage.ITERATE_STORY,
                    clauses = timedClauses,
                    voicedClauseTexts = timedClauses.map { it.spokenText.trim() },
                    ttsVoiceSha = stored.sha256,
                    ttsAudioUrl = null,
                    ttsDurationMs = wavInfo.durationMs,
                    waveformPeaks = wavInfo.peaks,
                    ttsIterationCount = session.ttsIterationCount + 1,
                    talkingHeadJobId = null,
                    pendingHeadJobId = null,
                    pendingRigTasks = emptyMap(),
                    talkingHeadSha = null,
                    talkingHeadVideoUrl = null,
                    alignedWords = micAlignedWords,
                    alignUrl = null,
                    ttsMetrics = null,
                    headMetrics = null,
                    ttsSource = if (onDeviceWav != null) TTS_SOURCE_ON_DEVICE_RUN9 else TTS_SOURCE_MIC,
                    silenceGaps = emptyList(),
                    silenceGapsJobId = null,
                    finalClipId = null,
                    finalVideoSha = null,
                    finalVideoUrl = null,
                    lastCutStats = null,
                    lastCutSignature = null,
                    updatedAtMillis = System.currentTimeMillis()
                )
            } else {
                // Run9 voice isn't installed on this phone: voice the script on the Mac studio. When the
                // Mac is unreachable the user gets a plain "Mac offline" message, not a raw stack error.
                val health = runCatching { macRigGateway.resolveAndCheckHealth(rigBaseUrl) }.getOrNull()
                    ?: return@withContext Result.failure(MacStudioOfflineException())
                val ttsRes = macRigGateway.synthesizeCoreStoryTts(
                    rigBaseUrl = health.resolvedUrl,
                    projectId = effectiveProjectId,
                    clauses = activeClauses,
                    task = task
                )
                val storedWav = macRigGateway.downloadAudioWavToCas(ttsRes.audioUrl)
                val mergedClauses = activeClauses.mapIndexed { idx, existing ->
                    val timed = ttsRes.timedClauses.getOrNull(idx)
                    if (timed != null) {
                        existing.copy(
                            index = idx,
                            startMs = timed.startMs,
                            endMs = timed.endMs
                        )
                    } else {
                        existing.copy(index = idx)
                    }
                }.let { list ->
                    if (list.all { it.endMs <= 0L } && ttsRes.durationMs > 0L) {
                        distributeClauseTimings(list, ttsRes.durationMs)
                    } else {
                        list
                    }
                }
                val peaks = if (ttsRes.waveformPeaks.isNotEmpty()) {
                    ttsRes.waveformPeaks
                } else {
                    parseWavDurationAndPeaks(storedWav.file).peaks
                }
                val fullText = mergedClauses.joinToString(" ") { it.spokenText }
                val remoteAlignedWords = when {
                    ttsRes.words.isNotEmpty() -> ttsRes.words
                    !ttsRes.alignUrl.isNullOrBlank() ->
                        runCatching { macRigGateway.fetchAlignedWords(ttsRes.alignUrl) }
                            .getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                            ?: runLocalCtcAlignment(storedWav.file, fullText)
                    else -> runLocalCtcAlignment(storedWav.file, fullText)
                }
                clearStaleLocalPcmManifest(effectiveProjectId)
                session.copy(
                    title = session.title.ifBlank { mergedClauses.first().spokenText.take(40) },
                    stage = CorePipelineStage.ITERATE_STORY,
                    clauses = mergedClauses,
                    voicedClauseTexts = mergedClauses.map { it.spokenText.trim() },
                    ttsVoiceSha = storedWav.sha256,
                    ttsAudioUrl = ttsRes.audioUrl,
                    ttsDurationMs = ttsRes.durationMs,
                    waveformPeaks = peaks,
                    ttsIterationCount = session.ttsIterationCount + 1,
                    talkingHeadJobId = null,
                    pendingHeadJobId = null,
                    pendingRigTasks = emptyMap(),
                    talkingHeadSha = null,
                    talkingHeadVideoUrl = null,
                    alignedWords = remoteAlignedWords,
                    alignUrl = ttsRes.alignUrl,
                    ttsMetrics = ttsRes.metrics,
                    headMetrics = null,
                    ttsSource = TTS_SOURCE_REMOTE_RIG,
                    silenceGaps = emptyList(),
                    silenceGapsJobId = null,
                    finalClipId = null,
                    finalVideoSha = null,
                    finalVideoUrl = null,
                    lastCutStats = null,
                    lastCutSignature = null,
                    updatedAtMillis = System.currentTimeMillis()
                )
            }

            localRepo.saveCorePipelineSession(updated)
            Result.success(updated)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Stage 3 (`03 HEAD`):
     * Matches the locked Stage 1/2 TTS WAV (`session.ttsVoiceSha`) to a clean un-burned talking-head
     * video (`VoiceSource.MIC_VOICEOVER` + `masterAudioSha = ttsVoiceSha`, zero premature newsroom
     * chyron burn-in), downloads the MP4 into `CasMediaStore`, locks sample-accurate clause cut
     * windows through `:ui-clipper`'s `Media3BeatExporter`, and advances to `CHOP_INTO_BROLL`.
     */
    suspend fun matchSpeechToTalkingHeadStage3(
        session: CorePipelineSession,
        rigBaseUrl: String,
        photoSha: String?,
        pollIntervalMs: Long = 350L,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<CorePipelineSession> = withContext(Dispatchers.IO) {
        try {
            val lockedVoiceSha = session.ttsVoiceSha
            if (lockedVoiceSha.isNullOrBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Lock TTS audio in Stages 1–2 before matching speech to talking head.")
                )
            }
            val activeClauses = session.clauses
                .filter { it.spokenText.isNotBlank() }
                .mapIndexed { idx, c -> c.copy(index = idx) }
            if (activeClauses.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Story has no spoken clauses to match.")
                )
            }

            onProgress(0.06f, "Connecting to studio rig…")
            val health = macRigGateway.resolveAndCheckHealth(rigBaseUrl)
            val effectiveProjectId = session.scriptId.ifBlank { session.id }
            val effectiveTitle = session.title.ifBlank {
                activeClauses.first().spokenText.take(40)
            }
            val cleanHeadProject = ScriptProject(
                id = effectiveProjectId,
                title = effectiveTitle,
                ideaSpark = activeClauses.joinToString(" ") { it.spokenText },
                voiceSource = VoiceSource.MIC_VOICEOVER,
                masterAudioSha = lockedVoiceSha,
                stageConfig = NewsroomStageConfig(
                    photoSha = photoSha,
                    chyronHeadline = "",
                    chyronSubline = "",
                    showBrandBug = false
                ),
                beats = activeClauses.mapIndexed { idx, clause ->
                    ScriptBeat(
                        id = clause.id,
                        index = idx,
                        spokenText = clause.spokenText,
                        durationSec = max(3, ((clause.durationMs + 999L) / 1000L).toInt())
                    )
                }
            )

            val targetMaxSec = max(4, ((session.ttsDurationMs + 999L) / 1000L).toInt()).coerceIn(4, 75)
            // Resume a rig render that a previous (killed) process already started instead of paying twice.
            var headJobId = session.pendingHeadJobId?.takeIf { it.isNotBlank() }
            var resubmitted = false
            val interval = pollIntervalMs.coerceAtLeast(25L)
            lateinit var snapshot: MacRigJobSnapshot

            while (true) {
                if (headJobId == null) {
                    headJobId = macRigGateway.enqueueMacRigRender(
                        rigBaseUrl = health.resolvedUrl,
                        project = cleanHeadProject,
                        photoSha = photoSha,
                        resolution = "1080P",
                        ratio = "9:16",
                        maxDurationSec = targetMaxSec,
                        words = session.alignedWords,
                        alignUrl = session.alignUrl,
                        brollPlan = activeClauses,
                        brollAspect = session.renderOptions.aspect
                    ).jobId
                    localRepo.saveCorePipelineSession(session.copy(pendingHeadJobId = headJobId))
                }

                onProgress(0.15f, "Matching speech to talking head…")
                var consecutiveErrors = 0
                var needsResubmit = false

                while (true) {
                    val poll = runCatching {
                        macRigGateway.pollMacRigJob(health.resolvedUrl, headJobId!!)
                    }
                    if (poll.isSuccess) {
                        consecutiveErrors = 0
                        val current = poll.getOrThrow()
                        if (!current.status.isTerminal) {
                            onProgress(
                                current.progressFraction.coerceIn(0.15f, 0.92f),
                                current.stageDetail.ifBlank { "Matching speech to talking head…" }
                            )
                            delay(interval)
                            continue
                        }
                        if (current.status == RenderJobStatus.FAILED &&
                            current.errorKind == "rig_restarted" &&
                            !resubmitted
                        ) {
                            localRepo.saveCorePipelineSession(session.copy(pendingHeadJobId = null))
                            needsResubmit = true
                            break
                        }
                        snapshot = current
                        break
                    } else {
                        val err = poll.exceptionOrNull()
                        if (err is CancellationException) throw err
                        if (err is RigJobNotFoundException) {
                            localRepo.saveCorePipelineSession(session.copy(pendingHeadJobId = null))
                            if (!resubmitted) {
                                needsResubmit = true
                                break
                            }
                            return@withContext Result.failure(err)
                        }
                        consecutiveErrors++
                        if (consecutiveErrors >= 5) {
                            throw IllegalStateException(
                                err?.localizedMessage ?: "Lost connection while rendering talking head."
                            )
                        }
                        delay(interval)
                    }
                }

                if (needsResubmit) {
                    resubmitted = true
                    headJobId = null
                    continue
                }
                break
            }

            if (snapshot.status == RenderJobStatus.FAILED) {
                localRepo.saveCorePipelineSession(session.copy(pendingHeadJobId = null))
                return@withContext Result.failure(
                    IllegalStateException(snapshot.errorMessage ?: "Talking-head synthesis failed.")
                )
            }
            val headVideoUrl = snapshot.videoUrl
            if (headVideoUrl.isNullOrBlank()) {
                localRepo.saveCorePipelineSession(session.copy(pendingHeadJobId = null))
                return@withContext Result.failure(
                    IllegalStateException("Talking-head render finished without a video URL.")
                )
            }

            onProgress(0.95f, "Caching speech-matched talking head…")
            val storedHead = macRigGateway.downloadRenderedMp4ToCas(headVideoUrl)
            val effectiveDurationMs = when {
                snapshot.durationMs > 0L -> snapshot.durationMs
                session.ttsDurationMs > 0L -> session.ttsDurationMs
                else -> 4_000L
            }

            val timedClauses = if (activeClauses.all { it.endMs > it.startMs }) {
                activeClauses
            } else {
                distributeClauseTimings(activeClauses, effectiveDurationMs)
            }

            val (beatLockedClauses, alignedWords) = lockClauseCutWindowsWithMedia3Exporter(
                projectId = effectiveProjectId,
                session = session,
                clauses = timedClauses,
                snapshot = snapshot,
                talkingHeadFile = storedHead.file,
                totalDurationMs = effectiveDurationMs
            )

            // Pre-stage clause index 1 as B-Roll Cutaway when >= 2 clauses exist and none have been
            // customized yet (including no explicit SPARK/SCRIPT shotPlan), giving an immediate
            // Talking Head -> B-Roll -> Talking Head cut list.
            val brollReadyClauses = if (
                beatLockedClauses.size >= 2 &&
                beatLockedClauses.all {
                    it.segmentMode == BrollSegmentMode.TALKING_HEAD && it.shotTypeWire.isEmpty()
                }
            ) {
                beatLockedClauses.mapIndexed { idx, clause ->
                    if (idx == 1) {
                        clause.copy(
                            segmentMode = BrollSegmentMode.BROLL_CUTAWAY,
                            brollCaption = clause.brollCaption.ifBlank { clause.spokenText.take(48) }
                        )
                    } else {
                        clause
                    }
                }
            } else {
                beatLockedClauses
            }

            val updated = session.copy(
                scriptId = cleanHeadProject.id,
                title = effectiveTitle,
                stage = CorePipelineStage.CHOP_INTO_BROLL,
                clauses = brollReadyClauses,
                talkingHeadJobId = headJobId,
                pendingHeadJobId = null,
                talkingHeadSha = storedHead.sha256,
                talkingHeadVideoUrl = headVideoUrl,
                alignedWords = alignedWords,
                alignUrl = snapshot.alignUrl ?: session.alignUrl,
                headMetrics = snapshot.metrics ?: session.headMetrics,
                updatedAtMillis = System.currentTimeMillis()
            )
            localRepo.saveCorePipelineSession(updated)
            onProgress(1f, "Talking head ready for B-roll chop.")
            Result.success(updated)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Stage 4 (`04 B-ROLL`):
     * Chops the speech-matched talking head at clause boundaries into alternating Talking Head and
     * B-Roll Cutaway windows while preserving continuous speech audio, optionally compositing 0 or
     * more user-selected `SavedNewsroomOverlay` objects (empty by default for standard stories).
     * Produces the finished `ClipRecord` and advances to `CorePipelineStage.FINAL_REVIEW`.
     */
    suspend fun chopTalkingHeadIntoBrollStage4(
        session: CorePipelineSession,
        rigBaseUrl: String,
        allSavedOverlays: List<SavedNewsroomOverlay>,
        photoSha: String? = null,
        look: NewsroomLook? = null,
        task: RigTaskHandle? = null
    ): Result<Pair<CorePipelineSession, ClipRecord>> = withContext(Dispatchers.IO) {
        try {
            if (!session.hasTalkingHead) {
                return@withContext Result.failure(
                    IllegalStateException("Complete Stage 3 (Speech Matched to Talking Head) first.")
                )
            }
            val activeClauses = session.clauses
                .filter { it.spokenText.isNotBlank() }
                .mapIndexed { idx, c -> c.copy(index = idx) }
            if (activeClauses.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Story has no clauses to chop.")
                )
            }

            val attachedOverlays = allSavedOverlays.filter { it.id in session.selectedOverlayIds }
            val health = macRigGateway.resolveAndCheckHealth(rigBaseUrl)
            val settings = runCatching { profileStore?.current() }.getOrNull()
            val effectiveUseRemotion = session.renderOptions.useRemotion
                ?: (settings?.providers?.packageProviderId == "mac_rig_remotion")
            val effectiveTitle = session.title.ifBlank {
                activeClauses.first().spokenText.take(40)
            }

            val chopRes = macRigGateway.chopTalkingHeadIntoBroll(
                rigBaseUrl = health.resolvedUrl,
                scriptId = session.scriptId.ifBlank { session.id },
                title = effectiveTitle,
                sourceJobId = session.talkingHeadJobId,
                sourceMp4Sha = session.talkingHeadSha,
                clauses = activeClauses,
                overlays = attachedOverlays,
                renderOptions = session.renderOptions,
                look = look,
                task = task,
                alignedWords = session.alignedWords,
                alignUrl = session.alignUrl,
                useRemotion = effectiveUseRemotion
            )
            val storedFinal = macRigGateway.downloadRenderedMp4ToCas(chopRes.videoUrl)

            val now = System.currentTimeMillis()
            val primaryOverlay = attachedOverlays.firstOrNull()
            val clipId = "clip_${chopRes.jobId}"
            val clip = ClipRecord(
                id = clipId,
                scriptId = session.scriptId,
                jobId = chopRes.jobId,
                title = effectiveTitle,
                photoSha = photoSha,
                voiceSha = session.ttsVoiceSha,
                alignSha = null,
                lipsyncSha = session.talkingHeadSha,
                finalSha = storedFinal.sha256,
                status = RenderJobStatus.READY,
                progressFraction = 1f,
                stageDetail = "Ready",
                errorMessage = null,
                durationMs = if (chopRes.durationMs > 0L) chopRes.durationMs else session.ttsDurationMs,
                waveformPeaks = session.waveformPeaks,
                thumbHash = null,
                alignedWords = session.alignedWords,
                chyronHeadline = primaryOverlay?.headline.orEmpty(),
                chyronSubline = primaryOverlay?.subline.orEmpty(),
                remoteLipsyncUrl = session.talkingHeadVideoUrl,
                remoteFinalUrl = chopRes.videoUrl,
                exportedUri = null,
                createdAtMillis = now,
                updatedAtMillis = now
            )
            localRepo.upsertClip(clip)

            val updatedSession = session.copy(
                title = effectiveTitle,
                stage = CorePipelineStage.FINAL_REVIEW,
                clauses = activeClauses,
                finalClipId = clip.id,
                finalVideoSha = storedFinal.sha256,
                finalVideoUrl = chopRes.videoUrl,
                lastCutStats = chopRes.studioStats,
                updatedAtMillis = now
            )
            localRepo.saveCorePipelineSession(updatedSession)
            Result.success(updatedSession to clip)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private suspend fun runLocalCtcAlignment(
        wavFile: File,
        expectedText: String
    ): List<AlignedWord> {
        if (expectedText.isBlank()) return emptyList()
        val aligner = localCtcAligner ?: return emptyList()
        return runCatching { aligner.invoke(wavFile, expectedText) }.getOrDefault(emptyList())
    }

    private suspend fun clearStaleLocalPcmManifest(projectId: String) {
        val existing = localRepo.getProjectManifest(projectId) ?: return
        val hasPcm = existing.clauses.any { c ->
            c.selectedCandidate?.pcmFilePath.orEmpty().endsWith(".pcm", ignoreCase = true)
        }
        if (hasPcm) {
            localRepo.saveProjectManifest(
                VoiceProjectManifest(
                    projectId = projectId,
                    sampleRate = SYNTHESIS_SAMPLE_RATE_HZ,
                    globalGapScale = 1.0f,
                    activeLoraVersion = 0,
                    clauses = emptyList()
                )
            )
        }
    }

    private suspend fun lockClauseCutWindowsWithMedia3Exporter(
        projectId: String,
        session: CorePipelineSession,
        clauses: List<CoreStoryClause>,
        snapshot: MacRigJobSnapshot,
        talkingHeadFile: File,
        totalDurationMs: Long
    ): Pair<List<CoreStoryClause>, List<AlignedWord>> {
        val sampleRate = SYNTHESIS_SAMPLE_RATE_HZ
        // Priority: (1) snapshot.words -> (2) fetch snapshot.alignUrl -> (3) session.alignedWords
        // -> (4) localCtcAligner on ttsVoiceSha WAV -> (5) emptyList() (never uniform split).
        val resolvedWords: List<AlignedWord> = when {
            snapshot.words.isNotEmpty() -> snapshot.words
            !snapshot.alignUrl.isNullOrBlank() ->
                runCatching { macRigGateway.fetchAlignedWords(snapshot.alignUrl) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: session.alignedWords
            session.alignedWords.isNotEmpty() -> session.alignedWords
            else -> {
                val wavFile = session.ttsVoiceSha?.let { sha -> casStore.fileForSha(sha, "wav") }
                if (wavFile != null && wavFile.isFile) {
                    runLocalCtcAlignment(wavFile, clauses.joinToString(" ") { it.spokenText })
                } else {
                    emptyList()
                }
            }
        }

        val existingManifest = localRepo.getProjectManifest(projectId)
        val isLocalV002Session = session.ttsSource == TTS_SOURCE_LOCAL_V002 ||
            (session.ttsSource.isBlank() && session.ttsAudioUrl == null && session.ttsMetrics != null)
        val localPcmManifest = existingManifest?.takeIf { manifest ->
            isLocalV002Session &&
                manifest.clauses.isNotEmpty() &&
                clauses.all { c ->
                    manifest.clauses.any { node ->
                        val baseId = node.clauseId.replace(SUB_CLAUSE_SUFFIX_REGEX, "")
                        node.clauseId == c.id || baseId == c.id
                    }
                } &&
                manifest.clauses.any { c ->
                    c.selectedCandidate?.pcmFilePath.orEmpty().endsWith(".pcm", ignoreCase = true)
                }
        }

        if (localPcmManifest != null) {
            val headUri = talkingHeadFile.toURI().toString()
            val anchoredParents = localPcmManifest.clauses.flatMap { clauseNode ->
                val tokens = clauseNode.selectedCandidate?.tokens.orEmpty()
                val explicitAnchors = tokens.mapNotNull { it.videoAnchorId?.takeIf(String::isNotBlank) }
                if (explicitAnchors.isNotEmpty()) {
                    explicitAnchors + clauseNode.clauseId.replace(SUB_CLAUSE_SUFFIX_REGEX, "")
                } else {
                    emptyList()
                }
            }.toMutableSet()

            var changed = false
            val updatedClauses = localPcmManifest.clauses.map { clauseNode ->
                val selIdx = clauseNode.selectedTakeIndex
                val winner = clauseNode.candidates.getOrNull(selIdx)
                val parentId = clauseNode.clauseId.replace(SUB_CLAUSE_SUFFIX_REGEX, "")
                val isContinuationSubClause = parentId != clauseNode.clauseId &&
                    !clauseNode.clauseId.endsWith("_p1")
                val hasExistingAnchor = winner?.tokens.orEmpty().any { !it.videoAnchorId.isNullOrBlank() }
                if (winner != null &&
                    winner.tokens.isNotEmpty() &&
                    !hasExistingAnchor &&
                    !isContinuationSubClause &&
                    anchoredParents.add(parentId)
                ) {
                    changed = true
                    val boundTokens = winner.tokens.mapIndexed { tIdx, tok ->
                        if (tIdx == 0) tok.copy(videoAnchorId = parentId) else tok
                    }
                    val updatedWinner = winner.copy(tokens = boundTokens)
                    clauseNode.copy(
                        candidates = clauseNode.candidates.mapIndexed { cIdx, cand ->
                            if (cIdx == selIdx) updatedWinner else cand
                        }
                    )
                } else {
                    clauseNode
                }
            }
            val manifestForExport = if (changed) {
                localPcmManifest.copy(clauses = updatedClauses).also { localRepo.saveProjectManifest(it) }
            } else {
                localPcmManifest
            }
            val allAnchorIds = (
                manifestForExport.clauses.flatMap { it.selectedCandidate?.tokens.orEmpty() }
                    .mapNotNull { it.videoAnchorId?.takeIf(String::isNotBlank) } +
                    manifestForExport.clauses.map { it.clauseId }
                ).distinct()
            val bindings = allAnchorIds.associateWith {
                VideoAnchorBinding(videoAnchorId = it, videoUri = headUri)
            }
            val specsByClauseId = Media3BeatExporter.buildMedia3ClippingSpecs(manifestForExport, bindings)
                .associateBy { it.videoAnchorId }
            val lockedClauses = clauses.mapIndexed { idx, clause ->
                val spec = specsByClauseId[clause.id]
                if (spec != null) {
                    val lockedStartMs = spec.startPositionUs / 1_000L
                    val lockedEndMs = if (idx == clauses.lastIndex) {
                        max(totalDurationMs, spec.endPositionUs / 1_000L)
                    } else {
                        max(lockedStartMs + 200L, spec.endPositionUs / 1_000L)
                    }
                    clause.copy(startMs = lockedStartMs, endMs = lockedEndMs)
                } else {
                    clause
                }
            }
            return lockedClauses to resolvedWords
        }

        val metricsSource = snapshot.metrics ?: session.ttsMetrics
        val realGateMetrics = if (metricsSource != null) {
            GateMetrics(
                cer = (metricsSource.cer ?: 0.0).toFloat(),
                maxPhonemeDurationMs = (metricsSource.maxPhonemeDurationMs ?: 0.0).toFloat(),
                speakerCosineSim = (metricsSource.wespeakerCosine ?: 0.0).toFloat(),
                dnsmosP808 = (metricsSource.dnsmosP808 ?: 0.0).toFloat(),
                tailRmsDb = (metricsSource.tailRmsDbfs ?: 0.0).toFloat(),
                syllablesPerSec = (metricsSource.syllablesPerSec ?: 0.0).toFloat(),
                passedAllGates = metricsSource.passedAllStrictGates ?: false
            )
        } else {
            GateMetrics(
                cer = 0f,
                maxPhonemeDurationMs = 0f,
                speakerCosineSim = 0f,
                dnsmosP808 = 0f,
                tailRmsDb = 0f,
                syllablesPerSec = 0f,
                passedAllGates = false
            )
        }

        val manifestClauses = clauses.mapIndexed { idx, clause ->
            val startMs = clause.startMs.coerceAtLeast(0L)
            val endMs = clause.endMs.coerceAtLeast(startMs + 200L)
            val durMs = (endMs - startMs).coerceAtLeast(200L)
            val clauseWords = if (resolvedWords.isEmpty()) {
                emptyList()
            } else {
                resolvedWords.filter { w ->
                    if (idx == clauses.lastIndex) {
                        w.startMs >= startMs
                    } else {
                        w.startMs in startMs until endMs
                    }
                }
            }
            val wordTokens = clauseWords.mapIndexed { wIdx, w ->
                val localStartSample = (((w.startMs - startMs).coerceAtLeast(0L)) * sampleRate) / 1_000L
                val localEndSample = max(
                    localStartSample + 1L,
                    (((w.endMs - startMs).coerceAtLeast(1L)) * sampleRate) / 1_000L
                )
                WordToken(
                    word = w.word,
                    startSample = localStartSample,
                    endSample = localEndSample,
                    zeroCrossIn = localStartSample,
                    zeroCrossOut = localEndSample,
                    f0PeakSample = (localStartSample + localEndSample) / 2L,
                    videoAnchorId = if (wIdx == 0) clause.id else null
                )
            }
            val clauseSamples = max(1L, (durMs * sampleRate) / 1_000L)
            ClauseNode(
                clauseId = clause.id,
                index = idx,
                rawText = clause.spokenText,
                normalizedPhonemes = clause.spokenText.lowercase(),
                selectedTakeIndex = 0,
                baseGapAfterSamples = 0,
                candidates = listOf(
                    CandidateTake(
                        takeId = "take_${clause.id}",
                        pcmFilePath = "",
                        totalSamples = clauseSamples,
                        discreteAudioTokens = IntArray(0),
                        seed = idx.toLong(),
                        temperature = 0.7f,
                        metrics = realGateMetrics,
                        tokens = wordTokens
                    )
                )
            )
        }

        val manifest = VoiceProjectManifest(
            projectId = projectId,
            sampleRate = sampleRate,
            globalGapScale = 1.0f,
            activeLoraVersion = 0,
            clauses = manifestClauses
        )
        localRepo.saveProjectManifest(manifest)

        // Non-EDL (remote :8443 or mic WAV): talking-head MP4 was rendered from the continuous
        // master WAV including pauses, so preserve exact continuous clause startMs/endMs rather
        // than compressing leading/trailing silence via Media3BeatExporter.
        return clauses to resolvedWords
    }

    companion object {
        const val TTS_SOURCE_LOCAL_V002: String = "local_v002"
        const val TTS_SOURCE_REMOTE_RIG: String = "remote_rig"
        const val TTS_SOURCE_MIC: String = "mic"
        const val TTS_SOURCE_ON_DEVICE_RUN9: String = "on_device_run9"
        private val SUB_CLAUSE_SUFFIX_REGEX = Regex("_p\\d+$")
        fun splitStoryIntoClauses(
            rawText: String,
            existing: List<CoreStoryClause> = emptyList()
        ): List<CoreStoryClause> {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return emptyList()
            val rawParts = trimmed
                .split(Regex("(?<=[.!?])\\s+|\\n+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (rawParts.isEmpty()) return emptyList()

            return rawParts.mapIndexed { idx, sentence ->
                val matched = existing.firstOrNull {
                    it.spokenText.equals(sentence, ignoreCase = true)
                } ?: existing.getOrNull(idx)
                if (matched != null) {
                    matched.copy(
                        index = idx,
                        spokenText = sentence
                    )
                } else {
                    CoreStoryClause(
                        index = idx,
                        spokenText = sentence,
                        segmentMode = BrollSegmentMode.TALKING_HEAD
                    )
                }
            }
        }

        fun distributeClauseTimings(
            clauses: List<CoreStoryClause>,
            totalDurationMs: Long
        ): List<CoreStoryClause> {
            if (clauses.isEmpty()) return emptyList()
            val safeTotalMs = max(totalDurationMs, clauses.size * 600L)
            val weights = clauses.map { c ->
                max(1, c.spokenText.trim().split(Regex("\\s+")).count { it.isNotBlank() })
            }
            val weightSum = max(1, weights.sum())
            var cursorMs = 0L
            return clauses.mapIndexed { idx, clause ->
                val startMs = cursorMs
                val endMs = if (idx == clauses.lastIndex) {
                    safeTotalMs
                } else {
                    val share = (safeTotalMs * weights[idx]) / weightSum
                    min(safeTotalMs, startMs + max(300L, share))
                }
                cursorMs = endMs
                clause.copy(
                    index = idx,
                    startMs = startMs,
                    endMs = max(startMs + 200L, endMs)
                )
            }
        }

        fun parseWavDurationAndPeaks(wavFile: File, binCount: Int = 64): WavInspection {
            val bytes = wavFile.readBytes()
            if (bytes.size < 44) {
                return WavInspection(durationMs = 0L, sampleRateHz = 16_000, peaks = emptyList())
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var sampleRate = 16_000
            var channels = 1
            var bitsPerSample = 16
            var dataOffset = 44
            var dataSize = bytes.size - 44

            if (
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
            ) {
                var pos = 12
                while (pos + 8 <= bytes.size) {
                    val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
                    val chunkSize = buf.getInt(pos + 4).coerceAtLeast(0)
                    val bodyStart = pos + 8
                    if (chunkId == "fmt " && bodyStart + 16 <= bytes.size) {
                        channels = buf.getShort(bodyStart + 2).toInt().coerceAtLeast(1)
                        sampleRate = buf.getInt(bodyStart + 4).coerceAtLeast(8_000)
                        bitsPerSample = buf.getShort(bodyStart + 14).toInt().coerceAtLeast(8)
                    } else if (chunkId == "data") {
                        dataOffset = bodyStart
                        dataSize = min(chunkSize, bytes.size - bodyStart).coerceAtLeast(0)
                        break
                    }
                    val step = 8 + chunkSize + (chunkSize and 1)
                    if (step <= 0) break
                    pos += step
                }
            }

            val bytesPerSample = max(1, (bitsPerSample / 8) * channels)
            val totalFrames = dataSize / bytesPerSample
            val durationMs = if (sampleRate > 0) (totalFrames * 1_000L) / sampleRate else 0L

            if (bitsPerSample != 16 || dataSize < 2 || binCount <= 0) {
                return WavInspection(
                    durationMs = durationMs,
                    sampleRateHz = sampleRate,
                    peaks = emptyList()
                )
            }

            val totalShorts = dataSize / 2
            val shortsPerBin = max(1, totalShorts / binCount)
            val rawPeaks = FloatArray(binCount)
            var maxObserved = 0f
            for (b in 0 until binCount) {
                val startShort = b * shortsPerBin
                if (startShort >= totalShorts) break
                val endShort = if (b == binCount - 1) totalShorts else min(totalShorts, startShort + shortsPerBin)
                var peak = 0f
                for (s in startShort until endShort) {
                    val byteIdx = dataOffset + s * 2
                    if (byteIdx + 2 <= bytes.size) {
                        val sample = abs(buf.getShort(byteIdx).toInt()) / 32768f
                        if (sample > peak) peak = sample
                    }
                }
                rawPeaks[b] = peak
                if (peak > maxObserved) maxObserved = peak
            }
            val scale = if (maxObserved > 0.001f) maxObserved else 1f
            val normalized = rawPeaks.map { (it / scale).coerceIn(0.04f, 1f) }
            return WavInspection(
                durationMs = durationMs,
                sampleRateHz = sampleRate,
                peaks = normalized
            )
        }

        fun writeFloat32PcmAs16BitMonoWav(
            pcm44k: FloatArray,
            outFile: File,
            sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ
        ) {
            val numSamples = pcm44k.size
            val dataSize = numSamples * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(1) // Mono
                putInt(sampleRateHz)
                putInt(sampleRateHz * 2)
                putShort(2) // Block align
                putShort(16) // Bits per sample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }
            val pcmBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcm44k) {
                val finite = if (sample.isFinite()) sample else 0f
                val clamped = finite.coerceIn(-1.0f, 1.0f)
                val s = (clamped * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
                pcmBuf.putShort(s)
            }
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                fos.write(header.array())
                fos.write(pcmBuf.array())
            }
        }

        fun readWavAsFloat44kMono(wavFile: File): FloatArray? {
            if (!wavFile.isFile || wavFile.length() <= 44L) return null
            val bytes = wavFile.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (
                bytes[0] != 'R'.code.toByte() ||
                bytes[1] != 'I'.code.toByte() ||
                bytes[2] != 'F'.code.toByte() ||
                bytes[3] != 'F'.code.toByte()
            ) {
                return null
            }
            var audioFormat = 1
            var channels = 1
            var sampleRate = SYNTHESIS_SAMPLE_RATE_HZ
            var bitsPerSample = 16
            var dataOffset = -1
            var dataSize = 0
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
                val chunkSize = buf.getInt(pos + 4).coerceAtLeast(0)
                val bodyStart = pos + 8
                if (chunkId == "fmt " && bodyStart + 16 <= bytes.size) {
                    audioFormat = buf.getShort(bodyStart).toInt() and 0xFFFF
                    channels = buf.getShort(bodyStart + 2).toInt().coerceAtLeast(1)
                    sampleRate = buf.getInt(bodyStart + 4).coerceAtLeast(8_000)
                    bitsPerSample = buf.getShort(bodyStart + 14).toInt().coerceAtLeast(8)
                } else if (chunkId == "data") {
                    dataOffset = bodyStart
                    dataSize = min(chunkSize, bytes.size - bodyStart).coerceAtLeast(0)
                    break
                }
                val step = 8 + chunkSize + (chunkSize and 1)
                if (step <= 0) break
                pos += step
            }
            if (dataOffset < 0 || dataSize <= 0) return null
            val bytesPerFrame = (bitsPerSample / 8) * channels
            if (bytesPerFrame <= 0) return null
            val frameCount = dataSize / bytesPerFrame
            if (frameCount <= 0) return null

            val mono = FloatArray(frameCount)
            for (frame in 0 until frameCount) {
                var sum = 0f
                for (ch in 0 until channels) {
                    val off = dataOffset + frame * bytesPerFrame + ch * (bitsPerSample / 8)
                    if (off + (bitsPerSample / 8) > bytes.size) break
                    val v = when {
                        bitsPerSample == 16 && audioFormat == 1 -> buf.getShort(off).toFloat() / 32768f
                        bitsPerSample == 32 && audioFormat == 3 -> {
                            val raw = buf.getFloat(off)
                            if (raw.isFinite()) raw.coerceIn(-1f, 1f) else 0f
                        }
                        bitsPerSample == 32 && audioFormat == 1 -> buf.getInt(off).toFloat() / 2147483648f
                        else -> return null
                    }
                    sum += v
                }
                mono[frame] = sum / channels.toFloat()
            }
            if (sampleRate == SYNTHESIS_SAMPLE_RATE_HZ) return mono
            val outLen = max(1, ((mono.size.toLong() * SYNTHESIS_SAMPLE_RATE_HZ) / sampleRate).toInt())
            return FloatArray(outLen) { i ->
                val srcPos = (i.toDouble() * sampleRate) / SYNTHESIS_SAMPLE_RATE_HZ
                val idx0 = srcPos.toInt().coerceIn(0, mono.lastIndex)
                val idx1 = (idx0 + 1).coerceIn(0, mono.lastIndex)
                val frac = (srcPos - idx0).toFloat()
                mono[idx0] * (1f - frac) + mono[idx1] * frac
            }
        }

        fun alignWavWithOrtCtc(
            wavFile: File,
            expectedText: String,
            sherpaCtcDir: File
        ): List<AlignedWord> {
            val pcm44k = readWavAsFloat44kMono(wavFile) ?: return emptyList()
            if (pcm44k.isEmpty() || expectedText.isBlank()) return emptyList()
            return runCatching {
                val engine = OrtCtcAlignerEngine.createDefault(
                    sherpaCtcModelDir = sherpaCtcDir
                ).getOrNull() ?: return emptyList()
                engine.use { aligner ->
                    val output = aligner.align(pcm44k, expectedText).getOrNull() ?: return emptyList()
                    output.wordSpans.map { span ->
                        val startMs = ((span.startSample * 1_000L) / SYNTHESIS_SAMPLE_RATE_HZ).coerceAtLeast(0L)
                        val endMs = max(
                            startMs + 1L,
                            (span.endSample * 1_000L) / SYNTHESIS_SAMPLE_RATE_HZ
                        )
                        AlignedWord(word = span.word, startMs = startMs, endMs = endMs)
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}

/** The Mac studio could not be reached on any configured route (tunnel, USB, LAN). */
class MacStudioOfflineException : IllegalStateException(
    "Your Mac studio is offline, so this step can't run right now. Your story is saved. " +
        "Check that the Mac is on and connected, then tap retry."
)
