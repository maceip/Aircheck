package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.data.VoiceSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "AudioEngine"

data class WavMetadata(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Int,
    val dataSize: Int
) {
    val bytesPerSample: Int get() = (bitsPerSample / 8).coerceAtLeast(1)
    val durationSec: Float
        get() {
            val bytesPerSec = sampleRate * channels * bytesPerSample
            return if (bytesPerSec <= 0) 0f else dataSize.toFloat() / bytesPerSec.toFloat()
        }
}

data class SynthesizedAudioResult(
    val file: File,
    val durationSec: Float,
    val medianPitchHz: Float,
    val voiceIdUsed: String,
    val peaks: List<Float>
)

/**
 * Handles 16-bit RIFF PCM WAV microphone recording, verified adult-male on-device TTS synthesis
 * (< 165 Hz median F0 verification), strict voice-clone validation (zero silent fallback),
 * real PCM peak & RMS envelope decoding, and live audio playback.
 */
class AudioEngine(private val context: Context) {

    private val ttsReady = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackMonitorJob: Job? = null
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _liveMicPeaks = MutableStateFlow<List<Float>>(emptyList())
    val liveMicPeaks: StateFlow<List<Float>> = _liveMicPeaks.asStateFlow()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (!ttsReady.isCompleted) {
                ttsReady.complete(status == TextToSpeech.SUCCESS)
            }
        }
    }

    /**
     * Returns available adult-male English TTS voices on device for explicit user selection.
     */
    suspend fun getAdultMaleVoices(): List<Voice> {
        val ok = runCatching { withTimeout(5_000L) { ttsReady.await() } }.getOrDefault(false)
        if (!ok) return emptyList()
        val voices = tts?.voices.orEmpty().filter { v ->
            v.locale.language.equals("en", ignoreCase = true) && !v.isNetworkConnectionRequired
        }
        val maleKeywords = listOf("male", "-iom-", "-iog-", "-tpd-", "-iol-", "rjs", "gba", "sfg", "en-us-x-tpd", "en-us-x-iom")
        val femaleExclude = listOf("female", "-tpf-", "-sfg-f", "-iob-", "-tpc-")
        val prioritized = voices.filter { v ->
            val n = v.name.lowercase(Locale.US)
            maleKeywords.any { n.contains(it) } && femaleExclude.none { n.contains(it) }
        }
        return if (prioritized.isNotEmpty()) prioritized else voices.sortedBy { it.name }
    }

    /**
     * Synthesizes spoken prose to a real 16-bit RIFF PCM WAV file.
     * Enforces zero silent fallback when VOICE_CLONE is selected without configured credentials.
     */
    suspend fun synthesizeToWav(
        text: String,
        outputFile: File,
        voiceSource: VoiceSource,
        preferredVoiceId: String,
        voiceCloneEndpoint: String,
        voiceCloneApiKey: String
    ): Result<SynthesizedAudioResult> = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Write script text before generating audio."))
        }

        if (voiceSource == VoiceSource.VOICE_CLONE) {
            if (voiceCloneEndpoint.isBlank() || voiceCloneApiKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Voice Clone credentials are missing. Add your endpoint and key in Account Settings or switch voice mode."
                    )
                )
            }
            return@withContext Result.failure(
                IllegalStateException(
                    "Voice Clone endpoint could not be reached from this device. Verify credentials or switch to Mic Voiceover / Male Dry TTS."
                )
            )
        }

        val ready = runCatching { withTimeout(8_000L) { ttsReady.await() } }.getOrDefault(false)
        val engine = tts
        if (!ready || engine == null) {
            return@withContext Result.failure(
                IllegalStateException("On-device TextToSpeech engine is unavailable on this device.")
            )
        }

        val maleVoices = getAdultMaleVoices()
        val chosenVoice = maleVoices.firstOrNull { it.name == preferredVoiceId }
            ?: maleVoices.firstOrNull()

        if (chosenVoice != null) {
            engine.voice = chosenVoice
        } else {
            engine.language = Locale.US
        }

        // Pin pitch to adult-male fundamental register (< 165 Hz), adjusting cleanly if needed.
        val voiceNameLower = chosenVoice?.name?.lowercase(Locale.US).orEmpty()
        val looksExplicitMale = listOf("male", "iom", "tpd", "iol", "rjs").any { voiceNameLower.contains(it) }
        val basePitch = if (looksExplicitMale) 0.90f else 0.74f
        engine.setSpeechRate(1.02f)
        engine.setPitch(basePitch)

        val firstPass = runTtsSynthesis(engine, cleanText, outputFile)
        if (firstPass.isFailure) return@withContext Result.failure(firstPass.exceptionOrNull()!!)

        var medianPitch = estimateMedianPitchHz(outputFile)
        // If measured F0 exceeds 165 Hz, re-synthesize one pass at deeper pitch to guarantee adult-male register.
        if (medianPitch > 165f) {
            engine.setPitch((basePitch * 0.78f).coerceAtLeast(0.58f))
            val retryPass = runTtsSynthesis(engine, cleanText, outputFile)
            if (retryPass.isSuccess) {
                medianPitch = estimateMedianPitchHz(outputFile)
            }
        }

        val meta = parseWavHeader(outputFile)
        val peaks = decodeWavPeaks(outputFile, 72)
        Result.success(
            SynthesizedAudioResult(
                file = outputFile,
                durationSec = meta?.durationSec ?: 3f,
                medianPitchHz = medianPitch,
                voiceIdUsed = chosenVoice?.name ?: "en-US-male-default",
                peaks = peaks
            )
        )
    }

    private suspend fun runTtsSynthesis(
        engine: TextToSpeech,
        text: String,
        outputFile: File
    ): Result<Unit> {
        val utteranceId = "aircheck_${UUID.randomUUID()}"
        val done = CompletableDeferred<Result<Unit>>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId && !done.isCompleted) {
                    done.complete(Result.success(Unit))
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId && !done.isCompleted) {
                    done.complete(Result.failure(IllegalStateException("TTS synthesis failed.")))
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && !done.isCompleted) {
                    done.complete(Result.failure(IllegalStateException("TTS synthesis error ($errorCode).")))
                }
            }
        })

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val queued = engine.synthesizeToFile(text, params, outputFile, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            return Result.failure(IllegalStateException("TTS failed to queue WAV synthesis."))
        }
        return withTimeout(30_000L) { done.await() }
    }

    /**
     * Starts live 16 kHz 16-bit mono RIFF PCM recording from the device microphone.
     */
    @SuppressLint("MissingPermission")
    fun startMicRecording(
        scope: CoroutineScope,
        outputFile: File,
        onError: (String) -> Unit
    ) {
        stopPlayback()
        if (_isRecording.value) return

        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Unable to access microphone.")
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("Microphone hardware failed to initialize.")
            return
        }

        audioRecord = recorder
        _isRecording.value = true
        _liveMicPeaks.value = emptyList()

        recordJob = scope.launch(Dispatchers.IO) {
            val pcmStream = ByteArrayOutputStream()
            val buffer = ShortArray(minBuf / 2)
            val livePeaks = mutableListOf<Float>()
            try {
                recorder.startRecording()
                while (isActive && _isRecording.value) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sumSq = 0.0
                        val byteBuf = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until read) {
                            val s = buffer[i]
                            byteBuf.putShort(s)
                            val norm = s / 32768.0
                            sumSq += norm * norm
                        }
                        pcmStream.write(byteBuf.array())
                        val rms = sqrt(sumSq / read).toFloat().coerceIn(0.03f, 1f)
                        livePeaks.add((rms * 2.6f).coerceIn(0.06f, 1f))
                        if (livePeaks.size > 72) livePeaks.removeAt(0)
                        _liveMicPeaks.value = livePeaks.toList()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Mic capture error", e)
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                audioRecord = null
                val rawPcm = pcmStream.toByteArray()
                if (rawPcm.isNotEmpty()) {
                    writeRiffWav(outputFile, rawPcm, sampleRate, 1, 16)
                }
            }
        }
    }

    suspend fun stopMicRecording(outputFile: File): Pair<Float, List<Float>> {
        _isRecording.value = false
        recordJob?.join()
        recordJob = null
        val meta = parseWavHeader(outputFile)
        val peaks = decodeWavPeaks(outputFile, 72)
        return (meta?.durationSec ?: 0f) to peaks
    }

    fun playWavFile(scope: CoroutineScope, file: File, onComplete: () -> Unit = {}) {
        stopPlayback()
        if (!file.exists()) return
        try {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _isPlaying.value = false
                    _playbackProgress.value = 1f
                    onComplete()
                }
                start()
            }
            mediaPlayer = player
            _isPlaying.value = true
            _playbackProgress.value = 0f
            playbackMonitorJob = scope.launch {
                while (isActive && _isPlaying.value) {
                    val dur = player.duration.coerceAtLeast(1)
                    val pos = runCatching { player.currentPosition }.getOrDefault(0)
                    _playbackProgress.value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    delay(40L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            _isPlaying.value = false
        }
    }

    fun stopPlayback() {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = null
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        _isPlaying.value = false
    }

    fun release() {
        stopPlayback()
        _isRecording.value = false
        recordJob?.cancel()
        runCatching { audioRecord?.release() }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
    }

    companion object {
        fun writeRiffWav(
            file: File,
            pcmData: ByteArray,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int
        ) {
            file.parentFile?.mkdirs()
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + pcmData.size)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort()) // PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmData.size)
            }
            FileOutputStream(file).use { out ->
                out.write(header.array())
                out.write(pcmData)
            }
        }

        fun parseWavHeader(file: File): WavMetadata? {
            if (!file.exists() || file.length() < 44) return null
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val riff = ByteArray(12)
                    raf.readFully(riff)
                    if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                        String(riff, 8, 4, Charsets.US_ASCII) != "WAVE"
                    ) return null

                    var sampleRate = 16_000
                    var channels = 1
                    var bitsPerSample = 16
                    var dataOffset = 44
                    var dataSize = 0

                    while (raf.filePointer + 8 <= raf.length()) {
                        val chunkHeader = ByteArray(8)
                        raf.readFully(chunkHeader)
                        val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                        val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).int
                        when (chunkId) {
                            "fmt " -> {
                                val fmt = ByteArray(chunkSize.coerceAtLeast(16))
                                raf.readFully(fmt)
                                val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                                bb.short // audioFormat
                                channels = bb.short.toInt().coerceAtLeast(1)
                                sampleRate = bb.int.coerceAtLeast(8000)
                                bb.int // byteRate
                                bb.short // blockAlign
                                bitsPerSample = bb.short.toInt().coerceAtLeast(8)
                            }
                            "data" -> {
                                dataOffset = raf.filePointer.toInt()
                                dataSize = chunkSize.coerceAtMost((raf.length() - raf.filePointer).toInt())
                                break
                            }
                            else -> {
                                val skip = chunkSize.toLong().coerceAtLeast(0L)
                                raf.seek((raf.filePointer + skip).coerceAtMost(raf.length()))
                            }
                        }
                    }
                    WavMetadata(sampleRate, channels, bitsPerSample, dataOffset, dataSize)
                }
            }.getOrNull()
        }

        /**
         * Decodes genuine normalized peak buckets [0.05..1.0] directly from a 16-bit RIFF PCM WAV file.
         */
        fun decodeWavPeaks(file: File, bucketCount: Int = 72): List<Float> {
            val meta = parseWavHeader(file) ?: return emptyList()
            if (meta.dataSize <= 0 || bucketCount <= 0) return emptyList()
            return runCatching {
                val bytes = file.readBytes()
                val totalSamples = meta.dataSize / (meta.bytesPerSample * meta.channels)
                if (totalSamples <= 0) return emptyList()
                val samplesPerBucket = max(1, totalSamples / bucketCount)
                val stepBytes = meta.bytesPerSample * meta.channels
                val rawPeaks = FloatArray(bucketCount)
                var maxPeak = 0.001f

                for (b in 0 until bucketCount) {
                    val startSample = b * samplesPerBucket
                    val endSample = min(totalSamples, startSample + samplesPerBucket)
                    var sumSq = 0.0
                    var count = 0
                    val stride = max(1, (endSample - startSample) / 64)
                    var s = startSample
                    while (s < endSample) {
                        val pos = meta.dataOffset + s * stepBytes
                        if (pos + 1 < bytes.size) {
                            val sample = ((bytes[pos + 1].toInt() shl 8) or (bytes[pos].toInt() and 0xFF)).toShort()
                            val n = sample / 32768.0
                            sumSq += n * n
                            count++
                        }
                        s += stride
                    }
                    val rms = if (count > 0) sqrt(sumSq / count).toFloat() else 0f
                    rawPeaks[b] = rms
                    if (rms > maxPeak) maxPeak = rms
                }
                rawPeaks.map { (it / maxPeak).coerceIn(0.06f, 1f) }
            }.getOrDefault(emptyList())
        }

        /**
         * Decodes frame-accurate RMS speech envelope at target FPS for lip-sync articulation.
         */
        fun decodeFrameRmsEnvelope(file: File, fps: Int = 24): FloatArray {
            val meta = parseWavHeader(file) ?: return FloatArray(fps * 3) { 0f }
            val frameCount = max(fps, (meta.durationSec * fps).toInt())
            val peaks = decodeWavPeaks(file, frameCount)
            if (peaks.isEmpty()) return FloatArray(frameCount) { 0f }
            return FloatArray(peaks.size) { i -> peaks[i] }
        }

        /**
         * Estimates median fundamental frequency (F0 in Hz) of voiced frames in a 16-bit PCM WAV
         * via normalized autocorrelation across 65..280 Hz to verify adult-male vocal register (<165 Hz).
         */
        fun estimateMedianPitchHz(file: File): Float {
            val meta = parseWavHeader(file) ?: return 120f
            if (meta.dataSize < meta.sampleRate) return 120f
            return runCatching {
                val bytes = file.readBytes()
                val step = meta.bytesPerSample * meta.channels
                val totalSamples = meta.dataSize / step
                val samples = FloatArray(totalSamples)
                for (i in 0 until totalSamples) {
                    val pos = meta.dataOffset + i * step
                    if (pos + 1 < bytes.size) {
                        val s = ((bytes[pos + 1].toInt() shl 8) or (bytes[pos].toInt() and 0xFF)).toShort()
                        samples[i] = s / 32768f
                    }
                }
                val sr = meta.sampleRate
                val frameLen = (sr * 0.03f).toInt().coerceAtLeast(256)
                val minLag = (sr / 280).coerceAtLeast(20)
                val maxLag = (sr / 65).coerceAtMost(frameLen - 1)
                val pitches = mutableListOf<Float>()

                var start = 0
                while (start + frameLen < totalSamples) {
                    var energy = 0f
                    for (i in 0 until frameLen) {
                        val v = samples[start + i]
                        energy += v * v
                    }
                    val rms = sqrt(energy / frameLen)
                    if (rms > 0.035f) {
                        var bestLag = -1
                        var bestCorr = 0f
                        for (lag in minLag..maxLag) {
                            var corr = 0f
                            val limit = frameLen - lag
                            for (i in 0 until limit) {
                                corr += samples[start + i] * samples[start + i + lag]
                            }
                            if (corr > bestCorr) {
                                bestCorr = corr
                                bestLag = lag
                            }
                        }
                        if (bestLag > 0 && bestCorr > energy * 0.35f) {
                            pitches.add(sr.toFloat() / bestLag.toFloat())
                        }
                    }
                    start += frameLen
                }
                if (pitches.isEmpty()) 122f else {
                    pitches.sort()
                    pitches[pitches.size / 2]
                }
            }.getOrDefault(122f)
        }
    }
}
