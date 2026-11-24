package com.example.ui.draft

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ui.theme.AcidVoltLime
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.EvidenceAmber
import com.example.ui.theme.GraphiteHairline
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted
import kotlin.math.roundToInt

private const val MIN_DENSITY_DELTA = -25
private const val MAX_DENSITY_DELTA = 15
private const val DENSITY_STEP = 5

/**
 * Inline Eye/Thumb divider that behaves as a passive `1dp` hairline in non-CUT stages
 * and upgrades into a tactile `-25%..+15%` (5% detent) density scrubber rule in Stage 3 (`03 CUT`).
 *
 * Geometry: exact `24dp` touch target height with a centered `1dp` hairline and playhead notch.
 */
@Composable
fun DensityScrubberRule(
    active: Boolean,
    currentDeltaPercent: Int,
    isApplying: Boolean,
    onDeltaPreview: (Int) -> Unit,
    onDeltaCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isThinking: Boolean = false
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val tokens = LocalAircheckTokens.current
    val accent = tokens.channelAccent

    val currentActive by rememberUpdatedState(active)
    val currentDelta by rememberUpdatedState(snapDensityDelta(currentDeltaPercent))
    val currentOnPreview by rememberUpdatedState(onDeltaPreview)
    val currentOnCommit by rememberUpdatedState(onDeltaCommit)

    var widthPx by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var dragPreviewDelta by remember { mutableIntStateOf(snapDensityDelta(currentDeltaPercent)) }
    var dragBaseDelta by remember { mutableIntStateOf(snapDensityDelta(currentDeltaPercent)) }
    var accumulatedDx by remember { mutableFloatStateOf(0f) }

    val displayedDelta = if (isDragging) dragPreviewDelta else snapDensityDelta(currentDeltaPercent)
    val formattedDelta = formatDensityDelta(displayedDelta)

    val siriThinkingActive = isThinking || isApplying
    val siriFlowPhase: Float
    val siriGlowBreath: Float
    if (siriThinkingActive) {
        val siriTransition = rememberInfiniteTransition(label = "siri_hairline_thinking")
        siriFlowPhase = siriTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "siri_flow_phase"
        ).value
        siriGlowBreath = siriTransition.animateFloat(
            initialValue = 0.65f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 820, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "siri_glow_breath"
        ).value
    } else {
        siriFlowPhase = 0f
        siriGlowBreath = 0f
    }

    val applyingPulse = if (isApplying) siriGlowBreath else 1f

    val minStepPx = with(density) { 22.dp.toPx() }
    val dragModifier = if (active) {
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = {
                    if (!currentActive) return@detectHorizontalDragGestures
                    isDragging = true
                    dragBaseDelta = currentDelta
                    dragPreviewDelta = currentDelta
                    accumulatedDx = 0f
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                },
                onHorizontalDrag = { change, dragAmount ->
                    if (!currentActive) return@detectHorizontalDragGestures
                    change.consume()
                    accumulatedDx += dragAmount
                    val stepPx = maxOf(minStepPx, (widthPx * 0.36f) / 5f)
                    val deltaSteps = (accumulatedDx / stepPx).roundToInt()
                    val nextDelta = snapDensityDelta(dragBaseDelta + deltaSteps * DENSITY_STEP)
                    if (nextDelta != dragPreviewDelta) {
                        dragPreviewDelta = nextDelta
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        currentOnPreview(nextDelta)
                    }
                },
                onDragEnd = {
                    if (!isDragging) return@detectHorizontalDragGestures
                    val committed = dragPreviewDelta
                    isDragging = false
                    accumulatedDx = 0f
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    currentOnCommit(committed)
                },
                onDragCancel = {
                    if (!isDragging) return@detectHorizontalDragGestures
                    val rollback = dragBaseDelta
                    isDragging = false
                    accumulatedDx = 0f
                    dragPreviewDelta = rollback
                    currentOnPreview(rollback)
                }
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .zIndex(5f)
            .fillMaxWidth()
            .height(24.dp)
            .onSizeChanged { widthPx = it.width }
            .then(dragModifier)
            .semantics {
                contentDescription = when {
                    siriThinkingActive -> "AI thinking hairline glow"
                    active -> "Density scrubber rule"
                    else -> "Stage divider"
                }
                if (siriThinkingActive) {
                    stateDescription = "Thinking"
                } else if (active) {
                    stateDescription = formattedDelta
                }
            }
            .testTag("divider_density_scrubber"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            val hairlinePx = 1.dp.toPx()

            if (siriThinkingActive) {
                // Stretched new-Siri iridescent liquid plasma hairline glow while AI is thinking
                val w = size.width.coerceAtLeast(1f)
                val shift = siriFlowPhase * w
                val siriColors = listOf(
                    Color(0xFF18D4F5), // Luminous Cyan
                    Color(0xFF7B61FF), // Electric Violet
                    Color(0xFFFF3B9A), // Siri Magenta
                    Color(0xFFFF8A00), // Warm Coral
                    Color(0xFFB8FF00), // Aircheck Volt Lime
                    Color(0xFF18D4F5)  // Seamless Loop Cyan
                )
                val flowBrush = Brush.linearGradient(
                    colors = siriColors,
                    start = Offset(shift - w, centerY),
                    end = Offset(shift + w, centerY),
                    tileMode = TileMode.Repeated
                )
                val softHaloBrush = Brush.linearGradient(
                    colors = siriColors.map { it.copy(alpha = 0.24f * siriGlowBreath) },
                    start = Offset(shift - w, centerY),
                    end = Offset(shift + w, centerY),
                    tileMode = TileMode.Repeated
                )
                val midBloomBrush = Brush.linearGradient(
                    colors = siriColors.map { it.copy(alpha = 0.52f * siriGlowBreath) },
                    start = Offset(shift - w, centerY),
                    end = Offset(shift + w, centerY),
                    tileMode = TileMode.Repeated
                )
                // Feathered ambient light bleed above and below the stretched hairline
                drawRect(
                    brush = Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.32f to Color(0xFF7B61FF).copy(alpha = 0.14f * siriGlowBreath),
                        0.50f to Color(0xFFFF3B9A).copy(alpha = 0.22f * siriGlowBreath),
                        0.68f to Color(0xFF18D4F5).copy(alpha = 0.14f * siriGlowBreath),
                        1.0f to Color.Transparent
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(w, size.height)
                )
                // Soft outer ribbon (9dp)
                drawLine(
                    brush = softHaloBrush,
                    start = Offset(0f, centerY),
                    end = Offset(w, centerY),
                    strokeWidth = 9.dp.toPx(),
                    cap = StrokeCap.Butt
                )
                // Mid chromatic bloom (4.5dp)
                drawLine(
                    brush = midBloomBrush,
                    start = Offset(0f, centerY),
                    end = Offset(w, centerY),
                    strokeWidth = 4.5.dp.toPx(),
                    cap = StrokeCap.Butt
                )
                // Crisp iridescent plasma core (2.2dp)
                drawLine(
                    brush = flowBrush,
                    start = Offset(0f, centerY),
                    end = Offset(w, centerY),
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Butt
                )
                // Razor-thin specular white-hot filament (0.8dp)
                val filamentX = (siriFlowPhase * 1.4f - 0.2f) * w
                drawLine(
                    brush = Brush.horizontalGradient(
                        0.0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.85f * siriGlowBreath),
                        1.0f to Color.Transparent,
                        startX = filamentX - w * 0.22f,
                        endX = filamentX + w * 0.22f
                    ),
                    start = Offset(0f, centerY),
                    end = Offset(w, centerY),
                    strokeWidth = 0.9.dp.toPx(),
                    cap = StrokeCap.Round
                )
            } else {
                // Base 1dp horizontal hairline centered vertically inside the 24dp touch target
                drawLine(
                    color = GraphiteHairline,
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = hairlinePx
                )
            }

            if (!active) return@Canvas

            val horizontalInset = 24.dp.toPx()
            val usableHalf = ((size.width / 2f) - horizontalInset).coerceAtLeast(1f)
            val zeroX = size.width / 2f
            val pxPerPercent = usableHalf / 25f // -25% maps to left inset; 0% sits dead center

            // Draw discrete 5% detent ticks (-25% .. +15%)
            var step = MIN_DENSITY_DELTA
            while (step <= MAX_DENSITY_DELTA) {
                val x = (zeroX + step * pxPerPercent).coerceIn(horizontalInset, size.width - horizontalInset)
                val isZero = step == 0
                val tickHalfH = if (isZero) 4.dp.toPx() else 2.dp.toPx()
                drawLine(
                    color = if (isZero) TelemetryMuted.copy(alpha = 0.75f) else GraphiteHairline,
                    start = Offset(x, centerY - tickHalfH),
                    end = Offset(x, centerY + tickHalfH),
                    strokeWidth = hairlinePx
                )
                step += DENSITY_STEP
            }

            val notchX = (zeroX + displayedDelta * pxPerPercent).coerceIn(
                horizontalInset,
                size.width - horizontalInset
            )
            val signalColor = when {
                isApplying -> EvidenceAmber.copy(alpha = applyingPulse)
                isDragging || displayedDelta != 0 -> accent
                else -> AcidVoltLime
            }

            // Active span fill from 0% center to live notch position
            if (displayedDelta != 0) {
                drawLine(
                    color = signalColor,
                    start = Offset(zeroX, centerY),
                    end = Offset(notchX, centerY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Tactile hardware playhead notch centered at notchX
            val notchWidth = 22.dp.toPx()
            val notchHeight = 10.dp.toPx()
            val notchTopLeft = Offset(notchX - notchWidth / 2f, centerY - notchHeight / 2f)
            val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())

            drawRoundRect(
                color = ConsoleElevated,
                topLeft = notchTopLeft,
                size = Size(notchWidth, notchHeight),
                cornerRadius = corner
            )
            drawRoundRect(
                color = if (isDragging || isApplying || displayedDelta != 0) {
                    signalColor
                } else {
                    StudioCrisp.copy(alpha = 0.72f)
                },
                topLeft = notchTopLeft,
                size = Size(notchWidth, notchHeight),
                cornerRadius = corner,
                style = Stroke(width = if (isDragging || isApplying) 1.5.dp.toPx() else 1.dp.toPx())
            )
            // Center grip pip inside the playhead notch
            drawLine(
                color = if (isDragging || isApplying || displayedDelta != 0) signalColor else StudioCrisp,
                start = Offset(notchX, centerY - 2.5.dp.toPx()),
                end = Offset(notchX, centerY + 2.5.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Live tabular monospace delta readout while dragging, applying, or shifted off 0%
        if (active && (isDragging || isApplying || displayedDelta != 0)) {
            val badgeBorder = if (isApplying) EvidenceAmber else accent
            val badgeTextColor = if (isApplying) EvidenceAmber else if (isDragging) OledVoid else accent
            val badgeBackground = if (isDragging && !isApplying) accent else ConsoleElevated
            val insetPx = with(density) { 24.dp.toPx() }
            val usableHalfPx = ((widthPx / 2f) - insetPx).coerceAtLeast(1f)
            val notchOffsetFromCenterPx = (displayedDelta * (usableHalfPx / 25f))
                .coerceIn(-usableHalfPx + with(density) { 28.dp.toPx() }, usableHalfPx - with(density) { 28.dp.toPx() })
                .roundToInt()

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = notchOffsetFromCenterPx,
                            y = with(density) { (-20).dp.roundToPx() }
                        )
                    }
                    .background(badgeBackground, RoundedCornerShape(4.dp))
                    .border(1.dp, badgeBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("density_scrubber_readout")
            ) {
                Text(
                    text = formattedDelta,
                    style = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontFeatureSettings = "tnum"
                    ),
                    color = badgeTextColor,
                    maxLines = 1
                )
            }
        }
    }
}

private fun snapDensityDelta(rawDelta: Int): Int {
    val clamped = rawDelta.coerceIn(MIN_DENSITY_DELTA, MAX_DENSITY_DELTA)
    val snapped = ((clamped.toFloat() / DENSITY_STEP).roundToInt()) * DENSITY_STEP
    return snapped.coerceIn(MIN_DENSITY_DELTA, MAX_DENSITY_DELTA)
}

private fun formatDensityDelta(deltaPercent: Int): String = when {
    deltaPercent > 0 -> "+${deltaPercent}%"
    else -> "${deltaPercent}%"
}
