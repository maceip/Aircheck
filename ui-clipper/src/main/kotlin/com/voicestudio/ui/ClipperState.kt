package com.voicestudio.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VERNIER_COARSE_SAMPLES_PER_DP
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.edl.WordToken
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Aircheck OLED Broadcast Deck color tokens mirrored for standalone `:ui-clipper` composition.
 * Matches `com.example.ui.theme.AircheckColors` verbatim.
 */
object ClipperDeckColors {
    val OledVoid = Color(0xFF000000)
    val ConsoleObsidian = Color(0xFF0A0C10)
    val ConsoleElevated = Color(0xFF12151C)
    val GraphiteHairline = Color(0xFF232730)
    val GraphiteSubtle = Color(0xFF1A1D24)
    val AcidVoltLime = Color(0xFFB8FF00)
    val AcidVoltLimeDim = Color(0xFF243300)
    val BroadcastCobalt = Color(0xFF0088FF)
    val BroadcastCobaltDim = Color(0xFF001E3D)
    val TelemetryCyan = Color(0xFF18D4F5)
    val EvidenceAmber = Color(0xFFF5B700)
    val LiveTallyGreen = Color(0xFF00E676)
    val OnAirCrimson = Color(0xFFFF1744)
    val OnAirCrimsonSurface = Color(0xFF1F090E)
    val StudioCrisp = Color(0xFFF4F6FA)
    val TelemetryMuted = Color(0xFF8A93A2)
    val PureBlackInk = Color(0xFF000000)
}

/**
 * Minimum and maximum allowed global gap compression scales (`25%`..`100%`).
 */
const val MIN_GLOBAL_GAP_SCALE: Float = 0.25f
const val MAX_GLOBAL_GAP_SCALE: Float = 1.0f

/**
 * Immutable UI state contract for the VoiceStudio Hairline Clipper screen.
 * Decoupled from Android Context so `:app` wires Room/EDL/WorkManager cleanly in Wave 3
 * and Compose tests exercise every control deterministically.
 *
 * [waveformMinMaxPeaks] holds interleaved `(min, max)` peak pairs in `[-1f, 1f]` extracted from
 * real 44.1 kHz Float32 `.pcm` files (bucketed at [samplesPerPeakBucket] samples per pair).
 * Empty when no real `.pcm` waveform has been decoded yet — NEVER fabricated.
 */
@Immutable
data class ClipperUiState(
    val manifest: VoiceProjectManifest? = null,
    val playheadSample: Long = 0L,
    val isPlaying: Boolean = false,
    val waveformMinMaxPeaks: FloatArray = FloatArray(0),
    val samplesPerPeakBucket: Int = VERNIER_COARSE_SAMPLES_PER_DP,
    val otaReady: Boolean = false,
    val otaStatusText: String = "",
    val synthesisError: String? = null,
    val editingClauseId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipperUiState) return false
        return manifest == other.manifest &&
            playheadSample == other.playheadSample &&
            isPlaying == other.isPlaying &&
            waveformMinMaxPeaks.contentEquals(other.waveformMinMaxPeaks) &&
            samplesPerPeakBucket == other.samplesPerPeakBucket &&
            otaReady == other.otaReady &&
            otaStatusText == other.otaStatusText &&
            synthesisError == other.synthesisError &&
            editingClauseId == other.editingClauseId
    }

    override fun hashCode(): Int {
        var result = manifest?.hashCode() ?: 0
        result = 31 * result + playheadSample.hashCode()
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + waveformMinMaxPeaks.contentHashCode()
        result = 31 * result + samplesPerPeakBucket
        result = 31 * result + otaReady.hashCode()
        result = 31 * result + otaStatusText.hashCode()
        result = 31 * result + (synthesisError?.hashCode() ?: 0)
        result = 31 * result + (editingClauseId?.hashCode() ?: 0)
        return result
    }
}

/**
 * Callback contract invoked by [ClipperScreen] on user actions.
 */
@Immutable
data class ClipperActions(
    val onPlayPause: () -> Unit = {},
    val onSeekSample: (Long) -> Unit = {},
    val onSwapTake: (clauseId: String, nextTakeIndex: Int) -> Unit = { _, _ -> },
    val onOverrideClauseText: (clauseId: String, newText: String, newPhonemes: String) -> Unit = { _, _, _ -> },
    val onGlobalGapScaleChange: (Float) -> Unit = {},
    val onToggleVideoAnchor: (clauseId: String, wordIndex: Int, videoAnchorId: String?) -> Unit = { _, _, _ -> },
    val onExportClip: () -> Unit = {},
    val onOpenOtaSync: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onEditingClauseChange: ((String?) -> Unit)? = null
)

/**
 * Projected timeline interval for one synthesized clause after applying `selectedTakeIndex`
 * and `globalGapScale`. Matches `com.voicestudio.edl.computeAnchorTimestampsUs` sample-for-sample.
 */
@Immutable
data class TimelineClauseSpan(
    val clauseId: String,
    val clauseIndex: Int,
    val clause: ClauseNode,
    val clauseStartInSample: Long,
    val timelineStartSample: Long,
    val timelineEndSample: Long,
    val scaledGapAfterSamples: Long
) {
    val gapStartSample: Long get() = timelineEndSample
    val gapEndSample: Long get() = timelineEndSample + scaledGapAfterSamples
}

/**
 * Projected timeline interval for one [WordToken] inside a synthesized [ClauseNode].
 */
@Immutable
data class TimelineWordSpan(
    val clauseId: String,
    val clauseIndex: Int,
    val wordIndex: Int,
    val token: WordToken,
    val selectedTakeIndex: Int,
    val candidateCount: Int,
    val timelineStartSample: Long,
    val timelineEndSample: Long,
    val timelineZeroCrossIn: Long,
    val timelineZeroCrossOut: Long,
    val timelineF0PeakSample: Long
)

/**
 * Deterministic timeline projection computed directly from [VoiceProjectManifest].
 */
@Immutable
data class ClipperTimelineProjection(
    val sampleRate: Int = SYNTHESIS_SAMPLE_RATE_HZ,
    val globalGapScale: Float = MAX_GLOBAL_GAP_SCALE,
    val clauseSpans: List<TimelineClauseSpan> = emptyList(),
    val wordSpans: List<TimelineWordSpan> = emptyList(),
    val totalTimelineSamples: Long = 0L,
    val uncompressedTimelineSamples: Long = 0L
) {
    val compressedDeltaSamples: Long
        get() = totalTimelineSamples - uncompressedTimelineSamples

    val compressedDeltaMs: Long
        get() = (compressedDeltaSamples * 1_000L) / sampleRate.coerceAtLeast(1).toLong()

    /**
     * Magnetically snaps [rawSample] strictly to the nearest [WordToken.zeroCrossIn] or
     * [WordToken.zeroCrossOut] projected onto the compressed timeline.
     */
    fun nearestZeroCrossingSample(rawSample: Long): Long {
        val clamped = rawSample.coerceIn(0L, totalTimelineSamples.coerceAtLeast(0L))
        if (wordSpans.isEmpty()) return clamped

        var bestSample = wordSpans.first().timelineZeroCrossIn
        var bestDistance = abs(clamped - bestSample)
        for (span in wordSpans) {
            val inDist = abs(clamped - span.timelineZeroCrossIn)
            if (inDist < bestDistance) {
                bestDistance = inDist
                bestSample = span.timelineZeroCrossIn
            }
            val outDist = abs(clamped - span.timelineZeroCrossOut)
            if (outDist < bestDistance) {
                bestDistance = outDist
                bestSample = span.timelineZeroCrossOut
            }
        }
        return bestSample
    }

    /**
     * Returns the index into [wordSpans] currently under [playheadSample], or `-1` if empty.
     */
    fun activeWordIndexForSample(playheadSample: Long): Int {
        if (wordSpans.isEmpty()) return -1
        val clamped = playheadSample.coerceIn(0L, totalTimelineSamples.coerceAtLeast(0L))
        wordSpans.forEachIndexed { idx, span ->
            if (clamped in span.timelineZeroCrossIn..span.timelineZeroCrossOut) {
                return idx
            }
        }
        var nearestIdx = 0
        var nearestDist = Long.MAX_VALUE
        wordSpans.forEachIndexed { idx, span ->
            val mid = (span.timelineZeroCrossIn + span.timelineZeroCrossOut) / 2L
            val dist = abs(clamped - mid)
            if (dist < nearestDist) {
                nearestDist = dist
                nearestIdx = idx
            }
        }
        return nearestIdx
    }

    companion object {
        val EMPTY = ClipperTimelineProjection()

        fun fromManifest(manifest: VoiceProjectManifest?): ClipperTimelineProjection {
            if (manifest == null || manifest.clauses.isEmpty()) return EMPTY
            val rate = manifest.sampleRate.coerceAtLeast(1)
            val clampedScale = manifest.globalGapScale.coerceIn(MIN_GLOBAL_GAP_SCALE, MAX_GLOBAL_GAP_SCALE)

            val clauseSpans = ArrayList<TimelineClauseSpan>(manifest.clauses.size)
            val wordSpans = ArrayList<TimelineWordSpan>()

            var cumulativeSamples = 0L
            var uncompressedSamples = 0L

            for ((clauseIdx, clause) in manifest.clauses.withIndex()) {
                val winner = clause.selectedCandidate ?: continue
                val clauseStartIn = winner.tokens.firstOrNull()?.zeroCrossIn ?: 0L
                val activeDuration = if (winner.tokens.isNotEmpty()) {
                    (winner.tokens.last().zeroCrossOut - clauseStartIn).coerceAtLeast(0L)
                } else {
                    winner.totalSamples.coerceAtLeast(0L)
                }
                val scaledGap = (clause.baseGapAfterSamples * manifest.globalGapScale)
                    .toLong()
                    .coerceAtLeast(0L)
                val baseGap = clause.baseGapAfterSamples.toLong().coerceAtLeast(0L)

                clauseSpans += TimelineClauseSpan(
                    clauseId = clause.clauseId,
                    clauseIndex = clauseIdx,
                    clause = clause,
                    clauseStartInSample = clauseStartIn,
                    timelineStartSample = cumulativeSamples,
                    timelineEndSample = cumulativeSamples + activeDuration,
                    scaledGapAfterSamples = scaledGap
                )

                for ((wordIdx, token) in winner.tokens.withIndex()) {
                    val startOffset = (token.startSample - clauseStartIn).coerceAtLeast(0L)
                    val endOffset = (token.endSample - clauseStartIn).coerceAtLeast(startOffset)
                    val zcInOffset = (token.zeroCrossIn - clauseStartIn).coerceAtLeast(0L)
                    val zcOutOffset = (token.zeroCrossOut - clauseStartIn).coerceAtLeast(zcInOffset)
                    val f0Offset = (token.f0PeakSample - clauseStartIn).coerceAtLeast(0L)

                    wordSpans += TimelineWordSpan(
                        clauseId = clause.clauseId,
                        clauseIndex = clauseIdx,
                        wordIndex = wordIdx,
                        token = token,
                        selectedTakeIndex = clause.selectedTakeIndex,
                        candidateCount = clause.candidates.size,
                        timelineStartSample = cumulativeSamples + startOffset,
                        timelineEndSample = cumulativeSamples + endOffset,
                        timelineZeroCrossIn = cumulativeSamples + zcInOffset,
                        timelineZeroCrossOut = cumulativeSamples + zcOutOffset,
                        timelineF0PeakSample = cumulativeSamples + f0Offset
                    )
                }

                cumulativeSamples += activeDuration + scaledGap
                uncompressedSamples += activeDuration + baseGap
            }

            return ClipperTimelineProjection(
                sampleRate = rate,
                globalGapScale = clampedScale,
                clauseSpans = clauseSpans,
                wordSpans = wordSpans,
                totalTimelineSamples = cumulativeSamples,
                uncompressedTimelineSamples = uncompressedSamples
            )
        }
    }
}

/**
 * Formats a sample offset into broadcast tabular timecode `MM:SS.mmm` at [sampleRate].
 */
fun formatSampleTimecode(sample: Long, sampleRate: Int = SYNTHESIS_SAMPLE_RATE_HZ): String {
    val safeRate = sampleRate.coerceAtLeast(1).toLong()
    val safeSample = sample.coerceAtLeast(0L)
    val totalMs = (safeSample * 1_000L) / safeRate
    val minutes = totalMs / 60_000L
    val seconds = (totalMs % 60_000L) / 1_000L
    val millis = totalMs % 1_000L
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}

/**
 * Extracts real interleaved `(min, max)` peak pairs from headerless 44.1 kHz Float32 little-endian
 * `.pcm` files referenced by [manifest].
 *
 * Constitutional Law 2: Never fabricates peaks. If no referenced `.pcm` files exist on disk,
 * returns an empty [FloatArray].
 */
object PcmWaveformExtractor {
    private const val BYTES_PER_FLOAT = 4

    fun extractMinMaxPeaks(
        manifest: VoiceProjectManifest,
        samplesPerBucket: Int = VERNIER_COARSE_SAMPLES_PER_DP,
        baseDir: File? = null
    ): FloatArray {
        val bucketSize = samplesPerBucket.coerceAtLeast(1)
        val projection = ClipperTimelineProjection.fromManifest(manifest)
        if (projection.totalTimelineSamples <= 0L) return FloatArray(0)

        val bucketCount = ((projection.totalTimelineSamples + bucketSize - 1L) / bucketSize).toInt()
        if (bucketCount <= 0) return FloatArray(0)

        val peaks = FloatArray(bucketCount * 2)
        val bucketHasData = BooleanArray(bucketCount)
        var anyFileDecoded = false

        val readBuffer = ByteArray(4096 * BYTES_PER_FLOAT)
        val byteBuf = ByteBuffer.wrap(readBuffer).order(ByteOrder.LITTLE_ENDIAN)

        for (span in projection.clauseSpans) {
            val winner = span.clause.selectedCandidate ?: continue
            val rawPath = winner.pcmFilePath
            val file = resolvePcmFile(rawPath, baseDir)
            if (file == null || !file.isFile || !file.canRead()) continue

            val fileSamples = file.length() / BYTES_PER_FLOAT
            val activeDuration = (span.timelineEndSample - span.timelineStartSample).coerceAtLeast(0L)
            if (activeDuration == 0L || span.clauseStartInSample >= fileSamples) continue

            val readableSamples = min(activeDuration, fileSamples - span.clauseStartInSample)
            if (readableSamples <= 0L) continue

            anyFileDecoded = true
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(span.clauseStartInSample * BYTES_PER_FLOAT)
                var remaining = readableSamples
                var localOffset = 0L
                while (remaining > 0L) {
                    val chunkSamples = min(remaining, 4096L).toInt()
                    val bytesToRead = chunkSamples * BYTES_PER_FLOAT
                    val bytesRead = raf.read(readBuffer, 0, bytesToRead)
                    if (bytesRead <= 0) break
                    val floatsRead = bytesRead / BYTES_PER_FLOAT
                    byteBuf.clear()
                    val floatBuf = byteBuf.asFloatBuffer()
                    for (i in 0 until floatsRead) {
                        val sampleValue = floatBuf.get(i).coerceIn(-1f, 1f)
                        val timelineSample = span.timelineStartSample + localOffset + i
                        val bucketIdx = (timelineSample / bucketSize).toInt()
                        if (bucketIdx in 0 until bucketCount) {
                            val minPos = bucketIdx * 2
                            val maxPos = minPos + 1
                            if (!bucketHasData[bucketIdx]) {
                                peaks[minPos] = sampleValue
                                peaks[maxPos] = sampleValue
                                bucketHasData[bucketIdx] = true
                            } else {
                                peaks[minPos] = min(peaks[minPos], sampleValue)
                                peaks[maxPos] = max(peaks[maxPos], sampleValue)
                            }
                        }
                    }
                    localOffset += floatsRead
                    remaining -= floatsRead
                }
            }
        }

        return if (anyFileDecoded) peaks else FloatArray(0)
    }

    private fun resolvePcmFile(path: String, baseDir: File?): File? {
        if (path.isBlank()) return null
        val direct = File(path)
        if (direct.isAbsolute) return direct
        return if (baseDir != null) File(baseDir, path) else direct
    }
}
