package com.example.ui.clipstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGlowDitherTest {

    @Test
    fun rmsMappingGatesSilenceAndReachesFullEnergyOnMaxSpeech() {
        assertEquals(0f, mapMicRmsToVoiceEnergy(0f), 1e-5f)
        assertEquals(0f, mapMicRmsToVoiceEnergy(0.005f), 1e-5f)
        val soft = mapMicRmsToVoiceEnergy(0.025f)
        val normal = mapMicRmsToVoiceEnergy(0.06f)
        val loud = mapMicRmsToVoiceEnergy(0.14f)
        val maxClip = mapMicRmsToVoiceEnergy(1.0f)

        assertTrue("Soft speech should register above 0.25 (was $soft)", soft > 0.25f)
        assertTrue("Normal speech should exceed soft speech ($normal vs $soft)", normal > soft + 0.15f)
        assertEquals("Max conversational RMS (0.14) should reach 1.0 energy", 1.0f, loud, 1e-4f)
        assertEquals("Full-scale RMS (1.0) should clamp to 1.0 energy", 1.0f, maxClip, 1e-4f)
    }

    @Test
    fun litDitherPixelsRespondMonotonicallyAndExceedOneThirdScreenAtMaxVoiceAcrossDevices() {
        val devices = listOf(
            Triple("Pixel 11 Pro XL", 1344f to 2992f, 15.6f),
            Triple("FHD+ Phone", 1080f to 2400f, 13.5f),
            Triple("Compact Phone", 720f to 1280f, 10.4f)
        )
        val clockSamples = listOf(0.0f, 0.35f, 1.15f, 2.4f)

        devices.forEach { (name, dims, pitchPx) ->
            val (w, h) = dims
            val silenceFrac = measureVoiceDitherLitReachFraction(
                canvasWidthPx = w,
                canvasHeightPx = h,
                voiceEnergy01 = mapMicRmsToVoiceEnergy(0f),
                cellPitchPx = pitchPx,
                clockSec = 0.5f
            )
            val midFrac = measureVoiceDitherLitReachFraction(
                canvasWidthPx = w,
                canvasHeightPx = h,
                voiceEnergy01 = mapMicRmsToVoiceEnergy(0.06f),
                cellPitchPx = pitchPx,
                clockSec = 0.5f
            )
            assertTrue("$name silence should stay calm at bottom (<=12%), got $silenceFrac", silenceFrac in 0.04f..0.12f)
            assertTrue(
                "$name speaking should rise clearly above silence ($midFrac vs $silenceFrac)",
                midFrac >= silenceFrac + 0.10f
            )

            clockSamples.forEach { t ->
                val maxFrac = measureVoiceDitherLitReachFraction(
                    canvasWidthPx = w,
                    canvasHeightPx = h,
                    voiceEnergy01 = mapMicRmsToVoiceEnergy(0.18f),
                    cellPitchPx = pitchPx,
                    clockSec = t
                )
                assertTrue(
                    "$name max voice at t=$t must rise >= 1/3 (0.3333) of screen height, got $maxFrac",
                    maxFrac >= 1f / 3f
                )
            }
        }
    }
}
