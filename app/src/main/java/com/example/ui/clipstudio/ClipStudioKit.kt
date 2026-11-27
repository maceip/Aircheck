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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
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
    val Cobalt = Color(0xFF3A86F2)
    val Mint = Color(0xFF44D498)
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
// Raised tactile hardware keys (v4 3D elevation + etched soft-neumorphic dock)
// =============================================================================

enum class KeyRole(
    val topFill: Color,
    val fill: Color,
    val hi: Color,
    val border: Color,
    val skirt: Color,
    val text: Color
) {
    STD(
        topFill = Color(0xFF2A2B32),
        fill = Color(0xFF1F2026),
        hi = Color(0xFF565864),
        border = Color(0xFF17181D),
        skirt = Color(0xFF0A0A0E),
        text = Cs.Ink
    ),
    DOWN(
        topFill = Color(0xFF16161B),
        fill = Color(0xFF141418),
        hi = Color(0x14FFFFFF),
        border = Color(0x66000000),
        skirt = Color(0xFF08080A),
        text = Cs.White
    ),
    COMMIT(
        topFill = Color(0xFF343742),
        fill = Color(0xFF24262F),
        hi = Color(0xFF7C8296),
        border = Color(0xFF1B1D24),
        skirt = Color(0xFF0A0B0F),
        text = Cs.White
    ),
    VOICE(
        topFill = Color(0xFFDF4822),
        fill = Color(0xFFB33114),
        hi = Color(0xFFFF906E),
        border = Color(0xFF7D210C),
        skirt = Color(0xFF4D1306),
        text = Cs.White
    ),
    REPAIR(
        topFill = Color(0xFF3E2F16),
        fill = Color(0xFF2B200E),
        hi = Color(0xFF9C7630),
        border = Color(0xFF4A3818),
        skirt = Color(0xFF140E05),
        text = Cs.AmberBright
    ),
    ACTIVE(
        topFill = Color(0xFF363842),
        fill = Color(0xFF262830),
        hi = Color(0xFF8A90A4),
        border = Color(0xFF1E2027),
        skirt = Color(0xFF0B0B0F),
        text = Cs.White
    )
}

fun Modifier.neumorphicSurface(
    role: KeyRole,
    pressed: Boolean = false,
    radius: Dp = 12.dp,
    enabled: Boolean = true,
    pip: Boolean = false,
    accentBarColor: Color? = null
): Modifier = drawBehind {
    val r = radius.toPx()
    val corner = CornerRadius(r, r)
    val isSunken = pressed || role == KeyRole.DOWN
    val effectiveRole = if (pressed && role == KeyRole.STD) KeyRole.DOWN else role
    val skirtPx = if (isSunken) 0f else 3.dp.toPx()
    val faceH = (size.height - skirtPx).coerceAtLeast(2f)

    if (!isSunken) {
        // Subtle outer soft elevation shadow + 3dp physical keycap lower skirt (v4 raised depth)
        drawRoundRect(
            color = Color.Black.copy(alpha = if (enabled) 0.60f else 0.25f),
            topLeft = Offset(1.dp.toPx(), 2.5.dp.toPx()),
            size = size,
            cornerRadius = corner
        )
        drawRoundRect(
            color = if (enabled) effectiveRole.skirt else effectiveRole.skirt.copy(alpha = 0.55f),
            topLeft = Offset(0f, 1.dp.toPx()),
            size = Size(size.width, (size.height - 1.dp.toPx()).coerceAtLeast(1f)),
            cornerRadius = corner
        )
    }

    val topCol = if (enabled) effectiveRole.topFill else effectiveRole.topFill.copy(alpha = 0.55f)
    val botCol = if (enabled) effectiveRole.fill else effectiveRole.fill.copy(alpha = 0.55f)
    drawRoundRect(
        brush = Brush.verticalGradient(colors = listOf(topCol, botCol), startY = 0f, endY = faceH),
        topLeft = Offset.Zero,
        size = Size(size.width, faceH),
        cornerRadius = corner
    )

    if (isSunken) {
        val sw = 1.5.dp.toPx()
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.65f),
            topLeft = Offset(sw / 2f, sw / 2f),
            size = Size((size.width - sw).coerceAtLeast(0f), (size.height - sw).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((r - sw / 2f).coerceAtLeast(0f)),
            style = Stroke(width = sw)
        )
        drawLine(
            color = Color.White.copy(alpha = 0.04f),
            start = Offset(r * 0.5f, size.height - 0.75.dp.toPx()),
            end = Offset((size.width - r * 0.5f).coerceAtLeast(r * 0.5f), size.height - 0.75.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
    } else {
        // Crisp top specular bevel highlight + machined perimeter stroke
        val inset = (r * 0.75f).coerceAtMost((size.width / 2f).coerceAtLeast(0f))
        if (size.width - inset > inset) {
            drawLine(
                color = if (enabled) effectiveRole.hi else effectiveRole.hi.copy(alpha = 0.30f),
                start = Offset(inset, 1.dp.toPx()),
                end = Offset(size.width - inset, 1.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
        }
        drawRoundRect(
            color = effectiveRole.border,
            topLeft = Offset.Zero,
            size = Size(size.width, faceH),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx())
        )
        if (pip && enabled) {
            val ps = 3.dp.toPx()
            val pIns = 5.5.dp.toPx()
            drawRect(
                color = Cs.Pip,
                topLeft = Offset(size.width - pIns - ps, pIns),
                size = Size(ps, ps)
            )
        }
    }

    if (accentBarColor != null) {
        val barW = 3.dp.toPx()
        val vInset = (faceH * 0.24f).coerceAtLeast(6.dp.toPx())
        drawRoundRect(
            color = accentBarColor,
            topLeft = Offset(4.dp.toPx(), vInset),
            size = Size(barW, (faceH - 2f * vInset).coerceAtLeast(6f)),
            cornerRadius = CornerRadius(barW / 2f, barW / 2f)
        )
    }
}

/** Small glowing hardware LED indicator dot (Nothing / TE tactile active state). */
@Composable
fun CsLedDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                val r = this.size.minDimension / 2f
                drawCircle(color = color.copy(alpha = 0.35f), radius = r * 1.9f)
                drawCircle(color = color, radius = r)
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = r * 0.36f,
                    center = Offset(center.x - r * 0.22f, center.y - r * 0.22f)
                )
            }
    )
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
            .neumorphicSurface(role = role, pressed = pressed && enabled, radius = radius, enabled = enabled, pip = pip)
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

/** 44dp raised hardware toggle keycap with glowing LED dot when active (zero harsh flat white slabs). */
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
    val role = if (selected) KeyRole.ACTIVE else KeyRole.STD
    val cornerRadius = 11.dp
    Row(
        modifier = modifier
            .height(height)
            .offset(y = if (pressed && enabled) 1.dp else 0.dp)
            .neumorphicSurface(
                role = role,
                pressed = pressed && enabled,
                radius = cornerRadius,
                enabled = enabled,
                pip = !selected
            )
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        val fg = if (!enabled) Cs.Dim else role.text
        if (selected || accentBarColor != null) {
            CsLedDot(
                color = when {
                    accentBarColor != null -> accentBarColor
                    else -> Cs.Mint
                },
                size = 6.5.dp
            )
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(17.dp)
            )
        }
        Text(
            text = label,
            style = mono(13.sp, fg, if (selected) FontWeight.SemiBold else FontWeight.Normal),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Sunken #101013 segmented switch well (radius 14dp, 5dp padding, 6dp gap). */
@Composable
fun CsSegmentedRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 14.dp, fill = Cs.SunkenTrack)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * Individual raised hardware segment inside [CsSegmentedRow].
 * Active navigation segments highlight cleanly with an elevated keycap + subtle crisp white bottom
 * indicator bar (zero misleading red recording lights); optional [ledColor] renders a colored LED only
 * when explicitly requested.
 */
@Composable
fun RowScope.CsSegment(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    enabled: Boolean = true,
    ledColor: Color? = null,
    content: @Composable RowScope.(Color) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val role = if (selected) KeyRole.ACTIVE else KeyRole.STD
    Row(
        modifier = modifier
            .weight(1f)
            .height(height)
            .offset(y = if (pressed && enabled) 1.dp else 0.dp)
            .neumorphicSurface(
                role = role,
                pressed = pressed && enabled,
                radius = 10.dp,
                enabled = enabled,
                pip = !selected
            )
            .drawBehind {
                if (selected && enabled && ledColor == null) {
                    val barW = 20.dp.toPx().coerceAtMost(size.width * 0.45f)
                    val barH = 2.dp.toPx()
                    drawRoundRect(
                        color = Cs.InkBright.copy(alpha = 0.85f),
                        topLeft = Offset((size.width - barW) / 2f, size.height - 6.dp.toPx()),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barH / 2f, barH / 2f)
                    )
                }
            }
            .clip(RoundedCornerShape(10.dp))
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
            selected -> Cs.White
            else -> Cs.InkSub
        }
        if (selected && enabled && ledColor != null) {
            CsLedDot(color = ledColor, size = 6.dp)
        }
        content(tint)
    }
}

/**
 * Rock-solid hold-to-record gesture that consumes down + move pointer events on [PointerEventPass.Initial]
 * so parent scroll containers never hijack the hold and minor thumb drift never ends recording early.
 * Only calls [onRelease] when the tracked finger physically lifts (or the gesture is cancelled by OS).
 */
internal suspend fun PointerInputScope.detectHoldGesture(
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onPress()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val tracked = event.changes.firstOrNull { it.id == down.id }
                if (tracked == null || !tracked.pressed) {
                    tracked?.consume()
                    break
                }
                tracked.consume()
            }
        } finally {
            onRelease()
        }
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
                detectHoldGesture(onPress = { press() }, onRelease = { release() })
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

/**
 * Horizontal `[Mic  HOLD]` soft-neumorphic extruded dock voice key (matching Ryan's uploaded reference):
 * proportional mic icon on the left, short `HOLD` label on the right, inside an etched outer bezel frame.
 */
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
    Row(
        modifier = modifier
            .width(114.dp)
            .height(66.dp)
            .offset(y = if (held) 1.dp else 0.dp)
            .neumorphicDockKey(role = KeyRole.VOICE, pressed = held, radius = 20.dp, enabled = enabled)
            .clip(RoundedCornerShape(20.dp))
            .semantics(mergeDescendants = true) {
                this.role = Role.Button
                onClick(label = label) {
                    if (held) release() else press()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHoldGesture(onPress = { press() }, onRelease = { release() })
            }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (enabled) Cs.White else Cs.Dim
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(21.dp))
        Text(
            text = label,
            style = mono(13.sp, fg, FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Secondary soft-neumorphic extruded dock key (e.g. `pin`, `new idea`, `cancel`, `Tweak w/ AI`). */
@Composable
fun CsDockSideKey(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    horizontal: Boolean = false
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val role = if (active) KeyRole.ACTIVE else KeyRole.STD
    val fg = when {
        !enabled -> Cs.Dim
        active -> Cs.White
        else -> Cs.Ink
    }
    val baseMod = modifier
        .height(66.dp)
        .offset(y = if (pressed && enabled) 1.dp else 0.dp)
        .neumorphicDockKey(role = role, pressed = pressed && enabled, radius = 20.dp, enabled = enabled)
        .clip(RoundedCornerShape(20.dp))
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick
        )

    if (horizontal) {
        Row(
            modifier = baseMod.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Text(
                text = label,
                style = mono(13.sp, fg, FontWeight.Medium),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        Column(
            modifier = baseMod.width(100.dp).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Text(
                text = label,
                style = mono(11.sp, fg, FontWeight.Medium),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Sunken 66dp progress box shown in place of the primary dock key while a real job runs.
 * Displays the `libraries.dev/orbs` "solving" dotted sphere loader alongside the live status & real %.
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
            .height(66.dp)
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
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ThinkingOrbSolving(size = 32.dp, tint = Cs.White)
            Text(
                text = label,
                style = mono(14.sp, Cs.White, FontWeight.Medium),
                maxLines = 2,
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
 * Standard v5+ bottom dock using Ryan's soft-neumorphic extruded etched-frame style:
 * dark satin-graphite primary key with bare trailing arrow/check (no harsh flat white slabs, no arrow circles),
 * replaced in place by [CsBusyBox] (`ThinkingOrbSolving`) while a real job runs.
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
            .padding(start = 10.dp, top = 12.dp, end = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val fg = if (primaryEnabled) primaryRole.text else Cs.Dim
            val ledCol = when (primaryRole) {
                KeyRole.REPAIR -> Cs.AmberBright
                KeyRole.VOICE -> Cs.VoiceHi
                else -> Cs.Mint
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(66.dp)
                    .offset(y = if (pressed && primaryEnabled) 1.dp else 0.dp)
                    .neumorphicDockKey(
                        role = primaryRole,
                        pressed = pressed && primaryEnabled,
                        radius = 20.dp,
                        enabled = primaryEnabled
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = primaryEnabled,
                        role = Role.Button,
                        onClick = onPrimaryClick
                    )
                    .padding(horizontal = 20.dp)
                    .testTag(primaryTag),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (primaryEnabled) {
                    CsLedDot(color = ledCol, size = 7.dp)
                }
                if (primaryLeadingIcon != null) {
                    Icon(
                        imageVector = primaryLeadingIcon,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = primaryLabel,
                    style = mono(14.5.sp, fg, FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (primaryTrailingIcon != null) {
                    Icon(
                        imageVector = primaryTrailingIcon,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

// =============================================================================
// Tiny 4-LED pipeline status cluster (RIG · VOX · HEAD · CUT)
// =============================================================================

enum class LedState { IDLE, WORKING, READY, ERROR }

data class PipelineLedState(
    val rig: LedState = LedState.IDLE,
    val voice: LedState = LedState.IDLE,
    val head: LedState = LedState.IDLE,
    val cut: LedState = LedState.IDLE,
)

private fun ledColor(state: LedState): Color = when (state) {
    LedState.READY -> Cs.Mint
    LedState.WORKING -> Cs.AmberBright
    LedState.ERROR -> Cs.Red
    LedState.IDLE -> Cs.Dim
}

/** Four 6dp hardware LED pips with micro-labels: green=ready, amber=working, red=error, dim=idle. */
@Composable
fun PipelineStatusLeds(state: PipelineLedState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.testTag("cs_pipeline_leds"),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LedPip("RIG", state.rig)
        LedPip("VOX", state.voice)
        LedPip("HEAD", state.head)
        LedPip("CUT", state.cut)
    }
}

@Composable
private fun LedPip(label: String, state: LedState) {
    val color = ledColor(state)
    val active = state != LedState.IDLE
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
                .then(
                    if (active) Modifier.border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(4.dp))
                    else Modifier
                )
        )
        Text(
            text = label,
            style = mono(7.sp, if (active) Cs.Muted else Cs.Dim),
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Consistent 56dp top bar across the streamlined 2-stage flow (`01 SPARK` · `02 CUT`):
 * active stage is a 44dp sunken segment in Ndot 13sp; other stage is a 40dp target in Geist Mono.
 */
@Composable
fun CsTopBar(
    activeStage: Int,
    onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    leds: PipelineLedState? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val normalizedActive = if (activeStage >= 2) 2 else 1
    val stageNames = listOf("SPARK", "CUT")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        stageNames.forEachIndexed { idx, name ->
            val stageNum = idx + 1
            val isActive = stageNum == normalizedActive
            if (isActive) {
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .defaultMinSize(minWidth = 40.dp)
                        .neumorphicSurface(role = KeyRole.DOWN, pressed = true, radius = 12.dp, enabled = true)
                        .clip(RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp)
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
                val color = if (stageNum < normalizedActive) Cs.Ink else Cs.Dim
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .defaultMinSize(minWidth = 34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Tab) { onNavigate(stageNum) }
                        .padding(horizontal = 4.dp)
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
        if (leds != null) {
            PipelineStatusLeds(state = leds, modifier = Modifier.padding(end = 4.dp))
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing
        )
    }
}

/**
 * Floating high-contrast top notification banner (distinct frosted ivory surface anchored below
 * the top bar so it never blends in with dark charcoal buttons) with auto-dismiss + tap-to-close.
 */
@Composable
fun BoxScope.CsToastOverlay(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.isNullOrBlank()) return
    val currentDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(3_000L)
        currentDismiss()
    }
    Row(
        modifier = modifier
            .align(Alignment.TopCenter)
            .padding(start = 14.dp, top = 60.dp, end = 14.dp)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF3F4F8))
            .border(1.dp, Color(0xFFD0D4DE), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onDismiss)
            .padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
            .testTag("cs_toast"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF10B981))
        )
        Text(
            text = message,
            style = sans(13.5.sp, Color(0xFF111218), 18.sp, FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF191B24))
                .clickable(role = Role.Button, onClick = onDismiss)
                .padding(horizontal = 10.dp)
                .testTag("cs_toast_ok"),
            contentAlignment = Alignment.Center
        ) {
            Text("ok", style = mono(11.5.sp, Color(0xFFF3F4F8), FontWeight.SemiBold), maxLines = 1)
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

/**
 * Restored v4 old-school analog radio/camera drag tick ruler with glowing red needle!
 * Supports smooth horizontal drag scrubbing plus left/right tap stepping (`${tag}_dec` / `${tag}_inc`).
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
    val clampedNorm = normalizedValue.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .sunkenPanel(radius = 12.dp, fill = Cs.SunkenTrack)
            .drawBehind {
                val padX = 14.dp.toPx()
                val tx0 = padX
                val tx1 = (size.width - padX).coerceAtLeast(tx0 + 1f)
                val baseY = size.height - 5.dp.toPx()
                val totalTicks = 40
                for (i in 0..totalTicks) {
                    val x = tx0 + (tx1 - tx0) * (i.toFloat() / totalTicks.toFloat())
                    val isCenter = i == totalTicks / 2
                    val isMajor = i % 5 == 0
                    val tickH = when {
                        isCenter -> 15.dp.toPx()
                        isMajor -> 11.dp.toPx()
                        else -> 6.dp.toPx()
                    }
                    val tickCol = when {
                        !enabled -> Cs.Dim.copy(alpha = 0.5f)
                        isCenter -> Cs.Ink
                        isMajor -> Cs.Muted
                        else -> Cs.Hairline
                    }
                    drawLine(
                        color = tickCol,
                        start = Offset(x, baseY - tickH),
                        end = Offset(x, baseY),
                        strokeWidth = if (isCenter || isMajor) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                }
                // Glowing red analog needle + top pointer triangle at normalizedValue!
                val nx = tx0 + (tx1 - tx0) * clampedNorm
                val needleCol = if (enabled) Cs.Red else Cs.Dim
                if (enabled) {
                    drawRect(
                        color = Cs.Red.copy(alpha = 0.28f),
                        topLeft = Offset(nx - 4.5.dp.toPx(), baseY - 22.dp.toPx()),
                        size = Size(9.dp.toPx(), 23.dp.toPx())
                    )
                }
                drawLine(
                    color = needleCol,
                    start = Offset(nx, baseY - 21.dp.toPx()),
                    end = Offset(nx, baseY + 1.dp.toPx()),
                    strokeWidth = 2.5.dp.toPx()
                )
                val tri = Path().apply {
                    moveTo(nx, baseY - 19.dp.toPx())
                    lineTo(nx - 4.dp.toPx(), baseY - 25.dp.toPx())
                    lineTo(nx + 4.dp.toPx(), baseY - 25.dp.toPx())
                    close()
                }
                drawPath(path = tri, color = needleCol)
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
                .padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = SectionLabelStyle, maxLines = 1)
            Text(
                valueText,
                style = mono(13.sp, if (enabled) Cs.White else Cs.Dim, FontWeight.SemiBold),
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

/** Parses a persisted 6-digit hex accent string (e.g. `#D71921`) into a Compose [Color]. */
internal fun parseAccentHex(hex: String?): Color {
    val clean = hex.orEmpty().trim().removePrefix("#")
    return runCatching {
        if (clean.length == 6) Color((0xFF000000L or clean.toLong(16)).toInt()) else Cs.Red
    }.getOrDefault(Cs.Red)
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
