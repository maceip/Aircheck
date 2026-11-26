package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.engine.ModelDownloadManager.DownloadState
import com.example.engine.ModelDownloadManager.Phase
import com.example.engine.formatModelBytes
import com.example.ui.components.StudioCard
import com.example.ui.theme.LiveTallyGreen
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle

/** What AICore reports for the system drafting model. [checked] is false until the probe returns. */
data class AicoreModelStatus(val checked: Boolean, val baseModelName: String?)

/**
 * On-device drafting models: the AICore system model (primary) and the downloadable Gemma 4 E2B
 * fallback. Every value shown is read from AICore or from the files on disk; every button acts on
 * the real download job.
 */
@Composable
fun OnDeviceModelsCard(
    aicore: AicoreModelStatus,
    download: DownloadState,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    draftOnPhone: Boolean,
    onDraftOnPhoneChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAircheckTokens.current
    // Phone drafting needs a model that can actually run here: the AICore model or verified Gemma.
    val phoneCanDraft = aicore.baseModelName != null || download.phase == Phase.READY
    StudioCard(modifier = modifier.fillMaxWidth().testTag("on_device_models_card")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("On-device drafting models", style = MaterialTheme.typography.titleSmall, color = StudioCrisp)

            // Where Spark/Script drafting runs. Persisted in settings; read by the LLM stage router.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Write scripts on", style = MaterialTheme.typography.bodyMedium, color = StudioCrisp)
                Text(
                    text = if (draftOnPhone) "This phone" else "Mac Studio",
                    style = TelemetryTimecodeStyle,
                    color = tokens.channelAccent,
                    modifier = Modifier.testTag("draft_location_label")
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ModelButton(
                    label = "This phone",
                    tag = "draft_on_phone_button",
                    onClick = { onDraftOnPhoneChange(true) },
                    modifier = Modifier.weight(1f),
                    enabled = phoneCanDraft && !draftOnPhone,
                    selected = draftOnPhone
                )
                ModelButton(
                    label = "Mac Studio",
                    tag = "draft_on_mac_button",
                    onClick = { onDraftOnPhoneChange(false) },
                    modifier = Modifier.weight(1f),
                    enabled = draftOnPhone,
                    selected = !draftOnPhone
                )
            }
            if (!phoneCanDraft && aicore.checked) {
                Text(
                    text = "No on-device model can run yet — download the Gemma fallback to draft on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TelemetryMuted,
                    modifier = Modifier.testTag("draft_location_hint")
                )
            }

            // Primary: AICore system model.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("System model (AICore)", style = MaterialTheme.typography.bodyMedium, color = StudioCrisp)
                Text(
                    text = when {
                        !aicore.checked -> "Checking…"
                        aicore.baseModelName != null -> aicore.baseModelName
                        else -> "Not available"
                    },
                    style = TelemetryTimecodeStyle,
                    color = if (aicore.baseModelName != null) LiveTallyGreen else TelemetryMuted,
                    modifier = Modifier.testTag("aicore_model_label")
                )
            }

            // Fallback: downloadable Gemma weights.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Gemma 4 E2B fallback", style = MaterialTheme.typography.bodyMedium, color = StudioCrisp)
                Text(
                    text = when (download.phase) {
                        Phase.NOT_DOWNLOADED -> "Not downloaded"
                        Phase.DOWNLOADING -> "Downloading"
                        Phase.PAUSED -> "Paused"
                        Phase.VERIFYING -> "Verifying"
                        Phase.READY -> "Ready"
                        Phase.FAILED -> "Failed"
                    },
                    style = TelemetryTimecodeStyle,
                    color = when (download.phase) {
                        Phase.READY -> LiveTallyGreen
                        Phase.FAILED -> OnAirCrimson
                        Phase.DOWNLOADING, Phase.VERIFYING -> tokens.channelAccent
                        else -> TelemetryMuted
                    },
                    modifier = Modifier.testTag("gemma_phase_label")
                )
            }
            Text(
                text = "Used when the system model is unavailable or a drafting pass fails. " +
                    "Downloads resume where they left off.",
                style = MaterialTheme.typography.bodySmall,
                color = TelemetryMuted
            )

            if (download.totalBytes > 0L && download.phase != Phase.READY) {
                val fraction = (download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(OledVoid)
                        .testTag("gemma_progress_bar")
                        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(tokens.channelAccent)
                    )
                }
            }
            if (download.downloadedBytes > 0L || download.totalBytes > 0L) {
                Text(
                    text = when {
                        download.phase == Phase.READY -> formatModelBytes(download.downloadedBytes)
                        download.totalBytes > 0L ->
                            "${formatModelBytes(download.downloadedBytes)} / ${formatModelBytes(download.totalBytes)}" +
                                " · ${(download.downloadedBytes * 100 / download.totalBytes)}%"
                        else -> formatModelBytes(download.downloadedBytes)
                    },
                    style = TelemetryTimecodeStyle,
                    color = TelemetryMuted,
                    modifier = Modifier.testTag("gemma_bytes_label")
                )
            }
            download.error?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (download.phase == Phase.FAILED) OnAirCrimson else TelemetryMuted,
                    modifier = Modifier.testTag("gemma_error_label")
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                when (download.phase) {
                    Phase.NOT_DOWNLOADED -> ModelButton("Download", "gemma_download_button", onDownload, Modifier.weight(1f))
                    Phase.DOWNLOADING -> ModelButton("Pause", "gemma_pause_button", onPause, Modifier.weight(1f))
                    Phase.VERIFYING -> Unit
                    Phase.PAUSED, Phase.FAILED -> {
                        ModelButton(
                            if (download.downloadedBytes > 0L) "Resume" else "Retry",
                            "gemma_download_button",
                            onDownload,
                            Modifier.weight(1f)
                        )
                        if (download.downloadedBytes > 0L) {
                            ModelButton("Delete", "gemma_delete_button", onDelete, Modifier.weight(1f))
                        }
                    }
                    Phase.READY -> ModelButton("Delete download", "gemma_delete_button", onDelete, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ModelButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val tokens = LocalAircheckTokens.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) tokens.channelAccent else tokens.hairline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = StudioCrisp,
            disabledContentColor = if (selected) tokens.channelAccent else TelemetryMuted
        ),
        modifier = modifier.height(46.dp).testTag(tag)
    ) {
        Text(label)
    }
}
