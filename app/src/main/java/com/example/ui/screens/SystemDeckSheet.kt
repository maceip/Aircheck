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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onOpenNewsroomStudio: () -> Unit = {},
    onLoadActivity: (suspend () -> List<String>)? = null,
    onDeviceModels: (@Composable () -> Unit)? = null,
    onLoadStudioOptions: ((refresh: Boolean) -> Unit)? = null,
    onSelectVoiceBackend: ((String) -> Unit)? = null,
    onSelectHeygenLook: ((String) -> Unit)? = null,
    onSelectHeygenEngine: ((String) -> Unit)? = null,
    onSelectHeygenResolution: ((String) -> Unit)? = null,
    onSaveHeygenMotionPrompt: ((String) -> Unit)? = null,
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
            // Optional Savable Newsroom Overlays Page
            item(key = "newsroom_studio_entry") {
                StudioCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenNewsroomStudio)
                        .testTag("open_newsroom_studio_button")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Create Newsroom Overlays (Optional)",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        Text(
                            text = "Savable AI A2UI overlays for news briefs & Remotion setup",
                            style = MaterialTheme.typography.bodySmall,
                            color = TelemetryMuted
                        )
                    }
                }
            }

            // 0. Activity — what's happening on the phone + rig, in one durable log.
            if (onLoadActivity != null) {
                item(key = "activity_card") {
                    ActivityLogCard(loadLogs = onLoadActivity)
                }
            }

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

            // On-device drafting models (AICore system model + downloadable Gemma fallback)
            if (onDeviceModels != null) {
                item(key = "on_device_models_card") { onDeviceModels() }
            }

            // Voice engine & HeyGen avatar/engine/quality controls (live from Mac Studio rig)
            if (onLoadStudioOptions != null &&
                onSelectVoiceBackend != null &&
                onSelectHeygenLook != null &&
                onSelectHeygenEngine != null &&
                onSelectHeygenResolution != null &&
                onSaveHeygenMotionPrompt != null
            ) {
                item(key = "voice_heygen_card") {
                    VoiceAndHeygenCard(
                        uiState = uiState,
                        onLoadOptions = onLoadStudioOptions,
                        onSelectVoiceBackend = onSelectVoiceBackend,
                        onSelectHeygenLook = onSelectHeygenLook,
                        onSelectHeygenEngine = onSelectHeygenEngine,
                        onSelectHeygenResolution = onSelectHeygenResolution,
                        onSaveMotionPrompt = onSaveHeygenMotionPrompt
                    )
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

/**
 * "What's happening" panel: tails the durable generation log (rig + shipped phone events). The log
 * is auto-rotating on both ends, so there is nothing for the user to manage or clean up.
 */
@Composable
private fun ActivityLogCard(loadLogs: suspend () -> List<String>) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey) {
        loading = true
        lines = runCatching { loadLogs() }.getOrDefault(emptyList())
        loading = false
    }
    StudioCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity_log_card")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Activity",
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioCrisp
                )
                Text(
                    text = if (loading) "loading…" else "${lines.size} events",
                    style = TelemetryTimecodeStyle,
                    color = TelemetryMuted,
                    modifier = Modifier.testTag("activity_log_count")
                )
            }
            Text(
                text = "Phone + Mac rig pipeline log. Auto-rotating — nothing to manage.",
                style = MaterialTheme.typography.bodySmall,
                color = TelemetryMuted
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(OledVoid)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
                    .testTag("activity_log_scroll")
            ) {
                if (lines.isEmpty()) {
                    Text(
                        text = if (loading) "Loading…" else "No activity yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TelemetryMuted
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        lines.takeLast(120).forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = StudioCrisp
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { refreshKey++ },
                enabled = !loading,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, LocalAircheckTokens.current.hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("activity_refresh_button")
            ) {
                Text(if (loading) "Refreshing…" else "Refresh")
            }
        }
    }
}

/**
 * Real, rig-backed Voice Engine & HeyGen Talking-Head controls.
 * Fetches available voice engines and live HeyGen avatar looks from `GET /v1/studio/options`
 * and persists selections in `AircheckProfileStore`.
 */
@Composable
private fun VoiceAndHeygenCard(
    uiState: StudioUiState,
    onLoadOptions: (refresh: Boolean) -> Unit,
    onSelectVoiceBackend: (String) -> Unit,
    onSelectHeygenLook: (String) -> Unit,
    onSelectHeygenEngine: (String) -> Unit,
    onSelectHeygenResolution: (String) -> Unit,
    onSaveMotionPrompt: (String) -> Unit
) {
    val tokens = LocalAircheckTokens.current
    val settings = uiState.settings
    val options = uiState.studioRigOptions
    var motionDraft by remember(settings.heygenMotionPrompt) {
        mutableStateOf(settings.heygenMotionPrompt)
    }

    LaunchedEffect(Unit) {
        if (uiState.studioRigOptions == null && !uiState.isLoadingStudioOptions) {
            onLoadOptions(false)
        }
    }

    StudioCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_heygen_card")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Voice & HeyGen video",
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioCrisp
                )
                OutlinedButton(
                    onClick = { onLoadOptions(true) },
                    enabled = !uiState.isLoadingStudioOptions,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, tokens.hairline),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("studio_options_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refresh options",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (uiState.isLoadingStudioOptions) "Loading…" else "Refresh",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            val err = uiState.studioOptionsError
            if (!err.isNullOrBlank()) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnAirCrimson,
                    modifier = Modifier.testTag("studio_options_error")
                )
            }

            if (options == null) {
                Text(
                    text = if (uiState.isLoadingStudioOptions) {
                        "Loading live voice & HeyGen options from Mac Studio…"
                    } else {
                        "Connect to Mac Studio and tap Refresh to load voice engines & HeyGen looks."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TelemetryMuted
                )
            } else {
                // 1. Mac Studio Voice Engine
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mac voice engine", style = MaterialTheme.typography.bodyMedium, color = StudioCrisp)
                    Text(
                        text = options.voiceBackends.firstOrNull { it.id == settings.rigVoiceBackend }?.label
                            ?: settings.rigVoiceBackend,
                        style = TelemetryTimecodeStyle,
                        color = tokens.channelAccent,
                        modifier = Modifier.testTag("voice_backend_active_label")
                    )
                }
                options.voiceBackends.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { opt ->
                            val selected = settings.rigVoiceBackend.equals(opt.id, ignoreCase = true)
                            Surface(
                                color = if (selected) tokens.channelAccent.copy(alpha = 0.16f) else ConsoleElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) tokens.channelAccent else tokens.hairline
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = opt.available && !selected) {
                                        onSelectVoiceBackend(opt.id)
                                    }
                                    .testTag("voice_backend_${opt.id}")
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = opt.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when {
                                            !opt.available -> TelemetryMuted.copy(alpha = 0.5f)
                                            selected -> tokens.channelAccent
                                            else -> StudioCrisp
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 2. HeyGen Avatar Look (live from Ryan's HeyGen account)
                val activeLookId = settings.heygenLookId.ifBlank { options.defaultHeygenLookId }
                val activeLook = options.heygenLooks.firstOrNull { it.id == activeLookId }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("HeyGen look", style = MaterialTheme.typography.bodyMedium, color = StudioCrisp)
                    Text(
                        text = activeLook?.name ?: if (activeLookId.isBlank()) "Default" else activeLookId.take(8),
                        style = TelemetryTimecodeStyle,
                        color = tokens.channelAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 180.dp)
                            .testTag("heygen_look_active_label")
                    )
                }
                if (options.heygenLooks.isEmpty()) {
                    Text(
                        text = "No HeyGen looks returned from your account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TelemetryMuted
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.heygenLooks.forEach { look ->
                            val selected = look.id == activeLookId
                            val typeLabel = if (look.avatarType == "digital_twin") "digital twin" else "photo look"
                            Surface(
                                color = if (selected) tokens.channelAccent.copy(alpha = 0.14f) else ConsoleElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) tokens.channelAccent else tokens.hairline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !selected) { onSelectHeygenLook(look.id) }
                                    .testTag("heygen_look_${look.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (look.previewImageUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = look.previewImageUrl,
                                            contentDescription = look.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(OledVoid)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = look.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (selected) tokens.channelAccent else StudioCrisp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "$typeLabel · ${look.preferredOrientation}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TelemetryMuted,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. HeyGen Engine
                val activeEngine = settings.heygenEngine.ifBlank { options.defaultHeygenEngine }
                val supportedForLook = activeLook?.supportedEngines.orEmpty()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("HeyGen engine", style = MaterialTheme.typography.bodyMedium, color = StudioCrisp)
                    Text(
                        text = options.heygenEngines.firstOrNull { it.id == activeEngine }?.label ?: activeEngine,
                        style = TelemetryTimecodeStyle,
                        color = tokens.channelAccent,
                        modifier = Modifier.testTag("heygen_engine_active_label")
                    )
                }
                options.heygenEngines.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { eng ->
                            val selected = activeEngine.equals(eng.id, ignoreCase = true)
                            val eligible = supportedForLook.isEmpty() || supportedForLook.contains(eng.id)
                            Surface(
                                color = if (selected) tokens.channelAccent.copy(alpha = 0.16f) else ConsoleElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) tokens.channelAccent else tokens.hairline
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = eligible && !selected) { onSelectHeygenEngine(eng.id) }
                                    .testTag("heygen_engine_${eng.id}")
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = eng.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when {
                                            !eligible -> TelemetryMuted.copy(alpha = 0.5f)
                                            selected -> tokens.channelAccent
                                            else -> StudioCrisp
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 4. HeyGen Resolution (filtered to engine's real supported resolutions)
                val engineResolutions = options.heygenEngines
                    .firstOrNull { it.id.equals(activeEngine, ignoreCase = true) }
                    ?.resolutions
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf("1080p")
                val activeRes = settings.heygenResolution.ifBlank { options.defaultHeygenResolution }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Video quality", style = MaterialTheme.typography.bodyMedium, color = StudioCrisp)
                    Text(
                        text = activeRes,
                        style = TelemetryTimecodeStyle,
                        color = tokens.channelAccent,
                        modifier = Modifier.testTag("heygen_resolution_active_label")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    engineResolutions.forEach { res ->
                        val selected = activeRes.equals(res, ignoreCase = true)
                        Surface(
                            color = if (selected) tokens.channelAccent.copy(alpha = 0.16f) else ConsoleElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (selected) tokens.channelAccent else tokens.hairline
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !selected) { onSelectHeygenResolution(res) }
                                .testTag("heygen_res_$res")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = res.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) tokens.channelAccent else StudioCrisp
                                )
                            }
                        }
                    }
                }

                // 5. Optional HeyGen Motion Prompt
                Text("Motion prompt (optional)", style = MaterialTheme.typography.bodyMedium, color = StudioCrisp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = motionDraft,
                        onValueChange = { motionDraft = it },
                        placeholder = {
                            Text("e.g. calm news anchor gestures, eye contact", color = TelemetryMuted)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = StudioCrisp),
                        colors = studioFieldColors(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("heygen_motion_prompt_input")
                    )
                    OutlinedButton(
                        onClick = { onSaveMotionPrompt(motionDraft) },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, tokens.hairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("heygen_motion_prompt_save")
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
