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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.ScriptBeat
import com.example.data.formatDurationBadge
import com.example.ui.StudioUiState
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

/**
 * Tab 1 — Story:
 * Drop a raw news idea -> tap ✨ to flesh it out into punchy broadcast segments ->
 * polish any segment with its own ✨ AI button (or optionally record your own voice) ->
 * tap Generate News Clip.
 */
@Composable
fun ScriptScreen(
    uiState: StudioUiState,
    onUpdateIdea: (String) -> Unit,
    onAiFleshOutStory: () -> Unit,
    onUpdateBeatText: (String, String) -> Unit,
    onAiPolishSegment: (String) -> Unit,
    onToggleBeatMicRecord: (String) -> Unit,
    onTogglePreviewWav: () -> Unit,
    onClearMicTake: () -> Unit,
    onAddBeat: () -> Unit,
    onDeleteBeat: (String) -> Unit,
    onNewStory: () -> Unit,
    onDismissError: () -> Unit,
    onRender: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val context = LocalContext.current
    val project = uiState.activeProject
    var pendingRecordBeatId by remember { mutableStateOf<String?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingRecordBeatId
        pendingRecordBeatId = null
        if (granted && target != null) {
            onToggleBeatMicRecord(target)
        }
    }

    fun requestOrToggleMic(beatId: String) {
        if (uiState.isRecordingMic) {
            onToggleBeatMicRecord(beatId)
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onToggleBeatMicRecord(beatId)
        } else {
            pendingRecordBeatId = beatId
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
            // 1. Story Idea Input with ✨ AI Story Writer button
            item(key = "story_idea_card") {
                val isShimmering = uiState.activeAiFieldKey == "story_idea"
                StudioCard(
                    shimmering = isShimmering,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("story_idea_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Story idea",
                            style = MaterialTheme.typography.labelLarge,
                            color = TelemetryMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(top = 4.dp)
                                    .heightIn(min = 52.dp)
                            ) {
                                BasicTextField(
                                    value = project.ideaSpark,
                                    onValueChange = onUpdateIdea,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = StudioCrisp),
                                    cursorBrush = SolidColor(tokens.channelAccent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("story_idea_input")
                                )
                                if (project.ideaSpark.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.idea_placeholder),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TelemetryMuted.copy(alpha = 0.62f)
                                    )
                                }
                            }
                            AiAssistIconButton(
                                active = isShimmering,
                                contentDescription = stringResource(R.string.cd_ai_flesh_out_story),
                                onClick = onAiFleshOutStory,
                                modifier = Modifier.testTag("idea_ai_button")
                            )
                        }
                    }
                }
            }

            // Optional recorded voice take strip (only visible once a user actually records audio)
            if (!project.masterAudioSha.isNullOrBlank() || uiState.isRecordingMic) {
                item(key = "recorded_take_card") {
                    StudioCard(
                        highlighted = uiState.isRecordingMic,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recorded_take_card")
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (uiState.isRecordingMic) "Recording your voice…" else "Using your recorded voice",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (uiState.isRecordingMic) OnAirCrimson else tokens.channelAccent
                                )
                                if (!uiState.isRecordingMic) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(
                                            color = ConsoleElevated,
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, tokens.hairline),
                                            modifier = Modifier
                                                .height(36.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable(onClick = onTogglePreviewWav)
                                                .testTag("preview_wav_button")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (uiState.isPlayingAudio) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                                    contentDescription = stringResource(R.string.cd_play_pause),
                                                    tint = StudioCrisp,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = if (uiState.isPlayingAudio) "Stop" else "Play",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = StudioCrisp
                                                )
                                            }
                                        }
                                        Surface(
                                            color = ConsoleElevated,
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, tokens.hairline),
                                            modifier = Modifier
                                                .height(36.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable(onClick = onClearMicTake)
                                                .testTag("clear_mic_take_button")
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(horizontal = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Use AI voice",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = TelemetryMuted
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (uiState.waveformPeaks.isNotEmpty()) {
                                TelemetryWaveform(
                                    peaks = uiState.waveformPeaks,
                                    progress = if (uiState.isPlayingAudio) uiState.playbackProgress else 1f,
                                    isRecording = uiState.isRecordingMic,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                        .testTag("script_mic_waveform")
                                )
                            }
                        }
                    }
                }
            }

            // 2. Broadcast Segments — every text input has its own ✨ AI Assist button
            itemsIndexed(project.beats, key = { _, beat -> beat.id }) { idx, beat ->
                val isShimmering = uiState.activeAiFieldKey == "segment_${beat.id}"
                val isRecordingThisBeat = uiState.isRecordingMic && uiState.recordingTargetBeatId == beat.id

                SegmentCard(
                    index = idx,
                    beat = beat,
                    canDelete = project.beats.size > 1,
                    shimmering = isShimmering,
                    isRecordingMic = isRecordingThisBeat,
                    onTextChange = { onUpdateBeatText(beat.id, it) },
                    onAiPolish = { onAiPolishSegment(beat.id) },
                    onToggleMic = { requestOrToggleMic(beat.id) },
                    onDelete = { onDeleteBeat(beat.id) }
                )
            }

            // 3. Add Segment + New Story footer
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
                            text = stringResource(R.string.cta_add_segment),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = onNewStory,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, tokens.hairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryMuted),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("new_story_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "New",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        // Single clear primary action at the bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            StudioPrimaryCta(
                text = if (uiState.isEnqueuingRender) "Generating clip…" else stringResource(R.string.cta_generate_clip),
                onClick = onRender,
                enabled = !uiState.isEnqueuingRender && !uiState.isRecordingMic,
                modifier = Modifier.testTag("script_primary_cta")
            )
        }
    }
}

@Composable
private fun SegmentCard(
    index: Int,
    beat: ScriptBeat,
    canDelete: Boolean,
    shimmering: Boolean,
    isRecordingMic: Boolean,
    onTextChange: (String) -> Unit,
    onAiPolish: () -> Unit,
    onToggleMic: () -> Unit,
    onDelete: () -> Unit
) {
    val tokens = LocalAircheckTokens.current

    StudioCard(
        shimmering = shimmering,
        highlighted = isRecordingMic,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("beat_card_${beat.id}")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Segment ${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TelemetryMuted
                )
                Text(
                    text = formatDurationBadge(beat.effectiveDurationSec),
                    style = TelemetryTimecodeStyle,
                    color = TelemetryMuted
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp)
                        .heightIn(min = 54.dp)
                ) {
                    BasicTextField(
                        value = beat.spokenText,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = StudioCrisp),
                        cursorBrush = SolidColor(tokens.channelAccent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("beat_input_${beat.id}")
                    )
                    if (beat.spokenText.isEmpty()) {
                        Text(
                            text = stringResource(R.string.segment_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TelemetryMuted.copy(alpha = 0.58f)
                        )
                    }
                }

                AiAssistIconButton(
                    active = shimmering,
                    contentDescription = stringResource(R.string.cd_ai_polish_segment),
                    onClick = onAiPolish,
                    modifier = Modifier.testTag("segment_ai_button_${beat.id}")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = if (isRecordingMic) OnAirCrimson else if (!beat.beatAudioSha.isNullOrBlank()) tokens.channelAccentContainer else ConsoleElevated,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isRecordingMic) OnAirCrimson else if (!beat.beatAudioSha.isNullOrBlank()) tokens.channelAccent else tokens.hairline
                    ),
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onToggleMic)
                        .testTag("beat_mic_${beat.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isRecordingMic) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.cd_record_mic),
                            tint = if (isRecordingMic) StudioCrisp else if (!beat.beatAudioSha.isNullOrBlank()) tokens.channelAccent else StudioCrisp,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (isRecordingMic) "Stop recording" else if (!beat.beatAudioSha.isNullOrBlank()) "Re-record voice" else "Record my voice",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isRecordingMic) StudioCrisp else if (!beat.beatAudioSha.isNullOrBlank()) tokens.channelAccent else StudioCrisp
                        )
                    }
                }

                if (canDelete) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("beat_delete_${beat.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Delete segment",
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
fun AiAssistIconButton(
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) tokens.channelAccent else tokens.channelAccentContainer)
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.Close else Icons.Filled.AutoAwesome,
            contentDescription = contentDescription,
            tint = if (active) tokens.channelAccentInk else tokens.channelAccent,
            modifier = Modifier.size(20.dp)
        )
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
