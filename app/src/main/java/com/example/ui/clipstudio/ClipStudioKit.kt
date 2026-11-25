package com.example.ui.clipstudio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.data.BreakingNewsStyle
import kotlin.math.roundToInt

// =============================================================================
// Clip Studio v5 tokens (soft neumorphic graphite on #141417 body, sunken #000 panels)
// =============================================================================

object Cs {
    // v5 core surfaces
    val Body = Color(0xFF141417)
    val Raised = Color(0xFF1C1C21)
    val Pressed = Color(0xFF17171B)
    val Sheet = Color(0xFF1A1A1F)
    val SunkenTrack = Color(0xFF101013)
    val SunkenPanel = Color(0xFF000000)
    val BusyFill = Color(0xFF26262C)

    // Dividers & subtle rows
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF141417)
    val Video = Color(0xFF000000)
    val Panel = Color(0xFF141417)
    val Well = Color(0xFF101013)
    val WellEdge = Color(0xFF222228)
    val RowDivider = Color(0xFF1A1A1E)
    val ClauseDivider = Color(0xFF111114)
    val ActiveClauseBg = Color(0xFF0A0A0D)
    val BrollRow = Color(0xFF0D0D10)
    val Hairline = Color(0xFF2C2C32)
    val Pinned = Color(0xFF1E1E24)
    val Track = Color(0xFF1A1A1E)

    // Ink & accents
    val Ink = Color(0xFFEBEBEF)
    val InkSub = Color(0xFFC8C8CE)
    val InkBright = Color(0xFFF2F2F5)
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xFF8E8E96)
    val Dim = Color(0xFF4A4A52)
    val Pip = Color(0xFF5A5A66)
    val Red = Color(0xFFD71921)
    val Voice = Color(0xFFD9381E)
    val VoiceHi = Color(0xFFFF6B52)
    val Amber = Color(0xFFE6A100)
    val AmberBright = Color(0xFFFFB800)
    val RepairFill = Color(0xFF2E2310)
    val RepairHi = Color(0xFF6B5021)
}

val GeistFamily = FontFamily(
    Font(R.font.geist, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist, FontWeight(370), variationSettings = FontVariation.Settings(FontVariation.weight(370))),
    Font(R.font.geist, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500)))
)

val GeistMonoFamily = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.geist_mono, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.geist_mono, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
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

fun sans(
    size: TextUnit,
    color: Color = Cs.Ink,
    lineHeight: TextUnit = TextUnit.Unspecified,
    weight: FontWeight = FontWeight.Normal
) = TextStyle(fontFamily = GeistFamily, fontSize = size, lineHeight = lineHeight, fontWeight = weight, color = color)

fun ndot(size: TextUnit, color: Color = Cs.Ink) = TextStyle(fontFamily = NdotFamily, fontSize = size, color = color)

val SectionLabelStyle = mono(11.sp, Cs.Muted, FontWeight.Medium, 0.06f)

// =============================================================================
// Soft neumorphic keys & sunken surfaces (v5)
// =============================================================================

enum class KeyRole(val fill: Color, val hi: Color, val border: Color, val text: Color) {
    STD(Cs.Raised, Color(0x0AFFFFFF), Color(0x09FFFFFF), Cs.Ink),
    DOWN(Cs.Pressed, Color(0x09FFFFFF), Color(0x59000000), Cs.White),
    COMMIT(Cs.Ink, Cs.White, Color(0x26FFFFFF), Cs.Black),
    VOICE(Cs.Voice, Cs.VoiceHi, Color(0x33FF6B52), Cs.White),
    REPAIR(Cs.RepairFill, Cs.RepairHi, Color(0x406B5021), Cs.AmberBright),
    ACTIVE(Cs.Ink, Cs.White, Color(0x26FFFFFF), Cs.Black)
}

fun Modifier.neumorphicSurface(
    role: KeyRole,
    pressed: Boolean = false,
    radius: Dp = 14.dp,
    enabled: Boolean = true,
    accentBarColor: Color? = null
): Modifier = drawBehind {
    val r = radius.toPx()
    val corner = CornerRadius(r, r)
    val isSunken = pressed || role == KeyRole.DOWN
    val effectiveRole = if (pressed && role == KeyRole.STD) KeyRole.DOWN else role
    val fill = if (enabled) effectiveRole.fill else effectiveRole.fill.copy(alpha = 0.55f)

    if (!isSunken) {
        // Outer top-left soft highlight & bottom-right ambient shadow
        drawRoundRect(
            color = Color.White.copy(alpha = if (enabled) 0.04f else 0.015f),
            topLeft = Offset((-1.5).dp.toPx(), (-1.5).dp.toPx()),
            size = size,
            cornerRadius = corner
        )
        drawRoundRect(
            color = Color.Black.copy(alpha = if (enabled) 0.55f else 0.25f),
            topLeft = Offset(2.5.dp.toPx(), 3.5.dp.toPx()),
            size = size,
            cornerRadius = corner
        )
    }

    drawRoundRect(color = fill, cornerRadius = corner)

    if (isSunken) {
        // Inner dark top-left & subtle bottom-right light rim
        val sw = 1.5.dp.toPx()
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.65f),
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size((size.width - sw).coerceAtLeast(0f), (size.height - sw).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((r - sw / 2f).coerceAtLeast(0f)),
            style = Stroke(width = sw)
        )
        drawLine(
            color = Color.White.copy(alpha = 0.035f),
            start = Offset(r * 0.5f, size.height - 0.75.dp.toPx()),
            end = Offset((size.width - r * 0.5f).coerceAtLeast(r * 0.5f), size.height - 0.75.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
    } else {
        if (role == KeyRole.COMMIT || role == KeyRole.VOICE || role == KeyRole.REPAIR || role == KeyRole.ACTIVE) {
            val inset = (r * 0.92f).coerceAtMost((size.width / 2f).coerceAtLeast(0f))
            if (size.width - inset > inset) {
                drawLine(
                    color = if (enabled) effectiveRole.hi else effectiveRole.hi.copy(alpha = 0.35f),
                    start = Offset(inset, 1.dp.toPx()),
                    end = Offset(size.width - inset, 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
        drawRoundRect(
            color = effectiveRole.border,
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx())
        )
    }

    if (accentBarColor != null) {
        val barW = 3.dp.toPx()
        val vInset = (size.height * 0.24f).coerceAtLeast(6.dp.toPx())
        drawRoundRect(
            color = accentBarColor,
            topLeft = Offset(4.dp.toPx(), vInset),
            size = Size(barW, (size.height - 2f * vInset).coerceAtLeast(6f)),
            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
        )
    }
}

/** Sunken black panel (#000000, radius 18dp) for outlines, scripts, video monitors, and inputs. */
fun Modifier.sunkenPanel(radius: Dp = 18.dp, fill: Color = Cs.SunkenPanel): Modifier =
    clip(RoundedCornerShape(radius))
        .background(fill)
        .drawBehind {
            val r = radius.toPx()
            val sw = 1.5.dp.toPx()
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.85f),
                topLeft = Offset(sw / 2f, sw / 2f),
                size = Size((size.width - sw).coerceAtLeast(0f), (size.height - sw).coerceAtLeast(0f)),
                cornerRadius = CornerRadius((r - sw / 2f).coerceAtLeast(0f)),
                style = Stroke(width = sw)
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.035f),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 1.dp.toPx())
            )
        }

/** Raised card / sheet container (#1A1A1F). */
fun Modifier.raisedCard(radius: Dp = 20.dp): Modifier =
    drawBehind {
        val r = radius.toPx()
        val corner = CornerRadius(r, r)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.04f),
            topLeft = Offset((-2).dp.toPx(), (-2).dp.toPx()),
            size = size,
            cornerRadius = corner
        )
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.65f),
            topLeft = Offset(3.dp.toPx(), 5.dp.toPx()),
            size = size,
            cornerRadius = corner
        )
        drawRoundRect(color = Cs.Sheet, cornerRadius = corner)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.035f),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx())
        )
    }.clip(RoundedCornerShape(radius))

/** Soft neumorphic tappable key. */
@Composable
fun CsKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: KeyRole = KeyRole.STD,
    enabled: Boolean = true,
    radius: Dp = 14.dp,
    pip: Boolean = false,
    horizontalPadding: Dp = 14.dp,
    contentAlignment: Arrangement.Horizontal = Arrangement.Center,
    textColor: Color? = null,
    content: @Composable RowScope.(Color) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = modifier
            .offset(y = if (pressed && enabled) 1.dp else 0.dp)
            .neumorphicSurface(role = role, pressed = pressed && enabled, radius = radius, enabled = enabled)
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
        val baseTint = textColor ?: if (pressed && role == KeyRole.STD) Cs.White else role.text
        val tint = if (enabled) baseTint else Cs.Dim
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
    radius: Dp = 14.dp,
    size: TextUnit = 13.sp,
    weight: FontWeight = FontWeight.Normal,
    textColor: Color? = null,
    pip: Boolean = false,
    horizontalPadding: Dp = 12.dp,
    contentAlignment: Arrangement.Horizontal? = null,
    trailingArrow: Boolean = false
) {
    CsKey(
        onClick = onClick,
        modifier = modifier,
        role = role,
        enabled = enabled,
        radius = radius,
        pip = pip,
        horizontalPadding = horizontalPadding,
        textColor = textColor,
        contentAlignment = contentAlignment ?: if (trailingArrow) Arrangement.SpaceBetween else Arrangement.Center
    ) { tint ->
        Text(
            label,
            style = mono(size, tint, weight),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = if (trailingArrow) Modifier.weight(1f, fill = false).padding(end = 6.dp) else Modifier
        )
        if (trailingArrow) Text("→", style = mono(size, tint, weight), maxLines = 1, softWrap = false)
    }
}

/** 44x44dp (or custom size) square/round icon utility key. */
@Composable
fun CsIconKey(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    size: Dp = 44.dp,
    radius: Dp = 12.dp,
    iconSize: Dp = 22.dp,
    tintOverride: Color? = null
) {
    val role = if (active) KeyRole.DOWN else KeyRole.STD
    CsKey(
        onClick = onClick,
        modifier = modifier.size(size),
        role = role,
        enabled = enabled,
        radius = radius,
        horizontalPadding = 0.dp
    ) { tint ->
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) (tintOverride ?: tint) else Cs.Dim,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** 44dp rounded pill (radius 22dp) with commit-style active fill (#EBEBEF on black). */
@Composable
fun CsPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accentBarColor: Color? = null,
    enabled: Boolean = true,
    height: Dp = 44.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val role = if (selected) KeyRole.COMMIT else KeyRole.STD
    Row(
        modifier = modifier
            .height(height)
            .offset(y = if (pressed && enabled) 1.dp else 0.dp)
            .neumorphicSurface(
                role = role,
                pressed = pressed && enabled,
                radius = height / 2,
                enabled = enabled,
                accentBarColor = if (!selected) accentBarColor else null
            )
            .clip(RoundedCornerShape(height / 2))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(
                start = if (!selected && accentBarColor != null) 14.dp else 13.dp,
                end = 14.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val fg = if (!enabled) Cs.Dim else role.text
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = label,
            style = mono(13.sp, fg, if (selected) FontWeight.Medium else FontWeight.Normal),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Sunken #101013 segmented track (radius 16dp, 5dp padding, 5dp gap). */
@Composable
fun CsSegmentedRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 16.dp, fill = Cs.SunkenTrack)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Individual segment inside [CsSegmentedRow]. Active segment is raised (#1C1C21); inactive is bare (#8E8E96). */
@Composable
fun RowScope.CsSegment(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    enabled: Boolean = true,
    content: @Composable RowScope.(Color) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .weight(1f)
            .height(height)
            .then(
                if (selected) {
                    Modifier.neumorphicSurface(role = KeyRole.STD, radius = 12.dp, enabled = enabled)
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = when {
            !enabled -> Cs.Dim
            selected -> Cs.Ink
            else -> Cs.Muted
        }
        content(tint)
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
    radius: Dp = 18.dp,
    size: TextUnit = 11.sp
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    Row(
        modifier = modifier
            .offset(y = if (held) 1.dp else 0.dp)
            .neumorphicSurface(role = role, pressed = held, radius = radius, enabled = enabled)
            .clip(RoundedCornerShape(radius))
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
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = mono(size, if (enabled) role.text else Cs.Dim),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 92x64dp press-and-hold dock voice key (`mic` icon over 11sp mono label). */
@Composable
fun CsVoiceDockKey(
    label: String,
    icon: ImageVector,
    held: Boolean,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    Column(
        modifier = modifier
            .width(92.dp)
            .height(64.dp)
            .offset(y = if (held) 1.dp else 0.dp)
            .neumorphicSurface(role = KeyRole.VOICE, pressed = held, radius = 18.dp, enabled = enabled)
            .clip(RoundedCornerShape(18.dp))
            .semantics(mergeDescendants = true) {
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
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        val fg = if (enabled) Cs.White else Cs.Dim
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
        Text(
            text = label,
            style = mono(11.sp, fg),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 92x64dp secondary dock key (e.g. `pin`, `new idea`, `cancel`). */
@Composable
fun CsDockSideKey(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val role = if (active) KeyRole.DOWN else KeyRole.STD
    Column(
        modifier = modifier
            .width(92.dp)
            .height(64.dp)
            .offset(y = if (pressed && enabled) 1.dp else 0.dp)
            .neumorphicSurface(role = role, pressed = pressed && enabled, radius = 18.dp, enabled = enabled)
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        val fg = when {
            !enabled -> Cs.Dim
            active -> Cs.White
            else -> Cs.Ink
        }
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            style = mono(11.sp, fg),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Sunken 64dp progress box shown in place of the primary dock key while a real job runs.
 * Animates width linearly in 80ms (zero springs). Shows percentage only when a real fraction is known.
 */
@Composable
fun CsBusyBox(
    label: String,
    progressFraction: Float?,
    modifier: Modifier = Modifier
) {
    val targetFraction = (progressFraction ?: 0f).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "busy_progress"
    )
    Box(
        modifier = modifier
            .height(64.dp)
            .neumorphicSurface(role = KeyRole.DOWN, pressed = true, radius = 18.dp, enabled = true)
            .clip(RoundedCornerShape(18.dp))
            .testTag("cs_busy_box"),
        contentAlignment = Alignment.CenterStart
    ) {
        if (progressFraction != null && animatedFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedFraction)
                    .background(Cs.BusyFill)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = label,
                style = mono(14.sp, Cs.White, FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (progressFraction != null) {
                Text(
                    text = "${(targetFraction * 100f).roundToInt()}%",
                    style = mono(14.sp, Cs.White, FontWeight.Medium),
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Standard v5 bottom dock: at most two keys (optional 92x64 leading key + flex-1 64dp primary commit key),
 * replaced in place by [CsBusyBox] while a real job runs.
 */
@Composable
fun CsDock(
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    primaryTag: String,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    primaryTrailingIcon: ImageVector? = null,
    primaryLeadingIcon: ImageVector? = null,
    primaryRole: KeyRole = KeyRole.COMMIT,
    primaryEnabled: Boolean = true,
    isBusy: Boolean = false,
    busyLabel: String? = null,
    busyFraction: Float? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 14.dp, end = 12.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke(this)
        if (isBusy) {
            CsBusyBox(
                label = busyLabel ?: "working…",
                progressFraction = busyFraction,
                modifier = Modifier.weight(1f)
            )
        } else {
            CsKey(
                onClick = onPrimaryClick,
                role = primaryRole,
                enabled = primaryEnabled,
                radius = 18.dp,
                horizontalPadding = 20.dp,
                contentAlignment = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .testTag(primaryTag)
            ) { tint ->
                if (primaryLeadingIcon != null) {
                    Icon(
                        imageVector = primaryLeadingIcon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = primaryLabel,
                    style = mono(15.sp, tint, FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (primaryTrailingIcon != null) {
                    Icon(
                        imageVector = primaryTrailingIcon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Consistent 56dp v5 top bar across 01 SPARK, 02 SCRIPT, and 03 CUT:
 * active stage is a 44dp sunken segment in Ndot 13sp; other stages are bare 40dp targets in Geist Mono 500 13sp.
 */
@Composable
fun CsTopBar(
    activeStage: Int,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val stageNames = listOf("SPARK", "SCRIPT", "CUT")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stageNames.forEachIndexed { idx, name ->
            val stageNum = idx + 1
            val isActive = stageNum == activeStage
            if (isActive) {
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .defaultMinSize(minWidth = 40.dp)
                        .neumorphicSurface(role = KeyRole.DOWN, pressed = true, radius = 12.dp, enabled = true)
                        .clip(RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp)
                        .testTag("cs_crumb_$stageNum"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "%02d %s".format(stageNum, name),
                        style = ndot(13.sp, Cs.White),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else {
                val color = if (stageNum < activeStage) Cs.Ink else Cs.Dim
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .defaultMinSize(minWidth = 40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Tab) { onNavigate(stageNum) }
                        .padding(horizontal = 6.dp)
                        .testTag("cs_crumb_$stageNum"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "%02d".format(stageNum),
                        style = mono(13.sp, color, FontWeight.Medium),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing
        )
    }
}

/** Global 48dp #EBEBEF toast sitting 112dp above the bottom edge with an "ok" dismiss key. */
@Composable
fun BoxScope.CsToastOverlay(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.isNullOrBlank()) return
    Row(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(start = 12.dp, end = 12.dp, bottom = 112.dp)
            .fillMaxWidth()
            .height(48.dp)
            .neumorphicSurface(role = KeyRole.COMMIT, radius = 14.dp, enabled = true)
            .clip(RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 6.dp)
            .testTag("cs_toast"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = message,
            style = mono(13.sp, Cs.Black),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClick = onDismiss)
                .padding(horizontal = 12.dp)
                .testTag("cs_toast_ok"),
            contentAlignment = Alignment.Center
        ) {
            Text("ok", style = mono(13.sp, Cs.Black, FontWeight.Medium), maxLines = 1)
        }
    }
}

// =============================================================================
// Preserved cross-screen primitives (NewsroomStudioScreen & legacy helpers)
// =============================================================================

@Composable
fun CsChip(
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    size: TextUnit = 13.sp,
    horizontalPadding: Dp = 14.dp,
    outlined: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val role = if (on) KeyRole.COMMIT else KeyRole.STD
    Row(
        modifier = modifier
            .height(height)
            .offset(y = if (pressed) 1.dp else 0.dp)
            .neumorphicSurface(role = role, pressed = pressed, radius = height / 2, enabled = true)
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
        Text(label, style = mono(size, role.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun breakingAccentColor(style: BreakingNewsStyle): Color = when (style) {
    BreakingNewsStyle.BREAKING -> Cs.Red
    BreakingNewsStyle.DEVELOPING -> Cs.AmberBright
    BreakingNewsStyle.EXCLUSIVE -> Cs.Ink
    BreakingNewsStyle.ALERT_STAT -> Cs.Red
    BreakingNewsStyle.CLEAN -> Cs.Muted
}

@Composable
fun CsBreakingChip(
    style: BreakingNewsStyle,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = breakingAccentColor(style)
    CsPill(
        label = style.chipLabel.uppercase(),
        selected = selected,
        onClick = onSelect,
        accentBarColor = if (style == BreakingNewsStyle.CLEAN) null else accent,
        modifier = modifier
    )
}

@Composable
fun CsWell(
    modifier: Modifier = Modifier,
    padding: Dp = 5.dp,
    radius: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .sunkenPanel(radius = radius, fill = Cs.SunkenTrack)
            .padding(padding),
        content = content
    )
}

fun Modifier.hairlineBottom(color: Color = Cs.RowDivider): Modifier = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
}

fun Modifier.hairlineTop(color: Color = Cs.RowDivider): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
}

/**
 * Dual-lane stereo LED meter driven strictly by real persisted [peaks] at [positionMs].
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
                    .clickable(enabled = enabled) { latestStep(-1) }
                    .testTag("${tag}_dec")
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(enabled = enabled) { latestStep(1) }
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
                style = mono(13.sp, if (enabled) Cs.Ink else Cs.Dim, FontWeight.Medium),
                maxLines = 1,
                modifier = Modifier.testTag("${tag}_value")
            )
        }
    }
}

/** Tappable error or notice banner (busy states render in the bottom dock in v5). */
@Composable
fun CsStatusLine(
    busy: String? = null,
    error: String?,
    notice: String?,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val (text, color, dismiss) = when {
        !error.isNullOrBlank() -> Triple(error, Cs.Red, onDismissError)
        !notice.isNullOrBlank() -> Triple(notice, Cs.Muted, onDismissNotice)
        else -> return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Cs.SunkenTrack)
            .clickable(role = Role.Button) { dismiss() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("cs_status_line")
    ) {
        Text(text, style = mono(12.sp, color), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SectionLabelStyle, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun CsColumn(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

/** Formats milliseconds as `m:ss` (e.g. `0:58`, `1:05`), matching the Clip Studio v5 spec. */
fun mmss(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Formats an epoch timestamp into a concise uppercase relative age string (e.g. `2 DAYS AGO`, `TODAY`). */
fun formatRelativeAgeUpper(updatedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (updatedAtMillis <= 0L) return "RECENT"
    val diffMs = (nowMillis - updatedAtMillis).coerceAtLeast(0L)
    val mins = diffMs / 60_000L
    val hours = diffMs / 3_600_000L
    val days = diffMs / 86_400_000L
    return when {
        mins < 60L -> if (mins <= 1L) "JUST NOW" else "$mins MINS AGO"
        hours < 24L -> if (hours == 1L) "1 HOUR AGO" else "$hours HOURS AGO"
        days == 1L -> "1 DAY AGO"
        days < 30L -> "$days DAYS AGO"
        else -> "${days / 30L} MO AGO"
    }
}
