package com.example.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================================================
// Aircheck design v2 components (docs/DESIGN_SYSTEM.md, approved by Ryan 2026-11-27).
// Every screen, sheet and button uses ONLY these. New components need Ryan's approval.
// =============================================================================

/** Key sizes from the rule book: regular 46dp (icon 54dp), small 38dp (icon 40dp). */
enum class AcKeySize(val height: Dp, val iconKey: Dp, val glyph: Dp, val padding: Dp) {
    Regular(height = 46.dp, iconKey = 54.dp, glyph = 20.dp, padding = 18.dp),
    Small(height = 38.dp, iconKey = 40.dp, glyph = 18.dp, padding = 14.dp)
}

/**
 * Collects press-down interactions into a haptic keyboard tick and returns the pressed state.
 * Every key in the design system goes through this.
 */
@Composable
private fun rememberAcPressed(interaction: MutableInteractionSource, enabled: Boolean): Boolean {
    val haptic = LocalHapticFeedback.current
    val isEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            if (event is PressInteraction.Press && isEnabled) {
                haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            }
        }
    }
    val pressed by interaction.collectIsPressedAsState()
    return pressed && enabled
}

/** Sinking press transform shared by all keys: 1–2dp down, scale 0.98. */
private fun Modifier.acSink(pressed: Boolean, depth: Dp): Modifier = graphicsLayer {
    if (pressed) {
        translationY = depth.toPx()
        scaleX = 0.98f
        scaleY = 0.98f
    }
}

/** Soft neumorphic raise used by app keys and cards: light top-left, grey bottom-right. */
private fun Modifier.acRaised(shape: Shape, red: Boolean = false, soft: Boolean = false): Modifier {
    val lightAlpha = if (red) 0.8f else if (soft) 0.85f else 0.9f
    val dark = if (red) AcTokens.RedLo.copy(alpha = 0.45f) else AcTokens.Ink2.copy(alpha = if (soft) 0.28f else 0.35f)
    return this
        .dropShadow(shape, Shadow(radius = if (soft) 8.dp else 9.dp, color = Color.White.copy(alpha = lightAlpha), offset = DpOffset((-4).dp, (-4).dp)))
        .dropShadow(shape, Shadow(radius = 12.dp, color = dark, offset = DpOffset(if (soft) 4.dp else 5.dp, if (soft) 5.dp else 6.dp)))
}

/** Inset press shadow used by app keys while held. */
private fun Modifier.acInset(shape: Shape, red: Boolean = false): Modifier = this
    .innerShadow(shape, Shadow(radius = 7.dp, color = if (red) AcTokens.RedLo.copy(alpha = 0.75f) else AcTokens.Ink2.copy(alpha = 0.35f), offset = DpOffset(3.dp, 3.dp)))
    .innerShadow(shape, Shadow(radius = 7.dp, color = if (red) AcTokens.RedHi.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.9f), offset = DpOffset((-3).dp, (-3).dp)))

// -----------------------------------------------------------------------------
// 1. AcKey — app control
// -----------------------------------------------------------------------------

/**
 * App control key: white `ui` surface, fully round, raised. `selected = true` turns it red with
 * white content. Presses sink with a haptic tick. No LEDs, no outlines.
 *
 * This is the slot form; [content] receives the content color to tint text and icons with.
 * [circle] makes a circular icon key of [AcKeySize.iconKey] diameter.
 */
@Composable
fun AcKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: AcKeySize = AcKeySize.Regular,
    circle: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    contentPadding: Dp? = null,
    role: Role = Role.Button,
    content: @Composable RowScope.(contentColor: Color) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberAcPressed(interaction, enabled)
    val shape = CircleShape
    val sizeMod = if (circle) {
        Modifier.size(size.iconKey)
    } else {
        Modifier.heightIn(min = size.height).widthIn(min = size.height)
    }
    val fg = when {
        !enabled -> AcTokens.Ink2
        selected -> AcTokens.Ui
        else -> AcTokens.Ink
    }
    // Tag, click and semantics live on the outer 48dp touch target so a key's testTag is the
    // clickable node even when the key sits inside another clickable container.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = role,
                onClick = onClick
            )
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .then(sizeMod)
                .acSink(pressed, 1.5.dp)
                .then(if (pressed) Modifier else Modifier.acRaised(shape, red = selected))
                .clip(shape)
                .background(if (selected) AcTokens.Red else AcTokens.Ui)
                .then(if (pressed) Modifier.acInset(shape, red = selected) else Modifier)
                .padding(horizontal = if (circle) 0.dp else (contentPadding ?: size.padding))
                .alpha(if (enabled) 1f else 0.5f),
            horizontalArrangement = if (circle) Arrangement.Center else horizontalArrangement,
            verticalAlignment = Alignment.CenterVertically
        ) {
            content(fg)
        }
    }
}

/** Text (and optional icon) pill key. */
@Composable
fun AcKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: AcKeySize = AcKeySize.Regular,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    maxLines: Int = 1,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    role: Role = Role.Button
) {
    AcKey(
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        enabled = enabled,
        size = size,
        horizontalArrangement = horizontalArrangement,
        role = role
    ) { fg ->
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size.glyph))
        }
        Text(
            text = label,
            style = AcType.Label.copy(color = fg),
            maxLines = maxLines,
            softWrap = maxLines > 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (trailingIcon != null) Modifier.weight(1f, fill = false) else Modifier
        )
        if (trailingIcon != null) {
            Icon(trailingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(size.glyph))
        }
    }
}

/**
 * Circular icon key. [badgeColor] draws a small status dot at the top-right of the key (e.g. the
 * rig connection on the Cut settings key: ink = online, red = offline); null = no dot.
 */
@Composable
fun AcKey(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: AcKeySize = AcKeySize.Regular,
    badgeColor: Color? = null
) {
    Box(contentAlignment = Alignment.Center) {
        AcKey(
            onClick = onClick,
            modifier = modifier,
            selected = selected,
            enabled = enabled,
            size = size,
            circle = true
        ) { fg ->
            Icon(icon, contentDescription = contentDescription, tint = fg, modifier = Modifier.size(size.glyph + 2.dp))
        }
        if (badgeColor != null) {
            val ring = if (selected) AcTokens.Red else AcTokens.Ui
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(start = size.iconKey * 0.62f, bottom = size.iconKey * 0.62f)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(ring)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 1b. AcVideoOverlayKey — controls overlaid on video are FLAT
// -----------------------------------------------------------------------------

/**
 * A control drawn over video (e.g. play on the Cut player). Flat: translucent dark disc, white
 * glyph, no elevation, raise or skirt. The one exception to raised keys (approved by Ryan
 * 2026-11-28). Use only on top of video; everywhere else use [AcKey] / [AcMediaKey].
 */
@Composable
fun AcVideoOverlayKey(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 52.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberAcPressed(interaction, enabled)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .graphicsLayer { if (pressed) { scaleX = 0.96f; scaleY = 0.96f } }
            .clip(CircleShape)
            .background(AcTokens.Display.copy(alpha = if (pressed) 0.62f else 0.42f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = AcTokens.Ui, modifier = Modifier.size(size * 0.5f))
    }
}

// -----------------------------------------------------------------------------
// 2. AcPrimaryKey — the one raised red action per screen
// -----------------------------------------------------------------------------

private val PrimaryLabelStyle = AcType.Title.copy(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)

/**
 * The one main action per screen: red gradient, raised on a red-lo skirt with a drop shadow,
 * white icon + Inter 600 label. Sinks onto its skirt when pressed, with a haptic tick.
 */
@Composable
fun AcPrimaryKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberAcPressed(interaction, enabled)
    val shape = RoundedCornerShape(percent = 50)
    val skirt = 5.dp
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .height(58.dp + skirt)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(bottom = skirt)
            .alpha(if (enabled) 1f else 0.45f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .graphicsLayer {
                    translationY = if (pressed) with(density) { 4.dp.toPx() } else 0f
                    if (pressed) { scaleX = 0.98f; scaleY = 0.98f }
                }
                .then(
                    if (pressed) Modifier else Modifier
                        .dropShadow(shape, Shadow(radius = 22.dp, color = AcTokens.RedLo.copy(alpha = 0.35f), offset = DpOffset(0.dp, 14.dp)))
                        .drawBehind {
                            val r = size.height / 2f
                            drawRoundRect(
                                color = AcTokens.RedLo,
                                topLeft = Offset(0f, skirt.toPx()),
                                size = Size(size.width, size.height),
                                cornerRadius = CornerRadius(r, r)
                            )
                        }
                )
                .clip(shape)
                .background(Brush.verticalGradient(0f to AcTokens.RedHi, 0.6f to AcTokens.Red, 1f to AcTokens.Red))
                .then(
                    if (pressed) {
                        Modifier.innerShadow(shape, Shadow(radius = 6.dp, color = AcTokens.RedLo.copy(alpha = 0.8f), offset = DpOffset(0.dp, 2.dp)))
                    } else {
                        Modifier.innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.35f), offset = DpOffset(0.dp, 1.dp)))
                    }
                )
                .padding(horizontal = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = AcTokens.Ui, modifier = Modifier.size(20.dp))
            }
            Text(
                text = label,
                style = PrimaryLabelStyle.copy(color = AcTokens.Ui),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// -----------------------------------------------------------------------------
// 3. AcMediaWell + AcMediaKey — controls that edit or play media
// -----------------------------------------------------------------------------

/** Black well (radius 14, inner shadow) holding [AcMediaKey]s. */
@Composable
fun AcMediaWell(
    modifier: Modifier = Modifier,
    padding: Dp = 8.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .dropShadow(shape, Shadow(radius = 0.dp, color = AcTokens.ChassisHi, offset = DpOffset(0.dp, 1.dp)))
            .clip(shape)
            .background(AcTokens.Well)
            .innerShadow(shape, Shadow(radius = 6.dp, color = AcTokens.Well.copy(alpha = 0.9f), offset = DpOffset(0.dp, 2.dp)))
            .padding(padding),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

private val MediaCaptionStyle = AcType.Caption.copy(fontSize = 8.5.sp, letterSpacing = 0.06.sp, color = AcTokens.OpGrey)

@Composable
private fun AcMediaKeyFace(
    modifier: Modifier,
    pressed: Boolean,
    size: Dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .heightIn(min = size)
            .widthIn(min = size)
            .acSink(pressed, 2.dp)
            .then(
                if (pressed) Modifier else Modifier
                    .dropShadow(shape, Shadow(radius = 10.dp, color = AcTokens.Well.copy(alpha = 0.6f), offset = DpOffset(0.dp, 6.dp)))
                    .dropShadow(shape, Shadow(radius = 0.dp, color = AcTokens.MediaKeyLo, offset = DpOffset(0.dp, 3.dp)))
            )
            .clip(shape)
            .background(Brush.verticalGradient(0f to AcTokens.MediaKeyHi, 0.45f to AcTokens.MediaKey, 1f to AcTokens.MediaKey))
            .then(
                if (pressed) {
                    Modifier.innerShadow(shape, Shadow(radius = 5.dp, color = AcTokens.Well.copy(alpha = 0.7f), offset = DpOffset(0.dp, 2.dp)))
                } else {
                    Modifier
                        .innerShadow(shape, Shadow(radius = 0.dp, color = Color.White.copy(alpha = 0.14f), offset = DpOffset(0.dp, 1.dp)))
                        .innerShadow(shape, Shadow(radius = 0.dp, color = AcTokens.Well.copy(alpha = 0.35f), offset = DpOffset(0.dp, (-2).dp)))
                }
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun BoxScope.AcMediaKeyContent(
    icon: ImageVector?,
    label: String?,
    contentDescription: String?,
    active: Boolean,
    enabled: Boolean,
    toggle: Boolean?,
    caption: String?
) {
    val glyph = when {
        !enabled -> AcTokens.OpGrey
        active -> AcTokens.RedHi
        else -> AcTokens.Ui
    }
    if (toggle != null) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(6.dp)
                .then(
                    if (toggle) Modifier.dropShadow(CircleShape, Shadow(radius = 6.dp, color = AcTokens.RedHi))
                    else Modifier
                )
                .clip(CircleShape)
                .background(if (toggle) AcTokens.RedHi else AcTokens.MediaKeyLo)
        )
    }
    Column(
        modifier = Modifier.padding(horizontal = if (label != null) 14.dp else 0.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = contentDescription, tint = glyph, modifier = Modifier.size(24.dp))
            }
            if (label != null) {
                Text(label, style = AcType.Label.copy(color = glyph, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (caption != null) {
            Text(caption, style = MediaCaptionStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Dark square media key (radius 12) for controls that edit or play media. White glyph;
 * `active = true` turns the glyph red. [toggle] non-null shows an on/off LED (only for on/off
 * controls). [caption] is a tiny mono caption under the glyph. [label] makes a wide key.
 */
@Composable
fun AcMediaKey(
    icon: ImageVector?,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    toggle: Boolean? = null,
    caption: String? = null,
    label: String? = null,
    size: Dp = 62.dp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberAcPressed(interaction, enabled)
    AcMediaKeyFace(
        modifier = modifier
            .semantics { if (toggle != null) this.selected = toggle }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        pressed = pressed,
        size = size
    ) {
        AcMediaKeyContent(icon, label, contentDescription, active, enabled, toggle, caption)
    }
}

/**
 * Press-and-hold media key (e.g. hold to record). [onPress] fires on finger down and
 * [onRelease] on lift or cancel; the accessibility click toggles press then release.
 */
@Composable
fun AcMediaKey(
    icon: ImageVector?,
    contentDescription: String?,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    held: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    enabled: Boolean = true,
    caption: String? = null,
    label: String? = null,
    size: Dp = 62.dp,
    semanticsLabel: String? = null
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val haptic = LocalHapticFeedback.current
    AcMediaKeyFace(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                this.role = Role.Button
                onClick(label = semanticsLabel ?: label ?: contentDescription) {
                    if (held) release() else press()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectAcHoldGesture(
                    onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        press()
                    },
                    onRelease = { release() }
                )
            },
        pressed = held && enabled,
        size = size
    ) {
        AcMediaKeyContent(icon, label, contentDescription, active, enabled, null, caption)
    }
}

/**
 * Hold gesture that consumes down + move events on [PointerEventPass.Initial] so parent scroll
 * containers never hijack the hold. Calls [onRelease] only when the tracked finger lifts or the
 * gesture is cancelled.
 */
suspend fun PointerInputScope.detectAcHoldGesture(
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

// -----------------------------------------------------------------------------
// 4. AcDisplay — black OP-1 panel for media
// -----------------------------------------------------------------------------

/**
 * Black rounded display for media: video player, waveform, timeline, meters, timecode.
 * Draw graphics inside in 1–1.5dp thin lines with the op* tokens; numbers in [AcType.Numeral].
 */
@Composable
fun AcDisplay(
    modifier: Modifier = Modifier,
    radius: Dp = 12.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(AcTokens.Display)
            .innerShadow(shape, Shadow(radius = 8.dp, color = AcTokens.Well.copy(alpha = 0.9f), offset = DpOffset(0.dp, 2.dp))),
        contentAlignment = contentAlignment,
        content = content
    )
}

// -----------------------------------------------------------------------------
// 5. AcCard
// -----------------------------------------------------------------------------

/**
 * `ui` surface card, radius 20, soft raised shadow. Optional Caption [tag] on top; put Body
 * text in [content]. No accent bars, no colored borders. With [onClick] it presses like a key.
 */
@Composable
fun AcCard(
    modifier: Modifier = Modifier,
    tag: String? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(6.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberAcPressed(interaction, onClick != null)
    Column(
        modifier = modifier
            .acSink(pressed, 1.dp)
            .then(if (pressed) Modifier else Modifier.acRaised(shape, soft = true))
            .clip(shape)
            .background(AcTokens.Ui)
            .then(if (pressed) Modifier.acInset(shape) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(contentPadding),
        verticalArrangement = verticalArrangement
    ) {
        if (tag != null) {
            Text(tag.uppercase(), style = AcType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        content()
    }
}

// -----------------------------------------------------------------------------
// 6. AcField — recessed text field
// -----------------------------------------------------------------------------

private val FieldTextStyle = AcType.Body.copy(fontSize = 16.sp, lineHeight = 22.sp)

/** Recessed well text field (inset shadow), Inter 16. Replaces Material OutlinedTextField. */
@Composable
fun AcField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = FieldTextStyle,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        textStyle = textStyle.copy(color = if (enabled) AcTokens.Ink else AcTokens.Ink2),
        cursorBrush = SolidColor(AcTokens.Red),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(AcTokens.Chassis)
                    .innerShadow(shape, Shadow(radius = 6.dp, color = AcTokens.Ink2.copy(alpha = 0.35f), offset = DpOffset(2.dp, 3.dp)))
                    .innerShadow(shape, Shadow(radius = 5.dp, color = Color.White.copy(alpha = 0.8f), offset = DpOffset((-2).dp, (-2).dp)))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = textStyle.copy(color = AcTokens.Ink2))
                    }
                    inner()
                }
                trailing?.invoke(this)
            }
        }
    )
}

/** [AcField] backed by a [TextFieldState] (for fields observed with snapshotFlow). */
@Composable
fun AcField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    textStyle: TextStyle = FieldTextStyle,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        state = state,
        modifier = modifier,
        enabled = enabled,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minLines, maxLines),
        keyboardOptions = keyboardOptions,
        textStyle = textStyle.copy(color = if (enabled) AcTokens.Ink else AcTokens.Ink2),
        cursorBrush = SolidColor(AcTokens.Red),
        decorator = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(AcTokens.Chassis)
                    .innerShadow(shape, Shadow(radius = 6.dp, color = AcTokens.Ink2.copy(alpha = 0.35f), offset = DpOffset(2.dp, 3.dp)))
                    .innerShadow(shape, Shadow(radius = 5.dp, color = Color.White.copy(alpha = 0.8f), offset = DpOffset((-2).dp, (-2).dp)))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    if (state.text.isEmpty() && placeholder != null) {
                        Text(placeholder, style = textStyle.copy(color = AcTokens.Ink2))
                    }
                    inner()
                }
                trailing?.invoke(this)
            }
        }
    )
}

// -----------------------------------------------------------------------------
// 7. AcPanel — chassis grouping panel with bevel
// -----------------------------------------------------------------------------

/**
 * Chassis-colored grouping panel with a bevel (Recorder body). [raised] lifts it off the screen
 * with a soft drop shadow, as for the Recorder block on Spark.
 */
@Composable
fun AcPanel(
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(10.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(10.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .then(
                if (raised) Modifier.dropShadow(shape, Shadow(radius = 20.dp, color = AcTokens.Ink2.copy(alpha = 0.3f), offset = DpOffset(0.dp, 10.dp)))
                else Modifier
            )
            .clip(shape)
            .background(AcTokens.Chassis)
            .innerShadow(shape, Shadow(radius = 0.dp, color = AcTokens.ChassisHi, offset = DpOffset(0.dp, 1.dp)))
            .innerShadow(shape, Shadow(radius = 0.dp, color = AcTokens.ChassisLo, offset = DpOffset(0.dp, (-1).dp)))
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}
