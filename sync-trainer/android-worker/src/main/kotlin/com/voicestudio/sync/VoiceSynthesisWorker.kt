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
import com.voicestudio.edl.DEFAULT_BASE_GAP_SAMPLES
import com.voicestudio.edl.DEFAULT_TAKES_PER_CLAUSE
import com.voicestudio.edl.EdlTimelineCalculator
import com.voicestudio.edl.FOREGROUND_RAM_CEILING_MB
import com.voicestudio.edl.GateMetrics
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

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
        val otaManager = VoiceStudioRuntimeRegistry.otaBundleSyncManager
            ?: OtaBundleSyncManager(applicationContext)

        val outcome = LocalVoiceSynthesisRunner.synthesizeForWorker(
            context = applicationContext,
            otaBundleSyncManager = otaManager,
            projectId = projectId,
            targetClauseId = targetClauseId,
            numCandidates = numCandidates
        )

        outcome.fold(
            onSuccess = { (completedClauses, postRssMb) ->
                Result.success(
                    workDataOf(
                        KEY_PROJECT_ID to projectId,
                        KEY_COMPLETED_CLAUSES to completedClauses,
                        KEY_MEASURED_RSS_MB to (postRssMb ?: -1)
                    )
                )
            },
            onFailure = { err ->
                Result.failure(
                    workDataOf(
                        KEY_PROJECT_ID to projectId,
                        KEY_ERROR_MESSAGE to (err.message ?: "Local voice synthesis failed."),
                        KEY_MEASURED_RSS_MB to (VoiceStudioRuntimeRegistry.vmRssReader() ?: -1)
                    )
                )
            }
        )
    }

    private fun failProgress(
        projectId: String,
        measuredRssMb: Int?,
        message: String
    ): Result {
        VoiceStudioRuntimeRegistry.synthesisProgressFlow.value = ClauseSynthesisProgress(
            projectId = projectId,
            activeClauseId = null,
            phase = ClauseSynthesisPhase.FAILED,
            completedClauses = 0,
            totalClauses = 0,
            measuredRssMb = measuredRssMb,
            errorMessage = message
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

/**
 * Shared local `v002` voice synthesis runner used by both [VoiceSynthesisWorker] and
 * [VoiceStudioSyncCoordinator.synthesizeClausesDirectly] (avoiding nested WorkManager calls
 * and stale `scripts` Room-table lookups).
 */
internal object LocalVoiceSynthesisRunner {

    suspend fun synthesizeForWorker(
        context: Context,
        otaBundleSyncManager: OtaBundleSyncManager,
        projectId: String,
        targetClauseId: String?,
        numCandidates: Int
    ): Result<Pair<Int, Int?>> = withContext(Dispatchers.IO) {
        try {
            val preRssMb = VoiceStudioRuntimeRegistry.vmRssReader()
            if (ProcessMemoryReader.isApproachingCeiling(preRssMb, FOREGROUND_RAM_CEILING_MB)) {
                return@withContext fail(
                    projectId,
                    preRssMb,
                    "Aborted local synthesis: process VmRSS (${preRssMb} MB) is approaching the $FOREGROUND_RAM_CEILING_MB MB ceiling."
                )
            }

            publish(
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

            val resolvedBundle = otaBundleSyncManager.resolveVerifiedBundleForSynthesis().getOrElse { err ->
                return@withContext fail(
                    projectId,
                    preRssMb,
                    err.message ?: "Required VoiceStudio OTA bundle artifacts are missing."
                )
            }

            val projectStore = VoiceStudioRuntimeRegistry.projectStore
                ?: return@withContext fail(
                    projectId,
                    preRssMb,
                    "VoiceStudio SyncProjectStore is not registered."
                )

            val outputRootDir = File(context.noBackupFilesDir, "projects/$projectId")
            val takesDir = File(outputRootDir, "takes")
            val normalizer = ClauseNormalizer(takesDirPrefix = takesDir.absolutePath)
            val lexiconMap = normalizer.loadLexicon(resolvedBundle.lexiconJson)

            val existingManifest = projectStore.getProjectManifest(projectId)
            val clausesToSynthesize: List<ClauseNode>
            val baseManifest: VoiceProjectManifest

            if (targetClauseId != null) {
                if (existingManifest == null) {
                    return@withContext fail(
                        projectId,
                        preRssMb,
                        "Cannot re-synthesize clause '$targetClauseId': project manifest not found."
                    )
                }
                val match = existingManifest.clauses.firstOrNull { it.clauseId == targetClauseId }
                    ?: return@withContext fail(
                        projectId,
                        preRssMb,
                        "Clause '$targetClauseId' not found in manifest for project '$projectId'."
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
                    return@withContext fail(
                        projectId,
                        preRssMb,
                        "Script '$projectId' has no spoken beats to synthesize."
                    )
                }
                val normalizedClauses = normalizer.normalizeAndSplit(
                    projectId = projectId,
                    rawDraft = rawText,
                    lexiconFile = resolvedBundle.lexiconJson,
                    phoneticOverrides = draft?.phoneticOverrides.orEmpty()
                )
                if (normalizedClauses.isEmpty()) {
                    return@withContext fail(
                        projectId,
                        preRssMb,
                        "ClauseNormalizer produced zero clauses for project '$projectId'."
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

            val executor = VoiceStudioRuntimeRegistry.synthesisExecutorFactory?.invoke(context)
                ?: NativeVoiceSynthesisExecutor(context)

            val execResult = executor.synthesizeClauses(
                projectId = projectId,
                clauses = clausesToSynthesize,
                bundle = resolvedBundle,
                outputRootDir = outputRootDir,
                numCandidates = numCandidates.coerceIn(1, 8),
                onClauseProgress = { completed, total, activeId, phase ->
                    publish(
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
                return@withContext fail(
                    projectId,
                    VoiceStudioRuntimeRegistry.vmRssReader(),
                    err.message ?: "Local voice synthesis failed."
                )
            }

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

            val prefStore = VoiceStudioRuntimeRegistry.preferenceStore
            if (prefStore != null && execResult.rejectedPreferences.isNotEmpty()) {
                for (record in execResult.rejectedPreferences) {
                    prefStore.appendPreference(record)
                }
            }

            val postRssMb = VoiceStudioRuntimeRegistry.vmRssReader()
            publish(
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
            Result.success(clausesToSynthesize.size to postRssMb)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(
                projectId,
                VoiceStudioRuntimeRegistry.vmRssReader(),
                e.message ?: "Local voice synthesis failed."
            )
        }
    }

    suspend fun synthesizeClausesDirectly(
        context: Context,
        otaBundleSyncManager: OtaBundleSyncManager,
        projectId: String,
        clauses: List<DirectSynthesisClauseInput>,
        numCandidates: Int,
        onProgress: (Float, String) -> Unit
    ): Result<DirectSynthesisResult> = withContext(Dispatchers.IO) {
        try {
            val cleanProjectId = projectId.trim()
            if (cleanProjectId.isEmpty()) {
                return@withContext fail(
                    "",
                    VoiceStudioRuntimeRegistry.vmRssReader(),
                    "Missing project_id for local VoiceStudio synthesis."
                )
            }
            val activeInputs = clauses.map { it.copy(spokenText = it.spokenText.trim()) }
                .filter { it.spokenText.isNotEmpty() }
            if (activeInputs.isEmpty()) {
                return@withContext fail(
                    cleanProjectId,
                    VoiceStudioRuntimeRegistry.vmRssReader(),
                    "No spoken clauses provided for local VoiceStudio synthesis."
                )
            }

            val preRssMb = VoiceStudioRuntimeRegistry.vmRssReader()
            if (ProcessMemoryReader.isApproachingCeiling(preRssMb, FOREGROUND_RAM_CEILING_MB)) {
                return@withContext fail(
                    cleanProjectId,
                    preRssMb,
                    "Aborted local synthesis: process VmRSS (${preRssMb} MB) is approaching the $FOREGROUND_RAM_CEILING_MB MB ceiling."
                )
            }

            onProgress(0.06f, "Verifying local v002 voice bundle…")
            publish(
                ClauseSynthesisProgress(
                    projectId = cleanProjectId,
                    activeClauseId = activeInputs.first().clauseId,
                    phase = ClauseSynthesisPhase.NORMALIZING,
                    completedClauses = 0,
                    totalClauses = activeInputs.size,
                    measuredRssMb = preRssMb,
                    errorMessage = null
                )
            )

            val resolvedBundle = otaBundleSyncManager.resolveVerifiedBundleForSynthesis().getOrElse { err ->
                return@withContext fail(
                    cleanProjectId,
                    preRssMb,
                    err.message ?: "Required VoiceStudio v002 OTA bundle artifacts are missing."
                )
            }

            val outputRootDir = File(context.noBackupFilesDir, "projects/$cleanProjectId")
            val takesDir = File(outputRootDir, "takes")
            val normalizer = ClauseNormalizer(takesDirPrefix = takesDir.absolutePath)
            val lexiconMap = normalizer.loadLexicon(resolvedBundle.lexiconJson)

            val normalizedClauses = mutableListOf<ClauseNode>()
            val subToParentId = linkedMapOf<String, String>()
            val takesDirPath = takesDir.absolutePath.trimEnd('/')
            for (input in activeInputs) {
                val baseId = input.clauseId.ifBlank { ClauseNormalizer.formatClauseId(normalizedClauses.size + 1) }
                val subNodes = normalizer.normalizeAndSplitWithLexicon(
                    rawDraft = input.spokenText,
                    lexicon = lexiconMap,
                    phoneticOverrides = input.phoneticOverrides
                )
                if (subNodes.isEmpty()) continue
                subNodes.forEachIndexed { subIdx, sub ->
                    val globalIdx = normalizedClauses.size
                    val nodeId = if (subNodes.size == 1) baseId else "${baseId}_p${subIdx + 1}"
                    val prefixPath = if (globalIdx == 0) {
                        null
                    } else {
                        val prevId = normalizedClauses.last().clauseId
                        "$takesDirPath/${prevId}_winner_tail_1500ms.pcm"
                    }
                    normalizedClauses += sub.copy(
                        clauseId = nodeId,
                        index = globalIdx,
                        acousticPrefixPcmPath = prefixPath,
                        selectedTakeIndex = 0,
                        baseGapAfterSamples = DEFAULT_BASE_GAP_SAMPLES,
                        candidates = emptyList()
                    )
                    subToParentId[nodeId] = baseId
                }
            }

            if (normalizedClauses.isEmpty()) {
                return@withContext fail(
                    cleanProjectId,
                    preRssMb,
                    "ClauseNormalizer produced zero clauses for project '$cleanProjectId'."
                )
            }

            onProgress(0.15f, "Synthesizing 44.1 kHz voice locally (v002)…")
            val executor = VoiceStudioRuntimeRegistry.synthesisExecutorFactory?.invoke(context)
                ?: NativeVoiceSynthesisExecutor(context)

            val execResult = executor.synthesizeClauses(
                projectId = cleanProjectId,
                clauses = normalizedClauses,
                bundle = resolvedBundle,
                outputRootDir = outputRootDir,
                numCandidates = numCandidates.coerceIn(1, 8),
                onClauseProgress = { completed, total, activeId, phase ->
                    val safeTotal = total.coerceAtLeast(1)
                    val fraction = (0.15f + (completed.toFloat() / safeTotal) * 0.75f).coerceIn(0.15f, 0.90f)
                    onProgress(fraction, "Synthesizing clause ${completed + 1}/$safeTotal (${phase.name.lowercase()})…")
                    publish(
                        ClauseSynthesisProgress(
                            projectId = cleanProjectId,
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
                return@withContext fail(
                    cleanProjectId,
                    VoiceStudioRuntimeRegistry.vmRssReader(),
                    err.message ?: "Local voice synthesis failed."
                )
            }

            // Bind videoAnchorId onto the first WordToken of the first sub-clause per parent clauseId.
            val boundParents = hashSetOf<String>()
            val anchoredClauses = execResult.updatedClauses.map { clauseNode ->
                val parentId = subToParentId[clauseNode.clauseId] ?: clauseNode.clauseId
                val selectedIdx = clauseNode.selectedTakeIndex
                val winner = clauseNode.candidates.getOrNull(selectedIdx)
                if (winner != null && winner.tokens.isNotEmpty() && boundParents.add(parentId)) {
                    val updatedTokens = winner.tokens.mapIndexed { tIdx, tok ->
                        if (tIdx == 0 && tok.videoAnchorId.isNullOrBlank()) {
                            tok.copy(videoAnchorId = parentId)
                        } else {
                            tok
                        }
                    }
                    val updatedWinner = winner.copy(tokens = updatedTokens)
                    val updatedCandidates = clauseNode.candidates.mapIndexed { cIdx, cand ->
                        if (cIdx == selectedIdx) updatedWinner else cand
                    }
                    clauseNode.copy(candidates = updatedCandidates)
                } else {
                    clauseNode
                }
            }

            val manifest = VoiceProjectManifest(
                projectId = cleanProjectId,
                sampleRate = SYNTHESIS_SAMPLE_RATE_HZ,
                globalGapScale = 1.0f,
                activeLoraVersion = resolvedBundle.manifest.loraVersion,
                clauses = anchoredClauses
            )
            VoiceStudioRuntimeRegistry.projectStore?.saveProjectManifest(manifest)
            val prefStore = VoiceStudioRuntimeRegistry.preferenceStore
            if (prefStore != null && execResult.rejectedPreferences.isNotEmpty()) {
                for (record in execResult.rejectedPreferences) {
                    prefStore.appendPreference(record)
                }
            }

            onProgress(0.92f, "Stitching 44.1 kHz voice timeline…")
            val spans = EdlTimelineCalculator.buildTimelineSpans(manifest)
            if (spans.isEmpty()) {
                return@withContext fail(
                    cleanProjectId,
                    VoiceStudioRuntimeRegistry.vmRssReader(),
                    "Local voice synthesis produced no surviving candidate takes."
                )
            }

            val totalSamplesLong = spans.last().timelineEndSample
            require(totalSamplesLong in 1L..Int.MAX_VALUE.toLong()) {
                "Invalid stitched timeline sample count: $totalSamplesLong"
            }
            val stitchedPcm44k = FloatArray(totalSamplesLong.toInt())
            val sampleRate = manifest.sampleRate.coerceAtLeast(1)

            val clauseBounds = linkedMapOf<String, Pair<Long, Long>>()
            val alignedWords = mutableListOf<LocalAlignedWord>()

            for (span in spans) {
                val slice = span.slice
                val sliceSamples = readFloat32PcmSlice(
                    file = File(slice.pcmFilePath),
                    startSample = slice.zeroCrossInSample,
                    endSample = slice.zeroCrossOutSample
                )
                val destOffset = span.timelineStartSample.toInt()
                sliceSamples.copyInto(stitchedPcm44k, destinationOffset = destOffset)

                val clauseNode = manifest.clauses[span.clauseIndex]
                val parentId = subToParentId[clauseNode.clauseId] ?: clauseNode.clauseId
                val spanStartMs = (span.timelineStartSample * 1_000L) / sampleRate
                val spanEndMs = max(spanStartMs + 1L, (span.activeEndSample * 1_000L) / sampleRate)
                val existingBounds = clauseBounds[parentId]
                clauseBounds[parentId] = if (existingBounds == null) {
                    spanStartMs to spanEndMs
                } else {
                    minOf(existingBounds.first, spanStartMs) to maxOf(existingBounds.second, spanEndMs)
                }

                val winner = clauseNode.selectedCandidate
                if (winner != null && winner.tokens.isNotEmpty()) {
                    val baseIn = slice.zeroCrossInSample
                    for (token in winner.tokens) {
                        val globalIn = span.timelineStartSample + (token.zeroCrossIn - baseIn).coerceAtLeast(0L)
                        val globalOut = span.timelineStartSample + (token.zeroCrossOut - baseIn).coerceAtLeast(0L)
                        val wStartMs = (globalIn * 1_000L) / sampleRate
                        val wEndMs = max(wStartMs + 1L, (globalOut * 1_000L) / sampleRate)
                        alignedWords += LocalAlignedWord(
                            word = token.word,
                            startMs = wStartMs,
                            endMs = wEndMs
                        )
                    }
                }
            }

            val winners = manifest.clauses.mapNotNull { it.selectedCandidate }
            val summaryMetrics = GateMetrics(
                cer = winners.maxOf { it.metrics.cer },
                maxPhonemeDurationMs = winners.maxOf { it.metrics.maxPhonemeDurationMs },
                speakerCosineSim = winners.map { it.metrics.speakerCosineSim }.average().toFloat(),
                dnsmosP808 = winners.map { it.metrics.dnsmosP808 }.average().toFloat(),
                tailRmsDb = winners.maxOf { it.metrics.tailRmsDb },
                syllablesPerSec = winners.map { it.metrics.syllablesPerSec }.average().toFloat(),
                passedAllGates = winners.all { it.metrics.passedAllGates }
            )

            val postRssMb = VoiceStudioRuntimeRegistry.vmRssReader()
            publish(
                ClauseSynthesisProgress(
                    projectId = cleanProjectId,
                    activeClauseId = null,
                    phase = ClauseSynthesisPhase.READY,
                    completedClauses = manifest.clauses.size,
                    totalClauses = manifest.clauses.size,
                    measuredRssMb = postRssMb,
                    errorMessage = null
                )
            )
            onProgress(1.0f, "Local voice ready.")
            Result.success(
                DirectSynthesisResult(
                    manifest = manifest,
                    stitchedPcm44k = stitchedPcm44k,
                    clauseTimeBoundsMs = clauseBounds,
                    alignedWords = alignedWords,
                    summaryMetrics = summaryMetrics
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail(
                projectId,
                VoiceStudioRuntimeRegistry.vmRssReader(),
                e.message ?: "Local voice synthesis failed."
            )
        }
    }

    private fun readFloat32PcmSlice(
        file: File,
        startSample: Long,
        endSample: Long
    ): FloatArray {
        if (!file.isFile || !file.canRead()) {
            throw IllegalStateException("Synthesized PCM file missing or unreadable: ${file.absolutePath}")
        }
        val safeStart = startSample.coerceAtLeast(0L)
        val safeEnd = endSample.coerceAtLeast(safeStart)
        val countLong = safeEnd - safeStart
        if (countLong == 0L) return FloatArray(0)
        require(countLong <= Int.MAX_VALUE / 4L) { "PCM slice too large: $countLong samples" }

        val requiredBytes = safeEnd * 4L
        if (file.length() < requiredBytes) {
            throw IllegalStateException(
                "Synthesized PCM file truncated (${file.length()} < $requiredBytes bytes): ${file.absolutePath}"
            )
        }
        val byteLen = (countLong * 4L).toInt()
        val rawBytes = ByteArray(byteLen)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(safeStart * 4L)
            raf.readFully(rawBytes)
        }
        val out = FloatArray(countLong.toInt())
        ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun publish(progress: ClauseSynthesisProgress) {
        VoiceStudioRuntimeRegistry.synthesisProgressFlow.value = progress
    }

    private fun <T> fail(
        projectId: String,
        measuredRssMb: Int?,
        message: String
    ): Result<T> {
        publish(
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
        return Result.failure(IllegalStateException(message))
    }
}
