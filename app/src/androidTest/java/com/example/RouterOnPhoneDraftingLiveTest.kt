package com.example

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.LlmProviderChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With "Write scripts on: This phone" selected in Studio Settings, the app's own LLM stage router
 * must draft on-device (never calling the Mac rig). Skipped when the setting is on Mac Studio; the
 * test never changes the user's setting.
 */
@RunWith(AndroidJUnit4::class)
class RouterOnPhoneDraftingLiveTest {
    @Test
    fun router_draftsOnThisPhone_whenSettingSelectsIt() = runBlocking {
        val container = ApplicationProvider.getApplicationContext<AircheckApplication>().container
        val routing = container.profileStore.current().llmRouting
        assumeTrue(
            "Drafting is set to Mac Studio on this device",
            routing.drafting.provider == LlmProviderChoice.LOCAL_AICORE_NANO
        )
        assertEquals(LlmProviderChoice.LOCAL_AICORE_NANO, routing.scriptEdit.provider)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // An unroutable rig override proves the pass cannot have gone to the Mac.
            val dead = "http://127.0.0.1:9"
            val seed = "Small teams can now ship AI features that run entirely on the phone, with no cloud bill."
            val angles = container.llmStageRouter.extractAngles(seed, rigUrlOverride = dead)
            Log.i("ROUTER_PHONE", "angles=${angles.extractedAngles.map { it.label }} meta=${angles.llmMeta}")
            assertTrue(angles.extractedAngles.isNotEmpty())
            assertEquals(true, angles.llmMeta?.isLocal)
            val arc = container.llmStageRouter.arc(seedTranscript = seed, targetSec = 30, rigUrlOverride = dead)
            Log.i("ROUTER_PHONE", "arc=${arc.beats.map { it.label }} meta=${arc.llmMeta}")
            assertTrue(arc.beats.isNotEmpty())
        } finally {
            scenario.close()
        }
    }
}
