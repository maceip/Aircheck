package com.voicestudio.engine

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtCodecDecoderTest {

    @Test
    fun `rejects missing, truncated, and non-TFL3 codec decoder files honestly`() = runTest {
        val tmpDir = Files.createTempDirectory("voicestudio_litert_test").toFile()
        val cacheDir = File(tmpDir, "code_cache").apply { mkdirs() }
        val decoder = LiteRtCodecDecoder(codeCacheDir = cacheDir)
        try {
            val missing = File(tmpDir, "codec_decoder_fp16.tflite")
            val missingRes = decoder.load(missing)
            assertTrue("Missing .tflite must fail cleanly", missingRes.isFailure)
            assertFalse(decoder.isLoaded)

            val truncated = File(tmpDir, "truncated.tflite").apply { writeBytes(ByteArray(8)) }
            assertTrue("Truncated .tflite must fail cleanly", decoder.load(truncated).isFailure)

            val corrupt = File(tmpDir, "corrupt.tflite").apply {
                writeBytes("0000BAD!0000000012345678".toByteArray(Charsets.US_ASCII))
            }
            val corruptRes = decoder.load(corrupt)
            assertTrue("Non-TFL3 flatbuffer must fail cleanly", corruptRes.isFailure)
            assertTrue(
                corruptRes.exceptionOrNull()?.message?.contains("TFL3") == true
            )

            // Valid TFL3 header passes file validation helper.
            val validHeader = File(tmpDir, "valid_header.tflite").apply {
                val bytes = ByteArray(64)
                bytes[4] = 'T'.code.toByte()
                bytes[5] = 'F'.code.toByte()
                bytes[6] = 'L'.code.toByte()
                bytes[7] = '3'.code.toByte()
                writeBytes(bytes)
            }
            assertTrue(LiteRtCodecDecoder.validateTfliteModelFile(validHeader).isSuccess)

            // Decode before model is loaded must fail cleanly (never fabricate PCM).
            val decodeRes = decoder.decode(intArrayOf(12, 34, 56))
            assertTrue("Unloaded decode must fail cleanly", decodeRes.isFailure)
        } finally {
            decoder.close()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `prunes outdated versioned XNNPACK weight caches while preserving current version`() {
        val tmpDir = Files.createTempDirectory("voicestudio_xnnpack_cache_test").toFile()
        try {
            val staleV1 = File(tmpDir, "codec_decoder_fp16.tflite-v1.xnnpack_cache").apply {
                writeText("old-v1")
            }
            val staleV2 = File(tmpDir, "codec_decoder_fp16.tflite-v2.xnnpack_cache").apply {
                writeText("old-v2")
            }
            val unrelated = File(tmpDir, "other_model.xnnpack_cache").apply {
                writeText("keep-me")
            }

            val currentV3 = LiteRtCodecDecoder.prepareVersionedXnnPackCacheFile(
                cacheDir = tmpDir,
                modelName = "codec_decoder_fp16.tflite",
                cacheVersion = "v3"
            )

            assertEquals("codec_decoder_fp16.tflite-v3.xnnpack_cache", currentV3.name)
            assertFalse("Stale v1 cache must be deleted", staleV1.exists())
            assertFalse("Stale v2 cache must be deleted", staleV2.exists())
            assertTrue("Unrelated cache file must remain untouched", unrelated.exists())
            assertEquals(0x23, LiteRtCodecDecoder.XNNPACK_FLAGS_TRANSIENT_INDIRECTION)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `VoiceEngineJni reports honest failure when native library is unavailable on host JVM`() {
        if (!VoiceEngineJni.isNativeLibraryLoaded) {
            val res = VoiceEngineJni.initEngine("/missing.gguf", "", "/missing.tflite", "")
            assertTrue("Host JVM without Android .so must fail cleanly", res.isFailure)
            assertTrue(
                res.exceptionOrNull()?.message?.contains("voicestudio_jni") == true
            )
        }
    }
}
