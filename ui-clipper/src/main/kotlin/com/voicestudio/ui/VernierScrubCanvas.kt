package com.voicestudio.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.edl.VERNIER_COARSE_SAMPLES_PER_DP
import com.voicestudio.edl.VERNIER_FINE_SAMPLES_PER_INCH
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Pure deterministic sample-scrubbing physics for the Hairline Clipper's dual-gear Vernier deck:
 * - Coarse waveform canvas drag: `1 dp = 220 samples` (`~5.0 ms` @ 44.1 kHz).
 * - Fine Vernier tick strip drag: `160 dp (1 inch) = 2,205 samples` (`50.0 ms` @ 44.1 kHz, 1:20s/in).
 */
object VernierPhysics {
    const val VERNIER_INCH_DP: Double = 160.0
    const val COARSE_SAMPLES_PER_DP: Double = VERNIER_COARSE_SAMPLES_PER_DP.toDouble()
    const val FINE_SAMPLES_PER_DP: Double = VERNIER_FINE_SAMPLES_PER_INCH.toDouble() / VERNIER_INCH_DP

    /**
     * Converts a coarse horizontal drag delta in dp (negative = pulling tape left / forward in time)
     * into a signed sample delta.
     */
    fun coarseDragDeltaSamples(deltaDp: Float): Long =
        (-deltaDp.toDouble() * COARSE_SAMPLES_PER_DP).roundToLong()

    /**
     * Converts a fine Vernier horizontal drag delta in dp (negative = pulling strip left / forward)
     * into a signed sample delta at `160 dp = 2,205 samples = 50.0 ms`.
     */
    fun fineDragDeltaSamples(deltaDp: Float): Long =
        (-deltaDp.toDouble() * FINE_SAMPLES_PER_DP).roundToLong()

    /**
     * Converts a signed pixel drag delta into a floating-point sample delta for drift-free
     * accumulation across multi-frame touch gestures.
     */
    fun dragDeltaSamplesExact(
        deltaPx: Float,
        density: Float,
        samplesPerDp: Double
    ): Double {
        val safeDensity = density.coerceAtLeast(0.5f).toDouble()
        val deltaDp = deltaPx.toDouble() / safeDensity
        return -deltaDp * samplesPerDp
    }

    /**
     * Snaps [rawSample] strictly to the nearest `zeroCrossIn` or `zeroCrossOut` in [projection].
     */
    fun snapToNearestZeroCrossing(
        rawSample: Long,
        projection: ClipperTimelineProjection
    ): Long = projection.nearestZeroCrossingSample(rawSample)
}

/**
 * Center Zone: High-contrast OLED min/max peak waveform canvas (`#000000` void, `#B8FF00` Volt Lime
 * played / `#0088FF` Broadcast Cobalt upcoming) with a permanently fixed center vertical razor blade
 * at `canvasWidth / 2f`.
 *
 * Displays real [waveformMinMaxPeaks] extracted from 44.1 kHz `.pcm` files (never fabricated),
 * `WordToken` zero-crossing boundaries, `f0PeakSample` pitch emphasis markers, and `videoAnchorId`
 * beat-cut flags. Supports coarse horizontal drag (`1 dp = 220 samples`) with magnetic snap to
 * zero-crossing and `HapticFeedbackConstants.CLOCK_TICK` on release.
 */
@Composable
fun VernierScrubCanvas(
    projection: ClipperTimelineProjection,
    playheadSample: Long,
    waveformMinMaxPeaks: FloatArray,
    onSeekSample: (Long) -> Unit,
    modifier: Modifier = Modifier,
    samplesPerPeakBucket: Int = VERNIER_COARSE_SAMPLES_PER_DP,
    snapToZeroCrossing: ((Long) -> Long)? = null,
    onHapticTick: (() -> Unit)? = null
) {
    val view = LocalView.current
    val density = LocalDensity.current.density
    val latestPlayhead by rememberUpdatedState(playheadSample)
    val latestProjection by rememberUpdatedState(projection)
    val latestOnSeek by rememberUpdatedState(onSeekSample)
    val latestSnap by rememberUpdatedState(snapToZeroCrossing)
    val latestHaptic by rememberUpdatedState(onHapticTick)

    val shape = RoundedCornerShape(8.dp)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ClipperDeckColors.OledVoid)
            .border(1.dp, ClipperDeckColors.GraphiteHairline, shape)
            .testTag("vernier_waveform_canvas")
            .semantics {
                contentDescription = "Waveform timeline with fixed center razor blade"
            }
            .pointerInput(density) {
                var accumulatedSample = 0.0
                detectHorizontalDragGestures(
                    onDragStart = {
                        accumulatedSample = latestPlayhead.toDouble()
                    },
                    onHorizontalDrag = { change, dragAmountPx ->
                        change.consume()
                        val maxSamples = latestProjection.totalTimelineSamples.coerceAtLeast(0L).toDouble()
                        accumulatedSample = (
                            accumulatedSample + VernierPhysics.dragDeltaSamplesExact(
                                deltaPx = dragAmountPx,
                                density = density,
                                samplesPerDp = VernierPhysics.COARSE_SAMPLES_PER_DP
                            )
                            ).coerceIn(0.0, maxSamples)
                        latestOnSeek(accumulatedSample.roundToLong())
                    },
                    onDragEnd = {
                        val raw = accumulatedSample.roundToLong()
                        val snapped = latestSnap?.invoke(raw)
                            ?: VernierPhysics.snapToNearestZeroCrossing(raw, latestProjection)
                        latestOnSeek(snapped)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        latestHaptic?.invoke()
                    },
                    onDragCancel = {
                        val raw = accumulatedSample.roundToLong()
                        val snapped = latestSnap?.invoke(raw)
                            ?: VernierPhysics.snapToNearestZeroCrossing(raw, latestProjection)
                        latestOnSeek(snapped)
                    }
                )
            }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        if (canvasWidth <= 0f || canvasHeight <= 0f) return@Canvas

        val centerX = canvasWidth / 2f
        val midY = canvasHeight / 2f
        val samplesPerPx = (VernierPhysics.COARSE_SAMPLES_PER_DP / density.coerceAtLeast(0.5f).toDouble())
        val halfVisibleSamples = (centerX * samplesPerPx).toLong() + samplesPerPeakBucket
        val visibleStartSample = playheadSample - halfVisibleSamples
        val visibleEndSample = playheadSample + halfVisibleSamples

        fun sampleToX(sample: Long): Float =
            centerX + ((sample - playheadSample).toDouble() / samplesPerPx).toFloat()

        // 1. Inter-clause compressed gap regions (recessed obsidian wells)
        for (clauseSpan in projection.clauseSpans) {
            if (clauseSpan.scaledGapAfterSamples <= 0L) continue
            if (clauseSpan.gapEndSample < visibleStartSample || clauseSpan.gapStartSample > visibleEndSample) continue
            val leftX = max(0f, sampleToX(clauseSpan.gapStartSample))
            val rightX = min(canvasWidth, sampleToX(clauseSpan.gapEndSample))
            if (rightX > leftX) {
                drawRect(
                    color = ClipperDeckColors.ConsoleObsidian,
                    topLeft = Offset(leftX, 0f),
                    size = Size(rightX - leftX, canvasHeight)
                )
            }
        }

        // 2. Center zero-amplitude reference hairline
        drawLine(
            color = ClipperDeckColors.GraphiteHairline,
            start = Offset(0f, midY),
            end = Offset(canvasWidth, midY),
            strokeWidth = 1.dp.toPx()
        )

        // 3. Real min/max peak waveform (interleaved [min, max] pairs; zero fabrication if empty)
        val bucketCount = waveformMinMaxPeaks.size / 2
        val safeBucketSamples = samplesPerPeakBucket.coerceAtLeast(1).toLong()
        if (bucketCount > 0) {
            val firstBucket = max(0, floor(visibleStartSample.toDouble() / safeBucketSamples).toInt())
            val lastBucket = min(bucketCount - 1, ceil(visibleEndSample.toDouble() / safeBucketSamples).toInt())
            val maxAmplitudePx = canvasHeight * 0.39f
            val minHalfStroke = 1.dp.toPx()
            val barWidthPx = max(1.25.dp.toPx(), (safeBucketSamples / samplesPerPx).toFloat() * 0.72f)

            for (bucketIdx in firstBucket..lastBucket) {
                val bucketSample = bucketIdx * safeBucketSamples + safeBucketSamples / 2L
                val x = sampleToX(bucketSample)
                if (x < -barWidthPx || x > canvasWidth + barWidthPx) continue

                val rawMin = waveformMinMaxPeaks[bucketIdx * 2].coerceIn(-1f, 1f)
                val rawMax = waveformMinMaxPeaks[bucketIdx * 2 + 1].coerceIn(-1f, 1f)
                val topY = midY - (rawMax * maxAmplitudePx)
                val bottomY = midY - (rawMin * maxAmplitudePx)
                val y1 = min(topY, bottomY)
                val y2 = max(topY, bottomY)
                val spanY = max(y2 - y1, minHalfStroke * 2f)
                val centerBarY = (y1 + y2) * 0.5f

                val barColor = if (x <= centerX) {
                    ClipperDeckColors.AcidVoltLime.copy(alpha = 0.92f)
                } else {
                    ClipperDeckColors.BroadcastCobalt.copy(alpha = 0.86f)
                }

                drawLine(
                    color = barColor,
                    start = Offset(x, centerBarY - spanY * 0.5f),
                    end = Offset(x, centerBarY + spanY * 0.5f),
                    strokeWidth = barWidthPx,
                    cap = StrokeCap.Round
                )
            }
        }

        // 4. WordToken zero-crossing boundaries, f0PeakSample pitch markers, and videoAnchorId cuts
        val tickLen = 8.dp.toPx()
        val diamondRadius = 3.dp.toPx()
        val flagSize = 7.dp.toPx()

        for (span in projection.wordSpans) {
            if (span.timelineZeroCrossOut < visibleStartSample || span.timelineZeroCrossIn > visibleEndSample) {
                continue
            }
            val inX = sampleToX(span.timelineZeroCrossIn)
            val outX = sampleToX(span.timelineZeroCrossOut)

            if (inX in 0f..canvasWidth) {
                drawLine(
                    color = ClipperDeckColors.GraphiteHairline,
                    start = Offset(inX, 0f),
                    end = Offset(inX, tickLen),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = ClipperDeckColors.GraphiteHairline,
                    start = Offset(inX, canvasHeight - tickLen),
                    end = Offset(inX, canvasHeight),
                    strokeWidth = 1.dp.toPx()
                )
            }
            if (outX in 0f..canvasWidth) {
                drawLine(
                    color = ClipperDeckColors.GraphiteSubtle,
                    start = Offset(outX, canvasHeight - tickLen * 0.7f),
                    end = Offset(outX, canvasHeight),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // f0PeakSample pitch emphasis diamond pip
            val f0X = sampleToX(span.timelineF0PeakSample)
            if (f0X in 0f..canvasWidth) {
                val pipY = canvasHeight - 14.dp.toPx()
                val pipColor = if (!span.token.videoAnchorId.isNullOrBlank()) {
                    ClipperDeckColors.BroadcastCobalt
                } else {
                    ClipperDeckColors.TelemetryCyan.copy(alpha = 0.55f)
                }
                val diamond = Path().apply {
                    moveTo(f0X, pipY - diamondRadius)
                    lineTo(f0X + diamondRadius, pipY)
                    lineTo(f0X, pipY + diamondRadius)
                    lineTo(f0X - diamondRadius, pipY)
                    close()
                }
                drawPath(path = diamond, color = pipColor)
            }

            // videoAnchorId cut marker strictly aligned to timelineZeroCrossIn
            if (!span.token.videoAnchorId.isNullOrBlank() && inX in 0f..canvasWidth) {
                drawLine(
                    color = ClipperDeckColors.BroadcastCobalt,
                    start = Offset(inX, 0f),
                    end = Offset(inX, canvasHeight),
                    strokeWidth = 1.5.dp.toPx()
                )
                val pennant = Path().apply {
                    moveTo(inX, 0f)
                    lineTo(inX + flagSize, flagSize * 0.75f)
                    lineTo(inX, flagSize * 1.5f)
                    close()
                }
                drawPath(path = pennant, color = ClipperDeckColors.BroadcastCobalt)
            }
        }

        // 5. Permanently fixed center vertical razor blade at canvasWidth / 2f
        val bladeWidth = 1.5.dp.toPx()
        val reticleHalf = 5.dp.toPx()
        val reticleHeight = 6.dp.toPx()
        drawLine(
            color = ClipperDeckColors.AcidVoltLime,
            start = Offset(centerX, 0f),
            end = Offset(centerX, canvasHeight),
            strokeWidth = bladeWidth
        )
        val topReticle = Path().apply {
            moveTo(centerX - reticleHalf, 0f)
            lineTo(centerX + reticleHalf, 0f)
            lineTo(centerX, reticleHeight)
            close()
        }
        drawPath(path = topReticle, color = ClipperDeckColors.AcidVoltLime)
        val bottomReticle = Path().apply {
            moveTo(centerX - reticleHalf, canvasHeight)
            lineTo(centerX + reticleHalf, canvasHeight)
            lineTo(centerX, canvasHeight - reticleHeight)
            close()
        }
        drawPath(path = bottomReticle, color = ClipperDeckColors.AcidVoltLime)
    }
}

/**
 * Bottom Zone: Precision 1:20 Vernier tick strip (`160 dp (1 inch) = 2,205 samples = 50.0 ms`).
 * Dragging horizontally scrubs at fine micrometer resolution; releasing magnetically snaps to the
 * nearest `WordToken.zeroCrossIn` / `zeroCrossOut` and triggers `HapticFeedbackConstants.CLOCK_TICK`.
 */
@Composable
fun VernierScrubBar(
    projection: ClipperTimelineProjection,
    playheadSample: Long,
    onSeekSample: (Long) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 54.dp,
    snapToZeroCrossing: ((Long) -> Long)? = null,
    onHapticTick: (() -> Unit)? = null
) {
    val view = LocalView.current
    val density = LocalDensity.current.density
    val latestPlayhead by rememberUpdatedState(playheadSample)
    val latestProjection by rememberUpdatedState(projection)
    val latestOnSeek by rememberUpdatedState(onSeekSample)
    val latestSnap by rememberUpdatedState(snapToZeroCrossing)
    val latestHaptic by rememberUpdatedState(onHapticTick)

    val shape = RoundedCornerShape(8.dp)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(ClipperDeckColors.ConsoleObsidian)
            .border(1.dp, ClipperDeckColors.GraphiteHairline, shape)
            .testTag("vernier_scrub_bar")
            .semantics {
                contentDescription = "Vernier fine scrub strip 50 milliseconds per inch"
            }
            .pointerInput(density) {
                var accumulatedSample = 0.0
                detectHorizontalDragGestures(
                    onDragStart = {
                        accumulatedSample = latestPlayhead.toDouble()
                    },
                    onHorizontalDrag = { change, dragAmountPx ->
                        change.consume()
                        val maxSamples = latestProjection.totalTimelineSamples.coerceAtLeast(0L).toDouble()
                        accumulatedSample = (
                            accumulatedSample + VernierPhysics.dragDeltaSamplesExact(
                                deltaPx = dragAmountPx,
                                density = density,
                                samplesPerDp = VernierPhysics.FINE_SAMPLES_PER_DP
                            )
                            ).coerceIn(0.0, maxSamples)
                        latestOnSeek(accumulatedSample.roundToLong())
                    },
                    onDragEnd = {
                        val raw = accumulatedSample.roundToLong()
                        val snapped = latestSnap?.invoke(raw)
                            ?: VernierPhysics.snapToNearestZeroCrossing(raw, latestProjection)
                        latestOnSeek(snapped)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        latestHaptic?.invoke()
                    },
                    onDragCancel = {
                        val raw = accumulatedSample.roundToLong()
                        val snapped = latestSnap?.invoke(raw)
                            ?: VernierPhysics.snapToNearestZeroCrossing(raw, latestProjection)
                        latestOnSeek(snapped)
                    }
                )
            }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        if (canvasWidth <= 0f || canvasHeight <= 0f) return@Canvas

        val centerX = canvasWidth / 2f
        val samplesPerPx = VernierPhysics.FINE_SAMPLES_PER_DP / density.coerceAtLeast(0.5f).toDouble()
        val sampleRate = projection.sampleRate.coerceAtLeast(1).toDouble()
        val samplesPerMs = sampleRate / 1_000.0 // 44.1 samples/ms @ 44.1 kHz
        val halfVisibleMs = ((centerX * samplesPerPx) / samplesPerMs).toLong() + 2L
        val centerMs = (playheadSample.toDouble() / samplesPerMs).toLong()

        fun sampleToX(sample: Double): Float =
            centerX + ((sample - playheadSample.toDouble()) / samplesPerPx).toFloat()

        // 1. Scrolling precision millisecond graduations (1ms minor, 5ms medium, 10ms major)
        val startMs = max(0L, centerMs - halfVisibleMs)
        val endMs = centerMs + halfVisibleMs
        for (ms in startMs..endMs) {
            val x = sampleToX(ms * samplesPerMs)
            if (x < 0f || x > canvasWidth) continue
            val isMajor = ms % 10L == 0L
            val isMedium = ms % 5L == 0L
            val tickHeight = when {
                isMajor -> canvasHeight * 0.56f
                isMedium -> canvasHeight * 0.36f
                else -> canvasHeight * 0.20f
            }
            val tickColor = when {
                isMajor -> ClipperDeckColors.StudioCrisp.copy(alpha = 0.65f)
                isMedium -> ClipperDeckColors.TelemetryMuted.copy(alpha = 0.55f)
                else -> ClipperDeckColors.GraphiteHairline
            }
            val topY = (canvasHeight - tickHeight) * 0.5f
            drawLine(
                color = tickColor,
                start = Offset(x, topY),
                end = Offset(x, topY + tickHeight),
                strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 2. Highlight zero-crossing snap targets inside the fine Vernier window
        val halfVisibleSamples = (centerX * samplesPerPx).toLong()
        val visStart = playheadSample - halfVisibleSamples
        val visEnd = playheadSample + halfVisibleSamples
        for (span in projection.wordSpans) {
            if (span.timelineZeroCrossIn in visStart..visEnd) {
                val x = sampleToX(span.timelineZeroCrossIn.toDouble())
                val color = if (!span.token.videoAnchorId.isNullOrBlank()) {
                    ClipperDeckColors.BroadcastCobalt
                } else {
                    ClipperDeckColors.TelemetryCyan
                }
                drawCircle(
                    color = color,
                    radius = 2.5.dp.toPx(),
                    center = Offset(x, 6.dp.toPx())
                )
            }
        }

        // 3. Fixed center micrometer needle locked to canvasWidth / 2f
        drawLine(
            color = ClipperDeckColors.AcidVoltLime,
            start = Offset(centerX, 0f),
            end = Offset(centerX, canvasHeight),
            strokeWidth = 2.dp.toPx()
        )
    }
}
