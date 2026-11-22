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
import coil.compose.AsyncImage
import com.example.R
import com.example.data.BackdropPreset
import com.example.data.NewsroomStageConfig
import com.example.data.VoiceSource
import com.example.data.local.MediaStorageManager
import com.example.ui.StudioUiState
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioErrorBanner
import com.example.ui.components.StudioPrimaryCta
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted

@Composable
fun StageScreen(
    uiState: StudioUiState,
    mediaStorage: MediaStorageManager,
    onImportSelfie: (Uri) -> Unit,
    onUpdateStageConfig: ((NewsroomStageConfig) -> NewsroomStageConfig) -> Unit,
    onComposeAnchor: () -> Unit,
    onSetVoiceSource: (VoiceSource) -> Unit,
    onSetVoiceSelectionId: (String) -> Unit,
    onGenerateBeatBRoll: (String) -> Unit,
    onRenderVideo: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val project = uiState.activeProject
    val stage = project.stageConfig

    var showChyronSection by remember { mutableStateOf(false) }
    var showBRollSection by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onImportSelfie(uri)
    }

    val previewFile = remember(stage.compositedAnchorKey, stage.selfieImageKey) {
        mediaStorage.fileForKey(stage.compositedAnchorKey)
            ?: mediaStorage.fileForKey(stage.selfieImageKey)
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!uiState.errorMessage.isNullOrBlank()) {
                item(key = "stage_error") {
                    StudioErrorBanner(message = uiState.errorMessage, onDismiss = onDismissError)
                }
            }

            // 1. Presenter Likeness & Studio Anchor Frame
            item(key = "anchor_card") {
                StudioCard(
                    shimmering = uiState.isComposingAnchor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Anchor portrait",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )

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
                                        contentDescription = "Anchor studio frame",
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
                                            text = "Import a frontal selfie to build your anchor",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TelemetryMuted
                                        )
                                    }
                                }
                            }
                        }

                        if (stage.faceValidationNote.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (stage.faceValidated) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = if (stage.faceValidated) tokens.statusLive else tokens.evidenceAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stage.faceValidationNote,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (stage.faceValidated) StudioCrisp else tokens.evidenceAmber
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
                                    .testTag("import_selfie_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AddPhotoAlternate,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (stage.selfieImageKey == null) "Import selfie" else "Change selfie",
                                    maxLines = 1
                                )
                            }

                            Button(
                                onClick = onComposeAnchor,
                                enabled = !uiState.isComposingAnchor && !stage.selfieImageKey.isNullOrBlank(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tokens.channelAccentContainer,
                                    contentColor = tokens.channelAccent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("compose_anchor_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (uiState.isComposingAnchor) "Compositing…" else "AI Compose",
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // 2. Newsroom Backdrop Presets (includes bundled Volt & Cobalt Truss Globe + Neon Triangle)
            item(key = "backdrop_section") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Studio backdrop",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(BackdropPreset.entries, key = { it.id }) { preset ->
                                val selected = preset.id == stage.backdropPresetId
                                Column(
                                    modifier = Modifier
                                        .width(124.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) tokens.channelAccentContainer else ConsoleElevated)
                                        .border(
                                            width = 1.dp,
                                            color = if (selected) tokens.channelAccent else tokens.hairline,
                                            shape = RoundedCornerShape(8.dp)
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
                            placeholder = { Text("Optional custom studio set description", color = TelemetryMuted) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                            colors = studioFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 3. Voice Source (Adult-Male Dry TTS / Mic Voiceover / Voice Clone)
            item(key = "voice_source_section") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                text = "Voice track",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
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

                        if (project.voiceSource == VoiceSource.MALE_TTS_DRY_RUN && uiState.availableMaleVoices.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(uiState.availableMaleVoices.take(4), key = { it.name }) { voice ->
                                    val chosen = project.voiceSelectionId == voice.name
                                    val shortLabel = voice.name.substringAfterLast("-").uppercase()
                                    Surface(
                                        color = if (chosen) tokens.channelAccentContainer else OledVoid,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (chosen) tokens.channelAccent else tokens.hairline
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onSetVoiceSelectionId(voice.name) }
                                    ) {
                                        Text(
                                            text = "Voice $shortLabel",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (chosen) tokens.channelAccent else TelemetryMuted,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Collapsible Lower-Third Chyron & Corner Bug
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

            // 5. Collapsible B-Roll Plates per Beat
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
                                    val brollFile = mediaStorage.fileForKey(beat.bRollImageKey)
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

        // Uniform Pinned Primary CTA Slot
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            StudioPrimaryCta(
                text = stringResource(R.string.cta_render_aircheck),
                onClick = onRenderVideo,
                enabled = !uiState.renderProgress.active,
                modifier = Modifier.testTag("stage_render_cta")
            )
        }
    }
}
