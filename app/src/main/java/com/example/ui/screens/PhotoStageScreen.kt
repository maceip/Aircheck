package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.NewsroomStageConfig
import com.example.data.local.CasMediaStore
import com.example.ui.StudioUiState
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioErrorBanner
import com.example.ui.components.StudioPrimaryCta
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle

/**
 * Tab 2 — Desk:
 * Live TV news broadcast preview showing your real anchor photo + real-time LIVE bug &
 * BREAKING NEWS lower-third banner, with ✨ AI Assist buttons on BOTH Headline & Subline.
 */
@Composable
fun PhotoStageScreen(
    uiState: StudioUiState,
    casStore: CasMediaStore,
    onImportPhoto: (Uri) -> Unit,
    onUpdateStageConfig: ((NewsroomStageConfig) -> NewsroomStageConfig) -> Unit,
    onAiPolishHeadline: () -> Unit,
    onAiPolishSubline: () -> Unit,
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
    val imageModel: Any = previewFile ?: uiState.defaultAvatarUrl
    val liveHeadline = stage.chyronHeadline.ifBlank { project.title }.ifBlank { "BREAKING NEWS" }.uppercase()
    val liveSubline = stage.chyronSubline.ifBlank { "AIRCHECK LIVE DESK" }.uppercase()

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
            // 1. Live Broadcast Preview (Your real anchor portrait + live lower-third preview)
            item(key = "broadcast_preview_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0A0D12))
                                .border(1.dp, tokens.hairline, RoundedCornerShape(8.dp))
                                .testTag("anchor_preview_box")
                        ) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = "News anchor portrait",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            if (stage.showBrandBug) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(10.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .background(OnAirCrimson)
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                        Text(
                                            text = "LIVE",
                                            style = TelemetryTimecodeStyle.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xE00A0C10))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "AIRCHECK NEWS",
                                            style = TelemetryTimecodeStyle,
                                            color = StudioCrisp
                                        )
                                    }
                                }
                            }

                            // Live lower-third chyron preview mirroring what burns into the video
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .padding(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(OnAirCrimson)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "BREAKING NEWS",
                                        style = TelemetryTimecodeStyle.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xE8090C12))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(48.dp)
                                            .background(tokens.channelAccent)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = liveHeadline,
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = liveSubline,
                                            style = TelemetryTimecodeStyle,
                                            color = tokens.channelAccent,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
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
                                .height(46.dp)
                                .testTag("pick_photo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddPhotoAlternate,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Change anchor photo",
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 2. Breaking News Headline Input with ✨ AI Assist button
            item(key = "headline_card") {
                val isShimmering = uiState.activeAiFieldKey == "chyron_headline"
                StudioCard(
                    shimmering = isShimmering,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Breaking news headline",
                            style = MaterialTheme.typography.labelLarge,
                            color = TelemetryMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                BasicTextField(
                                    value = stage.chyronHeadline,
                                    onValueChange = { v -> onUpdateStageConfig { it.copy(chyronHeadline = v) } },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = StudioCrisp),
                                    cursorBrush = SolidColor(tokens.channelAccent),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("chyron_headline_input")
                                )
                                if (stage.chyronHeadline.isEmpty()) {
                                    Text(
                                        text = "e.g. ILLEGAL HOCKEY ON GOLDEN GATE",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TelemetryMuted.copy(alpha = 0.58f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            AiAssistIconButton(
                                active = isShimmering,
                                contentDescription = stringResource(R.string.cd_ai_polish_headline),
                                onClick = onAiPolishHeadline,
                                modifier = Modifier.testTag("headline_ai_button")
                            )
                        }
                    }
                }
            }

            // 3. Lower-Third Ticker Subline Input with ✨ AI Assist button
            item(key = "subline_card") {
                val isShimmering = uiState.activeAiFieldKey == "chyron_subline"
                StudioCard(
                    shimmering = isShimmering,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Lower-third subline",
                            style = MaterialTheme.typography.labelLarge,
                            color = TelemetryMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                BasicTextField(
                                    value = stage.chyronSubline,
                                    onValueChange = { v -> onUpdateStageConfig { it.copy(chyronSubline = v) } },
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = StudioCrisp),
                                    cursorBrush = SolidColor(tokens.channelAccent),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("chyron_subline_input")
                                )
                                if (stage.chyronSubline.isEmpty()) {
                                    Text(
                                        text = "e.g. DEVELOPING · SAN FRANCISCO",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = TelemetryMuted.copy(alpha = 0.58f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            AiAssistIconButton(
                                active = isShimmering,
                                contentDescription = stringResource(R.string.cd_ai_polish_subline),
                                onClick = onAiPolishSubline,
                                modifier = Modifier.testTag("subline_ai_button")
                            )
                        }
                    }
                }
            }

            // 4. LIVE Bug Toggle
            item(key = "live_bug_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Show LIVE badge",
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            StudioPrimaryCta(
                text = if (uiState.isEnqueuingRender) "Generating clip…" else stringResource(R.string.cta_generate_clip),
                onClick = onEnqueueRender,
                enabled = !uiState.isEnqueuingRender,
                modifier = Modifier.testTag("photo_render_cta")
            )
        }
    }
}
