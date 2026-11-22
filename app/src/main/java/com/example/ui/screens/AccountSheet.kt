package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BrandChannel
import com.example.data.StageProviderSettings
import com.example.ui.StudioUiState
import com.example.ui.components.AircheckBrandMark
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioPrimaryCta
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted

@Composable
fun AccountSheet(
    uiState: StudioUiState,
    onSignInWithGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
    onCreateNewProject: () -> Unit,
    onSavePersonaIds: (headModelId: String, voiceId: String) -> Unit,
    onSaveProviders: (StageProviderSettings) -> Unit,
    onStartPersonaTraining: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val heroRes = if (uiState.settings.brandChannel == BrandChannel.VOLT_LIME) {
        R.drawable.brand_globe_lime
    } else {
        R.drawable.brand_globe_cobalt
    }

    val persona = uiState.settings.persona
    val providers = uiState.settings.providers

    var headModelId by remember(persona.headModelId) { mutableStateOf(persona.headModelId) }
    var voiceId by remember(persona.voiceId) { mutableStateOf(persona.voiceId) }
    var trainingUris by remember { mutableStateOf("") }
    var lanUrl by remember(providers.lanGatewayUrl) { mutableStateOf(providers.lanGatewayUrl) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AircheckBrandMark(size = 24.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Account & Persona",
                style = MaterialTheme.typography.titleMedium,
                color = StudioCrisp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("close_account_sheet_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close_sheet),
                    tint = StudioCrisp
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(tokens.hairline)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .testTag("account_lazy_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Aircheck Cloud Auth
            item(key = "cloud_auth_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, tokens.hairline, RoundedCornerShape(6.dp))
                        ) {
                            Image(
                                painter = painterResource(heroRes),
                                contentDescription = "Aircheck Global Broadcast Network",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        if (uiState.isSignedIn) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudDone,
                                    contentDescription = null,
                                    tint = tokens.statusLive,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = uiState.userDisplayName ?: "Signed in",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = StudioCrisp
                                    )
                                    if (!uiState.userEmail.isNullOrBlank()) {
                                        Text(
                                            text = uiState.userEmail,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TelemetryMuted
                                        )
                                    }
                                }
                            }

                            if (!uiState.lastSyncMessage.isNullOrBlank()) {
                                Text(
                                    text = uiState.lastSyncMessage,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = tokens.channelAccent
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onSyncNow,
                                    enabled = !uiState.isSyncingCloud,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, tokens.channelAccent),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.channelAccent),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("account_sync_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CloudSync,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (uiState.isSyncingCloud) "Syncing…" else stringResource(R.string.action_sync_now)
                                    )
                                }

                                OutlinedButton(
                                    onClick = onSignOut,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, tokens.hairline),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TelemetryMuted),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("account_sign_out_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Logout,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(text = stringResource(R.string.action_sign_out))
                                }
                            }
                        } else {
                            Text(
                                text = "Sync & cloud rendering",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            Text(
                                text = "Scripts save locally in Room WAL immediately. Sign in with Google to run Cloud Functions v2 rendering and persona sync.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TelemetryMuted
                            )
                            StudioPrimaryCta(
                                text = stringResource(R.string.action_sign_in_google),
                                onClick = onSignInWithGoogle,
                                modifier = Modifier.testTag("google_sign_in_button")
                            )
                        }
                    }
                }
            }

            // 2. Quick New Segment Trigger
            item(key = "new_segment_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start a fresh segment",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            Text(
                                text = "${uiState.projects.size} saved in Room",
                                style = MaterialTheme.typography.labelMedium,
                                color = TelemetryMuted
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onCreateNewProject()
                                onClose()
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.channelAccent),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.channelAccent),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("account_new_segment_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New")
                        }
                    }
                }
            }

            // 3. Enrolled Persona IDs (Phase A Cloud Head LoRA + Persistent Voice ID -> Proto DataStore)
            item(key = "persona_enrollment_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Enrolled persona models",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        if (persona.trainingStatus.isNotBlank()) {
                            Text(
                                text = "Training: ${persona.trainingStatus}",
                                style = MaterialTheme.typography.labelMedium,
                                color = tokens.channelAccent
                            )
                        }
                        OutlinedTextField(
                            value = headModelId,
                            onValueChange = { headModelId = it },
                            placeholder = { Text("head_model_id (e.g. ryan_head_v1)", color = TelemetryMuted) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                            colors = studioFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("head_model_id_input")
                        )
                        OutlinedTextField(
                            value = voiceId,
                            onValueChange = { voiceId = it },
                            placeholder = { Text("voice_id (e.g. ryan_voice_v1)", color = TelemetryMuted) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                            colors = studioFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("voice_id_input")
                        )
                        OutlinedButton(
                            onClick = { onSavePersonaIds(headModelId, voiceId) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.channelAccent),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.channelAccent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("save_persona_ids_button")
                        ) {
                            Text("Save persona IDs")
                        }
                        OutlinedTextField(
                            value = trainingUris,
                            onValueChange = { trainingUris = it },
                            placeholder = { Text("gs://bucket/training/clip1.mp4, ...", color = TelemetryMuted) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                            colors = studioFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = { onStartPersonaTraining(trainingUris) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.hairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("start_persona_training_button")
                        ) {
                            Text("Run Phase A cloud training")
                        }
                    }
                }
            }

            // 4. Explicit StageProvider Routing (Cloud Default vs Optional LAN GPU — zero silent fallback)
            item(key = "stage_providers_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Stage provider routing",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        OutlinedTextField(
                            value = lanUrl,
                            onValueChange = { lanUrl = it },
                            placeholder = { Text("Optional LAN GPU URL (http://192.168.x.x:8080)", color = TelemetryMuted) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                            colors = studioFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        ProviderSwitchRow(
                            label = "LAN Voice (Chatterbox/RVC)",
                            checked = providers.useLanVoice,
                            onCheckedChange = {
                                onSaveProviders(providers.copy(lanGatewayUrl = lanUrl, useLanVoice = it))
                            }
                        )
                        ProviderSwitchRow(
                            label = "LAN Alignment (WhisperX)",
                            checked = providers.useLanAlign,
                            onCheckedChange = {
                                onSaveProviders(providers.copy(lanGatewayUrl = lanUrl, useLanAlign = it))
                            }
                        )
                        ProviderSwitchRow(
                            label = "LAN Head Render (Wan 2.2)",
                            checked = providers.useLanHead,
                            onCheckedChange = {
                                onSaveProviders(providers.copy(lanGatewayUrl = lanUrl, useLanHead = it))
                            }
                        )
                        ProviderSwitchRow(
                            label = "LAN Packaging (Remotion)",
                            checked = providers.useLanPackage,
                            onCheckedChange = {
                                onSaveProviders(providers.copy(lanGatewayUrl = lanUrl, useLanPackage = it))
                            }
                        )
                        OutlinedButton(
                            onClick = { onSaveProviders(providers.copy(lanGatewayUrl = lanUrl)) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.hairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("save_providers_button")
                        ) {
                            Text("Save provider routing")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val tokens = LocalAircheckTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = StudioCrisp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = tokens.channelAccentInk,
                checkedTrackColor = tokens.channelAccent
            )
        )
    }
}
