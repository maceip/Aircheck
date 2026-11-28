package com.example

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agent.OnDevicePromptEngine
import com.example.agent.liteRtAvailability
import com.example.data.LlmProviderChoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Live on-device benchmark on the exact 52-word benchmark script across:
 *   1. AICore system model (`nano-v4-full` via [OnDevicePromptEngine])
 *   2. Downloaded LiteRT-LM fallback (`gemma-4-E2B-it.litertlm`)
 *   3. End-to-end `setDraftOnPhone()` routing for DRAFTING, SCRIPT_EDIT, and NEWSROOM_OVERLAY
 *
 * Writes full structured results to `cacheDir/on_device_llm_benchmark.json` so host tooling can
 * pull exact latencies and verbatim outputs with zero truncation.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceLlmBenchmarkTest {

    private val seed =
        "What I didn't do: introduce a custom brand palette, new typography, an ownable motion " +
            "language, or bespoke illustrations. So \"brand-consistent adaptation\" is accurate; " +
            "\"uniquely branded component system\" would overstate this change. Its most " +
            "distinctive abb quality is the focus on who holds the work, what was recorded, and " +
            "why it changed."

    @Test
    fun benchmarkAicoreFullAndGemmaFallbackOnExact52WordScript(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AircheckApplication>()
        val container = app.container
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val report = JSONObject()
            report.put("seed", seed)

            // 1. AICore nano-v4-full live benchmark
            val aicore = container.onDevicePromptEngine
            val baseModel = aicore.baseModelName()
            val avail = aicore.checkAvailability()
            val aicoreJson = JSONObject()
                .put("base_model_name", baseModel ?: JSONObject.NULL)
                .put("available", avail.isModelAvailable)
                .put("structured_output", avail.isStructuredOutputSupported)
                .put("prefix_caching", avail.isPrefixCachingSupported)

            Log.i(TAG, "AICore base=$baseModel avail=$avail")
            assertEquals("nano-v4-full", baseModel)
            aicoreJson.put("passes", benchmarkEnginePasses(aicore, includeTypedPackaging = true))
            report.put("aicore_nano_v4_full", aicoreJson)

            // 2. Verify setDraftOnPhone() wires DRAFTING, SCRIPT_EDIT, AND NEWSROOM_OVERLAY
            val routingCheck = JSONObject()
            container.setDraftOnPhone(true)
            waitUntil(5_000L) {
                val r = container.profileStore.current().llmRouting
                r.drafting.provider == LlmProviderChoice.LOCAL_AICORE_NANO &&
                    r.scriptEdit.provider == LlmProviderChoice.LOCAL_AICORE_NANO &&
                    r.newsroomOverlay.provider == LlmProviderChoice.LOCAL_AICORE_NANO
            }
            val tRouterOverlay = System.currentTimeMillis()
            val routedOverlay = container.llmStageRouter.generateOverlayBrief(
                topic = seed,
                rigUrlOverride = "http://127.0.0.1:9"
            ).getOrThrow()
            val routerOverlayMs = System.currentTimeMillis() - tRouterOverlay
            assertEquals(true, routedOverlay.llmMeta.isLocal)
            assertTrue(routedOverlay.brief.headline.isNotBlank())
            routingCheck.put("all_three_stages_on_phone_verified", true)
            routingCheck.put("routed_overlay_ms", routerOverlayMs)
            routingCheck.put("routed_overlay_headline", routedOverlay.brief.headline)
            routingCheck.put("routed_overlay_subline", routedOverlay.brief.subline)
            routingCheck.put("routed_overlay_stat", routedOverlay.brief.calloutStat)

            // Verify toggling off flips all 3 back, then restore on-phone mode
            container.setDraftOnPhone(false)
            waitUntil(5_000L) {
                val r = container.profileStore.current().llmRouting
                r.drafting.provider == LlmProviderChoice.MAC_RIG_DEFAULT &&
                    r.scriptEdit.provider == LlmProviderChoice.MAC_RIG_DEFAULT &&
                    r.newsroomOverlay.provider == LlmProviderChoice.MAC_RIG_DEFAULT
            }
            container.setDraftOnPhone(true)
            waitUntil(5_000L) {
                val r = container.profileStore.current().llmRouting
                r.drafting.provider == LlmProviderChoice.LOCAL_AICORE_NANO &&
                    r.scriptEdit.provider == LlmProviderChoice.LOCAL_AICORE_NANO &&
                    r.newsroomOverlay.provider == LlmProviderChoice.LOCAL_AICORE_NANO
            }
            routingCheck.put("toggle_roundtrip_verified", true)
            report.put("overlay_routing_check", routingCheck)

            // 3. Downloaded LiteRT-LM Gemma 4 E2B fallback benchmark (if weights are present)
            val gemmaJson = JSONObject()
            val manager = container.modelDownloadManager
            gemmaJson.put("ready_on_disk", manager.isReady())
            if (manager.isReady()) {
                val modelFile = manager.modelFile()
                gemmaJson.put("model_bytes", modelFile.length())
                val tInit = System.currentTimeMillis()
                val loaded = container.liteRtDraftingEngine.initialize(modelFile)
                gemmaJson.put("init_ms", System.currentTimeMillis() - tInit)
                gemmaJson.put("loaded", loaded)
                if (loaded) {
                    val gemmaEngine = OnDevicePromptEngine(
                        modelProvider = { OnDevicePromptEngine.aicoreFullModelClient() },
                        completionOverride = { prompt -> container.liteRtDraftingEngine.generate(modelFile, prompt) },
                        availabilityOverride = { liteRtAvailability(true) },
                        engineLlmMeta = OnDevicePromptEngine.LITERT_GEMMA_LLM_META
                    )
                    gemmaJson.put("passes", benchmarkEnginePasses(gemmaEngine, includeTypedPackaging = false))
                }
            }
            report.put("litert_gemma_4_e2b", gemmaJson)

            val outFile = File(app.cacheDir, "on_device_llm_benchmark.json")
            outFile.writeText(report.toString(2))
            Log.i(TAG, "BENCHMARK_SAVED path=${outFile.absolutePath} bytes=${outFile.length()}")
        } finally {
            scenario.close()
        }
    }

    private suspend fun benchmarkEnginePasses(
        engine: OnDevicePromptEngine,
        includeTypedPackaging: Boolean
    ): JSONObject {
        val passes = JSONObject()

        // angles
        var t0 = System.currentTimeMillis()
        val anglesRes = engine.extractAngles(seed)
        val anglesMs = System.currentTimeMillis() - t0
        passes.put("angles", JSONObject().apply {
            put("ok", anglesRes.isSuccess)
            put("ms", anglesMs)
            anglesRes.fold(
                onSuccess = { a ->
                    put("extracted_angles", JSONArray().apply {
                        a.extractedAngles.forEach { put(JSONObject().put("label", it.label).put("preview", it.preview)) }
                    })
                    put("blindspots", JSONArray().apply {
                        a.blindspots.forEach { put(JSONObject().put("label", it.label).put("preview", it.preview)) }
                    })
                },
                onFailure = { put("error", it.message ?: it.toString()) }
            )
        })
        Log.i(TAG, "angles ok=${anglesRes.isSuccess} ms=$anglesMs")

        // arc
        t0 = System.currentTimeMillis()
        val arcRes = engine.arc(seedTranscript = seed, targetSec = 30)
        val arcMs = System.currentTimeMillis() - t0
        var arcBeatTexts: List<String> = emptyList()
        passes.put("arc", JSONObject().apply {
            put("ok", arcRes.isSuccess)
            put("ms", arcMs)
            arcRes.fold(
                onSuccess = { a ->
                    arcBeatTexts = a.beats.map { it.text }
                    put("beats", JSONArray().apply {
                        a.beats.forEach { put(JSONObject().put("label", it.label).put("text", it.text)) }
                    })
                },
                onFailure = { put("error", it.message ?: it.toString()) }
            )
        })
        Log.i(TAG, "arc ok=${arcRes.isSuccess} ms=$arcMs")

        // synthesizeScript
        t0 = System.currentTimeMillis()
        val synthRes = engine.synthesizeScript(
            seedTranscript = seed,
            arcBeats = arcBeatTexts,
            targetSec = 30
        )
        val synthMs = System.currentTimeMillis() - t0
        passes.put("synthesize_script", JSONObject().apply {
            put("ok", synthRes.isSuccess)
            put("ms", synthMs)
            synthRes.fold(
                onSuccess = { s ->
                    put("candidates", JSONArray().apply {
                        s.candidates.forEach { c ->
                            val words = c.text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                            put(JSONObject()
                                .put("posture_label", c.postureLabel)
                                .put("word_count", words)
                                .put("text", c.text))
                        }
                    })
                },
                onFailure = { put("error", it.message ?: it.toString()) }
            )
        })
        Log.i(TAG, "synthesize_script ok=${synthRes.isSuccess} ms=$synthMs")

        // rewrite
        t0 = System.currentTimeMillis()
        val rewriteRes = engine.rewrite(text = seed)
        val rewriteMs = System.currentTimeMillis() - t0
        passes.put("rewrite", JSONObject().apply {
            put("ok", rewriteRes.isSuccess)
            put("ms", rewriteMs)
            rewriteRes.fold(
                onSuccess = { r ->
                    put("text", r.text)
                    put("lock_parity_preserved", r.lockParityPreserved)
                },
                onFailure = { put("error", it.message ?: it.toString()) }
            )
        })
        Log.i(TAG, "rewrite ok=${rewriteRes.isSuccess} ms=$rewriteMs")

        // overlay_brief (first pass + warm second pass)
        t0 = System.currentTimeMillis()
        val ovlRes = engine.generateOverlayBrief(seed)
        val ovlMs = System.currentTimeMillis() - t0
        passes.put("overlay_brief", JSONObject().apply {
            put("ok", ovlRes.isSuccess)
            put("ms", ovlMs)
            ovlRes.fold(
                onSuccess = { o ->
                    put("kind", o.kind.name)
                    put("headline", o.headline)
                    put("subline", o.subline)
                    put("callout_stat", o.calloutStat)
                    put("duration_sec", o.durationSec)
                },
                onFailure = { put("error", it.message ?: it.toString()) }
            )
        })
        Log.i(TAG, "overlay_brief ok=${ovlRes.isSuccess} ms=$ovlMs")

        if (includeTypedPackaging) {
            t0 = System.currentTimeMillis()
            val pkgRes = engine.generateBroadcastPackaging(seed)
            val pkgMs = System.currentTimeMillis() - t0
            passes.put("typed_broadcast_packaging", JSONObject().apply {
                put("ok", pkgRes.isSuccess)
                put("ms", pkgMs)
                pkgRes.fold(
                    onSuccess = { p ->
                        put("chyron_headline", p.chyronHeadline)
                        put("chyron_subline", p.chyronSubline)
                        put("primary_angle_label", p.primaryAngleLabel)
                    },
                    onFailure = { put("error", it.message ?: it.toString()) }
                )
            })
            Log.i(TAG, "typed_broadcast_packaging ok=${pkgRes.isSuccess} ms=$pkgMs")
        }

        return passes
    }

    private suspend fun waitUntil(timeoutMs: Long, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(30L)
        }
        assertTrue("Timed out waiting for persisted routing state", predicate())
    }

    companion object {
        private const val TAG = "ONDEVICE_LLM_BENCH"
    }
}
