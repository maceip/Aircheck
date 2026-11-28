package com.example.ui.clipstudio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.sharp.Delete
import androidx.compose.material.icons.sharp.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.data.BrollSegment
import com.example.data.ClipTransitionType
import com.example.data.CoreRenderOptions
import com.example.data.CorePipelineSession
import com.example.data.CutClip
import com.example.data.CutTimeline
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcField
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcPrimaryKey
import com.example.ui.design.AcTileKey
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import java.io.File

/** Order of the transition tiles (mockup: cut, fade, dissolve, dip black, wipe ←, wipe →, slide ↑). */
internal val TransitionTileOrder = listOf(
    ClipTransitionType.CUT,
    ClipTransitionType.FADE,
    ClipTransitionType.DISSOLVE,
    ClipTransitionType.FADE_BLACK,
    ClipTransitionType.WIPE_LEFT,
    ClipTransitionType.WIPE_RIGHT,
    ClipTransitionType.SLIDE_UP
)

internal fun ClipTransitionType.tileLabel(): String = when (this) {
    ClipTransitionType.CUT -> "cut"
    ClipTransitionType.FADE -> "fade"
    ClipTransitionType.DISSOLVE -> "dissolve"
    ClipTransitionType.FADE_BLACK -> "dip black"
    ClipTransitionType.WIPE_LEFT -> "wipe ←"
    ClipTransitionType.WIPE_RIGHT -> "wipe →"
    ClipTransitionType.SLIDE_UP -> "slide ↑"
}

/** Speeds the renderer supports (ms). */
internal val TransitionSpeeds = listOf(200 to "fast 0.2s", 350 to "med 0.35s", 600 to "slow 0.6s")

/**
 * Transition sheet. [clauseIndex] = the join entering that sentence (from a ◆), or null for every
 * join (the blue transitions key). Tiles loop clip A into clip B with each effect.
 */
@Composable
internal fun TransitionSheet(
    session: CorePipelineSession,
    clips: List<CutClip>,
    clauseIndex: Int?,
    onSetJoin: (index: Int, ClipTransitionType, durationMs: Int) -> Unit,
    onSetDefault: (ClipTransitionType) -> Unit,
    onApplyAll: (ClipTransitionType, durationMs: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val joins = cutJoins(session, clips)
    val clause = clauseIndex?.let { session.clauses.getOrNull(it) }
    var allType by rememberSaveable { mutableStateOf(ClipTransitionType.fromWire(session.renderOptions.defaultTransition).wire) }
    var allSpeed by rememberSaveable { mutableIntStateOf(350) }
    val selected = if (clause != null) ClipTransitionType.fromWire(clause.transitionType) else ClipTransitionType.fromWire(allType)
    val speed = clause?.transitionDurationMs ?: allSpeed
    val subtitle = if (clause != null) {
        val pos = joins.indexOfFirst { it.clauseIndex == clauseIndex }
        val at = joins.getOrNull(pos)?.timelineMs ?: clause.startMs
        "Join ${pos + 1} of ${joins.size} · ${mmss(at)}"
    } else {
        "Every join · ${joins.size}"
    }
    StudioSheet(
        title = "Transition",
        subtitle = subtitle,
        onDismiss = onDismiss,
        closeTag = "cs_transition_sheet_close",
        closeDescription = "Close transitions",
        modifier = Modifier.testTag("cs_transition_sheet")
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TransitionTileOrder.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        AcTileKey(
                            label = t.tileLabel(),
                            selected = selected == t,
                            onClick = {
                                if (clauseIndex != null) {
                                    onSetJoin(clauseIndex, t, speed)
                                } else {
                                    allType = t.wire
                                    onSetDefault(t)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cs_transition_tile_${t.wire}")
                        ) {
                            TransitionPreview(t, Modifier.fillMaxSize())
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            SectionCaption("Speed")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransitionSpeeds.forEach { (ms, label) ->
                    AcKey(
                        label = label,
                        selected = speed == ms,
                        onClick = {
                            if (clauseIndex != null) onSetJoin(clauseIndex, selected, ms) else allSpeed = ms
                        },
                        size = AcKeySize.Small,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_transition_speed_$ms")
                    )
                }
            }
            AcKey(
                label = "use for every join",
                onClick = { onApplyAll(selected, speed) },
                enabled = joins.isNotEmpty(),
                size = AcKeySize.Small,
                modifier = Modifier.testTag("cs_transition_apply_all")
            )
            AcPrimaryKey(
                label = "done",
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cs_transition_done")
            )
        }
    }
}

/** Small loop of clip A (grey) into clip B (blue) using [type], drawn in thin lines. */
@Composable
internal fun TransitionPreview(type: ClipTransitionType, modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "transition_${type.wire}")
    val p by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "p"
    )
    Canvas(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        val t = ((p - 0.3f) / 0.4f).coerceIn(0f, 1f)
        when (type) {
            ClipTransitionType.CUT -> if (t < 0.5f) frame(false) else frame(true)
            ClipTransitionType.FADE -> {
                frame(false, alpha = 1f - t)
                frame(true, alpha = t)
            }
            ClipTransitionType.DISSOLVE -> {
                frame(false)
                // B appears in a fixed scatter of cells as t grows.
                val cols = 8
                val rows = 5
                val cw = size.width / cols
                val ch = size.height / rows
                clipRect {
                    for (r in 0 until rows) for (c in 0 until cols) {
                        val threshold = ((r * 7 + c * 13) % 40) / 40f
                        if (t > threshold) {
                            drawRect(AcTokens.OpBlue.copy(alpha = 0.55f), topLeft = Offset(c * cw, r * ch), size = Size(cw, ch))
                        }
                    }
                }
                if (t >= 1f) frame(true)
            }
            ClipTransitionType.FADE_BLACK -> if (t < 0.5f) frame(false, alpha = 1f - 2f * t) else frame(true, alpha = 2f * t - 1f)
            ClipTransitionType.WIPE_LEFT -> {
                frame(false)
                clipRect(left = size.width * (1f - t)) { frame(true) }
            }
            ClipTransitionType.WIPE_RIGHT -> {
                frame(false)
                clipRect(right = size.width * t) { frame(true) }
            }
            ClipTransitionType.SLIDE_UP -> {
                frame(false)
                clipRect { frame(true, dy = size.height * (1f - t)) }
            }
        }
    }
}

private fun DrawScope.frame(b: Boolean, alpha: Float = 1f, dy: Float = 0f) {
    if (alpha <= 0f) return
    val color = if (b) AcTokens.OpBlue else AcTokens.OpGrey
    drawRect(color.copy(alpha = (if (b) 0.55f else 0.35f) * alpha), topLeft = Offset(0f, dy), size = size)
    drawRect(color.copy(alpha = alpha), topLeft = Offset(0f, dy), size = size, style = Stroke(1.dp.toPx()))
}

/**
 * B-roll sheet for one timeline block: preview, prompt with AI generate, pick photo/video,
 * framing pictures (corner / full / split), delete, done.
 */
@Composable
internal fun BrollSegmentSheet(
    segment: BrollSegment,
    clips: List<CutClip>,
    defaultFraming: String,
    isGenerating: Boolean,
    isUploading: Boolean,
    onCommitPrompt: (String) -> Unit,
    onGenerate: (String) -> Unit,
    onPickMedia: () -> Unit,
    onSetFraming: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var prompt by rememberSaveable(segment.id) { mutableStateOf(segment.prompt.ifBlank { segment.caption }) }
    LaunchedEffect(segment.id, segment.prompt) {
        if (segment.prompt.isNotBlank() && prompt != segment.prompt) prompt = segment.prompt
    }
    val startTl = CutTimeline.sourceToTimeline(clips, segment.startMs) ?: segment.startMs
    val title = segment.caption.ifBlank { segment.prompt }.ifBlank { "B-roll" }
    val commitAndClose = {
        if (prompt.trim() != segment.prompt) onCommitPrompt(prompt)
        onDismiss()
    }
    StudioSheet(
        title = title,
        subtitle = "B-roll · ${mmss(startTl)} to ${mmss(startTl + segment.durationMs)}",
        onDismiss = commitAndClose,
        closeTag = "cs_broll_sheet_close",
        closeDescription = "Close B-roll",
        modifier = Modifier.testTag("cs_broll_sheet")
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BrollPreview(segment)
            AcField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = "Describe the shot…",
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cs_broll_sheet_prompt"),
                trailing = {
                    AcKey(
                        onClick = {
                            val txt = prompt.trim()
                            onCommitPrompt(txt)
                            onGenerate(txt)
                        },
                        selected = isGenerating,
                        enabled = !isGenerating && !isUploading && prompt.isNotBlank(),
                        size = AcKeySize.Small,
                        circle = true,
                        modifier = Modifier.testTag("cs_broll_sheet_generate")
                    ) { fg ->
                        if (isGenerating) {
                            ThinkingOrbSolving(size = 18.dp, tint = fg)
                        } else {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = "Generate a still with AI", tint = fg, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AcKey(
                    label = if (isUploading) "loading…" else "pick photo or video",
                    icon = Icons.Sharp.PhotoLibrary,
                    onClick = onPickMedia,
                    enabled = !isUploading && !isGenerating,
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag("cs_broll_sheet_pick")
                )
                Spacer(Modifier.weight(1f))
                AcKey(
                    icon = Icons.Sharp.Delete,
                    contentDescription = "Delete this B-roll",
                    onClick = onDelete,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_broll_sheet_delete")
                )
            }
            SectionCaption("Framing")
            val framing = segment.framingOverride.ifBlank { defaultFraming }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    CoreRenderOptions.BROLL_FRAMING_PIP to "corner",
                    CoreRenderOptions.BROLL_FRAMING_FULL to "full",
                    CoreRenderOptions.BROLL_FRAMING_SPLIT to "split"
                ).forEach { (wire, label) ->
                    AcTileKey(
                        label = label,
                        selected = framing == wire,
                        onClick = { onSetFraming(wire) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_broll_framing_$wire")
                    ) {
                        FramingPicture(wire, Modifier.fillMaxSize())
                    }
                }
            }
            AcPrimaryKey(
                label = "done",
                onClick = commitAndClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cs_broll_sheet_done")
            )
        }
    }
}

@Composable
private fun BrollPreview(segment: BrollSegment) {
    val isVideo = segment.customExt in setOf("mp4", "mov") && !segment.sha.isNullOrBlank()
    val model: Any? = remember(segment.previewUrl, segment.customExt) {
        val raw = segment.previewUrl?.trim().orEmpty()
        when {
            raw.isEmpty() || isVideo -> null
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> File(raw).takeIf { it.isFile }
        }
    }
    val caption = when {
        isVideo -> "VIDEO · .${segment.customExt}"
        !segment.sha.isNullOrBlank() -> "PHOTO"
        model != null || !segment.mediaId.isNullOrBlank() -> "GENERATED STILL"
        else -> "NO SOURCE YET"
    }
    AcDisplay(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .testTag("cs_broll_sheet_preview"),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = "B-roll preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            caption,
            style = AcType.Caption.copy(color = AcTokens.OpGrey),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
        )
    }
}

/** Corner (B-roll full, presenter in a corner), full (B-roll only), split (presenter | B-roll). */
@Composable
private fun FramingPicture(wire: String, modifier: Modifier) {
    Canvas(modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        val stroke = Stroke(1.dp.toPx())
        when (wire) {
            CoreRenderOptions.BROLL_FRAMING_SPLIT -> {
                val half = size.width / 2f
                drawRect(AcTokens.OpGrey.copy(alpha = 0.35f), size = Size(half, size.height))
                drawRect(AcTokens.OpGrey, size = Size(half, size.height), style = stroke)
                drawRect(AcTokens.OpBlue.copy(alpha = 0.55f), topLeft = Offset(half, 0f), size = Size(half, size.height))
                drawRect(AcTokens.OpBlue, topLeft = Offset(half, 0f), size = Size(half, size.height), style = stroke)
            }
            else -> {
                drawRect(AcTokens.OpBlue.copy(alpha = 0.55f))
                drawRect(AcTokens.OpBlue, style = stroke)
                if (wire == CoreRenderOptions.BROLL_FRAMING_PIP) {
                    val w = size.width * 0.32f
                    val h = size.height * 0.55f
                    val tl = Offset(size.width - w - 3.dp.toPx(), 3.dp.toPx())
                    drawRect(AcTokens.OpGrey.copy(alpha = 0.6f), topLeft = tl, size = Size(w, h))
                    drawRect(AcTokens.OpGrey, topLeft = tl, size = Size(w, h), style = stroke)
                }
            }
        }
    }
}
