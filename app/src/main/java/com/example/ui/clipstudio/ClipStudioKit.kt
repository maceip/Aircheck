package com.example.ui.clipstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.R

// =============================================================================
// Clip Studio v4 tokens (from Clip Studio Android v4 design handoff)
// =============================================================================

object Cs {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF0A0A0D)
    val Video = Color(0xFF0D0D10)
    val Panel = Color(0xFF121215)
    val Raised = Color(0xFF18181C)
    val Sheet = Color(0xFF111113)
    val Well = Color(0xFF070709)
    val WellEdge = Color(0xFF1C1C22)
    val Hairline = Color(0xFF2C2C32)
    val Pinned = Color(0xFF1E1E24)
    val Track = Color(0xFF1A1A1E)
    val Ink = Color(0xFFEBEBEF)
    val InkBright = Color(0xFFF2F2F5)
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xFF8E8E96)
    val Dim = Color(0xFF4A4A52)
    val Pip = Color(0xFF5A5A66)
    val Red = Color(0xFFD71921)
    val Amber = Color(0xFFE6A100)
    val AmberBright = Color(0xFFFFB800)
}

val GeistFamily = FontFamily(
    Font(R.font.geist, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist, FontWeight(370), variationSettings = FontVariation.Settings(FontVariation.weight(370))),
    Font(R.font.geist, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500)))
)

val GeistMonoFamily = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500)))
)

val NdotFamily = FontFamily(Font(R.font.ndot47))

fun mono(size: TextUnit, color: Color = Cs.Ink, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(
        fontFamily = GeistMonoFamily,
        fontSize = size,
        fontWeight = weight,
        color = color,
        letterSpacing = tracking.em,
        fontFeatureSettings = "tnum, zero"
    )

fun sans(size: TextUnit, color: Color = Cs.Ink, lineHeight: TextUnit = TextUnit.Unspecified, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = GeistFamily, fontSize = size, lineHeight = lineHeight, fontWeight = weight, color = color)

fun ndot(size: TextUnit, color: Color = Cs.Ink) = TextStyle(fontFamily = NdotFamily, fontSize = size, color = color)

val SectionLabelStyle = mono(10.sp, Cs.Muted, FontWeight.Medium, 0.06f)

// =============================================================================
// Bevelled hardware keys
// =============================================================================

/** Key colour roles: fill, top highlight, side, bottom lip, text. */
enum class KeyRole(val fill: Color, val hi: Color, val side: Color, val lip: Color, val text: Color) {
    STD(Color(0xFF25252B), Color(0xFF43434E), Color(0xFF2E2E36), Color(0xFF050507), Color(0xFFF2F2F5)),
    COMMIT(Color(0xFFEBEBEF), Color(0xFFFFFFFF), Color(0xFFC9C9D1), Color(0xFF9E9EA8), Color(0xFF000000)),
    VOICE(Color(0xFFD9381E), Color(0xFFFF6B52), Color(0xFFB02D17), Color(0xFF7A1B0C), Color(0xFFFFFFFF)),
    REPAIR(Color(0xFF2E2310), Color(0xFF6B5021), Color(0xFFC88A14), Color(0xFF120D05), Color(0xFFFFB800)),
    ACTIVE(Color(0xFFD4D4DC), Color(0xFFFFFFFF), Color(0xFFB4B4BE), Color(0xFF787882), Color(0xFF000000))
}

private fun Modifier.keyBevel(role: KeyRole, pressed: Boolean, radius: Dp, enabled: Boolean, pip: Boolean = false): Modifier =
    drawBehind {
        val r = radius.toPx()
        val px = 1.dp.toPx()
        val lip = if (pressed) px else 2 * px
        val fill = if (enabled) role.fill else role.fill.copy(alpha = 0.7f)
        drawRoundRect(role.lip, cornerRadius = CornerRadius(r, r))
        drawRoundRect(
            role.side,
            size = Size(size.width, size.height - lip),
            cornerRadius = CornerRadius(r, r)
        )
        drawRoundRect(
            if (pressed) role.side else role.hi,
            size = Size(size.width, (r + px).coerceAtMost(size.height - lip)),
            cornerRadius = CornerRadius(r, r),
            topLeft = Offset.Zero
        )
        drawRoundRect(
            fill,
            topLeft = Offset(px, px),
            size = Size(size.width - 2 * px, size.height - lip - px),
            cornerRadius = CornerRadius((r - px).coerceAtLeast(0f), (r - px).coerceAtLeast(0f))
        )
        if (pip) {
            val pipSize = 3.dp.toPx()
            val inset = 5.dp.toPx()
            drawRect(
                color = if (role == KeyRole.ACTIVE) Cs.Dim else Cs.Pip,
                topLeft = Offset(size.width - inset - pipSize, inset),
                size = Size(pipSize, pipSize)
            )
        }
    }

/** A tappable bevelled key. Disabled keys keep their footprint but dim text and ignore taps. */
@Composable
fun CsKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: KeyRole = KeyRole.STD,
    enabled: Boolean = true,
    radius: Dp = 4.dp,
    pip: Boolean = false,
    horizontalPadding: Dp = 12.dp,
    contentAlignment: Arrangement.Horizontal = Arrangement.Center,
    textColor: Color? = null,
    content: @Composable RowScope.(Color) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .offset(y = if (pressed) 1.dp else 0.dp)
            .keyBevel(role, pressed, radius, enabled, pip = pip)
            .clip(RoundedCornerShape(radius))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = contentAlignment,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = (textColor ?: role.text).let { if (enabled) it else Cs.Dim }
        content(tint)
    }
}

/** Single-label key helper. */
@Composable
fun CsKeyLabel(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: KeyRole = KeyRole.STD,
    enabled: Boolean = true,
    size: TextUnit = 12.sp,
    weight: FontWeight = FontWeight.Normal,
    textColor: Color? = null,
    pip: Boolean = false,
    horizontalPadding: Dp = 10.dp,
    contentAlignment: Arrangement.Horizontal? = null,
    trailingArrow: Boolean = false
) {
    CsKey(
        onClick = onClick,
        modifier = modifier,
        role = role,
        enabled = enabled,
        pip = pip,
        horizontalPadding = horizontalPadding,
        textColor = textColor,
        contentAlignment = contentAlignment ?: if (trailingArrow) Arrangement.SpaceBetween else Arrangement.Center
    ) { tint ->
        Text(
            label,
            style = mono(size, tint, weight, 0.02f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = if (trailingArrow) Modifier.weight(1f, fill = false).padding(end = 4.dp) else Modifier
        )
        if (trailingArrow) Text("->", style = mono(size, tint, weight), maxLines = 1, softWrap = false)
    }
}

/**
 * Press-and-hold key (records while held). [onPress] fires on finger down and [onRelease]
 * on lift or cancel; accessibility "click" toggles press then release.
 */
@Composable
fun CsHoldKey(
    label: String,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    role: KeyRole = KeyRole.VOICE,
    enabled: Boolean = true,
    held: Boolean = false,
    size: TextUnit = 11.sp
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    Row(
        modifier = modifier
            .offset(y = if (held) 1.dp else 0.dp)
            .keyBevel(role, held, 4.dp, enabled)
            .semantics {
                this.role = Role.Button
                onClick(label = label) {
                    if (held) release() else press()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    press()
                    waitForUpOrCancellation()
                    release()
                }
            }
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = mono(size, if (enabled) role.text else Cs.Dim, tracking = 0.02f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Pill chip; [on] uses the ACTIVE key role. Only [outlined] tone rules show the 4dp square glyph. */
@Composable
fun CsChip(
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    size: TextUnit = 12.sp,
    horizontalPadding: Dp = 12.dp,
    outlined: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val role = if (on) KeyRole.ACTIVE else KeyRole.STD
    Row(
        modifier = modifier
            .height(height)
            .offset(y = if (pressed) 1.dp else 0.dp)
            .keyBevel(role, pressed, height / 2, true)
            .then(if (outlined) Modifier.border(1.dp, Cs.White, RoundedCornerShape(height / 2)) else Modifier)
            .clip(RoundedCornerShape(height / 2))
            .toggleable(value = on, interactionSource = interaction, indication = null, role = Role.Checkbox) { onToggle() }
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (outlined) {
            Box(Modifier.size(4.dp).background(role.text))
        }
        Text(label, style = mono(size, role.text, tracking = 0.02f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// =============================================================================
// Surfaces, Meters, Rulers
// =============================================================================

/** Recessed well that holds keys/chips. */
@Composable
fun CsWell(
    modifier: Modifier = Modifier,
    padding: Dp = 6.dp,
    radius: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(Cs.Well)
            .drawBehind {
                drawLine(Cs.Black, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
                drawLine(Cs.WellEdge, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
            }
            .padding(padding),
        content = content
    )
}

/** Dot-grid panel background (#121215 with 8dp radial dots). */
fun Modifier.dotPanel(): Modifier = background(Cs.Panel).drawBehind {
    val step = 8.dp.toPx()
    val r = 1.dp.toPx() * 0.6f
    val dot = Color.White.copy(alpha = 0.04f)
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(dot, r, Offset(x, y))
            x += step
        }
        y += step
    }
}

fun Modifier.hairlineBottom(color: Color = Cs.Hairline): Modifier = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
}

fun Modifier.hairlineTop(color: Color = Cs.Hairline): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
}

/** Corner-bracket marks used for pinned phrases and the active canvas zone. */
fun Modifier.cornerBrackets(color: Color = Cs.White, len: Dp = 6.dp, all: Boolean = false): Modifier = drawBehind {
    val l = len.toPx()
    val w = 1.dp.toPx()
    drawLine(color, Offset(0f, 0f), Offset(l, 0f), w)
    drawLine(color, Offset(0f, 0f), Offset(0f, l), w)
    drawLine(color, Offset(size.width, size.height), Offset(size.width - l, size.height), w)
    drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - l), w)
    if (all) {
        drawLine(color, Offset(size.width, 0f), Offset(size.width - l, 0f), w)
        drawLine(color, Offset(size.width, 0f), Offset(size.width, l), w)
        drawLine(color, Offset(0f, size.height), Offset(l, size.height), w)
        drawLine(color, Offset(0f, size.height), Offset(0f, size.height - l), w)
    }
}

/**
 * Dual-lane stereo LED meter driven strictly by real persisted [waveformPeaks] at [positionMs].
 * Shows unlit bars when no audio is loaded or playback is parked at silence.
 */
@Composable
fun CsStereoMeter(
    peaks: List<Float>,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val activeLevel = remember(peaks, positionMs, durationMs, isPlaying) {
        if (peaks.isEmpty() || durationMs <= 0L) {
            0f
        } else {
            val frac = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 0.999f)
            val idx = (frac * peaks.size).toInt().coerceIn(0, peaks.lastIndex)
            val primary = peaks[idx].coerceIn(0f, 1f)
            if (isPlaying || positionMs > 0L) primary else 0f
        }
    }
    val secondaryLevel = remember(peaks, positionMs, durationMs, isPlaying) {
        if (peaks.isEmpty() || durationMs <= 0L || (!isPlaying && positionMs == 0L)) {
            0f
        } else {
            val frac = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 0.999f)
            val idx = ((frac * peaks.size).toInt() + 1).coerceIn(0, peaks.lastIndex)
            peaks[idx].coerceIn(0f, 1f)
        }
    }
    Box(
        modifier = modifier
            .width(16.dp)
            .drawBehind {
                val laneW = 6.dp.toPx()
                val gapW = 4.dp.toPx()
                val barH = 2.dp.toPx()
                val pitch = 4.dp.toPx()
                val redZoneY = 24.dp.toPx()
                val totalBars = (size.height / pitch).toInt().coerceAtLeast(1)
                val litLeft = (activeLevel * totalBars).toInt()
                val litRight = (secondaryLevel * totalBars).toInt()
                for (i in 0 until totalBars) {
                    val y = size.height - (i + 1) * pitch
                    val isPeakZone = y < redZoneY
                    val leftColor = when {
                        i < litLeft && isPeakZone -> Cs.Red
                        i < litLeft -> Cs.Ink
                        isPeakZone -> Cs.Red.copy(alpha = 0.25f)
                        else -> Cs.Hairline
                    }
                    val rightColor = when {
                        i < litRight && isPeakZone -> Cs.Red
                        i < litRight -> Cs.Ink
                        isPeakZone -> Cs.Red.copy(alpha = 0.25f)
                        else -> Cs.Hairline
                    }
                    drawRect(leftColor, topLeft = Offset(0f, y), size = Size(laneW, barH))
                    drawRect(rightColor, topLeft = Offset(laneW + gapW, y), size = Size(laneW, barH))
                }
            }
            .testTag("cs_stereo_meter")
    )
}

/**
 * Interactive 56dp horizontal tick ruler scrubber (matching 04 Newsroom Studio).
 * Dragging moves ticks under the fixed center red needle; tapping the left/right half steps down/up.
 */
@Composable
fun CsTickRuler(
    label: String,
    valueText: String,
    normalizedValue: Float,
    enabled: Boolean,
    onStep: (deltaSteps: Int) -> Unit,
    tag: String,
    modifier: Modifier = Modifier
) {
    var accumPx by remember { mutableFloatStateOf(0f) }
    val latestStep by rememberUpdatedState(onStep)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Cs.Surface)
            .hairlineTop()
            .hairlineBottom()
            .drawBehind {
                val minor = 4.dp.toPx()
                val major = 20.dp.toPx()
                val offsetPx = ((normalizedValue - 0.5f) * -size.width * 0.5f)
                val shiftMinor = ((offsetPx % minor) + minor) % minor
                val shiftMajor = ((offsetPx % major) + major) % major
                val minorH = 5.dp.toPx()
                val majorH = 12.dp.toPx()
                var x = shiftMinor
                while (x < size.width) {
                    drawLine(Cs.Hairline, Offset(x, size.height - minorH), Offset(x, size.height), 1.dp.toPx())
                    x += minor
                }
                x = shiftMajor
                while (x < size.width) {
                    drawLine(if (enabled) Cs.Muted else Cs.Dim, Offset(x, size.height - majorH), Offset(x, size.height), 1.dp.toPx())
                    x += major
                }
                // Fixed center needle (lower half below labels)
                drawLine(
                    if (enabled) Cs.Red else Cs.Dim,
                    Offset(size.width / 2f, size.height - 22.dp.toPx()),
                    Offset(size.width / 2f, size.height),
                    1.5.dp.toPx()
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { accumPx = 0f },
                    onDragEnd = { accumPx = 0f },
                    onDragCancel = { accumPx = 0f }
                ) { change, dx ->
                    change.consume()
                    accumPx += dx
                    val pxPerStep = 14.dp.toPx()
                    val steps = (accumPx / pxPerStep).toInt()
                    if (steps != 0) {
                        accumPx -= steps * pxPerStep
                        latestStep(steps)
                    }
                }
            }
            .testTag(tag)
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled
                    ) { latestStep(-1) }
                    .testTag("${tag}_dec")
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled
                    ) { latestStep(1) }
                    .testTag("${tag}_inc")
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = SectionLabelStyle, maxLines = 1)
            Text(
                valueText,
                style = mono(11.sp, if (enabled) Cs.Ink else Cs.Dim, FontWeight.Medium),
                maxLines = 1,
                modifier = Modifier.testTag("${tag}_value")
            )
        }
    }
}

/** 36dp header strip with hairline. */
@Composable
fun CsHeader(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .hairlineBottom()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

/** Stage breadcrumb: dimmed others, Ndot for the active one. */
@Composable
fun StageCrumb(active: Int, label: String, modifier: Modifier = Modifier, onSelect: (Int) -> Unit = {}) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (n in 1..3) {
            if (n == active) {
                Text("%02d %s".format(active, label), style = ndot(12.sp), maxLines = 1)
            } else {
                Text(
                    "%02d".format(n),
                    style = mono(11.sp, Cs.Dim),
                    maxLines = 1,
                    modifier = Modifier.clickable { onSelect(n) }.padding(horizontal = 2.dp).testTag("cs_crumb_$n")
                )
            }
            if (n < 3) Text("·", style = mono(11.sp, Cs.Dim))
        }
    }
}

/** Busy / error / notice line. Errors and notices are tappable to dismiss. */
@Composable
fun CsStatusLine(busy: String?, error: String?, notice: String?, onDismissError: () -> Unit, onDismissNotice: () -> Unit) {
    val (text, color, dismiss) = when {
        error != null -> Triple("! $error", Cs.Red, onDismissError)
        busy != null -> Triple("… $busy", Cs.Muted, null)
        notice != null -> Triple(notice, Cs.Muted, onDismissNotice)
        else -> return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(Cs.Surface)
            .hairlineBottom()
            .then(if (dismiss != null) Modifier.clickable { dismiss() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, style = mono(10.sp, color, tracking = 0.02f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SectionLabelStyle, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Bottom bar row of keys (56dp high, full-bleed recessed well). */
@Composable
fun CsBottomBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    CsWell(modifier.fillMaxWidth().height(56.dp), padding = 6.dp, radius = 0.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
fun CsColumn(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

fun mmss(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(s / 60, s % 60)
}
