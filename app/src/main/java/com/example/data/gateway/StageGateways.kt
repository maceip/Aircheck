package com.example.data.gateway

import com.example.data.RenderJobStatus
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
    val activeRenderJobs: Int
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
}
