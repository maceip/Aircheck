package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ui.StudioUiState
import com.example.ui.components.StudioCard
import com.example.ui.theme.LiveTallyGreen
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle
import java.util.Locale

/**
 * Separate dedicated settings page exclusively for on-phone Voice Model Care, Storage Location,
 * local LoRA / model upgrades, enrollment voice samples, and on-phone DSP/ONNX quality checks.
 *
 * Strictly enforces Phone-Only Isolation (`phoneOnlyVoiceMode = true` by default) so voice weights
 * and samples stay exclusively on the phone with zero Mac sync, even when home on Wi-Fi.
 */
@Composable
fun VoiceModelCareScreen(
    uiState: StudioUiState,
    onTogglePhoneOnlyMode: (Boolean) -> Unit,
    onVerifyFiles: () -> Unit,
    onStartRecordSample: () -> Unit,
    onStopRecordSample: () -> Unit,
    onDeleteSample: (String) -> Unit,
    onImportModelUri: (Uri) -> Unit,
    onRunQualityCheck: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val tokens = LocalAircheckTokens.current
    val phoneOnly = uiState.settings.phoneOnlyVoiceMode
    val snapshot = uiState.voiceStorageSnapshot
    val qualityReport = uiState.voiceQualityReport

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartRecordSample()
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportModelUri(uri)
        }
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
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("voice_care_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = StudioCrisp
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Voice Model Care",
                style = MaterialTheme.typography.titleMedium,
                color = StudioCrisp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (phoneOnly) "Phone-Only" else "LAN Unlocked",
                style = TelemetryTimecodeStyle,
                color = if (phoneOnly) LiveTallyGreen else TelemetryCyan
            )
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
                .testTag("voice_care_lazy_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Phone-Only Isolation Lock
            item(key = "phone_only_lock_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Keep Voice Model Only on Phone",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            Text(
                                text = "Blocks Mac sync even when home on Wi-Fi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TelemetryMuted
                            )
                        }
                        Switch(
                            checked = phoneOnly,
                            onCheckedChange = onTogglePhoneOnlyMode,
                            modifier = Modifier.testTag("voice_care_phone_only_switch")
                        )
                    }
                }
            }

            // 2. Exact On-Phone Storage Location & File Presence Verification
            item(key = "storage_location_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "On-Phone Location & Files",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            val countText = if (snapshot != null) {
                                "${snapshot.presentArtifactCount}/${snapshot.totalExpectedArtifacts} present"
                            } else {
                                "Scanning…"
                            }
                            Text(
                                text = countText,
                                style = TelemetryTimecodeStyle,
                                color = if (snapshot != null && snapshot.presentArtifactCount == snapshot.totalExpectedArtifacts) {
                                    LiveTallyGreen
                                } else {
                                    TelemetryMuted
                                }
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Bundles path",
                                style = MaterialTheme.typography.labelSmall,
                                color = TelemetryMuted
                            )
                            Text(
                                text = snapshot?.bundlesDirPath ?: "Loading…",
                                style = TelemetryTimecodeStyle,
                                color = StudioCrisp,
                                modifier = Modifier.testTag("voice_care_bundles_path_text")
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Voice care & LoRA path",
                                style = MaterialTheme.typography.labelSmall,
                                color = TelemetryMuted
                            )
                            Text(
                                text = snapshot?.voiceCareDirPath ?: "Loading…",
                                style = TelemetryTimecodeStyle,
                                color = StudioCrisp,
                                modifier = Modifier.testTag("voice_care_root_path_text")
                            )
                        }

                        snapshot?.artifacts?.forEach { art ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = art.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioCrisp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val statusLabel = when {
                                    !art.exists -> "Missing"
                                    art.sha256Verified == true -> "${formatBytes(art.sizeBytes)} · SHA OK"
                                    art.sha256Verified == false -> "SHA Mismatch"
                                    else -> formatBytes(art.sizeBytes)
                                }
                                val statusColor = when {
                                    !art.exists -> TelemetryMuted
                                    art.sha256Verified == false -> OnAirCrimson
                                    else -> LiveTallyGreen
                                }
                                Text(
                                    text = statusLabel,
                                    style = TelemetryTimecodeStyle,
                                    color = statusColor
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onVerifyFiles,
                            enabled = !uiState.isVerifyingVoiceFiles,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.hairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("verify_on_phone_files_button")
                        ) {
                            Text(
                                text = if (uiState.isVerifyingVoiceFiles) {
                                    "Verifying SHA-256 on phone…"
                                } else {
                                    "Verify On-Phone Files"
                                }
                            )
                        }
                    }
                }
            }

            // 3. Add Voice Samples & Local LoRA / Model Upgrades
            item(key = "add_lora_and_samples_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Add Samples & Local LoRA Upgrades",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            Text(
                                text = "Record 16 kHz WAV on phone or import .gguf / .onnx from device files.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TelemetryMuted
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (uiState.isRecordingVoiceCareSample) {
                                        onStopRecordSample()
                                    } else {
                                        val hasPerm = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (hasPerm) {
                                            onStartRecordSample()
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (uiState.isRecordingVoiceCareSample) OnAirCrimson else tokens.hairline
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (uiState.isRecordingVoiceCareSample) OnAirCrimson else StudioCrisp
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("record_voice_care_sample_button")
                            ) {
                                Text(
                                    text = if (uiState.isRecordingVoiceCareSample) {
                                        "Stop Sample"
                                    } else {
                                        "Record Sample"
                                    }
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    openDocumentLauncher.launch(arrayOf("*/*"))
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, tokens.hairline),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("import_local_lora_button")
                            ) {
                                Text("Import LoRA / Model")
                            }
                        }

                        snapshot?.importedFiles?.forEach { lora ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lora.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioCrisp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SHA ${lora.sha256Hex.take(8)}",
                                    style = TelemetryTimecodeStyle,
                                    color = LiveTallyGreen
                                )
                            }
                        }

                        snapshot?.samples?.forEach { sample ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sample.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioCrisp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = String.format(Locale.US, "%.1fs", sample.durationSec),
                                    style = TelemetryTimecodeStyle,
                                    color = TelemetryCyan
                                )
                                IconButton(
                                    onClick = { onDeleteSample(sample.fileName) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = "Delete sample",
                                        tint = TelemetryMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. On-Phone Quality Check
            item(key = "quality_check_card") {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "On-Phone Quality Check",
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            Text(
                                text = "Checks real dBFS headroom, clipping, 80 ms tail & local ONNX gates.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TelemetryMuted
                            )
                        }

                        OutlinedButton(
                            onClick = onRunQualityCheck,
                            enabled = !uiState.isCheckingVoiceQuality,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, tokens.hairline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("run_voice_quality_check_button")
                        ) {
                            Text(
                                text = if (uiState.isCheckingVoiceQuality) {
                                    "Analyzing on phone…"
                                } else {
                                    "Run On-Phone Quality Check"
                                }
                            )
                        }

                        if (qualityReport != null) {
                            Text(
                                text = qualityReport.summaryMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (qualityReport.hasAudio) StudioCrisp else TelemetryMuted,
                                modifier = Modifier.testTag("voice_quality_summary_text")
                            )
                            if (qualityReport.hasAudio && qualityReport.rmsDbfs != null && qualityReport.peakDbfs != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "RMS / Peak",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TelemetryMuted
                                    )
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "%.1f / %.1f dBFS",
                                            qualityReport.rmsDbfs,
                                            qualityReport.peakDbfs
                                        ),
                                        style = TelemetryTimecodeStyle,
                                        color = LiveTallyGreen
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

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
    val kb = bytes / 1024.0
    if (kb >= 1.0) return String.format(Locale.US, "%.1f KB", kb)
    return "$bytes B"
}
