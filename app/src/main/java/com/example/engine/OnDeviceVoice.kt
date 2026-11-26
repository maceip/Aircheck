package com.example.engine

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class ClauseVoiceSynthesisResult(
    val wavFile: File,
    val clauseBoundsMs: List<Pair<Long, Long>>,
    val totalDurationMs: Long
)

/**
 * On-phone voice: Ryan's run9 Qwen3-TTS fine-tune (trained off-device on Azure), exported to LiteRT
 * and run by Google's LiteRT-LM `omni_runner`, shipped uncompressed in the APK as `libomni_runner.so`
 * and executed through `/system/bin/linker64` straight from the APK (no Termux, no proot, no extraction).
 *
 * Model files live in `noBackupFilesDir/voice/run9_litert/` (talker, MTP drafter, codec decoder,
 * embeddings, tokenizer, `voices/demo_speaker.bin` = Ryan's 1024-d speaker vector).
 */
class OnDeviceVoice(private val context: Context) {

    val modelDir: File get() = File(context.noBackupFilesDir, "voice/run9_litert")

    private val apk: File get() = File(context.applicationInfo.sourceDir)
    private val runnerInApk: String get() = "${apk.absolutePath}!/lib/arm64-v8a/libomni_runner.so"
    private fun runnerPresent(): Boolean = runCatching {
        java.util.zip.ZipFile(apk).use { it.getEntry("lib/arm64-v8a/libomni_runner.so") != null }
    }.getOrDefault(false)

    fun missingFiles(): List<String> = REQUIRED_FILES.filter { name ->
        val f = File(modelDir, name)
        !f.isFile || f.length() == 0L
    } + (if (runnerPresent()) emptyList() else listOf("libomni_runner.so"))

    fun isInstalled(): Boolean = missingFiles().isEmpty()

    /**
     * Synthesizes [clauses] sequentially on this phone with real per-clause progress callbacks and
     * exact per-clause millisecond boundaries, concatenating the resulting 24 kHz mono PCM16 WAVs
     * with a short natural breath pause between clauses. Avoids single-pass token limits on
     * 20-120s scripts and prevents the progress bar from stalling at a static percentage.
     */
    fun synthesizeClauses(
        clauses: List<String>,
        onProgressFraction: (Float, String) -> Unit = { _, _ -> }
    ): ClauseVoiceSynthesisResult {
        val cleanClauses = clauses.map { it.trim() }.filter { it.isNotEmpty() }
        require(cleanClauses.isNotEmpty()) { "Enter story text before running on-phone voice." }
        if (cleanClauses.size == 1) {
            onProgressFraction(0.15f, "Voicing line 1/1 on this phone…")
            val single = synthesize(cleanClauses[0])
            val pcm = parsePcm16MonoWav(single)
            val durMs = ((pcm.pcmBytes.size / 2L) * 1000L / pcm.sampleRateHz.coerceAtLeast(1)).coerceAtLeast(1L)
            onProgressFraction(0.90f, "Voice synthesized on this phone.")
            return ClauseVoiceSynthesisResult(
                wavFile = single,
                clauseBoundsMs = listOf(0L to durMs),
                totalDurationMs = durMs
            )
        }

        val total = cleanClauses.size
        val tempFiles = mutableListOf<File>()
        try {
            val pcmOut = ByteArrayOutputStream()
            val bounds = ArrayList<Pair<Long, Long>>(total)
            var sampleRateHz = DEFAULT_SAMPLE_RATE_HZ
            var cursorSamples = 0L

            cleanClauses.forEachIndexed { idx, clauseText ->
                val startFrac = 0.08f + 0.80f * (idx.toFloat() / total.toFloat())
                onProgressFraction(startFrac, "Voicing line ${idx + 1}/$total on this phone…")
                val clauseWav = synthesize(clauseText)
                tempFiles += clauseWav
                val decoded = parsePcm16MonoWav(clauseWav)
                if (idx == 0) {
                    sampleRateHz = decoded.sampleRateHz
                }
                val startMs = (cursorSamples * 1000L) / sampleRateHz.coerceAtLeast(1)
                pcmOut.write(decoded.pcmBytes)
                cursorSamples += decoded.pcmBytes.size / 2L
                val endMs = ((cursorSamples * 1000L) / sampleRateHz.coerceAtLeast(1)).coerceAtLeast(startMs + 1L)
                bounds += startMs to endMs

                if (idx < total - 1) {
                    val gapSamples = (sampleRateHz * INTER_CLAUSE_BREATH_MS) / 1000
                    pcmOut.write(ByteArray(gapSamples * 2))
                    cursorSamples += gapSamples
                }
                val doneFrac = 0.08f + 0.80f * ((idx + 1).toFloat() / total.toFloat())
                onProgressFraction(doneFrac, "Voiced line ${idx + 1}/$total on this phone…")
            }

            val mergedOut = File(context.cacheDir, "run9_tts_merged_${System.nanoTime()}.wav")
            writePcm16MonoWav(mergedOut, sampleRateHz, pcmOut.toByteArray())
            val totalDurationMs = ((cursorSamples * 1000L) / sampleRateHz.coerceAtLeast(1)).coerceAtLeast(1L)
            onProgressFraction(0.90f, "Voice synthesized on this phone.")
            return ClauseVoiceSynthesisResult(
                wavFile = mergedOut,
                clauseBoundsMs = bounds,
                totalDurationMs = totalDurationMs
            )
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
        }
    }

    /** Synthesizes [text] to a 24 kHz mono WAV in the cache dir; throws with the runner's own error text. */
    fun synthesize(text: String, onProgress: (String) -> Unit = {}): File {
        val missing = missingFiles()
        check(missing.isEmpty()) { "Your voice model isn't fully on this phone (missing: ${missing.joinToString()})." }
        val out = File(context.cacheDir, "run9_tts_${System.nanoTime()}.wav")
        val cache = File(context.cacheDir, "omni_cache").apply { mkdirs() }
        onProgress("Synthesizing your voice on this phone…")
        val proc = ProcessBuilder(
            "/system/bin/linker64",
            runnerInApk,
            "--model_name=qwen3-tts",
            "--model_path=${modelDir.absolutePath}",
            "--backend=cpu",
            "--num_threads=6",
            "--cache_dir=${cache.absolutePath}",
            "--language=en",
            "--text=$text",
            "--output_path=${out.absolutePath}"
        ).redirectErrorStream(true).start()
        val log = StringBuilder()
        val reader = Thread {
            proc.inputStream.bufferedReader().forEachLine { line -> synchronized(log) { log.appendLine(line) } }
        }.apply { start() }
        val finished = proc.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)
        if (!finished) {
            proc.destroyForcibly()
            throw IllegalStateException("On-phone voice took longer than $TIMEOUT_MIN minutes and was stopped.")
        }
        reader.join(2000)
        val tail = synchronized(log) { log.toString() }.lines().filter { it.isNotBlank() }.takeLast(4).joinToString(" | ")
        if (proc.exitValue() != 0 || !out.isFile || out.length() <= 44L) {
            throw IllegalStateException("On-phone voice failed (exit ${proc.exitValue()}): $tail")
        }
        return out
    }

    internal data class DecodedPcmWav(val sampleRateHz: Int, val pcmBytes: ByteArray)

    companion object {
        const val TIMEOUT_MIN = 15L
        private const val DEFAULT_SAMPLE_RATE_HZ = 24_000
        private const val INTER_CLAUSE_BREATH_MS = 120
        val REQUIRED_FILES = listOf(
            "talker_int4.tflite",
            "mtp_fp32.tflite",
            "codec_decoder_fp32.tflite",
            "text_embedding.tflite",
            "text_projection.tflite",
            "codec_embedding.tflite",
            "mtp_embedding.tflite",
            "tokenizer.json",
            "voices/demo_speaker.bin"
        )

        internal fun parsePcm16MonoWav(file: File): DecodedPcmWav {
            val bytes = file.readBytes()
            require(bytes.size > 44) { "Generated WAV file is too small (${bytes.size} bytes)." }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var sampleRate = DEFAULT_SAMPLE_RATE_HZ
            var dataOffset = 44
            var dataLength = bytes.size - 44
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = buf.getInt(pos + 4).coerceAtLeast(0)
                val bodyStart = pos + 8
                if (id == "fmt " && bodyStart + 8 <= bytes.size) {
                    sampleRate = buf.getInt(bodyStart + 4).coerceIn(8_000, 96_000)
                } else if (id == "data" && bodyStart <= bytes.size) {
                    dataOffset = bodyStart
                    dataLength = size.coerceAtMost(bytes.size - bodyStart)
                    break
                }
                pos = bodyStart + size + (size and 1)
            }
            val alignedLen = dataLength - (dataLength and 1)
            return DecodedPcmWav(
                sampleRateHz = sampleRate,
                pcmBytes = bytes.copyOfRange(dataOffset, dataOffset + alignedLen)
            )
        }

        internal fun writePcm16MonoWav(out: File, sampleRateHz: Int, pcmBytes: ByteArray) {
            val dataLen = pcmBytes.size - (pcmBytes.size and 1)
            val byteRate = sampleRateHz * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(1) // mono
                putInt(sampleRateHz)
                putInt(byteRate)
                putShort(2) // blockAlign
                putShort(16) // bitsPerSample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataLen)
            }.array()
            FileOutputStream(out).use { fos ->
                fos.write(header)
                fos.write(pcmBytes, 0, dataLen)
            }
        }
    }
}
