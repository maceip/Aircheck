package com.example.engine

import android.content.Context
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * Android 17 (`API 37`) background `ProfilingManager` trigger coordinator.
 *
 * Registers rate-limited system profiling triggers (`TRIGGER_TYPE_COLD_START`,
 * `TRIGGER_TYPE_ANOMALY`, `TRIGGER_TYPE_OOM`, and `TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE`)
 * on a dedicated daemon executor and records completed trace file paths under `filesDir/profiling`
 * with zero main-thread or UI overhead.
 */
class AircheckProfilingCoordinator(
    context: Context
) : AutoCloseable {

    private val appContext = context.applicationContext ?: context
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aircheck-profiling-mgr").apply { isDaemon = true }
    }

    @Volatile
    var lastRecordedTracePath: String? = null
        private set

    private val resultListener = Consumer<ProfilingResult> { result ->
        if (result.errorCode == ProfilingResult.ERROR_NONE) {
            val tracePath = result.resultFilePath
            if (!tracePath.isNullOrBlank()) {
                lastRecordedTracePath = tracePath
                runCatching {
                    val logDir = File(appContext.filesDir, "profiling").apply { mkdirs() }
                    File(logDir, "latest_trace.txt").writeText(tracePath)
                }
            }
        }
    }

    fun registerDefaultTriggers(): Boolean = runCatching {
        val manager = appContext.getSystemService(ProfilingManager::class.java) ?: return false
        manager.registerForAllProfilingResults(executor, resultListener)
        manager.addProfilingTriggers(buildDefaultTriggers())
        true
    }.getOrDefault(false)

    override fun close() {
        runCatching {
            val manager = appContext.getSystemService(ProfilingManager::class.java)
            manager?.unregisterForAllProfilingResults(resultListener)
        }
        executor.shutdown()
    }

    companion object {
        fun buildDefaultTriggers(): List<ProfilingTrigger> = listOf(
            ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_COLD_START)
                .setRateLimitingPeriodHours(24)
                .build(),
            ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_ANOMALY)
                .setRateLimitingPeriodHours(12)
                .build(),
            ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_OOM)
                .setRateLimitingPeriodHours(12)
                .build(),
            ProfilingTrigger.Builder(ProfilingTrigger.TRIGGER_TYPE_KILL_EXCESSIVE_CPU_USAGE)
                .setRateLimitingPeriodHours(24)
                .build()
        )
    }
}
