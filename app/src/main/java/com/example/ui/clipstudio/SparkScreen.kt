package com.example.ui.clipstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowForward
import androidx.compose.material.icons.automirrored.sharp.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.Anchor
import androidx.compose.material.icons.sharp.Check
import androidx.compose.material.icons.sharp.Edit
import androidx.compose.material.icons.sharp.ExpandLess
import androidx.compose.material.icons.sharp.ExpandMore
import androidx.compose.material.icons.sharp.FiberManualRecord
import androidx.compose.material.icons.sharp.Forum
import androidx.compose.material.icons.sharp.Keyboard
import androidx.compose.material.icons.sharp.Layers
import androidx.compose.material.icons.sharp.Mic
import androidx.compose.material.icons.sharp.Pause
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.Stop
import androidx.compose.material.icons.sharp.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.data.LastVideoSummary
import com.example.draft.ArcBeat
import com.example.draft.DraftTone
import com.example.ui.design.AcCard
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcField
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcMediaKey
import com.example.ui.design.AcMediaWell
import com.example.ui.design.AcPanel
import com.example.ui.design.AcPrimaryKey
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import com.example.ui.draft.DraftDeckUiState
import com.example.ui.draft.DraftDeckViewModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 01 SPARK (design v2):
 * - Empty state: tone keys, the Recorder panel (live waveform display + media well with hold-to-record,
 *   play and stop), "type it instead", and the "continue your last video" card + follow-up directions.
 * - Outline state: note strip, anchors key + Anchors sheet, 4 beat cards (Body 17 text, edit + AI keys),
 *   and the dock (hold-to-steer media key + "make the video" primary key).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SparkScreen(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel,
    onRequestMic: (() -> Unit) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    onNavigate: (Int) -> Unit = {},
    onNewIdea: () -> Unit = { vm.resetNewDraft() },
    onCreateVideo: ((String) -> Unit)? = null,
    lastCompletedVideo: LastVideoSummary? = null,
    renderGlobalOverlays: Boolean = true
) {
    val doc = state.document
    // Stay on the empty hold-to-record surface while actively holding the seed mic (so incremental
    // VAD chunks never unmount the record key out from under the user's thumb mid-sentence).
    val sparkEmpty = (doc.seedTranscript.isBlank() || state.isRecordingSeed) && !state.followupLoaded && doc.arcBeats.isEmpty()

    var typedSheetOpen by rememberSaveable { mutableStateOf(false) }
    var noteEditSheetOpen by rememberSaveable { mutableStateOf(false) }
    val activeFollowupVideo = state.followupVideo ?: lastCompletedVideo

    BackHandler(enabled = typedSheetOpen) { typedSheetOpen = false }
    BackHandler(enabled = noteEditSheetOpen) { noteEditSheetOpen = false }
    BackHandler(enabled = state.anchorsSheetOpen && !sparkEmpty) { vm.setAnchorsSheetOpen(false) }
    BackHandler(enabled = state.continueSheetOpen && activeFollowupVideo != null) { vm.closeContinueSheet() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AcTokens.Chassis)
            .testTag("cs_spark_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            StudioTopBar(
                activeStage = 1,
                onNavigate = onNavigate
            ) {
                if (!sparkEmpty) {
                    AcKey(
                        label = "anchors · ${state.selectedAnchorCount}",
                        onClick = vm::toggleAnchorsSheet,
                        selected = state.anchorsSheetOpen,
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_anchors_button")
                    )
                    AcKey(
                        icon = Icons.AutoMirrored.Sharp.Undo,
                        contentDescription = "Undo beat edit",
                        onClick = vm::undoArcShotEdit,
                        enabled = doc.canUndoArc,
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_spark_undo")
                    )
                    AcKey(
                        icon = Icons.Sharp.Add,
                        contentDescription = "New idea",
                        onClick = onNewIdea,
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_spark_new_idea")
                    )
                }
                AcKey(
                    icon = Icons.Sharp.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_spark_settings")
                )
            }

            StudioStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

            if (sparkEmpty) {
                SparkEmptyContent(
                    state = state,
                    vm = vm,
                    lastCompletedVideo = lastCompletedVideo,
                    onRequestMic = onRequestMic,
                    onOpenTypedSheet = { typedSheetOpen = true },
                    modifier = Modifier.weight(1f)
                )
            } else {
                SparkOutlineContent(
                    state = state,
                    vm = vm,
                    onRequestMic = onRequestMic,
                    onOpenNoteEdit = { noteEditSheetOpen = true },
                    onCreateVideo = onCreateVideo,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (typedSheetOpen) {
            TypedIdeaBottomSheet(
                initialText = doc.seedTranscript,
                onDismiss = { typedSheetOpen = false },
                onSubmit = { text ->
                    typedSheetOpen = false
                    vm.submitTypedNote(text)
                }
            )
        }

        if (noteEditSheetOpen) {
            NoteEditBottomSheet(
                state = state,
                vm = vm,
                onRequestMic = onRequestMic,
                onClose = { noteEditSheetOpen = false }
            )
        }

        if (state.anchorsSheetOpen && !sparkEmpty) {
            AnchorsBottomSheet(state = state, vm = vm)
        }

        if (state.continueSheetOpen && activeFollowupVideo != null) {
            FollowupBottomSheet(state = state, video = activeFollowupVideo, vm = vm)
        }

        if (renderGlobalOverlays) {
            StudioToast(message = state.toastMessage, onDismiss = vm::dismissToast)
            VoiceGlowDitherOverlay(active = state.isAnyRecording, level = state.liveRmsLevel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SparkEmptyContent(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel,
    lastCompletedVideo: LastVideoSummary?,
    onRequestMic: (() -> Unit) -> Unit,
    onOpenTypedSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionCaption("Step 1 of 2 · your idea")
                Text("What's the clip about?", style = AcType.Headline)
                Text(
                    "Pick a tone, then hold record and talk. Rough is fine. Length is set from what you say.",
                    style = AcType.BodySmall
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                DraftTone.entries.forEach { tone ->
                    AcKey(
                        label = tone.label,
                        onClick = { vm.setTone(tone) },
                        selected = state.tone == tone,
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_tone_${tone.wireValue}")
                    )
                }
            }

            RecorderPanel(state = state, vm = vm, onRequestMic = onRequestMic)

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AcKey(
                    label = "type it instead",
                    icon = Icons.Sharp.Keyboard,
                    onClick = onOpenTypedSheet,
                    enabled = !state.isBusy && !state.isAnyRecording,
                    modifier = Modifier.testTag("cs_type_instead")
                )
            }
        }

        if (lastCompletedVideo != null) {
            ContinueLastVideoBlock(state = state, video = lastCompletedVideo, vm = vm)
        }
    }
}

/** Recorder block: live waveform display + media well (hold to record, play, stop). */
@Composable
private fun RecorderPanel(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel,
    onRequestMic: (() -> Unit) -> Unit
) {
    AcPanel(raised = true, contentPadding = PaddingValues(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionCaption("Recorder", color = AcTokens.Red, modifier = Modifier.weight(1f))
            if (state.isRecordingSeed) {
                SectionCaption("● rec", color = AcTokens.Red)
            } else {
                // Perforated OP-1 Field dot-matrix mic grille texture (pure bezel craft).
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .size(width = 44.dp, height = 14.dp)
                        .testTag("cs_recorder_grille")
                ) {
                    val cols = 9
                    val rows = 3
                    val dx = size.width / (cols - 1).coerceAtLeast(1)
                    val dy = size.height / (rows - 1).coerceAtLeast(1)
                    val r = 1.15.dp.toPx()
                    val dotColor = AcTokens.Ink2.copy(alpha = 0.5f)
                    for (row in 0 until rows) {
                        for (col in 0 until cols) {
                            drawCircle(dotColor, radius = r, center = Offset(col * dx, row * dy))
                        }
                    }
                }
            }
        }
        RecorderWaveformDisplay(
            recording = state.isRecordingSeed,
            playing = state.isPlayingSeedAudio,
            elapsedMs = state.recordingElapsedMs,
            rmsFlow = vm.liveRmsFlow
        )
        val busy = state.isBusy || state.isExtractingAngles
        if (busy) {
            StudioProgressDisplay(
                label = state.busyLabel ?: "outlining your idea…",
                progressFraction = null,
                height = 72.dp,
                tag = "cs_seed_hold_record",
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            val canPlay = !state.lastSeedAudioPath.isNullOrBlank()
            val recordLabel = if (state.isRecordingSeed) "recording ${mmss(state.recordingElapsedMs)}" else "hold to record your idea"
            AcMediaWell(modifier = Modifier.fillMaxWidth()) {
                AcMediaKey(
                    icon = Icons.Sharp.FiberManualRecord,
                    contentDescription = null,
                    onPress = { onRequestMic { vm.startSeedLatch() } },
                    onRelease = { vm.stopSeedLatch() },
                    held = state.isRecordingSeed,
                    enabled = !state.isRecordingSteer && !state.isRecordingSplice,
                    caption = if (state.isRecordingSeed) "release when done" else "hold to record",
                    semanticsLabel = recordLabel,
                    size = 72.dp,
                    concave = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_seed_hold_record")
                )
                AcMediaKey(
                    icon = Icons.Sharp.PlayArrow,
                    contentDescription = "Play voice note",
                    onClick = { if (!state.isPlayingSeedAudio) vm.togglePlaySeedAudio() },
                    active = state.isPlayingSeedAudio,
                    enabled = canPlay && !state.isRecordingSeed,
                    size = 72.dp,
                    modifier = Modifier.testTag("cs_recorder_play")
                )
                AcMediaKey(
                    icon = Icons.Sharp.Stop,
                    contentDescription = "Stop voice note",
                    onClick = { if (state.isPlayingSeedAudio) vm.togglePlaySeedAudio() },
                    enabled = state.isPlayingSeedAudio,
                    size = 72.dp,
                    modifier = Modifier.testTag("cs_recorder_stop")
                )
            }
        }
    }
}

/**
 * Live waveform of the real mic RMS while recording (thin white lines, red playhead), calibrated
 * OP-1 tick baseline, plus the elapsed timecode. Collects the RMS flow only while recording, so
 * idle Spark never recomposes at 50 Hz.
 */
@Composable
private fun RecorderWaveformDisplay(
    recording: Boolean,
    playing: Boolean,
    elapsedMs: Long,
    rmsFlow: StateFlow<Float>
) {
    val samples = remember { mutableStateListOf<Float>() }
    LaunchedEffect(recording) {
        if (recording) {
            samples.clear()
            rmsFlow.collect { rms ->
                samples.add(mapMicRmsToVoiceEnergy(rms))
                if (samples.size > 160) samples.removeAt(0)
            }
        }
    }
    AcDisplay(
        modifier = Modifier
            .fillMaxWidth()
            .height(98.dp)
            .testTag("cs_recorder_waveform")
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 10.dp)
                .drawBehind {
                    val mid = (size.height - 10.dp.toPx()) / 2f
                    val pitch = 4.dp.toPx()
                    val stroke = 1.5.dp.toPx()
                    val maxBars = (size.width * 0.7f / pitch).toInt().coerceAtLeast(1)
                    val shown = samples.takeLast(maxBars)
                    shown.forEachIndexed { i, v ->
                        val x = i * pitch
                        val h = (v.coerceIn(0f, 1f) * (size.height - 16.dp.toPx()) * 0.9f).coerceAtLeast(stroke)
                        drawLine(AcTokens.DisplayInk, Offset(x, mid - h / 2f), Offset(x, mid + h / 2f), stroke)
                    }
                    val headX = shown.size * pitch
                    drawLine(AcTokens.MediaKeyHi, Offset(headX, mid), Offset(size.width, mid), 1.dp.toPx())
                    drawLine(AcTokens.OpRed, Offset(headX, 2.dp.toPx()), Offset(headX, size.height - 12.dp.toPx()), stroke)
                    drawCircle(AcTokens.OpRed, radius = 3.5.dp.toPx(), center = Offset(headX, 2.dp.toPx()))

                    // Calibrated OP-1 Field OLED baseline tick scale along the bottom edge.
                    val baseY = size.height
                    val ticks = 28
                    for (i in 0..ticks) {
                        val x = size.width * (i.toFloat() / ticks)
                        val major = i % 7 == 0
                        val h = if (major) 6.dp.toPx() else 3.dp.toPx()
                        drawLine(
                            color = if (major) AcTokens.OpGrey.copy(alpha = 0.7f) else AcTokens.MediaKeyHi,
                            start = Offset(x, baseY - h),
                            end = Offset(x, baseY),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    // Subtle TP-7 thin-line spindle badge on the right when playing back the recorded take.
                    if (playing) {
                        val reelR = 13.dp.toPx()
                        val reelC = Offset(size.width - reelR - 2.dp.toPx(), mid + 4.dp.toPx())
                        drawCircle(AcTokens.OpGrey.copy(alpha = 0.65f), radius = reelR, center = reelC, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        drawCircle(AcTokens.OpRed, radius = 2.5.dp.toPx(), center = reelC)
                        drawLine(AcTokens.OpGrey.copy(alpha = 0.75f), Offset(reelC.x - reelR * 0.7f, reelC.y), Offset(reelC.x + reelR * 0.7f, reelC.y), 1.dp.toPx())
                    }
                }
        )
        Text(
            text = mmss(if (recording) elapsedMs else 0L),
            style = AcType.numeral(18.sp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 12.dp)
        )
    }
}

@Composable
private fun ContinueLastVideoBlock(
    state: DraftDeckUiState,
    video: LastVideoSummary,
    vm: DraftDeckViewModel
) {
    if (state.directionsOpen) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                .testTag("cs_continue_directions"),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val cards = state.followupDirections
            if (cards.isNotEmpty()) {
                cards.take(2).forEach { dir ->
                    AcCard(
                        tag = dir.theme,
                        onClick = { vm.selectFollowupDirection(dir.id) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 94.dp)
                            .testTag("cs_continue_dir_${dir.id}")
                    ) {
                        Text(dir.words, style = AcType.BodySmall.copy(color = AcTokens.Ink), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                listOf("shitpost" to "SHITPOST", "solarpunk" to "SOLARPUNK").forEach { (id, theme) ->
                    AcCard(
                        tag = theme,
                        onClick = {
                            vm.openContinueSheet(video)
                            vm.selectFollowupDirection(id)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 94.dp)
                            .testTag("cs_continue_dir_$id")
                    ) {
                        Text(
                            text = if (state.isDraftingFollowup) "drafting…" else "draft $id angle",
                            style = AcType.BodySmall.copy(color = if (state.isDraftingFollowup) AcTokens.Ink2 else AcTokens.Ink),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    AcCard(
        onClick = { vm.toggleDirectionsPanel(video) },
        contentPadding = PaddingValues(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
            .testTag("cs_continue_card")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VideoThumbBox(
                thumbPath = video.thumbFilePath,
                remoteUrl = video.remoteThumbUrl,
                durationMs = video.durationMs,
                modifier = Modifier
                    .width(48.dp)
                    .height(60.dp)
                    .clickable(role = Role.Button) { vm.openContinueSheet(video) }
                    .testTag("cs_continue_open_sheet")
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SectionCaption("Or continue your last video")
                Text(video.title, style = AcType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val statusSub = when {
                    state.isDraftingFollowup -> "drafting follow-up…"
                    state.followupBeats.isNotEmpty() -> "follow-up script drafted"
                    else -> "tap for follow-up angles"
                }
                Text(statusSub, style = AcType.BodySmall, maxLines = 1)
            }
            AcKey(
                icon = if (state.directionsOpen) Icons.Sharp.ExpandMore else Icons.Sharp.ExpandLess,
                contentDescription = "Toggle follow-up directions",
                onClick = { vm.toggleDirectionsPanel(video) },
                size = AcKeySize.Small
            )
        }
    }
}

@Composable
private fun VideoThumbBox(
    thumbPath: String?,
    remoteUrl: String?,
    durationMs: Long,
    modifier: Modifier = Modifier,
    showPlayIcon: Boolean = false
) {
    val model: Any? = when {
        !thumbPath.isNullOrBlank() && File(thumbPath).exists() -> File(thumbPath)
        !remoteUrl.isNullOrBlank() -> remoteUrl
        else -> null
    }
    AcDisplay(modifier = modifier, radius = 10.dp, contentAlignment = Alignment.Center) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (showPlayIcon) {
            Icon(Icons.Sharp.PlayArrow, contentDescription = null, tint = AcTokens.DisplayInk, modifier = Modifier.size(26.dp))
        } else if (durationMs > 0L) {
            Text(
                text = mmss(durationMs),
                style = AcType.Caption.copy(color = AcTokens.DisplayInk),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .background(AcTokens.Display.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SparkOutlineContent(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel,
    onRequestMic: (() -> Unit) -> Unit,
    onOpenNoteEdit: () -> Unit,
    onCreateVideo: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val doc = state.document
    Column(modifier = modifier.fillMaxSize()) {
        // Note strip: play the real voice note, read the quote, edit it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 2.dp, end = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val canPlay = !state.lastSeedAudioPath.isNullOrBlank() || state.isPlayingSeedAudio
            AcMediaWell(padding = 4.dp) {
                AcMediaKey(
                    icon = if (state.isPlayingSeedAudio) Icons.Sharp.Pause else Icons.Sharp.PlayArrow,
                    contentDescription = if (state.isPlayingSeedAudio) "Pause voice note" else "Play voice note",
                    onClick = vm::togglePlaySeedAudio,
                    active = state.isPlayingSeedAudio,
                    enabled = canPlay,
                    size = 48.dp,
                    modifier = Modifier.testTag("cs_note_play")
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_voice_note_preview"),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val (tagText, tagColor) = when {
                    state.isRecordingSeed -> "● RECORDING · ${mmss(state.recordingElapsedMs)}:" to AcTokens.Red
                    state.followupLoaded -> "FOLLOW-UP:" to AcTokens.Ink2
                    state.lastSeedDurationSec > 0 -> "● YOUR IDEA · ${mmss(state.lastSeedDurationSec * 1000L)}:" to AcTokens.Red
                    else -> "● YOUR IDEA:" to AcTokens.Red
                }
                val quoteText = if (state.followupLoaded && state.followupSourceLabel.isNotBlank()) {
                    state.followupSourceLabel
                } else {
                    "\"${doc.seedTranscript}\""
                }
                Text(tagText, style = AcType.Caption.copy(color = tagColor), maxLines = 1, softWrap = false)
                Text(quoteText, style = AcType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AcKey(
                icon = Icons.Sharp.Edit,
                contentDescription = "Edit voice note",
                onClick = onOpenNoteEdit,
                size = AcKeySize.Small,
                modifier = Modifier.testTag("cs_note_edit")
            )
        }

        var editingBeatIndex by rememberSaveable { mutableStateOf<Int?>(null) }
        BackHandler(enabled = editingBeatIndex != null) { editingBeatIndex = null }
        LaunchedEffect(doc.id, doc.arcBeats.size) {
            if (editingBeatIndex != null && editingBeatIndex !in doc.arcBeats.indices) {
                editingBeatIndex = null
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .testTag("cs_outline_panel"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val activeAnchors = (doc.extractedAngles + doc.blindspotAngles).filter { it.selected }
            if (doc.arcBeats.isNotEmpty()) {
                AcCard(
                    onClick = vm::toggleAnchorsSheet,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cs_active_anchors_bar")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Sharp.Anchor,
                            contentDescription = null,
                            tint = if (activeAnchors.isNotEmpty()) AcTokens.Red else AcTokens.Ink2,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (activeAnchors.isEmpty()) {
                                "No locked anchors · tap to add"
                            } else {
                                "Keeping: " + activeAnchors.joinToString(" · ") { it.label }
                            },
                            style = AcType.BodySmall.copy(color = if (activeAnchors.isNotEmpty()) AcTokens.Ink else AcTokens.Ink2),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (doc.arcBeats.isEmpty()) {
                AcCard(modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (state.isBusy || state.isExtractingAngles) {
                            ThinkingOrbSolving(size = 56.dp, tint = AcTokens.Ink)
                        }
                        Text(
                            text = if (state.isBusy || state.isExtractingAngles) {
                                state.busyLabel ?: "Outlining your idea into 4 beats…"
                            } else {
                                "No 4-beat outline yet."
                            },
                            style = AcType.Body.copy(color = AcTokens.Ink2),
                            textAlign = TextAlign.Center
                        )
                        if (!state.isBusy && doc.seedTranscript.isNotBlank()) {
                            AcKey(
                                label = "propose 4-beat outline",
                                onClick = vm::generateArc,
                                modifier = Modifier.testTag("cs_arc_generate")
                            )
                        }
                    }
                }
            } else {
                doc.arcBeats.forEachIndexed { i, beat ->
                    val isEditing = editingBeatIndex == i
                    OutlineBeatCard(
                        index = i,
                        beat = beat,
                        isRerolling = i in state.rerollingBeatIndices,
                        isEditing = isEditing,
                        isWholeArcBusy = state.isBusy,
                        onStartEdit = { editingBeatIndex = i },
                        onSaveEdit = { updated ->
                            vm.updateArcBeatText(i, updated)
                            editingBeatIndex = null
                        },
                        onCancelEdit = { editingBeatIndex = null },
                        onReroll = {
                            if (editingBeatIndex == i) editingBeatIndex = null
                            vm.rerollArcBeat(i)
                        }
                    )
                }

                AcKey(
                    label = "rewrite all 4 beats",
                    icon = Icons.Sharp.Refresh,
                    onClick = vm::generateArc,
                    enabled = !state.isBusy && doc.seedTranscript.isNotBlank(),
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .testTag("cs_arc_rewrite_all")
                )
            }
        }

        val beatsScriptText = remember(doc.arcBeats, doc.seedTranscript) {
            if (doc.arcBeats.isNotEmpty()) {
                doc.arcBeats.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
            } else {
                doc.seedTranscript.trim()
            }
        }
        val directToVideo = onCreateVideo != null
        StudioDock(
            leading = {
                AcMediaWell(padding = 6.dp) {
                    AcMediaKey(
                        icon = Icons.Sharp.Mic,
                        contentDescription = null,
                        onPress = { onRequestMic { vm.startSteerClutch() } },
                        onRelease = vm::stopSteerClutch,
                        held = state.isRecordingSteer,
                        enabled = !state.isRecordingSeed && !state.isRecordingSplice,
                        caption = if (state.isRecordingSteer) "stop" else "hold",
                        semanticsLabel = if (state.isRecordingSteer) "STOP" else "HOLD",
                        size = 54.dp,
                        modifier = Modifier.testTag("cs_hold_direction")
                    )
                }
            },
            primaryLabel = if (directToVideo) "make the video" else "write the script",
            primaryIcon = Icons.AutoMirrored.Sharp.ArrowForward,
            primaryEnabled = beatsScriptText.isNotBlank() && !state.isBusy && state.rerollingBeatIndices.isEmpty(),
            primaryTag = if (directToVideo) "cs_create_video" else "cs_write_script",
            onPrimaryClick = {
                if (onCreateVideo != null) onCreateVideo(beatsScriptText) else vm.writeVideoScript()
            },
            isBusy = state.isBusy,
            busyLabel = state.busyLabel ?: "outlining beats…"
        )
    }
}

@Composable
private fun OutlineBeatCard(
    index: Int,
    beat: ArcBeat,
    isRerolling: Boolean,
    isEditing: Boolean,
    isWholeArcBusy: Boolean,
    onStartEdit: () -> Unit,
    onSaveEdit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onReroll: () -> Unit
) {
    val beatInteractive = !isWholeArcBusy && !isRerolling
    val window = beat.window.ifBlank { "${beat.startSec}–${beat.endSec}s" }
    AcCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cs_arc_beat_$index")
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionCaption("${beat.label} · $window")
                if (!isEditing) {
                    Text(
                        text = if (isRerolling) "Rewriting ${beat.label.lowercase()}…" else beat.text,
                        style = AcType.Body.copy(color = if (isRerolling) AcTokens.Ink2 else AcTokens.Ink),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = beatInteractive, role = Role.Button, onClick = onStartEdit)
                            .testTag("cs_beat_text_$index")
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AcKey(
                    icon = Icons.Sharp.Edit,
                    contentDescription = "Edit beat ${index + 1}",
                    onClick = onStartEdit,
                    selected = isEditing,
                    enabled = beatInteractive,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_beat_edit_$index")
                )
                AcKey(
                    icon = Icons.Outlined.AutoAwesome,
                    contentDescription = if (isRerolling) "Rewriting beat ${index + 1}" else "Rewrite beat ${index + 1}",
                    onClick = onReroll,
                    selected = isRerolling,
                    enabled = beatInteractive || isRerolling,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_reroll_beat_$index")
                )
            }
        }
        if (isEditing) {
            var text by rememberSaveable(beat.text) { mutableStateOf(beat.text) }
            AcField(
                value = text,
                onValueChange = { text = it },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cs_beat_editor_$index")
            )
            PulseLine(text = text, field = PulseField.BEAT, onReplace = { text = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AcKey(
                    label = "save",
                    icon = Icons.Sharp.Check,
                    onClick = { onSaveEdit(text) },
                    enabled = text.isNotBlank(),
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_beat_save_$index")
                )
                AcKey(
                    label = "clear",
                    onClick = { text = "" },
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_beat_clear_$index")
                )
                AcKey(
                    label = "cancel",
                    onClick = onCancelEdit,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_beat_cancel_$index")
                )
            }
        }
    }
}

@Composable
private fun TypedIdeaBottomSheet(
    initialText: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    StudioSheet(
        title = "Type your idea",
        onDismiss = onDismiss,
        closeTag = "cs_typed_note_close",
        closeDescription = "Close",
        modifier = Modifier
            .padding(bottom = 12.dp)
            .testTag("cs_typed_note_sheet")
    ) {
        AcField(
            value = text,
            onValueChange = { text = it },
            placeholder = "Rough notes are fine. What's the clip about?",
            minLines = 4,
            maxLines = 8,
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .testTag("cs_voice_note_editor")
        )
        PulseLine(
            text = text,
            field = PulseField.NOTE,
            onReplace = { text = it },
            modifier = Modifier.padding(horizontal = 14.dp)
        )
        StudioDock(
            primaryLabel = "outline this idea",
            primaryIcon = Icons.AutoMirrored.Sharp.ArrowForward,
            primaryEnabled = text.isNotBlank(),
            primaryTag = "cs_typed_note_submit",
            onPrimaryClick = { onSubmit(text) }
        )
    }
}

@Composable
private fun NoteEditBottomSheet(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel,
    onRequestMic: (() -> Unit) -> Unit,
    onClose: () -> Unit
) {
    val doc = state.document
    var text by rememberSaveable { mutableStateOf(doc.seedTranscript) }
    LaunchedEffect(doc.seedTranscript) {
        if (text != doc.seedTranscript) text = doc.seedTranscript
    }

    StudioSheet(
        title = "Edit your note",
        onDismiss = onClose,
        closeTag = "cs_note_edit_close",
        closeDescription = "Close note editor",
        modifier = Modifier
            .padding(bottom = 12.dp)
            .testTag("cs_note_edit_sheet")
    ) {
        AcField(
            value = text,
            onValueChange = { text = it },
            minLines = 3,
            maxLines = 7,
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .testTag("cs_voice_note_editor")
        )
        PulseLine(
            text = text,
            field = PulseField.NOTE,
            onReplace = { text = it },
            modifier = Modifier.padding(horizontal = 14.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AcKey(
                label = "keep last 15s",
                onClick = {
                    vm.distillLast15Seconds()
                    onClose()
                },
                enabled = !state.isBusy && doc.seedTranscript.isNotBlank(),
                modifier = Modifier.testTag("cs_keep_15s")
            )
            AcMediaWell(padding = 6.dp, modifier = Modifier.weight(1f)) {
                AcMediaKey(
                    icon = Icons.Sharp.Mic,
                    contentDescription = null,
                    label = if (state.isRecordingSeed) "recording…" else "hold to re-record",
                    onPress = {
                        onRequestMic {
                            if (!state.isRecordingSeed) vm.toggleLatchMic()
                        }
                    },
                    onRelease = {
                        if (state.isRecordingSeed) {
                            vm.toggleLatchMic()
                            onClose()
                        }
                    },
                    held = state.isRecordingSeed,
                    enabled = !state.isRecordingSteer && !state.isRecordingSplice,
                    size = 54.dp,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_rerecord")
                )
            }
        }

        StudioDock(
            leading = {
                AcKey(
                    label = "new idea",
                    icon = Icons.Sharp.Add,
                    onClick = {
                        vm.resetNewDraft()
                        onClose()
                    },
                    modifier = Modifier.testTag("cs_reset_draft")
                )
            },
            primaryLabel = "save & re-outline",
            primaryIcon = Icons.Sharp.Check,
            primaryEnabled = text.isNotBlank() && !state.isBusy,
            primaryTag = "cs_note_save",
            onPrimaryClick = {
                val edited = text
                onClose()
                vm.submitTypedNote(edited)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnchorsBottomSheet(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel
) {
    val doc = state.document
    val count = state.selectedAnchorCount
    StudioSheet(
        title = "Anchors",
        subtitle = "Points the script has to keep. Everything else can change.",
        onDismiss = { vm.setAnchorsSheetOpen(false) },
        closeTag = "cs_anchors_close",
        closeDescription = "Close anchors",
        modifier = Modifier.testTag("cs_anchors_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Red keys are kept. Turning a point on or off changes nothing until you tap the red key below; " +
                    "then all 4 beats are rewritten to keep the red points.",
                style = AcType.BodySmall,
                modifier = Modifier.testTag("cs_anchors_explainer")
            )

            SectionCaption("From your note")
            if (doc.extractedAngles.isEmpty()) {
                Text(
                    text = if (state.isExtractingAngles) "Extracting points from your note…" else "No extracted points yet.",
                    style = AcType.BodySmall
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    doc.extractedAngles.forEach { chip ->
                        AcKey(
                            label = chip.label,
                            icon = if (chip.selected) Icons.Sharp.Anchor else Icons.Sharp.Add,
                            selected = chip.selected,
                            onClick = { vm.toggleAngleChip(chip.id) },
                            size = AcKeySize.Small,
                            modifier = Modifier.testTag("cs_keep_${chip.id}")
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionCaption("Suggested", modifier = Modifier.weight(1f))
                AcKey(
                    label = "more",
                    icon = Icons.Sharp.Refresh,
                    enabled = doc.seedTranscript.isNotBlank() && !state.isBusy,
                    onClick = vm::refreshAnglesFromSeed,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_fresh_angles")
                )
            }

            if (doc.blindspotAngles.isEmpty() && doc.steeringConstraints.isEmpty()) {
                Text("No suggested angles yet.", style = AcType.BodySmall)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    doc.blindspotAngles.forEach { chip ->
                        AcKey(
                            label = chip.label,
                            icon = if (chip.selected) Icons.Sharp.Anchor else Icons.Sharp.Add,
                            selected = chip.selected,
                            onClick = { vm.toggleAngleChip(chip.id) },
                            size = AcKeySize.Small,
                            modifier = Modifier.testTag("cs_angle_${chip.id}")
                        )
                    }
                    doc.steeringConstraints.forEachIndexed { idx, steer ->
                        AcKey(
                            label = steer,
                            icon = Icons.Sharp.Anchor,
                            selected = true,
                            onClick = { vm.removeSteeringConstraint(idx) },
                            size = AcKeySize.Small,
                            modifier = Modifier.testTag("cs_steer_$idx")
                        )
                    }
                }
            }

            var custom by rememberSaveable { mutableStateOf("") }
            AcField(
                value = custom,
                onValueChange = { custom = it },
                placeholder = "Add a point you want included…",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cs_anchor_custom_input"),
                trailing = {
                    AcKey(
                        label = "add",
                        onClick = {
                            val txt = custom.trim()
                            if (txt.isNotEmpty()) {
                                vm.addCustomAnchor(txt)
                                custom = ""
                            }
                        },
                        enabled = custom.isNotBlank(),
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_anchor_custom_add")
                    )
                }
            )
        }

        AcPrimaryKey(
            label = if (state.anchorsDirtyForBeats) {
                "apply $count anchor${if (count == 1) "" else "s"} & update beats"
            } else {
                "keep $count anchor${if (count == 1) "" else "s"}"
            },
            icon = Icons.Sharp.Check,
            onClick = { vm.applyAnchorsAndRegenerateBeats() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)
                .testTag("cs_anchors_done")
        )
    }
}

@Composable
private fun FollowupBottomSheet(
    state: DraftDeckUiState,
    video: LastVideoSummary,
    vm: DraftDeckViewModel
) {
    StudioSheet(
        title = "Follow up your last video",
        onDismiss = vm::closeContinueSheet,
        closeTag = "cs_followup_close",
        closeDescription = "Close follow-up sheet",
        fill = true,
        modifier = Modifier.testTag("cs_followup_sheet")
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, top = 4.dp, end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AcCard(contentPadding = PaddingValues(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    VideoThumbBox(
                        thumbPath = video.thumbFilePath,
                        remoteUrl = video.remoteThumbUrl,
                        durationMs = video.durationMs,
                        showPlayIcon = true,
                        modifier = Modifier
                            .width(64.dp)
                            .height(84.dp)
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionCaption("Last video · ${formatRelativeAgeUpper(video.updatedAtMillis)} · ${mmss(video.durationMs)}")
                        Text(video.title, style = AcType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val reply = video.topReplyQuestion?.trim().orEmpty()
                        if (reply.isNotEmpty()) {
                            Text(
                                text = "Most asked in replies: \"$reply\"",
                                style = AcType.BodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("cs_followup_top_reply")
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionCaption("Follow-up angle")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("answer", "answer", Icons.Sharp.Forum),
                        Triple("part_2", "part 2", Icons.Sharp.Layers),
                        Triple("update", "update", Icons.Sharp.Update)
                    ).forEach { (wire, label, icon) ->
                        AcKey(
                            label = label,
                            icon = icon,
                            selected = state.followupDirection == null && state.followupAngle == wire,
                            onClick = { vm.selectFollowupAngle(wire) },
                            size = AcKeySize.Small,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cs_followup_angle_$wire")
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionCaption("Draft follow-up · 60s", modifier = Modifier.weight(1f))
                    val followNote = when {
                        state.isDraftingFollowup -> "drafting…"
                        state.followupDirection != null -> state.followupDirection
                        state.followupAngle == "answer" -> if (!video.topReplyQuestion.isNullOrBlank()) "from replies" else "answer"
                        state.followupAngle == "part_2" -> "goes deeper"
                        state.followupAngle == "update" -> "what changed"
                        else -> ""
                    }
                    if (followNote.isNotBlank()) SectionCaption(followNote)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cs_followup_beats"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.followupBeats.isEmpty()) {
                        AcCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (state.isDraftingFollowup) ThinkingOrbSolving(size = 24.dp, tint = AcTokens.Ink)
                                Text(
                                    text = if (state.isDraftingFollowup) "Drafting 4-beat follow-up…" else "Select a follow-up angle above to draft beats.",
                                    style = AcType.BodySmall
                                )
                            }
                        }
                    } else {
                        state.followupBeats.forEach { fb ->
                            AcCard(
                                tag = "${fb.label} · ${fb.window.ifBlank { "${fb.startSec}–${fb.endSec}s" }}",
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(fb.text, style = AcType.Body.copy(color = if (state.isDraftingFollowup) AcTokens.Ink2 else AcTokens.Ink))
                            }
                        }
                    }
                }
            }
        }

        StudioDock(
            leading = {
                AcKey(
                    label = "new idea",
                    icon = Icons.Sharp.Add,
                    onClick = vm::closeContinueSheet,
                    modifier = Modifier.testTag("cs_followup_new_idea")
                )
            },
            primaryLabel = "use this follow-up",
            primaryIcon = Icons.AutoMirrored.Sharp.ArrowForward,
            primaryEnabled = state.followupBeats.isNotEmpty() && !state.isDraftingFollowup,
            primaryTag = "cs_followup_use",
            onPrimaryClick = { vm.loadFollowupIntoSpark(videoOverride = video) },
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}
