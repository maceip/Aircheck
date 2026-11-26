package com.example.engine

import android.content.Context
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable, zero-upkeep pipeline logging. Every event is appended to a bounded on-phone file and
 * batch-shipped (fire-and-forget) to the Mac rig's `POST /v1/logs`, which appends to its auto-rotating
 * generation.log. The user never manages log files: both sinks are size-capped and self-trimming.
 * Logging never blocks or fails a generation step.
 */
class PipelineLogger(
    context: Context,
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val deviceId: String = (android.os.Build.MODEL ?: "phone"),
) {
    private val logFile = File(context.noBackupFilesDir, "logs/pipeline.log").apply { parentFile?.mkdirs() }

    @Volatile
    var rigBaseUrl: String = DEFAULT_MAC_RIG_LAN_URL

    private val lock = Any()
    private val pending = ArrayDeque<JSONObject>()
    private var flushScheduled = false

    fun log(tag: String, msg: String, level: String = "info") {
        val line = "${System.currentTimeMillis()} $level $tag $msg"
        val event = JSONObject().put("tag", tag).put("msg", msg).put("level", level)
        synchronized(lock) {
            pending.addLast(event)
            while (pending.size > MAX_PENDING) pending.removeFirst()
            if (!flushScheduled) {
                flushScheduled = true
                scope.launch {
                    delay(FLUSH_DEBOUNCE_MS)
                    flush()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            synchronized(lock) { runCatching { appendBounded(line) } }
        }
    }

    fun info(tag: String, msg: String) = log(tag, msg, "info")
    fun warn(tag: String, msg: String) = log(tag, msg, "warn")
    fun error(tag: String, msg: String) = log(tag, msg, "error")

    private fun appendBounded(line: String) {
        logFile.appendText(line + "\n")
        if (logFile.length() > MAX_LOCAL_BYTES) {
            val tail = logFile.readLines().takeLast(LOCAL_KEEP_LINES).joinToString("\n")
            logFile.writeText(tail + "\n")
        }
    }

    private suspend fun flush() {
        val batch: List<JSONObject>
        synchronized(lock) {
            flushScheduled = false
            if (pending.isEmpty()) return
            batch = pending.toList()
        }
        val url = rigBaseUrl.trim().trimEnd('/')
        val body = JSONObject().put("device", deviceId).put("events", JSONArray(batch))
        val shipped = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$url/v1/logs")
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                okHttpClient.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (shipped) {
            synchronized(lock) {
                repeat(batch.size) { if (pending.isNotEmpty()) pending.removeFirst() }
            }
        }
    }

    /** Recent on-phone events for the Activity viewer when the rig is unreachable. */
    fun recentLocal(lines: Int = 200): List<String> =
        runCatching { logFile.readLines().takeLast(lines) }.getOrDefault(emptyList())

    companion object {
        private const val MAX_PENDING = 500
        private const val FLUSH_DEBOUNCE_MS = 1_500L
        private const val MAX_LOCAL_BYTES = 256_000L
        private const val LOCAL_KEEP_LINES = 400
    }
}
