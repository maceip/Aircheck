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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.ConsoleObsidian
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp

/**
 * Geometric Triangle + Broadcast Mic emblem for Aircheck.
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
 * Uniform 8.dp OLED studio card with 1.dp graphite border and optional shimmer.
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
 * Tactile hardware broadcast transport key with metallic bevel + recessed OLED well + LED pip.
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
 * Real decoded RIFF PCM waveform renderer. Never synthesizes fake peaks:
 * when peaks is empty, renders a quiet studio baseline.
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

@Composable
fun studioFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalAircheckTokens.current.channelAccent,
    unfocusedBorderColor = LocalAircheckTokens.current.hairline,
    focusedContainerColor = OledVoid,
    unfocusedContainerColor = OledVoid,
    focusedTextColor = StudioCrisp,
    unfocusedTextColor = StudioCrisp,
    cursorColor = LocalAircheckTokens.current.channelAccent
)
