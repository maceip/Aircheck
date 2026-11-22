package com.example

import com.example.data.BackdropPreset
import com.example.data.BrandChannel
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.Stage4Mode
import com.example.data.VoiceSource
import com.example.data.estimateDurationSeconds
import com.example.data.formatDurationBadge
import com.example.data.formatTimecode
import com.example.data.local.AircheckProfileProto
import com.example.data.local.AircheckProfileSerializer
import com.example.data.local.CasMediaStore
import com.example.data.local.WaveformBlobCodec
import com.example.engine.AudioEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class AircheckEngineUnitTest {

    @Test
    fun wavRiffHeaderPeakDecodingAndPitchEstimatorWorkAccurately() {
        val sampleRate = 16_000
        val seconds = 2
        val totalSamples = sampleRate * seconds
        val targetFreqHz = 118.0
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

            val peaks = AudioEngine.decodeWavPeaks(tmpFile, 64)
            assertEquals(64, peaks.size)
            assertTrue("Decoded peaks must have real signal amplitude", peaks.maxOrNull()!! > 0.8f)

            // Verify 64-bin Room BLOB round-trip preserves exact peak floats
            val blob = WaveformBlobCodec.encode(peaks)
            assertEquals(64 * 4, blob?.size)
            val decoded = WaveformBlobCodec.decode(blob)
            assertEquals(peaks.size, decoded.size)
            for (i in peaks.indices) {
                assertEquals(peaks[i], decoded[i], 0.0001f)
            }

            val pitchHz = AudioEngine.estimateMedianPitchHz(tmpFile)
            assertTrue("Estimated pitch ($pitchHz Hz) near 118 Hz", pitchHz in 105f..135f)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun protoDataStoreBinaryRoundTripPreservesPersonaAndProviders() = runTest {
        val original = AircheckProfileProto(
            voiceId = "ryan_voice_v1",
            headModelId = "ryan_head_v1",
            defaultPhotoSha = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            brandChannel = BrandChannel.BROADCAST_COBALT.name,
            activeScriptId = "proj_abc123",
            voiceSource = VoiceSource.ENROLLED_VOICE_CLONE.name,
            stage4Mode = Stage4Mode.PHOTO_HEAD_LORA.name,
            voiceProviderId = "elevenlabs_pvc",
            alignProviderId = "groq_whisper_v3_turbo",
            headProviderId = "wan22_s2v_lora",
            lanGatewayUrl = "http://192.168.1.40:8080",
            useLanHead = true
        )
        val out = ByteArrayOutputStream()
        AircheckProfileSerializer.writeTo(original, out)
        val bytes = out.toByteArray()
        assertTrue("Binary protobuf payload should be compact", bytes.size in 40..400)

        val restored = AircheckProfileSerializer.readFrom(ByteArrayInputStream(bytes))
        assertEquals(original, restored)
    }

    @Test
    fun casSha256HashIsDeterministicAndContentAddressed() {
        val h1 = CasMediaStore.computeSha256Hex("frame-v1".toByteArray())
        val h2 = CasMediaStore.computeSha256Hex("frame-v1".toByteArray())
        val h3 = CasMediaStore.computeSha256Hex("frame-v2".toByteArray())
        assertEquals(64, h1.length)
        assertEquals(h1, h2)
        assertNotEquals(h1, h3)
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
        assertEquals(VoiceSource.ENROLLED_VOICE_CLONE, VoiceSource.fromRaw(null))
        assertEquals(Stage4Mode.PHOTO_HEAD_LORA, Stage4Mode.fromRaw("photo_head_lora"))
        assertEquals(BackdropPreset.VOLT_TRUSS_GLOBE, BackdropPreset.fromId("volt_truss_globe"))
    }
}
