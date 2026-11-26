package com.example.engine

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

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
            "--num_threads=4",
            "--cache_dir=${cache.absolutePath}",
            "--language=en",
            "--text=$text",
            "--output_path=${out.absolutePath}"
        ).redirectErrorStream(true).start()
        val log = StringBuilder()
        val reader = Thread {
            proc.inputStream.bufferedReader().forEachLine { line -> synchronized(log) { log.appendLine(line) } }
        }.apply { start() }
        // The runner prints nothing while it generates, so report the real elapsed time each second:
        // on-phone synthesis runs several times slower than real time and must not look frozen.
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var finished = false
        while (!finished) {
            finished = proc.waitFor(1, TimeUnit.SECONDS)
            val elapsedSec = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000
            if (finished) break
            if (elapsedSec >= TIMEOUT_MIN * 60) break
            onProgress("Synthesizing your voice on this phone… %d:%02d elapsed".format(elapsedSec / 60, elapsedSec % 60))
        }
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

    companion object {
        const val TIMEOUT_MIN = 15L
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
    }
}
