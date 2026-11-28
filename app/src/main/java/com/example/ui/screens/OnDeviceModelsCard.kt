package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.ModelDownloadManager.DownloadState
import com.example.engine.ModelDownloadManager.Phase
import com.example.engine.formatModelBytes
import com.example.ui.design.AcCard
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType

/** What AICore reports for the system drafting model. [checked] is false until the probe returns. */
data class AicoreModelStatus(val checked: Boolean, val baseModelName: String?)

/**
 * On-device drafting models styled in the Clip Studio tactile hardware design system:
 * the AICore system model (primary) and the downloadable Gemma 4 E2B fallback. Every value shown
 * is read from AICore or from files on disk; every button acts on the real download job.
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
    phoneVoiceInstalled: Boolean = false,
    voiceOnPhone: Boolean = false,
    onVoiceOnPhoneChange: (Boolean) -> Unit = {},
) {
    // Phone drafting needs a model that can actually run here: the AICore model or verified Gemma.
    val phoneCanDraft = aicore.baseModelName != null || download.phase == Phase.READY
    AcCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("on_device_models_card"),
        tag = "On-device drafting models",
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        // Where Spark drafting runs. Persisted in settings; read by the LLM stage router.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Scripts & overlays on", style = AcType.BodySmall.copy(color = AcTokens.Ink))
            Text(
                text = if (draftOnPhone) "This phone" else "Mac Studio",
                style = AcType.Label,
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
                style = AcType.BodySmall,
                modifier = Modifier.testTag("draft_location_hint")
            )
        }

        if (phoneVoiceInstalled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Voice scripts on", style = AcType.BodySmall.copy(color = AcTokens.Ink))
                Text(
                    text = if (voiceOnPhone) "This phone" else "Mac Studio",
                    style = AcType.Label,
                    modifier = Modifier.testTag("voice_location_label")
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ModelButton(
                    label = "This phone",
                    tag = "voice_on_phone_button",
                    onClick = { onVoiceOnPhoneChange(true) },
                    modifier = Modifier.weight(1f),
                    enabled = !voiceOnPhone,
                    selected = voiceOnPhone
                )
                ModelButton(
                    label = "Mac Studio",
                    tag = "voice_on_mac_button",
                    onClick = { onVoiceOnPhoneChange(false) },
                    modifier = Modifier.weight(1f),
                    enabled = voiceOnPhone,
                    selected = !voiceOnPhone
                )
            }
        }

        // Primary: AICore system model.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("System model (AICore)", style = AcType.BodySmall)
            Text(
                text = when {
                    !aicore.checked -> "Checking…"
                    aicore.baseModelName != null -> aicore.baseModelName
                    else -> "Not available"
                },
                style = AcType.Label.copy(color = if (aicore.baseModelName != null) AcTokens.Ink else AcTokens.Ink2),
                modifier = Modifier.testTag("aicore_model_label")
            )
        }

        // Fallback: downloadable Gemma weights.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Gemma 4 E2B fallback", style = AcType.BodySmall)
            Text(
                text = when (download.phase) {
                    Phase.NOT_DOWNLOADED -> "Not downloaded"
                    Phase.DOWNLOADING -> "Downloading"
                    Phase.PAUSED -> "Paused"
                    Phase.VERIFYING -> "Verifying"
                    Phase.READY -> "Ready"
                    Phase.FAILED -> "Failed"
                },
                style = AcType.Label.copy(
                    color = when (download.phase) {
                        Phase.READY -> AcTokens.Ink
                        Phase.FAILED -> AcTokens.Red
                        else -> AcTokens.Ink2
                    }
                ),
                modifier = Modifier.testTag("gemma_phase_label")
            )
        }
        Text(
            text = "Used when the system model is unavailable or a drafting pass fails. Downloads resume where they left off.",
            style = AcType.BodySmall
        )

        if (download.totalBytes > 0L && download.phase != Phase.READY) {
            val fraction = (download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f)
            AcDisplay(
                radius = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .testTag("gemma_progress_bar")
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 5.dp)
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(AcTokens.OpGreen)
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
                style = AcType.Caption,
                modifier = Modifier.testTag("gemma_bytes_label")
            )
        }
        download.error?.let { err ->
            Text(
                text = err,
                style = AcType.BodySmall.copy(color = if (download.phase == Phase.FAILED) AcTokens.Red else AcTokens.Ink2),
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

@Composable
private fun ModelButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    AcKey(
        label = label,
        onClick = onClick,
        enabled = enabled,
        selected = selected,
        size = AcKeySize.Small,
        modifier = modifier.testTag(tag)
    )
}
