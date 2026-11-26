package com.example

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real Spark -> Script drafting chain (angles -> arc -> script) on this device through the
 * AICore FULL system model, with no Mac rig involved. Fails if any pass fails.
 */
@RunWith(AndroidJUnit4::class)
class AicoreDraftingLiveTest {
    private val seed = "Phones now run large language models locally. That means a two person startup " +
        "can ship AI features without paying monthly cloud inference bills, and user data never leaves " +
        "the device. I think that changes who gets to build AI products."

    @Test
    fun aicoreFull_draftsAnglesArcAndScript() = runBlocking {
        // AICore refuses inference from background apps (error 30), so the real activity must be resumed.
        val scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        try {
            runChain()
        } finally {
            scenario.close()
        }
    }

    private suspend fun runChain() {
        val container = ApplicationProvider.getApplicationContext<AircheckApplication>().container
        val engine = container.onDevicePromptEngine
        val base = engine.baseModelName()
        Log.i("AICORE_DRAFT", "base=$base availability=${engine.checkAvailability()}")
        assertEquals("nano-v4-full", base)

        var t = System.currentTimeMillis()
        val angles = engine.extractAngles(seed).getOrThrow()
        Log.i("AICORE_DRAFT", "angles ms=${System.currentTimeMillis() - t} n=${angles.extractedAngles.size} $angles")
        assertTrue("no angles", angles.extractedAngles.isNotEmpty())

        t = System.currentTimeMillis()
        val arc = engine.arc(seedTranscript = seed, targetSec = 45).getOrThrow()
        Log.i("AICORE_DRAFT", "arc ms=${System.currentTimeMillis() - t} beats=${arc.beats.map { it.label + ": " + it.text }}")
        assertTrue("no arc beats", arc.beats.isNotEmpty())

        t = System.currentTimeMillis()
        val script = engine.synthesizeScript(
            seedTranscript = seed,
            arcBeats = arc.beats.map { it.text },
            targetSec = 45
        ).getOrThrow()
        Log.i("AICORE_DRAFT", "script ms=${System.currentTimeMillis() - t} n=${script.candidates.size} $script")
        assertTrue("no script candidates", script.candidates.isNotEmpty())
    }
}
