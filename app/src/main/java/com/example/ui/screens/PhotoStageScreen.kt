package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.NewsroomStageConfig
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
 * Local Presenter Photo, Voice Mode & Chyron Settings.
 * Zero Firebase / Cloud Pose Gate dependencies: uses Mac Rig enrolled portrait by default,
 * or sends any optional phone portrait cached locally in CAS directly to `:8443/v1/render-video`.
 */
@Composable
fun PhotoStageScreen(
    uiState: StudioUiState,
    casStore: CasMediaStore,
    onImportPhoto: (Uri) -> Unit,
    onUpdateStageConfig: ((NewsroomStageConfig) -> NewsroomStageConfig) -> Unit,
    onSetVoiceSource: (VoiceSource) -> Unit,
    onEnqueueRender: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val project = uiState.activeProject
    val stage = project.stageConfig
    val persona = uiState.settings.persona

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onImportPhoto(uri)
    }

    val activeSha = stage.activePhotoSha ?: persona.defaultPhotoSha.ifBlank { null }
    val previewFile = remember(activeSha) {
        casStore.fileForSha(activeSha, "jpg")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                StudioErrorBanner(message = uiState.errorMessage, onDismiss = onDismissError)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .testTag("photo_lazy_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Optional Custom Presenter Photo (Mac Rig default portrait active otherwise)
            item(key = "photo_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Presenter photo",
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
                            if (previewFile != null) {
                                AsyncImage(
                                    model = previewFile,
                                    contentDescription = "Selected presenter photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = tokens.statusLive,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Using Mac Rig default portrait",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = StudioCrisp
                                    )
                                }
                            }
                        }

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
                                .fillMaxWidth()
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
                                text = if (activeSha == null) "Pick custom photo (optional)" else "Change photo",
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 2. Voice Source (Mic Voiceover WAV or Local Mac Rig Voice Clone)
            item(key = "voice_source_card") {
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
                                text = "Voice source",
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
                    }
                }
            }

            // 3. Lower-Third Broadcast Chyron
            item(key = "chyron_section") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Lower-third graphic",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        OutlinedTextField(
                            value = stage.chyronHeadline,
                            onValueChange = { v -> onUpdateStageConfig { it.copy(chyronHeadline = v) } },
                            placeholder = { Text("Lower-third headline", color = TelemetryMuted) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                            colors = studioFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chyron_headline_input")
                        )
                        OutlinedTextField(
                            value = stage.chyronSubline,
                            onValueChange = { v -> onUpdateStageConfig { it.copy(chyronSubline = v) } },
                            placeholder = { Text("Subline or source credit", color = TelemetryMuted) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                            colors = studioFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chyron_subline_input")
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
                                ),
                                modifier = Modifier.testTag("show_brand_bug_switch")
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            StudioPrimaryCta(
                text = if (uiState.isEnqueuingRender) "Queueing on Mac Rig…" else stringResource(R.string.cta_render_aircheck),
                onClick = onEnqueueRender,
                enabled = !uiState.isEnqueuingRender,
                modifier = Modifier.testTag("photo_render_cta")
            )
        }
    }
}
