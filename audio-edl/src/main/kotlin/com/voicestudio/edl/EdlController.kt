package com.voicestudio.edl

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Computed global timeline span for a single playable [ClauseNode] inside a [VoiceProjectManifest].
 */
data class ClauseTimelineSpan(
    val clauseIndex: Int,
    val clauseId: String,
    val slice: EdlAudioSlice,
    val timelineStartSample: Long,
    val activeEndSample: Long,
    val timelineEndSample: Long
) {
    val activeDurationSamples: Long
        get() = (activeEndSample - timelineStartSample).coerceAtLeast(0L)
}

/**
 * Resolved mapping from a global timeline sample to a clause, word token, and local PCM offset.
 */
data class EdlTimelinePosition(
    val clauseIndex: Int,
    val clauseId: String?,
    val wordTokenIndex: Int,
    val localSample: Long,
    val timelineSample: Long,
    val inGap: Boolean
)

/**
 * Pure Kotlin deterministic EDL timeline calculator shared with `:ui-clipper` and `:audio-edl`.
 * Slice bounds and scaled gaps match [computeAnchorTimestampsUs] sample-for-sample.
 */
object EdlTimelineCalculator {

    /**
     * Builds the flat list of [EdlAudioSlice] descriptors from [manifest], honoring each clause's
     * [ClauseNode.selectedTakeIndex] and scaling gaps by [VoiceProjectManifest.globalGapScale].
     */
    fun buildSlices(manifest: VoiceProjectManifest): List<EdlAudioSlice> {
        return buildTimelineSpans(manifest).map { it.slice }
    }

    /**
     * Computes contiguous global timeline spans for every clause in [manifest] that has a valid
     * selected candidate take.
     */
    fun buildTimelineSpans(manifest: VoiceProjectManifest): List<ClauseTimelineSpan> {
        val gapScale = manifest.globalGapScale.coerceAtLeast(0f)
        val spans = ArrayList<ClauseTimelineSpan>(manifest.clauses.size)
        var cursor = 0L

        for ((clauseIndex, clause) in manifest.clauses.withIndex()) {
            val winner = clause.selectedCandidate ?: continue
            val totalSamples = winner.totalSamples.coerceAtLeast(0L)
            val zeroCrossIn = if (winner.tokens.isNotEmpty()) {
                winner.tokens.first().zeroCrossIn.coerceIn(0L, totalSamples)
            } else {
                0L
            }
            val zeroCrossOut = if (winner.tokens.isNotEmpty()) {
                winner.tokens.last().zeroCrossOut.coerceIn(zeroCrossIn, totalSamples)
            } else {
                totalSamples
            }
            val scaledGap = (clause.baseGapAfterSamples * gapScale).toInt().coerceAtLeast(0)
            val activeDuration = (zeroCrossOut - zeroCrossIn).coerceAtLeast(0L)
            val activeEnd = cursor + activeDuration
            val spanEnd = activeEnd + scaledGap.toLong()

            spans.add(
                ClauseTimelineSpan(
                    clauseIndex = clauseIndex,
                    clauseId = clause.clauseId,
                    slice = EdlAudioSlice(
                        pcmFilePath = winner.pcmFilePath,
                        zeroCrossInSample = zeroCrossIn,
                        zeroCrossOutSample = zeroCrossOut,
                        gapAfterSamples = scaledGap
                    ),
                    timelineStartSample = cursor,
                    activeEndSample = activeEnd,
                    timelineEndSample = spanEnd
                )
            )
            cursor = spanEnd
        }
        return spans
    }

    /**
     * Total duration of the compressed EDL timeline in 44.1 kHz samples.
     */
    fun totalTimelineSamples(manifest: VoiceProjectManifest): Long {
        return buildTimelineSpans(manifest).lastOrNull()?.timelineEndSample ?: 0L
    }

    /**
     * Maps any global [timelineSample] to `(clauseIndex, wordTokenIndex, localSample)` so
     * `:ui-clipper` can highlight the active `WordPill`, position the center blade, and inspect
     * the underlying take PCM file.
     */
    fun mapSampleToPosition(
        manifest: VoiceProjectManifest,
        timelineSample: Long
    ): EdlTimelinePosition {
        val clampedSample = timelineSample.coerceAtLeast(0L)
        val spans = buildTimelineSpans(manifest)
        if (spans.isEmpty()) {
            return EdlTimelinePosition(
                clauseIndex = -1,
                clauseId = null,
                wordTokenIndex = -1,
                localSample = -1L,
                timelineSample = clampedSample,
                inGap = true
            )
        }

        val lastSpan = spans.last()
        if (clampedSample >= lastSpan.timelineEndSample) {
            if (lastSpan.slice.gapAfterSamples > 0 || lastSpan.activeDurationSamples == 0L) {
                return EdlTimelinePosition(
                    clauseIndex = lastSpan.clauseIndex,
                    clauseId = lastSpan.clauseId,
                    wordTokenIndex = -1,
                    localSample = -1L,
                    timelineSample = clampedSample,
                    inGap = true
                )
            }
            val lastTokens = manifest.clauses[lastSpan.clauseIndex].selectedCandidate?.tokens.orEmpty()
            return EdlTimelinePosition(
                clauseIndex = lastSpan.clauseIndex,
                clauseId = lastSpan.clauseId,
                wordTokenIndex = if (lastTokens.isEmpty()) -1 else lastTokens.lastIndex,
                localSample = lastSpan.slice.zeroCrossOutSample,
                timelineSample = clampedSample,
                inGap = false
            )
        }

        // Binary search for the first span with timelineEndSample > clampedSample.
        var low = 0
        var high = spans.lastIndex
        var hitIndex = spans.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (clampedSample < spans[mid].timelineEndSample) {
                hitIndex = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }

        val span = spans[hitIndex]
        if (clampedSample >= span.activeEndSample) {
            return EdlTimelinePosition(
                clauseIndex = span.clauseIndex,
                clauseId = span.clauseId,
                wordTokenIndex = -1,
                localSample = -1L,
                timelineSample = clampedSample,
                inGap = true
            )
        }

        val localSample = span.slice.zeroCrossInSample + (clampedSample - span.timelineStartSample)
        val tokens = manifest.clauses[span.clauseIndex].selectedCandidate?.tokens.orEmpty()
        val wordTokenIndex = resolveWordTokenIndex(tokens, localSample)

        return EdlTimelinePosition(
            clauseIndex = span.clauseIndex,
            clauseId = span.clauseId,
            wordTokenIndex = wordTokenIndex,
            localSample = localSample,
            timelineSample = clampedSample,
            inGap = false
        )
    }

    /**
     * Snaps a raw [timelineSample] (e.g. on Vernier drag release) magnetically to the nearest
     * zero-crossing boundary (`WordToken.zeroCrossIn` or `WordToken.zeroCrossOut`) on the timeline.
     */
    fun snapToNearestZeroCrossing(manifest: VoiceProjectManifest, timelineSample: Long): Long {
        val target = timelineSample.coerceAtLeast(0L)
        val spans = buildTimelineSpans(manifest)
        if (spans.isEmpty()) return 0L

        var bestBoundary = 0L
        var bestDistance = Long.MAX_VALUE

        fun consider(boundary: Long) {
            val dist = abs(boundary - target)
            if (dist < bestDistance || (dist == bestDistance && boundary < bestBoundary)) {
                bestDistance = dist
                bestBoundary = boundary
            }
        }

        for (span in spans) {
            val winner = manifest.clauses[span.clauseIndex].selectedCandidate ?: continue
            if (winner.tokens.isEmpty()) {
                consider(span.timelineStartSample)
                consider(span.activeEndSample)
            } else {
                val baseIn = span.slice.zeroCrossInSample
                for (token in winner.tokens) {
                    val globalIn = span.timelineStartSample + (token.zeroCrossIn - baseIn).coerceAtLeast(0L)
                    val globalOut = span.timelineStartSample + (token.zeroCrossOut - baseIn).coerceAtLeast(0L)
                    consider(globalIn.coerceIn(span.timelineStartSample, span.activeEndSample))
                    consider(globalOut.coerceIn(span.timelineStartSample, span.activeEndSample))
                }
            }
        }
        return bestBoundary
    }

    fun sampleToTimeUs(sample: Long, sampleRateHz: Int = SYNTHESIS_SAMPLE_RATE_HZ): Long {
        val rate = sampleRateHz.coerceAtLeast(1).toLong()
        return (sample.coerceAtLeast(0L) * 1_000_000L) / rate
    }

    private fun resolveWordTokenIndex(tokens: List<WordToken>, localSample: Long): Int {
        if (tokens.isEmpty()) return -1
        if (localSample < tokens.first().zeroCrossIn) return 0
        if (localSample >= tokens.last().zeroCrossOut) return tokens.lastIndex

        for (i in tokens.indices) {
            val token = tokens[i]
            if (localSample in token.zeroCrossIn until token.zeroCrossOut) {
                return i
            }
            if (i + 1 < tokens.size) {
                val next = tokens[i + 1]
                if (localSample in token.zeroCrossOut until next.zeroCrossIn) {
                    val distPrev = localSample - token.zeroCrossOut
                    val distNext = next.zeroCrossIn - localSample
                    return if (distPrev <= distNext) i else (i + 1)
                }
            }
        }
        return tokens.lastIndex
    }
}

/**
 * Low-level native binding contract for [EdlController].
 * Production instances always bind to `libvoicestudio_edl.so` via JNI (zero fake/mock fallbacks).
 */
internal interface EdlNativeBackend {
    fun create(): Result<Long>
    fun init(handle: Long, roomToneLoopPath: String): String?
    fun start(handle: Long): String?
    fun pause(handle: Long)
    fun close(handle: Long)
    fun preloadFiles(handle: Long, pcmPaths: Array<String>): String?
    fun updateSnapshot(
        handle: Long,
        pcmPaths: Array<String>,
        zeroCrossInSamples: LongArray,
        zeroCrossOutSamples: LongArray,
        gapAfterSamples: IntArray,
        globalGapScale: Float
    ): String?
    fun seekToSample(handle: Long, timelineSample: Long)
    fun getCurrentSample(handle: Long): Long
    fun getXRunCount(handle: Long): Int
    fun isPlaying(handle: Long): Boolean
}

/**
 * Wait-free Oboe (AAudio) Edit Decision List controller backed by `libvoicestudio_edl.so`.
 *
 * - Builds flat [EdlAudioSlice] descriptors directly from [VoiceProjectManifest] with zero WAV
 *   stitching on disk.
 * - Publishes updated snapshots via atomic `std::atomic<EdlSnapshot*>::exchange` (`< 0.05 ms`).
 * - Exposes live [isPlaying] and [playheadSample] [StateFlow]s for `:ui-clipper`.
 */
class EdlController internal constructor(
    backendOverride: EdlNativeBackend?,
    pollDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val playheadPollIntervalMs: Long = DEFAULT_PLAYHEAD_POLL_MS
) : AutoCloseable {

    constructor() : this(
        backendOverride = null,
        pollDispatcher = Dispatchers.Default,
        playheadPollIntervalMs = DEFAULT_PLAYHEAD_POLL_MS
    )

    private val backend: EdlNativeBackend = backendOverride ?: JniEdlBackend(this)
    private val nativeHandle = AtomicLong(0L)
    private val closed = AtomicBoolean(false)

    private val pollScope = CoroutineScope(SupervisorJob() + pollDispatcher)
    private var playheadPollJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playheadSample = MutableStateFlow(0L)
    val playheadSample: StateFlow<Long> = _playheadSample.asStateFlow()

    private val _activeSlices = MutableStateFlow<List<EdlAudioSlice>>(emptyList())
    val activeSlices: StateFlow<List<EdlAudioSlice>> = _activeSlices.asStateFlow()

    private val _lastSwapLatencyNs = MutableStateFlow(0L)
    val lastSwapLatencyNs: StateFlow<Long> = _lastSwapLatencyNs.asStateFlow()

    /**
     * Initializes the native Oboe audio stream and mmaps the `-58 dBFS` studio room-tone loop.
     */
    fun initPlayer(roomToneLoop44kPath: String): Result<Unit> {
        if (closed.get()) {
            return Result.failure(IllegalStateException("EdlController has been closed"))
        }
        if (roomToneLoop44kPath.isBlank()) {
            return Result.failure(IllegalArgumentException("roomToneLoop44kPath must not be blank"))
        }

        val handleResult = ensureHandle()
        val handle = handleResult.getOrElse { return Result.failure(it) }

        val err = backend.init(handle, roomToneLoop44kPath)
        return if (err == null) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(err))
        }
    }

    /**
     * Pre-mmaps every candidate `.pcm` file referenced in [manifest] off the audio thread so
     * subsequent take swaps and gap-scale sweeps execute in `< 0.05 ms`.
     */
    fun preloadManifestFiles(manifest: VoiceProjectManifest): Result<Unit> {
        if (closed.get()) {
            return Result.failure(IllegalStateException("EdlController has been closed"))
        }
        val handle = ensureHandle().getOrElse { return Result.failure(it) }
        val uniquePaths = manifest.clauses
            .asSequence()
            .flatMap { it.candidates.asSequence() }
            .map { it.pcmFilePath }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .toTypedArray()

        if (uniquePaths.isEmpty()) {
            return Result.success(Unit)
        }
        val err = backend.preloadFiles(handle, uniquePaths)
        return if (err == null) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(err))
        }
    }

    /**
     * Rebuilds [EdlAudioSlice] descriptors directly from [manifest] (respecting each clause's
     * `selectedTakeIndex` and `(clause.baseGapAfterSamples * manifest.globalGapScale).toInt()`)
     * and atomically swaps the live EDL snapshot.
     */
    fun updateFromManifest(manifest: VoiceProjectManifest): Result<Unit> {
        val slices = EdlTimelineCalculator.buildSlices(manifest)
        return updateEdlSnapshot(slices, manifest.globalGapScale)
    }

    /**
     * Wait-free atomic publication of a new [EdlAudioSlice] list onto the active Oboe stream.
     */
    fun updateEdlSnapshot(
        slices: List<EdlAudioSlice>,
        globalGapScale: Float = 1.0f
    ): Result<Unit> {
        if (closed.get()) {
            return Result.failure(IllegalStateException("EdlController has been closed"))
        }
        val handle = ensureHandle().getOrElse { return Result.failure(it) }

        val count = slices.size
        val paths = Array(count) { slices[it].pcmFilePath }
        val ins = LongArray(count) { slices[it].zeroCrossInSample }
        val outs = LongArray(count) { slices[it].zeroCrossOutSample }
        val gaps = IntArray(count) { slices[it].gapAfterSamples.coerceAtLeast(0) }

        val startNs = System.nanoTime()
        val err = backend.updateSnapshot(handle, paths, ins, outs, gaps, globalGapScale)
        val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(0L)

        return if (err == null) {
            _activeSlices.value = slices.toList()
            _lastSwapLatencyNs.value = elapsedNs
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(err))
        }
    }

    /**
     * Starts low-latency Oboe playback and begins live playhead polling.
     */
    fun start(): Result<Unit> {
        if (closed.get()) {
            return Result.failure(IllegalStateException("EdlController has been closed"))
        }
        val handle = nativeHandle.get()
        if (handle == 0L) {
            return Result.failure(IllegalStateException("initPlayer() must be called before start()"))
        }
        val err = backend.start(handle)
        if (err != null) {
            _isPlaying.value = false
            return Result.failure(IllegalStateException(err))
        }
        _isPlaying.value = true
        startPlayheadPolling()
        return Result.success(Unit)
    }

    /**
     * Smoothly pauses playback (660-sample fade-out on the audio thread) and stops playhead polling.
     */
    fun pause() {
        stopPlayheadPolling()
        val handle = nativeHandle.get()
        if (handle != 0L) {
            backend.pause(handle)
            _playheadSample.value = backend.getCurrentSample(handle).coerceAtLeast(0L)
        }
        _isPlaying.value = false
    }

    /**
     * Seeks the audio playhead to [timelineSample] with a click-free 660-sample crossfade if playing.
     */
    fun seekToSample(timelineSample: Long) {
        val clamped = timelineSample.coerceAtLeast(0L)
        val handle = nativeHandle.get()
        if (handle != 0L) {
            backend.seekToSample(handle, clamped)
        }
        _playheadSample.value = clamped
    }

    /**
     * Reads the current sample-accurate playhead directly from the native audio renderer and
     * synchronizes [playheadSample].
     */
    fun getCurrentSample(): Long {
        val handle = nativeHandle.get()
        if (handle == 0L) {
            return _playheadSample.value
        }
        val sample = backend.getCurrentSample(handle).coerceAtLeast(0L)
        _playheadSample.value = sample
        return sample
    }

    /**
     * Returns the real Oboe AAudio xrun count (`stream_->getXRunCount().value()`).
     */
    fun getXRunCount(): Int {
        val handle = nativeHandle.get()
        if (handle == 0L) return 0
        return backend.getXRunCount(handle)
    }

    /**
     * Synchronizes [playheadSample] and [isPlaying] with native state.
     */
    fun refreshState(): Long {
        val handle = nativeHandle.get()
        if (handle == 0L) return _playheadSample.value
        val sample = backend.getCurrentSample(handle).coerceAtLeast(0L)
        _playheadSample.value = sample
        _isPlaying.value = backend.isPlaying(handle)
        return sample
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        stopPlayheadPolling()
        pollScope.cancel()
        val handle = nativeHandle.getAndSet(0L)
        if (handle != 0L) {
            backend.close(handle)
        }
        _isPlaying.value = false
        _activeSlices.value = emptyList()
    }

    private fun ensureHandle(): Result<Long> {
        val existing = nativeHandle.get()
        if (existing != 0L) {
            return Result.success(existing)
        }
        val created = backend.create().getOrElse { return Result.failure(it) }
        if (created == 0L) {
            return Result.failure(IllegalStateException("Native EdlPlayer allocation returned 0"))
        }
        if (nativeHandle.compareAndSet(0L, created)) {
            return Result.success(created)
        }
        // Another caller raced us and won; close our duplicate handle immediately.
        backend.close(created)
        return Result.success(nativeHandle.get())
    }

    private fun startPlayheadPolling() {
        stopPlayheadPolling()
        if (playheadPollIntervalMs <= 0L) return
        playheadPollJob = pollScope.launch {
            while (isActive && _isPlaying.value) {
                val handle = nativeHandle.get()
                if (handle == 0L) break
                _playheadSample.value = backend.getCurrentSample(handle).coerceAtLeast(0L)
                delay(playheadPollIntervalMs)
            }
        }
    }

    private fun stopPlayheadPolling() {
        playheadPollJob?.cancel()
        playheadPollJob = null
    }

    // JNI native entry points implemented in audio-edl/src/main/cpp/edl_jni.cc
    private external fun nativeCreate(): Long
    private external fun nativeInit(handle: Long, roomToneLoopPath: String): String?
    private external fun nativeStart(handle: Long): String?
    private external fun nativePause(handle: Long)
    private external fun nativeClose(handle: Long)
    private external fun nativePreloadFiles(handle: Long, pcmPaths: Array<String>): String?
    private external fun nativeUpdateSnapshot(
        handle: Long,
        pcmPaths: Array<String>,
        zeroCrossInSamples: LongArray,
        zeroCrossOutSamples: LongArray,
        gapAfterSamples: IntArray,
        globalGapScale: Float
    ): String?
    private external fun nativeSeekToSample(handle: Long, timelineSample: Long)
    private external fun nativeGetCurrentSample(handle: Long): Long
    private external fun nativeGetXRunCount(handle: Long): Int
    private external fun nativeIsPlaying(handle: Long): Boolean

    private class JniEdlBackend(private val owner: EdlController) : EdlNativeBackend {
        override fun create(): Result<Long> {
            return ensureNativeLibraryLoaded().mapCatching { owner.nativeCreate() }
        }

        override fun init(handle: Long, roomToneLoopPath: String): String? =
            owner.nativeInit(handle, roomToneLoopPath)

        override fun start(handle: Long): String? =
            owner.nativeStart(handle)

        override fun pause(handle: Long) =
            owner.nativePause(handle)

        override fun close(handle: Long) =
            owner.nativeClose(handle)

        override fun preloadFiles(handle: Long, pcmPaths: Array<String>): String? =
            owner.nativePreloadFiles(handle, pcmPaths)

        override fun updateSnapshot(
            handle: Long,
            pcmPaths: Array<String>,
            zeroCrossInSamples: LongArray,
            zeroCrossOutSamples: LongArray,
            gapAfterSamples: IntArray,
            globalGapScale: Float
        ): String? = owner.nativeUpdateSnapshot(
            handle = handle,
            pcmPaths = pcmPaths,
            zeroCrossInSamples = zeroCrossInSamples,
            zeroCrossOutSamples = zeroCrossOutSamples,
            gapAfterSamples = gapAfterSamples,
            globalGapScale = globalGapScale
        )

        override fun seekToSample(handle: Long, timelineSample: Long) =
            owner.nativeSeekToSample(handle, timelineSample)

        override fun getCurrentSample(handle: Long): Long =
            owner.nativeGetCurrentSample(handle)

        override fun getXRunCount(handle: Long): Int =
            owner.nativeGetXRunCount(handle)

        override fun isPlaying(handle: Long): Boolean =
            owner.nativeIsPlaying(handle)
    }

    companion object {
        private const val NATIVE_LIB_NAME = "voicestudio_edl"
        private const val DEFAULT_PLAYHEAD_POLL_MS = 16L

        @Volatile
        private var libraryLoadResult: Result<Unit>? = null

        private fun ensureNativeLibraryLoaded(): Result<Unit> {
            libraryLoadResult?.let { return it }
            return synchronized(this) {
                libraryLoadResult ?: runCatching {
                    System.loadLibrary(NATIVE_LIB_NAME)
                }.also { libraryLoadResult = it }
            }
        }
    }
}
