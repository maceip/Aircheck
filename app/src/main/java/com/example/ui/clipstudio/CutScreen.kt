package com.example.ui.clipstudio

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.Notes
import androidx.compose.material.icons.automirrored.sharp.Undo
import androidx.compose.material.icons.sharp.ClosedCaption
import androidx.compose.material.icons.sharp.ContentCut
import androidx.compose.material.icons.sharp.CropLandscape
import androidx.compose.material.icons.sharp.CropPortrait
import androidx.compose.material.icons.sharp.Edit
import androidx.compose.material.icons.sharp.Movie
import androidx.compose.material.icons.sharp.Palette
import androidx.compose.material.icons.sharp.Pause
import androidx.compose.material.icons.sharp.Person
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.material.icons.sharp.RecordVoiceOver
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.Share
import androidx.compose.material.icons.sharp.Sync
import androidx.compose.material.icons.sharp.SyncProblem
import androidx.compose.material.icons.sharp.ZoomIn
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.AlignedWord
import com.example.data.BrollSegmentMode
import com.example.data.ClipRecord
import com.example.data.CoreRenderOptions
import com.example.data.CoreStoryClause
import com.example.data.ExportState
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import kotlinx.coroutines.delay

/**
 * 03 CUT (Clip Studio v5):
 * - 56dp top bar: `01 · 02 · 03 CUT`, amber Ndot 11sp `"N LINE(S) NEED RE-VOICE"` badge when dirty, `undo`, `settings`.
 * - 218dp sunken `#000` ExoPlayer video monitor (`m:ss / m:ss` top-left, centered 64dp circular Play/Pause,
 *   live caption bottom, 3dp `#D71921` progress bar).
 * - 44dp horizontal tool pill row (`captions`, `trim pauses`, `punch-in`, `9:16`/`16:9`, `look`).
 * - Sunken 2-way `words` · `b-roll` mode switch.
 * - Tap-to-expand transcript clauses (seeks & expands `edit words` + `b-roll here` / `back to face`;
 *   amber 2dp left edge + `"edited · voice not synced"` when dirty; `B-ROLL` tag when cutaway).
 * - Single sequential primary dock key (`voice the script` -> amber `re-voice N edited line(s)` ->
 *   `render talking head` -> `render the video` -> `export & share` -> `share again`).
 */
@Composable
fun CutScreen(
    state: StudioUiState,
    vm: StudioViewModel,
    onOpenSettings: () -> Unit,
    onOpenNewsroom: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (Int) -> Unit = {},
    renderGlobalOverlays: Boolean = true
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

    LaunchedEffect(sourceUri) {
        sourceUri?.let { vm.playerController.prepareUri(it, autoPlay = false) }
    }
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
    val effectiveDurationMs = remember(durationMs, session.ttsDurationMs, session.clauses) {
        when {
            durationMs > 0L -> durationMs
            session.ttsDurationMs > 0L -> session.ttsDurationMs
            else -> session.clauses.maxOfOrNull { it.endMs } ?: 0L
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Cs.Body)
            .testTag("cs_cut_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            CsTopBar(
                activeStage = 3,
                onNavigate = onNavigate
            ) {
                if (unsynced.isNotEmpty()) {
                    val badgeText = if (unsynced.size == 1) {
                        "1 LINE NEEDS RE-VOICE"
                    } else {
                        "${unsynced.size} LINES NEED RE-VOICE"
                    }
                    Text(
                        text = badgeText,
                        style = ndot(11.sp, Cs.Amber),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .testTag("cs_cut_badge")
                    )
                }
                CsIconKey(
                    icon = Icons.AutoMirrored.Sharp.Undo,
                    contentDescription = "Undo cut edit",
                    onClick = vm::undoCoreEdit,
                    enabled = state.coreUndoDepth > 0,
                    modifier = Modifier.testTag("cs_cut_undo")
                )
                CsIconKey(
                    icon = Icons.Sharp.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("cs_cut_settings")
                )
            }

            CsStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

            // 218dp sunken #000 ExoPlayer video monitor
            VideoMonitorPanel(
                player = player,
                sourceUri = sourceUri,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = effectiveDurationMs,
                punchIn = opts.punchIn,
                liveCaption = if (opts.captions && session.clauses.isNotEmpty()) {
                    session.clauses.getOrNull(activeClause)?.spokenText.orEmpty()
                } else {
                    ""
                },
                onTogglePlay = {
                    vm.playerController.togglePlayPause()
                    positionMs = vm.playerController.currentPositionMs()
                }
            )

            // 44dp horizontal tool pill row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, top = 12.dp, end = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CsPill(
                    label = "captions",
                    selected = opts.captions,
                    icon = Icons.Sharp.ClosedCaption,
                    onClick = { vm.setCoreCaptions(!opts.captions) },
                    modifier = Modifier.testTag("cs_cut_captions")
                )
                CsPill(
                    label = "trim pauses",
                    selected = opts.stripSilence,
                    icon = Icons.Sharp.ContentCut,
                    onClick = { vm.setCoreStripSilence(!opts.stripSilence) },
                    modifier = Modifier.testTag("cs_cut_strip_silence")
                )
                CsPill(
                    label = "punch-in",
                    selected = opts.punchIn,
                    icon = Icons.Sharp.ZoomIn,
                    onClick = { vm.setCorePunchIn(!opts.punchIn) },
                    modifier = Modifier.testTag("cs_cut_punch_in")
                )
                val isVertical = opts.aspect == CoreRenderOptions.ASPECT_VERTICAL
                CsPill(
                    label = if (isVertical) "9:16" else "16:9",
                    selected = false,
                    icon = if (isVertical) Icons.Sharp.CropPortrait else Icons.Sharp.CropLandscape,
                    onClick = {
                        vm.setCoreAspect(
                            if (isVertical) CoreRenderOptions.ASPECT_HORIZONTAL else CoreRenderOptions.ASPECT_VERTICAL
                        )
                    },
                    modifier = Modifier.testTag("cs_cut_aspect")
                )
                CsPill(
                    label = "look",
                    selected = false,
                    icon = Icons.Sharp.Palette,
                    onClick = onOpenNewsroom,
                    modifier = Modifier.testTag("cs_cut_look")
                )
            }

            // Sunken 2-way `words` · `b-roll` mode switch
            CsSegmentedRow(
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 10.dp)
            ) {
                CsSegment(
                    selected = mode == "transcript",
                    onClick = { mode = "transcript" },
                    height = 44.dp,
                    modifier = Modifier.testTag("cs_cut_mode_transcript")
                ) { tint ->
                    Icon(Icons.AutoMirrored.Sharp.Notes, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    Text("words", style = mono(13.sp, tint), maxLines = 1)
                }
                CsSegment(
                    selected = mode == "timeline",
                    onClick = { mode = "timeline" },
                    height = 44.dp,
                    modifier = Modifier.testTag("cs_cut_mode_timeline")
                ) { tint ->
                    Icon(Icons.Sharp.Movie, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    Text("b-roll", style = mono(13.sp, tint), maxLines = 1)
                }
            }

            // Sunken #000 Transcript / B-roll Panel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .sunkenPanel(radius = 18.dp)
            ) {
                if (session.clauses.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No story loaded yet. Go to 02 SCRIPT and tap make the video.",
                            style = sans(15.sp, Cs.Muted, 21.sp)
                        )
                    }
                } else if (mode == "transcript") {
                    TranscriptWordsPane(
                        state = state,
                        vm = vm,
                        positionMs = positionMs,
                        unsynced = unsynced,
                        editingClause = editingClause,
                        onEdit = { editingClause = it },
                        onSeekLocal = { ms -> positionMs = ms }
                    )
                } else {
                    BrollModePane(
                        clauses = session.clauses,
                        vm = vm,
                        onSeekLocal = { ms -> positionMs = ms }
                    )
                }
            }

            // Single sequential primary pipeline dock key
            val signature = session.cutSignature(defaultLook)
            val cutCurrent = session.hasFinalVideo && session.lastCutSignature == signature
            val isCutBusy = state.isCoreStageBusy || state.exportState == ExportState.EXPORTING
            val busyLabel = when {
                state.exportState == ExportState.EXPORTING -> "exporting…"
                else -> state.coreStageStatusLabel ?: "rendering…"
            }
            val busyFraction = if (state.isCoreStageBusy) state.coreStageProgress else null

            val dockSpec = resolveCutPrimarySpec(
                hasClauses = session.clauses.isNotEmpty(),
                hasTtsAudio = session.hasTtsAudio,
                unsyncedCount = unsynced.size,
                hasTalkingHead = session.hasTalkingHead,
                cutCurrent = cutCurrent,
                exportState = state.exportState,
                clip = clip,
                vm = vm
            )

            CsDock(
                primaryLabel = dockSpec.label,
                primaryLeadingIcon = dockSpec.icon,
                primaryRole = dockSpec.role,
                primaryEnabled = dockSpec.enabled && !isCutBusy,
                primaryTag = "cs_cut_primary",
                onPrimaryClick = dockSpec.onClick,
                isBusy = isCutBusy,
                busyLabel = busyLabel,
                busyFraction = busyFraction
            )
        }

        if (renderGlobalOverlays) {
            CsToastOverlay(
                message = state.toastMessage,
                onDismiss = vm::dismissToast
            )
        }
    }
}

private data class CutPrimarySpec(
    val label: String,
    val icon: ImageVector,
    val role: KeyRole,
    val enabled: Boolean,
    val onClick: () -> Unit
)

private fun resolveCutPrimarySpec(
    hasClauses: Boolean,
    hasTtsAudio: Boolean,
    unsyncedCount: Int,
    hasTalkingHead: Boolean,
    cutCurrent: Boolean,
    exportState: ExportState,
    clip: ClipRecord?,
    vm: StudioViewModel
): CutPrimarySpec {
    return when {
        !hasClauses -> CutPrimarySpec(
            label = "voice the script",
            icon = Icons.Sharp.RecordVoiceOver,
            role = KeyRole.COMMIT,
            enabled = false,
            onClick = {}
        )
        !hasTtsAudio -> CutPrimarySpec(
            label = "voice the script",
            icon = Icons.Sharp.RecordVoiceOver,
            role = KeyRole.COMMIT,
            enabled = true,
            onClick = vm::runCoreStage1Tts
        )
        unsyncedCount > 0 -> CutPrimarySpec(
            label = "re-voice $unsyncedCount edited line${if (unsyncedCount == 1) "" else "s"}",
            icon = Icons.Sharp.Sync,
            role = KeyRole.REPAIR,
            enabled = true,
            onClick = vm::resyncEditedVoice
        )
        !hasTalkingHead -> CutPrimarySpec(
            label = "render talking head",
            icon = Icons.Sharp.Person,
            role = KeyRole.COMMIT,
            enabled = true,
            onClick = vm::runCoreStage3SpeechToHead
        )
        !cutCurrent -> CutPrimarySpec(
            label = "render the video",
            icon = Icons.Sharp.Movie,
            role = KeyRole.COMMIT,
            enabled = true,
            onClick = { vm.runCoreStage4ChopBroll() }
        )
        exportState != ExportState.EXPORTED -> CutPrimarySpec(
            label = "export & share",
            icon = Icons.Sharp.Share,
            role = KeyRole.COMMIT,
            enabled = clip != null,
            onClick = { clip?.let { vm.exportClip(it, openShareSheet = true) } }
        )
        else -> CutPrimarySpec(
            label = "share again",
            icon = Icons.Sharp.Share,
            role = KeyRole.STD,
            enabled = clip != null,
            onClick = { clip?.let { vm.exportClip(it, openShareSheet = true) } }
        )
    }
}

@Composable
private fun VideoMonitorPanel(
    player: androidx.media3.common.Player,
    sourceUri: Uri?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    punchIn: Boolean,
    liveCaption: String,
    onTogglePlay: () -> Unit
) {
    val progressFraction = remember(positionMs, durationMs) {
        if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .height(218.dp)
            .sunkenPanel(radius = 18.dp)
            .testTag("cs_cut_monitor"),
        contentAlignment = Alignment.Center
    ) {
        if (sourceUri != null) {
            val punchScale = if (punchIn) 1.06f else 1.0f
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
        }

        // Top-left timecode (`m:ss / m:ss`)
        Text(
            text = "${mmss(positionMs)} / ${mmss(durationMs)}",
            style = mono(11.sp, Cs.Muted),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 12.dp)
                .testTag("cs_cut_timecode")
        )

        // Centered 64dp circular Play/Pause button
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Cs.Raised.copy(alpha = 0.9f))
                .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape)
                .clickable(
                    enabled = sourceUri != null,
                    role = Role.Button,
                    onClick = onTogglePlay
                )
                .testTag("cs_cut_play"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Sharp.Pause else Icons.Sharp.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = if (sourceUri != null) Cs.Ink else Cs.Dim,
                modifier = Modifier.size(36.dp)
            )
        }

        // Live caption at bottom (Geist 500 14/19, white)
        if (liveCaption.isNotBlank()) {
            Text(
                text = liveCaption,
                style = sans(14.sp, Cs.White, 19.sp, FontWeight.Medium),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 16.dp)
                    .testTag("cs_cut_live_caption")
            )
        }

        // 3dp progress bar (#D71921 over #2C2C32)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 18.dp, end = 18.dp, bottom = 6.dp)
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Cs.Hairline)
        ) {
            if (progressFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressFraction)
                        .background(Cs.Red)
                )
            }
        }
    }
}

/** Clause under the playhead; before Stage 1 timing exists (all windows 0) it is the first clause. */
internal fun clauseAtPlayhead(clauses: List<CoreStoryClause>, positionMs: Long): Int {
    if (clauses.isEmpty()) return 0
    if (clauses.all { it.endMs <= 0L }) return 0
    return clauses.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
}

@Composable
private fun TranscriptWordsPane(
    state: StudioUiState,
    vm: StudioViewModel,
    positionMs: Long,
    unsynced: List<Int>,
    editingClause: Int?,
    onEdit: (Int?) -> Unit,
    onSeekLocal: (Long) -> Unit
) {
    val session = state.corePipelineSession
    val listState = rememberLazyListState()
    val activeClause = clauseAtPlayhead(session.clauses, positionMs)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("cs_transcript"),
        state = listState
    ) {
        itemsIndexed(session.clauses, key = { _, c -> c.id }) { idx, clause ->
            val dirty = idx in unsynced
            val broll = clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
            val isOpen = state.openClause == idx
            val isActive = activeClause == idx && session.clauses.any { it.endMs > 0L }

            val clauseWords = remember(dirty, session.alignedWords, clause.startMs, clause.endMs) {
                if (dirty) {
                    emptyList()
                } else {
                    session.alignedWords.filter {
                        it.startMs >= clause.startMs && it.startMs < clause.endMs.coerceAtLeast(clause.startMs + 1L)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isActive) Cs.ActiveClauseBg else Color.Transparent)
                    .drawBehind {
                        if (dirty) {
                            drawRect(
                                color = Cs.Amber,
                                topLeft = Offset.Zero,
                                size = Size(2.dp.toPx(), size.height)
                            )
                        }
                        drawLine(
                            color = Cs.ClauseDivider,
                            start = Offset(0f, size.height - 0.5f),
                            end = Offset(size.width, size.height - 0.5f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .then(
                        if (editingClause != idx) {
                            Modifier.clickable(role = Role.Button) {
                                vm.toggleOpenCutClause(idx)
                                onSeekLocal(clause.startMs)
                            }
                        } else {
                            Modifier
                        }
                    )
                    .padding(start = 12.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)
                    .testTag("cs_clause_$idx")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = mmss(clause.startMs),
                        style = mono(11.sp, Cs.Muted).copy(lineHeight = 25.sp),
                        maxLines = 1,
                        modifier = Modifier.width(44.dp)
                    )

                    if (editingClause == idx) {
                        InlineClauseWordsEditor(
                            idx = idx,
                            initialText = clause.spokenText,
                            onSave = { updated ->
                                vm.editCutClauseText(idx, updated)
                                onEdit(null)
                                vm.setOpenCutClause(null)
                            },
                            onCancel = { onEdit(null) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        ClauseKaraokeWords(
                            spokenText = clause.spokenText,
                            words = clauseWords,
                            positionMs = positionMs,
                            clauseStartMs = clause.startMs,
                            dirty = dirty,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (dirty && editingClause != idx) {
                    Row(
                        modifier = Modifier.padding(start = 52.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Sharp.SyncProblem,
                            contentDescription = null,
                            tint = Cs.Amber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "edited · voice not synced",
                            style = mono(12.sp, Cs.Amber),
                            maxLines = 1
                        )
                    }
                }

                if (broll && editingClause != idx) {
                    Row(
                        modifier = Modifier
                            .padding(start = 52.dp, top = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Cs.Track)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Sharp.Movie,
                            contentDescription = null,
                            tint = Cs.Ink,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("B-ROLL", style = mono(11.sp, Cs.Ink), maxLines = 1)
                    }
                }

                if (isOpen && editingClause != idx) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 52.dp, top = 10.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Cs.Raised)
                                .border(1.dp, Cs.Hairline, RoundedCornerShape(14.dp))
                                .clickable(role = Role.Button) { onEdit(idx) }
                                .padding(horizontal = 10.dp)
                                .testTag("cs_clause_edit_btn_$idx"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Sharp.Edit, contentDescription = null, tint = Cs.Ink, modifier = Modifier.size(18.dp))
                            Text("edit words", style = mono(13.sp, Cs.Ink), maxLines = 1)
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Cs.Raised)
                                .border(1.dp, Cs.Hairline, RoundedCornerShape(14.dp))
                                .clickable(role = Role.Button) { vm.toggleCutClauseBroll(idx) }
                                .padding(horizontal = 10.dp)
                                .testTag("cs_clause_broll_btn_$idx"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Sharp.Movie, contentDescription = null, tint = Cs.Ink, modifier = Modifier.size(18.dp))
                            Text(
                                text = if (broll) "back to face" else "b-roll here",
                                style = mono(13.sp, Cs.Ink),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClauseKaraokeWords(
    spokenText: String,
    words: List<AlignedWord>,
    positionMs: Long,
    clauseStartMs: Long,
    dirty: Boolean,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    data class WordSpanRange(val charRange: IntRange, val active: Boolean)

    val built: Pair<AnnotatedString, IntRange?> = remember(spokenText, words, positionMs, clauseStartMs, dirty) {
        if (words.isEmpty()) {
            val color = if (dirty || positionMs >= clauseStartMs) Cs.Ink else Cs.Muted
            buildAnnotatedString {
                pushStyle(SpanStyle(color = color))
                append(spokenText)
                pop()
            } to null
        } else {
            var activeRange: IntRange? = null
            val str = buildAnnotatedString {
                words.forEachIndexed { idx, w ->
                    val startChar = length
                    val spoken = dirty || positionMs >= w.startMs
                    val isCurrent = !dirty && positionMs >= w.startMs && positionMs < w.endMs
                    pushStyle(SpanStyle(color = if (spoken) Cs.Ink else Cs.Muted))
                    append(w.word)
                    pop()
                    val endCharExcl = length
                    if (isCurrent && endCharExcl > startChar) {
                        activeRange = startChar until endCharExcl
                    }
                    if (idx < words.lastIndex) append(" ")
                }
            }
            str to activeRange
        }
    }

    val activeRange = built.second
    Text(
        text = built.first,
        style = sans(17.sp, Cs.Ink, 25.sp),
        onTextLayout = { layout = it },
        modifier = modifier.drawBehind {
            val l = layout ?: return@drawBehind
            val range = activeRange ?: return@drawBehind
            if (range.isEmpty() || l.layoutInput.text.isEmpty()) return@drawBehind
            val safeStart = range.first.coerceIn(0, l.layoutInput.text.length - 1)
            val safeEnd = range.last.coerceIn(safeStart, l.layoutInput.text.length - 1)
            val a = l.getBoundingBox(safeStart)
            val b = l.getBoundingBox(safeEnd)
            val y = maxOf(a.bottom, b.bottom) - 1.dp.toPx()
            if (b.right > a.left) {
                drawLine(Cs.Red, Offset(a.left, y), Offset(b.right, y), 2.dp.toPx())
            }
        }
    )
}

@Composable
private fun InlineClauseWordsEditor(
    idx: Int,
    initialText: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fieldState = rememberTextFieldState(initialText = initialText)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BasicTextField(
            state = fieldState,
            textStyle = sans(17.sp, Cs.Ink, 25.sp),
            cursorBrush = SolidColor(Cs.Ink),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 5),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Cs.Body)
                .border(1.dp, Cs.Amber, RoundedCornerShape(12.dp))
                .padding(10.dp)
                .testTag("cs_clause_editor_$idx")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CsKeyLabel(
                label = "save words",
                onClick = { onSave(fieldState.text.toString()) },
                role = KeyRole.COMMIT,
                radius = 12.dp,
                weight = FontWeight.Medium,
                enabled = fieldState.text.toString().isNotBlank(),
                modifier = Modifier
                    .height(44.dp)
                    .testTag("cs_clause_apply_$idx")
            )
            CsKeyLabel(
                label = "cancel",
                onClick = onCancel,
                role = KeyRole.STD,
                radius = 12.dp,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("cs_clause_cancel_$idx")
            )
        }
    }
}

@Composable
private fun BrollModePane(
    clauses: List<CoreStoryClause>,
    vm: StudioViewModel,
    onSeekLocal: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .testTag("cs_timeline"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(clauses, key = { _, c -> c.id }) { idx, clause ->
            val broll = clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cs.BrollRow)
                    .clickable(role = Role.Button) {
                        vm.playerController.seekTo(clause.startMs)
                        onSeekLocal(clause.startMs)
                    }
                    .padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = mmss(clause.startMs),
                    style = mono(11.sp, Cs.Muted),
                    maxLines = 1,
                    modifier = Modifier.width(40.dp)
                )
                Text(
                    text = clause.spokenText,
                    style = sans(14.sp, Cs.Ink, 19.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                val toggleBg = if (broll) Cs.Ink else Cs.Raised
                val toggleFg = if (broll) Cs.Black else Cs.Ink
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(toggleBg)
                        .then(
                            if (!broll) Modifier.border(1.dp, Cs.Hairline, RoundedCornerShape(12.dp)) else Modifier
                        )
                        .clickable(role = Role.Button) { vm.toggleCutClauseBroll(idx) }
                        .padding(horizontal = 12.dp)
                        .testTag("cs_timeline_toggle_$idx"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (broll) Icons.Sharp.Movie else Icons.Sharp.Person,
                        contentDescription = null,
                        tint = toggleFg,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (broll) "b-roll" else "face",
                        style = mono(12.sp, toggleFg, if (broll) FontWeight.Medium else FontWeight.Normal),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
