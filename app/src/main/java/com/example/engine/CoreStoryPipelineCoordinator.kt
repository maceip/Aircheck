package com.example.engine

import com.example.data.AlignedWord
import com.example.data.BrollSegmentMode
import com.example.data.ClipRecord
import com.example.data.CorePipelineSession
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
import com.example.data.local.CasMediaStore
import com.voicestudio.edl.CandidateTake
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.GateMetrics
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.WordToken
import com.voicestudio.ui.Media3BeatExporter
import com.voicestudio.ui.VideoAnchorBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    private val macRigGateway: MacRigRenderGateway
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
        localMicWavFile: File? = null
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

            val updated = if (localMicWavFile != null && localMicWavFile.exists() && localMicWavFile.length() > 44L) {
                val stored = casStore.putFile(localMicWavFile, "wav")
                val wavInfo = parseWavDurationAndPeaks(stored.file)
                val timedClauses = distributeClauseTimings(activeClauses, wavInfo.durationMs)
                session.copy(
                    title = session.title.ifBlank { timedClauses.first().spokenText.take(40) },
                    stage = CorePipelineStage.ITERATE_STORY,
                    clauses = timedClauses,
                    ttsVoiceSha = stored.sha256,
                    ttsAudioUrl = null,
                    ttsDurationMs = wavInfo.durationMs,
                    waveformPeaks = wavInfo.peaks,
                    ttsIterationCount = session.ttsIterationCount + 1,
                    talkingHeadJobId = null,
                    talkingHeadSha = null,
                    talkingHeadVideoUrl = null,
                    alignedWords = emptyList(),
                    finalClipId = null,
                    finalVideoSha = null,
                    finalVideoUrl = null,
                    updatedAtMillis = System.currentTimeMillis()
                )
            } else {
                val health = macRigGateway.resolveAndCheckHealth(rigBaseUrl)
                val ttsRes = macRigGateway.synthesizeCoreStoryTts(
                    rigBaseUrl = health.resolvedUrl,
                    projectId = session.scriptId.ifBlank { session.id },
                    clauses = activeClauses
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
                session.copy(
                    title = session.title.ifBlank { mergedClauses.first().spokenText.take(40) },
                    stage = CorePipelineStage.ITERATE_STORY,
                    clauses = mergedClauses,
                    ttsVoiceSha = storedWav.sha256,
                    ttsAudioUrl = ttsRes.audioUrl,
                    ttsDurationMs = ttsRes.durationMs,
                    waveformPeaks = ttsRes.waveformPeaks,
                    ttsIterationCount = session.ttsIterationCount + 1,
                    talkingHeadJobId = null,
                    talkingHeadSha = null,
                    talkingHeadVideoUrl = null,
                    alignedWords = emptyList(),
                    finalClipId = null,
                    finalVideoSha = null,
                    finalVideoUrl = null,
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
            val effectiveTitle = session.title.ifBlank {
                activeClauses.first().spokenText.take(40)
            }
            val cleanHeadProject = ScriptProject(
                id = session.scriptId.ifBlank { "proj_${session.id.takeLast(10)}" },
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

            val targetMaxSec = max(4, ((session.ttsDurationMs + 999L) / 1000L).toInt()).coerceIn(4, 30)
            val enqueued = macRigGateway.enqueueMacRigRender(
                rigBaseUrl = health.resolvedUrl,
                project = cleanHeadProject,
                photoSha = photoSha,
                resolution = "720P",
                ratio = "9:16",
                maxDurationSec = targetMaxSec
            )

            onProgress(0.15f, "Matching speech to talking head…")
            val interval = pollIntervalMs.coerceAtLeast(25L)
            var snapshot = macRigGateway.pollMacRigJob(health.resolvedUrl, enqueued.jobId)
            var consecutiveErrors = 0
            while (!snapshot.status.isTerminal) {
                onProgress(
                    snapshot.progressFraction.coerceIn(0.15f, 0.92f),
                    snapshot.stageDetail.ifBlank { "Matching speech to talking head…" }
                )
                delay(interval)
                val nextPoll = runCatching {
                    macRigGateway.pollMacRigJob(health.resolvedUrl, enqueued.jobId)
                }
                if (nextPoll.isSuccess) {
                    consecutiveErrors = 0
                    snapshot = nextPoll.getOrThrow()
                } else {
                    val err = nextPoll.exceptionOrNull()
                    if (err is CancellationException) throw err
                    consecutiveErrors++
                    if (consecutiveErrors >= 5) {
                        throw IllegalStateException(
                            err?.localizedMessage ?: "Lost connection while rendering talking head."
                        )
                    }
                }
            }

            if (snapshot.status == RenderJobStatus.FAILED) {
                return@withContext Result.failure(
                    IllegalStateException(snapshot.errorMessage ?: "Talking-head synthesis failed.")
                )
            }
            val headVideoUrl = snapshot.videoUrl
            if (headVideoUrl.isNullOrBlank()) {
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
                projectId = cleanHeadProject.id,
                clauses = timedClauses,
                talkingHeadFile = storedHead.file,
                totalDurationMs = effectiveDurationMs
            )

            // Pre-stage clause index 1 as B-Roll Cutaway when >= 2 clauses exist and none have been
            // customized yet, giving an immediate Talking Head -> B-Roll -> Talking Head cut list.
            val brollReadyClauses = if (
                beatLockedClauses.size >= 2 &&
                beatLockedClauses.all { it.segmentMode == BrollSegmentMode.TALKING_HEAD }
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
                talkingHeadJobId = enqueued.jobId,
                talkingHeadSha = storedHead.sha256,
                talkingHeadVideoUrl = headVideoUrl,
                alignedWords = alignedWords,
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
        photoSha: String? = null
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
                overlays = attachedOverlays
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
                updatedAtMillis = now
            )
            localRepo.saveCorePipelineSession(updatedSession)
            Result.success(updatedSession to clip)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private suspend fun lockClauseCutWindowsWithMedia3Exporter(
        projectId: String,
        clauses: List<CoreStoryClause>,
        talkingHeadFile: File,
        totalDurationMs: Long
    ): Pair<List<CoreStoryClause>, List<AlignedWord>> {
        val sampleRate = SYNTHESIS_SAMPLE_RATE_HZ
        val alignedWords = mutableListOf<AlignedWord>()
        val manifestClauses = clauses.mapIndexed { idx, clause ->
            val startMs = clause.startMs.coerceAtLeast(0L)
            val endMs = clause.endMs.coerceAtLeast(startMs + 200L)
            val durMs = (endMs - startMs).coerceAtLeast(200L)
            val words = clause.spokenText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val safeWords = if (words.isEmpty()) listOf(clause.spokenText.trim()) else words
            var cursorMs = startMs
            val wordTokens = safeWords.mapIndexed { wIdx, w ->
                val sliceMs = if (wIdx == safeWords.lastIndex) {
                    (endMs - cursorMs).coerceAtLeast(40L)
                } else {
                    max(40L, durMs / safeWords.size)
                }
                val wStartMs = cursorMs
                val wEndMs = min(endMs, wStartMs + sliceMs)
                cursorMs = wEndMs
                alignedWords += AlignedWord(word = w, startMs = wStartMs, endMs = wEndMs)

                val localStartSample = ((wStartMs - startMs) * sampleRate) / 1_000L
                val localEndSample = max(localStartSample + 1L, ((wEndMs - startMs) * sampleRate) / 1_000L)
                WordToken(
                    word = w,
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
                        pcmFilePath = talkingHeadFile.absolutePath,
                        totalSamples = clauseSamples,
                        discreteAudioTokens = IntArray(0),
                        seed = idx.toLong(),
                        temperature = 0.7f,
                        metrics = GateMetrics(
                            cer = 0f,
                            maxPhonemeDurationMs = 180f,
                            speakerCosineSim = 0.92f,
                            dnsmosP808 = 4.25f,
                            tailRmsDb = -52f,
                            syllablesPerSec = 4.8f,
                            passedAllGates = true
                        ),
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

        val headUri = talkingHeadFile.toURI().toString()
        val bindings = clauses.associate { clause ->
            clause.id to VideoAnchorBinding(
                videoAnchorId = clause.id,
                videoUri = headUri
            )
        }
        val specsByClauseId = Media3BeatExporter.buildMedia3ClippingSpecs(manifest, bindings)
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
        return lockedClauses to alignedWords
    }

    companion object {
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
    }
}
