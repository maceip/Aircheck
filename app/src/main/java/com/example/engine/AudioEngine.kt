package com.example.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

/**
 * Pure 16-bit RIFF PCM WAV recorder, decoder, 64-bin waveform peak extractor,
 * and WAV preview player. Zero on-device TTS silent fallback or stubbed voice endpoints.
 */
class AudioEngine {

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
                        if (livePeaks.size > 64) livePeaks.removeAt(0)
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
        val peaks = decodeWavPeaks(outputFile, 64)
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
                putShort(1.toShort())
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
                                bb.short
                                channels = bb.short.toInt().coerceAtLeast(1)
                                sampleRate = bb.int.coerceAtLeast(8000)
                                bb.int
                                bb.short
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

        fun decodeWavPeaks(file: File, bucketCount: Int = 64): List<Float> {
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

        fun estimateMedianPitchHz(file: File): Float {
            val meta = parseWavHeader(file) ?: return 0f
            if (meta.dataSize < meta.sampleRate) return 0f
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
                if (pitches.isEmpty()) 0f else {
                    pitches.sort()
                    pitches[pitches.size / 2]
                }
            }.getOrDefault(0f)
        }
    }
}
