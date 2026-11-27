package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.sharp.ChevronRight
import androidx.compose.material.icons.sharp.Layers
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.BrandChannel
import com.example.ui.RigConnectionStatus
import com.example.ui.StudioUiState
import com.example.ui.clipstudio.Cs
import com.example.ui.clipstudio.CsIconKey
import com.example.ui.clipstudio.CsKey
import com.example.ui.clipstudio.CsKeyLabel
import com.example.ui.clipstudio.CsLedDot
import com.example.ui.clipstudio.CsToastOverlay
import com.example.ui.clipstudio.KeyRole
import com.example.ui.clipstudio.SectionLabelStyle
import com.example.ui.clipstudio.mono
import com.example.ui.clipstudio.ndot
import com.example.ui.clipstudio.neumorphicSurface
import com.example.ui.clipstudio.sans
import com.example.ui.clipstudio.sunkenPanel
import com.example.ui.components.AircheckBrandMark
import com.example.ui.theme.AcidVoltLime
import com.example.ui.theme.BroadcastCobalt

/**
 * Tactile Studio Settings sheet aligned to the Clip Studio hardware design system:
 * sculpted neumorphic keys, sunken matte black panels, glowing LED status dots, live
 * HeyGen look & voice/video provider controls with immediate feedback, and zero flat Material cards.
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
    onSelectVideoBackend: ((String) -> Unit)? = null,
    onSelectHeygenLook: ((String) -> Unit)? = null,
    onSelectHeygenEngine: ((String) -> Unit)? = null,
    onSelectHeygenResolution: ((String) -> Unit)? = null,
    onSaveHeygenMotionPrompt: ((String) -> Unit)? = null,
    onShowToast: ((String) -> Unit)? = null,
    onDismissToast: () -> Unit = {},
    modifier: Modifier = Modifier
) {
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Cs.Body)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AircheckBrandMark(size = 24.dp)
                Text(
                    text = "STUDIO SETTINGS",
                    style = ndot(15.sp, Cs.White),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                CsIconKey(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close_sheet),
                    onClick = onClose,
                    size = 42.dp,
                    radius = 12.dp,
                    iconSize = 20.dp,
                    modifier = Modifier.testTag("close_system_deck_button")
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .testTag("system_deck_lazy_list"),
                contentPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Direct jump to revamped Newsroom Overlays Editor
                item(key = "newsroom_studio_entry") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .neumorphicSurface(
                                role = KeyRole.STD,
                                pressed = false,
                                radius = 16.dp,
                                enabled = true,
                                accentBarColor = Cs.Red
                            )
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button, onClick = onOpenNewsroomStudio)
                            .padding(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 14.dp)
                            .testTag("open_newsroom_studio_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Sharp.Layers,
                            contentDescription = null,
                            tint = Cs.Ink,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Newsroom Overlays Editor",
                                style = sans(15.sp, Cs.White, 20.sp, FontWeight.Medium)
                            )
                            Text(
                                text = "Edit broadcast labels, chyrons, cards & live studio look",
                                style = sans(12.5.sp, Cs.Muted, 17.sp)
                            )
                        }
                        Icon(
                            imageVector = Icons.Sharp.ChevronRight,
                            contentDescription = null,
                            tint = Cs.Muted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
                            onSelectVideoBackend = onSelectVideoBackend ?: {},
                            onSelectHeygenLook = onSelectHeygenLook,
                            onSelectHeygenEngine = onSelectHeygenEngine,
                            onSelectHeygenResolution = onSelectHeygenResolution,
                            onSaveMotionPrompt = onSaveHeygenMotionPrompt,
                            onUnavailableClick = { msg -> onShowToast?.invoke(msg) }
                        )
                    }
                }

                // On-device drafting models (AICore system model + downloadable Gemma fallback)
                if (onDeviceModels != null) {
                    item(key = "on_device_models_card") { onDeviceModels() }
                }

                // Studio Server Connection
                item(key = "studio_server_card") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sunkenPanel(radius = 18.dp)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("MAC STUDIO SERVER", style = SectionLabelStyle)
                            Text(
                                text = rigStatusLabel,
                                style = mono(
                                    11.5.sp,
                                    when (rigHealth.status) {
                                        RigConnectionStatus.ONLINE -> Cs.Mint
                                        RigConnectionStatus.CHECKING -> Cs.Amber
                                        RigConnectionStatus.ERROR -> Cs.Red
                                        RigConnectionStatus.IDLE -> Cs.Muted
                                    },
                                    FontWeight.Medium
                                ),
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
                            BasicTextField(
                                value = rigUrlDraft,
                                onValueChange = { rigUrlDraft = it },
                                singleLine = true,
                                textStyle = mono(13.sp, Cs.White),
                                cursorBrush = SolidColor(Cs.White),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Cs.Body)
                                    .border(1.dp, Cs.Hairline, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 13.dp)
                                    .testTag("tailscale_rig_url_input"),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (rigUrlDraft.isEmpty()) {
                                            Text("http://10.0.0.242:8443", style = mono(13.sp, Cs.Dim))
                                        }
                                        inner()
                                    }
                                }
                            )
                            CsKeyLabel(
                                label = "Save",
                                onClick = { onSaveTailscaleRigUrl(rigUrlDraft) },
                                role = KeyRole.ACTIVE,
                                radius = 12.dp,
                                size = 12.5.sp,
                                weight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .height(46.dp)
                                    .testTag("save_tailscale_url_button")
                            )
                        }

                        CsKey(
                            onClick = { onTestRigConnection(rigUrlDraft) },
                            enabled = rigHealth.status != RigConnectionStatus.CHECKING,
                            role = KeyRole.STD,
                            radius = 12.dp,
                            contentAlignment = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("test_rig_connection_button")
                        ) { tint ->
                            Icon(
                                imageVector = Icons.Filled.NetworkCheck,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(17.dp)
                            )
                            Text(
                                text = if (rigHealth.status == RigConnectionStatus.CHECKING) {
                                    "Checking connection…"
                                } else {
                                    "Test connection"
                                },
                                style = mono(12.5.sp, tint, FontWeight.Medium)
                            )
                        }

                        val rigError = rigHealth.errorMessage
                        if (rigHealth.status == RigConnectionStatus.ERROR && !rigError.isNullOrBlank()) {
                            Text(
                                text = rigError,
                                style = sans(12.5.sp, Cs.Red, 17.sp),
                                modifier = Modifier.testTag("rig_connection_error_label")
                            )
                        }
                    }
                }

                // Activity log
                if (onLoadActivity != null) {
                    item(key = "activity_card") {
                        ActivityLogCard(loadLogs = onLoadActivity)
                    }
                }

                // Broadcast Channel Accent
                item(key = "channel_switcher") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sunkenPanel(radius = 18.dp)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("BROADCAST ACCENT", style = SectionLabelStyle)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            BrandChannel.entries.forEach { channel ->
                                val selected = activeChannel == channel
                                val color = if (channel == BrandChannel.VOLT_LIME) AcidVoltLime else BroadcastCobalt
                                CsKey(
                                    onClick = { onSelectChannel(channel) },
                                    role = if (selected) KeyRole.ACTIVE else KeyRole.STD,
                                    radius = 12.dp,
                                    horizontalPadding = 12.dp,
                                    contentAlignment = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .testTag("channel_toggle_${channel.name.lowercase()}")
                                ) { tint ->
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(color)
                                    )
                                    Text(
                                        text = channel.label,
                                        style = mono(12.sp, if (selected) Cs.White else tint, FontWeight.Medium)
                                    )
                                }
                            }
                        }
                    }
                }

                // Storage & Reset
                item(key = "storage_card") {
                    val mbUsed = uiState.casTotalBytes / (1024.0 * 1024.0)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sunkenPanel(radius = 18.dp)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("LOCAL VIDEO CACHE", style = SectionLabelStyle)
                            Text(
                                text = "${"%.1f".format(mbUsed)} MB",
                                style = mono(12.sp, Cs.InkSub, FontWeight.Medium),
                                modifier = Modifier.testTag("cas_cache_size_label")
                            )
                        }
                        CsKey(
                            onClick = onClearCasCache,
                            role = KeyRole.STD,
                            radius = 12.dp,
                            contentAlignment = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("clear_cas_cache_button")
                        ) { tint ->
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(17.dp)
                            )
                            Text("Clear cached files", style = mono(12.5.sp, tint, FontWeight.Medium))
                        }
                    }
                }
            }
        }

        CsToastOverlay(
            message = uiState.toastMessage,
            onDismiss = onDismissToast
        )
    }
}

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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 18.dp)
            .padding(14.dp)
            .testTag("activity_log_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ACTIVITY LOG", style = SectionLabelStyle)
            Text(
                text = if (loading) "loading…" else "${lines.size} events",
                style = mono(11.sp, Cs.Muted),
                modifier = Modifier.testTag("activity_log_count")
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Cs.Body)
                .border(1.dp, Cs.Hairline, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
                .testTag("activity_log_scroll")
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = if (loading) "Loading…" else "No activity yet.",
                    style = sans(12.5.sp, Cs.Muted, 17.sp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lines.takeLast(120).forEach { line ->
                        Text(
                            text = line,
                            style = mono(10.sp, Cs.InkSub)
                        )
                    }
                }
            }
        }
        CsKeyLabel(
            label = if (loading) "Refreshing…" else "Refresh",
            onClick = { refreshKey++ },
            enabled = !loading,
            role = KeyRole.STD,
            radius = 12.dp,
            size = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .testTag("activity_refresh_button")
        )
    }
}

/**
 * Tactile rig-backed Voice & HeyGen Video controls.
 * Every selectable button uses sculpted neumorphic keys with glowing LED status dots,
 * and unavailable backends explain why they are unavailable on tap instead of feeling dead.
 */
@Composable
private fun VoiceAndHeygenCard(
    uiState: StudioUiState,
    onLoadOptions: (refresh: Boolean) -> Unit,
    onSelectVoiceBackend: (String) -> Unit,
    onSelectVideoBackend: (String) -> Unit,
    onSelectHeygenLook: (String) -> Unit,
    onSelectHeygenEngine: (String) -> Unit,
    onSelectHeygenResolution: (String) -> Unit,
    onSaveMotionPrompt: (String) -> Unit,
    onUnavailableClick: (String) -> Unit
) {
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 18.dp)
            .padding(14.dp)
            .testTag("voice_heygen_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("VOICE & AVATAR VIDEO", style = SectionLabelStyle)
            CsKey(
                onClick = { onLoadOptions(true) },
                enabled = !uiState.isLoadingStudioOptions,
                role = KeyRole.STD,
                radius = 10.dp,
                horizontalPadding = 10.dp,
                contentAlignment = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("studio_options_refresh_button")
            ) { tint ->
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh options",
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (uiState.isLoadingStudioOptions) "Loading…" else "Refresh",
                    style = mono(11.sp, tint, FontWeight.Medium)
                )
            }
        }

        val err = uiState.studioOptionsError
        if (!err.isNullOrBlank()) {
            Text(
                text = err,
                style = sans(12.5.sp, Cs.Red, 17.sp),
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
                style = sans(13.sp, Cs.Muted, 18.sp)
            )
        } else {
            // 1. Mac Studio Voice Engine
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mac voice engine", style = sans(14.sp, Cs.Ink, 19.sp, FontWeight.Medium))
                Text(
                    text = options.voiceBackends.firstOrNull { it.id == settings.rigVoiceBackend }?.label
                        ?: settings.rigVoiceBackend,
                    style = mono(11.5.sp, Cs.Mint, FontWeight.Medium),
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
                        TactileOptionTile(
                            label = opt.label,
                            selected = selected,
                            available = opt.available,
                            onClick = {
                                if (!opt.available) {
                                    onUnavailableClick("${opt.label} unavailable: ${opt.detail.ifBlank { "not configured on rig" }}")
                                } else {
                                    onSelectVoiceBackend(opt.id)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("voice_backend_${opt.id}")
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // 2. Video Provider (HeyGen Avatar / Mac Studio MLX Avatars / MuseTalk)
            val activeVideoBackend = settings.rigVideoBackend.ifBlank { options.defaultVideoBackend }
            val activeVideoOption = options.videoBackends.firstOrNull {
                it.id.equals(activeVideoBackend, ignoreCase = true)
            }
            if (options.videoBackends.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Video provider", style = sans(14.sp, Cs.Ink, 19.sp, FontWeight.Medium))
                    Text(
                        text = activeVideoOption?.label ?: activeVideoBackend,
                        style = mono(11.5.sp, Cs.Mint, FontWeight.Medium),
                        modifier = Modifier.testTag("video_backend_active_label")
                    )
                }
                options.videoBackends.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { vOpt ->
                            val selected = activeVideoBackend.equals(vOpt.id, ignoreCase = true)
                            TactileOptionTile(
                                label = vOpt.label,
                                selected = selected,
                                available = vOpt.available,
                                onClick = {
                                    if (!vOpt.available) {
                                        onUnavailableClick("${vOpt.label} unavailable: ${vOpt.detail.ifBlank { "not configured on rig" }}")
                                    } else {
                                        onSelectVideoBackend(vOpt.id)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("video_backend_${vOpt.id}")
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (!activeVideoBackend.equals("heygen", ignoreCase = true)) {
                    val detail = activeVideoOption?.detail.orEmpty()
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = mono(11.sp, Cs.InkSub),
                            modifier = Modifier.testTag("video_backend_active_detail")
                        )
                    }
                    if (activeVideoBackend.equals("longcat_avatar_mlx", ignoreCase = true)) {
                        Text("Scene prompt (optional)", style = sans(14.sp, Cs.Ink, 19.sp, FontWeight.Medium))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = motionDraft,
                                onValueChange = { motionDraft = it },
                                singleLine = true,
                                textStyle = sans(13.5.sp, Cs.White, 18.sp),
                                cursorBrush = SolidColor(Cs.White),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Cs.Body)
                                    .border(1.dp, Cs.Hairline, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 13.dp)
                                    .testTag("longcat_scene_prompt_input"),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (motionDraft.isEmpty()) {
                                            Text("e.g. presenter in warm studio lighting", style = sans(13.sp, Cs.Dim, 18.sp))
                                        }
                                        inner()
                                    }
                                }
                            )
                            CsKeyLabel(
                                label = "Save",
                                onClick = { onSaveMotionPrompt(motionDraft) },
                                role = KeyRole.ACTIVE,
                                radius = 12.dp,
                                size = 12.5.sp,
                                weight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .height(46.dp)
                                    .testTag("longcat_scene_prompt_save")
                            )
                        }
                    }
                }
            }

            // 3. HeyGen Avatar Look, Engine, Quality, Motion Prompt
            if (activeVideoBackend.equals("heygen", ignoreCase = true)) {
                val activeLookId = settings.heygenLookId.ifBlank { options.defaultHeygenLookId }
                val activeLook = options.heygenLooks.firstOrNull { it.id == activeLookId }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("HeyGen look", style = sans(14.sp, Cs.Ink, 19.sp, FontWeight.Medium))
                    Text(
                        text = activeLook?.name ?: if (activeLookId.isBlank()) "Default" else activeLookId.take(8),
                        style = mono(11.5.sp, Cs.Mint, FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 190.dp)
                            .testTag("heygen_look_active_label")
                    )
                }
                if (options.heygenLooks.isEmpty()) {
                    Text(
                        text = "No HeyGen looks returned from your account.",
                        style = sans(12.5.sp, Cs.Muted, 17.sp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.heygenLooks.forEach { look ->
                            val selected = look.id == activeLookId
                            val typeLabel = if (look.avatarType == "digital_twin") "digital twin" else "photo look"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .neumorphicSurface(
                                        role = if (selected) KeyRole.ACTIVE else KeyRole.STD,
                                        pressed = false,
                                        radius = 14.dp,
                                        enabled = true,
                                        accentBarColor = if (selected) Cs.Mint else null
                                    )
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(role = Role.RadioButton) { onSelectHeygenLook(look.id) }
                                    .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp)
                                    .testTag("heygen_look_${look.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (look.previewImageUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = look.previewImageUrl,
                                        contentDescription = look.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Cs.Body)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = look.name,
                                        style = sans(
                                            14.sp,
                                            if (selected) Cs.White else Cs.Ink,
                                            19.sp,
                                            if (selected) FontWeight.SemiBold else FontWeight.Medium
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$typeLabel · ${look.preferredOrientation}",
                                        style = mono(11.sp, if (selected) Cs.InkSub else Cs.Muted),
                                        maxLines = 1
                                    )
                                }
                                if (selected) {
                                    CsLedDot(color = Cs.Mint, size = 7.dp)
                                }
                            }
                        }
                    }
                }

                // 4. HeyGen Engine
                val activeEngine = settings.heygenEngine.ifBlank { options.defaultHeygenEngine }
                val supportedForLook = activeLook?.supportedEngines.orEmpty()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("HeyGen engine", style = sans(14.sp, Cs.Ink, 19.sp, FontWeight.Medium))
                    Text(
                        text = options.heygenEngines.firstOrNull { it.id == activeEngine }?.label ?: activeEngine,
                        style = mono(11.5.sp, Cs.Mint, FontWeight.Medium),
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
                            TactileOptionTile(
                                label = eng.label,
                                selected = selected,
                                available = eligible,
                                onClick = {
                                    if (!eligible) {
                                        onUnavailableClick("${eng.label} is not supported by the selected look")
                                    } else {
                                        onSelectHeygenEngine(eng.id)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("heygen_engine_${eng.id}")
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 5. HeyGen Resolution
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
                    Text("Video quality", style = sans(14.sp, Cs.Ink, 19.sp, FontWeight.Medium))
                    Text(
                        text = activeRes.uppercase(),
                        style = mono(11.5.sp, Cs.Mint, FontWeight.Medium),
                        modifier = Modifier.testTag("heygen_resolution_active_label")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    engineResolutions.forEach { res ->
                        val selected = activeRes.equals(res, ignoreCase = true)
                        TactileOptionTile(
                            label = res.uppercase(),
                            selected = selected,
                            available = true,
                            onClick = { onSelectHeygenResolution(res) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("heygen_res_$res")
                        )
                    }
                }

                // 6. Optional HeyGen Motion Prompt
                Text("Motion prompt (optional)", style = sans(14.sp, Cs.Ink, 19.sp, FontWeight.Medium))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = motionDraft,
                        onValueChange = { motionDraft = it },
                        singleLine = true,
                        textStyle = sans(13.5.sp, Cs.White, 18.sp),
                        cursorBrush = SolidColor(Cs.White),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Cs.Body)
                            .border(1.dp, Cs.Hairline, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 13.dp)
                            .testTag("heygen_motion_prompt_input"),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (motionDraft.isEmpty()) {
                                    Text("e.g. calm news anchor gestures", style = sans(13.sp, Cs.Dim, 18.sp))
                                }
                                inner()
                            }
                        }
                    )
                    CsKeyLabel(
                        label = "Save",
                        onClick = { onSaveMotionPrompt(motionDraft) },
                        role = KeyRole.ACTIVE,
                        radius = 12.dp,
                        size = 12.5.sp,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier
                            .height(46.dp)
                            .testTag("heygen_motion_prompt_save")
                    )
                }
            }
        }
    }
}

@Composable
private fun TactileOptionTile(
    label: String,
    selected: Boolean,
    available: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .neumorphicSurface(
                role = if (selected) KeyRole.ACTIVE else KeyRole.STD,
                pressed = false,
                radius = 12.dp,
                enabled = available,
                accentBarColor = if (selected) Cs.Mint else null
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            CsLedDot(color = Cs.Mint, size = 6.dp)
        }
        Text(
            text = label,
            style = mono(
                11.5.sp,
                when {
                    !available -> Cs.Dim
                    selected -> Cs.White
                    else -> Cs.Ink
                },
                if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
