package com.example.ui.clipstudio

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.BrollSegmentMode
import com.example.data.ClipRecord
import com.example.data.CoreRenderOptions
import com.example.data.ExportState
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import kotlinx.coroutines.delay

/**
 * 03 CUT: plays the real rig/on-device render (final cut, else talking head, else TTS audio)
 * beside a real waveform-driven stereo LED meter, edits by karaoke transcript or b-roll timeline,
 * and applies real studio-cut / Remotion 4 broadcast render options.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CutScreen(
    state: StudioUiState,
    vm: StudioViewModel,
    onOpenSettings: () -> Unit,
    onOpenNewsroom: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (Int) -> Unit = {}
) {
    val session = state.corePipelineSession
    val opts = session.renderOptions
    val defaultLook = state.newsroomLooks.firstOrNull { it.isDefault }
    val player = remember { vm.playerController.acquirePlayer() }
    val isPlaying by vm.playerController.isPlaying.collectAsStateWithLifecycle()
    val durationMs by vm.playerController.durationMs.collectAsStateWithLifecycle()
    var positionMs by remember { mutableLongStateOf(0L) }
    var mode by rememberSaveable { mutableStateOf("transcript") }
    var editingClause by rememberSaveable { mutableStateOf<Int?>(null) }

    val clipFlow = remember(session.finalClipId) { vm.observeClip(session.finalClipId.orEmpty()) }
    val clip: ClipRecord? by clipFlow.collectAsStateWithLifecycle(initialValue = null)

    val sourceUri: Uri? = remember(
        session.finalVideoSha,
        session.finalVideoUrl,
        session.talkingHeadSha,
        session.talkingHeadVideoUrl,
        session.ttsVoiceSha,
        session.ttsAudioUrl
    ) {
        val finalFile = vm.casStore.fileForSha(session.finalVideoSha, "mp4")
        val headFile = vm.casStore.fileForSha(session.talkingHeadSha, "mp4")
        val wavFile = vm.casStore.fileForSha(session.ttsVoiceSha, "wav")
        when {
            finalFile != null -> Uri.fromFile(finalFile)
            !session.finalVideoUrl.isNullOrBlank() -> Uri.parse(session.finalVideoUrl)
            headFile != null -> Uri.fromFile(headFile)
            !session.talkingHeadVideoUrl.isNullOrBlank() -> Uri.parse(session.talkingHeadVideoUrl)
            wavFile != null -> Uri.fromFile(wavFile)
            !session.ttsAudioUrl.isNullOrBlank() -> Uri.parse(session.ttsAudioUrl)
            else -> null
        }
    }
    val sourceLabel = when {
        session.hasFinalVideo -> "FINAL CUT"
        session.hasTalkingHead -> "TALKING HEAD"
        session.hasTtsAudio -> "VOICE ONLY"
        else -> "NO MEDIA YET"
    }
    LaunchedEffect(sourceUri) { sourceUri?.let { vm.playerController.prepareUri(it, autoPlay = false) } }
    LaunchedEffect(isPlaying) {
        while (true) {
            positionMs = vm.playerController.currentPositionMs()
            if (!isPlaying) break
            delay(100L)
        }
    }
    DisposableEffect(Unit) { onDispose { vm.playerController.pause() } }

    // Auto-detect silence once a talking head exists and gaps are unknown for it.
    LaunchedEffect(session.talkingHeadJobId) {
        if (!session.talkingHeadJobId.isNullOrBlank() && session.silenceGapsJobId != session.talkingHeadJobId) {
            vm.refreshSilenceGaps()
        }
    }

    val unsynced = session.unsyncedClauseIndices
    val activeClause = clauseAtPlayhead(session.clauses, positionMs)

    Box(modifier.fillMaxSize().background(Cs.Black).testTag("cs_cut_root")) {
        Column(Modifier.fillMaxSize()) {
            CsHeader {
                StageCrumb(active = 3, label = "CUT", onSelect = onNavigate)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val (badge, color) = when {
                        session.voicedClauseTexts.isEmpty() -> "NOT VOICED" to Cs.Muted
                        unsynced.isNotEmpty() -> "${unsynced.size} UNSYNCED EDIT${if (unsynced.size == 1) "" else "S"}" to Cs.Amber
                        else -> "VOICE SYNCED" to Cs.Muted
                    }
                    Text(badge, style = ndot(11.sp, color), maxLines = 1, modifier = Modifier.testTag("cs_cut_badge"))
                }
                Text(
                    "${mmss(positionMs)} / ${mmss(durationMs)}",
                    style = mono(11.sp, Cs.Ink, FontWeight.Medium),
                    maxLines = 1,
                    modifier = Modifier.testTag("cs_cut_timecode")
                )
                CsWell(padding = 2.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        CsKeyLabel(
                            "↶",
                            onClick = vm::undoCoreEdit,
                            enabled = state.coreUndoDepth > 0,
                            size = 14.sp,
                            horizontalPadding = 0.dp,
                            modifier = Modifier.width(30.dp).height(28.dp).testTag("cs_cut_undo")
                        )
                        CsKeyLabel(
                            "⚙",
                            onClick = onOpenSettings,
                            size = 14.sp,
                            horizontalPadding = 0.dp,
                            modifier = Modifier.width(30.dp).height(28.dp).testTag("cs_cut_settings")
                        )
                    }
                }
            }
            val busy = if (state.isCoreStageBusy) {
                (state.coreStageStatusLabel ?: "Working…") + " ${(state.coreStageProgress * 100).toInt()}%"
            } else if (state.isGeneratingBackdrop) "Generating backdrop…" else null
            CsStatusLine(busy, state.errorMessage, state.statusNotice, vm::dismissError, vm::dismissNotice)

            // Video monitor + real waveform stereo meter
            Column(Modifier.fillMaxWidth().hairlineBottom().padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CsStereoMeter(
                        peaks = session.waveformPeaks,
                        positionMs = positionMs,
                        durationMs = if (durationMs > 0L) durationMs else session.ttsDurationMs,
                        isPlaying = isPlaying,
                        modifier = Modifier.fillMaxHeight()
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(if (opts.aspect == CoreRenderOptions.ASPECT_HORIZONTAL) 16f / 9f else 370f / 312f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Cs.Video)
                            .border(1.dp, Cs.Hairline, RoundedCornerShape(2.dp))
                            .testTag("cs_cut_monitor")
                    ) {
                        if (sourceUri != null) {
                            val punchScale = if (opts.punchIn) 1.06f else 1.0f
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        this.player = player
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = punchScale
                                        scaleY = punchScale
                                    }
                            )
                        } else {
                            Text(
                                "No render yet. Voice the script, then render the talking head.",
                                style = sans(13.sp, Cs.Muted, 18.sp),
                                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                            )
                        }
                        Text(sourceLabel, style = mono(9.sp, Cs.Dim), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(24.dp).padding(start = 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "LOOK: ${defaultLook?.name ?: "NONE"}",
                        style = mono(10.sp, Cs.Muted),
                        modifier = Modifier.weight(1f).clickable(onClick = onOpenNewsroom).testTag("cs_cut_look"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "[captions: ${if (opts.captions) "on" else "off"}]",
                        style = mono(10.sp, Cs.Ink),
                        modifier = Modifier.clickable { vm.setCoreCaptions(!opts.captions) }.testTag("cs_cut_captions")
                    )
                    Text(" · ", style = mono(10.sp, Cs.Muted))
                    Text(
                        if (opts.aspect == CoreRenderOptions.ASPECT_VERTICAL) "[9:16 | 16:9]" else "[16:9 | 9:16]",
                        style = mono(10.sp, Cs.Ink),
                        modifier = Modifier.clickable {
                            vm.setCoreAspect(
                                if (opts.aspect == CoreRenderOptions.ASPECT_VERTICAL) CoreRenderOptions.ASPECT_HORIZONTAL else CoreRenderOptions.ASPECT_VERTICAL
                            )
                        }.testTag("cs_cut_aspect")
                    )
                }
            }

            // Mode switch
            CsWell(Modifier.fillMaxWidth().height(44.dp).hairlineBottom(), radius = 0.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("transcript" to "edit by transcript", "timeline" to "edit b-roll timeline").forEach { (m, l) ->
                        CsKeyLabel(
                            l,
                            onClick = { mode = m },
                            role = if (mode == m) KeyRole.ACTIVE else KeyRole.STD,
                            size = 11.sp,
                            modifier = Modifier.weight(1f).fillMaxSize().testTag("cs_cut_mode_$m")
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().background(Color08).padding(12.dp)) {
                if (session.clauses.isEmpty()) {
                    Text("No story loaded. Create the video from 02 SCRIPT.", style = sans(15.sp, Cs.Muted, 20.sp))
                } else if (mode == "transcript") {
                    TranscriptPane(state, vm, positionMs, unsynced, editingClause, onEdit = { editingClause = it })
                } else {
                    TimelinePane(state, vm, positionMs, durationMs)
                }
            }

            // Action deck: 2x2 bevelled pip keys on dot-grid DeckPlate
            Box(Modifier.fillMaxWidth().dotPanel().hairlineTop().padding(12.dp)) {
                CsWell(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CsKeyLabel(
                                "re-voice edited words",
                                onClick = vm::resyncEditedVoice,
                                role = if (unsynced.isNotEmpty()) KeyRole.REPAIR else KeyRole.STD,
                                enabled = unsynced.isNotEmpty() && !state.isCoreStageBusy,
                                size = 11.sp,
                                pip = true,
                                contentAlignment = Arrangement.Start,
                                modifier = Modifier.weight(1f).height(48.dp).testTag("cs_cut_revoice")
                            )
                            val here = session.clauses.getOrNull(activeClause)
                            CsKeyLabel(
                                if (here?.segmentMode == BrollSegmentMode.BROLL_CUTAWAY) "remove b-roll here" else "insert b-roll here",
                                onClick = { vm.toggleCutClauseBroll(activeClause) },
                                enabled = here != null,
                                size = 11.sp,
                                pip = true,
                                contentAlignment = Arrangement.Start,
                                modifier = Modifier.weight(1f).height(48.dp).testTag("cs_cut_insert_broll")
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val gapsKnown = session.silenceGapsJobId != null && session.silenceGapsJobId == session.talkingHeadJobId
                            val gapLabel = when {
                                state.isDetectingSilence -> "…"
                                gapsKnown -> session.silenceGaps.size.toString()
                                else -> "?"
                            }
                            CsKeyLabel(
                                "strip silence gaps ($gapLabel)",
                                onClick = { vm.setCoreStripSilence(!opts.stripSilence) },
                                role = if (opts.stripSilence) KeyRole.ACTIVE else KeyRole.STD,
                                size = 11.sp,
                                pip = true,
                                contentAlignment = Arrangement.Start,
                                modifier = Modifier.weight(1f).height(48.dp).testTag("cs_cut_strip_silence")
                            )
                            CsKeyLabel(
                                "camera punch-in [${if (opts.punchIn) "on" else "off"}]",
                                onClick = { vm.setCorePunchIn(!opts.punchIn) },
                                role = if (opts.punchIn) KeyRole.ACTIVE else KeyRole.STD,
                                size = 11.sp,
                                pip = true,
                                contentAlignment = Arrangement.Start,
                                modifier = Modifier.weight(1f).height(48.dp).testTag("cs_cut_punch_in")
                            )
                        }
                        session.lastCutStats?.let { st ->
                            Text(
                                "LAST CUT: ${st.aspect} · ${st.brollCutCount} b-roll · ${st.captionCount} captions · " +
                                    "${st.punchInCount} punch-ins · ${st.removedSilenceGapCount} gaps (-${st.removedSilenceMs} ms)",
                                style = mono(9.sp, Cs.Muted),
                                maxLines = 2,
                                modifier = Modifier.padding(horizontal = 4.dp).testTag("cs_cut_stats")
                            )
                        }
                    }
                }
            }

            CsBottomBar {
                CsKeyLabel(
                    if (isPlaying) "❚❚ pause" else "▶ play",
                    onClick = { vm.playerController.togglePlayPause() },
                    enabled = sourceUri != null,
                    modifier = Modifier.weight(103f).fillMaxSize().testTag("cs_cut_play")
                )
                CsKeyLabel(
                    "sync voice (${unsynced.size})",
                    onClick = vm::resyncEditedVoice,
                    role = if (unsynced.isNotEmpty()) KeyRole.REPAIR else KeyRole.STD,
                    enabled = unsynced.isNotEmpty() && !state.isCoreStageBusy,
                    modifier = Modifier.weight(144f).fillMaxSize().testTag("cs_cut_sync")
                )
                val signature = session.cutSignature(defaultLook)
                val cutCurrent = session.hasFinalVideo && session.lastCutSignature == signature
                val (label, action) = when {
                    session.clauses.isEmpty() -> "export video" to {}
                    !session.hasTtsAudio -> "voice script" to { vm.runCoreStage1Tts() }
                    !session.hasTalkingHead -> "render head" to { vm.runCoreStage3SpeechToHead() }
                    !cutCurrent -> (if (session.hasFinalVideo) "re-render cut" else "render cut") to { vm.runCoreStage4ChopBroll() }
                    else -> (if (state.exportState == ExportState.EXPORTED) "exported ✓" else "export video") to {
                        clip?.let { vm.exportClip(it, openShareSheet = true) } ?: Unit
                    }
                }
                CsKeyLabel(
                    label,
                    onClick = action,
                    role = KeyRole.COMMIT,
                    weight = FontWeight.Medium,
                    trailingArrow = true,
                    enabled = session.clauses.isNotEmpty() && !state.isCoreStageBusy && state.exportState != ExportState.EXPORTING &&
                        (unsynced.isEmpty() || !session.hasTtsAudio) && (!cutCurrent || clip != null),
                    modifier = Modifier.weight(165f).fillMaxSize().testTag("cs_cut_primary")
                )
            }
        }
    }
}

/** Clause under the playhead; before Stage 1 timing exists (all windows 0) it is the first clause. */
internal fun clauseAtPlayhead(clauses: List<com.example.data.CoreStoryClause>, positionMs: Long): Int {
    if (clauses.isEmpty()) return 0
    if (clauses.all { it.endMs <= 0L }) return 0
    return clauses.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
}

private val Color08 = androidx.compose.ui.graphics.Color(0xFF08080A)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun TranscriptPane(
    state: StudioUiState,
    vm: StudioViewModel,
    positionMs: Long,
    unsynced: List<Int>,
    editingClause: Int?,
    onEdit: (Int?) -> Unit
) {
    val session = state.corePipelineSession
    val listState = rememberLazyListState()
    val activeClause = clauseAtPlayhead(session.clauses, positionMs)
    LaunchedEffect(activeClause) { if (state.corePipelineSession.clauses.isNotEmpty()) listState.animateScrollToItem(activeClause) }
    LazyColumn(
        Modifier.fillMaxSize().background(Cs.Black).testTag("cs_transcript"),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(session.clauses, key = { _, c -> c.id }) { idx, clause ->
            val dirty = idx in unsynced
            if (editingClause == idx) {
                var draft by remember(clause.id) { mutableStateOf(clause.spokenText) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = sans(17.sp, Cs.Ink, 25.sp),
                        cursorBrush = SolidColor(Cs.Ink),
                        modifier = Modifier.fillMaxWidth().border(1.dp, Cs.Amber).padding(6.dp).testTag("cs_clause_editor_$idx")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CsKeyLabel("apply", onClick = { vm.editCutClauseText(idx, draft); onEdit(null) }, role = KeyRole.COMMIT, size = 11.sp, modifier = Modifier.height(30.dp).testTag("cs_clause_apply_$idx"))
                        CsKeyLabel("cancel", onClick = { onEdit(null) }, size = 11.sp, modifier = Modifier.height(30.dp))
                    }
                }
                return@itemsIndexed
            }
            val words = if (dirty) {
                emptyList()
            } else {
                session.alignedWords.filter { it.startMs >= clause.startMs && it.startMs < clause.endMs.coerceAtLeast(clause.startMs + 1) }
            }
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = { onEdit(idx) })
                    .testTag("cs_clause_$idx"),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    "[${mmss(clause.startMs)}]",
                    style = mono(10.sp, Cs.Muted).copy(lineHeight = 25.sp),
                    modifier = Modifier.clickable { vm.playerController.seekTo(clause.startMs) }.padding(end = 3.dp)
                )
                if (words.isNotEmpty()) {
                    words.forEach { w ->
                        val active = positionMs in w.startMs until w.endMs
                        Text(
                            w.word,
                            style = sans(17.sp, if (w.startMs <= positionMs) Cs.Ink else Cs.Muted, 25.sp),
                            modifier = Modifier
                                .then(if (active) Modifier.drawBehind {
                                    drawLine(Cs.Red, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx())
                                } else Modifier)
                                .clickable { vm.playerController.seekTo(w.startMs) }
                        )
                    }
                } else {
                    Text(
                        clause.spokenText,
                        style = sans(17.sp, if (dirty) Cs.Ink else Cs.Muted, 25.sp),
                        modifier = if (dirty) Modifier.border(1.dp, Cs.Amber, RoundedCornerShape(2.dp)).padding(horizontal = 5.dp) else Modifier
                    )
                    if (dirty) Text("unsynced", style = mono(9.sp, Cs.Amber), modifier = Modifier.padding(top = 6.dp))
                }
                if (clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY) {
                    Text(
                        "[+ B-ROLL: ${clause.brollCaption.ifBlank { clause.spokenText }.take(28).uppercase()}]",
                        style = mono(10.sp, Cs.Ink),
                        modifier = Modifier.background(Cs.Track, RoundedCornerShape(2.dp)).border(1.dp, Cs.Hairline, RoundedCornerShape(2.dp)).padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        item { Text("long-press a line to edit its words", style = mono(9.sp, Cs.Dim), modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun TimelinePane(state: StudioUiState, vm: StudioViewModel, positionMs: Long, durationMs: Long) {
    val session = state.corePipelineSession
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("cs_timeline"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(session.clauses, key = { _, c -> c.id }) { idx, clause ->
                val broll = clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
                val active = positionMs in clause.startMs until clause.endMs.coerceAtLeast(clause.startMs + 1)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(if (broll) Cs.Pinned else Cs.Track, RoundedCornerShape(2.dp))
                        .border(1.dp, if (active) Cs.Ink else if (broll) Cs.Muted else Cs.Hairline, RoundedCornerShape(2.dp))
                        .clickable { vm.playerController.seekTo(clause.startMs) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${mmss(clause.startMs)}–${mmss(clause.endMs)}", style = mono(9.sp, Cs.Muted))
                    Text(
                        if (broll) "B-ROLL: ${clause.brollCaption.ifBlank { clause.spokenText }}" else clause.spokenText,
                        style = mono(10.sp, Cs.Ink),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    CsKeyLabel(
                        if (broll) "head" else "b-roll",
                        onClick = { vm.toggleCutClauseBroll(idx) },
                        role = if (broll) KeyRole.ACTIVE else KeyRole.STD,
                        size = 10.sp,
                        horizontalPadding = 8.dp,
                        modifier = Modifier.height(26.dp).testTag("cs_timeline_toggle_$idx")
                    )
                }
            }
        }
        val peaks = session.waveformPeaks
        if (peaks.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .drawBehind {
                        val bw = size.width / peaks.size
                        val played = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                        peaks.forEachIndexed { i, p ->
                            val h = (p.coerceIn(0f, 1f) * size.height).coerceAtLeast(2f)
                            val x = i * bw
                            drawRect(
                                if (i.toFloat() / peaks.size <= played) Cs.Ink else Cs.Muted,
                                topLeft = Offset(x, (size.height - h) / 2),
                                size = androidx.compose.ui.geometry.Size((bw - 2f).coerceAtLeast(1f), h)
                            )
                        }
                        val px = played * size.width
                        drawLine(Cs.Red, Offset(px, 0f), Offset(px, size.height), 1.dp.toPx())
                    }
                    .testTag("cs_waveform")
            )
        }
    }
}
