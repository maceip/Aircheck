package com.voicestudio.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.voicestudio.edl.ClauseSynthesisProgress
import com.voicestudio.edl.DEFAULT_TAKES_PER_CLAUSE
import com.voicestudio.edl.GateMetrics
import com.voicestudio.edl.OtaBundleManifest
import com.voicestudio.edl.OtaBundleState
import com.voicestudio.edl.VoiceProjectManifest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.TimeUnit

data class DirectSynthesisClauseInput(
    val clauseId: String,
    val spokenText: String,
    val phoneticOverrides: Map<String, String> = emptyMap()
)

data class LocalAlignedWord(
    val word: String,
    val startMs: Long,
    val endMs: Long
)

data class DirectSynthesisResult(
    val manifest: VoiceProjectManifest,
    val stitchedPcm44k: FloatArray,
    val clauseTimeBoundsMs: Map<String, Pair<Long, Long>>,
    val alignedWords: List<LocalAlignedWord>,
    val summaryMetrics: GateMetrics
)

/**
 * Top-level Android coordinator wiring:
 * - Expedited WorkManager synthesis (`enqueueProjectSynthesis`, `enqueueSingleClauseResynthesis`)
 * - Direct suspend synthesis (`synthesizeClausesDirectly`) for `CoreStoryPipelineCoordinator`
 * - Wi-Fi / Tailscale (`NetworkType.UNMETERED`) preference upload & nightly LoRA hot-swap check
 * - Live [OtaBundleSyncManager] state and direct manual OTA sync trigger.
 */
class VoiceStudioSyncCoordinator(
    private val context: Context,
    val otaBundleSyncManager: OtaBundleSyncManager,
    private val workManagerProvider: () -> WorkManager = { WorkManager.getInstance(context) }
) {
    val progress: StateFlow<ClauseSynthesisProgress> =
        VoiceStudioRuntimeRegistry.synthesisProgressFlow.asStateFlow()

    val otaState: StateFlow<OtaBundleState> = otaBundleSyncManager.bundleState

    fun enqueueProjectSynthesis(
        projectId: String,
        numCandidates: Int = DEFAULT_TAKES_PER_CLAUSE
    ): UUID {
        val request = OneTimeWorkRequestBuilder<VoiceSynthesisWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    VoiceSynthesisWorker.KEY_PROJECT_ID to projectId,
                    VoiceSynthesisWorker.KEY_NUM_CANDIDATES to numCandidates
                )
            )
            .addTag(TAG_VOICE_SYNTHESIS)
            .addTag(projectSynthesisTag(projectId))
            .build()

        workManagerProvider().enqueueUniqueWork(
            uniqueSynthesisWorkName(projectId),
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    fun enqueueSingleClauseResynthesis(
        projectId: String,
        clauseId: String,
        numCandidates: Int = DEFAULT_TAKES_PER_CLAUSE
    ): UUID {
        val request = OneTimeWorkRequestBuilder<VoiceSynthesisWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    VoiceSynthesisWorker.KEY_PROJECT_ID to projectId,
                    VoiceSynthesisWorker.KEY_CLAUSE_ID to clauseId,
                    VoiceSynthesisWorker.KEY_NUM_CANDIDATES to numCandidates
                )
            )
            .addTag(TAG_VOICE_SYNTHESIS)
            .addTag(projectSynthesisTag(projectId))
            .build()

        workManagerProvider().enqueueUniqueWork(
            uniqueClauseWorkName(projectId, clauseId),
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    /**
     * Enqueues a one-shot [PreferenceSyncWorker] constrained by default to `NetworkType.UNMETERED`
     * (Wi-Fi / Tailscale) to upload `preferences.jsonl` and hot-swap any newer LoRA delta.
     */
    fun enqueuePreferenceSyncAndLoraCheck(
        tailscaleBaseUrl: String? = null,
        requireUnmetered: Boolean = true
    ): UUID {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (requireUnmetered) SyncModuleConfig.SYNC_NETWORK_TYPE else NetworkType.CONNECTED
            )
            .build()

        val builder = OneTimeWorkRequestBuilder<PreferenceSyncWorker>()
            .setConstraints(constraints)
            .addTag(TAG_PREFERENCE_SYNC)

        if (!tailscaleBaseUrl.isNullOrBlank()) {
            builder.setInputData(
                workDataOf(PreferenceSyncWorker.KEY_TAILSCALE_BASE_URL to tailscaleBaseUrl.trim())
            )
        }

        val request = builder.build()
        workManagerProvider().enqueueUniqueWork(
            UNIQUE_PREFERENCE_SYNC_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
        return request.id
    }

    /**
     * Schedules periodic nightly Wi-Fi/Tailscale preference upload & LoRA delta checks.
     */
    fun schedulePeriodicPreferenceSync(tailscaleBaseUrl: String? = null) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(SyncModuleConfig.SYNC_NETWORK_TYPE)
            .build()

        val builder = PeriodicWorkRequestBuilder<PreferenceSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .addTag(TAG_PREFERENCE_SYNC_PERIODIC)

        if (!tailscaleBaseUrl.isNullOrBlank()) {
            builder.setInputData(
                workDataOf(PreferenceSyncWorker.KEY_TAILSCALE_BASE_URL to tailscaleBaseUrl.trim())
            )
        }

        workManagerProvider().enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_PREFERENCE_SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            builder.build()
        )
    }

    fun cancelActiveSynthesis() {
        workManagerProvider().cancelAllWorkByTag(TAG_VOICE_SYNTHESIS)
    }

    suspend fun syncOtaBundleNow(tailscaleBaseUrl: String): Result<OtaBundleManifest> {
        return otaBundleSyncManager.syncFromRig(tailscaleBaseUrl)
    }

    /**
     * Runs the local `v002` VoiceStudio synthesis + 4-gate verification pipeline directly from
     * explicit [clauses] without nested `WorkManager` scheduling or stale `scripts` table lookups.
     */
    suspend fun synthesizeClausesDirectly(
        projectId: String,
        clauses: List<DirectSynthesisClauseInput>,
        numCandidates: Int = DEFAULT_TAKES_PER_CLAUSE,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<DirectSynthesisResult> {
        return LocalVoiceSynthesisRunner.synthesizeClausesDirectly(
            context = context,
            otaBundleSyncManager = otaBundleSyncManager,
            projectId = projectId,
            clauses = clauses,
            numCandidates = numCandidates,
            onProgress = onProgress
        )
    }

    companion object {
        const val TAG_VOICE_SYNTHESIS: String = "voicestudio_synthesis"
        const val TAG_PREFERENCE_SYNC: String = "voicestudio_preference_sync"
        const val TAG_PREFERENCE_SYNC_PERIODIC: String = "voicestudio_preference_sync_periodic"
        const val UNIQUE_PREFERENCE_SYNC_WORK: String = "voicestudio_preference_sync_once"
        const val UNIQUE_PERIODIC_PREFERENCE_SYNC_WORK: String = "voicestudio_preference_sync_periodic"

        fun uniqueSynthesisWorkName(projectId: String): String = "voicestudio_synthesis_$projectId"
        fun uniqueClauseWorkName(projectId: String, clauseId: String): String =
            "voicestudio_clause_${projectId}_$clauseId"
        fun projectSynthesisTag(projectId: String): String = "voicestudio_project_$projectId"
    }
}
