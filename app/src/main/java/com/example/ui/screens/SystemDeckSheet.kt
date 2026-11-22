package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BrandChannel
import com.example.ui.StudioUiState
import com.example.ui.components.AircheckBrandMark
import com.example.ui.components.HardwareKeyButton
import com.example.ui.components.HazardCutOverlay
import com.example.ui.components.LoopingBrandVideoPlayer
import com.example.ui.components.StudioCard
import com.example.ui.components.TelemetryWaveform
import com.example.ui.theme.AcidVoltLime
import com.example.ui.theme.BroadcastCobalt
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.ConsoleObsidian
import com.example.ui.theme.EvidenceAmber
import com.example.ui.theme.GraphiteHairline
import com.example.ui.theme.HardwarePearl
import com.example.ui.theme.HazardSignalOrange
import com.example.ui.theme.LiveTallyGreen
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle

private data class BrandAssetItem(val title: String, val resId: Int)
private data class ColorSwatchItem(val name: String, val hex: String, val color: Color)

@Composable
fun SystemDeckSheet(
    uiState: StudioUiState,
    onSelectChannel: (BrandChannel) -> Unit,
    onSynthesizeAudioTest: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val activeChannel = uiState.settings.brandChannel
    var activeLoopRes by remember { mutableStateOf(R.raw.brand_loop_torus) }
    var activeTestLed by remember { mutableStateOf(0) }
    var shimmerTestActive by remember { mutableStateOf(false) }

    val brandAssets = remember(activeChannel) {
        if (activeChannel == BrandChannel.VOLT_LIME) {
            listOf(
                BrandAssetItem("Triangle Puck", R.drawable.aircheck_puck_icon),
                BrandAssetItem("Truss Globe", R.drawable.stage_truss_globe_lime),
                BrandAssetItem("Neon Globe", R.drawable.brand_globe_lime),
                BrandAssetItem("Chrome Mic", R.drawable.brand_mic_ribbon_lime),
                BrandAssetItem("Signal Arc", R.drawable.brand_signal_lime),
                BrandAssetItem("Triangle Rig", R.drawable.stage_triangle_rig_lime)
            )
        } else {
            listOf(
                BrandAssetItem("Headphones", R.drawable.brand_headphones_cobalt),
                BrandAssetItem("Truss Globe", R.drawable.stage_truss_globe_cobalt),
                BrandAssetItem("Neon Globe", R.drawable.brand_globe_cobalt),
                BrandAssetItem("Signal Arc", R.drawable.brand_signal_cobalt),
                BrandAssetItem("Triangle Rig", R.drawable.stage_triangle_rig_cobalt),
                BrandAssetItem("Triangle Puck", R.drawable.aircheck_puck_icon)
            )
        }
    }

    val swatches = remember {
        listOf(
            ColorSwatchItem("OLED Void", "#000000", OledVoid),
            ColorSwatchItem("Obsidian", "#0A0C10", ConsoleObsidian),
            ColorSwatchItem("Hairline", "#232730", GraphiteHairline),
            ColorSwatchItem("Alloy Pearl", "#F2F5F9", HardwarePearl),
            ColorSwatchItem("Volt Lime", "#B8FF00", AcidVoltLime),
            ColorSwatchItem("Broadcast Cobalt", "#0088FF", BroadcastCobalt),
            ColorSwatchItem("Telemetry Cyan", "#18D4F5", TelemetryCyan),
            ColorSwatchItem("Hazard Cut", "#FF451A", HazardSignalOrange),
            ColorSwatchItem("Evidence Amber", "#F5B700", EvidenceAmber),
            ColorSwatchItem("Live Green", "#00E676", LiveTallyGreen),
            ColorSwatchItem("On-Air Red", "#FF1744", OnAirCrimson)
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Uniform 52.dp Sheet Top Bar
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
                text = "System Deck",
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
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Live Broadcast Channel Switcher (Persisted via DataStore)
            item(key = "channel_switcher") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Broadcast channel accent",
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
                                        Column {
                                            Text(
                                                text = channel.label,
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (selected) color else StudioCrisp
                                            )
                                            Text(
                                                text = channel.accentHex,
                                                style = TelemetryTimecodeStyle,
                                                color = TelemetryMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Live 3D Conglomerate Motion Vault (Bundled MP4 loops)
            item(key = "motion_vault") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Motion vault",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "Torus" to R.raw.brand_loop_torus,
                                    "Clock" to R.raw.brand_loop_clock
                                ).forEach { (label, resId) ->
                                    val chosen = activeLoopRes == resId
                                    Surface(
                                        color = if (chosen) tokens.channelAccentContainer else ConsoleElevated,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            if (chosen) tokens.channelAccent else tokens.hairline
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { activeLoopRes = resId }
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (chosen) tokens.channelAccent else TelemetryMuted,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(OledVoid)
                                .border(1.dp, tokens.hairline, RoundedCornerShape(6.dp))
                        ) {
                            LoopingBrandVideoPlayer(
                                rawResId = activeLoopRes,
                                contentDescription = "Aircheck conglomerate motion loop",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            // 3. Brand Asset Gallery (Bundled Volt & Cobalt Marks)
            item(key = "brand_gallery") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Brand gallery",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(brandAssets, key = { it.title }) { asset ->
                                Column(
                                    modifier = Modifier
                                        .width(116.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(OledVoid)
                                        .border(1.dp, tokens.hairline, RoundedCornerShape(6.dp))
                                ) {
                                    Image(
                                        painter = painterResource(asset.resId),
                                        contentDescription = asset.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(82.dp)
                                    )
                                    Text(
                                        text = asset.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = StudioCrisp,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Tactile Hardware Key Playground + LED Tally
            item(key = "hardware_playground") {
                StudioCard(
                    shimmering = shimmerTestActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Hardware keys & shimmer",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            HardwareKeyButton(
                                icon = Icons.Filled.PlayArrow,
                                contentDescription = "Live tally key",
                                onClick = { activeTestLed = 0 },
                                active = activeTestLed == 0,
                                ledColor = LiveTallyGreen,
                                label = "Live"
                            )
                            HardwareKeyButton(
                                icon = Icons.Filled.FiberManualRecord,
                                contentDescription = "Record tally key",
                                onClick = { activeTestLed = 1 },
                                active = activeTestLed == 1,
                                iconTint = OnAirCrimson,
                                ledColor = OnAirCrimson,
                                label = "Rec"
                            )
                            HardwareKeyButton(
                                icon = Icons.Filled.Mic,
                                contentDescription = "Standby key",
                                onClick = { activeTestLed = 2 },
                                active = activeTestLed == 2,
                                ledColor = BroadcastCobalt,
                                label = "Mic"
                            )
                            HardwareKeyButton(
                                icon = if (shimmerTestActive) Icons.Filled.Stop else Icons.Filled.AutoAwesome,
                                contentDescription = "Toggle card shimmer",
                                onClick = { shimmerTestActive = !shimmerTestActive },
                                active = shimmerTestActive,
                                ledColor = tokens.channelAccent,
                                label = "Shimmer",
                                modifier = Modifier.testTag("system_deck_shimmer_toggle")
                            )
                        }
                    }
                }
            }

            // 5. Color Tokens Grid
            item(key = "color_tokens") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Color tokens",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(swatches, key = { it.name }) { item ->
                                Column(
                                    modifier = Modifier
                                        .width(92.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(ConsoleElevated)
                                        .border(1.dp, tokens.hairline, RoundedCornerShape(6.dp))
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(item.color)
                                            .border(1.dp, tokens.hairline, RoundedCornerShape(4.dp))
                                    )
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = StudioCrisp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = item.hex,
                                        style = TelemetryTimecodeStyle,
                                        color = TelemetryMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 6. Typography Specimen (Space Grotesk + JetBrains Mono) + Hazard Cut Specimen
            item(key = "type_and_hazard") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Typography & beat cut",
                            style = MaterialTheme.typography.titleSmall,
                            color = StudioCrisp
                        )
                        Text(
                            text = "Space Grotesk Bold · Broadcast Headline",
                            style = MaterialTheme.typography.headlineSmall,
                            color = StudioCrisp
                        )
                        Text(
                            text = "Natural sentence-case prose stays effortless to scan while speaking on camera.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TelemetryMuted
                        )
                        Text(
                            text = "00:30 · 24 FPS · 16-BIT PCM · 118 HZ",
                            style = TelemetryTimecodeStyle,
                            color = tokens.channelAccent
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(OledVoid)
                                .border(1.dp, tokens.hazardCut.copy(alpha = 0.6f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            HazardCutOverlay(modifier = Modifier.matchParentSize())
                            Text(
                                text = "Cut beat preview",
                                style = MaterialTheme.typography.labelLarge,
                                color = tokens.hazardCut,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
            }

            // 7. Live Real-WAV Telemetry Waveform Inspector
            item(key = "live_waveform") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live audio telemetry",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            uiState.lastMeasuredPitchHz?.let { hz ->
                                Text(
                                    text = "${hz.toInt()} Hz",
                                    style = TelemetryTimecodeStyle,
                                    color = tokens.channelAccent
                                )
                            }
                        }

                        TelemetryWaveform(
                            peaks = uiState.waveformPeaks,
                            progress = uiState.playbackProgress,
                            isRecording = uiState.isRecordingMic,
                            height = 56.dp
                        )

                        OutlinedButton(
                            onClick = onSynthesizeAudioTest,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.channelAccent),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.channelAccent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.waveformPeaks.isEmpty()) {
                                    "Synthesize script WAV to inspect"
                                } else {
                                    "Replay decoded WAV track"
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
