package com.example.ui.clipstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowForward
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.Anchor
import androidx.compose.material.icons.sharp.AutoAwesome
import androidx.compose.material.icons.sharp.Check
import androidx.compose.material.icons.sharp.Close
import androidx.compose.material.icons.sharp.Edit
import androidx.compose.material.icons.sharp.ExpandLess
import androidx.compose.material.icons.sharp.ExpandMore
import androidx.compose.material.icons.sharp.Forum
import androidx.compose.material.icons.sharp.HourglassEmpty
import androidx.compose.material.icons.sharp.Keyboard
import androidx.compose.material.icons.sharp.Layers
import androidx.compose.material.icons.sharp.Mic
import androidx.compose.material.icons.sharp.Pause
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.data.LastVideoSummary
import com.example.draft.ArcBeat
import com.example.draft.DraftTone
import com.example.draft.ShotSpec
import com.example.draft.ShotType
import com.example.ui.draft.DraftDeckUiState
import com.example.ui.draft.DraftDeckViewModel
import java.io.File

/**
 * 01 SPARK (Clip Studio v5):
 * - First-run empty state: tone selector, 76dp press-and-hold red mic key, "type it instead" modal,
 *   and real "OR CONTINUE YOUR LAST VIDEO" card + 2-up split directions + Follow-Up sheet.
 * - Populated outline state: single-row note strip (real MediaPlayer audio playback + Note Edit sheet),
 *   top-bar `anchors` badge + Anchors bottom sheet, sunken 4-beat outline panel with immediate rig
 *   shot previews (`face -> pip -> broll -> card`) + per-beat sparkle rewrite, and 2-key dock.
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
    lastCompletedVideo: LastVideoSummary? = null,
    renderGlobalOverlays: Boolean = true
) {
    val doc = state.document
    val sparkEmpty = doc.seedTranscript.isBlank() && !state.followupLoaded && doc.arcBeats.isEmpty()

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
            .background(Cs.Body)
            .testTag("cs_spark_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            CsTopBar(
                activeStage = 1,
                onNavigate = onNavigate
            ) {
                if (!sparkEmpty) {
                    AnchorsTopKey(
                        count = state.selectedAnchorCount,
                        open = state.anchorsSheetOpen,
                        onClick = vm::toggleAnchorsSheet
                    )
                }
                CsIconKey(
                    icon = Icons.Sharp.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("cs_spark_settings")
                )
            }

            CsStatusLine(
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
            AnchorsBottomSheet(
                state = state,
                vm = vm
            )
        }

        if (state.continueSheetOpen && activeFollowupVideo != null) {
            FollowupBottomSheet(
                state = state,
                video = activeFollowupVideo,
                vm = vm
            )
        }

        if (renderGlobalOverlays) {
            CsToastOverlay(
                message = state.toastMessage,
                onDismiss = vm::dismissToast
            )
            if (state.isAnyRecording) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, Cs.Red)
                        .testTag("cs_recording_border")
                )
            }
        }
    }
}

@Composable
private fun AnchorsTopKey(
    count: Int,
    open: Boolean,
    onClick: () -> Unit
) {
    val role = if (open) KeyRole.DOWN else KeyRole.STD
    CsKey(
        onClick = onClick,
        role = role,
        radius = 12.dp,
        horizontalPadding = 10.dp,
        contentAlignment = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .height(44.dp)
            .testTag("cs_anchors_button")
    ) { tint ->
        Icon(
            imageVector = Icons.Sharp.Anchor,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Text("anchors", style = mono(12.sp, tint), maxLines = 1)
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Cs.Ink),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = count.toString(),
                style = mono(11.sp, Cs.Black, FontWeight.Medium),
                maxLines = 1
            )
        }
    }
}

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
                .padding(top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("STEP 1 OF 3 · YOUR IDEA", style = SectionLabelStyle)
                Text(
                    text = "What's the clip about?",
                    style = sans(26.sp, Cs.Ink, 32.sp, FontWeight.Medium)
                )
                Text(
                    text = "Pick a tone, then hold the red key and talk. Rough is fine. Length is set from what you say.",
                    style = sans(16.sp, Cs.Muted, 23.sp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "TONE",
                    style = SectionLabelStyle,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DraftTone.entries.forEach { tone ->
                        CsPill(
                            label = tone.label,
                            selected = state.tone == tone,
                            onClick = { vm.setTone(tone) },
                            modifier = Modifier.testTag("cs_tone_${tone.wireValue}")
                        )
                    }
                }
            }

            Box(Modifier.padding(horizontal = 20.dp)) {
                SeedRecordHeroKey(
                    isRecording = state.isRecordingSeed,
                    elapsedMs = state.recordingElapsedMs,
                    isBusy = state.isBusy || state.isExtractingAngles,
                    busyLabel = state.busyLabel,
                    enabled = !state.isRecordingSteer && !state.isRecordingSplice,
                    onPress = {
                        onRequestMic {
                            if (!state.isRecordingSeed) vm.toggleLatchMic()
                        }
                    },
                    onRelease = {
                        if (state.isRecordingSeed) vm.toggleLatchMic()
                    }
                )
            }

            CsKey(
                onClick = onOpenTypedSheet,
                role = KeyRole.STD,
                enabled = !state.isBusy && !state.isAnyRecording,
                radius = 16.dp,
                contentAlignment = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("cs_type_instead")
            ) { tint ->
                Icon(
                    imageVector = Icons.Sharp.Keyboard,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                Text("type it instead", style = mono(14.sp, tint), maxLines = 1)
            }
        }

        if (lastCompletedVideo != null) {
            ContinueLastVideoBlock(
                state = state,
                video = lastCompletedVideo,
                vm = vm
            )
        }
    }
}

@Composable
private fun SeedRecordHeroKey(
    isRecording: Boolean,
    elapsedMs: Long,
    isBusy: Boolean,
    busyLabel: String?,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)

    if (isBusy) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .neumorphicSurface(role = KeyRole.DOWN, pressed = true, radius = 20.dp, enabled = true)
                .clip(RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp)
                .testTag("cs_seed_hold_record"),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = busyLabel ?: "outlining your idea…",
                style = mono(15.sp, Cs.White, FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    val mainLabel = if (isRecording) "recording ${mmss(elapsedMs)}" else "hold to record your idea"
    val subLabel = if (isRecording) "release when done" else "press and hold"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .offset(y = if (isRecording) 1.dp else 0.dp)
            .neumorphicSurface(role = KeyRole.VOICE, pressed = isRecording, radius = 20.dp, enabled = enabled)
            .clip(RoundedCornerShape(20.dp))
            .semantics(mergeDescendants = true) {
                this.role = Role.Button
                onClick(label = mainLabel) {
                    if (isRecording) release() else press()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    press()
                    waitForUpOrCancellation()
                    release()
                }
            }
            .padding(horizontal = 20.dp)
            .testTag("cs_seed_hold_record"),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (enabled) Cs.White else Cs.Dim
        Icon(
            imageVector = Icons.Sharp.Mic,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(34.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = mainLabel,
                style = mono(15.sp, fg, FontWeight.Bold),
                maxLines = 1
            )
            Text(
                text = subLabel,
                style = mono(12.sp, fg.copy(alpha = 0.85f), FontWeight.SemiBold),
                maxLines = 1
            )
        }
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
                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
                .height(94.dp)
                .testTag("cs_continue_directions"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val cards = state.followupDirections
            if (cards.isNotEmpty()) {
                cards.take(2).forEach { dir ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(94.dp)
                            .raisedCard(radius = 20.dp)
                            .clickable(role = Role.Button) {
                                vm.selectFollowupDirection(dir.id)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("cs_continue_dir_${dir.id}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                    ) {
                        Text(dir.theme, style = SectionLabelStyle, maxLines = 1)
                        Text(
                            text = dir.words,
                            style = sans(15.sp, Cs.Ink, 20.sp, FontWeight.Medium),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                listOf("shitpost" to "SHITPOST", "solarpunk" to "SOLARPUNK").forEach { (id, theme) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(94.dp)
                            .raisedCard(radius = 20.dp)
                            .clickable(role = Role.Button) {
                                vm.openContinueSheet(video)
                                vm.selectFollowupDirection(id)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("cs_continue_dir_$id"),
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
                    ) {
                        Text(theme, style = SectionLabelStyle, maxLines = 1)
                        Text(
                            text = if (state.isDraftingFollowup) "drafting…" else "draft $id angle",
                            style = sans(15.sp, if (state.isDraftingFollowup) Cs.Muted else Cs.Ink, 20.sp, FontWeight.Medium),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
            .raisedCard(radius = 20.dp)
            .clickable(role = Role.Button) { vm.toggleDirectionsPanel(video) }
            .padding(12.dp)
            .testTag("cs_continue_card"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VideoThumbBox(
            thumbPath = video.thumbFilePath,
            remoteUrl = video.remoteThumbUrl,
            durationMs = video.durationMs,
            modifier = Modifier
                .width(52.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { vm.openContinueSheet(video) }
                .testTag("cs_continue_open_sheet")
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text("OR CONTINUE YOUR LAST VIDEO", style = SectionLabelStyle, maxLines = 1)
            Text(
                text = video.title,
                style = sans(15.sp, Cs.Ink, 20.sp, FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val statusSub = when {
                state.isDraftingFollowup -> "drafting follow-up…"
                state.followupBeats.isNotEmpty() -> "follow-up script drafted"
                else -> "tap for follow-up angles"
            }
            Text(statusSub, style = mono(12.sp, Cs.Muted), maxLines = 1)
        }
        Icon(
            imageVector = if (state.directionsOpen) Icons.Sharp.ExpandMore else Icons.Sharp.ExpandLess,
            contentDescription = "Toggle follow-up directions",
            tint = Cs.Ink,
            modifier = Modifier.size(24.dp)
        )
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
    Box(
        modifier = modifier
            .background(Color(0xFF16212E)),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (showPlayIcon) {
            Icon(
                imageVector = Icons.Sharp.PlayArrow,
                contentDescription = null,
                tint = Cs.Ink,
                modifier = Modifier.size(26.dp)
            )
        } else if (durationMs > 0L) {
            Text(
                text = mmss(durationMs),
                style = mono(9.sp, Cs.Ink, FontWeight.Medium),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
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
    modifier: Modifier = Modifier
) {
    val doc = state.document
    Column(modifier = modifier.fillMaxSize()) {
        // Single-row Note Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val canPlay = !state.lastSeedAudioPath.isNullOrBlank() || state.isPlayingSeedAudio
            CsIconKey(
                icon = if (state.isPlayingSeedAudio) Icons.Sharp.Pause else Icons.Sharp.PlayArrow,
                contentDescription = if (state.isPlayingSeedAudio) "Pause voice note" else "Play voice note",
                onClick = vm::togglePlaySeedAudio,
                enabled = canPlay,
                size = 40.dp,
                radius = 12.dp,
                iconSize = 22.dp,
                modifier = Modifier.testTag("cs_note_play")
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_voice_note_preview"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val (tagText, tagColor) = when {
                    state.isRecordingSeed -> "● RECORDING · ${mmss(state.recordingElapsedMs)}:" to Cs.Red
                    state.followupLoaded -> "FOLLOW-UP:" to Cs.Muted
                    state.lastSeedDurationSec > 0 -> "● YOUR IDEA · ${mmss(state.lastSeedDurationSec * 1000L)}:" to Cs.Red
                    else -> "● YOUR IDEA:" to Cs.Red
                }
                val quoteText = if (state.followupLoaded && state.followupSourceLabel.isNotBlank()) {
                    state.followupSourceLabel
                } else {
                    "\"${doc.seedTranscript}\""
                }
                Text(
                    text = tagText,
                    style = mono(11.sp, tagColor, FontWeight.Medium, 0.06f),
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = quoteText,
                    style = sans(14.sp, Cs.InkSub),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            CsIconKey(
                icon = Icons.Sharp.Edit,
                contentDescription = "Edit voice note",
                onClick = onOpenNoteEdit,
                size = 40.dp,
                radius = 12.dp,
                iconSize = 20.dp,
                modifier = Modifier.testTag("cs_note_edit")
            )
        }

        // Sunken #000 4-beat Outline Panel
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .sunkenPanel(radius = 18.dp)
                .testTag("cs_outline_panel")
        ) {
            if (doc.arcBeats.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (state.isBusy || state.isExtractingAngles) {
                            state.busyLabel ?: "Outlining your idea into 4 beats…"
                        } else {
                            "No 4-beat outline yet."
                        },
                        style = sans(16.sp, Cs.Muted, 22.sp)
                    )
                    if (!state.isBusy && doc.seedTranscript.isNotBlank()) {
                        CsKeyLabel(
                            label = "propose 4-beat outline",
                            onClick = vm::generateArc,
                            role = KeyRole.STD,
                            radius = 14.dp,
                            modifier = Modifier
                                .height(44.dp)
                                .testTag("cs_arc_generate")
                        )
                    }
                }
            } else {
                doc.arcBeats.forEachIndexed { i, beat ->
                    OutlineBeatRow(
                        index = i,
                        beat = beat,
                        isRerolling = state.rerollingBeatIndex == i,
                        shotPreviews = state.shotPreviews,
                        inFlightShotIds = state.inFlightShotIds,
                        isBusy = state.isBusy,
                        onCycleShot = { shotIdx -> vm.cycleBeatShot(i, shotIdx) },
                        onAddShot = { vm.addBeatBrollShot(i) },
                        onReroll = { vm.rerollArcBeat(i) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(if (i > 0) Modifier.hairlineTop(Cs.RowDivider) else Modifier)
                    )
                }
            }
        }

        // Slim 2-Key Dock
        CsDock(
            leading = {
                CsVoiceDockKey(
                    label = if (state.isRecordingSteer) "listening" else "hold: add",
                    icon = Icons.Sharp.Mic,
                    held = state.isRecordingSteer,
                    enabled = !state.isRecordingSeed && !state.isRecordingSplice,
                    onPress = { onRequestMic { vm.startSteerClutch() } },
                    onRelease = vm::stopSteerClutch,
                    modifier = Modifier.testTag("cs_hold_direction")
                )
            },
            primaryLabel = "write the script",
            primaryTrailingIcon = Icons.AutoMirrored.Sharp.ArrowForward,
            primaryRole = KeyRole.COMMIT,
            primaryEnabled = doc.seedTranscript.isNotBlank() && !state.isBusy,
            primaryTag = "cs_write_script",
            onPrimaryClick = vm::writeVideoScript,
            isBusy = state.isBusy,
            busyLabel = state.busyLabel ?: "writing 3 versions…"
        )
    }
}

@Composable
private fun OutlineBeatRow(
    index: Int,
    beat: ArcBeat,
    isRerolling: Boolean,
    shotPreviews: Map<String, String>,
    inFlightShotIds: Set<String>,
    isBusy: Boolean,
    onCycleShot: (Int) -> Unit,
    onAddShot: () -> Unit,
    onReroll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(start = 16.dp, top = 4.dp, end = 6.dp, bottom = 4.dp)
            .testTag("cs_arc_beat_$index"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier.width(54.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(beat.label, style = mono(11.sp, Cs.Ink, FontWeight.Medium), maxLines = 1)
            Text(
                text = beat.window.ifBlank { "${beat.startSec}–${beat.endSec}s" },
                style = mono(11.sp, Cs.Muted),
                maxLines = 1
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (isRerolling) "rewriting…" else beat.text,
                style = sans(16.sp, if (isRerolling) Cs.Muted else Cs.Ink, 21.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                beat.shots.forEachIndexed { sIdx, shot ->
                    ShotThumbnailKey(
                        shot = shot,
                        previewPath = shotPreviews[shot.id],
                        isLoading = shot.id in inFlightShotIds,
                        onClick = { onCycleShot(sIdx) },
                        modifier = Modifier.testTag("cs_shot_${index}_$sIdx")
                    )
                }

                // Dashed 30x42 "+" key that adds a b-roll shot
                Box(
                    modifier = Modifier
                        .width(30.dp)
                        .height(42.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .drawBehind {
                            drawRoundRect(
                                color = Cs.Dim,
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                                style = Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
                                )
                            )
                        }
                        .clickable(role = Role.Button, onClick = onAddShot)
                        .testTag("cs_add_shot_$index"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Sharp.Add,
                        contentDescription = "Add b-roll shot",
                        tint = Cs.Muted,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (beat.shotSummaryLabel.isNotBlank()) {
                    Text(
                        text = beat.shotSummaryLabel,
                        style = mono(11.sp, Cs.Muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(start = 4.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !isBusy && !isRerolling, role = Role.Button, onClick = onReroll)
                .testTag("cs_reroll_beat_$index"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Sharp.AutoAwesome,
                contentDescription = "Rewrite beat ${index + 1}",
                tint = if (!isBusy && !isRerolling) Cs.Muted else Cs.Dim,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ShotThumbnailKey(
    shot: ShotSpec,
    previewPath: String?,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fileModel = previewPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf(File::exists)
    Box(
        modifier = modifier
            .width(30.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Cs.Body)
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            fileModel != null -> {
                AsyncImage(
                    model = fileModel,
                    contentDescription = shot.type.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            isLoading -> {
                Icon(
                    imageVector = Icons.Sharp.HourglassEmpty,
                    contentDescription = "Loading shot preview",
                    tint = Cs.Dim,
                    modifier = Modifier.size(14.dp)
                )
            }
            else -> {
                OfflineShotFallbackGlyph(type = shot.type)
            }
        }
    }
}

@Composable
private fun OfflineShotFallbackGlyph(type: ShotType) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                when (type) {
                    ShotType.FACE -> {
                        drawRect(Color(0xFF1E1E24))
                        drawCircle(Color(0xFF5A5A66), radius = 5.dp.toPx(), center = Offset(size.width * 0.5f, size.height * 0.38f))
                        drawRoundRect(
                            Color(0xFF2C2C32),
                            topLeft = Offset(size.width * 0.2f, size.height * 0.56f),
                            size = Size(size.width * 0.6f, size.height * 0.38f),
                            cornerRadius = CornerRadius(5.dp.toPx())
                        )
                    }
                    ShotType.PIP -> {
                        drawRect(Color(0xFF16212E))
                        drawLine(Color(0xFF1C2A3A), Offset(0f, size.height * 0.35f), Offset(size.width, 0f), 4.dp.toPx())
                        drawLine(Color(0xFF1C2A3A), Offset(0f, size.height * 0.85f), Offset(size.width, size.height * 0.5f), 4.dp.toPx())
                        drawCircle(Color(0xFF8E8E96), radius = 4.dp.toPx(), center = Offset(size.width * 0.74f, size.height * 0.78f))
                    }
                    ShotType.BROLL -> {
                        drawRect(Color(0xFF16212E))
                        drawLine(Color(0xFF1C2A3A), Offset(0f, size.height * 0.35f), Offset(size.width, 0f), 4.dp.toPx())
                        drawLine(Color(0xFF1C2A3A), Offset(0f, size.height * 0.85f), Offset(size.width, size.height * 0.5f), 4.dp.toPx())
                    }
                    ShotType.CARD -> {
                        drawRect(Color(0xFF1A1A1E))
                        drawRect(
                            Color(0xFFD71921),
                            topLeft = Offset(0f, size.height * 0.54f),
                            size = Size(size.width, size.height * 0.12f)
                        )
                    }
                }
            }
    )
}

@Composable
private fun TypedIdeaBottomSheet(
    initialText: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val fieldState = rememberTextFieldState(initialText = initialText)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onDismiss)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Cs.Sheet)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                .testTag("cs_typed_note_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SheetDragHandle()
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Type your idea",
                    style = sans(20.sp, Cs.Ink, 26.sp, FontWeight.Medium),
                    modifier = Modifier.weight(1f)
                )
                CsIconKey(
                    icon = Icons.Sharp.Close,
                    contentDescription = "Close",
                    onClick = onDismiss,
                    modifier = Modifier.testTag("cs_typed_note_close")
                )
            }
            BasicTextField(
                state = fieldState,
                textStyle = sans(16.sp, Cs.Ink, 23.sp),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 8),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .sunkenPanel(radius = 16.dp)
                    .padding(14.dp)
                    .testTag("cs_voice_note_editor"),
                decorator = { inner ->
                    Box {
                        if (fieldState.text.isEmpty()) {
                            Text(
                                "Rough notes are fine. What's the clip about?",
                                style = sans(16.sp, Cs.Dim, 23.sp)
                            )
                        }
                        inner()
                    }
                }
            )
            CsDock(
                primaryLabel = "outline this idea",
                primaryTrailingIcon = Icons.AutoMirrored.Sharp.ArrowForward,
                primaryEnabled = fieldState.text.toString().isNotBlank(),
                primaryTag = "cs_typed_note_submit",
                onPrimaryClick = { onSubmit(fieldState.text.toString()) }
            )
        }
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
    val fieldState = rememberTextFieldState(initialText = doc.seedTranscript)
    LaunchedEffect(doc.seedTranscript) {
        if (fieldState.text.toString() != doc.seedTranscript) {
            fieldState.setTextAndPlaceCursorAtEnd(doc.seedTranscript)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onClose)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Cs.Sheet)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                .testTag("cs_note_edit_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SheetDragHandle()
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Edit your note",
                    style = sans(20.sp, Cs.Ink, 26.sp, FontWeight.Medium),
                    modifier = Modifier.weight(1f)
                )
                CsIconKey(
                    icon = Icons.Sharp.Close,
                    contentDescription = "Close note editor",
                    onClick = onClose,
                    modifier = Modifier.testTag("cs_note_edit_close")
                )
            }

            BasicTextField(
                state = fieldState,
                textStyle = sans(15.sp, Cs.Ink, 22.sp),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 7),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp)
                    .sunkenPanel(radius = 16.dp)
                    .padding(14.dp)
                    .testTag("cs_voice_note_editor")
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CsKeyLabel(
                    label = "keep last 15s",
                    onClick = {
                        vm.distillLast15Seconds()
                        onClose()
                    },
                    enabled = !state.isBusy && doc.seedTranscript.isNotBlank(),
                    radius = 14.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("cs_keep_15s")
                )
                CsHoldKey(
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
                    radius = 14.dp,
                    size = 12.sp,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("cs_rerecord")
                )
            }

            CsDock(
                leading = {
                    CsDockSideKey(
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
                primaryTrailingIcon = Icons.Sharp.Check,
                primaryEnabled = fieldState.text.toString().isNotBlank() && !state.isBusy,
                primaryTag = "cs_note_save",
                onPrimaryClick = {
                    val edited = fieldState.text.toString()
                    onClose()
                    vm.submitTypedNote(edited)
                }
            )
        }
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
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable { vm.setAnchorsSheetOpen(false) }
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Cs.Sheet)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 28.dp)
                .testTag("cs_anchors_sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SheetDragHandle()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Sharp.Anchor,
                    contentDescription = null,
                    tint = Cs.Ink,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(26.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("Anchors", style = sans(20.sp, Cs.Ink, 26.sp, FontWeight.Medium))
                    Text(
                        "Points the script has to keep. Everything else can change.",
                        style = sans(14.sp, Cs.Muted, 20.sp)
                    )
                }
                CsIconKey(
                    icon = Icons.Sharp.Close,
                    contentDescription = "Close anchors",
                    onClick = { vm.setAnchorsSheetOpen(false) },
                    modifier = Modifier.testTag("cs_anchors_close")
                )
            }

            Text("FROM YOUR NOTE", style = SectionLabelStyle)
            if (doc.extractedAngles.isEmpty()) {
                Text(
                    text = if (state.isExtractingAngles) "Extracting points from your note…" else "No extracted points yet.",
                    style = sans(14.sp, Cs.Muted, 20.sp)
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    doc.extractedAngles.forEach { chip ->
                        CsPill(
                            label = chip.label,
                            selected = chip.selected,
                            icon = if (chip.selected) Icons.Sharp.Anchor else Icons.Sharp.Add,
                            onClick = { vm.toggleAngleChip(chip.id) },
                            modifier = Modifier.testTag("cs_keep_${chip.id}")
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SUGGESTED", style = SectionLabelStyle, modifier = Modifier.weight(1f))
                CsPill(
                    label = "more",
                    selected = false,
                    icon = Icons.Sharp.Refresh,
                    enabled = doc.seedTranscript.isNotBlank() && !state.isBusy,
                    onClick = vm::refreshAnglesFromSeed,
                    modifier = Modifier.testTag("cs_fresh_angles")
                )
            }

            if (doc.blindspotAngles.isEmpty() && doc.steeringConstraints.isEmpty()) {
                Text("No suggested angles yet.", style = sans(14.sp, Cs.Muted, 20.sp))
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    doc.blindspotAngles.forEach { chip ->
                        CsPill(
                            label = chip.label,
                            selected = chip.selected,
                            icon = if (chip.selected) Icons.Sharp.Anchor else Icons.Sharp.Add,
                            onClick = { vm.toggleAngleChip(chip.id) },
                            modifier = Modifier.testTag("cs_angle_${chip.id}")
                        )
                    }
                    doc.steeringConstraints.forEachIndexed { idx, steer ->
                        CsPill(
                            label = steer,
                            selected = true,
                            icon = Icons.Sharp.Anchor,
                            onClick = { vm.removeSteeringConstraint(idx) },
                            modifier = Modifier.testTag("cs_steer_$idx")
                        )
                    }
                }
            }

            CsKey(
                onClick = { vm.setAnchorsSheetOpen(false) },
                role = KeyRole.COMMIT,
                radius = 18.dp,
                horizontalPadding = 20.dp,
                contentAlignment = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("cs_anchors_done")
            ) { tint ->
                Text(
                    text = "keep $count anchor${if (count == 1) "" else "s"}",
                    style = mono(15.sp, tint, FontWeight.Medium),
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Sharp.Check, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun FollowupBottomSheet(
    state: DraftDeckUiState,
    video: LastVideoSummary,
    vm: DraftDeckViewModel
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = vm::closeContinueSheet)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 118.dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Cs.Sheet)
                .testTag("cs_followup_sheet")
        ) {
            SheetDragHandle()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Follow up your last video",
                    style = sans(20.sp, Cs.Ink, 26.sp, FontWeight.Medium),
                    modifier = Modifier.weight(1f)
                )
                CsIconKey(
                    icon = Icons.Sharp.Close,
                    contentDescription = "Close follow-up sheet",
                    onClick = vm::closeContinueSheet,
                    modifier = Modifier.testTag("cs_followup_close")
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, top = 10.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Sunken Last Video Card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sunkenPanel(radius = 18.dp, fill = Cs.SunkenTrack)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VideoThumbBox(
                        thumbPath = video.thumbFilePath,
                        remoteUrl = video.remoteThumbUrl,
                        durationMs = video.durationMs,
                        showPlayIcon = true,
                        modifier = Modifier
                            .width(64.dp)
                            .height(84.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "LAST VIDEO · ${formatRelativeAgeUpper(video.updatedAtMillis)} · ${mmss(video.durationMs)}",
                            style = SectionLabelStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = video.title,
                            style = sans(16.sp, Cs.Ink, 21.sp, FontWeight.Medium),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        val reply = video.topReplyQuestion?.trim().orEmpty()
                        if (reply.isNotEmpty()) {
                            Text(
                                text = "Most asked in replies: \"$reply\"",
                                style = sans(13.sp, Cs.Muted, 18.sp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("cs_followup_top_reply")
                            )
                        }
                    }
                }

                // 2. FOLLOW-UP ANGLE 3-way sunken segmented control
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("FOLLOW-UP ANGLE", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
                    CsSegmentedRow {
                        val angles = listOf(
                            Triple("answer", "answer", Icons.Sharp.Forum),
                            Triple("part_2", "part 2", Icons.Sharp.Layers),
                            Triple("update", "update", Icons.Sharp.Update)
                        )
                        angles.forEach { (wire, label, icon) ->
                            val selected = state.followupDirection == null && state.followupAngle == wire
                            CsSegment(
                                selected = selected,
                                onClick = { vm.selectFollowupAngle(wire) },
                                height = 44.dp,
                                modifier = Modifier.testTag("cs_followup_angle_$wire")
                            ) { tint ->
                                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                                Text(label, style = mono(13.sp, tint), maxLines = 1)
                            }
                        }
                    }
                }

                // 3. DRAFT FOLLOW-UP · 60S
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("DRAFT FOLLOW-UP · 60S", style = SectionLabelStyle, modifier = Modifier.weight(1f))
                        val followNote = when {
                            state.isDraftingFollowup -> "drafting…"
                            state.followupDirection != null -> state.followupDirection
                            state.followupAngle == "answer" -> if (!video.topReplyQuestion.isNullOrBlank()) "from replies" else "answer"
                            state.followupAngle == "part_2" -> "goes deeper"
                            state.followupAngle == "update" -> "what changed"
                            else -> ""
                        }
                        if (followNote.isNotBlank()) {
                            Text(followNote, style = mono(11.sp, Cs.Muted), maxLines = 1)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sunkenPanel(radius = 18.dp)
                            .testTag("cs_followup_beats")
                    ) {
                        if (state.followupBeats.isEmpty()) {
                            Text(
                                text = if (state.isDraftingFollowup) {
                                    "Drafting 4-beat follow-up…"
                                } else {
                                    "Select a follow-up angle above to draft beats."
                                },
                                style = sans(15.sp, Cs.Muted, 21.sp),
                                modifier = Modifier.padding(16.dp)
                            )
                        } else {
                            state.followupBeats.forEachIndexed { idx, fb ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (idx > 0) Modifier.hairlineTop(Cs.Body) else Modifier)
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.width(52.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(fb.label, style = mono(11.sp, Cs.Ink, FontWeight.Medium), maxLines = 1)
                                        Text(
                                            fb.window.ifBlank { "${fb.startSec}–${fb.endSec}s" },
                                            style = mono(11.sp, Cs.Muted),
                                            maxLines = 1
                                        )
                                    }
                                    Text(
                                        text = fb.text,
                                        style = sans(15.sp, if (state.isDraftingFollowup) Cs.Dim else Cs.Ink, 21.sp),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            CsDock(
                leading = {
                    CsDockSideKey(
                        label = "new idea",
                        icon = Icons.Sharp.Add,
                        onClick = vm::closeContinueSheet,
                        modifier = Modifier.testTag("cs_followup_new_idea")
                    )
                },
                primaryLabel = "use this follow-up",
                primaryTrailingIcon = Icons.AutoMirrored.Sharp.ArrowForward,
                primaryEnabled = state.followupBeats.isNotEmpty() && !state.isDraftingFollowup,
                primaryTag = "cs_followup_use",
                onPrimaryClick = { vm.loadFollowupIntoSpark(videoOverride = video) },
                modifier = Modifier.padding(bottom = 14.dp)
            )
        }
    }
}

@Composable
private fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Cs.Dim)
        )
    }
}
