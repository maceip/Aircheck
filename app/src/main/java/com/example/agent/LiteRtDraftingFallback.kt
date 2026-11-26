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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Downloaded Gemma (gemma-4-E2B-it) drafting engine via LiteRT-LM, used as the on-device fallback when
 * the AICore system model (the TPU-accelerated primary) is unavailable on a device or a pass fails.
 *
 * Lifecycle follows the LiteRT-LM mandates: weights load off the main thread on a single-thread
 * executor that solely owns the [Engine]/[Conversation]; both are closed exactly once. CPU backend
 * is used so the fallback runs on every device regardless of GPU/OpenCL support.
 */
class LiteRtDraftingEngine(private val context: Context) : AutoCloseable {
    // One single-thread executor owns the Engine and every Conversation (LiteRT-LM mandate).
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null

    @Volatile
    var isReady: Boolean = false
        private set

    /** Load (or reuse) the model weights. Returns true once the engine is ready to generate. */
    suspend fun initialize(modelFile: File): Boolean = withContext(dispatcher) {
        mutex.withLock { initializeLocked(modelFile) }
    }

    private fun initializeLocked(modelFile: File): Boolean {
        if (isReady && loadedPath == modelFile.absolutePath) return true
        releaseLocked()
        var created: Engine? = null
        return try {
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path,
            )
            created = Engine(config)
            created.initialize()
            // Store the engine only after initialize() returned: closing a failed engine throws.
            engine = created
            loadedPath = modelFile.absolutePath
            isReady = true
            true
        } catch (_: Throwable) {
            engine = null
            loadedPath = null
            isReady = false
            false
        }
    }

    /**
     * Generate a completion for [prompt]. Loads [modelFile] on first use. Every drafting pass is
     * independent, so each call gets a fresh [Conversation] (no history carried between passes).
     */
    suspend fun generate(modelFile: File, prompt: String): String = withContext(dispatcher) {
        mutex.withLock {
            if (!initializeLocked(modelFile)) {
                throw IllegalStateException("On-device Gemma model failed to load.")
            }
            val conv: Conversation = checkNotNull(engine).createConversation(
                ConversationConfig(systemInstruction = Contents.of(SYSTEM_INSTRUCTION))
            )
            try {
                val out = StringBuilder()
                conv.sendMessageAsync(prompt).collect { chunk -> out.append(chunk.toString()) }
                out.toString()
            } finally {
                runCatching { conv.close() }
            }
        }
    }

    private fun releaseLocked() {
        val e = engine
        engine = null
        loadedPath = null
        isReady = false
        if (e != null) runCatching { e.close() }
    }

    /** Unload the weights (e.g. before the model file is deleted). */
    suspend fun release() = withContext(dispatcher) { mutex.withLock { releaseLocked() } }

    override fun close() {
        scope.launch { mutex.withLock { releaseLocked() } }
            .invokeOnCompletion {
                scope.cancel()
                executor.shutdown()
            }
    }

    private companion object {
        const val SYSTEM_INSTRUCTION =
            "You are a broadcast news drafting assistant. Always respond with a single " +
                "valid JSON object matching the requested schema, and nothing else."
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
