package com.voicestudio.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.voicestudio.edl.PreferenceTelemetryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * WorkManager `CoroutineWorker` (constrained to `NetworkType.UNMETERED` Wi-Fi / Tailscale) that:
 * 1. Reads unsynced [PreferenceTelemetryRecord] rows from Room via [SyncPreferenceStore].
 * 2. Serializes newline-delimited `preferences.jsonl` strictly conforming to
 *    `docs/swarm/schemas/preference_tuple.schema.json` and `POST`s to `{tailscaleBaseUrl}/v1/preferences`.
 * 3. Marks rows synced ONLY after an HTTP 2xx response.
 * 4. Checks `{tailscaleBaseUrl}/v1/bundles/latest/manifest.json` -> if a newer `voice_lora_vXXX.gguf`
 *    exists, downloads + SHA-256 verifies ONLY the nightly delta and triggers live LoRA hot-swap
 *    via [VoiceEngineJni.hotSwapLora] without restarting the app.
 */
class PreferenceSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val rawBaseUrl = inputData.getString(KEY_TAILSCALE_BASE_URL)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { VoiceStudioRuntimeRegistry.configProvider?.getTailscaleBaseUrl() }
                .getOrNull()
                ?.trim()
                .orEmpty()

        val baseUrl = OtaBundleSyncManager.normalizeBaseUrl(rawBaseUrl)
            ?: return@withContext Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to "Tailscale Rig URL is not configured or invalid.")
            )

        val otaManager = VoiceStudioRuntimeRegistry.otaBundleSyncManager
            ?: OtaBundleSyncManager(applicationContext)
        val prefStore = VoiceStudioRuntimeRegistry.preferenceStore
        val hotSwapBridge = VoiceStudioRuntimeRegistry.loraHotSwapBridge
            ?: VoiceStudioRuntimeRegistry.defaultLoraHotSwapBridge
        val httpClient = OtaBundleSyncManager.defaultHttpClient()

        // Step 1: Upload unsynced preferences.jsonl records (only mark synced on HTTP 2xx).
        var uploadedCount = 0
        if (prefStore != null) {
            val unsynced = prefStore.listUnsyncedPreferences(MAX_PREFS_PER_BATCH)
            if (unsynced.isNotEmpty()) {
                val ndjsonBody = encodePreferencesNdjson(unsynced)
                val uploadOutcome = postPreferencesNdjson(httpClient, baseUrl, ndjsonBody)
                when (uploadOutcome) {
                    is UploadOutcome.Success -> {
                        prefStore.markPreferencesSynced(unsynced.map { it.timestamp })
                        uploadedCount = unsynced.size
                    }
                    is UploadOutcome.ClientRejected -> {
                        return@withContext Result.failure(
                            workDataOf(
                                KEY_ERROR_MESSAGE to uploadOutcome.reason,
                                KEY_UPLOADED_COUNT to 0
                            )
                        )
                    }
                    is UploadOutcome.TransientError -> {
                        return@withContext if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                            Result.retry()
                        } else {
                            Result.failure(
                                workDataOf(
                                    KEY_ERROR_MESSAGE to uploadOutcome.reason,
                                    KEY_UPLOADED_COUNT to 0
                                )
                            )
                        }
                    }
                }
            }
        }

        // Step 2: Check /v1/bundles/latest/manifest.json and pull ONLY nightly voice_lora_vXXX.gguf delta.
        val deltaResult = otaManager.pullLatestLoraDelta(baseUrl)
        if (deltaResult.isFailure) {
            val err = deltaResult.exceptionOrNull()
            val message = err?.message ?: "Failed checking latest LoRA bundle manifest"
            // If rig simply hasn't published a bundle yet (404), keep preference upload success.
            if (message.contains("HTTP 404")) {
                return@withContext Result.success(
                    workDataOf(
                        KEY_UPLOADED_COUNT to uploadedCount,
                        KEY_NEW_LORA_VERSION to otaManager.bundleState.value.activeLoraVersion,
                        KEY_LORA_HOT_SWAPPED to false
                    )
                )
            }
            return@withContext if (err is SecurityException || err is IllegalArgumentException) {
                Result.failure(
                    workDataOf(
                        KEY_UPLOADED_COUNT to uploadedCount,
                        KEY_ERROR_MESSAGE to message
                    )
                )
            } else if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        KEY_UPLOADED_COUNT to uploadedCount,
                        KEY_ERROR_MESSAGE to message
                    )
                )
            }
        }

        val delta = deltaResult.getOrNull()
        var hotSwapped = false
        var activeVersion = otaManager.bundleState.value.activeLoraVersion
        if (delta != null) {
            activeVersion = delta.loraVersion
            val swapRes = hotSwapBridge.onLoraHotSwapped(delta.loraVersion, delta.loraFile)
            if (swapRes.isFailure) {
                return@withContext Result.failure(
                    workDataOf(
                        KEY_UPLOADED_COUNT to uploadedCount,
                        KEY_NEW_LORA_VERSION to delta.loraVersion,
                        KEY_LORA_HOT_SWAPPED to false,
                        KEY_ERROR_MESSAGE to (swapRes.exceptionOrNull()?.message ?: "HotSwapLora failed")
                    )
                )
            }
            hotSwapped = swapRes.getOrDefault(false)
        }

        Result.success(
            workDataOf(
                KEY_UPLOADED_COUNT to uploadedCount,
                KEY_NEW_LORA_VERSION to activeVersion,
                KEY_LORA_HOT_SWAPPED to hotSwapped
            )
        )
    }

    private sealed interface UploadOutcome {
        data object Success : UploadOutcome
        data class ClientRejected(val reason: String) : UploadOutcome
        data class TransientError(val reason: String) : UploadOutcome
    }

    private fun postPreferencesNdjson(
        httpClient: OkHttpClient,
        normalizedBaseUrl: String,
        ndjsonPayload: String
    ): UploadOutcome {
        val url = "$normalizedBaseUrl/v1/preferences"
        val body = ndjsonPayload.toRequestBody(NDJSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> UploadOutcome.Success
                    response.code in 400..499 -> {
                        val detail = response.body?.string()?.take(240).orEmpty()
                        UploadOutcome.ClientRejected(
                            "POST /v1/preferences rejected with HTTP ${response.code}: $detail"
                        )
                    }
                    else -> {
                        UploadOutcome.TransientError(
                            "POST /v1/preferences failed with HTTP ${response.code}"
                        )
                    }
                }
            }
        } catch (e: IOException) {
            UploadOutcome.TransientError("POST /v1/preferences network error: ${e.message}")
        }
    }

    companion object {
        const val KEY_TAILSCALE_BASE_URL: String = "tailscale_base_url"
        const val KEY_UPLOADED_COUNT: String = "uploaded_count"
        const val KEY_NEW_LORA_VERSION: String = "new_lora_version"
        const val KEY_LORA_HOT_SWAPPED: String = "lora_hot_swapped"
        const val KEY_ERROR_MESSAGE: String = "error_message"

        private const val MAX_PREFS_PER_BATCH: Int = 200
        private const val MAX_RETRY_ATTEMPTS: Int = 3
        private val NDJSON_MEDIA_TYPE = "application/x-ndjson; charset=utf-8".toMediaType()

        private val ndjsonSerializer = Json {
            encodeDefaults = true
            prettyPrint = false
        }

        fun encodePreferencesNdjson(records: List<PreferenceTelemetryRecord>): String {
            return buildString {
                for (record in records) {
                    val line = ndjsonSerializer.encodeToString(PreferenceTelemetryRecord.serializer(), record)
                    require(!line.contains('\n')) { "PreferenceTelemetryRecord must serialize to single JSONL line" }
                    append(line)
                    append('\n')
                }
            }
        }
    }
}
