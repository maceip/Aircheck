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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result returned when a new Aircheck broadcast script draft is created.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class CreatedDraftResult(
    /** The unique identifier of the created script draft. */
    val scriptId: String,
    /** The working title of the script draft. */
    val title: String,
    /** The target spoken duration in seconds (15, 30, 45, or 60). */
    val targetDurationSec: Int,
    /** The number of active spoken beats stored in the draft. */
    val beatCount: Int
)

/**
 * Result returned when a cloud talking-head render job is queued.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class QueuedJobResult(
    /** The unique cloud render job identifier. */
    val jobId: String,
    /** The local Room clip identifier tracking live pipeline progress. */
    val clipId: String,
    /** The SHA-256 content digest of the photo used for rendering. */
    val photoSha: String,
    /** The initial pipeline status string (for example, QUEUED). */
    val status: String
)

/**
 * Summary item representing a completed Aircheck video clip ready to watch or export.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ClipSummaryItem(
    /** The unique clip identifier. */
    val clipId: String,
    /** The broadcast segment title. */
    val title: String,
    /** The duration of the rendered video in milliseconds. */
    val durationMs: Long,
    /** The immutable SHA-256 digest of the final MP4 file, if available. */
    val finalSha: String?,
    /** The streaming URL of the final MP4 in Cloud Storage, if available. */
    val remoteFinalUrl: String?
)

/**
 * Android 16/17 Jetpack AppFunctions service exposing Aircheck's core creation workflows
 * directly to the OS, Gemini system assistant, and voice shortcuts without opening the UI.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "AircheckAppFunctionService",
    appFunctionXmlFileName = "aircheck_app_function_service"
)
abstract class BaseAircheckAppFunctionService : AppFunctionService() {

    private val container by lazy {
        (applicationContext as AircheckApplication).container
    }

    /**
     * Creates a new broadcast script draft in local storage from a topic or URL spark.
     *
     * @param topic The headline, thesis, or http(s) URL to draft a broadcast script around. Must not be blank.
     * @param targetDurationSec Target runtime in seconds: 15, 30, 45, or 60. Pass 0 to default to 30 seconds.
     * @return Metadata for the saved script draft including its scriptId.
     */
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
            val isUrl = cleanTopic.startsWith("http://") || cleanTopic.startsWith("https://")
            val honed = runCatching {
                if (isUrl) {
                    container.ingestGateway.ingestUrlToScript(
                        sourceUrl = cleanTopic.substringBefore(" "),
                        targetSeconds = resolvedTarget,
                        providers = settings.providers
                    )
                } else {
                    container.scriptGateway.honeSparkToScript(
                        spark = cleanTopic,
                        targetSeconds = resolvedTarget,
                        onStreamChunk = {}
                    )
                }
            }.getOrNull()

            val beats = honed?.beats ?: listOf(
                ScriptBeat(index = 0, spokenText = cleanTopic, durationSec = resolvedTarget)
            )
            val title = honed?.title?.ifBlank { cleanTopic.take(64) } ?: cleanTopic.take(64)
            val draft = ScriptProject(
                title = title,
                ideaSpark = cleanTopic,
                sourceUrl = if (isUrl) cleanTopic.substringBefore(" ") else "",
                targetDurationSec = resolvedTarget,
                beats = beats,
                stageConfig = NewsroomStageConfig(
                    photoSha = settings.persona.defaultPhotoSha.ifBlank { null },
                    stage4Mode = settings.stage4Mode,
                    chyronHeadline = honed?.chyronHeadline.orEmpty(),
                    chyronSubline = honed?.chyronSubline.orEmpty()
                ),
                voiceSource = settings.voiceSource
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

    /**
     * Queues a cloud talking-head video render for an existing script using a saved presenter photo.
     *
     * @param scriptId The identifier of the script draft to render.
     * @param presetPhotoId Optional SHA-256 digest of a cached presenter photo. Pass null to use the script or default profile photo.
     * @return Identifiers and initial status for the queued cloud render job.
     */
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
            ?: throw AppFunctionElementNotFoundException(
                "No saved presenter photo is available. Import a photo in Aircheck first."
            )

        try {
            val enqueued = container.renderJobGateway.enqueueClipRender(
                project = script,
                persona = settings.persona,
                providers = settings.providers,
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
                    stageDetail = "Queued via AppFunctions",
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
                photoSha = photoSha,
                status = RenderJobStatus.QUEUED.name
            )
        } catch (e: IllegalStateException) {
            throw AppFunctionInvalidArgumentException(e.localizedMessage ?: "Render preconditions not satisfied.")
        } catch (e: Exception) {
            throw AppFunctionAppUnknownException(e.localizedMessage ?: "Failed to queue cloud clip render.")
        }
    }

    /**
     * Lists completed Aircheck talking-head video clips ready for playback or export.
     *
     * @param limit Maximum number of ready clips to return (1 to 25). Pass 0 to default to 5 clips.
     * @return Most recently completed clips ordered newest first.
     */
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
