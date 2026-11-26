package com.example

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agent.OnDevicePromptEngine
import com.example.agent.liteRtAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the downloaded Gemma 4 E2B weights through LiteRT-LM on this device and through the same
 * prompt/JSON-parse path the AICore engine uses. Skipped when the weights are not downloaded.
 */
@RunWith(AndroidJUnit4::class)
class GemmaFallbackOnDeviceTest {
    @Test
    fun downloadedGemma_loadsAndDraftsAnglesThroughFallbackEngine() = runBlocking {
        val container = ApplicationProvider.getApplicationContext<AircheckApplication>().container
        val manager = container.modelDownloadManager
        assumeTrue("Gemma weights not downloaded on this device", manager.isReady())

        val t0 = System.currentTimeMillis()
        val raw = container.liteRtDraftingEngine.generate(
            manager.modelFile(),
            "Return exactly this JSON object and nothing else: {\"ok\": true}"
        )
        Log.i("GEMMA_FALLBACK", "raw load+generate ms=${System.currentTimeMillis() - t0} out=${raw.take(200)}")
        assertTrue("Gemma returned no text", raw.isNotBlank())

        val engine = OnDevicePromptEngine(
            modelProvider = { OnDevicePromptEngine.aicoreFullModelClient() },
            completionOverride = { prompt -> container.liteRtDraftingEngine.generate(manager.modelFile(), prompt) },
            availabilityOverride = { liteRtAvailability(manager.isReady()) },
            engineLlmMeta = OnDevicePromptEngine.LITERT_GEMMA_LLM_META,
        )
        val t1 = System.currentTimeMillis()
        val result = engine.extractAngles(
            seedTranscript = "Phones now run large language models locally, so small teams can ship AI " +
                "features without paying monthly cloud inference bills or sending user data off the device.",
            steeringConstraints = emptyList()
        )
        Log.i("GEMMA_FALLBACK", "extractAngles ms=${System.currentTimeMillis() - t1} result=$result")
        val angles = result.getOrThrow()
        assertTrue("fallback produced no angles", angles.extractedAngles.isNotEmpty())
        assertEquals(OnDevicePromptEngine.LITERT_GEMMA_LLM_META.model, angles.llmMeta?.model)
    }
}
