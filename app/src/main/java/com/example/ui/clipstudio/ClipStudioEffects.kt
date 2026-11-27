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
import androidx.compose.runtime.rememberUpdatedState
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
    size: Dp = 32.dp,
    tint: Color = com.example.ui.design.AcTokens.Ink,
    speedMultiplier: Float = 1f,
    paused: Boolean = false
) {
    // High-contrast, bold OLED-tuned thinking-orbs@0.3.2 Rubik sphere: denser rings + bolder crisp dots
    val preset = remember(size) {
        when {
            size.value <= 28f -> Triple(1.95f, 0.19f, 2.25f)
            size.value <= 42f -> Triple(1.90f, 0.25f, 1.95f)
            else -> Triple(1.82f, 0.42f, 1.45f)
        }
    }
    val (presetSpeed, countScale, sizeScale) = preset
    val rt = remember(countScale) { sqrt(countScale) }
    val latRings = remember(rt) { max(6, (15f * rt).roundToInt()) }
    val lonDensity = remember(rt) { max(16, (40f * rt).roundToInt()) }
    val rBase = 0.95f * sizeScale
    val rDepth = 2.05f * sizeScale
    val rActive = 0.55f * sizeScale
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
        val radius = sz / 2f * 0.86f
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

        // Bolder OLED-readable dot sizing scaled cleanly to physical dp
        val logicalSize = size.value.coerceAtLeast(20f)
        val rs = (logicalSize / 210f).pow(0.55f) * (sz / logicalSize)
        val minDotPx = 0.95.dp.toPx()

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
                val dotR = max(minDotPx, (rBase + rDepth * depth + if (inActive) rActive else 0f) * rs)
                val w = (0.44f - 0.44f * depth - if (inActive) 0.14f else 0f).coerceIn(0f, 0.48f)
                dots.add(OrbDot(cx + x1 * radius, cy - y1 * radius, z2, dotR, w))
            }
        }
        dots.sortBy { it.z }
        // Subtle center bloom behind the orb so it pops cleanly off dark OLED surfaces
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius * 1.15f
            ),
            radius = radius * 1.15f,
            center = Offset(cx, cy)
        )
        for (dot in dots) {
            val lum = (1f - dot.white).coerceIn(0.52f, 1f)
            val alpha = (0.68f + 0.32f * lum).coerceIn(0.68f, 1f)
            drawCircle(
                color = Color(
                    red = (tint.red * lum + (1f - tint.red) * 0.10f * lum).coerceIn(0f, 1f),
                    green = (tint.green * lum + (1f - tint.green) * 0.10f * lum).coerceIn(0f, 1f),
                    blue = (tint.blue * lum + (1f - tint.blue) * 0.10f * lum).coerceIn(0f, 1f),
                    alpha = alpha
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

// Design v2: the 7-lobe voice glow uses only the red tokens (no multicolor glow).
private val VOICE_PALETTE_DARK = arrayOf(
    com.example.ui.design.AcTokens.RedHi,
    com.example.ui.design.AcTokens.Red,
    com.example.ui.design.AcTokens.OpRed,
    com.example.ui.design.AcTokens.RedHi,
    com.example.ui.design.AcTokens.Red,
    com.example.ui.design.AcTokens.OpRed,
    com.example.ui.design.AcTokens.RedLo
).map { floatArrayOf(it.red, it.green, it.blue) }.toTypedArray()

/**
 * Maps raw 16-bit PCM microphone RMS (`0..1`, where real smartphone conversational speech ranges
 * from `~0.015` soft voice to `~0.14+` loud/max voice) into a perceptual `0..1` voice energy value.
 * Ambient room noise below `0.006` RMS stays cleanly gated at `0f`.
 */
internal fun mapMicRmsToVoiceEnergy(rawRms: Float): Float {
    val clamped = rawRms.coerceIn(0f, 1f)
    if (clamped <= 0.006f) return 0f
    return ((clamped - 0.006f) / 0.134f).coerceIn(0f, 1f).pow(0.55f)
}

internal data class VoiceDitherLayout(
    val voiceEnergy: Float,
    val targetVisibleHeightPx: Float,
    val maxFieldHeightPx: Float,
    val haloHeightPx: Float,
    val rangeRxPx: Float,
    val rangeRyPx: Float,
    val centerLobeHeightPx: Float,
    val horizontalScale: Float,
    val spread: Float
)

/**
 * Computes screen-proportional geometry for [VoiceGlowDitherOverlay] so the dither field scales
 * identically across every screen resolution & DPI (including 1344x2992 Pixel 11 Pro XL):
 * - Silence (`voiceEnergy = 0`): calm listening rim (`~8.5%` of screen height).
 * - Normal speech (`voiceEnergy ~ 0.55`): dynamic waves (`~26%` of screen height).
 * - Max voice (`voiceEnergy = 1.0`): surges cleanly above `1/3` of screen height (`~38–40%` visible lit dither).
 */
internal fun computeVoiceDitherLayout(
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    voiceEnergy01: Float
): VoiceDitherLayout {
    val v = voiceEnergy01.coerceIn(0f, 1f)
    val targetVisibleH = canvasHeightPx * (0.085f + 0.365f * v)
    val maxFieldH = (targetVisibleH * 1.24f).coerceAtMost(canvasHeightPx * 0.58f)
    val haloH = (targetVisibleH * 1.15f).coerceAtMost(canvasHeightPx * 0.52f)
    val spread = 0.90f + 0.55f * v
    return VoiceDitherLayout(
        voiceEnergy = v,
        targetVisibleHeightPx = targetVisibleH,
        maxFieldHeightPx = maxFieldH,
        haloHeightPx = haloH,
        rangeRxPx = max(1f, canvasWidthPx * (0.58f + 0.24f * v)),
        rangeRyPx = max(1f, maxFieldH),
        centerLobeHeightPx = max(16f, targetVisibleH / 0.62f),
        horizontalScale = (canvasWidthPx / 360f).coerceAtLeast(0.75f),
        spread = spread
    )
}

/** Balanced per-lobe height multiplier so sideways lobe flow stays organic without center-peak collapse. */
private fun voiceLobeRelativeHeight(lb: VoiceLobe): Float = 0.86f + 0.14f * (lb.h / 46f)

/** Shared 3-band voice modulation used by both [VoiceGlowDitherOverlay] and [measureVoiceDitherLitReachFraction]. */
private fun computeVoiceBands(v: Float, clockSec: Float): FloatArray = floatArrayOf(
    0.60f + 0.40f * v,
    (0.55f + 0.45f * v) * (0.90f + 0.14f * sin(clockSec * 9.1f)),
    (0.50f + 0.50f * v) * (0.86f + 0.18f * sin(clockSec * 13.7f + 2f))
)

/**
 * Evaluates the highest lit Bayer dither pixel height as a fraction of [canvasHeightPx] (`0..1`)
 * for a given [voiceEnergy01] and [clockSec], using the exact same field math rendered by
 * [VoiceGlowDitherOverlay]. Used to verify mathematically & in unit tests that max voice rises
 * strictly `>= 1/3` of screen height across all resolutions.
 */
internal fun measureVoiceDitherLitReachFraction(
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    voiceEnergy01: Float,
    cellPitchPx: Float = 15f,
    clockSec: Float = 0.5f
): Float {
    if (canvasWidthPx <= 1f || canvasHeightPx <= 1f || cellPitchPx <= 1f) return 0f
    val layout = computeVoiceDitherLayout(canvasWidthPx, canvasHeightPx, voiceEnergy01)
    val v = layout.voiceEnergy
    val bands = computeVoiceBands(v, clockSec)
    val flowOffset = (clockSec * 56f * (0.28f + 1.25f * v)) % (36f * VOICE_LOBES.size)
    val halfRing = 36f * VOICE_LOBES.size * 0.5f
    val lobeX = FloatArray(VOICE_LOBES.size)
    val lobeInvW = FloatArray(VOICE_LOBES.size)
    val lobeInvH = FloatArray(VOICE_LOBES.size)
    for (i in VOICE_LOBES.indices) {
        val lb = VOICE_LOBES[i]
        var wrappedX = ((lb.xOff + flowOffset + halfRing) % (halfRing * 2f))
        if (wrappedX < 0f) wrappedX += halfRing * 2f
        wrappedX -= halfRing
        lobeX[i] = canvasWidthPx * 0.5f + wrappedX * layout.spread * layout.horizontalScale * 1.05f
        val rw = max(16f, lb.w * 1.15f * layout.spread * layout.horizontalScale)
        val relH = voiceLobeRelativeHeight(lb)
        val rh = max(16f, layout.centerLobeHeightPx * relH * bands[lb.band])
        lobeInvW[i] = 1f / rw
        lobeInvH[i] = 1f / rh
    }
    val cols = ceil(canvasWidthPx / cellPitchPx).toInt()
    val rows = ceil(layout.maxFieldHeightPx / cellPitchPx).toInt()
    val xStart = (canvasWidthPx - cols * cellPitchPx) * 0.5f
    val domeFactor = 0.55f * 2.0f
    val invHalfW = 2f / max(1f, canvasWidthPx)
    val softness = 0.78f
    var highestLitHeightPx = 0f
    for (row in 0 until rows) {
        val heightFromBottom = (row + 0.5f) * cellPitchPx
        val dyBottom = -heightFromBottom
        for (col in 0 until cols) {
            val px = xStart + (col + 0.5f) * cellPitchPx
            val nx = (px - canvasWidthPx * 0.5f) * invHalfW
            val dome = 1f + domeFactor * nx * nx
            val rx = (px - canvasWidthPx * 0.5f) / layout.rangeRxPx
            val ry = (dyBottom / layout.rangeRyPx) * dome
            val dMask = sqrt(rx * rx + ry * ry)
            if (dMask >= 1f) continue
            val maskGain = if (dMask < 0.45f) {
                1f - 0.45f * (dMask / 0.45f)
            } else {
                0.55f * (1f - (dMask - 0.45f) / 0.55f)
            }
            var prod = 1f
            for (i in VOICE_LOBES.indices) {
                val lx = (px - lobeX[i]) * lobeInvW[i]
                val ly = dyBottom * lobeInvH[i] * dome
                val dist = sqrt(lx * lx + ly * ly) / softness
                val b = if (dist < 1f) 1f - dist else 0f
                prod *= (1f - b * 0.94f)
            }
            val rawX = (1f - prod) * maskGain * (0.55f + 0.75f * v)
            if (rawX <= 0.005f) continue
            val rBright = min(1f, rawX.pow(0.55f) * 1.32f)
            val bayer = BAYER_4X4[(row and 3) * 4 + (col and 3)]
            if (rBright >= bayer * 0.90f + 0.03f && heightFromBottom > highestLitHeightPx) {
                highestLitHeightPx = heightFromBottom
            }
        }
    }
    return (highestLitHeightPx / canvasHeightPx).coerceIn(0f, 1f)
}

/**
 * Exact Jetpack Compose port of `libraries.dev/voice` (`voice-glow@0.5.0`, `type="mobile"`, `look="dither"`):
 * rises from the bottom edge of the screen whenever any microphone is active, driven by real live
 * microphone RMS [level] (0..1) with 4x4 ordered Bayer dithering, 5-level per-channel color quantization,
 * dome curvature, sideways lobe flow, and soft pixel-glow bloom beneath the active dither pixels.
 * Screen-proportional geometry ensures calm low rim on silence and `>= 1/3` screen rise on max voice.
 */
@Composable
fun VoiceGlowDitherOverlay(
    active: Boolean,
    level: Float,
    modifier: Modifier = Modifier
) {
    val fade by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = if (active) 260 else 380, easing = LinearEasing),
        label = "voice_dither_fade"
    )
    if (fade <= 0.01f) return

    val latestLevel by rememberUpdatedState(level)
    var clockSec by remember { mutableFloatStateOf(0f) }
    var smoothedEnergy by remember { mutableFloatStateOf(mapMicRmsToVoiceEnergy(level)) }
    LaunchedEffect(active) {
        var lastNanos = -1L
        while (true) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos < 0L) 0.016f else ((nanos - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
                lastNanos = nanos
                clockSec += dt
                // Real live 50 Hz mic RMS read through rememberUpdatedState so every frame tracks voice live!
                val target = mapMicRmsToVoiceEnergy(latestLevel)
                val rate = if (target > smoothedEnergy) 1f / 0.045f else 1f / 0.26f
                smoothedEnergy += (target - smoothedEnergy) * (1f - exp(-dt * rate))
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("cs_recording_border")) {
        Canvas(Modifier.fillMaxSize()) {
            val cw = size.width
            val ch = size.height
            if (cw <= 1f || ch <= 1f) return@Canvas

            val t = clockSec
            val breathe = 0.025f * sin(t * (2f * PI.toFloat() / 4.8f))
            val v = (smoothedEnergy + breathe * (1f - 0.5f * smoothedEnergy)).coerceIn(0f, 1f)
            val layout = computeVoiceDitherLayout(cw, ch, v)

            // 3 frequency bands driven from live smoothed voice energy (shared with unit verifier)
            val bands = computeVoiceBands(v, t)

            val flowOffset = (t * 56f * (0.28f + 1.25f * v)) % (36f * VOICE_LOBES.size)
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
                lobeX[i] = cw * 0.5f + wrappedX * layout.spread * layout.horizontalScale * 1.05f
                val rw = max(16f, lb.w * 1.15f * layout.spread * layout.horizontalScale)
                val bandL = bands[lb.band]
                val relH = voiceLobeRelativeHeight(lb)
                val rh = max(16f, layout.centerLobeHeightPx * relH * bandL)
                lobeInvW[i] = 1f / rw
                lobeInvH[i] = 1f / rh
                lobeLvl[i] = bandL
            }

            // Soft pixel-glow ambient halo behind the dither field (voice-glow `pixelGlow`)
            val haloHeight = layout.haloHeightPx
            val haloIntensity = 0.28f + 0.72f * v
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        com.example.ui.design.AcTokens.RedHi.copy(alpha = 0.09f * fade * haloIntensity),
                        com.example.ui.design.AcTokens.Red.copy(alpha = 0.18f * fade * haloIntensity),
                        com.example.ui.design.AcTokens.OpRed.copy(alpha = 0.28f * fade * haloIntensity)
                    ),
                    startY = ch - haloHeight,
                    endY = ch
                ),
                topLeft = Offset(0f, ch - haloHeight),
                size = Size(cw, haloHeight)
            )

            // 4x4 Ordered Bayer Dither Grid scaled proportionally across all display DPIs
            val cellPitch = (5.2.dp.toPx()).coerceAtLeast(8f)
            val dotSize = max(2.5f, cellPitch - max(1.6f, cellPitch * 0.22f))
            val faintDotSize = dotSize * 0.44f
            val cols = ceil(cw / cellPitch).toInt()
            val rows = ceil(layout.maxFieldHeightPx / cellPitch).toInt()
            val xStart = (cw - cols * cellPitch) * 0.5f

            val domeFactor = 0.55f * 2.0f
            val invHalfW = 2f / max(1f, cw)
            val quantLevels = 5f
            val quantMax = quantLevels - 1f
            val softness = 0.78f

            for (row in 0 until rows) {
                val py = ch - (row + 0.5f) * cellPitch
                val dyBottom = py - ch
                for (col in 0 until cols) {
                    val px = xStart + (col + 0.5f) * cellPitch
                    val nx = (px - cw * 0.5f) * invHalfW
                    val dome = 1f + domeFactor * nx * nx

                    // Elliptical range mask
                    val rx = (px - cw * 0.5f) / layout.rangeRxPx
                    val ry = (dyBottom / layout.rangeRyPx) * dome
                    val dMask = sqrt(rx * rx + ry * ry)
                    if (dMask >= 1f) continue
                    val maskGain = if (dMask < 0.45f) {
                        1f - 0.45f * (dMask / 0.45f)
                    } else {
                        0.55f * (1f - (dMask - 0.45f) / 0.55f)
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
                        prod *= (1f - b * 0.94f)
                        val wCol = exp(-sq / (0.66f * 0.66f)) * lobeLvl[i]
                        if (wCol > 1e-4f) {
                            val rgb = VOICE_PALETTE_DARK[i]
                            wSum += wCol
                            rAcc += rgb[0] * wCol
                            gAcc += rgb[1] * wCol
                            bAcc += rgb[2] * wCol
                        }
                    }

                    val rawX = (1f - prod) * maskGain * (0.55f + 0.75f * v)
                    if (rawX <= 0.005f || wSum <= 1e-5f) continue

                    val rBright = min(1f, rawX.pow(0.55f) * 1.32f)
                    val bayer = BAYER_4X4[(row and 3) * 4 + (col and 3)]
                    val isLit = rBright >= bayer * 0.90f + 0.03f
                    if (!isLit && rBright < 0.06f) continue

                    // Saturation boost (1.38) + white-hot core lift + 5-level Bayer color quantization
                    val nr = rAcc / wSum
                    val ng = gAcc / wSum
                    val nb = bAcc / wSum
                    val lum = 0.2126f * nr + 0.7152f * ng + 0.0722f * nb
                    val sr = (lum + (nr - lum) * 1.38f).coerceIn(0f, 1f)
                    val sg = (lum + (ng - lum) * 1.38f).coerceIn(0f, 1f)
                    val sb = (lum + (nb - lum) * 1.38f).coerceIn(0f, 1f)
                    val hot = (max(0f, rBright - 0.68f) / 0.32f) * 0.65f

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

