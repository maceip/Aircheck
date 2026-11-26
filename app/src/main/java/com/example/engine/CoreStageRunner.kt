package com.example.engine

import com.example.data.CorePipelineSession
import com.example.data.NewsroomLook
import com.example.data.gateway.MacRigRenderGateway
import com.example.data.gateway.RigTaskHandle
import com.example.data.local.AircheckLocalRepository
import com.example.data.local.AircheckProfileStore
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import java.io.File
import java.util.Locale

/**
 * Long Core Pipeline stages (TTS, talking head, silence detection, studio cut, backdrop plate)
 * independent of any screen. Runs inside [CoreStageWorker] so work continues while the app is
 * backgrounded. Every rig task id is persisted before polling, so a restarted worker resumes the
 * same rig task instead of re-submitting it.
 */
class CoreStageRunner(
    private val localRepo: AircheckLocalRepository,
    private val profileStore: AircheckProfileStore,
    private val coordinator: CoreStoryPipelineCoordinator,
    private val gateway: MacRigRenderGateway
) {
    class StageFailure(message: String) : IllegalStateException(message)


    private suspend fun rigUrl(): String =
        runCatching { profileStore.current() }.getOrNull()?.tailscaleRigUrl?.trim()
            ?.ifBlank { null } ?: DEFAULT_MAC_RIG_LAN_URL

    private suspend fun session(): CorePipelineSession =
        localRepo.getLatestCorePipelineSession() ?: throw StageFailure("No story loaded in the pipeline.")

    private suspend fun photoSha(explicit: String?): String? =
        explicit?.ifBlank { null } ?: runCatching { profileStore.current().persona.defaultPhotoSha }.getOrNull()?.ifBlank { null }

    /** Handle that persists the rig task id under [key] in the session and resumes it if present. */
    private fun handle(
        base: CorePipelineSession,
        key: String,
        onProgress: (String) -> Unit,
        onProgressFraction: (Float, String) -> Unit = { _, _ -> }
    ): RigTaskHandle =
        RigTaskHandle(
            resumeTaskId = base.pendingRigTasks[key],
            onSubmitted = { id ->
                val latest = localRepo.getLatestCorePipelineSession() ?: base
                localRepo.saveCorePipelineSession(latest.copy(pendingRigTasks = latest.pendingRigTasks + (key to id)))
            },
            onProgress = onProgress,
            onProgressFraction = onProgressFraction
        )

    private suspend fun clearTask(key: String) {
        val latest = localRepo.getLatestCorePipelineSession() ?: return
        if (key in latest.pendingRigTasks) {
            localRepo.saveCorePipelineSession(latest.copy(pendingRigTasks = latest.pendingRigTasks - key))
        }
    }

    suspend fun tts(localMicWav: File?, onProgress: (Float, String) -> Unit): String {
        val base = session()
        val preferPhone = runCatching { profileStore.current().preferOnPhoneVoice }.getOrNull() == true
        val progressLabel = when {
            localMicWav != null -> "Preparing recorded voiceover…"
            preferPhone -> "Voicing script on this phone…"
            else -> "Voicing script on your Mac studio…"
        }
        // No measured progress yet: report "unknown" (negative) so the UI shows no invented percent.
        onProgress(PROGRESS_UNKNOWN, progressLabel)
        val result = coordinator.synthesizeStage1Tts(
            session = base,
            rigBaseUrl = rigUrl(),
            localMicWavFile = localMicWav,
            task = handle(
                base,
                KEY_TTS,
                // Label-only updates (on-phone voice, rig stage text) carry no fraction.
                onProgress = { detail -> onProgress(PROGRESS_UNKNOWN, detail.ifBlank { progressLabel }) },
                onProgressFraction = { fraction, detail ->
                    onProgress(fraction.coerceIn(0.01f, 0.98f), detail.ifBlank { progressLabel })
                }
            )
        )
        val voiced = result.getOrElse {
            clearTask(KEY_TTS)
            throw StageFailure(userFacingStageError(it, "TTS synthesis failed."))
        }
        val updated = voiced.copy(
            voicedClauseTexts = voiced.clauses.map { it.spokenText.trim() },
            pendingRigTasks = voiced.pendingRigTasks - KEY_TTS
        )
        localRepo.saveCorePipelineSession(updated)
        val durLabel = if (updated.ttsDurationMs < 1_000L) {
            "${updated.ttsDurationMs}ms"
        } else {
            String.format(Locale.US, "%.1fs", updated.ttsDurationMs / 1000.0)
        }
        return "Voice ready ($durLabel)."
    }

    suspend fun head(photoSha: String?, onProgress: (Float, String) -> Unit): String {
        val base = session()
        val result = coordinator.matchSpeechToTalkingHeadStage3(
            session = base,
            rigBaseUrl = rigUrl(),
            photoSha = photoSha(photoSha),
            pollIntervalMs = 2_000L,
            onProgress = onProgress
        )
        result.getOrElse { throw StageFailure(userFacingStageError(it, "Talking-head render failed.")) }
        runCatching { detectSilence() }
        return "Talking head ready."
    }

    suspend fun detectSilence(): String {
        val base = session()
        val jobId = base.talkingHeadJobId ?: throw StageFailure("Render the talking head first.")
        val health = gateway.resolveAndCheckHealth(rigUrl())
        val res = gateway.detectSilenceGaps(health.resolvedUrl, jobId)
        val latest = session()
        if (latest.talkingHeadJobId == jobId) {
            localRepo.saveCorePipelineSession(latest.copy(silenceGaps = res.gaps, silenceGapsJobId = jobId))
        }
        return "${res.gaps.size} silence gaps found."
    }

    suspend fun cut(lookIdOverride: String?, photoSha: String?, onProgress: (Float, String) -> Unit): String {
        val base = session()
        val look: NewsroomLook? = lookIdOverride?.let { localRepo.getNewsroomLook(it) } ?: localRepo.getDefaultNewsroomLook()
        val signature = base.cutSignature(look)
        onProgress(PROGRESS_UNKNOWN, "Preparing studio cut…")
        val result = coordinator.chopTalkingHeadIntoBrollStage4(
            session = base,
            rigBaseUrl = rigUrl(),
            allSavedOverlays = localRepo.listSavedNewsroomOverlays(),
            photoSha = photoSha(photoSha),
            look = look,
            task = handle(
                base,
                KEY_CUT,
                onProgress = { detail -> onProgress(PROGRESS_UNKNOWN, detail.ifBlank { "Rendering studio cut on rig…" }) },
                onProgressFraction = { fraction, detail ->
                    onProgress(fraction.coerceIn(0.01f, 0.98f), detail.ifBlank { "Rendering studio cut on rig…" })
                }
            )
        )
        val (chopped, _) = result.getOrElse {
            clearTask(KEY_CUT)
            throw StageFailure(userFacingStageError(it, "Studio cut failed."))
        }
        localRepo.saveCorePipelineSession(
            chopped.copy(
                lastCutSignature = if (lookIdOverride == null) signature else null,
                pendingRigTasks = chopped.pendingRigTasks - KEY_CUT
            )
        )
        if (chopped.selectedOverlayIds.isNotEmpty()) profileStore.incrementRemotionUsedCount()
        return "Final video ready."
    }

    suspend fun backdrop(lookId: String, prompt: String, onProgress: (Float, String) -> Unit): String {
        val key = "backdrop:$lookId"
        val base = session()
        onProgress(PROGRESS_UNKNOWN, "Generating backdrop plate on rig…")
        val health = gateway.resolveAndCheckHealth(rigUrl())
        val plate = try {
            gateway.generateBackdropPlate(health.resolvedUrl, prompt, base.renderOptions.aspect, handle(base, key, onProgress = {}))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            clearTask(key)
            throw StageFailure(userFacingStageError(e, "Backdrop generation failed."))
        }
        clearTask(key)
        val look = localRepo.getNewsroomLook(lookId) ?: throw StageFailure("Look was deleted.")
        localRepo.saveNewsroomLook(look.copy(plateId = plate.plateId, plateUrl = plate.url, platePrompt = plate.prompt))
        return "Backdrop plate generated."
    }

    /** Re-voices edited clauses, then re-renders the talking head to match. */
    suspend fun resync(photoSha: String?, onProgress: (Float, String) -> Unit): String {
        // Two sequential stages: forward each stage's own measured fraction (or "unknown").
        tts(null) { p, l -> onProgress(p, l) }
        head(photoSha) { p, l -> onProgress(p, l) }
        return "Edited words re-voiced and talking head re-rendered."
    }

    companion object {
        /** Progress value meaning "running, but no measured fraction": the UI shows no percent. */
        const val PROGRESS_UNKNOWN = -1f
        const val KEY_TTS = "tts"
        const val KEY_CUT = "chop"
    }
}

/** Turns network/rig failures into one plain message instead of raw exception text. */
fun userFacingStageError(t: Throwable, fallback: String): String {
    if (t is MacStudioOfflineException) return t.message.orEmpty()
    var c: Throwable? = t
    while (c != null) {
        if (c is java.net.UnknownHostException || c is java.net.ConnectException ||
            c is java.net.SocketTimeoutException || c is java.net.NoRouteToHostException ||
            c is javax.net.ssl.SSLException
        ) return MacStudioOfflineException().message.orEmpty()
        val m = c.message.orEmpty()
        // 530 = the outside tunnel has no Mac behind it. A 502/503 can be a hosted model's own error
        // relayed by a healthy Mac, so it is shown as-is rather than as "Mac offline".
        if (m.contains("unreachable", true) || m.contains("HTTP 530") || m.contains("failed to connect", true)
        ) return MacStudioOfflineException().message.orEmpty()
        c = c.cause
    }
    return t.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
}
