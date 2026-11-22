package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.BackdropPreset
import com.example.data.NewsroomStageConfig
import com.example.data.Stage4Mode
import com.example.data.VoiceSource
import com.example.data.local.CasMediaStore
import com.example.ui.StudioUiState
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioErrorBanner
import com.example.ui.components.StudioPrimaryCta
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted

/**
 * Daily On-Phone Common Path: Pick 1 photo -> Cloud Pose Gate + optional 1-tap `head_model_id`
 * eye-level normalizer -> Stage 4 contract selector -> Enqueue Cloud Render.
 */
@Composable
fun PhotoStageScreen(
    uiState: StudioUiState,
    casStore: CasMediaStore,
    onImportPhoto: (Uri) -> Unit,
    onNormalizePose: () -> Unit,
    onUpdateStageConfig: ((NewsroomStageConfig) -> NewsroomStageConfig) -> Unit,
    onSetStage4Mode: (Stage4Mode) -> Unit,
    onSetVoiceSource: (VoiceSource) -> Unit,
    onGenerateBeatBRoll: (String) -> Unit,
    onEnqueueRender: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val project = uiState.activeProject
    val stage = project.stageConfig
    val persona = uiState.settings.persona

    var showBackdropSection by remember { mutableStateOf(false) }
    var showChyronSection by remember { mutableStateOf(false) }
    var showBRollSection by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onImportPhoto(uri)
    }

    val activeSha = stage.activePhotoSha ?: persona.defaultPhotoSha.ifBlank { null }
    val previewFile = remember(activeSha) {
        casStore.fileForSha(activeSha, "jpg")
    }
    val selectedPreset = remember(stage.backdropPresetId) {
        BackdropPreset.fromId(stage.backdropPresetId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!uiState.errorMessage.isNullOrBlank()) {
                item(key = "photo_error") {
                    StudioErrorBanner(message = uiState.errorMessage, onDismiss = onDismissError)
                }
            }

            // 1. Single Daily Phone Photo + Cloud Pose Gate
            item(key = "photo_card") {
                StudioCard(
                    shimmering = uiState.isCheckingPose || uiState.isNormalizingPose,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Presenter photo",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            if (persona.headModelId.isNotBlank()) {
                                Text(
                                    text = persona.headModelId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.channelAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 10f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(OledVoid)
                                .border(1.dp, tokens.hairline, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                previewFile != null -> {
                                    AsyncImage(
                                        model = previewFile,
                                        contentDescription = "Selected presenter photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                selectedPreset.bundledDrawable != null -> {
                                    Image(
                                        painter = painterResource(selectedPreset.bundledDrawable),
                                        contentDescription = selectedPreset.label,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                else -> {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Filled.AddPhotoAlternate,
                                            contentDescription = null,
                                            tint = TelemetryMuted,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Pick 1 photo from your phone",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TelemetryMuted
                                        )
                                    }
                                }
                            }
                        }

                        if (stage.poseGate.note.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (stage.poseGate.valid) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = if (stage.poseGate.valid) tokens.statusLive else tokens.evidenceAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stage.poseGate.note,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (stage.poseGate.valid) StudioCrisp else tokens.evidenceAmber
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, tokens.hairline),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("pick_photo_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AddPhotoAlternate,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (activeSha == null) "Pick photo" else "Change photo",
                                    maxLines = 1
                                )
                            }

                            Button(
                                onClick = onNormalizePose,
                                enabled = !uiState.isNormalizingPose && activeSha != null,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tokens.channelAccentContainer,
                                    contentColor = tokens.channelAccent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("normalize_pose_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (uiState.isNormalizingPose) "Normalizing…" else "Pose fix",
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // 2. Stage 4 Render Contract (Photo + Head LoRA / Saved Loop / LibreClip 9:16)
            item(key = "stage4_mode_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Head renderer",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        Stage4Mode.entries.forEach { mode ->
                            val selected = stage.stage4Mode == mode
                            Surface(
                                color = if (selected) tokens.channelAccentContainer else ConsoleElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) tokens.channelAccent else tokens.hairline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSetStage4Mode(mode) }
                                    .testTag("stage4_mode_${mode.id}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) tokens.channelAccent else StudioCrisp
                                    )
                                    Text(
                                        text = mode.subtitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TelemetryMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Voice Source (Enrolled Voice ID or Live Mic WAV — zero silent fallback)
            item(key = "voice_source_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = null,
                                    tint = tokens.channelAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Voice",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioCrisp
                                )
                            }
                            if (persona.voiceId.isNotBlank()) {
                                Text(
                                    text = persona.voiceId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.channelAccent
                                )
                            }
                        }

                        VoiceSource.entries.forEach { source ->
                            val selected = project.voiceSource == source
                            Surface(
                                color = if (selected) tokens.channelAccentContainer else ConsoleElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) tokens.channelAccent else tokens.hairline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSetVoiceSource(source) }
                                    .testTag("voice_source_${source.name.lowercase()}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = source.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) tokens.channelAccent else StudioCrisp
                                    )
                                    Text(
                                        text = source.subtitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TelemetryMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Collapsible Studio Backdrop (for pose normalization / Flux Kontext scene dress)
            item(key = "backdrop_section") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showBackdropSection = !showBackdropSection }
                                .padding(vertical = 4.dp)
                                .testTag("backdrop_expand_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Studio backdrop",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            Icon(
                                imageVector = if (showBackdropSection) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = TelemetryMuted
                            )
                        }
                        AnimatedVisibility(visible = showBackdropSection) {
                            Column(
                                modifier = Modifier.padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(BackdropPreset.entries, key = { it.id }) { preset ->
                                        val selected = preset.id == stage.backdropPresetId
                                        Column(
                                            modifier = Modifier
                                                .width(124.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (selected) tokens.channelAccentContainer else ConsoleElevated)
                                                .border(
                                                    1.dp,
                                                    if (selected) tokens.channelAccent else tokens.hairline,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    onUpdateStageConfig { it.copy(backdropPresetId = preset.id) }
                                                }
                                                .testTag("backdrop_${preset.id}")
                                        ) {
                                            if (preset.bundledDrawable != null) {
                                                Image(
                                                    painter = painterResource(preset.bundledDrawable),
                                                    contentDescription = preset.label,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(64.dp)
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(64.dp)
                                                        .background(OledVoid),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Image,
                                                        contentDescription = null,
                                                        tint = if (selected) tokens.channelAccent else TelemetryMuted,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = preset.label,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (selected) tokens.channelAccent else StudioCrisp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = stage.customBackdropPrompt,
                                    onValueChange = { v -> onUpdateStageConfig { it.copy(customBackdropPrompt = v) } },
                                    placeholder = { Text("Optional custom scene prompt", color = TelemetryMuted) },
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

            // 5. Collapsible Lower-Third Graphic
            item(key = "chyron_section") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showChyronSection = !showChyronSection }
                                .padding(vertical = 4.dp)
                                .testTag("chyron_expand_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Lower-third graphic",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            Icon(
                                imageVector = if (showChyronSection) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = TelemetryMuted
                            )
                        }
                        AnimatedVisibility(visible = showChyronSection) {
                            Column(
                                modifier = Modifier.padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = stage.chyronHeadline,
                                    onValueChange = { v -> onUpdateStageConfig { it.copy(chyronHeadline = v) } },
                                    placeholder = { Text("Lower-third headline", color = TelemetryMuted) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                                    colors = studioFieldColors(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = stage.chyronSubline,
                                    onValueChange = { v -> onUpdateStageConfig { it.copy(chyronSubline = v) } },
                                    placeholder = { Text("Subline or source credit", color = TelemetryMuted) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                                    colors = studioFieldColors(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Show corner brand bug",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = StudioCrisp
                                    )
                                    Switch(
                                        checked = stage.showBrandBug,
                                        onCheckedChange = { checked ->
                                            onUpdateStageConfig { it.copy(showBrandBug = checked) }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = tokens.channelAccentInk,
                                            checkedTrackColor = tokens.channelAccent
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 6. Collapsible Beat B-Roll Cutaways
            if (project.activeBeats.isNotEmpty()) {
                item(key = "broll_section") {
                    StudioCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showBRollSection = !showBRollSection }
                                    .padding(vertical = 4.dp)
                                    .testTag("broll_expand_row"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Beat B-roll cutaways",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = StudioCrisp
                                )
                                Icon(
                                    imageVector = if (showBRollSection) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = TelemetryMuted
                                )
                            }
                            AnimatedVisibility(visible = showBRollSection) {
                                Column(
                                    modifier = Modifier.padding(top = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    project.activeBeats.forEachIndexed { idx, beat ->
                                        val brollFile = casStore.fileForSha(beat.bRollSha, "jpg")
                                        val generating = uiState.generatingBRollBeatId == beat.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(ConsoleElevated)
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            if (brollFile != null) {
                                                AsyncImage(
                                                    model = brollFile,
                                                    contentDescription = "B-roll plate",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                )
                                            }
                                            Text(
                                                text = beat.bRollPrompt.ifBlank { beat.spokenText },
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = StudioCrisp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedButton(
                                                onClick = { onGenerateBeatBRoll(beat.id) },
                                                enabled = !generating,
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(1.dp, tokens.channelAccent),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                                modifier = Modifier
                                                    .height(40.dp)
                                                    .testTag("generate_broll_$idx")
                                            ) {
                                                Text(
                                                    text = if (generating) "…" else "Generate",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = tokens.channelAccent
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Uniform Pinned Primary CTA Slot
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            StudioPrimaryCta(
                text = if (uiState.isEnqueuingRender) "Queueing…" else stringResource(R.string.cta_render_aircheck),
                onClick = onEnqueueRender,
                enabled = !uiState.isEnqueuingRender,
                modifier = Modifier.testTag("photo_render_cta")
            )
        }
    }
}
