package com.example.agent

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.CachedContext
import com.google.mlkit.genai.prompt.CreateCachedContextRequest
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

/**
 * Typed structured output schema compiled by `com.google.mlkit:genai-schema-compiler` for
 * on-device Gemini Nano broadcast packaging & angle extraction.
 */
@Generable
data class StructuredBroadcastPackaging(
    @Guide(description = "Punchy broadcast lower-third chyron headline, max 36 characters")
    var chyronHeadline: String = "",

    @Guide(description = "Concise broadcast subline or source attribution, max 48 characters")
    var chyronSubline: String = "",

    @Guide(description = "Sharpest angle label extracted from the spoken seed, 2 to 4 words")
    var primaryAngleLabel: String = ""
)

data class OnDevicePromptAvailability(
    val featureStatus: Int,
    val isModelAvailable: Boolean,
    val isStructuredOutputSupported: Boolean,
    val isPrefixCachingSupported: Boolean
)

/**
 * ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`) wrapper for AICore Gemini Nano.
 *
 * Constitutional Law 2 compliance:
 * - Strictly probes `GenerativeModel.checkStatus() == FeatureStatus.AVAILABLE` and
 *   `isStructuredOutputFeatureAvailable()` before claiming on-device readiness or running inference.
 * - Never fabricates synthetic headlines or angles when AICore Gemini Nano is absent (`UNAVAILABLE`).
 * - Releases `GenerativeModel.close()` cleanly on `close()`.
 */
class OnDevicePromptEngine(
    private val modelProvider: () -> GenerativeModel = { Generation.getClient() }
) : AutoCloseable {

    private var cachedModel: GenerativeModel? = null
    private var activePrefixCacheName: String? = null

    private fun model(): GenerativeModel {
        val existing = cachedModel
        if (existing != null) return existing
        return modelProvider().also { cachedModel = it }
    }

    suspend fun checkAvailability(): OnDevicePromptAvailability {
        return try {
            val gm = model()
            val status = gm.checkStatus()
            val available = status == FeatureStatus.AVAILABLE
            val structured = if (available) gm.isStructuredOutputFeatureAvailable() else false
            val caching = if (available) gm.isCachingFeatureAvailable() else false
            OnDevicePromptAvailability(
                featureStatus = status,
                isModelAvailable = available,
                isStructuredOutputSupported = structured,
                isPrefixCachingSupported = caching
            )
        } catch (_: GenAiException) {
            OnDevicePromptAvailability(
                featureStatus = FeatureStatus.UNAVAILABLE,
                isModelAvailable = false,
                isStructuredOutputSupported = false,
                isPrefixCachingSupported = false
            )
        } catch (_: Throwable) {
            OnDevicePromptAvailability(
                featureStatus = FeatureStatus.UNAVAILABLE,
                isModelAvailable = false,
                isStructuredOutputSupported = false,
                isPrefixCachingSupported = false
            )
        }
    }

    /**
     * Caches a >200-word studio style/steering prefix on AICore when prefix caching is supported.
     */
    suspend fun cacheLongSteeringPrefixIfEligible(
        cacheKey: String,
        prefixText: String
    ): CachedContext? {
        val wordCount = prefixText.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        if (wordCount <= 200) return null
        val availability = checkAvailability()
        if (!availability.isModelAvailable || !availability.isPrefixCachingSupported) return null

        return try {
            val gm = model()
            val req = CreateCachedContextRequest.Builder(
                cacheKey,
                PromptPrefix(prefixText)
            ).build()
            gm.caches.create(req).also { created ->
                activePrefixCacheName = created.name
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Generates a `@Generable` [StructuredBroadcastPackaging] strictly when AICore Gemini Nano and
     * Structured Output are verified `AVAILABLE` on-device. Returns `Result.failure` honestly otherwise.
     */
    suspend fun generateBroadcastPackaging(
        draftPlainText: String
    ): Result<StructuredBroadcastPackaging> {
        val clean = draftPlainText.trim()
        if (clean.isEmpty()) {
            return Result.failure(IllegalArgumentException("draftPlainText must not be empty."))
        }
        val availability = checkAvailability()
        if (!availability.isModelAvailable || !availability.isStructuredOutputSupported) {
            return Result.failure(
                IllegalStateException(
                    "AICore Gemini Nano structured output is not installed/available on this device (status=${availability.featureStatus})."
                )
            )
        }
        return try {
            val gm = model()
            val prompt = "Extract broadcast lower-third packaging for this script:\n$clean"
            val baseRequest = GenerateContentRequest.Builder(TextPart(prompt)).build()
            val typedRequest = generateTypedContentRequest(baseRequest, StructuredBroadcastPackaging::class)
            val response = gm.generateContent(typedRequest)
            val parsed = response.candidates.firstOrNull()?.response
                ?: return Result.failure(IllegalStateException("Gemini Nano returned no structured candidates."))
            Result.success(parsed)
        } catch (e: GenAiException) {
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override fun close() {
        runCatching { cachedModel?.close() }
        cachedModel = null
        activePrefixCacheName = null
    }
}
