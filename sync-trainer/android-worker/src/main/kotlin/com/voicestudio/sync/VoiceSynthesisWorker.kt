package com.voicestudio.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.ai.edge.litert.Accelerator
import com.voicestudio.edl.ACOUSTIC_PREFIX_TAIL_SAMPLES
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.ClauseSynthesisPhase
import com.voicestudio.edl.ClauseSynthesisProgress
import com.voicestudio.edl.DEFAULT_TAKES_PER_CLAUSE
import com.voicestudio.edl.FOREGROUND_RAM_CEILING_MB
import com.voicestudio.edl.PreferenceTelemetryRecord
import com.voicestudio.edl.PreferenceUserAction
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.engine.ClauseNormalizer
import com.voicestudio.engine.LiteRtCodecDecoder
import com.voicestudio.engine.VoiceEngineJni
import com.voicestudio.engine.VoiceEnginePipeline
import com.voicestudio.verifier.AudioDspMetrics
import com.voicestudio.verifier.ClauseVerifier
import com.voicestudio.verifier.RawCandidateInput
import com.voicestudio.verifier.VerifierModelsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Expedited WorkManager `CoroutineWorker` (`OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`,
 * foreground service type `FOREGROUND_SERVICE_DATA_SYNC`) orchestrating the rectified 3-stage
 * local VoiceStudio pipeline:
 *
 * 1. Measures `/proc/self/status` `VmRSS` before synthesis (aborting cleanly if approaching 9,500 MB).
 * 2. Verifies the on-disk OTA bundle under `noBackupFilesDir/bundles/` — fails cleanly with an
 *    explicit missing-bundle error if ANY required `.gguf`/`.tflite`/`.onnx`/`.bin`/`.pcm` file is
 *    absent (Constitutional Law 2: ZERO fabricated audio or gate metrics).
 * 3. Runs `ClauseNormalizer` (<14s clauses) -> Stage A (`VoiceEngineJni` + `LiteRtCodecDecoder`,
 *    N=4 discrete token rollouts -> 44.1 kHz Float32 PCM + Stage C NEON mastering) -> Stage B
 *    (`ClauseVerifier` 4-gate check + top 3 64B-aligned `.pcm` + `ClauseNode.json`) ->
 *    `VoiceEngineJni.evictWeightPages()` -> persists updated [VoiceProjectManifest] to Room.
 */
class VoiceSynthesisWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildMediaProcessingForegroundInfo(
            phase = ClauseSynthesisPhase.NORMALIZING,
            completedClauses = 0,
            totalClauses = 1
        )

    private fun buildMediaProcessingForegroundInfo(
        phase: ClauseSynthesisPhase,
        completedClauses: Int,
        totalClauses: Int
    ): ForegroundInfo {
        ensureNotificationChannel(applicationContext)
        val safeTotal = totalClauses.coerceAtLeast(1)
        val phaseOrdinal = when (phase) {
            ClauseSynthesisPhase.IDLE, ClauseSynthesisPhase.NORMALIZING -> 1
            ClauseSynthesisPhase.STAGE_A_GGUF_AND_LITERT_DAC -> 2
            ClauseSynthesisPhase.STAGE_B_4GATE_VERIFIER -> 3
            ClauseSynthesisPhase.STAGE_C_NEON_MASTERING, ClauseSynthesisPhase.READY -> 4
            ClauseSynthesisPhase.FAILED -> 0
        }
        val progressPercent = (((completedClauses * 4) + phaseOrdinal) * 100 / (safeTotal * 4))
            .coerceIn(5, 100)

        val progressStyle = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(progressPercent)
            .setProgressSegments(
                listOf(
                    Notification.ProgressStyle.Segment(25).setColor(Color.parseColor("#00E5FF")),
                    Notification.ProgressStyle.Segment(35).setColor(Color.parseColor("#B8FF00")),
                    Notification.ProgressStyle.Segment(25).setColor(Color.parseColor("#F5B700")),
                    Notification.ProgressStyle.Segment(15).setColor(Color.parseColor("#00E676"))
                )
            )

        val notification = Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Aircheck VoiceStudio")
            .setContentText("Synthesizing 44.1 kHz voice takes locally (${phase.name.lowercase()})…")
            .setStyle(progressStyle)
            .setOngoing(true)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val projectId = inputData.getString(KEY_PROJECT_ID)?.trim().orEmpty()
        if (projectId.isEmpty()) {
            return@withContext failProgress(
                projectId = "",
                measuredRssMb = VoiceStudioRuntimeRegistry.vmRssReader(),
                message = "Missing project_id for VoiceSynthesisWorker"
            )
        }

        val targetClauseId = inputData.getString(KEY_CLAUSE_ID)?.trim()?.takeIf { it.isNotEmpty() }
        val numCandidates = inputData.getInt(KEY_NUM_CANDIDATES, DEFAULT_TAKES_PER_CLAUSE)
            .coerceIn(1, 8)

        // 1. Pre-synthesis /proc/self/status VmRSS guard (< 9,500 MB foreground ceiling).
        val preRssMb = VoiceStudioRuntimeRegistry.vmRssReader()
        if (ProcessMemoryReader.isApproachingCeiling(preRssMb, FOREGROUND_RAM_CEILING_MB)) {
            return@withContext failProgress(
                projectId = projectId,
                measuredRssMb = preRssMb,
                message = "Aborted local synthesis: process VmRSS (${preRssMb} MB) is approaching the $FOREGROUND_RAM_CEILING_MB MB ceiling."
            )
        }

        publishProgress(
            ClauseSynthesisProgress(
                projectId = projectId,
                activeClauseId = targetClauseId,
                phase = ClauseSynthesisPhase.NORMALIZING,
                completedClauses = 0,
                totalClauses = 0,
                measuredRssMb = preRssMb,
                errorMessage = null
            )
        )

        // 2. Verify OTA bundle on disk BEFORE touching native engines (Constitutional Law 2).
        val otaManager = VoiceStudioRuntimeRegistry.otaBundleSyncManager
            ?: OtaBundleSyncManager(applicationContext)
        val resolvedBundle = otaManager.resolveVerifiedBundleForSynthesis().getOrElse { err ->
            return@withContext failProgress(
                projectId = projectId,
                measuredRssMb = preRssMb,
                message = err.message ?: "Required VoiceStudio OTA bundle artifacts are missing."
            )
        }

        val projectStore = VoiceStudioRuntimeRegistry.projectStore
            ?: return@withContext failProgress(
                projectId = projectId,
                measuredRssMb = preRssMb,
                message = "VoiceStudio SyncProjectStore is not registered."
            )

        // 3. Build or update ClauseNodes via deterministic ClauseNormalizer (< 14.0s per clause).
        val outputRootDir = File(applicationContext.noBackupFilesDir, "projects/$projectId")
        val takesDir = File(outputRootDir, "takes")
        val normalizer = ClauseNormalizer(takesDirPrefix = takesDir.absolutePath)
        val lexiconMap = normalizer.loadLexicon(resolvedBundle.lexiconJson)

        val existingManifest = projectStore.getProjectManifest(projectId)
        val clausesToSynthesize: List<ClauseNode>
        val baseManifest: VoiceProjectManifest

        if (targetClauseId != null) {
            if (existingManifest == null) {
                return@withContext failProgress(
                    projectId = projectId,
                    measuredRssMb = preRssMb,
                    message = "Cannot re-synthesize clause '$targetClauseId': project manifest not found."
                )
            }
            val match = existingManifest.clauses.firstOrNull { it.clauseId == targetClauseId }
                ?: return@withContext failProgress(
                    projectId = projectId,
                    measuredRssMb = preRssMb,
                    message = "Clause '$targetClauseId' not found in manifest for project '$projectId'."
                )
            val renormalized = match.copy(
                normalizedPhonemes = match.normalizedPhonemes.ifBlank {
                    normalizer.normalizeClauseToIpa(match.rawText, lexicon = lexiconMap)
                }
            )
            clausesToSynthesize = listOf(renormalized)
            baseManifest = existingManifest
        } else {
            val draft = projectStore.getScriptDraftForSynthesis(projectId)
            val rawText = draft?.rawDraftText?.trim().orEmpty().ifBlank {
                existingManifest?.clauses?.joinToString(" ") { it.rawText }.orEmpty()
            }
            if (rawText.isBlank()) {
                return@withContext failProgress(
                    projectId = projectId,
                    measuredRssMb = preRssMb,
                    message = "Script '$projectId' has no spoken beats to synthesize."
                )
            }
            val normalizedClauses = normalizer.normalizeAndSplit(
                projectId = projectId,
                rawDraft = rawText,
                lexiconFile = resolvedBundle.lexiconJson,
                phoneticOverrides = draft?.phoneticOverrides.orEmpty()
            )
            if (normalizedClauses.isEmpty()) {
                return@withContext failProgress(
                    projectId = projectId,
                    measuredRssMb = preRssMb,
                    message = "ClauseNormalizer produced zero clauses for project '$projectId'."
                )
            }
            clausesToSynthesize = normalizedClauses
            baseManifest = existingManifest ?: VoiceProjectManifest(
                projectId = projectId,
                sampleRate = SYNTHESIS_SAMPLE_RATE_HZ,
                globalGapScale = 1.0f,
                activeLoraVersion = resolvedBundle.manifest.loraVersion,
                clauses = normalizedClauses
            )
        }

        // 4. Execute Stage A (GGUF + LoRA + LiteRT DAC + NEON DSP) -> Stage B (4-gate Verifier) -> EvictWeightPages().
        val executor = VoiceStudioRuntimeRegistry.synthesisExecutorFactory?.invoke(applicationContext)
            ?: NativeVoiceSynthesisExecutor(applicationContext)

        val execResult = executor.synthesizeClauses(
            projectId = projectId,
            clauses = clausesToSynthesize,
            bundle = resolvedBundle,
            outputRootDir = outputRootDir,
            numCandidates = numCandidates,
            onClauseProgress = { completed, total, activeId, phase ->
                publishProgress(
                    ClauseSynthesisProgress(
                        projectId = projectId,
                        activeClauseId = activeId,
                        phase = phase,
                        completedClauses = completed,
                        totalClauses = total,
                        measuredRssMb = VoiceStudioRuntimeRegistry.vmRssReader(),
                        errorMessage = null
                    )
                )
            }
        ).getOrElse { err ->
            return@withContext failProgress(
                projectId = projectId,
                measuredRssMb = VoiceStudioRuntimeRegistry.vmRssReader(),
                message = err.message ?: "Local voice synthesis failed."
            )
        }

        // 5. Merge updated clauses into VoiceProjectManifest and persist to Room WAL.
        val mergedClauses = if (targetClauseId != null) {
            val replacementById = execResult.updatedClauses.associateBy { it.clauseId }
            baseManifest.clauses.map { existing -> replacementById[existing.clauseId] ?: existing }
        } else {
            execResult.updatedClauses
        }

        val updatedManifest = baseManifest.copy(
            sampleRate = SYNTHESIS_SAMPLE_RATE_HZ,
            activeLoraVersion = resolvedBundle.manifest.loraVersion,
            clauses = mergedClauses
        )
        projectStore.saveProjectManifest(updatedManifest)

        // Persist automated gate-failure tuples for Tinker GRPO if any candidate failed verification.
        val prefStore = VoiceStudioRuntimeRegistry.preferenceStore
        if (prefStore != null && execResult.rejectedPreferences.isNotEmpty()) {
            for (record in execResult.rejectedPreferences) {
                prefStore.appendPreference(record)
            }
        }

        // 6. Post-synthesis /proc/self/status VmRSS readout.
        val postRssMb = VoiceStudioRuntimeRegistry.vmRssReader()
        publishProgress(
            ClauseSynthesisProgress(
                projectId = projectId,
                activeClauseId = null,
                phase = ClauseSynthesisPhase.READY,
                completedClauses = clausesToSynthesize.size,
                totalClauses = clausesToSynthesize.size,
                measuredRssMb = postRssMb,
                errorMessage = null
            )
        )

        Result.success(
            workDataOf(
                KEY_PROJECT_ID to projectId,
                KEY_COMPLETED_CLAUSES to clausesToSynthesize.size,
                KEY_MEASURED_RSS_MB to (postRssMb ?: -1)
            )
        )
    }

    private fun publishProgress(progress: ClauseSynthesisProgress) {
        VoiceStudioRuntimeRegistry.synthesisProgressFlow.value = progress
    }

    private fun failProgress(
        projectId: String,
        measuredRssMb: Int?,
        message: String
    ): Result {
        publishProgress(
            ClauseSynthesisProgress(
                projectId = projectId,
                activeClauseId = null,
                phase = ClauseSynthesisPhase.FAILED,
                completedClauses = 0,
                totalClauses = 0,
                measuredRssMb = measuredRssMb,
                errorMessage = message
            )
        )
        return Result.failure(
            workDataOf(
                KEY_PROJECT_ID to projectId,
                KEY_ERROR_MESSAGE to message,
                KEY_MEASURED_RSS_MB to (measuredRssMb ?: -1)
            )
        )
    }

    companion object {
        const val KEY_PROJECT_ID: String = "project_id"
        const val KEY_CLAUSE_ID: String = "clause_id"
        const val KEY_NUM_CANDIDATES: String = "num_candidates"
        const val KEY_COMPLETED_CLAUSES: String = "completed_clauses"
        const val KEY_MEASURED_RSS_MB: String = "measured_rss_mb"
        const val KEY_ERROR_MESSAGE: String = "error_message"

        private const val NOTIFICATION_CHANNEL_ID: String = "voicestudio_synthesis"
        private const val NOTIFICATION_ID: Int = 44101

        private fun ensureNotificationChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "VoiceStudio Local Synthesis",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Local 44.1 kHz GGUF + LiteRT DAC voice synthesis progress"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}

/**
 * Production bare-metal executor driving `:core-engine` (`VoiceEngineJni` + `LiteRtCodecDecoder` +
 * C++20 NEON DSP) and `:core-verifier` (`ClauseVerifier` 4-gate check), always invoking
 * `VoiceEngineJni.evictWeightPages()` in `finally`.
 */
internal class NativeVoiceSynthesisExecutor(
    private val context: Context
) : VoiceSynthesisExecutor {

    override suspend fun synthesizeClauses(
        projectId: String,
        clauses: List<ClauseNode>,
        bundle: ResolvedOtaBundlePaths,
        outputRootDir: File,
        numCandidates: Int,
        onClauseProgress: suspend (Int, Int, String, ClauseSynthesisPhase) -> Unit
    ): Result<ClauseSynthesisExecutionResult> {
        val codecDecoder = LiteRtCodecDecoder(context)
        val pipeline = VoiceEnginePipeline(codecDecoder)
        var verifier: ClauseVerifier? = null

        return try {
            val nativeLibDir = context.applicationInfo?.nativeLibraryDir
            val llamaLibFile = nativeLibDir?.let { File(it, "libllama.so") }?.takeIf { it.isFile }
            val engineHandle = pipeline.initialize(
                baseGgufPath = bundle.baseGguf.absolutePath,
                loraGgufPath = bundle.loraGguf.absolutePath,
                vocoderTflitePath = bundle.vocoderTflite.absolutePath,
                roomIrPcmPath = bundle.roomIrPcm.absolutePath,
                roomTonePcmPath = bundle.roomTonePcm.absolutePath,
                cacheVersion = bundle.manifest.bundleVersion,
                preferredAccelerator = Accelerator.GPU,
                llamaLibPath = llamaLibFile?.absolutePath,
                nativeLibraryDir = nativeLibDir
            ).getOrElse { return Result.failure(it) }
            VoiceStudioRuntimeRegistry.registerActiveEngineHandle(engineHandle)

            verifier = ClauseVerifier.create(
                config = VerifierModelsConfig(
                    sherpaCtcModelDir = bundle.sherpaCtcModelDir.absolutePath,
                    weSpeakerOnnxPath = bundle.weSpeakerOnnx.absolutePath,
                    dnsmosP808OnnxPath = bundle.dnsmosP808Onnx.absolutePath,
                    masterCentroidBinPath = bundle.masterCentroidBin.absolutePath
                )
            ).getOrElse { return Result.failure(it) }

            val takesDir = File(outputRootDir, "takes").apply { mkdirs() }
            val updatedClauses = ArrayList<ClauseNode>(clauses.size)
            val rejectedPreferences = mutableListOf<PreferenceTelemetryRecord>()
            var previousTailPath: String? = null

            for ((idx, clause) in clauses.withIndex()) {
                val loopRss = VoiceStudioRuntimeRegistry.vmRssReader()
                if (ProcessMemoryReader.isApproachingCeiling(loopRss, FOREGROUND_RAM_CEILING_MB)) {
                    return Result.failure(
                        IllegalStateException(
                            "Aborted synthesis before clause ${clause.clauseId}: VmRSS ${loopRss} MB approaching $FOREGROUND_RAM_CEILING_MB MB ceiling"
                        )
                    )
                }

                val prefixPath = clause.acousticPrefixPcmPath
                    ?.takeIf { File(it).isFile }
                    ?: previousTailPath

                onClauseProgress(idx, clauses.size, clause.clauseId, ClauseSynthesisPhase.STAGE_A_GGUF_AND_LITERT_DAC)
                val synthesizedTakes = pipeline.synthesizeCandidates(
                    normalizedText = clause.normalizedPhonemes.ifBlank { clause.rawText },
                    prefixPcmPath = prefixPath,
                    numCandidates = numCandidates
                ).getOrElse { return Result.failure(it) }

                onClauseProgress(idx, clauses.size, clause.clauseId, ClauseSynthesisPhase.STAGE_B_4GATE_VERIFIER)
                val rawCandidates = synthesizedTakes.mapIndexed { takeIdx, take ->
                    RawCandidateInput(
                        takeId = "${clause.clauseId}_t$takeIdx",
                        pcm44k = take.pcm44k,
                        discreteAudioTokens = take.discreteAudioTokens,
                        seed = take.seed,
                        temperature = take.temperature
                    )
                }

                val clauseDir = File(takesDir, clause.clauseId)
                val batch = verifier.evaluateAndPersistClause(
                    clause = clause.copy(acousticPrefixPcmPath = prefixPath),
                    candidates = rawCandidates,
                    outputDir = clauseDir,
                    maxSurvivingTakes = ClauseVerifier.DEFAULT_MAX_SURVIVING_TAKES
                ).getOrElse { return Result.failure(it) }

                if (batch.survivingTakes.isEmpty()) {
                    return Result.failure(
                        IllegalStateException(
                            "All $numCandidates takes for clause ${clause.clauseId} failed 4-gate verification."
                        )
                    )
                }

                onClauseProgress(idx, clauses.size, clause.clauseId, ClauseSynthesisPhase.STAGE_C_NEON_MASTERING)
                val winningRaw = rawCandidates.firstOrNull { it.takeId == batch.survivingTakes.first().takeId }
                if (winningRaw != null) {
                    val tailSamples = VoiceEngineJni.extractAcousticPrefixTail(
                        inputPcm = winningRaw.pcm44k,
                        tailSamples = ACOUSTIC_PREFIX_TAIL_SAMPLES
                    ).getOrElse {
                        val count = minOf(ACOUSTIC_PREFIX_TAIL_SAMPLES, winningRaw.pcm44k.size)
                        winningRaw.pcm44k.copyOfRange(winningRaw.pcm44k.size - count, winningRaw.pcm44k.size)
                    }
                    val tailFile = File(takesDir, "${clause.clauseId}_winner_tail_1500ms.pcm")
                    AudioDspMetrics.writeAlignedPcmFile(tailSamples, tailFile)
                    previousTailPath = tailFile.absolutePath
                }

                if (batch.rejectedFailures.isNotEmpty()) {
                    rejectedPreferences += PreferenceTelemetryRecord(
                        timestamp = System.currentTimeMillis() + idx,
                        clauseId = clause.clauseId,
                        loraVersion = bundle.manifest.loraVersion,
                        rawText = clause.rawText,
                        normalizedPhonemes = clause.normalizedPhonemes,
                        userAction = PreferenceUserAction.TAKE_SWAP,
                        userGapScale = 1.0f,
                        chosenTokens = batch.survivingTakes.first().discreteAudioTokens.toList(),
                        rejectedTokens = batch.rejectedFailures.first().tokens,
                        gateFailures = batch.rejectedFailures
                    )
                }

                updatedClauses += batch.updatedClause
            }

            val evictedBytes = pipeline.evictIdleMemory().getOrDefault(0L)
            Result.success(
                ClauseSynthesisExecutionResult(
                    updatedClauses = updatedClauses,
                    rejectedPreferences = rejectedPreferences,
                    evictedBytes = evictedBytes
                )
            )
        } finally {
            runCatching { pipeline.evictIdleMemory() }
            VoiceStudioRuntimeRegistry.registerActiveEngineHandle(0L)
            runCatching { verifier?.close() }
            runCatching { pipeline.close() }
        }
    }
}
