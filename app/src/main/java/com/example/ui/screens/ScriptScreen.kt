package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.ScriptBeat
import com.example.data.formatDurationBadge
import com.example.ui.StudioUiState
import com.example.ui.components.HazardCutOverlay
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioErrorBanner
import com.example.ui.components.StudioPrimaryCta
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle

private val DURATION_TARGETS = listOf(15, 30, 45, 60)

@Composable
fun ScriptScreen(
    uiState: StudioUiState,
    onUpdateTitle: (String) -> Unit,
    onUpdateSpark: (String) -> Unit,
    onSetTargetDuration: (Int) -> Unit,
    onToggleHoneSpark: () -> Unit,
    onUpdateBeatText: (String, String) -> Unit,
    onUpdateBeatMetadata: (
        beatId: String,
        mentionTag: String?,
        depthTextTag: String?,
        anchorWord: String?,
        evidenceSource: String?,
        sfxTag: String?,
        bRollPrompt: String?
    ) -> Unit,
    onToggleExpandBeat: (String) -> Unit,
    onFactCheckBeat: (String) -> Unit,
    onShorten20Percent: () -> Unit,
    onAcceptShortProposal: (String) -> Unit,
    onRejectShortProposal: (String) -> Unit,
    onToggleCutBeat: (String) -> Unit,
    onToggleBeatMicRecord: (String) -> Unit,
    onAddBeat: () -> Unit,
    onDeleteBeat: (String) -> Unit,
    onDismissError: () -> Unit,
    onPrimaryAction: () -> Unit,
    onOpenClipper: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val context = LocalContext.current
    val project = uiState.activeProject
    var expandedBeatId by remember { mutableStateOf<String?>(null) }
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
            // Unlabeled, clean inline title field + single tabular runtime readout
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

                    // Single subtle cycling target duration selector (15s / 30s / 45s / 60s)
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

            // Primary Spark Card with weightless inline ✨ trigger attached inside the card
            item(key = "spark_card") {
                val sparkShimmering = uiState.activeShimmerKey == "spark"
                StudioCard(
                    shimmering = sparkShimmering,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("spark_card")
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(top = 6.dp, end = 6.dp)
                                    .heightIn(min = 56.dp)
                            ) {
                                BasicTextField(
                                    value = project.ideaSpark,
                                    onValueChange = onUpdateSpark,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = StudioCrisp),
                                    cursorBrush = SolidColor(tokens.channelAccent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spark_input")
                                )
                                if (project.ideaSpark.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.spark_placeholder),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TelemetryMuted.copy(alpha = 0.65f)
                                    )
                                }
                            }

                            IconButton(
                                onClick = onToggleHoneSpark,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (sparkShimmering) tokens.channelAccent else tokens.channelAccentContainer
                                    )
                                    .testTag("spark_ai_button")
                            ) {
                                Icon(
                                    imageVector = if (sparkShimmering) Icons.Filled.Close else Icons.Filled.AutoAwesome,
                                    contentDescription = if (sparkShimmering) {
                                        stringResource(R.string.cd_ai_cancel)
                                    } else {
                                        stringResource(R.string.cd_ai_hone_spark)
                                    },
                                    tint = if (sparkShimmering) tokens.channelAccentInk else tokens.channelAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Breathable Script Beat Cards (zero step codes, <= 2 visible pills per beat)
            items(project.beats, key = { it.id }) { beat ->
                val isShimmering = uiState.activeShimmerKey == "beat_${beat.id}" ||
                    uiState.activeShimmerKey == "shorten"
                val isExpanded = expandedBeatId == beat.id
                val isRecordingThisBeat = uiState.isRecordingMic && uiState.recordingTargetBeatId == beat.id

                BeatEditorCard(
                    beat = beat,
                    shimmering = isShimmering,
                    expanded = isExpanded,
                    isRecordingMic = isRecordingThisBeat,
                    onTextChange = { onUpdateBeatText(beat.id, it) },
                    onToggleAi = { onToggleExpandBeat(beat.id) },
                    onToggleExpanded = {
                        expandedBeatId = if (isExpanded) null else beat.id
                    },
                    onFactCheck = { onFactCheckBeat(beat.id) },
                    onShorten20 = onShorten20Percent,
                    onAcceptShort = { onAcceptShortProposal(beat.id) },
                    onRejectShort = { onRejectShortProposal(beat.id) },
                    onToggleCut = { onToggleCutBeat(beat.id) },
                    onToggleMic = { requestOrToggleMic(beat.id) },
                    onDelete = { onDeleteBeat(beat.id) },
                    onMetadataChange = { mention, depth, anchor, source, sfx, broll ->
                        onUpdateBeatMetadata(beat.id, mention, depth, anchor, source, sfx, broll)
                    }
                )
            }

            // Lightweight Add Beat + Shorten 20% footer actions inside scroll flow
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

                    if (project.activeBeats.isNotEmpty()) {
                        val isShortening = uiState.activeShimmerKey == "shorten"
                        OutlinedButton(
                            onClick = onShorten20Percent,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isShortening) tokens.channelAccent else tokens.hairline
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (isShortening) tokens.channelAccent else TelemetryMuted
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("shorten_20_button")
                        ) {
                            Text(
                                text = if (isShortening) "Cancel" else stringResource(R.string.action_shorten_20),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            // Direct jump from Script -> VoiceStudio Hairline Clipper (dedicated row, <= 2 adjacent labels)
            item(key = "script_clipper_entry") {
                OutlinedButton(
                    onClick = onOpenClipper,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, tokens.channelAccent.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.channelAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_clipper_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hairline Clipper",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Uniform Pinned Primary CTA Slot directly above the dock
        val hasPhotoReady = !project.stageConfig.activePhotoSha.isNullOrBlank() ||
            uiState.settings.persona.defaultPhotoSha.isNotBlank()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            StudioPrimaryCta(
                text = if (hasPhotoReady && project.activeBeats.isNotEmpty()) {
                    if (uiState.isEnqueuingRender) "Queueing…" else stringResource(R.string.cta_render_aircheck)
                } else {
                    stringResource(R.string.cta_next_photo)
                },
                onClick = onPrimaryAction,
                enabled = !uiState.isEnqueuingRender,
                modifier = Modifier.testTag("script_primary_cta")
            )
        }
    }
}

@Composable
private fun BeatEditorCard(
    beat: ScriptBeat,
    shimmering: Boolean,
    expanded: Boolean,
    isRecordingMic: Boolean,
    onTextChange: (String) -> Unit,
    onToggleAi: () -> Unit,
    onToggleExpanded: () -> Unit,
    onFactCheck: () -> Unit,
    onShorten20: () -> Unit,
    onAcceptShort: () -> Unit,
    onRejectShort: () -> Unit,
    onToggleCut: () -> Unit,
    onToggleMic: () -> Unit,
    onDelete: () -> Unit,
    onMetadataChange: (
        mentionTag: String?,
        depthTextTag: String?,
        anchorWord: String?,
        evidenceSource: String?,
        sfxTag: String?,
        bRollPrompt: String?
    ) -> Unit
) {
    val tokens = LocalAircheckTokens.current

    StudioCard(
        shimmering = shimmering,
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
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp, end = 8.dp)
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

                    // Inline weightless ✨ trigger directly on the beat card
                    IconButton(
                        onClick = onToggleAi,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (shimmering) tokens.channelAccent else ConsoleElevated
                            )
                            .testTag("beat_ai_button_${beat.id}")
                    ) {
                        Icon(
                            imageVector = if (shimmering) Icons.Filled.Close else Icons.Filled.AutoAwesome,
                            contentDescription = if (shimmering) {
                                stringResource(R.string.cd_ai_cancel)
                            } else {
                                stringResource(R.string.cd_ai_expand_beat)
                            },
                            tint = if (shimmering) tokens.channelAccentInk else tokens.channelAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Inline Shorten 20% diff card (only when proposal exists)
                if (!beat.proposedShortText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(ConsoleElevated)
                            .border(1.dp, tokens.channelAccent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = beat.proposedShortText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioCrisp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = onRejectShort,
                                modifier = Modifier.testTag("keep_original_${beat.id}")
                            ) {
                                Text(
                                    text = stringResource(R.string.action_keep_original),
                                    color = TelemetryMuted
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            TextButton(
                                onClick = onAcceptShort,
                                modifier = Modifier.testTag("use_shorter_${beat.id}")
                            ) {
                                Text(
                                    text = stringResource(R.string.action_use_shorter),
                                    color = tokens.channelAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Strict <= 2:1 default metadata row: Duration + optional citation, plus quiet expand toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (beat.cut) "Cut" else formatDurationBadge(beat.effectiveDurationSec),
                            style = TelemetryTimecodeStyle,
                            color = if (beat.cut) tokens.hazardCut else TelemetryMuted
                        )
                        if (beat.hasCitation) {
                            Text(
                                text = beat.evidenceSource,
                                style = MaterialTheme.typography.labelMedium,
                                color = tokens.evidenceAmber,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("beat_expand_${beat.id}")
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = "Beat tools",
                            tint = TelemetryMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // On-demand beat tray (collapsed by default to preserve wide-open writing canvas)
                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HorizontalDivider(color = tokens.hairline)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompactBeatToolButton(
                                icon = Icons.Filled.FactCheck,
                                label = stringResource(R.string.action_fact_check),
                                active = beat.hasCitation,
                                onClick = onFactCheck,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("beat_fact_check_${beat.id}")
                            )
                            CompactBeatToolButton(
                                icon = if (isRecordingMic) Icons.Filled.Stop else Icons.Filled.Mic,
                                label = if (isRecordingMic) "Stop" else "Mic",
                                active = isRecordingMic || !beat.beatAudioSha.isNullOrBlank(),
                                onClick = onToggleMic,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("beat_mic_${beat.id}")
                            )
                            CompactBeatToolButton(
                                icon = Icons.Filled.ContentCut,
                                label = if (beat.cut) "Restore" else "Cut",
                                active = beat.cut,
                                onClick = onToggleCut,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("beat_cut_${beat.id}")
                            )
                            IconButton(
                                onClick = onDelete,
                                modifier = Modifier
                                    .size(48.dp)
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

                        OutlinedTextField(
                            value = beat.evidenceSource,
                            onValueChange = { onMetadataChange(null, null, null, it, null, null) },
                            placeholder = { Text("Source or citation", color = TelemetryMuted) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                            colors = studioFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = beat.depthTextTag,
                                onValueChange = { onMetadataChange(null, it, null, null, null, null) },
                                placeholder = { Text("On-screen keyword", color = TelemetryMuted) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                                colors = studioFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = beat.anchorWord,
                                onValueChange = { onMetadataChange(null, null, it, null, null, null) },
                                placeholder = { Text("Anchor word", color = TelemetryMuted) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                                colors = studioFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        OutlinedTextField(
                            value = beat.bRollPrompt,
                            onValueChange = { onMetadataChange(null, null, null, null, null, it) },
                            placeholder = { Text("B-roll cutaway visual cue", color = TelemetryMuted) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                            colors = studioFieldColors(),
                            modifier = Modifier.fillMaxWidth()
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
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
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
