package com.example.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.AircheckApplication
import com.example.MainActivity
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Runs a long Core Pipeline stage as a WorkManager foreground service (`mediaProcessing`) with a
 * progress notification, so TTS / talking head / studio cut / backdrop keep running while the
 * app is in the background or its UI is gone. WorkManager re-runs the worker if the process is
 * killed; [CoreStageRunner] then resumes the persisted rig task instead of re-submitting it.
 */
class CoreStageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(labelFor(inputData.getString(KEY_OP)), 0f)

    override suspend fun doWork(): Result {
        val op = inputData.getString(KEY_OP) ?: return Result.failure(workDataOf(KEY_ERROR to "Missing stage."))
        val runner = (applicationContext as AircheckApplication).container.coreStageRunner
        setForeground(foreground(labelFor(op), 0f))
        return coroutineScope {
        val latest = MutableStateFlow(0f to labelFor(op))
        val reporter = launch {
            latest.collect { (p, label) ->
                setProgress(workDataOf(KEY_PROGRESS to p, KEY_LABEL to label, KEY_OP to op))
                runCatching { setForeground(foreground(label, p)) }
            }
        }
        val progress: (Float, String) -> Unit = { p, label -> latest.value = p to label }
        try {
            val photo = inputData.getString(KEY_PHOTO_SHA)
            val notice = when (op) {
                OP_TTS -> runner.tts(inputData.getString(KEY_MIC_WAV)?.let(::File), progress)
                OP_HEAD -> runner.head(photo, progress)
                OP_SILENCE -> runner.detectSilence()
                OP_CUT -> runner.cut(inputData.getString(KEY_LOOK_ID), photo, progress)
                OP_RESYNC -> runner.resync(photo, progress)
                OP_BACKDROP -> runner.backdrop(
                    inputData.getString(KEY_LOOK_ID) ?: error("look id"),
                    inputData.getString(KEY_PROMPT).orEmpty(),
                    progress
                )
                else -> throw CoreStageRunner.StageFailure("Unknown stage $op")
            }
            notifyDone(notice, success = true)
            Result.success(workDataOf(KEY_NOTICE to notice, KEY_OP to op))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = userFacingStageError(e, "${labelFor(op)} failed.")
            notifyDone(msg, success = false)
            Result.failure(workDataOf(KEY_ERROR to msg, KEY_OP to op))
        } finally {
            reporter.cancel()
        }
        }
    }

    private fun foreground(label: String, progress: Float): ForegroundInfo {
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Aircheck · ${labelFor(inputData.getString(KEY_OP))}")
            .setContentText(label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), progress <= 0f)
            .setContentIntent(openAppIntent())
            .build()
        return ForegroundInfo(NOTIF_PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
    }

    private fun notifyDone(text: String, success: Boolean) {
        ensureChannel(applicationContext)
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "Aircheck · ${labelFor(inputData.getString(KEY_OP))} done" else "Aircheck · stage failed")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { nm.notify(NOTIF_DONE_ID, n) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val UNIQUE_CORE = "aircheck_core_stage"
        const val UNIQUE_BACKDROP = "aircheck_backdrop"
        const val KEY_OP = "op"
        const val KEY_PHOTO_SHA = "photo_sha"
        const val KEY_MIC_WAV = "mic_wav"
        const val KEY_LOOK_ID = "look_id"
        const val KEY_PROMPT = "prompt"
        const val KEY_PROGRESS = "progress"
        const val KEY_LABEL = "label"
        const val KEY_NOTICE = "notice"
        const val KEY_ERROR = "error"
        const val OP_TTS = "tts"
        const val OP_HEAD = "head"
        const val OP_SILENCE = "silence"
        const val OP_CUT = "cut"
        const val OP_RESYNC = "resync"
        const val OP_BACKDROP = "backdrop"
        private const val CHANNEL_ID = "aircheck_pipeline"
        private const val NOTIF_PROGRESS_ID = 4701
        private const val NOTIF_DONE_ID = 4702

        fun labelFor(op: String?): String = when (op) {
            OP_TTS -> "Voicing script"
            OP_HEAD -> "Rendering talking head"
            OP_SILENCE -> "Detecting silence"
            OP_CUT -> "Rendering studio cut"
            OP_RESYNC -> "Re-voicing edits"
            OP_BACKDROP -> "Generating backdrop"
            else -> "Pipeline"
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Video pipeline", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Progress of voicing, talking-head and studio-cut renders"
                    }
                )
            }
        }

        /** Enqueues [op]; a stage already running for [unique] is kept (no duplicate rig work). */
        fun enqueue(context: Context, op: String, extras: Map<String, String?> = emptyMap(), unique: String = UNIQUE_CORE) {
            val data = Data.Builder().putString(KEY_OP, op)
            extras.forEach { (k, v) -> if (v != null) data.putString(k, v) }
            val req = OneTimeWorkRequestBuilder<CoreStageWorker>()
                .setInputData(data.build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(unique)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(unique, ExistingWorkPolicy.KEEP, req)
        }
    }
}
