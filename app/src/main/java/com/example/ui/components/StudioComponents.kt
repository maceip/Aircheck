package com.example.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.StudioTab
import com.example.ui.theme.ConsoleObsidian
import com.example.ui.theme.GraphiteHairline
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp

/**
 * Geometric Triangle + Broadcast Mic emblem mirroring the uploaded Aircheck puck mark.
 */
@Composable
fun AircheckBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    accentColor: Color = LocalAircheckTokens.current.channelAccent
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val triPath = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            lineTo(w * 0.94f, h * 0.88f)
            lineTo(w * 0.06f, h * 0.88f)
            close()
        }
        drawPath(
            path = triPath,
            color = accentColor,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round)
        )
        // Minimal studio microphone silhouette centered inside triangle
        drawRoundRect(
            color = accentColor,
            topLeft = Offset(w * 0.43f, h * 0.38f),
            size = Size(w * 0.14f, h * 0.24f),
            cornerRadius = CornerRadius(w * 0.07f, w * 0.07f)
        )
        drawArc(
            color = accentColor,
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(w * 0.36f, h * 0.47f),
            size = Size(w * 0.28f, h * 0.21f),
            style = Stroke(width = w * 0.055f, cap = StrokeCap.Round)
        )
        drawLine(
            color = accentColor,
            start = Offset(w * 0.5f, h * 0.68f),
            end = Offset(w * 0.5f, h * 0.78f),
            strokeWidth = w * 0.055f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Uniform 52.dp top bar shared across every screen: Brand mark + Title on left,
 * max 2 clean 48.dp icon triggers on right (System Deck & Account). Zero step badges or clutter.
 */
@Composable
fun StudioTopBar(
    title: String,
    isSignedIn: Boolean,
    onOpenSystemDeck: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OledVoid)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AircheckBrandMark(size = 24.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onOpenSystemDeck,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("top_bar_system_deck_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.cd_open_system_deck),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }
            Box(contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = onOpenAccount,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("top_bar_account_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = stringResource(R.string.cd_open_account),
                        tint = if (isSignedIn) tokens.channelAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                if (isSignedIn) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 11.dp, end = 11.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(tokens.statusLive)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(tokens.hairline)
        )
    }
}

/**
 * Uniform 8.dp OLED studio card with 1.dp graphite border and optional active-channel
 * streaming shimmer across the entire card surface.
 */
@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    shimmering: Boolean = false,
    highlighted: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val tokens = LocalAircheckTokens.current
    val shape = RoundedCornerShape(8.dp)

    val shimmerProgress = if (shimmering) {
        val transition = rememberInfiniteTransition(label = "card_shimmer")
        val anim by transition.animateFloat(
            initialValue = -0.6f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1250, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer_x"
        )
        anim
    } else {
        -1f
    }

    val borderColor = when {
        shimmering -> tokens.channelAccent.copy(alpha = 0.78f)
        highlighted -> tokens.channelAccent.copy(alpha = 0.55f)
        else -> tokens.hairline
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(ConsoleObsidian)
            .border(1.dp, borderColor, shape)
            .semantics {
                stateDescription = if (shimmering) "Shimmering" else "Idle"
            }
            .drawWithContent {
                drawContent()
                if (shimmering && shimmerProgress >= -0.6f) {
                    val sweepW = size.width * 0.55f
                    val startX = (size.width + sweepW) * shimmerProgress - sweepW
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                tokens.channelAccent.copy(alpha = 0.14f),
                                tokens.channelAccent.copy(alpha = 0.24f),
                                tokens.channelAccent.copy(alpha = 0.14f),
                                Color.Transparent
                            ),
                            start = Offset(startX, 0f),
                            end = Offset(startX + sweepW, size.height)
                        )
                    )
                }
            }
            .padding(contentPadding),
        content = content
    )
}

/**
 * Glossy silver-white beveled hardware broadcast key matching the uploaded neumorphic icon sheet:
 * Metallic bevel frame + recessed pitch-black OLED icon screen + radial glowing LED status pip.
 */
@Composable
fun HardwareKeyButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ledColor: Color? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    iconTint: Color = StudioCrisp,
    label: String? = null,
    width: Dp = 64.dp,
    height: Dp = 48.dp
) {
    val tokens = LocalAircheckTokens.current
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 620f),
        label = "hardware_key_scale"
    )
    val effectiveLed = ledColor ?: if (active) tokens.channelAccent else null

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (enabled) 1f else 0.45f
                }
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            tokens.hardwareTop,
                            tokens.hardwareBase,
                            tokens.hardwareShadow
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.9f), Color(0xFF5D6572))
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
                .semantics { this.contentDescription = contentDescription }
                .padding(5.dp)
        ) {
            // Recessed pitch-black OLED icon well
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(7.dp))
                    .background(tokens.hardwareWell)
                    .border(
                        width = 1.dp,
                        color = if (active) tokens.channelAccent.copy(alpha = 0.65f) else Color(0xFF1D222B),
                        shape = RoundedCornerShape(7.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (active) tokens.channelAccent else iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Radial glowing LED status pip on bottom-right corner
            if (effectiveLed != null) {
                Canvas(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(10.dp)
                ) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(effectiveLed.copy(alpha = 0.55f), Color.Transparent)
                        ),
                        radius = size.minDimension * 0.72f
                    )
                    drawCircle(
                        color = effectiveLed,
                        radius = size.minDimension * 0.30f
                    )
                }
            }
        }

        if (!label.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) tokens.channelAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Uniform 50.dp full-width primary action button pinned above the hardware dock.
 */
@Composable
fun StudioPrimaryCta(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val tokens = LocalAircheckTokens.current
    val view = LocalView.current
    Button(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        },
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = tokens.channelAccent,
            contentColor = tokens.channelAccentInk,
            disabledContainerColor = tokens.channelAccent.copy(alpha = 0.25f),
            disabledContentColor = tokens.channelAccentInk.copy(alpha = 0.5f)
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Single-row glossy silver-white tactile hardware pill dock (Script · Stage · Review).
 */
@Composable
fun StudioDockBar(
    selectedTab: StudioTab,
    onSelectTab: (StudioTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val view = LocalView.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(OledVoid)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(tokens.hardwareTop, tokens.hardwareBase, tokens.hardwareShadow)
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.92f), Color(0xFF5D6572))
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StudioTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                val icon = when (tab) {
                    StudioTab.SCRIPT -> Icons.Filled.Edit
                    StudioTab.PHOTO -> Icons.Filled.PhotoCamera
                    StudioTab.CLIPS -> Icons.Filled.VideoLibrary
                }
                val ledColor = when {
                    !isSelected -> Color(0xFF3A414E)
                    tab == StudioTab.SCRIPT -> tokens.channelAccent
                    tab == StudioTab.PHOTO -> tokens.statusStandby
                    else -> tokens.statusLive
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.hardwareWell)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) tokens.channelAccent.copy(alpha = 0.72f) else Color(0xFF1F242E),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable(role = Role.Tab) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onSelectTab(tab)
                        }
                        .testTag("dock_tab_${tab.name.lowercase()}"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = tab.label,
                            tint = if (isSelected) tokens.channelAccent else StudioCrisp.copy(alpha = 0.78f),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) StudioCrisp else StudioCrisp.copy(alpha = 0.68f),
                            maxLines = 1
                        )
                    }
                    // LED tally dot
                    Canvas(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 6.dp, bottom = 5.dp)
                            .size(7.dp)
                    ) {
                        if (isSelected) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(ledColor.copy(alpha = 0.55f), Color.Transparent)
                                ),
                                radius = size.minDimension * 0.95f
                            )
                        }
                        drawCircle(color = ledColor, radius = size.minDimension * 0.36f)
                    }
                }
            }
        }
    }
}

/**
 * Adaptive tablet/foldable left navigation rail with the same glossy hardware well aesthetic.
 */
@Composable
fun StudioNavigationRail(
    selectedTab: StudioTab,
    onSelectTab: (StudioTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(88.dp)
            .background(OledVoid)
            .border(width = 1.dp, color = GraphiteHairline)
            .padding(vertical = 16.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StudioTab.entries.forEach { tab ->
            val icon = when (tab) {
                StudioTab.SCRIPT -> Icons.Filled.Edit
                StudioTab.PHOTO -> Icons.Filled.PhotoCamera
                StudioTab.CLIPS -> Icons.Filled.VideoLibrary
            }
            val selected = tab == selectedTab
            HardwareKeyButton(
                icon = icon,
                contentDescription = tab.label,
                onClick = { onSelectTab(tab) },
                active = selected,
                ledColor = if (selected) tokens.channelAccent else null,
                label = tab.label,
                width = 64.dp,
                height = 50.dp,
                modifier = Modifier.testTag("rail_tab_${tab.name.lowercase()}")
            )
        }
    }
}

/**
 * Real decoded RIFF PCM waveform renderer. Never synthesizes fake peaks:
 * when peaks is empty, renders a clean quiet studio baseline.
 */
@Composable
fun TelemetryWaveform(
    peaks: List<Float>,
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    isRecording: Boolean = false
) {
    val tokens = LocalAircheckTokens.current
    val activeColor = if (isRecording) tokens.statusRec else tokens.waveformColor
    val dimColor = tokens.hairline

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF06080C))
            .border(1.dp, tokens.hairline, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        val midY = size.height * 0.5f
        // Center zero-crossing reference line
        drawLine(
            color = dimColor.copy(alpha = 0.7f),
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1.dp.toPx()
        )

        if (peaks.isEmpty()) return@Canvas

        val count = peaks.size
        val step = size.width / count.toFloat()
        val barWidth = (step * 0.62f).coerceIn(2.dp.toPx(), 6.dp.toPx())
        val playX = (progress.coerceIn(0f, 1f)) * size.width

        for (i in 0 until count) {
            val amp = peaks[i].coerceIn(0.05f, 1f)
            val halfH = (amp * size.height * 0.44f).coerceAtLeast(2.dp.toPx())
            val x = i * step + step * 0.5f
            val passed = isRecording || x <= playX || progress <= 0f
            drawLine(
                color = if (passed) activeColor else activeColor.copy(alpha = 0.32f),
                start = Offset(x, midY - halfH),
                end = Offset(x, midY + halfH),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
        }

        // Playhead hairline
        if (progress > 0f && !isRecording) {
            drawLine(
                color = tokens.channelAccent,
                start = Offset(playX, 0f),
                end = Offset(playX, size.height),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

/**
 * 45° Hazard Signal Orange diagonal cut overlay for muted/cut script beats.
 */
@Composable
fun HazardCutOverlay(modifier: Modifier = Modifier) {
    val hazard = LocalAircheckTokens.current.hazardCut
    Canvas(modifier = modifier.fillMaxSize()) {
        val stripeGap = 16.dp.toPx()
        val strokeW = 2.dp.toPx()
        var x = -size.height
        while (x < size.width + size.height) {
            drawLine(
                color = hazard.copy(alpha = 0.22f),
                start = Offset(x, size.height),
                end = Offset(x + size.height, 0f),
                strokeWidth = strokeW
            )
            x += stripeGap
        }
    }
}

/**
 * Dismissible actionable On-Air Crimson error banner.
 */
@Composable
fun StudioErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF1F090E),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, OnAirCrimson.copy(alpha = 0.8f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("studio_error_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = StudioCrisp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close_sheet),
                    tint = OnAirCrimson,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
