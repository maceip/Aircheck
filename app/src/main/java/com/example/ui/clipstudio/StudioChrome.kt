package com.example.ui.clipstudio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.design.AcCard
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcPrimaryKey
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import kotlin.math.roundToInt

// =============================================================================
// Shared studio chrome composed only from the design v2 components (ui/design).
// =============================================================================

/** Eyebrow / section caption (Caption style, written uppercase). */
@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = AcTokens.Ink2) {
    Text(
        text = text.uppercase(),
        style = AcType.Caption.copy(color = color),
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

enum class LedState { IDLE, WORKING, READY, ERROR }

data class PipelineLedState(
    val rig: LedState = LedState.IDLE,
    val voice: LedState = LedState.IDLE,
    val head: LedState = LedState.IDLE,
    val cut: LedState = LedState.IDLE,
)

private fun pipelineColor(state: LedState) = when (state) {
    LedState.READY -> AcTokens.OpGreen
    LedState.WORKING -> AcTokens.OpOrange
    LedState.ERROR -> AcTokens.OpRed
    LedState.IDLE -> AcTokens.MediaKeyHi
}

/** RIG · VOX · HEAD · CUT pipeline status, drawn as a tiny display (op colors live in displays only). */
@Composable
fun PipelineStatusDisplay(state: PipelineLedState, modifier: Modifier = Modifier) {
    AcDisplay(modifier = modifier.testTag("cs_pipeline_leds"), radius = 8.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("RIG" to state.rig, "VOX" to state.voice, "HEAD" to state.head, "CUT" to state.cut).forEach { (label, s) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(pipelineColor(s))
                    )
                    Text(label, style = AcType.Caption.copy(fontSize = 7.sp, letterSpacing = 0.sp, color = AcTokens.OpGrey), maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * Top bar for the 2-stage flow: Doto step header ("01 SPARK" with the active word in red, the other
 * step number muted and tappable) plus trailing app keys.
 */
@Composable
fun StudioTopBar(
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
            .height(60.dp)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        stageNames.forEachIndexed { idx, name ->
            val stageNum = idx + 1
            val isActive = stageNum == normalizedActive
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .defaultMinSize(minWidth = 34.dp)
                    .then(if (isActive) Modifier else Modifier.clickable(role = Role.Tab) { onNavigate(stageNum) })
                    .padding(horizontal = 2.dp)
                    .testTag("cs_crumb_$stageNum"),
                contentAlignment = Alignment.Center
            ) {
                if (isActive) {
                    Text(
                        text = buildAnnotatedString {
                            append("%02d ".format(stageNum))
                            withStyle(AcType.Step.copy(color = AcTokens.Red).toSpanStyle()) { append(name) }
                        },
                        style = AcType.Step,
                        maxLines = 1,
                        softWrap = false
                    )
                } else {
                    Text(
                        text = "%02d".format(stageNum),
                        style = AcType.Step.copy(color = AcTokens.ChassisLo),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        if (leds != null) {
            PipelineStatusDisplay(state = leds, modifier = Modifier.padding(start = 4.dp))
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing
        )
    }
}

/** Floating notice card below the top bar with auto-dismiss + tap-to-close. */
@Composable
fun BoxScope.StudioToast(
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
    AcCard(
        modifier = modifier
            .align(Alignment.TopCenter)
            .padding(start = 14.dp, top = 64.dp, end = 14.dp)
            .fillMaxWidth()
            .testTag("cs_toast"),
        onClick = onDismiss,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = message,
                style = AcType.BodySmall.copy(color = AcTokens.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            AcKey(
                label = "ok",
                onClick = onDismiss,
                size = AcKeySize.Small,
                modifier = Modifier.testTag("cs_toast_ok")
            )
        }
    }
}

/** Tappable error or notice line. */
@Composable
fun StudioStatusLine(
    error: String?,
    notice: String?,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val (text, color, dismiss) = when {
        !error.isNullOrBlank() -> Triple(error, AcTokens.Red, onDismissError)
        !notice.isNullOrBlank() -> Triple(notice, AcTokens.Ink2, onDismissNotice)
        else -> return
    }
    AcCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag("cs_status_line"),
        onClick = dismiss,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text, style = AcType.BodySmall.copy(color = color), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Progress meter shown in place of the primary key while a real job runs (tag `cs_busy_box`). */
@Composable
fun StudioProgressDisplay(
    label: String,
    progressFraction: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 58.dp,
    tag: String = "cs_busy_box"
) {
    val target = (progressFraction ?: 0f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(target, tween(80, easing = LinearEasing), label = "busy_progress")
    AcDisplay(
        modifier = modifier
            .height(height)
            .testTag(tag),
        contentAlignment = Alignment.CenterStart
    ) {
        if (progressFraction != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .drawBehind {
                        val y = size.height - 3.dp.toPx()
                        drawLine(AcTokens.MediaKey, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
                        drawLine(AcTokens.OpRed, Offset(0f, y), Offset(size.width * animated, y), 1.5.dp.toPx())
                    }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ThinkingOrbSolving(size = 28.dp, tint = AcTokens.Ui)
            Text(
                text = label,
                style = AcType.Label.copy(color = AcTokens.Ui),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (progressFraction != null) {
                Text("${(target * 100f).roundToInt()}%", style = AcType.numeral(20.sp), maxLines = 1)
            }
        }
    }
}

/**
 * Bottom dock: optional [leading] media well / keys, then the screen's one [AcPrimaryKey]
 * (replaced by a [StudioProgressDisplay] while a real job runs).
 */
@Composable
fun StudioDock(
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    primaryTag: String,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    primaryIcon: ImageVector? = null,
    primaryEnabled: Boolean = true,
    isBusy: Boolean = false,
    busyLabel: String? = null,
    busyFraction: Float? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke(this)
        if (isBusy) {
            StudioProgressDisplay(
                label = busyLabel ?: "working…",
                progressFraction = busyFraction,
                modifier = Modifier.weight(1f)
            )
        } else {
            AcPrimaryKey(
                label = primaryLabel,
                onClick = onPrimaryClick,
                icon = primaryIcon,
                enabled = primaryEnabled,
                modifier = Modifier
                    .weight(1f)
                    .testTag(primaryTag)
            )
        }
    }
}

/**
 * Bottom sheet surface on the light chassis: scrim, drag handle, Title row with a close key.
 * [fill] makes the sheet take the height below [topInset].
 */
@Composable
fun StudioSheet(
    title: String,
    onDismiss: () -> Unit,
    closeTag: String,
    closeDescription: String,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    topInset: Dp = 118.dp,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AcTokens.Ink.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(if (fill) Modifier.fillMaxSize().padding(top = topInset) else Modifier)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(AcTokens.Chassis)
                .then(modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AcTokens.ChassisLo)
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = AcType.Title)
                    if (subtitle != null) Text(subtitle, style = AcType.BodySmall)
                }
                AcKey(
                    icon = Icons.Sharp.Close,
                    contentDescription = closeDescription,
                    onClick = onDismiss,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag(closeTag)
                )
            }
            content()
        }
    }
}

fun Modifier.hairlineBottom(color: androidx.compose.ui.graphics.Color = AcTokens.Hairline): Modifier = drawBehind {
    drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
}

fun Modifier.hairlineTop(color: androidx.compose.ui.graphics.Color = AcTokens.Hairline): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
}

/** Dual-lane level meter driven strictly by real persisted [peaks] at [positionMs], in a display. */
@Composable
fun StudioLevelMeter(
    peaks: List<Float>,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    fun levelAt(offset: Int): Float {
        if (peaks.isEmpty() || durationMs <= 0L || (!isPlaying && positionMs == 0L)) return 0f
        val frac = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 0.999f)
        val idx = ((frac * peaks.size).toInt() + offset).coerceIn(0, peaks.lastIndex)
        return peaks[idx].coerceIn(0f, 1f)
    }
    val left = levelAt(0)
    val right = levelAt(1)
    AcDisplay(modifier = modifier.width(22.dp).testTag("cs_stereo_meter"), radius = 6.dp) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(3.dp)
                .drawBehind {
                    val laneW = 6.dp.toPx()
                    val gapW = 2.dp.toPx()
                    val barH = 1.5.dp.toPx()
                    val pitch = 4.dp.toPx()
                    val redZoneY = 20.dp.toPx()
                    val total = (size.height / pitch).toInt().coerceAtLeast(1)
                    val litL = (left * total).toInt()
                    val litR = (right * total).toInt()
                    for (i in 0 until total) {
                        val y = size.height - (i + 1) * pitch
                        val peak = y < redZoneY
                        fun c(lit: Boolean) = when {
                            lit && peak -> AcTokens.OpRed
                            lit -> AcTokens.Ui
                            else -> AcTokens.MediaKey
                        }
                        drawRect(c(i < litL), Offset(0f, y), Size(laneW, barH))
                        drawRect(c(i < litR), Offset(laneW + gapW, y), Size(laneW, barH))
                    }
                }
        )
    }
}

/**
 * Drag tick ruler drawn on a display: horizontal drag scrubs, left/right taps step
 * (`${tag}_dec` / `${tag}_inc`).
 */
@Composable
fun StudioTickRuler(
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
    val norm = normalizedValue.coerceIn(0f, 1f)
    AcDisplay(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
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
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val padX = 14.dp.toPx()
                    val x0 = padX
                    val x1 = (size.width - padX).coerceAtLeast(x0 + 1f)
                    val baseY = size.height - 6.dp.toPx()
                    val ticks = 40
                    for (i in 0..ticks) {
                        val x = x0 + (x1 - x0) * (i.toFloat() / ticks)
                        val major = i % 5 == 0
                        val h = if (major) 10.dp.toPx() else 5.dp.toPx()
                        drawLine(
                            if (!enabled) AcTokens.MediaKey else if (major) AcTokens.OpGrey else AcTokens.MediaKeyHi,
                            Offset(x, baseY - h),
                            Offset(x, baseY),
                            1.dp.toPx()
                        )
                    }
                    val nx = x0 + (x1 - x0) * norm
                    val needle = if (enabled) AcTokens.OpRed else AcTokens.OpGrey
                    drawLine(needle, Offset(nx, baseY - 20.dp.toPx()), Offset(nx, baseY + 1.dp.toPx()), 1.5.dp.toPx())
                    val tri = Path().apply {
                        moveTo(nx, baseY - 18.dp.toPx())
                        lineTo(nx - 3.5.dp.toPx(), baseY - 23.dp.toPx())
                        lineTo(nx + 3.5.dp.toPx(), baseY - 23.dp.toPx())
                        close()
                    }
                    drawPath(tri, needle)
                }
        )
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
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label.uppercase(), style = AcType.Caption.copy(color = AcTokens.OpGrey), maxLines = 1)
            Text(
                valueText,
                style = AcType.numeral(15.sp).copy(color = if (enabled) AcTokens.Ui else AcTokens.OpGrey),
                maxLines = 1,
                modifier = Modifier.testTag("${tag}_value")
            )
        }
    }
}

/** Vertical gap helper. */
@Composable
fun ColumnScope.Gap(height: Dp) = Spacer(Modifier.height(height))
