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
import com.example.ui.clipstudio.SectionCaption
import com.example.ui.clipstudio.StudioStatusLine
import com.example.ui.clipstudio.StudioToast
import com.example.ui.design.AcCard
import com.example.ui.design.AcField
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import androidx.compose.foundation.shape.CircleShape

/**
 * Tactile Studio Settings sheet aligned to the Clip Studio hardware design system:
 * design v2 keys and cards grouped as Video / On this phone / Mac Studio / Overlays / Storage, live
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
            .background(AcTokens.Chassis)
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
                Text(
                    text = "Studio settings",
                    style = AcType.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AcKey(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close_sheet),
                    onClick = onClose,
                    size = AcKeySize.Small,
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
                // Voice engine & HeyGen avatar/engine/quality controls (live from Mac Studio rig)
                if (onLoadStudioOptions != null &&
                    onSelectVoiceBackend != null &&
                    onSelectHeygenLook != null &&
                    onSelectHeygenEngine != null &&
                    onSelectHeygenResolution != null &&
                    onSaveHeygenMotionPrompt != null
                ) {
                    item(key = "voice_heygen_card") {
                        SettingsGroupCaption("Video")
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
                    item(key = "on_device_models_card") {
                        SettingsGroupCaption("On this phone")
                        onDeviceModels()
                    }
                }

                // Studio Server Connection
                item(key = "studio_server_card") {
                    SettingsGroupCaption("Mac Studio")
                    AcCard(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("MAC STUDIO SERVER", style = AcType.Caption)
                            Text(
                                text = rigStatusLabel,
                                style = AcType.Caption.copy(color = when (rigHealth.status) {
                                        RigConnectionStatus.ONLINE -> AcTokens.Red
                                        RigConnectionStatus.CHECKING -> AcTokens.Red
                                        RigConnectionStatus.ERROR -> AcTokens.Red
                                        RigConnectionStatus.IDLE -> AcTokens.Ink2
                                    }),
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
                            AcField(
                                value = rigUrlDraft,
                                onValueChange = { rigUrlDraft = it },
                                singleLine = true,
                                placeholder = "http://10.0.0.242:8443",
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("tailscale_rig_url_input")
                            )
                            AcKey(
                                label = "Save",
                                onClick = { onSaveTailscaleRigUrl(rigUrlDraft) },
                                size = AcKeySize.Small,
                                modifier = Modifier
                                    .testTag("save_tailscale_url_button")
                            )
                        }

                        AcKey(
                            onClick = { onTestRigConnection(rigUrlDraft) },
                            enabled = rigHealth.status != RigConnectionStatus.CHECKING,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            size = AcKeySize.Small,
                            modifier = Modifier
                                .fillMaxWidth()
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
                                style = AcType.Label.copy(color = tint)
                            )
                        }

                        val rigError = rigHealth.errorMessage
                        if (rigHealth.status == RigConnectionStatus.ERROR && !rigError.isNullOrBlank()) {
                            Text(
                                text = rigError,
                                style = AcType.BodySmall.copy(color = AcTokens.Red),
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

                item(key = "newsroom_studio_entry") {
                    SettingsGroupCaption("Overlays")
                    AcCard(
                        onClick = onOpenNewsroomStudio,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_newsroom_studio_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Sharp.Layers, contentDescription = null, tint = AcTokens.Ink, modifier = Modifier.size(20.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Newsroom Overlays Editor", style = AcType.Body)
                                Text("Edit broadcast labels, chyrons, cards & live studio look", style = AcType.BodySmall)
                            }
                            Icon(Icons.Sharp.ChevronRight, contentDescription = null, tint = AcTokens.Ink2, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Storage & Reset
                item(key = "storage_card") {
                    SettingsGroupCaption("Storage")
                    val mbUsed = uiState.casTotalBytes / (1024.0 * 1024.0)
                    AcCard(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("LOCAL VIDEO CACHE", style = AcType.Caption)
                            Text(
                                text = "${"%.1f".format(mbUsed)} MB",
                                style = AcType.Label.copy(color = AcTokens.Ink2),
                                modifier = Modifier.testTag("cas_cache_size_label")
                            )
                        }
                        AcKey(
                            onClick = onClearCasCache,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            size = AcKeySize.Small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("clear_cas_cache_button")
                        ) { tint ->
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(17.dp)
                            )
                            Text("Clear cached files", style = AcType.Label.copy(color = tint))
                        }
                    }
                }
            }
        }

        StudioToast(
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
    AcCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity_log_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ACTIVITY LOG", style = AcType.Caption)
            Text(
                text = if (loading) "loading…" else "${lines.size} events",
                style = AcType.Caption.copy(color = AcTokens.Ink2),
                modifier = Modifier.testTag("activity_log_count")
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AcTokens.Chassis)
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
                .testTag("activity_log_scroll")
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = if (loading) "Loading…" else "No activity yet.",
                    style = AcType.BodySmall.copy(color = AcTokens.Ink2)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lines.takeLast(120).forEach { line ->
                        Text(
                            text = line,
                            style = AcType.Caption.copy(color = AcTokens.Ink2)
                        )
                    }
                }
            }
        }
        AcKey(
            label = if (loading) "Refreshing…" else "Refresh",
            onClick = { refreshKey++ },
            enabled = !loading,
            size = AcKeySize.Small,
            modifier = Modifier
                .fillMaxWidth()
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

    AcCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_heygen_card"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("VOICE & AVATAR VIDEO", style = AcType.Caption)
            AcKey(
                onClick = { onLoadOptions(true) },
                enabled = !uiState.isLoadingStudioOptions,
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                size = AcKeySize.Small,
                modifier = Modifier
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
                    style = AcType.Caption.copy(color = tint)
                )
            }
        }

        val err = uiState.studioOptionsError
        if (!err.isNullOrBlank()) {
            Text(
                text = err,
                style = AcType.BodySmall.copy(color = AcTokens.Red),
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
                style = AcType.BodySmall.copy(color = AcTokens.Ink2)
            )
        } else {
            // 1. Mac Studio Voice Engine
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mac voice engine", style = AcType.BodySmall.copy(color = AcTokens.Ink))
                Text(
                    text = options.voiceBackends.firstOrNull { it.id == settings.rigVoiceBackend }?.label
                        ?: settings.rigVoiceBackend,
                    style = AcType.Caption.copy(color = AcTokens.Red),
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
                    Text("Video provider", style = AcType.BodySmall.copy(color = AcTokens.Ink))
                    Text(
                        text = activeVideoOption?.label ?: activeVideoBackend,
                        style = AcType.Caption.copy(color = AcTokens.Red),
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
                            style = AcType.Caption.copy(color = AcTokens.Ink2),
                            modifier = Modifier.testTag("video_backend_active_detail")
                        )
                    }
                    if (activeVideoBackend.equals("longcat_avatar_mlx", ignoreCase = true)) {
                        Text("Scene prompt (optional)", style = AcType.BodySmall.copy(color = AcTokens.Ink))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AcField(
                                value = motionDraft,
                                onValueChange = { motionDraft = it },
                                singleLine = true,
                                placeholder = "e.g. presenter in warm studio lighting",
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("longcat_scene_prompt_input")
                            )
                            AcKey(
                                label = "Save",
                                onClick = { onSaveMotionPrompt(motionDraft) },
                                size = AcKeySize.Small,
                                modifier = Modifier
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
                    Text("HeyGen look", style = AcType.BodySmall.copy(color = AcTokens.Ink))
                    Text(
                        text = activeLook?.name ?: if (activeLookId.isBlank()) "Default" else activeLookId.take(8),
                        style = AcType.Caption.copy(color = AcTokens.Red),
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
                        style = AcType.BodySmall.copy(color = AcTokens.Ink2)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.heygenLooks.forEach { look ->
                            val selected = look.id == activeLookId
                            val typeLabel = if (look.avatarType == "digital_twin") "digital twin" else "photo look"
                            AcKey(
                                onClick = { onSelectHeygenLook(look.id) },
                                selected = selected,
                                role = Role.RadioButton,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = 6.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("heygen_look_${look.id}")
                            ) { fg ->
                                if (look.previewImageUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = look.previewImageUrl,
                                        contentDescription = look.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(look.name, style = AcType.Label.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = "$typeLabel · ${look.preferredOrientation}".uppercase(),
                                        style = AcType.Caption.copy(color = fg),
                                        maxLines = 1
                                    )
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
                    Text("HeyGen engine", style = AcType.BodySmall.copy(color = AcTokens.Ink))
                    Text(
                        text = options.heygenEngines.firstOrNull { it.id == activeEngine }?.label ?: activeEngine,
                        style = AcType.Caption.copy(color = AcTokens.Red),
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
                    Text("Video quality", style = AcType.BodySmall.copy(color = AcTokens.Ink))
                    Text(
                        text = activeRes.uppercase(),
                        style = AcType.Caption.copy(color = AcTokens.Red),
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
                Text("Motion prompt (optional)", style = AcType.BodySmall.copy(color = AcTokens.Ink))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AcField(
                        value = motionDraft,
                        onValueChange = { motionDraft = it },
                        singleLine = true,
                        placeholder = "e.g. calm news anchor gestures",
                        modifier = Modifier
                            .weight(1f)
                            .testTag("heygen_motion_prompt_input")
                    )
                    AcKey(
                        label = "Save",
                        onClick = { onSaveMotionPrompt(motionDraft) },
                        size = AcKeySize.Small,
                        modifier = Modifier
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
    // Unavailable options stay tappable: the tap explains why the backend can't be used.
    AcKey(
        onClick = onClick,
        selected = selected,
        role = Role.RadioButton,
        size = AcKeySize.Small,
        modifier = modifier
    ) { fg ->
        Text(
            text = label,
            style = AcType.Label.copy(color = if (!available && !selected) AcTokens.Ink2.copy(alpha = 0.6f) else fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Settings group heading. */
@Composable
private fun SettingsGroupCaption(text: String) {
    SectionCaption(text, modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp))
}
