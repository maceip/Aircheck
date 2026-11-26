package com.example

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Reports which AICore system model each (preference, release stage) resolves to on this device. */
@RunWith(AndroidJUnit4::class)
class AicoreModelProbeTest {
    @Test
    fun reportAicoreModelMatrix() = runBlocking {
        val prefs = listOf("FAST" to ModelPreference.FAST, "FULL" to ModelPreference.FULL)
        val stages = listOf("STABLE" to ModelReleaseStage.STABLE, "PREVIEW" to ModelReleaseStage.PREVIEW)
        for ((pn, p) in prefs) for ((sn, s) in stages) {
            val line = runCatching {
                val gm = Generation.getClient(
                    generationConfig { modelConfig = modelConfig { preference = p; releaseStage = s } }
                )
                try {
                    val status = gm.checkStatus()
                    val name = runCatching { gm.getBaseModelName() }.getOrElse { "name_error:${it.message}" }
                    val limit = runCatching { gm.getTokenLimit() }.getOrElse { -1 }
                    val structured = runCatching { gm.isStructuredOutputFeatureAvailable() }.getOrElse { false }
                    "status=$status base=$name tokenLimit=$limit structured=$structured"
                } finally {
                    gm.close()
                }
            }.getOrElse { "error=${it.javaClass.simpleName}:${it.message}" }
            Log.i("AICORE_PROBE", "pref=$pn stage=$sn $line")
        }
    }
}
