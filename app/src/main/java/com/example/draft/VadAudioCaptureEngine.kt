package com.example.draft

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure Kotlin VAD silence stripper for 16-bit mono PCM audio.
 *
 * Drops leading/trailing dead air and compacts internal silence runs strictly longer than
 * [maxPauseMs] down to a short natural breath gap (at most `min(200, maxPauseMs)` ms),
 * while preserving natural pauses `<= maxPauseMs` and all voiced speech verbatim.
 * Returns an empty [ShortArray] if the entire input is below [rmsThreshold].
 */
fun stripSilencePcm16(
    pcm: ShortArray,
    sampleRateHz: Int = 16000,
    maxPauseMs: Int = 600,
    rmsThreshold: Float = 0.015f
): ShortArray {
    if (pcm.isEmpty() || sampleRateHz <= 0) return ShortArray(0)

    val frameDurationMs = 20
    val frameSamples = ((sampleRateHz * frameDurationMs) / 1000).coerceAtLeast(1)
    val maxPauseFrames = (maxPauseMs / frameDurationMs).coerceAtLeast(1)
    val compactedPauseFrames = (min(200, maxPauseMs) / frameDurationMs).coerceAtLeast(1)
    val edgePadFrames = (min(100, maxPauseMs) / frameDurationMs).coerceAtLeast(1)

    val frameCount = (pcm.size + frameSamples - 1) / frameSamples
    val voiced = BooleanArray(frameCount)
    var firstVoiced = -1
    var lastVoiced = -1

    for (f in 0 until frameCount) {
        val start = f * frameSamples
        val end = min(start + frameSamples, pcm.size)
        val rms = computeRmsPcm16(pcm, start, end)
        if (rms >= rmsThreshold) {
            voiced[f] = true
            if (firstVoiced < 0) firstVoiced = f
            lastVoiced = f
        }
    }

    if (firstVoiced < 0) return ShortArray(0)

    val trimStart = (firstVoiced - edgePadFrames).coerceAtLeast(0)
    val trimEnd = (lastVoiced + edgePadFrames).coerceAtMost(frameCount - 1)

    val keptFrames = ArrayList<Int>(trimEnd - trimStart + 1)
    var f = trimStart
    while (f <= trimEnd) {
        if (voiced[f]) {
            keptFrames.add(f)
            f++
        } else {
            val runStart = f
            while (f <= trimEnd && !voiced[f]) {
                f++
            }
            val runLen = f - runStart
            val isInternal = runStart > firstVoiced && f <= lastVoiced
            if (!isInternal) {
                // Edge padding already clamped to edgePadFrames.
                for (k in runStart until f) keptFrames.add(k)
            } else if (runLen <= maxPauseFrames) {
                // Natural intra-utterance pause <= maxPauseMs: preserve verbatim.
                for (k in runStart until f) keptFrames.add(k)
            } else {
                // Dead-air pause > maxPauseMs: drop excess silence, keeping a brief breath gap.
                val keepCount = min(compactedPauseFrames, runLen)
                for (k in runStart until runStart + keepCount) keptFrames.add(k)
            }
        }
    }

    var totalOutSamples = 0
    for (idx in keptFrames) {
        val start = idx * frameSamples
        val end = min(start + frameSamples, pcm.size)
        totalOutSamples += (end - start)
    }

    val output = ShortArray(totalOutSamples)
    var dst = 0
    for (idx in keptFrames) {
        val start = idx * frameSamples
        val end = min(start + frameSamples, pcm.size)
        val len = end - start
        pcm.copyInto(output, destinationOffset = dst, startIndex = start, endIndex = end)
        dst += len
    }
    return output
}

internal fun computeRmsPcm16(pcm: ShortArray, start: Int = 0, endExclusive: Int = pcm.size): Float {
    val count = endExclusive - start
    if (count <= 0) return 0f
    var sumSq = 0.0
    for (i in start until endExclusive) {
        val norm = pcm[i] / 32768.0
        sumSq += norm * norm
    }
    return sqrt(sumSq / count).toFloat()
}

/**
 * Real Android `AudioRecord` (16 kHz mono 16-bit PCM) open-latch and momentary-clutch capture
 * engine with local energy/RMS VAD, >600ms dead-air stripping, timestamped 100ms rolling ring
 * buffer, incremental utterance WAV emission, and trailing 15s window extraction.
 *
 * Constitutional guarantee: never synthesizes or fakes audio when microphone hardware or
 * permissions are unavailable; surfaces real errors via `onError`.
 */
class VadAudioCaptureEngine(
    private val chunkCacheDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "aircheck_vad"),
    val silenceThresholdRms: Float = DEFAULT_SILENCE_THRESHOLD_RMS,
    val maxPauseMs: Long = DEFAULT_MAX_PAUSE_MS,
    private val attributionContext: Context? = null
) {

    data class TimestampedFrame(
        val timestampMs: Long,
        val pcm: ShortArray,
        val rms: Float,
        val isSpeech: Boolean
    )

    private enum class CaptureMode { NONE, OPEN_LATCH, MOMENTARY_CLUTCH }

    private val stateLock = Any()
    private val chunkSeq = AtomicInteger(0)

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var activeMode: CaptureMode = CaptureMode.NONE

    // Rolling ring buffer of timestamped 100ms frames (last 60 seconds)
    private val rollingFrames = ArrayDeque<TimestampedFrame>()
    // Distilled PCM stream for the active open-latch session (dead air > maxPauseMs dropped)
    private val distilledSessionChunks = ArrayList<ShortArray>()
    // Momentary clutch raw PCM chunks
    private val clutchChunks = ArrayList<ShortArray>()

    // Incremental utterance accumulator for open-latch live chunk emission
    private val activeUtteranceFrames = ArrayList<ShortArray>()
    private var activeUtteranceVoicedMs: Long = 0L
    private var consecutiveSilenceMs: Long = 0L
    private var inSpeechUtterance: Boolean = false
    private var sessionElapsedMs: Long = 0L

    private val _totalElapsedMs = MutableStateFlow(0L)
    val totalElapsedMs: StateFlow<Long> = _totalElapsedMs.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _liveRmsLevel = MutableStateFlow(0f)
    val liveRmsLevel: StateFlow<Float> = _liveRmsLevel.asStateFlow()

    /** True once >= 20s of open-latch audio has elapsed, unlocking `[ Distill Last 15s ]`. */
    val isTrailingDistillReady: Boolean
        get() = _totalElapsedMs.value >= DISTILL_UNLOCK_MS

    /**
     * Starts real 16kHz mono 16-bit `AudioRecord` open-latch capture.
     * Whenever a voiced utterance is followed by >[maxPauseMs] (600ms) of silence, writes a
     * real WAV chunk and invokes [onUtteranceReady] asynchronously without interrupting capture.
     */
    @SuppressLint("MissingPermission")
    fun startOpenLatch(
        scope: CoroutineScope,
        onUtteranceReady: suspend (File) -> Unit,
        onError: (String) -> Unit
    ) {
        if (_isCapturing.value) return

        val recorder = createInitializedRecorder(onError) ?: return
        resetSessionBuffers(CaptureMode.OPEN_LATCH)

        audioRecord = recorder
        _isCapturing.value = true

        captureJob = scope.launch(Dispatchers.IO) {
            val frameBuffer = ShortArray(FRAME_SAMPLES)
            try {
                recorder.startRecording()
                while (isActive && _isCapturing.value && activeMode == CaptureMode.OPEN_LATCH) {
                    val readCount = readFullFrame(recorder, frameBuffer)
                    if (readCount < 0) {
                        onError("AudioRecord read failed (code=$readCount).")
                        break
                    }
                    if (readCount == 0) continue

                    val frameCopy = if (readCount == frameBuffer.size) {
                        frameBuffer.copyOf()
                    } else {
                        frameBuffer.copyOf(readCount)
                    }
                    val completedUtterancePcm = ingestOpenLatchFrame(frameCopy)
                    if (completedUtterancePcm != null && completedUtterancePcm.isNotEmpty()) {
                        val chunkFile = createChunkFile()
                        writePcm16Wav(chunkFile, completedUtterancePcm, SAMPLE_RATE_HZ)
                        scope.launch(Dispatchers.IO) {
                            runCatching { onUtteranceReady(chunkFile) }
                                .onFailure { err ->
                                    onError(err.localizedMessage ?: "Utterance transcription failed.")
                                }
                        }
                    }
                }
            } catch (e: SecurityException) {
                onError(e.localizedMessage ?: "Microphone permission denied.")
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Open-latch audio capture failed.")
            } finally {
                stopAndReleaseRecorder(recorder)
                _isCapturing.value = false
                _liveRmsLevel.value = 0f
            }
        }
    }

    /**
     * Extracts the real trailing [windowMs] (default 15,000ms) of captured speech from the
     * rolling 100ms ring buffer, strips pauses > [maxPauseMs], and writes a valid 16kHz PCM WAV
     * to [outputFile]. Returns `null` if no voiced speech exists in the trailing window.
     */
    fun extractTrailingWindowWav(
        outputFile: File,
        windowMs: Long = DEFAULT_TRAILING_WINDOW_MS
    ): File? {
        if (windowMs <= 0L) return null
        val rawWindowPcm = synchronized(stateLock) {
            if (rollingFrames.isEmpty()) return null
            val latestTs = rollingFrames.last().timestampMs
            val cutoffTs = (latestTs - windowMs).coerceAtLeast(0L)
            val selected = rollingFrames.filter { it.timestampMs >= cutoffTs }
            concatShortArrays(selected.map { it.pcm })
        }
        if (rawWindowPcm.isEmpty()) return null

        val distilled = stripSilencePcm16(
            pcm = rawWindowPcm,
            sampleRateHz = SAMPLE_RATE_HZ,
            maxPauseMs = maxPauseMs.toInt(),
            rmsThreshold = silenceThresholdRms
        )
        if (distilled.isEmpty()) return null

        return writePcm16Wav(outputFile, distilled, SAMPLE_RATE_HZ)
    }

    /**
     * Stops open-latch capture, flushes any in-progress speech utterance into the distilled stream,
     * writes the distilled 16kHz mono 16-bit PCM WAV to [finalOutputFile], and returns it
     * (or `null` if no voiced audio was captured).
     */
    suspend fun stopOpenLatch(finalOutputFile: File): File? {
        _isCapturing.value = false
        val job = captureJob
        captureJob = null
        job?.join()

        val distilledPcm = synchronized(stateLock) {
            flushPendingUtteranceLocked()
            val all = concatShortArrays(distilledSessionChunks)
            activeMode = CaptureMode.NONE
            all
        }
        if (distilledPcm.isEmpty()) return null

        val stripped = stripSilencePcm16(
            pcm = distilledPcm,
            sampleRateHz = SAMPLE_RATE_HZ,
            maxPauseMs = maxPauseMs.toInt(),
            rmsThreshold = silenceThresholdRms
        )
        if (stripped.isEmpty()) return null

        return writePcm16Wav(finalOutputFile, stripped, SAMPLE_RATE_HZ)
    }

    /**
     * Starts real 16kHz mono 16-bit `AudioRecord` momentary-clutch capture
     * (for `[ Hold to Steer ]` and `[ Hold to Splice Voice ]`).
     */
    @SuppressLint("MissingPermission")
    fun startMomentaryClutch(
        scope: CoroutineScope,
        onError: (String) -> Unit
    ) {
        if (_isCapturing.value) return

        val recorder = createInitializedRecorder(onError) ?: return
        resetSessionBuffers(CaptureMode.MOMENTARY_CLUTCH)

        audioRecord = recorder
        _isCapturing.value = true

        captureJob = scope.launch(Dispatchers.IO) {
            val frameBuffer = ShortArray(FRAME_SAMPLES)
            try {
                recorder.startRecording()
                while (isActive && _isCapturing.value && activeMode == CaptureMode.MOMENTARY_CLUTCH) {
                    val readCount = readFullFrame(recorder, frameBuffer)
                    if (readCount < 0) {
                        onError("AudioRecord read failed (code=$readCount).")
                        break
                    }
                    if (readCount == 0) continue

                    val frameCopy = if (readCount == frameBuffer.size) {
                        frameBuffer.copyOf()
                    } else {
                        frameBuffer.copyOf(readCount)
                    }
                    val rms = computeRmsPcm16(frameCopy)
                    val durationMs = (frameCopy.size * 1000L) / SAMPLE_RATE_HZ
                    synchronized(stateLock) {
                        clutchChunks.add(frameCopy)
                        sessionElapsedMs += durationMs
                    }
                    _totalElapsedMs.value = sessionElapsedMs
                    _liveRmsLevel.value = rms.coerceIn(0f, 1f)
                }
            } catch (e: SecurityException) {
                onError(e.localizedMessage ?: "Microphone permission denied.")
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Momentary voice capture failed.")
            } finally {
                stopAndReleaseRecorder(recorder)
                _isCapturing.value = false
                _liveRmsLevel.value = 0f
            }
        }
    }

    /**
     * Stops momentary-clutch capture and writes the captured 16kHz PCM WAV to [outputFile].
     * Returns `null` if no voiced audio was captured.
     */
    suspend fun stopMomentaryClutch(outputFile: File): File? {
        _isCapturing.value = false
        val job = captureJob
        captureJob = null
        job?.join()

        val rawPcm = synchronized(stateLock) {
            val all = concatShortArrays(clutchChunks)
            clutchChunks.clear()
            activeMode = CaptureMode.NONE
            all
        }
        if (rawPcm.isEmpty()) return null

        val stripped = stripSilencePcm16(
            pcm = rawPcm,
            sampleRateHz = SAMPLE_RATE_HZ,
            maxPauseMs = maxPauseMs.toInt(),
            rmsThreshold = silenceThresholdRms
        )
        if (stripped.isEmpty()) return null

        return writePcm16Wav(outputFile, stripped, SAMPLE_RATE_HZ)
    }

    /**
     * Releases any active recording job and microphone hardware cleanly.
     */
    fun release() {
        _isCapturing.value = false
        captureJob?.cancel()
        captureJob = null
        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            stopAndReleaseRecorder(rec)
        }
        synchronized(stateLock) {
            activeMode = CaptureMode.NONE
            rollingFrames.clear()
            distilledSessionChunks.clear()
            clutchChunks.clear()
            activeUtteranceFrames.clear()
        }
        _liveRmsLevel.value = 0f
    }

    /**
     * Feeds a single real 100ms 16kHz PCM frame through the open-latch VAD ring buffer and
     * utterance segmenter. Called directly by the `AudioRecord` loop (and directly testable
     * on JVM with deterministic PCM waveforms).
     *
     * Returns the completed utterance PCM if a >[maxPauseMs] pause just closed a voiced
     * utterance, or `null` otherwise.
     */
    internal fun ingestOpenLatchFrame(framePcm: ShortArray): ShortArray? {
        if (framePcm.isEmpty()) return null

        val rms = computeRmsPcm16(framePcm)
        val isSpeech = rms >= silenceThresholdRms
        val frameMs = ((framePcm.size * 1000L) / SAMPLE_RATE_HZ).coerceAtLeast(1L)

        var emittedUtterance: ShortArray? = null

        synchronized(stateLock) {
            val frameTimestampMs = sessionElapsedMs
            sessionElapsedMs += frameMs

            val tsFrame = TimestampedFrame(
                timestampMs = frameTimestampMs,
                pcm = framePcm,
                rms = rms,
                isSpeech = isSpeech
            )

            // Capture up to 2 frames (200ms) pre-roll before appending current frame.
            val preRollFrames = if (!inSpeechUtterance && isSpeech) {
                rollingFrames.takeLast(PRE_ROLL_FRAMES).map { it.pcm }
            } else {
                emptyList()
            }

            rollingFrames.addLast(tsFrame)
            while (rollingFrames.size > MAX_RING_FRAMES) {
                rollingFrames.removeFirst()
            }

            if (isSpeech) {
                if (!inSpeechUtterance) {
                    inSpeechUtterance = true
                    activeUtteranceFrames.clear()
                    activeUtteranceVoicedMs = 0L
                    preRollFrames.forEach { pre ->
                        activeUtteranceFrames.add(pre)
                        distilledSessionChunks.add(pre)
                    }
                }
                consecutiveSilenceMs = 0L
                activeUtteranceVoicedMs += frameMs
                activeUtteranceFrames.add(framePcm)
                distilledSessionChunks.add(framePcm)
            } else if (inSpeechUtterance) {
                consecutiveSilenceMs += frameMs
                if (consecutiveSilenceMs <= maxPauseMs) {
                    activeUtteranceFrames.add(framePcm)
                    distilledSessionChunks.add(framePcm)
                } else {
                    // Pause strictly exceeded maxPauseMs (>600ms): finalize utterance & drop dead air!
                    if (activeUtteranceVoicedMs >= MIN_UTTERANCE_VOICED_MS) {
                        val rawUtterance = concatShortArrays(activeUtteranceFrames)
                        emittedUtterance = stripSilencePcm16(
                            pcm = rawUtterance,
                            sampleRateHz = SAMPLE_RATE_HZ,
                            maxPauseMs = maxPauseMs.toInt(),
                            rmsThreshold = silenceThresholdRms
                        )
                    }
                    activeUtteranceFrames.clear()
                    activeUtteranceVoicedMs = 0L
                    inSpeechUtterance = false
                }
            }
            // If !isSpeech && !inSpeechUtterance: drop dead air from distilledSessionChunks
            // while keeping the timestamped frame in rollingFrames.
        }

        _totalElapsedMs.value = sessionElapsedMs
        _liveRmsLevel.value = rms.coerceIn(0f, 1f)
        return emittedUtterance
    }

    private fun flushPendingUtteranceLocked() {
        activeUtteranceFrames.clear()
        activeUtteranceVoicedMs = 0L
        inSpeechUtterance = false
        consecutiveSilenceMs = 0L
    }

    private fun resetSessionBuffers(mode: CaptureMode) {
        synchronized(stateLock) {
            activeMode = mode
            rollingFrames.clear()
            distilledSessionChunks.clear()
            clutchChunks.clear()
            activeUtteranceFrames.clear()
            activeUtteranceVoicedMs = 0L
            consecutiveSilenceMs = 0L
            inSpeechUtterance = false
            sessionElapsedMs = 0L
        }
        _totalElapsedMs.value = 0L
        _liveRmsLevel.value = 0f
    }

    @SuppressLint("MissingPermission")
    private fun createInitializedRecorder(onError: (String) -> Unit): AudioRecord? {
        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes <= 0) {
            onError("AudioRecord unsupported for 16kHz mono PCM (status=$minBufBytes).")
            return null
        }
        val bufferSizeBytes = maxOf(minBufBytes * 2, FRAME_SAMPLES * 2 * 4)
        val recorder = try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .apply {
                    if (attributionContext != null) {
                        setContext(attributionContext)
                    }
                }
                .build()
        } catch (e: SecurityException) {
            onError(e.localizedMessage ?: "Microphone permission denied.")
            return null
        } catch (e: Exception) {
            onError(e.localizedMessage ?: "Unable to initialize AudioRecord.")
            return null
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            onError("Microphone hardware failed to initialize (AudioRecord uninitialized).")
            return null
        }
        return recorder
    }

    private fun readFullFrame(recorder: AudioRecord, dest: ShortArray): Int {
        var offset = 0
        while (offset < dest.size && _isCapturing.value) {
            val n = recorder.read(dest, offset, dest.size - offset)
            if (n < 0) return n
            if (n == 0) break
            offset += n
        }
        return offset
    }

    private fun stopAndReleaseRecorder(recorder: AudioRecord) {
        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        }
        runCatching { recorder.release() }
        if (audioRecord === recorder) {
            audioRecord = null
        }
    }

    private fun createChunkFile(): File {
        chunkCacheDir.mkdirs()
        val id = chunkSeq.incrementAndGet()
        return File(chunkCacheDir, "vad_utterance_${id}_${System.currentTimeMillis()}.wav")
    }

    private fun concatShortArrays(chunks: List<ShortArray>): ShortArray {
        var total = 0
        for (c in chunks) total += c.size
        if (total == 0) return ShortArray(0)
        val out = ShortArray(total)
        var offset = 0
        for (c in chunks) {
            c.copyInto(out, destinationOffset = offset)
            offset += c.size
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE_HZ: Int = 16_000
        const val FRAME_DURATION_MS: Long = 100L
        const val FRAME_SAMPLES: Int = (SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1000L).toInt() // 1600
        const val DEFAULT_SILENCE_THRESHOLD_RMS: Float = 0.015f
        const val DEFAULT_MAX_PAUSE_MS: Long = 600L
        const val DEFAULT_TRAILING_WINDOW_MS: Long = 15_000L
        const val DISTILL_UNLOCK_MS: Long = 20_000L

        private const val MAX_RING_FRAMES: Int = 600 // 60 seconds of 100ms frames
        private const val PRE_ROLL_FRAMES: Int = 2   // 200ms onset pre-roll
        private const val MIN_UTTERANCE_VOICED_MS: Long = 300L

        /**
         * Pure companion delegate for [com.example.draft.stripSilencePcm16].
         */
        fun stripSilencePcm16(
            pcm: ShortArray,
            sampleRateHz: Int = SAMPLE_RATE_HZ,
            maxPauseMs: Int = DEFAULT_MAX_PAUSE_MS.toInt(),
            rmsThreshold: Float = DEFAULT_SILENCE_THRESHOLD_RMS
        ): ShortArray = com.example.draft.stripSilencePcm16(
            pcm = pcm,
            sampleRateHz = sampleRateHz,
            maxPauseMs = maxPauseMs,
            rmsThreshold = rmsThreshold
        )

        /**
         * Writes a valid standard 44-byte RIFF/WAVE 16-bit mono PCM file.
         */
        fun writePcm16Wav(
            outputFile: File,
            pcm: ShortArray,
            sampleRateHz: Int = SAMPLE_RATE_HZ
        ): File {
            outputFile.parentFile?.mkdirs()
            val channels = 1
            val bitsPerSample = 16
            val dataSize = pcm.size * 2
            val byteRate = sampleRateHz * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort()) // PCM format
                putShort(channels.toShort())
                putInt(sampleRateHz)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }

            val pcmBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) {
                pcmBytes.putShort(s)
            }

            FileOutputStream(outputFile).use { out ->
                out.write(header.array())
                out.write(pcmBytes.array())
            }
            return outputFile
        }
    }
}
