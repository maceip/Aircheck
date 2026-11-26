package com.example.agent

import android.content.Context
import com.example.data.gateway.GeneratedOverlayBriefResult
import com.example.draft.AnglesResponse
import com.example.draft.ArcResponse
import com.example.draft.BrushNudge
import com.example.draft.BrushSwapResponse
import com.example.draft.DensityResponse
import com.example.draft.RewriteResponse
import com.example.draft.SeamHealResponse
import com.example.draft.SynthesizeResponse
import com.example.draft.SynthesizeScriptResponse
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors

/**
 * Bundled Gemma (gemma-4-E2B-it) drafting engine via LiteRT-LM, used as the on-device fallback when
 * AICore Gemini Nano (the TPU/NPU-accelerated primary) is unavailable on a device.
 *
 * Lifecycle follows the LiteRT-LM mandates: weights load off the main thread on a single-thread
 * executor that solely owns the [Engine]/[Conversation]; both are closed exactly once. CPU backend
 * is used so the fallback runs on every device regardless of GPU/OpenCL support.
 */
class LiteRtDraftingEngine(private val context: Context) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var loadedPath: String? = null

    @Volatile
    var isReady: Boolean = false
        private set

    /** Load (or reuse) the model weights. Returns true once the engine is ready to generate. */
    suspend fun initialize(modelFile: File): Boolean = mutex.withLock {
        if (isReady && loadedPath == modelFile.absolutePath) return@withLock true
        releaseLocked()
        try {
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path,
            )
            engine = Engine(config).also { it.initialize() }
            conversation = checkNotNull(engine).createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are a broadcast news drafting assistant. Always respond with a single " +
                            "valid JSON object matching the requested schema, and nothing else."
                    )
                )
            )
            loadedPath = modelFile.absolutePath
            isReady = true
            true
        } catch (_: Throwable) {
            releaseLocked()
            false
        }
    }

    /** Generate a completion for [prompt] on the owning executor. */
    suspend fun generate(prompt: String): String = mutex.withLock {
        val conv = conversation ?: throw IllegalStateException("LiteRT drafting engine not initialized")
        val out = StringBuilder()
        conv.sendMessageAsync(prompt).collect { chunk -> out.append(chunk.toString()) }
        out.toString()
    }

    private fun releaseLocked() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
        isReady = false
    }

    override fun close() {
        scope.launch { mutex.withLock { releaseLocked() } }
            .invokeOnCompletion {
                scope.cancel()
                executor.shutdown()
            }
    }
}

/**
 * On-device backend that prefers AICore Gemini Nano (TPU/NPU) and falls back to the bundled LiteRT
 * Gemma engine when AICore is absent or a pass fails. The fallback stays *within* the on-device tier
 * (it never silently routes to the Mac rig), and is only used when the Gemma weights are downloaded.
 * The fallback reuses [OnDevicePromptEngine]'s prompt/JSON-parse logic via its completion override.
 */
class FallbackOnDeviceLlmBackend(
    private val primary: OnDeviceLlmBackend,
    private val fallback: OnDeviceLlmBackend,
    private val fallbackReady: () -> Boolean,
) : OnDeviceLlmBackend {

    override suspend fun checkAvailability(): OnDevicePromptAvailability {
        val p = primary.checkAvailability()
        if (p.isModelAvailable) return p
        return fallback.checkAvailability()
    }

    private suspend fun <T> withFallback(
        primaryCall: suspend () -> Result<T>,
        fallbackCall: suspend () -> Result<T>,
    ): Result<T> {
        val p = primaryCall()
        if (p.isSuccess || !fallbackReady()) return p
        val f = fallbackCall()
        return if (f.isSuccess) f else p
    }

    override suspend fun generateBroadcastPackaging(draftPlainText: String) =
        // Structured output is AICore-only; the LiteRT fallback cannot produce typed packaging.
        primary.generateBroadcastPackaging(draftPlainText)

    override suspend fun generateOverlayBrief(briefTopic: String) =
        withFallback({ primary.generateOverlayBrief(briefTopic) }, { fallback.generateOverlayBrief(briefTopic) })

    override suspend fun extractAngles(seedTranscript: String, steeringConstraints: List<String>) =
        withFallback({ primary.extractAngles(seedTranscript, steeringConstraints) }, { fallback.extractAngles(seedTranscript, steeringConstraints) })

    override suspend fun arc(
        seedTranscript: String,
        steeringConstraints: List<String>,
        selectedAngles: List<String>,
        targetSec: Int,
        rerollIndex: Int?,
        existingBeats: List<String>?,
    ) = withFallback(
        { primary.arc(seedTranscript, steeringConstraints, selectedAngles, targetSec, rerollIndex, existingBeats) },
        { fallback.arc(seedTranscript, steeringConstraints, selectedAngles, targetSec, rerollIndex, existingBeats) },
    )

    override suspend fun synthesize(
        seedTranscript: String,
        selectedAngles: List<String>,
        steeringConstraints: List<String>,
        slot: Int?,
        existingDrafts: List<String>,
    ) = withFallback(
        { primary.synthesize(seedTranscript, selectedAngles, steeringConstraints, slot, existingDrafts) },
        { fallback.synthesize(seedTranscript, selectedAngles, steeringConstraints, slot, existingDrafts) },
    )

    override suspend fun synthesizeScript(
        seedTranscript: String,
        selectedAngles: List<String>,
        steeringConstraints: List<String>,
        arcBeats: List<String>,
        targetSec: Int,
    ) = withFallback(
        { primary.synthesizeScript(seedTranscript, selectedAngles, steeringConstraints, arcBeats, targetSec) },
        { fallback.synthesizeScript(seedTranscript, selectedAngles, steeringConstraints, arcBeats, targetSec) },
    )

    override suspend fun brushSwap(
        markedText: String,
        steeringConstraints: List<String>,
        nudge: BrushNudge?,
        excludeAlternatives: List<String>,
    ) = withFallback(
        { primary.brushSwap(markedText, steeringConstraints, nudge, excludeAlternatives) },
        { fallback.brushSwap(markedText, steeringConstraints, nudge, excludeAlternatives) },
    )

    override suspend fun seamHeal(markedText: String, operation: String, steeringConstraints: List<String>) =
        withFallback(
            { primary.seamHeal(markedText, operation, steeringConstraints) },
            { fallback.seamHeal(markedText, operation, steeringConstraints) },
        )

    override suspend fun adjustDensity(markedText: String, deltaPercent: Int, steeringConstraints: List<String>) =
        withFallback(
            { primary.adjustDensity(markedText, deltaPercent, steeringConstraints) },
            { fallback.adjustDensity(markedText, deltaPercent, steeringConstraints) },
        )

    override suspend fun rewrite(text: String, mode: String, steeringConstraints: List<String>) =
        withFallback({ primary.rewrite(text, mode, steeringConstraints) }, { fallback.rewrite(text, mode, steeringConstraints) })

    override fun close() {
        runCatching { primary.close() }
        runCatching { fallback.close() }
    }
}

/** Availability reported by the LiteRT fallback: ready only when the Gemma weights are loaded. */
fun liteRtAvailability(ready: Boolean): OnDevicePromptAvailability = OnDevicePromptAvailability(
    featureStatus = if (ready) FeatureStatus.AVAILABLE else FeatureStatus.UNAVAILABLE,
    isModelAvailable = ready,
    isStructuredOutputSupported = false,
    isPrefixCachingSupported = false,
)
