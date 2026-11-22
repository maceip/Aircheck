package com.example.data.remote

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

private const val TAG = "ModelRegistry"

enum class ModelHealth {
    UNCHECKED,
    HEALTHY,
    DEGRADED
}

data class ActiveModels(
    val textModelId: String = DEFAULT_TEXT_MODEL,
    val imageModelId: String = DEFAULT_IMAGE_MODEL,
    val health: ModelHealth = ModelHealth.UNCHECKED,
    val lastProbeError: String? = null
) {
    companion object {
        const val DEFAULT_TEXT_MODEL = "gemini-2.5-flash"
        const val DEFAULT_IMAGE_MODEL = "gemini-2.5-flash-image"
    }
}

/**
 * Centralizes active Firebase AI Logic model IDs with runtime override and startup health probe.
 */
object ModelRegistry {
    private val _models = MutableStateFlow(ActiveModels())
    val models: StateFlow<ActiveModels> = _models.asStateFlow()

    val textModelId: String
        get() = _models.value.textModelId

    val imageModelId: String
        get() = _models.value.imageModelId

    fun overrideModels(textModelId: String? = null, imageModelId: String? = null) {
        val current = _models.value
        _models.value = current.copy(
            textModelId = textModelId?.takeIf { it.isNotBlank() } ?: current.textModelId,
            imageModelId = imageModelId?.takeIf { it.isNotBlank() } ?: current.imageModelId
        )
    }

    suspend fun verifyStartupHealth() {
        try {
            val probeModel = Firebase.ai.generativeModel(textModelId)
            val response = withTimeout(15_000L) {
                probeModel.generateContent("Reply with OK.")
            }
            if (!response.text.isNullOrBlank()) {
                _models.value = _models.value.copy(
                    health = ModelHealth.HEALTHY,
                    lastProbeError = null
                )
                Log.d(TAG, "ModelRegistry probe healthy: $textModelId")
            } else {
                _models.value = _models.value.copy(
                    health = ModelHealth.DEGRADED,
                    lastProbeError = "Empty probe response"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ModelRegistry startup probe degraded: ${e.message}")
            _models.value = _models.value.copy(
                health = ModelHealth.DEGRADED,
                lastProbeError = e.localizedMessage ?: "Probe failed"
            )
        }
    }
}
