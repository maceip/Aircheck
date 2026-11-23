package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.ScriptBeat
import com.example.data.VoiceSource
import com.example.data.formatDurationBadge
import com.example.ui.StudioUiState
import com.example.ui.components.HazardCutOverlay
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioErrorBanner
import com.example.ui.components.StudioPrimaryCta
import com.example.ui.components.TelemetryWaveform
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle
import com.voicestudio.edl.OtaBundleStatus

private val DURATION_TARGETS = listOf(10, 15, 30, 45)

@Composable
fun ScriptScreen(
    uiState: StudioUiState,
    onUpdateTitle: (String) -> Unit,
    onSetTargetDuration: (Int) -> Unit,
    onUpdateBeatText: (String, String) -> Unit,
    onToggleCutBeat: (String) -> Unit,
    onToggleMicRecord: (String?) -> Unit,
    onTogglePreviewWav: () -> Unit,
    onSetVoiceSource: (VoiceSource) -> Unit,
    onAddBeat: () -> Unit,
    onDeleteBeat: (String) -> Unit,
    onDismissError: () -> Unit,
    onRender: () -> Unit,
    onOpenClipper: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val context = LocalContext.current
    val project = uiState.activeProject
    var pendingRecordBeatId by remember { mutableStateOf<String?>(null) }
    var pendingRecordTriggered by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val shouldStart = pendingRecordTriggered
        val target = pendingRecordBeatId
        pendingRecordTriggered = false
        pendingRecordBeatId = null
        if (granted && shouldStart) {
            onToggleMicRecord(target)
        }
    }

    fun requestOrToggleMic(beatId: String?) {
        if (uiState.isRecordingMic) {
            onToggleMicRecord(beatId)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onToggleMicRecord(beatId)
        } else {
            pendingRecordBeatId = beatId
            pendingRecordTriggered = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                StudioErrorBanner(
                    message = uiState.errorMessage,
                    onDismiss = onDismissError
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .testTag("script_lazy_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Clean inline title + duration readout (2 adjacent elements)
            item(key = "script_title_row") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        BasicTextField(
                            value = project.title,
                            onValueChange = onUpdateTitle,
                            textStyle = MaterialTheme.typography.headlineSmall.copy(color = StudioCrisp),
                            cursorBrush = SolidColor(tokens.channelAccent),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("script_title_input")
                        )
                        if (project.title.isEmpty()) {
                            Text(
                                text = stringResource(R.string.title_placeholder),
                                style = MaterialTheme.typography.headlineSmall,
                                color = TelemetryMuted.copy(alpha = 0.55f),
                                maxLines = 1
                            )
                        }
                    }

                    val nextDuration = remember(project.targetDurationSec) {
                        val idx = DURATION_TARGETS.indexOf(project.targetDurationSec)
                        DURATION_TARGETS[(idx + 1) % DURATION_TARGETS.size]
                    }
                    Surface(
                        color = ConsoleElevated,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, tokens.hairline),
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSetTargetDuration(nextDuration) }
                            .testTag("target_duration_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${formatDurationBadge(project.totalActiveSeconds)} / ${project.targetDurationSec}s",
                                style = TelemetryTimecodeStyle,
                                color = if (project.totalActiveSeconds > project.targetDurationSec + 4) {
                                    tokens.hazardCut
                                } else {
                                    tokens.channelAccent
                                }
                            )
                        }
                    }
                }
            }

            // 2. Direct Local Mic Recorder & Voice Source Selector (Zero Firebase)
            item(key = "voice_recorder_card") {
                val hasRecordedWav = !project.masterAudioSha.isNullOrBlank()
                StudioCard(
                    highlighted = uiState.isRecordingMic,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_recorder_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            VoiceSource.entries.forEach { source ->
                                val selected = project.voiceSource == source
                                Surface(
                                    color = if (selected) tokens.channelAccentContainer else ConsoleElevated,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) tokens.channelAccent else tokens.hairline
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onSetVoiceSource(source) }
                                        .testTag("script_voice_mode_${source.name.lowercase()}")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = if (source == VoiceSource.MIC_VOICEOVER) "Mic Voice" else "Voice Clone",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (selected) tokens.channelAccent else StudioCrisp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { requestOrToggleMic(null) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (uiState.isRecordingMic) OnAirCrimson else tokens.channelAccent,
                                    contentColor = if (uiState.isRecordingMic) StudioCrisp else tokens.channelAccentInk
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("record_mic_button")
                            ) {
                                Icon(
                                    imageVector = if (uiState.isRecordingMic) Icons.Filled.Stop else Icons.Filled.Mic,
                                    contentDescription = stringResource(R.string.cd_record_mic),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (uiState.isRecordingMic) "Stop Recording" else "Record Voice",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (hasRecordedWav && !uiState.isRecordingMic) {
                                OutlinedButton(
                                    onClick = onTogglePreviewWav,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, tokens.hairline),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("preview_wav_button")
                                ) {
                                    Icon(
                                        imageVector = if (uiState.isPlayingAudio) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                        contentDescription = stringResource(R.string.cd_play_pause),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (uiState.isPlayingAudio) "Stop" else "Play")
                                }
                            }
                        }

                        if (uiState.waveformPeaks.isNotEmpty()) {
                            TelemetryWaveform(
                                peaks = uiState.waveformPeaks,
                                progress = if (uiState.isPlayingAudio) uiState.playbackProgress else 1f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .testTag("script_mic_waveform")
                            )
                        }
                    }
                }
            }

            // 3. Spoken Script Beat Cards (100% Local Room persistence)
            items(project.beats, key = { it.id }) { beat ->
                val isRecordingThisBeat = uiState.isRecordingMic && uiState.recordingTargetBeatId == beat.id

                BeatEditorCard(
                    beat = beat,
                    isRecordingMic = isRecordingThisBeat,
                    onTextChange = { onUpdateBeatText(beat.id, it) },
                    onToggleCut = { onToggleCutBeat(beat.id) },
                    onToggleMic = { requestOrToggleMic(beat.id) },
                    onDelete = { onDeleteBeat(beat.id) }
                )
            }

            // 4. Add Beat + Hairline Clipper controls
            item(key = "script_list_footer") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onAddBeat,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, tokens.hairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("add_beat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.cta_add_beat),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    if (uiState.otaBundleState.status == OtaBundleStatus.READY) {
                        OutlinedButton(
                            onClick = onOpenClipper,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.channelAccent.copy(alpha = 0.65f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.channelAccent),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("open_clipper_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Clipper",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }

        // Uniform Pinned Primary CTA Slot directly above the dock — always renders directly to Mac :8443
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            StudioPrimaryCta(
                text = if (uiState.isEnqueuingRender) "Queueing on Mac Rig…" else stringResource(R.string.cta_render_aircheck),
                onClick = onRender,
                enabled = !uiState.isEnqueuingRender && !uiState.isRecordingMic,
                modifier = Modifier.testTag("script_primary_cta")
            )
        }
    }
}

@Composable
private fun BeatEditorCard(
    beat: ScriptBeat,
    isRecordingMic: Boolean,
    onTextChange: (String) -> Unit,
    onToggleCut: () -> Unit,
    onToggleMic: () -> Unit,
    onDelete: () -> Unit
) {
    val tokens = LocalAircheckTokens.current

    StudioCard(
        highlighted = isRecordingMic,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("beat_card_${beat.id}")
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (beat.cut) {
                HazardCutOverlay(modifier = Modifier.matchParentSize())
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    BasicTextField(
                        value = beat.spokenText,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = if (beat.cut) TelemetryMuted else StudioCrisp,
                            textDecoration = if (beat.cut) TextDecoration.LineThrough else TextDecoration.None
                        ),
                        cursorBrush = SolidColor(tokens.channelAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("beat_input_${beat.id}")
                    )
                    if (beat.spokenText.isEmpty()) {
                        Text(
                            text = stringResource(R.string.beat_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TelemetryMuted.copy(alpha = 0.58f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (beat.cut) "Cut" else formatDurationBadge(beat.effectiveDurationSec),
                        style = TelemetryTimecodeStyle,
                        color = if (beat.cut) tokens.hazardCut else TelemetryMuted,
                        modifier = Modifier.weight(1f)
                    )
                    CompactBeatToolButton(
                        icon = if (isRecordingMic) Icons.Filled.Stop else Icons.Filled.Mic,
                        label = if (isRecordingMic) "Stop" else "Mic",
                        active = isRecordingMic || !beat.beatAudioSha.isNullOrBlank(),
                        onClick = onToggleMic,
                        modifier = Modifier.testTag("beat_mic_${beat.id}")
                    )
                    CompactBeatToolButton(
                        icon = Icons.Filled.ContentCut,
                        label = if (beat.cut) "Restore" else "Cut",
                        active = beat.cut,
                        onClick = onToggleCut,
                        modifier = Modifier.testTag("beat_cut_${beat.id}")
                    )
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("beat_delete_${beat.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Delete beat",
                            tint = TelemetryMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactBeatToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    Surface(
        color = if (active) tokens.channelAccentContainer else ConsoleElevated,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (active) tokens.channelAccent else tokens.hairline),
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) tokens.channelAccent else StudioCrisp,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) tokens.channelAccent else StudioCrisp,
                maxLines = 1
            )
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
