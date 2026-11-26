package com.example

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.clipstudio.VoiceGlowDitherOverlay
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Live GPU framebuffer verification of [VoiceGlowDitherOverlay] on physical Android 17 hardware:
 * drives real Choreographer frames via [ActivityScenario] (no ComposeTestRule idling deadlock on
 * live 60/120Hz animations), renders silence (`0.0f`), conversational speech (`0.06f`), and max
 * voice (`0.22f`) edge-to-edge on pure black, measures actual lit colored pixel heights from real
 * device screenshots, and asserts:
 * 1. Calm low listening rim during silence.
 * 2. Clear monotonic live vertical surge when speaking.
 * 3. Lit voice FX rises strictly `>= 1/3` (`>= 33.3%`) of screen height at max voice.
 */
@RunWith(AndroidJUnit4::class)
class VoiceGlowDitherOnDeviceTest {

    @Test
    fun voiceGlowDitherRespondsToLiveRmsAndRisesPastOneThirdScreenOnMaxVoice() {
        val levelState = mutableFloatStateOf(0f)
        lateinit var outDir: File

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                outDir = File(
                    activity.getExternalFilesDir(null) ?: activity.filesDir,
                    "voice_fx_screenshots"
                ).apply { mkdirs() }
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                activity.setContent {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    ) {
                        VoiceGlowDitherOverlay(
                            active = true,
                            level = levelState.floatValue
                        )
                    }
                }
            }

            // 1. Silence (0.0 RMS)
            Thread.sleep(650L)
            val silenceBmp = captureAndSave(outDir, "voice_fx_01_silence.png")
            val silenceReach = measureColoredReachFraction(silenceBmp)
            silenceBmp.recycle()

            // 2. Conversational speech (0.06 RMS)
            scenario.onActivity { levelState.floatValue = 0.06f }
            Thread.sleep(550L)
            val midBmp = captureAndSave(outDir, "voice_fx_02_speaking.png")
            val midReach = measureColoredReachFraction(midBmp)
            midBmp.recycle()

            // 3. Max voice (0.22 RMS -> 1.0 perceptual energy)
            scenario.onActivity { levelState.floatValue = 0.22f }
            Thread.sleep(550L)
            val maxBmp = captureAndSave(outDir, "voice_fx_03_max_voice.png")
            val maxReach = measureColoredReachFraction(maxBmp)
            maxBmp.recycle()

            assertTrue(
                "Silence dither glow should stay low at bottom (<=0.18), got $silenceReach",
                silenceReach in 0.03f..0.18f
            )
            assertTrue(
                "Speaking dither glow must rise clearly above silence ($midReach vs $silenceReach)",
                midReach > silenceReach + 0.07f
            )
            assertTrue(
                "Max voice dither glow must rise at least 1/3 (0.333) of screen height, got $maxReach " +
                    "(silence=$silenceReach, mid=$midReach)",
                maxReach >= 1f / 3f
            )
        }
    }

    private fun captureAndSave(dir: File, filename: String): Bitmap {
        val bmp = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "Failed to capture device screenshot"
        }
        FileOutputStream(File(dir, filename)).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 92, out)
        }
        return bmp
    }

    /**
     * Scans the middle 60% horizontal band from top to bottom (skipping the top 15% system status bar)
     * and returns the height from the bottom edge of the highest distinctly lit colored dither pixel
     * as a fraction of full screen height.
     */
    private fun measureColoredReachFraction(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val x0 = (w * 0.20f).toInt()
        val x1 = (w * 0.80f).toInt()
        val yStart = (h * 0.15f).toInt()
        val yEnd = (h * 0.96f).toInt()
        for (y in yStart until yEnd) {
            var litCount = 0
            var x = x0
            while (x < x1) {
                val px = bmp.getPixel(x, y)
                val r = AndroidColor.red(px)
                val g = AndroidColor.green(px)
                val b = AndroidColor.blue(px)
                val maxCh = maxOf(r, g, b)
                val minCh = minOf(r, g, b)
                if (maxCh >= 70 && (maxCh - minCh >= 28 || maxCh >= 160)) {
                    litCount++
                    if (litCount >= 3) {
                        return (h - y).toFloat() / h.toFloat()
                    }
                }
                x += 3
            }
        }
        return 0f
    }
}
