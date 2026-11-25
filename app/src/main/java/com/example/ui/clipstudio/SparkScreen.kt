package com.example.ui.clipstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.draft.DraftDeckUiState
import com.example.ui.draft.DraftDeckViewModel

/**
 * 01 SPARK: voice note -> proposed 60s arc (tap any beat to re-roll on the rig) -> keep/add
 * angles and spoken direction -> write the 60s script.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SparkScreen(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel,
    onRequestMic: (() -> Unit) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    onNavigate: (Int) -> Unit = {}
) {
    val doc = state.document
    var noteOpen by rememberSaveable { mutableStateOf(false) }
    var noteDraft by rememberSaveable(doc.id) { mutableStateOf(doc.seedTranscript) }
    LaunchedEffect(doc.seedTranscript) { noteDraft = doc.seedTranscript }

    Box(modifier.fillMaxSize().background(Cs.Black).testTag("cs_spark_root")) {
        Column(Modifier.fillMaxSize()) {
            CsHeader {
                StageCrumb(active = 1, label = "SPARK", onSelect = onNavigate)
                Spacer(Modifier.weight(1f))
                if (doc.arcTargetSec > 0) {
                    Text(
                        "TARGET ${mmss(doc.arcTargetSec * 1000L)} · ~${doc.arcWordBudget} WDS",
                        style = mono(11.sp, Cs.Ink, FontWeight.Medium, 0.06f),
                        modifier = Modifier.padding(end = 4.dp).testTag("cs_spark_target"),
                        maxLines = 1
                    )
                }
                CsWell(padding = 2.dp) {
                    CsKeyLabel(
                        "⚙",
                        onClick = onOpenSettings,
                        size = 14.sp,
                        horizontalPadding = 0.dp,
                        modifier = Modifier.width(30.dp).height(28.dp).testTag("cs_spark_settings")
                    )
                }
            }
            CsStatusLine(state.busyLabel, state.errorMessage, state.statusNotice, vm::dismissError, vm::dismissNotice)

            // Voice note strip
            Column(Modifier.fillMaxWidth().background(Cs.Surface).hairlineBottom()) {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val preview = when {
                        state.isRecordingSeed -> "LISTENING… ${mmss(state.recordingElapsedMs)}"
                        doc.seedTranscript.isBlank() -> "NO VOICE NOTE YET · hold re-record or expand"
                        else -> "VOICE NOTE: \"${doc.seedTranscript.take(60)}${if (doc.seedTranscript.length > 60) "..." else ""}\""
                    }
                    Text("●", style = mono(11.sp, if (state.isRecordingSeed) Cs.Red else Cs.Red.copy(alpha = 0.85f)))
                    Text(
                        preview,
                        style = mono(11.sp, Cs.Muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("cs_voice_note_preview")
                    )
                    CsKeyLabel(
                        label = if (noteOpen) "close" else "expand",
                        onClick = {
                            if (noteOpen && noteDraft.trim() != doc.seedTranscript.trim()) {
                                vm.commitRawKeyboardEdit(noteDraft)
                            }
                            noteOpen = !noteOpen
                        },
                        size = 10.sp,
                        horizontalPadding = 6.dp,
                        modifier = Modifier.width(58.dp).height(26.dp).testTag("cs_voice_note_toggle")
                    )
                }
                if (noteOpen) {
                    BasicTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        textStyle = sans(13.sp, Cs.Muted, 18.sp),
                        cursorBrush = SolidColor(Cs.Ink),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 160.dp)
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .testTag("cs_voice_note_editor"),
                        decorationBox = { inner ->
                            if (noteDraft.isEmpty()) Text("Type your idea…", style = sans(13.sp, Cs.Dim))
                            inner()
                        }
                    )
                }
            }

            // Proposed 60s arc eye zone
            Column(Modifier.weight(1f).fillMaxWidth().hairlineBottom()) {
                Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CsSectionLabel(
                        if (doc.arcBeats.isEmpty()) "PROPOSED 60S ARC" else "PROPOSED ${doc.arcTargetSec}S ARC // TAP ANY BEAT TO RE-ROLL",
                        Modifier.weight(1f)
                    )
                    if (doc.seedTranscript.isNotBlank()) {
                        Text(
                            if (doc.arcBeats.isEmpty()) "propose ▸" else "redo ↻",
                            style = mono(10.sp, Cs.Ink),
                            modifier = Modifier.clickable(enabled = !state.isBusy) { vm.generateArc() }.padding(4.dp).testTag("cs_arc_generate")
                        )
                    }
                }
                if (doc.arcBeats.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopStart) {
                        Text(
                            if (doc.seedTranscript.isBlank()) {
                                "Record or type a voice note. The rig proposes a 4-beat arc from it."
                            } else {
                                "No arc yet. Tap propose to outline HOOK / DETAIL / SHIFT / PAYOFF."
                            },
                            style = sans(15.sp, Cs.Muted, 20.sp)
                        )
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().background(Cs.Hairline).hairlineTop(),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        doc.arcBeats.forEachIndexed { i, beat ->
                            Row(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Cs.Black)
                                    .clickable(enabled = !state.isBusy) { vm.rerollArcBeat(i) }
                                    .padding(horizontal = 12.dp)
                                    .testTag("cs_arc_beat_$i"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val tag = if (doc.lastRerolledBeat == i) Cs.Ink else Cs.Muted
                                Column(Modifier.width(68.dp)) {
                                    Text("%02d–%02dS".format(beat.startSec, beat.endSec), style = mono(10.sp, tag))
                                    Text(beat.label, style = mono(10.sp, tag))
                                }
                                Text(
                                    beat.text,
                                    style = sans(17.sp, Cs.Ink, 25.sp),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Thumb zone: angles + direction on dot-grid DeckPlate
            Column(Modifier.fillMaxWidth().dotPanel().hairlineBottom()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 310.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CsSectionLabel("KEEP FROM YOUR NOTE")
                    CsWell(Modifier.fillMaxWidth()) {
                        if (doc.extractedAngles.isEmpty()) {
                            Text(
                                if (state.isExtractingAngles) "extracting…" else "No claims extracted yet.",
                                style = mono(11.sp, Cs.Dim),
                                modifier = Modifier.padding(6.dp)
                            )
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                doc.extractedAngles.forEach { chip ->
                                    CsChip(chip.label, chip.selected, { vm.toggleAngleChip(chip.id) }, Modifier.testTag("cs_keep_${chip.id}"))
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Cs.Hairline))
                    CsSectionLabel("ADD AN UNEXPLORED ANGLE")
                    CsWell(Modifier.fillMaxWidth()) {
                        if (doc.blindspotAngles.isEmpty() && doc.steeringConstraints.isEmpty()) {
                            Text("No angles yet.", style = mono(11.sp, Cs.Dim), modifier = Modifier.padding(6.dp))
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                doc.blindspotAngles.forEach { chip ->
                                    CsChip(chip.label, chip.selected, { vm.toggleAngleChip(chip.id) }, Modifier.testTag("cs_angle_${chip.id}"))
                                }
                                doc.steeringConstraints.forEachIndexed { idx, steer ->
                                    CsChip(
                                        steer,
                                        on = false,
                                        onToggle = { vm.removeSteeringConstraint(idx) },
                                        outlined = true,
                                        modifier = Modifier.testTag("cs_steer_$idx")
                                    )
                                }
                            }
                        }
                    }
                }
                CsWell(Modifier.fillMaxWidth().height(56.dp).hairlineTop(), radius = 0.dp) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CsKeyLabel(
                            "↻ fresh angle ideas",
                            onClick = vm::refreshAnglesFromSeed,
                            enabled = doc.seedTranscript.isNotBlank() && !state.isBusy,
                            modifier = Modifier.weight(1f).fillMaxSize().testTag("cs_fresh_angles")
                        )
                        CsHoldKey(
                            label = if (state.isRecordingSteer) "● listening…" else "● hold to add direction",
                            onPress = { onRequestMic { vm.startSteerClutch() } },
                            onRelease = vm::stopSteerClutch,
                            held = state.isRecordingSteer,
                            enabled = !state.isRecordingSeed && !state.isRecordingSplice,
                            modifier = Modifier.weight(1f).fillMaxSize().testTag("cs_hold_direction")
                        )
                    }
                }
            }

            CsBottomBar {
                CsHoldKey(
                    label = if (state.isRecordingSeed) "● recording…" else "● re-record",
                    onPress = { onRequestMic { if (!state.isRecordingSeed) vm.toggleLatchMic() } },
                    onRelease = { if (state.isRecordingSeed) vm.toggleLatchMic() },
                    held = state.isRecordingSeed,
                    enabled = !state.isRecordingSteer && !state.isRecordingSplice,
                    modifier = Modifier.weight(116f).fillMaxSize().testTag("cs_rerecord")
                )
                CsKeyLabel(
                    "keep last 15s",
                    onClick = vm::distillLast15Seconds,
                    enabled = !state.isBusy,
                    modifier = Modifier.weight(112f).fillMaxSize().testTag("cs_keep_15s")
                )
                CsKeyLabel(
                    "write ${if (doc.arcTargetSec > 0) doc.arcTargetSec else DraftDeckViewModel.ARC_TARGET_SEC}s script",
                    onClick = vm::writeVideoScript,
                    role = KeyRole.COMMIT,
                    weight = FontWeight.Medium,
                    trailingArrow = true,
                    enabled = doc.seedTranscript.isNotBlank() && !state.isBusy,
                    modifier = Modifier.weight(184f).fillMaxSize().testTag("cs_write_script")
                )
            }
        }
        if (state.isAnyRecording) {
            Box(Modifier.fillMaxSize().border(2.dp, Cs.Red))
        }
    }
}
