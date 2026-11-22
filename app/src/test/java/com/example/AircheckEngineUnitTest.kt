package com.example

import com.example.data.BackdropPreset
import com.example.data.BrandChannel
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import com.example.data.estimateDurationSeconds
import com.example.data.formatDurationBadge
import com.example.data.formatTimecode
import com.example.engine.AudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class AircheckEngineUnitTest {

    @Test
    fun wavRiffHeaderPeakDecodingAndAdultMalePitchEstimatorWorkAccurately() {
        val sampleRate = 16_000
        val seconds = 2
        val totalSamples = sampleRate * seconds
        val targetFreqHz = 118.0 // Adult-male fundamental frequency (< 165 Hz)
        val pcmBuffer = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) {
            val sample = (sin(2.0 * PI * targetFreqHz * i / sampleRate) * 16_000.0).toInt().toShort()
            pcmBuffer.putShort(sample)
        }

        val tmpFile = File.createTempFile("aircheck_engine_vo", ".wav")
        try {
            AudioEngine.writeRiffWav(
                file = tmpFile,
                pcmData = pcmBuffer.array(),
                sampleRate = sampleRate,
                channels = 1,
                bitsPerSample = 16
            )

            val meta = AudioEngine.parseWavHeader(tmpFile)
            assertTrue("WAV header must parse cleanly", meta != null)
            assertEquals(sampleRate, meta!!.sampleRate)
            assertEquals(1, meta.channels)
            assertEquals(16, meta.bitsPerSample)
            assertTrue("Duration must be ~2.0s", meta.durationSec in 1.9f..2.1f)

            val peaks = AudioEngine.decodeWavPeaks(tmpFile, 48)
            assertEquals(48, peaks.size)
            assertTrue("Decoded peaks must have real signal amplitude", peaks.maxOrNull()!! > 0.8f)

            val rmsEnvelope = AudioEngine.decodeFrameRmsEnvelope(tmpFile, fps = 24)
            assertTrue("24fps RMS envelope should have ~48 frames", rmsEnvelope.size in 45..52)

            val pitchHz = AudioEngine.estimateMedianPitchHz(tmpFile)
            assertTrue(
                "Estimated adult-male pitch ($pitchHz Hz) must be under 165 Hz and near 118 Hz",
                pitchHz in 105f..135f
            )
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun scriptProjectActiveDurationExcludesCutBeats() {
        val project = ScriptProject(
            title = "Chip Market Shift",
            targetDurationSec = 30,
            beats = listOf(
                ScriptBeat(id = "b1", index = 0, spokenText = "New AI inference chips just cut cloud video costs by forty percent this quarter.", cut = false),
                ScriptBeat(id = "b2", index = 1, spokenText = "This beat is cut and should not count toward runtime.", cut = true),
                ScriptBeat(id = "b3", index = 2, spokenText = "Independent creators can now publish studio news updates from a phone.", cut = false)
            )
        )
        assertEquals(2, project.activeBeats.size)
        assertTrue(project.totalActiveSeconds >= 6)
        assertEquals(3, estimateDurationSeconds("Quick hook.", 3))
        assertEquals("05s", formatDurationBadge(5))
        assertEquals("00:30", formatTimecode(30f))
    }

    @Test
    fun dualChannelAndStagePresetsResolveCleanly() {
        assertEquals(BrandChannel.VOLT_LIME, BrandChannel.fromRaw("VOLT_LIME"))
        assertEquals(BrandChannel.BROADCAST_COBALT, BrandChannel.fromRaw("BROADCAST_COBALT"))
        assertEquals(VoiceSource.MALE_TTS_DRY_RUN, VoiceSource.fromRaw(null))
        assertEquals(BackdropPreset.VOLT_TRUSS_GLOBE, BackdropPreset.fromId("volt_truss_globe"))
        assertEquals(BackdropPreset.COBALT_TRUSS_GLOBE, BackdropPreset.fromId("cobalt_truss_globe"))
    }

    @Test
    fun hybridStudioDeckThemeTokensMatchBlueprint() {
        assertEquals(androidx.compose.ui.graphics.Color(0xFF000000), com.example.ui.theme.AircheckColors.OledVoid)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF0A0C10), com.example.ui.theme.AircheckColors.ConsoleObsidian)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF232730), com.example.ui.theme.AircheckColors.GraphiteHairline)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF2F5F9), com.example.ui.theme.AircheckColors.HardwarePearl)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFD5DBE3), com.example.ui.theme.AircheckColors.HardwareAlloy)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF8E96A4), com.example.ui.theme.AircheckColors.HardwareShadow)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFB8FF00), com.example.ui.theme.AircheckColors.AcidVoltLime)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF0088FF), com.example.ui.theme.AircheckColors.BroadcastCobalt)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF18D4F5), com.example.ui.theme.AircheckColors.TelemetryCyan)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFF451A), com.example.ui.theme.AircheckColors.HazardSignalOrange)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF5B700), com.example.ui.theme.AircheckColors.EvidenceAmber)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF00E676), com.example.ui.theme.AircheckColors.LiveTallyGreen)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFF1744), com.example.ui.theme.AircheckColors.OnAirCrimson)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF4F6FA), com.example.ui.theme.AircheckColors.StudioCrisp)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF8A93A2), com.example.ui.theme.AircheckColors.TelemetryMuted)

        val voltScheme = com.example.ui.theme.aircheckColorSchemeFor(BrandChannel.VOLT_LIME)
        val cobaltScheme = com.example.ui.theme.aircheckColorSchemeFor(BrandChannel.BROADCAST_COBALT)
        assertEquals(com.example.ui.theme.AircheckColors.AcidVoltLime, voltScheme.primary)
        assertEquals(com.example.ui.theme.AircheckColors.PureBlackInk, voltScheme.onPrimary)
        assertEquals(com.example.ui.theme.AircheckColors.BroadcastCobalt, cobaltScheme.primary)
        assertEquals(com.example.ui.theme.AircheckColors.StudioCrisp, cobaltScheme.onPrimary)

        assertEquals(16f, com.example.ui.theme.AircheckTypography.bodyLarge.fontSize.value)
        assertEquals(24f, com.example.ui.theme.AircheckTypography.bodyLarge.lineHeight.value)
        assertEquals(com.example.ui.theme.SpaceGroteskFontFamily, com.example.ui.theme.AircheckTypography.bodyLarge.fontFamily)
        assertEquals(com.example.ui.theme.JetBrainsMonoFontFamily, com.example.ui.theme.TelemetryTimecodeStyle.fontFamily)
        assertEquals("tnum", com.example.ui.theme.TelemetryTimecodeStyle.fontFeatureSettings)
    }
}
