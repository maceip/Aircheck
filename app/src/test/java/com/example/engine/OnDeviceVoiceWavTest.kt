package com.example.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OnDeviceVoiceWavTest {

    @Test
    fun writeAndParsePcm16MonoWavRoundTripsExactSamplesAndRate() {
        val tmp = File.createTempFile("run9_wav_roundtrip_", ".wav")
        try {
            val pcm = ByteArray(24_000 * 2) { idx -> ((idx * 31) and 0xFF).toByte() }
            OnDeviceVoice.writePcm16MonoWav(tmp, 24_000, pcm)
            val decoded = OnDeviceVoice.parsePcm16MonoWav(tmp)
            assertEquals(24_000, decoded.sampleRateHz)
            assertArrayEquals(pcm, decoded.pcmBytes)
        } finally {
            tmp.delete()
        }
    }
}
