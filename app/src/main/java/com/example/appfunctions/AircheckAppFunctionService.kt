package com.example.appfunctions

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.example.AircheckApplication
import com.example.data.ClipRecord
import com.example.data.NewsroomStageConfig
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AppFunctionSerializable(isDescribedByKDoc = true)
data class CreatedDraftResult(
    val scriptId: String,
    val title: String,
    val targetDurationSec: Int,
    val beatCount: Int
)

@AppFunctionSerializable(isDescribedByKDoc = true)
data class QueuedJobResult(
    val jobId: String,
    val clipId: String,
    val photoSha: String,
    val status: String
)

@AppFunctionSerializable(isDescribedByKDoc = true)
data class ClipSummaryItem(
    val clipId: String,
    val title: String,
    val durationMs: Long,
    val finalSha: String?,
    val remoteFinalUrl: String?
)

@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "AircheckAppFunctionService",
    appFunctionXmlFileName = "aircheck_app_function_service"
)
abstract class BaseAircheckAppFunctionService : AppFunctionService() {

    private val container by lazy {
        (applicationContext as AircheckApplication).container
    }

    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun createScriptDraft(
        topic: String,
        targetDurationSec: Int = 0
    ): CreatedDraftResult = withContext(Dispatchers.IO) {
        val cleanTopic = topic.trim()
        if (cleanTopic.isEmpty()) {
            throw AppFunctionInvalidArgumentException("Topic must not be blank.")
        }
        val resolvedTarget = when (targetDurationSec) {
            0 -> 30
            15, 30, 45, 60 -> targetDurationSec
            else -> throw AppFunctionInvalidArgumentException(
                "targetDurationSec must be 15, 30, 45, 60, or 0 for default (30)."
            )
        }

        try {
            val settings = container.profileStore.current()
            val draft = ScriptProject(
                title = cleanTopic.take(64),
                ideaSpark = cleanTopic,
                targetDurationSec = resolvedTarget,
                beats = listOf(
                    ScriptBeat(index = 0, spokenText = cleanTopic, durationSec = resolvedTarget)
                ),
                stageConfig = NewsroomStageConfig(
                    photoSha = settings.persona.defaultPhotoSha.ifBlank { null },
                    stage4Mode = settings.stage4Mode
                ),
                voiceSource = VoiceSource.ENROLLED_VOICE_CLONE
            )
            container.localRepo.saveScript(draft)
            container.profileStore.setActiveScriptId(draft.id)

            CreatedDraftResult(
                scriptId = draft.id,
                title = draft.title,
                targetDurationSec = draft.targetDurationSec,
                beatCount = draft.activeBeats.size
            )
        } catch (e: AppFunctionInvalidArgumentException) {
            throw e
        } catch (e: Exception) {
            throw AppFunctionAppUnknownException(e.localizedMessage ?: "Failed to create script draft.")
        }
    }

    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun renderClipWithSavedPhoto(
        scriptId: String,
        presetPhotoId: String? = null
    ): QueuedJobResult = withContext(Dispatchers.IO) {
        val cleanId = scriptId.trim()
        if (cleanId.isEmpty()) {
            throw AppFunctionInvalidArgumentException("scriptId must not be blank.")
        }
        val script = container.localRepo.getScriptById(cleanId)
            ?: throw AppFunctionElementNotFoundException("No script draft found with scriptId=$cleanId.")
        if (script.activeBeats.isEmpty()) {
            throw AppFunctionInvalidArgumentException("Script $cleanId contains no active spoken beats.")
        }

        val settings = container.profileStore.current()
        val photoSha = presetPhotoId?.trim()?.takeIf { it.isNotEmpty() }
            ?: script.stageConfig.activePhotoSha
            ?: settings.persona.defaultPhotoSha.takeIf { it.isNotBlank() }

        try {
            val health = container.macRigGateway.resolveAndCheckHealth(settings.tailscaleRigUrl)
            val enqueued = container.macRigGateway.enqueueMacRigRender(
                rigBaseUrl = health.resolvedUrl,
                project = script,
                photoSha = photoSha
            )
            val now = System.currentTimeMillis()
            container.localRepo.upsertClip(
                ClipRecord(
                    id = enqueued.clipId,
                    scriptId = script.id,
                    jobId = enqueued.jobId,
                    title = script.title.ifBlank { "Untitled Aircheck" },
                    photoSha = photoSha,
                    voiceSha = script.masterAudioSha,
                    alignSha = null,
                    lipsyncSha = null,
                    finalSha = null,
                    status = RenderJobStatus.QUEUED,
                    progressFraction = 0.05f,
                    stageDetail = "Queued on Mac Studio rig",
                    errorMessage = null,
                    durationMs = script.totalActiveSeconds * 1000L,
                    waveformPeaks = emptyList(),
                    thumbHash = null,
                    alignedWords = emptyList(),
                    chyronHeadline = script.stageConfig.chyronHeadline.ifBlank { script.title },
                    chyronSubline = script.stageConfig.chyronSubline,
                    remoteLipsyncUrl = null,
                    remoteFinalUrl = null,
                    exportedUri = null,
                    createdAtMillis = now,
                    updatedAtMillis = now
                )
            )
            QueuedJobResult(
                jobId = enqueued.jobId,
                clipId = enqueued.clipId,
                photoSha = photoSha.orEmpty(),
                status = RenderJobStatus.QUEUED.name
            )
        } catch (e: IllegalStateException) {
            throw AppFunctionInvalidArgumentException(e.localizedMessage ?: "Render preconditions not satisfied.")
        } catch (e: Exception) {
            throw AppFunctionAppUnknownException(e.localizedMessage ?: "Failed to queue Mac Rig render.")
        }
    }

    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun listReadyClips(
        limit: Int = 0
    ): List<ClipSummaryItem> = withContext(Dispatchers.IO) {
        val resolvedLimit = when {
            limit == 0 -> 5
            limit in 1..25 -> limit
            else -> throw AppFunctionInvalidArgumentException("limit must be between 1 and 25, or 0 for default (5).")
        }
        try {
            container.localRepo.listReadyClips(resolvedLimit).map { clip ->
                ClipSummaryItem(
                    clipId = clip.id,
                    title = clip.title,
                    durationMs = clip.durationMs,
                    finalSha = clip.finalSha,
                    remoteFinalUrl = clip.remoteFinalUrl
                )
            }
        } catch (e: Exception) {
            throw AppFunctionAppUnknownException(e.localizedMessage ?: "Unable to read ready clips.")
        }
    }
}
