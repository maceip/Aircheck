package com.example.ui.clipstudio

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// =============================================================================
// 1. Border Beam (exact Jetpack Compose port of libraries.dev/beam — border-beam@1.4.1)
// =============================================================================

enum class BorderBeamPalette { COLORFUL, MONO, SUNSET, OCEAN }

/**
 * Animated traveling border beam (ported from `libraries.dev/beam` `border-beam@1.4.1`):
 * draws a subtle idle hairline plus a smooth rotating conic comet & blurred bloom around
 * the rounded border of segmented cards (e.g. `01 SPARK` HOOK / DETAIL / SHIFT / PAYOFF).
 */
fun Modifier.borderBeam(
    active: Boolean = true,
    radius: Dp = 16.dp,
    strokeWidth: Dp = 1.25.dp,
    strength: Float = 0.72f,
    durationMs: Int = 2600,
    phaseOffsetDeg: Float = 0f,
    palette: BorderBeamPalette = BorderBeamPalette.COLORFUL,
    idleBorderColor: Color = Color(0xFF26262E)
): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "border_beam")
    val angleDeg by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "border_beam_angle"
    )
    val effectiveStrength by animateFloatAsState(
        targetValue = if (active) strength.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 360, easing = LinearEasing),
        label = "border_beam_fade"
    )

    val stops: Array<Pair<Float, Color>> = remember(palette, effectiveStrength) {
        val a = effectiveStrength
        when (palette) {
            BorderBeamPalette.MONO -> arrayOf(
                0.00f to Color.Transparent,
                0.34f to Color.Transparent,
                0.48f to Color(0xFFB4B4BC).copy(alpha = 0.16f * a),
                0.62f to Color(0xFFEBEBF2).copy(alpha = 0.65f * a),
                0.70f to Color.White.copy(alpha = 0.92f * a),
                0.78f to Color(0xFFB4B4BC).copy(alpha = 0.35f * a),
                0.88f to Color.Transparent,
                1.00f to Color.Transparent
            )
            BorderBeamPalette.SUNSET -> arrayOf(
                0.00f to Color.Transparent,
                0.32f to Color.Transparent,
                0.46f to Color(0xFFFF5032).copy(alpha = 0.25f * a),
                0.58f to Color(0xFFFF8C32).copy(alpha = 0.68f * a),
                0.68f to Color(0xFFFFDC64).copy(alpha = 0.92f * a),
                0.76f to Color(0xFFFF5050).copy(alpha = 0.50f * a),
                0.88f to Color.Transparent,
                1.00f to Color.Transparent
            )
            BorderBeamPalette.OCEAN -> arrayOf(
                0.00f to Color.Transparent,
                0.32f to Color.Transparent,
                0.46f to Color(0xFF3C78FF).copy(alpha = 0.25f * a),
                0.58f to Color(0xFF32C8DC).copy(alpha = 0.72f * a),
                0.68f to Color(0xFFD6EEFF).copy(alpha = 0.92f * a),
                0.76f to Color(0xFF7850FF).copy(alpha = 0.50f * a),
                0.88f to Color.Transparent,
                1.00f to Color.Transparent
            )
            BorderBeamPalette.COLORFUL -> arrayOf(
                // Matches border-beam@1.4.1 colorful dark spectrum + bright white comet core
                0.00f to Color.Transparent,
                0.30f to Color.Transparent,
                0.42f to Color(0xFF288CFF).copy(alpha = 0.26f * a),
                0.52f to Color(0xFF32C850).copy(alpha = 0.45f * a),
                0.60f to Color(0xFFFF7828).copy(alpha = 0.68f * a),
                0.67f to Color(0xFFFF3264).copy(alpha = 0.85f * a),
                0.71f to Color.White.copy(alpha = 0.95f * a),
                0.77f to Color(0xFFB428F0).copy(alpha = 0.60f * a),
                0.85f to Color(0xFF6446FF).copy(alpha = 0.24f * a),
                0.93f to Color.Transparent,
                1.00f to Color.Transparent
            )
        }
    }

    drawWithContent {
        drawContent()
        val rPx = radius.toPx()
        val sw = strokeWidth.toPx()
        val inset = sw / 2f
        val rectSize = Size((size.width - sw).coerceAtLeast(1f), (size.height - sw).coerceAtLeast(1f))
        val corner = CornerRadius(rPx, rPx)

        // Subtle idle 1dp hairline so the segment edge stays defined when the comet is across the card
        drawRoundRect(
            color = idleBorderColor,
            topLeft = Offset(inset, inset),
            size = rectSize,
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx())
        )

        if (effectiveStrength > 0.01f) {
            val totalAngle = (angleDeg + phaseOffsetDeg) % 360f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Soft outer/inner bloom pass (wider translucent stroke) + sharp traveling comet stroke
            val bloomSw = sw * 3.8f
            val bloomStops = stops.map { (pos, c) -> pos to c.copy(alpha = c.alpha * 0.28f) }.toTypedArray()
            val rotatedBrush = rotatedSweepBrush(stops, center, totalAngle)
            val rotatedBloomBrush = rotatedSweepBrush(bloomStops, center, totalAngle)

            drawRoundRect(
                brush = rotatedBloomBrush,
                topLeft = Offset(inset, inset),
                size = rectSize,
                cornerRadius = corner,
                style = Stroke(width = bloomSw, cap = StrokeCap.Round)
            )
            drawRoundRect(
                brush = rotatedBrush,
                topLeft = Offset(inset, inset),
                size = rectSize,
                cornerRadius = corner,
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
        }
    }
}

private fun rotatedSweepBrush(
    stops: Array<Pair<Float, Color>>,
    center: Offset,
    angleDeg: Float
): Brush {
    val shift = ((angleDeg % 360f) + 360f) % 360f / 360f
    // Sample 32 evenly spaced points shifted by `shift` around [0, 1] so Brush.sweepGradient rotates smoothly
    val samples = 36
    val shifted = Array(samples + 1) { idx ->
        val pos = idx.toFloat() / samples.toFloat()
        val src = (pos - shift + 1f) % 1f
        pos to sampleStops(stops, src)
    }
    return Brush.sweepGradient(colorStops = shifted, center = center)
}

private fun sampleStops(stops: Array<Pair<Float, Color>>, pos: Float): Color {
    if (stops.isEmpty()) return Color.Transparent
    if (pos <= stops.first().first) return stops.first().second
    if (pos >= stops.last().first) return stops.last().second
    for (i in 0 until stops.lastIndex) {
        val (p0, c0) = stops[i]
        val (p1, c1) = stops[i + 1]
        if (pos in p0..p1) {
            val span = (p1 - p0).coerceAtLeast(1e-5f)
            val t = ((pos - p0) / span).coerceIn(0f, 1f)
            return Color(
                red = c0.red + (c1.red - c0.red) * t,
                green = c0.green + (c1.green - c0.green) * t,
                blue = c0.blue + (c1.blue - c0.blue) * t,
                alpha = c0.alpha + (c1.alpha - c0.alpha) * t
            )
        }
    }
    return stops.last().second
}

/**
 * Flat, unfilled (outlined) sparkle icon (`Icons.Outlined.AutoAwesome`) with NO border, NO box,
 * and NO elevation — masked with a smooth traveling iridescent rainbow metallic shimmer directly
 * onto the icon strokes themselves (`CompositingStrategy.Offscreen` + `BlendMode.SrcIn`).
 */
@Composable
fun ShimmerSparkleIcon(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 22.dp,
    phaseOffsetDeg: Float = 0f,
    durationMs: Int = 2600
) {
    if (!enabled) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = contentDescription,
            tint = Cs.Dim,
            modifier = modifier.size(size)
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "sparkle_rainbow_shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sparkle_shimmer_phase"
    )
    val colors = remember {
        listOf(
            Color(0xFF4CC9FF),
            Color(0xFF4CE89A),
            Color(0xFFFFCF4A),
            Color(0xFFFF5C8A),
            Color(0xFFFFFFFF),
            Color(0xFFC56CFF),
            Color(0xFF4CC9FF)
        )
    }
    val offsetFraction = ((phase + phaseOffsetDeg / 360f) % 1f + 1f) % 1f

    Icon(
        imageVector = Icons.Outlined.AutoAwesome,
        contentDescription = contentDescription,
        tint = Color.White,
        modifier = modifier
            .size(size)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val span = max(this.size.width, this.size.height) * 2.2f
                val shift = offsetFraction * span
                val brush = Brush.linearGradient(
                    colors = colors,
                    start = Offset(-span + shift, -span * 0.45f + shift * 0.65f),
                    end = Offset(shift, span * 0.55f + shift * 0.65f),
                    tileMode = TileMode.Repeated
                )
                drawRect(brush = brush, blendMode = BlendMode.SrcIn)
            }
    )
}

// =============================================================================
// 2. Thinking Orb "Solving" (exact 1:1 math port of libraries.dev/orbs — thinking-orbs@0.3.2 frameRubik)
// =============================================================================

private data class RubikMove(val axis: Int, val lo: Float, val hi: Float, val ang: Float)
private data class OrbDot(val x: Float, val y: Float, val z: Float, val r: Float, val white: Float)

private fun orbHashD(a: Float, b: Float): Float {
    val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
    return (h - floor(h)).toFloat()
}

private fun makeRubikMoves(count: Int): List<RubikMove> = List(count) { i ->
    val fi = i.toFloat()
    val axis = min(2, floor(orbHashD(fi, 2.3f) * 3f).toInt())
    val lo = -1f + 0.5f * min(3, floor(orbHashD(fi, 5.9f) * 4f).toInt())
    val dir = if (orbHashD(fi, 7.7f) < 0.5f) 1f else -1f
    RubikMove(axis = axis, lo = lo, hi = lo + 0.5f, ang = dir * (PI.toFloat() / 2f))
}

/**
 * Exact Jetpack Compose port of `libraries.dev/orbs` `state="solving"` (`frameRubik` in `thinking-orbs@0.3.2`):
 * 3D dotted sphere whose latitude/longitude bands scramble across X/Y/Z slices and click back solved.
 * Used as the universal loader for text, image, voice, and video processing across Aircheck.
 */
@Composable
fun ThinkingOrbSolving(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Cs.Ink,
    speedMultiplier: Float = 1f,
    paused: Boolean = false
) {
    // Choose exact thinking-orbs@0.3.2 PRESETS.rubik tuning for inline (20), medium (32), or hero (64) scale
    val preset = remember(size) {
        when {
            size.value <= 24f -> Triple(1.95f, 0.088f, 1.90f) // 20px preset
            size.value <= 42f -> Triple(1.8964f, 0.1537f, 1.4951f) // 32px preset
            else -> Triple(1.82f, 0.35f, 1.05f) // 64px preset
        }
    }
    val (presetSpeed, countScale, sizeScale) = preset
    val rt = remember(countScale) { sqrt(countScale) }
    val latRings = remember(rt) { max(4, (15f * rt).roundToInt()) }
    val lonDensity = remember(rt) { max(8, (40f * rt).roundToInt()) }
    val rBase = 0.6f * sizeScale
    val rDepth = 1.7f * sizeScale
    val rActive = 0.3f * sizeScale
    val moves = remember { makeRubikMoves(14) }
    val amounts = remember { FloatArray(14) }

    var elapsedSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(paused, presetSpeed, speedMultiplier) {
        if (paused) return@LaunchedEffect
        var startNanos = -1L
        while (true) {
            withFrameNanos { nanos ->
                if (startNanos < 0L) startNanos = nanos
                elapsedSec = ((nanos - startNanos) / 1_000_000_000.0).toFloat() * presetSpeed * speedMultiplier
            }
        }
    }

    Canvas(
        modifier = modifier
            .size(size)
            .testTag("cs_solving_orb")
    ) {
        val sz = min(this.size.width, this.size.height)
        if (sz <= 1f) return@Canvas
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val radius = sz / 2f * 0.82f
        val t = elapsedSec

        // Exact solveCycle(t, moveCount=14, slotDur=0.42, rest=1.2)
        val count = moves.size
        val slotDur = 0.42f
        val rest = 1.2f
        val cyc = 2f * count * slotDur + rest
        val tc = t % cyc
        amounts.fill(0f)
        var activeMove = -1
        if (tc < 2f * count * slotDur) {
            val slot = floor(tc / slotDur).toInt()
            val p = (tc - slot * slotDur) / slotDur
            val cl = min(1f, p / 0.7f)
            val ep = 1f - (1f - cl).pow(3)
            if (slot < count) {
                for (i in 0 until slot) amounts[i] = 1f
                amounts[slot] = ep
                activeMove = slot
            } else {
                val u = 2 * count - 1 - slot
                for (i in 0 until u) amounts[i] = 1f
                amounts[u] = 1f - ep
                activeMove = u
            }
        }

        // Exact camera projection makeProj(yaw = t * 0.55, tilt = 0.35 + 0.1 * sin(t * 0.9))
        val yaw = t * 0.55f
        val tilt = 0.35f + 0.1f * sin(t * 0.9f)
        val st = sin(tilt)
        val ct = cos(tilt)
        val sy = sin(yaw)
        val cyw = cos(yaw)

        // Exact radiusScale(size, rsPow = 0.6) normalized to physical dp-equivalent pixels
        val logicalSize = size.value.coerceAtLeast(16f)
        val rs = (logicalSize / 300f).pow(0.6f) * (sz / logicalSize)

        val dots = ArrayList<OrbDot>((latRings + 1) * lonDensity)
        for (li in 0..latRings) {
            val lat = -PI.toFloat() / 2f + (li.toFloat() / latRings.toFloat()) * PI.toFloat()
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
            for (lj in 0 until lonCount) {
                val lon = (lj.toFloat() / lonCount.toFloat()) * 2f * PI.toFloat()
                var x = cosLat * cos(lon)
                var y = sinLat
                var z = cosLat * sin(lon)
                var inActive = false

                for (mi in 0 until count) {
                    val amt = amounts[mi]
                    if (amt <= 0f) continue
                    val mv = moves[mi]
                    val coord = when (mv.axis) {
                        0 -> x
                        1 -> y
                        else -> z
                    }
                    if (coord < mv.lo || coord >= mv.hi) continue
                    if (mi == activeMove) inActive = true
                    val a = mv.ang * amt
                    val ca = cos(a)
                    val sa = sin(a)
                    when (mv.axis) {
                        0 -> {
                            val y2 = y * ca - z * sa
                            z = y * sa + z * ca
                            y = y2
                        }
                        1 -> {
                            val x2 = x * ca + z * sa
                            z = -x * sa + z * ca
                            x = x2
                        }
                        else -> {
                            val x2 = x * ca - y * sa
                            y = x * sa + y * ca
                            x = x2
                        }
                    }
                }

                val x1 = x * cyw + z * sy
                val z1 = -x * sy + z * cyw
                val y1 = y * ct - z1 * st
                val z2 = y * st + z1 * ct
                val depth = (z2 + 1f) / 2f
                val dotR = max(0.6f, (rBase + rDepth * depth + if (inActive) rActive else 0f) * rs)
                val w = (0.62f - 0.54f * depth - if (inActive) 0.14f else 0f).coerceIn(0f, 1f)
                dots.add(OrbDot(cx + x1 * radius, cy - y1 * radius, z2, dotR, w))
            }
        }
        dots.sortBy { it.z }
        for (dot in dots) {
            val lum = (1f - dot.white).coerceIn(0.18f, 1f)
            drawCircle(
                color = Color(
                    red = tint.red * lum,
                    green = tint.green * lum,
                    blue = tint.blue * lum,
                    alpha = (0.35f + 0.65f * lum).coerceIn(0f, 1f)
                ),
                radius = dot.r,
                center = Offset(dot.x, dot.y)
            )
        }
    }
}

// =============================================================================
// 3. Voice Glow Dither (exact 1:1 port of libraries.dev/voice — voice-glow@0.5.0 look="dither")
// =============================================================================

private val BAYER_4X4 = floatArrayOf(
    0f, 8f, 2f, 10f,
    12f, 4f, 14f, 6f,
    3f, 11f, 1f, 9f,
    15f, 7f, 13f, 5f
).map { (it + 0.5f) / 16f }.toFloatArray()

private data class VoiceLobe(val xOff: Float, val w: Float, val h: Float, val band: Int)

private val VOICE_LOBES = arrayOf(
    VoiceLobe(0f, 74f, 46f, 0),
    VoiceLobe(-36f, 54f, 40f, 1),
    VoiceLobe(36f, 54f, 40f, 1),
    VoiceLobe(-72f, 48f, 32f, 2),
    VoiceLobe(72f, 48f, 32f, 2),
    VoiceLobe(-108f, 42f, 26f, 1),
    VoiceLobe(108f, 42f, 26f, 1)
)

// Exact voice-glow@0.5.0 dark "colorful" 7-lobe palette
private val VOICE_PALETTE_DARK = arrayOf(
    floatArrayOf(255f / 255f, 70f / 255f, 120f / 255f),
    floatArrayOf(60f / 255f, 190f / 255f, 255f / 255f),
    floatArrayOf(175f / 255f, 70f / 255f, 255f / 255f),
    floatArrayOf(60f / 255f, 220f / 255f, 130f / 255f),
    floatArrayOf(255f / 255f, 150f / 255f, 40f / 255f),
    floatArrayOf(90f / 255f, 100f / 255f, 255f / 255f),
    floatArrayOf(40f / 255f, 200f / 255f, 190f / 255f)
)

/**
 * Exact Jetpack Compose port of `libraries.dev/voice` (`voice-glow@0.5.0`, `type="mobile"`, `look="dither"`):
 * rises from the bottom edge of the screen whenever any microphone is active, driven by real live
 * microphone RMS [level] (0..1) with 4x4 ordered Bayer dithering, 5-level per-channel color quantization,
 * dome curvature, sideways lobe flow, and soft pixel-glow bloom beneath the active dither pixels.
 */
@Composable
fun VoiceGlowDitherOverlay(
    active: Boolean,
    level: Float,
    modifier: Modifier = Modifier
) {
    val fade by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = if (active) 320 else 420, easing = LinearEasing),
        label = "voice_dither_fade"
    )
    if (fade <= 0.01f) return

    var clockSec by remember { mutableFloatStateOf(0f) }
    var smoothedLevel by remember { mutableFloatStateOf(0.23f) }
    LaunchedEffect(active) {
        var lastNanos = -1L
        while (true) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos < 0L) 0.016f else ((nanos - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                lastNanos = nanos
                clockSec += dt
                // Real live mic level with voice-glow@0.5.0 idle presence (0.23) + attack/release smoothing
                val boosted = (level * 3.1f).coerceIn(0f, 1f)
                val target = max(0.23f, boosted)
                val rate = if (target > smoothedLevel) 1f / 0.14f else 1f / 0.55f
                smoothedLevel += (target - smoothedLevel) * (1f - exp(-dt * rate))
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("cs_recording_border")) {
        Canvas(Modifier.fillMaxSize()) {
            val cw = size.width
            val ch = size.height
            if (cw <= 1f || ch <= 1f) return@Canvas

            val densityScale = (cw / 390f).coerceIn(0.75f, 1.8f)
            val t = clockSec
            val breathe = 0.06f * sin(t * (2f * PI.toFloat() / 5.2f))
            val lvl = (smoothedLevel + breathe).coerceIn(0.16f, 1f)

            // 3 frequency bands driven from live smoothed RMS (matches voice-glow@0.5.0 band synthesis)
            val b0 = lvl
            val b1 = lvl * (0.72f + 0.28f * sin(t * 9.1f))
            val b2 = lvl * (0.60f + 0.40f * sin(t * 13.7f + 2f))
            val bands = floatArrayOf(b0, b1, b2)

            // Mobile preset geometry
            val reach = 1f + 2.15f * lvl
            val spread = 1f + 0.45f * lvl
            val flowOffset = (t * 48f * (0.35f + 0.65f * lvl)) % (36f * VOICE_LOBES.size)
            val halfRing = 36f * VOICE_LOBES.size * 0.5f

            val lobeX = FloatArray(VOICE_LOBES.size)
            val lobeInvW = FloatArray(VOICE_LOBES.size)
            val lobeInvH = FloatArray(VOICE_LOBES.size)
            val lobeLvl = FloatArray(VOICE_LOBES.size)
            for (i in VOICE_LOBES.indices) {
                val lb = VOICE_LOBES[i]
                var wrappedX = ((lb.xOff + flowOffset + halfRing) % (halfRing * 2f))
                if (wrappedX < 0f) wrappedX += halfRing * 2f
                wrappedX -= halfRing
                lobeX[i] = cw * 0.5f + wrappedX * spread * densityScale * 1.15f
                val rw = max(12f, lb.w * 1.15f * spread * densityScale)
                val bandL = bands[lb.band].coerceAtLeast(0.12f)
                val rh = max(14f, lb.h * 1.65f * reach * bandL * densityScale)
                lobeInvW[i] = 1f / rw
                lobeInvH[i] = 1f / rh
                lobeLvl[i] = bandL
            }

            // Soft pixel-glow ambient halo behind the dither field (voice-glow `pixelGlow`)
            val haloHeight = min(ch * 0.40f, (135f * reach) * densityScale)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFFFF4678).copy(alpha = 0.08f * fade * lvl),
                        Color(0xFF3CBEFF).copy(alpha = 0.16f * fade * lvl),
                        Color(0xFFAF46FF).copy(alpha = 0.24f * fade * lvl)
                    ),
                    startY = ch - haloHeight,
                    endY = ch
                ),
                topLeft = Offset(0f, ch - haloHeight),
                size = Size(cw, haloHeight)
            )

            // 4x4 Ordered Bayer Dither Grid (exact voice-glow@0.5.0 look="dither" pixel cell engine)
            val cellPitch = (4.8.dp.toPx()).coerceAtLeast(6f)
            val dotSize = max(2f, cellPitch - max(1.5f, cellPitch * 0.22f))
            val faintDotSize = dotSize * 0.42f
            val cols = ceil(cw / cellPitch).toInt()
            val maxFieldH = min(ch * 0.44f, (168f * reach) * densityScale)
            val rows = ceil(maxFieldH / cellPitch).toInt()
            val xStart = (cw - cols * cellPitch) * 0.5f

            val rangeRx = max(1f, 215f * spread * densityScale)
            val rangeRy = max(1f, maxFieldH * 0.96f)
            val domeFactor = 0.6f * 2.2f // mobile pixelDome=0.6
            val invHalfW = 2f / max(1f, cw)
            val quantLevels = 5f
            val quantMax = quantLevels - 1f
            val softness = 0.75f

            for (row in 0 until rows) {
                val py = ch - (row + 0.5f) * cellPitch
                val dyBottom = py - ch
                for (col in 0 until cols) {
                    val px = xStart + (col + 0.5f) * cellPitch
                    val nx = (px - cw * 0.5f) * invHalfW
                    val dome = 1f + domeFactor * nx * nx

                    // Elliptical range mask
                    val rx = (px - cw * 0.5f) / rangeRx
                    val ry = (dyBottom / rangeRy) * dome
                    val dMask = sqrt(rx * rx + ry * ry)
                    if (dMask >= 1f) continue
                    val maskGain = if (dMask < 0.45f) {
                        1f - 0.5f * (dMask / 0.45f)
                    } else {
                        0.5f * (1f - (dMask - 0.45f) / 0.55f)
                    }

                    // Evaluate 7-lobe field + weighted RGB color blend
                    var prod = 1f
                    var wSum = 0f
                    var rAcc = 0f
                    var gAcc = 0f
                    var bAcc = 0f
                    for (i in VOICE_LOBES.indices) {
                        val lx = (px - lobeX[i]) * lobeInvW[i]
                        val ly = dyBottom * lobeInvH[i] * dome
                        val sq = lx * lx + ly * ly
                        val dist = sqrt(sq) / softness
                        val b = if (dist < 1f) 1f - dist else 0f
                        prod *= (1f - b * 0.92f)
                        val wCol = exp(-sq / (0.62f * 0.62f)) * lobeLvl[i]
                        if (wCol > 1e-4f) {
                            val rgb = VOICE_PALETTE_DARK[i]
                            wSum += wCol
                            rAcc += rgb[0] * wCol
                            gAcc += rgb[1] * wCol
                            bAcc += rgb[2] * wCol
                        }
                    }

                    val rawX = (1f - prod) * maskGain * min(1f, (0.45f + 0.75f * lvl))
                    if (rawX <= 0.005f || wSum <= 1e-5f) continue

                    val rBright = min(1f, rawX.pow(0.65f) * 1.18f)
                    val bayer = BAYER_4X4[(row and 3) * 4 + (col and 3)]
                    val isLit = rBright >= bayer * 0.92f + 0.03f
                    if (!isLit && rBright < 0.07f) continue

                    // Saturation boost (aa = 1.35) + white-hot core lift + 5-level Bayer color quantization
                    val nr = rAcc / wSum
                    val ng = gAcc / wSum
                    val nb = bAcc / wSum
                    val lum = 0.2126f * nr + 0.7152f * ng + 0.0722f * nb
                    val sr = (lum + (nr - lum) * 1.35f).coerceIn(0f, 1f)
                    val sg = (lum + (ng - lum) * 1.35f).coerceIn(0f, 1f)
                    val sb = (lum + (nb - lum) * 1.35f).coerceIn(0f, 1f)
                    val hot = (max(0f, rBright - 0.7f) / 0.3f) * 0.6f

                    val qr = min(quantMax, floor((sr + (1f - sr) * hot) * quantMax + bayer)) / quantMax
                    val qg = min(quantMax, floor((sg + (1f - sg) * hot) * quantMax + bayer)) / quantMax
                    val qb = min(quantMax, floor((sb + (1f - sb) * hot) * quantMax + bayer)) / quantMax

                    val sz = if (isLit) dotSize else faintDotSize
                    val alpha = (if (isLit) 0.96f else 0.16f) * fade
                    drawRect(
                        color = Color(qr, qg, qb, alpha),
                        topLeft = Offset(px - sz / 2f, py - sz / 2f),
                        size = Size(sz, sz)
                    )
                }
            }
        }
    }
}

// =============================================================================
// 4. Soft-Neumorphic Extruded Dock Buttons (matching Ryan's uploaded reference)
// =============================================================================

/**
 * Etched outer hairline tray frame + true Gaussian-blurred dual-shadow soft-neumorphic extruded key
 * matching Ryan's uploaded dark-mode button reference (outer recessed hairline ring + soft top-left
 * light bloom + soft bottom-right dark drop shadow + satin matte pillow surface; collapses cleanly
 * into a soft pressed state when touched).
 */
fun Modifier.neumorphicDockKey(
    role: KeyRole,
    pressed: Boolean = false,
    radius: Dp = 18.dp,
    enabled: Boolean = true,
    outerFrame: Boolean = true
): Modifier = drawBehind {
    val rPx = radius.toPx()
    val inset = if (outerFrame) 4.dp.toPx() else 0f
    val innerR = (rPx - 3.dp.toPx()).coerceAtLeast(8.dp.toPx())
    val rect = RectF(inset, inset, size.width - inset, size.height - inset)

    // Choose cohesive matte satin surface colors (NO harsh flat white slabs!)
    val (fillColor, lightBloomColor, darkShadowColor) = when (role) {
        KeyRole.VOICE -> Triple(
            Color(0xFFCC3F1E),
            Color(0xFFFF8F6E).copy(alpha = if (enabled) 0.28f else 0.08f),
            Color(0xFF000000).copy(alpha = if (enabled) 0.72f else 0.35f)
        )
        KeyRole.REPAIR -> Triple(
            Color(0xFF342712),
            Color(0xFFFFB800).copy(alpha = if (enabled) 0.20f else 0.06f),
            Color(0xFF000000).copy(alpha = if (enabled) 0.72f else 0.35f)
        )
        KeyRole.COMMIT -> Triple(
            Color(0xFF252831),
            Color(0xFF6E778C).copy(alpha = if (enabled) 0.22f else 0.06f),
            Color(0xFF000000).copy(alpha = if (enabled) 0.78f else 0.35f)
        )
        else -> Triple(
            Color(0xFF1E2027),
            Color(0xFF565C6E).copy(alpha = if (enabled) 0.16f else 0.05f),
            Color(0xFF000000).copy(alpha = if (enabled) 0.72f else 0.35f)
        )
    }

    val effectiveFill = if (enabled) {
        if (pressed) Color(
            red = (fillColor.red * 0.88f).coerceIn(0f, 1f),
            green = (fillColor.green * 0.88f).coerceIn(0f, 1f),
            blue = (fillColor.blue * 0.88f).coerceIn(0f, 1f),
            alpha = 1f
        ) else fillColor
    } else {
        fillColor.copy(alpha = 0.55f)
    }

    // 1. Etched outer hairline bezel ring (left-side style in Ryan's dark neumorphic reference)
    if (outerFrame) {
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.56f),
            topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
            size = Size((size.width - 1.dp.toPx()).coerceAtLeast(1f), (size.height - 1.dp.toPx()).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(rPx + 2.dp.toPx(), rPx + 2.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.09f),
            topLeft = Offset(1.25.dp.toPx(), 1.25.dp.toPx()),
            size = Size((size.width - 2.5.dp.toPx()).coerceAtLeast(1f), (size.height - 2.5.dp.toPx()).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(rPx + 1.5.dp.toPx(), rPx + 1.5.dp.toPx()),
            style = Stroke(width = 0.85.dp.toPx())
        )
    }

    // 2. Soft Gaussian-blurred dual-shadow neumorphic extrusion via native Canvas BlurMaskFilter
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        if (!pressed) {
            val blurLight = 8.dp.toPx()
            val lightPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = lightBloomColor.toArgb()
                maskFilter = BlurMaskFilter(blurLight, BlurMaskFilter.Blur.NORMAL)
            }
            val lightRect = RectF(
                rect.left - 3.dp.toPx(),
                rect.top - 3.dp.toPx(),
                rect.right - 2.dp.toPx(),
                rect.bottom - 2.dp.toPx()
            )
            nc.drawRoundRect(lightRect, innerR, innerR, lightPaint)

            val blurDark = 10.dp.toPx()
            val darkPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = darkShadowColor.toArgb()
                maskFilter = BlurMaskFilter(blurDark, BlurMaskFilter.Blur.NORMAL)
            }
            val darkRect = RectF(
                rect.left + 3.5.dp.toPx(),
                rect.top + 4.dp.toPx(),
                rect.right + 3.5.dp.toPx(),
                rect.bottom + 4.dp.toPx()
            )
            nc.drawRoundRect(darkRect, innerR, innerR, darkPaint)
        }

        // 3. Smooth pillow face fill
        val bodyPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = effectiveFill.toArgb()
        }
        nc.drawRoundRect(rect, innerR, innerR, bodyPaint)
    }

    // 4. Subtle sculpted top-left satin rim / inset shadow when pressed
    val innerCorner = CornerRadius(innerR, innerR)
    val innerTopLeft = Offset(rect.left, rect.top)
    val innerSize = Size(rect.width(), rect.height())
    if (pressed) {
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.55f),
            topLeft = innerTopLeft,
            size = innerSize,
            cornerRadius = innerCorner,
            style = Stroke(width = 1.5.dp.toPx())
        )
    } else {
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (role == KeyRole.VOICE) 0.22f else 0.14f),
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.30f)
                ),
                start = innerTopLeft,
                end = Offset(rect.right, rect.bottom)
            ),
            topLeft = innerTopLeft,
            size = innerSize,
            cornerRadius = innerCorner,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

// =============================================================================
// 5. Silver-Bezel Recessed-OLED Hardware Video-Edit Controls (OP-1 / Tactile Deck)
//    Used exclusively on buttons that directly edit or modify video.
// =============================================================================

object HwLed {
    val Orange = Color(0xFFFF7A14)
    val Blue = Color(0xFF1A8CFF)
    val Green = Color(0xFF12C95B)
    val Red = Color(0xFFE52424)
    val Amber = Color(0xFFF0A500)
}

/**
 * Draws a recessed spherical 3D hardware LED jewel onto a light-silver faceplate:
 * - When [lit] is true: real colored radial light bloom on the surrounding aluminum + glossy 3D colored dome + specular glint.
 * - When [lit] is false: dark anodized recessed LED socket + unlit glass lens (or faint [dimTint] preview when supplied).
 */
internal fun DrawScope.drawHardwareLedJewel(
    center: Offset,
    radius: Float,
    color: Color,
    lit: Boolean,
    dimTint: Color? = null
) {
    // 1. Subtle recessed socket ring cut into the silver housing
    drawCircle(
        color = Color(0xFF707580).copy(alpha = 0.72f),
        radius = radius * 1.18f,
        center = Offset(center.x, center.y - radius * 0.10f)
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.65f),
        radius = radius * 1.16f,
        center = Offset(center.x, center.y + radius * 0.14f)
    )

    if (lit) {
        // Real colored light halo spilled onto the brushed-silver housing around the LED
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = 0.58f),
                    color.copy(alpha = 0.18f),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 2.55f
            ),
            radius = radius * 2.55f,
            center = center
        )
        // Saturated 3D refractive jewel body
        val darkRim = Color(
            red = (color.red * 0.48f).coerceIn(0f, 1f),
            green = (color.green * 0.48f).coerceIn(0f, 1f),
            blue = (color.blue * 0.48f).coerceIn(0f, 1f),
            alpha = 1f
        )
        val hotCore = Color(
            red = (color.red + (1f - color.red) * 0.42f).coerceIn(0f, 1f),
            green = (color.green + (1f - color.green) * 0.42f).coerceIn(0f, 1f),
            blue = (color.blue + (1f - color.blue) * 0.42f).coerceIn(0f, 1f),
            alpha = 1f
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(hotCore, color, darkRim),
                center = Offset(center.x - radius * 0.22f, center.y - radius * 0.22f),
                radius = radius * 1.18f
            ),
            radius = radius,
            center = center
        )
        // Specular glass pinpoint
        drawCircle(
            color = Color.White.copy(alpha = 0.90f),
            radius = radius * 0.30f,
            center = Offset(center.x - radius * 0.28f, center.y - radius * 0.28f)
        )
    } else {
        // Physical unlit dark lens sitting inside the recessed socket
        val base = dimTint ?: Color(0xFF474C56)
        val centerCol = if (dimTint != null) {
            Color(
                red = base.red * 0.55f + 0.18f,
                green = base.green * 0.55f + 0.18f,
                blue = base.blue * 0.55f + 0.18f,
                alpha = 1f
            )
        } else {
            Color(0xFF565B66)
        }
        val edgeCol = if (dimTint != null) {
            Color(base.red * 0.30f, base.green * 0.30f, base.blue * 0.30f, 1f)
        } else {
            Color(0xFF2B2E36)
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(centerCol, edgeCol),
                center = Offset(center.x - radius * 0.2f, center.y - radius * 0.2f),
                radius = radius * 1.15f
            ),
            radius = radius,
            center = center
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.24f),
            radius = radius * 0.24f,
            center = Offset(center.x - radius * 0.28f, center.y - radius * 0.28f)
        )
    }
}

/**
 * Draws the sculpted 3D molded light-silver / off-white hardware shell (used for console bars,
 * individual OLED keycaps, and unified hardware strips).
 */
private fun DrawScope.drawMoldedSilverShell(
    radiusPx: Float,
    pressed: Boolean = false,
    enabled: Boolean = true
) {
    val corner = CornerRadius(radiusPx, radiusPx)
    val skirtPx = if (pressed) 0.75.dp.toPx() else 2.25.dp.toPx()
    val faceH = (size.height - skirtPx).coerceAtLeast(2f)

    // Outer ambient drop shadow onto the dark chassis
    if (!pressed) {
        drawRoundRect(
            color = Color.Black.copy(alpha = if (enabled) 0.58f else 0.28f),
            topLeft = Offset(0f, 2.5.dp.toPx()),
            size = size,
            cornerRadius = corner
        )
    }

    // Lower 3D beveled skirt rim
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFAEB4BE), Color(0xFF838994)),
            startY = faceH * 0.55f,
            endY = size.height
        ),
        topLeft = Offset(0f, 0.75.dp.toPx()),
        size = Size(size.width, (size.height - 0.75.dp.toPx()).coerceAtLeast(1f)),
        cornerRadius = corner
    )

    // Main sculpted matte light-silver faceplate body
    val topFace = if (pressed) Color(0xFFD5D8DF) else Color(0xFFF1F3F6)
    val midFace = if (pressed) Color(0xFFC5C9D2) else Color(0xFFDCDFE5)
    val botFace = if (pressed) Color(0xFFB4B9C3) else Color(0xFFC4C9D2)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                if (enabled) topFace else topFace.copy(alpha = 0.55f),
                if (enabled) midFace else midFace.copy(alpha = 0.55f),
                if (enabled) botFace else botFace.copy(alpha = 0.55f)
            ),
            startY = 0f,
            endY = faceH
        ),
        topLeft = Offset.Zero,
        size = Size(size.width, faceH),
        cornerRadius = corner
    )

    // Crisp upper specular highlight + subtle inner bevel contour
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (pressed) 0.35f else 0.92f),
                Color.White.copy(alpha = 0.14f),
                Color(0xFF7B818C).copy(alpha = 0.45f)
            ),
            startY = 0f,
            endY = faceH
        ),
        topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
        size = Size((size.width - 1.dp.toPx()).coerceAtLeast(1f), (faceH - 1.dp.toPx()).coerceAtLeast(1f)),
        cornerRadius = CornerRadius((radiusPx - 0.5.dp.toPx()).coerceAtLeast(1f)),
        style = Stroke(width = 1.dp.toPx())
    )
}

/**
 * Draws a deeply recessed glossy pure-black OLED screen window inlaid inside a silver housing.
 */
private fun DrawScope.drawRecessedOledWindow(
    topLeft: Offset,
    winSize: Size,
    radiusPx: Float,
    activeSurface: Boolean = true
) {
    val corner = CornerRadius(radiusPx, radiusPx)
    // Recessed chamfer border on silver bezel around the cutout (shadow top, bright catch bottom)
    drawRoundRect(
        color = Color(0xFF838894).copy(alpha = 0.75f),
        topLeft = Offset(topLeft.x - 0.75.dp.toPx(), topLeft.y - 0.9.dp.toPx()),
        size = Size(winSize.width + 1.5.dp.toPx(), winSize.height + 1.5.dp.toPx()),
        cornerRadius = CornerRadius(radiusPx + 0.75.dp.toPx()),
        style = Stroke(width = 1.dp.toPx())
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.72f),
        topLeft = Offset(topLeft.x - 0.5.dp.toPx(), topLeft.y + 0.6.dp.toPx()),
        size = Size(winSize.width + 1.dp.toPx(), winSize.height + 0.8.dp.toPx()),
        cornerRadius = CornerRadius(radiusPx + 0.5.dp.toPx()),
        style = Stroke(width = 0.9.dp.toPx())
    )

    // Glossy jet-black OLED display cavity
    val topCol = if (activeSurface) Color(0xFF151820) else Color(0xFF262A33)
    val botCol = if (activeSurface) Color(0xFF040507) else Color(0xFF13151B)
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(topCol, botCol),
            startY = topLeft.y,
            endY = topLeft.y + winSize.height
        ),
        topLeft = topLeft,
        size = winSize,
        cornerRadius = corner
    )
    // Subtle crisp inner glass stroke
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.85f),
        topLeft = topLeft,
        size = winSize,
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx())
    )
}

/**
 * OP-1 / Industrial Hardware Console Bar (exact match to Ryan's 2nd reference):
 * a machined brushed-silver faceplate bar housing a horizontal row of [VideoEditConsoleModule]
 * controls (recessed black OLED icon tile above + 3D tactile sculpted light-silver pill keycap +
 * centered recessed glowing functional LED dot directly underneath).
 *
 * Fits all 5 direct video-editing modules edge-to-edge on standard phone widths while remaining
 * horizontally scrollable for narrow windows and E2E `performScrollTo()` compatibility.
 */
@Composable
fun VideoEditConsoleBar(
    modifier: Modifier = Modifier,
    moduleCount: Int = 5,
    content: @Composable RowScope.(moduleWidth: Dp) -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawMoldedSilverShell(radiusPx = 18.dp.toPx(), pressed = false, enabled = true)
            }
            .clip(RoundedCornerShape(18.dp))
            .padding(start = 7.dp, top = 7.dp, end = 7.dp, bottom = 7.dp)
    ) {
        val gap = 6.dp
        val count = moduleCount.coerceAtLeast(1)
        val computedW = ((maxWidth - gap * (count - 1)) / count).coerceAtLeast(64.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content(computedW)
        }
    }
}

/**
 * Single vertical 3-tier video-editing hardware module inside [VideoEditConsoleBar]:
 * 1. Top: recessed glossy pure-black OLED icon screen (`icon` in pure crisp white)
 * 2. Middle: raised 3D sculpted light-silver tactile pill keycap with subtle engraved label
 * 3. Bottom: centered recessed color-coded glowing LED jewel (real live state; dim unlit socket when off)
 */
@Composable
fun VideoEditConsoleModule(
    label: String,
    icon: ImageVector,
    active: Boolean,
    ledColor: Color,
    onClick: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDesc: String = label
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val isPressed = pressed && enabled

    Column(
        modifier = modifier
            .width(width)
            .semantics(mergeDescendants = true) {
                contentDescription = contentDesc
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 1. Top recessed glossy jet-black OLED screen tile with crisp white icon
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(29.dp)
                .drawBehind {
                    drawRecessedOledWindow(
                        topLeft = Offset.Zero,
                        winSize = size,
                        radiusPx = 8.dp.toPx(),
                        activeSurface = true
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp)
            )
        }

        // 2. Middle raised 3D sculpted light-silver tactile pill keycap (depresses on touch)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(23.dp)
                .offset(y = if (isPressed) 1.dp else 0.dp)
                .drawBehind {
                    val r = size.height / 2f
                    val corner = CornerRadius(r, r)
                    if (!isPressed) {
                        // Soft downward shadow onto the brushed-silver faceplate
                        drawRoundRect(
                            color = Color(0xFF424752).copy(alpha = 0.42f),
                            topLeft = Offset(0f, 1.8.dp.toPx()),
                            size = size,
                            cornerRadius = corner
                        )
                    }
                    // Molded convex light-grey keycap fill
                    val topCol = if (isPressed) Color(0xFFC4C8D0) else Color(0xFFF3F5F8)
                    val botCol = if (isPressed) Color(0xFFD5D8DF) else Color(0xFFCCD0D8)
                    drawRoundRect(
                        brush = Brush.verticalGradient(colors = listOf(topCol, botCol)),
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = corner
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (isPressed) 0.20f else 0.92f),
                                Color(0xFF7D838E).copy(alpha = 0.55f)
                            )
                        ),
                        topLeft = Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
                        size = Size((size.width - 1.dp.toPx()).coerceAtLeast(1f), (size.height - 1.dp.toPx()).coerceAtLeast(1f)),
                        cornerRadius = corner,
                        style = Stroke(width = 0.9.dp.toPx())
                    )
                }
                .padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = mono(
                    size = 9.2.sp,
                    color = if (enabled) Color(0xFF1F222A) else Color(0xFF6B707B),
                    weight = FontWeight.SemiBold
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }

        // 3. Bottom centered recessed color-coded hardware LED jewel
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(11.dp)
        ) {
            drawHardwareLedJewel(
                center = Offset(size.width / 2f, size.height / 2f),
                radius = 3.4.dp.toPx(),
                color = ledColor,
                lit = active && enabled
            )
        }
    }
}

/**
 * Individual Molded Silver-White Recessed-OLED Hardware Keycap (exact match to Ryan's 1st reference):
 * thick sculpted light-silver rounded shell + centered recessed jet-black OLED window with crisp
 * pure-white icon/label + tiny recessed spherical colored LED jewel tucked in the bottom-right corner.
 */
@Composable
fun VideoEditOledKey(
    label: String,
    active: Boolean,
    ledColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    fontSize: TextUnit = 12.sp,
    dimLedPreview: Color? = null,
    trailingText: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val isPressed = pressed && enabled

    Box(
        modifier = modifier
            .height(height)
            .offset(y = if (isPressed) 1.dp else 0.dp)
            .drawBehind {
                drawMoldedSilverShell(radiusPx = 13.dp.toPx(), pressed = isPressed, enabled = enabled)
                // Recessed jet-black OLED window centered inside the sculpted silver frame
                val padH = 6.dp.toPx()
                val padTop = 5.dp.toPx()
                val padBot = 7.dp.toPx()
                val winW = (size.width - padH * 2f).coerceAtLeast(8f)
                val winH = (size.height - padTop - padBot).coerceAtLeast(8f)
                drawRecessedOledWindow(
                    topLeft = Offset(padH, padTop),
                    winSize = Size(winW, winH),
                    radiusPx = 8.5.dp.toPx(),
                    activeSurface = true
                )
            }
            .drawWithContent {
                drawContent()
                // Bottom-right corner recessed glossy colored LED jewel (exact Image 1 placement)
                drawHardwareLedJewel(
                    center = Offset(size.width - 6.dp.toPx(), size.height - 7.dp.toPx()),
                    radius = 3.3.dp.toPx(),
                    color = ledColor,
                    lit = active && enabled,
                    dimTint = dimLedPreview
                )
            }
            .clip(RoundedCornerShape(13.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(start = 12.dp, top = 5.dp, end = 14.dp, bottom = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        val fg = if (enabled) Color.White else Color.White.copy(alpha = 0.45f)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = label,
                style = mono(fontSize, fg, if (active) FontWeight.SemiBold else FontWeight.Medium),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = if (trailingText != null) Modifier.weight(1f, fill = false) else Modifier
            )
            if (trailingText != null) {
                Text(
                    text = trailingText,
                    style = mono(11.sp, Color(0xFFA0A5B2)),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * Unified Molded Silver-White Hardware Strip (exact match to the bottom faceplate bar of Ryan's 1st reference):
 * a single continuous light-silver rounded hardware chassis containing side-by-side recessed jet-black
 * OLED screen insets, each with its own bottom-right corner spherical colored LED jewel.
 */
@Composable
fun VideoEditOledStrip(
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawMoldedSilverShell(radiusPx = 14.dp.toPx(), pressed = false, enabled = true)
            }
            .clip(RoundedCornerShape(14.dp))
            .padding(start = 7.dp, top = 5.dp, end = 7.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * Single recessed jet-black OLED button inset inside [VideoEditOledStrip], paired with a
 * bottom-right corner spherical colored LED jewel right on the silver bezel corner.
 */
@Composable
fun RowScope.VideoEditStripInset(
    label: String,
    selected: Boolean,
    ledColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    dimLedPreview: Color? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val isPressed = pressed && enabled

    Box(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .offset(y = if (isPressed) 0.8.dp else 0.dp)
            .drawBehind {
                drawRecessedOledWindow(
                    topLeft = Offset(0f, 0f),
                    winSize = Size(size.width, size.height),
                    radiusPx = 8.5.dp.toPx(),
                    activeSurface = selected || isPressed
                )
            }
            .drawWithContent {
                drawContent()
                drawHardwareLedJewel(
                    center = Offset(size.width - 2.dp.toPx(), size.height - 1.5.dp.toPx()),
                    radius = 3.2.dp.toPx(),
                    color = ledColor,
                    lit = selected && enabled,
                    dimTint = dimLedPreview
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        val fg = when {
            !enabled -> Color.White.copy(alpha = 0.35f)
            selected -> Color.White
            else -> Color(0xFFD0D4DE)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                style = mono(11.sp, fg, if (selected) FontWeight.SemiBold else FontWeight.Medium),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
