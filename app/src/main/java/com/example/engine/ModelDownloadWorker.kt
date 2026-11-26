package com.example.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.AircheckApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Downloads the Gemma fallback weights as a WorkManager `dataSync` foreground job so a multi-GB
 * transfer survives the app being backgrounded or killed. Recovery is automatic: a dropped
 * connection returns [Result.retry] (WorkManager re-runs with backoff once the network is back) and
 * [ModelDownloadManager] resumes from the bytes already on disk.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0L, 0L)

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override suspend fun doWork(): Result {
        val container = (applicationContext as AircheckApplication).container
        val manager = container.modelDownloadManager
        val log = container.pipelineLogger
        runCatching { setForeground(foreground(manager.state.value.downloadedBytes, manager.state.value.totalBytes)) }
        log.info("model", "download start attempt=$runAttemptCount have=${manager.state.value.downloadedBytes}")
        return coroutineScope {
            val reporter = launch {
                manager.state.sample(1_000).collect { s ->
                    runCatching { setForeground(foreground(s.downloadedBytes, s.totalBytes)) }
                }
            }
            try {
                val file = manager.download()
                log.info("model", "download verified bytes=${file.length()}")
                Result.success()
            } catch (e: CancellationException) {
                manager.markStopped()
                log.info("model", "download paused at=${manager.state.value.downloadedBytes}")
                throw e
            } catch (e: ModelDownloadManager.PermanentDownloadException) {
                manager.markStopped(error = e.message, failed = true)
                log.error("model", "download failed: ${e.message}")
                Result.failure()
            } catch (e: Exception) {
                // Transient (network / storage hiccup): keep the partial file and let WorkManager retry.
                manager.markStopped(error = "Connection lost — will resume automatically. (${e.message ?: e.javaClass.simpleName})")
                log.warn("model", "download interrupted at=${manager.state.value.downloadedBytes}: ${e.message}")
                Result.retry()
            } finally {
                reporter.cancel()
            }
        }
    }

    private fun foreground(done: Long, total: Long): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of on-device model downloads"
                }
            )
        }
        val percent = if (total > 0L) ((done * 100) / total).toInt() else 0
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Aircheck · downloading on-device model")
            .setContentText(if (total > 0L) "$percent% of ${formatModelBytes(total)}" else "Contacting model server…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total <= 0L)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val UNIQUE = "aircheck_model_download"
        private const val CHANNEL_ID = "aircheck_models"
        private const val NOTIF_ID = 4711

        /** Start or resume the download; an already-running download is kept. */
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(UNIQUE)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
        }

        /** Pause: stops the transfer and keeps the partial file for a later resume. */
        fun pause(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }
    }
}

fun formatModelBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%d KB".format(bytes / 1_000L)
}
