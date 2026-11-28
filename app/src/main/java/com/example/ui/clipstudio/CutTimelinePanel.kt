package com.example.ui.clipstudio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.BrollSegment
import com.example.data.ClipTransitionType
import com.example.data.CorePipelineSession
import com.example.data.CutClip
import com.example.data.CutTimeline
import com.example.data.GfxLane
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import kotlin.math.roundToLong

private val LaneLabelWidth = 50.dp
private val VoiceLaneHeight = 34.dp
private val BrollLaneHeight = 30.dp
private val GfxRowHeight = 18.dp
private val LaneCaption = AcType.Caption.copy(color = AcTokens.OpGrey, fontSize = 8.5.sp)
private val BlockText = AcType.Label.copy(fontSize = 9.5.sp)

/** A clause join on the edited timeline where the renderer applies that clause's transition. */
internal data class CutJoin(val clauseIndex: Int, val timelineMs: Long, val transition: ClipTransitionType)

/** Joins between sentences (clause 1..n), at their start on the edited timeline; trimmed-away joins are skipped. */
internal fun cutJoins(session: CorePipelineSession, clips: List<CutClip>): List<CutJoin> =
    session.clauses.mapIndexedNotNull { idx, clause ->
        if (idx == 0) return@mapIndexedNotNull null
        val tl = CutTimeline.sourceToTimeline(clips, clause.startMs) ?: return@mapIndexedNotNull null
        CutJoin(idx, tl, ClipTransitionType.fromWire(clause.transitionType))
    }

/** Timeline position of source [sourceMs]; a trimmed-away moment maps to the start of the next kept clip. */
internal fun sourceToTimelineClamped(clips: List<CutClip>, sourceMs: Long): Long {
    var offset = 0L
    for (clip in clips) {
        if (sourceMs < clip.sourceStartMs) return offset
        if (sourceMs < clip.sourceEndMs) return offset + (sourceMs - clip.sourceStartMs)
        offset += clip.durationMs
    }
    return offset
}

/**
 * 02 CUT timeline (cut mockup + studio mockup): one [AcDisplay] with a time ruler, the VOICE lane
 * (kept talking-head clips with blue trim brackets and blue ◆ transition joins), the B-ROLL lane
 * (draggable / resizable orange blocks) and the read-only GFX lane (when each graphic is on screen).
 * All positions are on the edited timeline.
 */
@Composable
internal fun CutTimelinePanel(
    session: CorePipelineSession,
    clips: List<CutClip>,
    gfxSpans: List<GfxLane.Span>,
    playheadMs: Long,
    onSeek: (Long) -> Unit,
    onTrim: (clipId: String, sourceStartMs: Long, sourceEndMs: Long) -> Unit,
    onMoveSegment: (segmentId: String, timelineStartMs: Long) -> Unit,
    onResizeSegment: (segmentId: String, durationMs: Long) -> Unit,
    onOpenSegment: (segmentId: String) -> Unit,
    onOpenJoin: (clauseIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalMs = CutTimeline.timelineDurationMs(clips)
    val sourceTotalMs = CutTimeline.sourceDurationMs(session)
    AcDisplay(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .testTag("cs_cut_timeline")
    ) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 10.dp)) {
            Column(Modifier.width(LaneLabelWidth)) {
                Box(Modifier.height(16.dp))
                LaneLabel("VOICE", VoiceLaneHeight)
                LaneLabel("B-ROLL", BrollLaneHeight)
                LaneLabel("GFX", GfxRowHeight * gfxSpans.size.coerceAtLeast(1) + 4.dp)
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val density = LocalDensity.current
                val widthPx = with(density) { maxWidth.toPx() }
                val msPerPx = if (widthPx > 0f && totalMs > 0L) totalMs / widthPx else 0f
                fun xDp(ms: Long): Dp = if (totalMs > 0L) maxWidth * (ms.toFloat() / totalMs.toFloat()) else 0.dp
                val latestSeek by rememberUpdatedState(onSeek)

                Column(
                    Modifier.pointerInput(totalMs, widthPx) {
                        detectTapGestures { pos ->
                            if (totalMs > 0L && widthPx > 0f) {
                                latestSeek((pos.x / widthPx * totalMs).roundToLong().coerceIn(0L, totalMs))
                            }
                        }
                    }
                ) {
                    TimeRuler(totalMs)
                    if (totalMs <= 0L) {
                        Box(Modifier.fillMaxWidth().height(VoiceLaneHeight + BrollLaneHeight + GfxRowHeight), contentAlignment = Alignment.CenterStart) {
                            Text("TIMELINE APPEARS ONCE THE SCRIPT IS VOICED", style = LaneCaption, maxLines = 2)
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(VoiceLaneHeight).testTag("cs_lane_voice")) {
                            var offset = 0L
                            clips.forEach { clip ->
                                val start = offset
                                offset += clip.durationMs
                                VoiceClip(
                                    clip = clip,
                                    peaks = session.waveformPeaks,
                                    sourceTotalMs = sourceTotalMs,
                                    msPerPx = msPerPx,
                                    modifier = Modifier
                                        .offset(x = xDp(start))
                                        .width(xDp(clip.durationMs))
                                        .fillMaxHeight(),
                                    onTrim = onTrim
                                )
                            }
                            cutJoins(session, clips).forEach { join ->
                                JoinDiamond(
                                    join = join,
                                    modifier = Modifier
                                        .offset(x = xDp(join.timelineMs) - 16.dp)
                                        .align(Alignment.CenterStart),
                                    onClick = { onOpenJoin(join.clauseIndex) }
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(BrollLaneHeight).testTag("cs_lane_broll")) {
                            session.brollSegments.forEach { seg ->
                                val startTl = CutTimeline.sourceToTimeline(clips, seg.startMs) ?: return@forEach
                                val durTl = seg.durationMs.coerceAtMost(totalMs - startTl).coerceAtLeast(0L)
                                BrollBlock(
                                    segment = seg,
                                    startTimelineMs = startTl,
                                    msPerPx = msPerPx,
                                    modifier = Modifier
                                        .offset(x = xDp(startTl))
                                        .width(xDp(durTl).coerceAtLeast(18.dp))
                                        .padding(vertical = 3.dp)
                                        .fillMaxHeight(),
                                    onMove = onMoveSegment,
                                    onResize = onResizeSegment,
                                    onOpen = { onOpenSegment(seg.id) }
                                )
                            }
                        }
                        Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("cs_lane_gfx")) {
                            if (gfxSpans.isEmpty()) {
                                Box(Modifier.height(GfxRowHeight), contentAlignment = Alignment.CenterStart) {
                                    Text("NO GRAPHICS", style = LaneCaption)
                                }
                            }
                            gfxSpans.forEach { span ->
                                Box(Modifier.fillMaxWidth().height(GfxRowHeight)) {
                                    GfxBar(
                                        span = span,
                                        modifier = Modifier
                                            .offset(x = xDp(span.startMs))
                                            .width(xDp(span.endMs - span.startMs).coerceAtLeast(10.dp))
                                            .padding(vertical = 1.5.dp)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                        }
                    }
                }
                if (totalMs > 0L) {
                    val px = xDp(playheadMs.coerceIn(0L, totalMs))
                    Canvas(
                        Modifier
                            .matchParentSize()
                            .testTag("cs_cut_playhead")
                    ) {
                        val x = px.toPx()
                        drawLine(AcTokens.OpRed, Offset(x, 6.dp.toPx()), Offset(x, size.height), 1.5.dp.toPx())
                        drawCircle(AcTokens.OpRed, radius = 3.5.dp.toPx(), center = Offset(x, 6.dp.toPx()))
                    }
                }
            }
        }
    }
}

@Composable
private fun LaneLabel(text: String, height: Dp) {
    Box(Modifier.height(height), contentAlignment = Alignment.CenterStart) {
        Text(text, style = LaneCaption, maxLines = 1)
    }
}

@Composable
private fun TimeRuler(totalMs: Long) {
    Row(Modifier.fillMaxWidth().height(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        if (totalMs > 0L) {
            (0..4).forEach { i -> Text(mmss(totalMs * i / 4), style = LaneCaption, maxLines = 1) }
        }
    }
}

@Composable
private fun VoiceClip(
    clip: CutClip,
    peaks: List<Float>,
    sourceTotalMs: Long,
    msPerPx: Float,
    modifier: Modifier,
    onTrim: (String, Long, Long) -> Unit
) {
    var leftDragPx by remember(clip.id, clip.sourceStartMs) { mutableFloatStateOf(0f) }
    var rightDragPx by remember(clip.id, clip.sourceEndMs) { mutableFloatStateOf(0f) }
    val latestClip by rememberUpdatedState(clip)
    val latestMsPerPx by rememberUpdatedState(msPerPx)
    Box(modifier.testTag("cs_voice_clip_${clip.id}")) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 1.dp)) {
            val left = leftDragPx.coerceAtLeast(-size.width)
            val right = size.width + rightDragPx
            drawRoundRect(
                color = AcTokens.OpGrey,
                topLeft = Offset(left, 2.dp.toPx()),
                size = Size((right - left).coerceAtLeast(1f), size.height - 4.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(1.dp.toPx())
            )
            if (peaks.isNotEmpty() && sourceTotalMs > 0L) {
                val first = (clip.sourceStartMs * peaks.size / sourceTotalMs).toInt().coerceIn(0, peaks.size)
                val last = (clip.sourceEndMs * peaks.size / sourceTotalMs).toInt().coerceIn(first, peaks.size)
                val slice = peaks.subList(first, last)
                if (slice.isNotEmpty()) {
                    val step = size.width / slice.size
                    val mid = size.height / 2f
                    slice.forEachIndexed { i, p ->
                        val h = (size.height - 10.dp.toPx()) * (0.12f + 0.88f * p.coerceIn(0f, 1f))
                        val x = i * step + step / 2f
                        drawLine(AcTokens.OpGrey, Offset(x, mid - h / 2f), Offset(x, mid + h / 2f), 1.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
        TrimBracket(
            left = true,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = with(LocalDensity.current) { leftDragPx.toDp() } - 8.dp)
                .testTag("cs_trim_in_${clip.id}")
                .pointerInput(clip.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val c = latestClip
                            val delta = (leftDragPx * latestMsPerPx).roundToLong()
                            if (delta != 0L) onTrim(c.id, c.sourceStartMs + delta, c.sourceEndMs)
                            leftDragPx = 0f
                        },
                        onDragCancel = { leftDragPx = 0f }
                    ) { change, dx ->
                        change.consume()
                        leftDragPx += dx
                    }
                }
        )
        TrimBracket(
            left = false,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = with(LocalDensity.current) { rightDragPx.toDp() } + 8.dp)
                .testTag("cs_trim_out_${clip.id}")
                .pointerInput(clip.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val c = latestClip
                            val delta = (rightDragPx * latestMsPerPx).roundToLong()
                            if (delta != 0L) onTrim(c.id, c.sourceStartMs, c.sourceEndMs + delta)
                            rightDragPx = 0f
                        },
                        onDragCancel = { rightDragPx = 0f }
                    ) { change, dx ->
                        change.consume()
                        rightDragPx += dx
                    }
                }
        )
    }
}

/** Blue trim bracket drawn in thin lines, with a 16dp-wide grab area. */
@Composable
private fun TrimBracket(left: Boolean, modifier: Modifier) {
    Canvas(
        modifier
            .width(16.dp)
            .fillMaxHeight()
            .semantics { contentDescription = if (left) "Trim clip start" else "Trim clip end" }
    ) {
        val stroke = 2.dp.toPx()
        val arm = 5.dp.toPx()
        val x = size.width / 2f
        val dir = if (left) 1f else -1f
        val top = 2.dp.toPx()
        val bottom = size.height - 2.dp.toPx()
        drawLine(AcTokens.OpBlue, Offset(x, top), Offset(x, bottom), stroke)
        drawLine(AcTokens.OpBlue, Offset(x, top), Offset(x + dir * arm, top), stroke)
        drawLine(AcTokens.OpBlue, Offset(x, bottom), Offset(x + dir * arm, bottom), stroke)
    }
}

/** Blue ◆ at a sentence join: filled when the join has a transition other than a hard cut. */
@Composable
private fun JoinDiamond(join: CutJoin, modifier: Modifier, onClick: () -> Unit) {
    val filled = join.transition != ClipTransitionType.CUT
    Box(
        modifier
            .size(32.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Transition at ${mmss(join.timelineMs)}: ${join.transition.label}" }
            .testTag("cs_cut_join_${join.clauseIndex}"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(14.dp)) {
            val path = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width / 2f, size.height)
                lineTo(0f, size.height / 2f)
                close()
            }
            drawPath(path, AcTokens.Display, style = Fill)
            if (filled) drawPath(path, AcTokens.OpBlue, style = Fill)
            drawPath(path, AcTokens.OpBlue, style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun BrollBlock(
    segment: BrollSegment,
    startTimelineMs: Long,
    msPerPx: Float,
    modifier: Modifier,
    onMove: (String, Long) -> Unit,
    onResize: (String, Long) -> Unit,
    onOpen: () -> Unit
) {
    var moveDx by remember(segment.id, segment.startMs) { mutableFloatStateOf(0f) }
    var resizeDx by remember(segment.id, segment.durationMs) { mutableFloatStateOf(0f) }
    val latestStart by rememberUpdatedState(startTimelineMs)
    val latestDuration by rememberUpdatedState(segment.durationMs)
    val latestMsPerPx by rememberUpdatedState(msPerPx)
    val density = LocalDensity.current
    Box(
        modifier
            .offset(x = with(density) { moveDx.toDp() })
            .testTag("cs_broll_block_${segment.id}")
            .pointerInput(segment.id) {
                detectDragGestures(
                    onDragEnd = {
                        val delta = (moveDx * latestMsPerPx).roundToLong()
                        if (delta != 0L) onMove(segment.id, (latestStart + delta).coerceAtLeast(0L))
                        moveDx = 0f
                    },
                    onDragCancel = { moveDx = 0f }
                ) { change, drag ->
                    change.consume()
                    moveDx += drag.x
                }
            }
            .clickable(role = Role.Button, onClick = onOpen)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = AcTokens.OpOrange,
                size = Size((size.width + resizeDx).coerceAtLeast(8.dp.toPx()), size.height),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(1.dp.toPx())
            )
        }
        Text(
            text = segment.caption.ifBlank { segment.prompt }.ifBlank { "b-roll" },
            style = BlockText.copy(color = AcTokens.OpOrange),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 5.dp, end = 14.dp)
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset(x = with(density) { resizeDx.toDp() } + 6.dp)
                .width(16.dp)
                .fillMaxHeight()
                .semantics { contentDescription = "Resize B-roll" }
                .testTag("cs_broll_resize_${segment.id}")
                .pointerInput(segment.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val delta = (resizeDx * latestMsPerPx).roundToLong()
                            if (delta != 0L) onResize(segment.id, latestDuration + delta)
                            resizeDx = 0f
                        },
                        onDragCancel = { resizeDx = 0f }
                    ) { change, dx ->
                        change.consume()
                        resizeDx += dx
                    }
                }
        )
    }
}

@Composable
private fun GfxBar(span: GfxLane.Span, modifier: Modifier) {
    Box(modifier.testTag("cs_gfx_${span.key}")) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                color = AcTokens.OpGreen,
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(1.dp.toPx())
            )
        }
        Text(
            text = span.label,
            style = BlockText.copy(color = AcTokens.OpGreen, fontSize = 8.5.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 5.dp)
        )
    }
}
