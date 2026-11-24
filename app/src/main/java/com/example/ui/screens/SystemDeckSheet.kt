package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BrandChannel
import com.example.ui.RigConnectionStatus
import com.example.ui.StudioUiState
import com.example.ui.components.AircheckBrandMark
import com.example.ui.components.StudioCard
import com.example.ui.components.studioFieldColors
import com.example.ui.theme.AcidVoltLime
import com.example.ui.theme.BroadcastCobalt
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LiveTallyGreen
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle

/**
 * Clean Studio Settings sheet: Mac Studio connection, channel accent color, and local cache.
 * Zero developer jargon, zero mandatory 3 GB OTA walls.
 */
@Composable
fun SystemDeckSheet(
    uiState: StudioUiState,
    onSelectChannel: (BrandChannel) -> Unit,
    onClearCasCache: () -> Unit,
    onClose: () -> Unit,
    onSaveTailscaleRigUrl: (String) -> Unit,
    onTestRigConnection: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val activeChannel = uiState.settings.brandChannel
    val rigHealth = uiState.rigHealth

    var rigUrlDraft by remember(uiState.settings.tailscaleRigUrl) {
        mutableStateOf(uiState.settings.tailscaleRigUrl)
    }

    val rigStatusLabel = when (rigHealth.status) {
        RigConnectionStatus.IDLE -> "Not tested"
        RigConnectionStatus.CHECKING -> "Checking…"
        RigConnectionStatus.ONLINE -> "Connected"
        RigConnectionStatus.ERROR -> "Offline"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid)
            .windowInsetsPadding(WindowInsets.systemBars),
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
                text = "Studio Settings",
                style = MaterialTheme.typography.titleMedium,
                color = StudioCrisp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("close_system_deck_button")
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
                .testTag("system_deck_lazy_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Studio Server Connection
            item(key = "studio_server_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mac Studio server",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            Text(
                                text = rigStatusLabel,
                                style = TelemetryTimecodeStyle,
                                color = when (rigHealth.status) {
                                    RigConnectionStatus.ONLINE -> LiveTallyGreen
                                    RigConnectionStatus.CHECKING -> TelemetryCyan
                                    RigConnectionStatus.ERROR -> OnAirCrimson
                                    RigConnectionStatus.IDLE -> TelemetryMuted
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("rig_connection_status_label")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = rigUrlDraft,
                                onValueChange = { rigUrlDraft = it },
                                placeholder = {
                                    Text("http://10.0.0.242:8443", color = TelemetryMuted)
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                                colors = studioFieldColors(),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("tailscale_rig_url_input")
                            )
                            OutlinedButton(
                                onClick = { onSaveTailscaleRigUrl(rigUrlDraft) },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, tokens.hairline),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                                modifier = Modifier
                                    .height(52.dp)
                                    .testTag("save_tailscale_url_button")
                            ) {
                                Text("Save")
                            }
                        }

                        OutlinedButton(
                            onClick = { onTestRigConnection(rigUrlDraft) },
                            enabled = rigHealth.status != RigConnectionStatus.CHECKING,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.hairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("test_rig_connection_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.NetworkCheck,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (rigHealth.status == RigConnectionStatus.CHECKING) {
                                    "Checking connection…"
                                } else {
                                    "Test connection"
                                }
                            )
                        }

                        val rigError = rigHealth.errorMessage
                        if (rigHealth.status == RigConnectionStatus.ERROR && !rigError.isNullOrBlank()) {
                            Text(
                                text = rigError,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnAirCrimson,
                                modifier = Modifier.testTag("rig_connection_error_label")
                            )
                        }
                    }
                }
            }

            // 2. Broadcast Channel Accent
            item(key = "channel_switcher") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Broadcast accent",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BrandChannel.entries.forEach { channel ->
                                val selected = activeChannel == channel
                                val color = if (channel == BrandChannel.VOLT_LIME) AcidVoltLime else BroadcastCobalt
                                Surface(
                                    color = if (selected) color.copy(alpha = 0.16f) else ConsoleElevated,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (selected) color else tokens.hairline),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSelectChannel(channel) }
                                        .testTag("channel_toggle_${channel.name.lowercase()}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(color)
                                        )
                                        Text(
                                            text = channel.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (selected) color else StudioCrisp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Storage & Reset
            item(key = "storage_card") {
                val mbUsed = uiState.casTotalBytes / (1024.0 * 1024.0)
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Local video cache",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            Text(
                                text = "${"%.1f".format(mbUsed)} MB",
                                style = TelemetryTimecodeStyle,
                                color = tokens.channelAccent,
                                modifier = Modifier.testTag("cas_cache_size_label")
                            )
                        }
                        OutlinedButton(
                            onClick = onClearCasCache,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.hairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("clear_cas_cache_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Clear cached files")
                        }
                    }
                }
            }
        }
    }
}
