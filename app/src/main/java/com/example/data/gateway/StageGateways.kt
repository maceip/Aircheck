package com.example.data.gateway

import com.example.data.CoreStoryClause
import com.example.data.OverlayObjectKind
import com.example.data.RenderJobStatus
import com.example.data.SavedNewsroomOverlay
import com.example.data.ScriptProject
import com.example.data.local.CasStoredAsset

data class EnqueuedRenderResult(
    val jobId: String,
    val clipId: String,
    val inputHash: String
)

data class MacRigHealthResult(
    val resolvedUrl: String,
    val status: String,
    val loraVersion: Int,
    val bundleVersion: String?,
    val pendingPreferences: Int,
    val renderBridgeAvailable: Boolean,
    val activeRenderJobs: Int,
    val remotionWorkerPresent: Boolean = false,
    val remotionEnabled: Boolean = false,
    val remotionRendersCompleted: Int = 0,
    val remotionVersion: String? = null
)

data class RemotionInstallResult(
    val installed: Boolean,
    val remotionVersion: String?,
    val packageProviderId: String,
    val userRequest: String,
    val enableBrollPopups: Boolean
)

data class CoreTtsSynthesisResult(
    val ttsId: String,
    val audioUrl: String,
    val durationMs: Long,
    val waveformPeaks: List<Float>,
    val timedClauses: List<CoreStoryClause>
)

data class ChoppedBrollResult(
    val jobId: String,
    val videoUrl: String,
    val durationMs: Long,
    val brollCutCount: Int,
    val appliedOverlayCount: Int
)

data class GeneratedOverlayBriefResult(
    val kind: OverlayObjectKind,
    val headline: String,
    val subline: String,
    val calloutStat: String,
    val durationSec: Int
)

data class MacRigJobSnapshot(
    val jobId: String,
    val status: RenderJobStatus,
    val progressFraction: Float,
    val stageDetail: String,
    val errorMessage: String?,
    val videoUrl: String?,
    val durationMs: Long,
    val cer: Double? = null,
    val wespeakerCosine: Double? = null,
    val dnsmosP808: Double? = null
)

data class RemoteRenderSummary(
    val id: String,
    val filename: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val updatedAtUtc: String,
    val videoUrl: String,
    val thumbUrl: String
)

interface MacRigRenderGateway {
    suspend fun resolveAndCheckHealth(configuredUrl: String): MacRigHealthResult

    suspend fun listRemoteRenders(rigBaseUrl: String): List<RemoteRenderSummary>

    suspend fun enqueueMacRigRender(
        rigBaseUrl: String,
        project: ScriptProject,
        photoSha: String?,
        resolution: String = "720P",
        ratio: String = "9:16",
        maxDurationSec: Int = 10
    ): EnqueuedRenderResult

    suspend fun pollMacRigJob(rigBaseUrl: String, jobId: String): MacRigJobSnapshot

    suspend fun downloadRenderedMp4ToCas(videoUrl: String): CasStoredAsset

    suspend fun downloadAudioWavToCas(audioUrl: String): CasStoredAsset

    suspend fun synthesizeCoreStoryTts(
        rigBaseUrl: String,
        projectId: String,
        clauses: List<CoreStoryClause>
    ): CoreTtsSynthesisResult

    suspend fun chopTalkingHeadIntoBroll(
        rigBaseUrl: String,
        scriptId: String,
        title: String,
        sourceJobId: String?,
        sourceMp4Sha: String?,
        clauses: List<CoreStoryClause>,
        overlays: List<SavedNewsroomOverlay> = emptyList()
    ): ChoppedBrollResult

    suspend fun generateAiOverlayBrief(
        rigBaseUrl: String,
        briefPrompt: String
    ): GeneratedOverlayBriefResult

    suspend fun requestRemotionInstall(
        rigBaseUrl: String,
        userRequest: String,
        enableBrollPopups: Boolean = true
    ): RemotionInstallResult
}
