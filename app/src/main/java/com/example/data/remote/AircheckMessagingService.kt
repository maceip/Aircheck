package com.example.data.remote

import android.util.Log
import com.example.AircheckApplication
import com.example.data.ClipRecord
import com.example.data.RenderJobStatus
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "AircheckFcm"

/**
 * Receives high-priority FCM data pushes from Firebase Cloud Functions v2
 * when `final_{sha8}.mp4` lands in Cloud Storage or Phase A persona training completes.
 */
class AircheckMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        val app = applicationContext as? AircheckApplication ?: return
        serviceScope.launch {
            app.container.profileStore.setFcmToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data.isEmpty()) return
        val app = applicationContext as? AircheckApplication ?: return

        serviceScope.launch {
            when (data["event"]) {
                "CLIP_READY", "CLIP_STAGE_UPDATE", "CLIP_FAILED" -> {
                    val jobId = data["jobId"] ?: return@launch
                    val existing = app.container.localRepo.getClipByJobId(jobId)
                    val status = RenderJobStatus.fromRaw(data["status"])
                    val updated = (existing ?: ClipRecord(
                        id = data["clipId"] ?: jobId,
                        scriptId = data["scriptId"].orEmpty(),
                        jobId = jobId,
                        title = data["title"].orEmpty(),
                        photoSha = data["photoSha"],
                        voiceSha = data["voiceSha"],
                        alignSha = data["alignSha"],
                        lipsyncSha = data["lipsyncSha"],
                        finalSha = data["finalSha"],
                        status = status,
                        progressFraction = data["progressFraction"]?.toFloatOrNull() ?: 1f,
                        stageDetail = data["stageDetail"].orEmpty(),
                        errorMessage = data["errorMessage"],
                        durationMs = data["durationMs"]?.toLongOrNull() ?: 0L,
                        waveformPeaks = emptyList(),
                        thumbHash = data["thumbHash"],
                        alignedWords = emptyList(),
                        chyronHeadline = data["chyronHeadline"].orEmpty(),
                        chyronSubline = data["chyronSubline"].orEmpty(),
                        remoteLipsyncUrl = data["remoteLipsyncUrl"],
                        remoteFinalUrl = data["remoteFinalUrl"],
                        exportedUri = null,
                        createdAtMillis = System.currentTimeMillis(),
                        updatedAtMillis = System.currentTimeMillis()
                    )).copy(
                        status = status,
                        progressFraction = data["progressFraction"]?.toFloatOrNull() ?: existing?.progressFraction ?: 1f,
                        stageDetail = data["stageDetail"] ?: existing?.stageDetail.orEmpty(),
                        errorMessage = data["errorMessage"] ?: existing?.errorMessage,
                        finalSha = data["finalSha"] ?: existing?.finalSha,
                        remoteFinalUrl = data["remoteFinalUrl"] ?: existing?.remoteFinalUrl,
                        thumbHash = data["thumbHash"] ?: existing?.thumbHash,
                        updatedAtMillis = System.currentTimeMillis()
                    )
                    app.container.localRepo.upsertClip(updated)
                }

                "PERSONA_READY" -> {
                    app.container.profileStore.updatePersona(
                        headModelId = data["headModelId"],
                        voiceId = data["voiceId"],
                        trainingStatus = "READY",
                        trainingError = ""
                    )
                }
            }
        }
    }
}
