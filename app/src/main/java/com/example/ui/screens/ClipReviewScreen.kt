package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.ClipRecord
import com.example.data.ExportState
import com.example.data.RenderJobStatus
import com.example.data.local.CasMediaStore
import com.example.engine.AircheckPlayerController
import com.example.ui.components.HardwareKeyButton
import com.example.ui.components.StudioErrorBanner
import com.example.ui.components.TelemetryWaveform
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle
import kotlinx.coroutines.delay

/**
 * Unobstructed 9:16 Review player bound to the single app-scoped `AircheckPlayerController`
 * (Media3 SimpleCache progressive streaming, zero ExoPlayer allocation inside Compose),
 * plus optional live Compose caption/chyron preview from `align_{sha8}.json` and 0.1 ms Room waveform.
 */
@Composable
fun ClipReviewScreen(
    clip: ClipRecord?,
    exportState: ExportState,
    errorMessage: String?,
    casStore: CasMediaStore,
    playerController: AircheckPlayerController,
    onBack: () -> Unit,
    onExport: (ClipRecord, Boolean) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val isPlaying by playerController.isPlaying.collectAsStateWithLifecycle()
    val durationMs by playerController.durationMs.collectAsStateWithLifecycle()

    var positionMs by remember { mutableLongStateOf(0L) }
    var showLiveCaptionOverlay by remember { mutableStateOf(false) }

    val playbackUri: Uri? = remember(clip?.finalSha, clip?.remoteFinalUrl, clip?.lipsyncSha, clip?.remoteLipsyncUrl) {
        val localFinal = casStore.fileForSha(clip?.finalSha, "mp4")
        val localLipsync = casStore.fileForSha(clip?.lipsyncSha, "mp4")
        val remoteFinalUrl = clip?.remoteFinalUrl
        val remoteLipsyncUrl = clip?.remoteLipsyncUrl
        when {
            localFinal != null -> Uri.fromFile(localFinal)
            !remoteFinalUrl.isNullOrBlank() -> Uri.parse(remoteFinalUrl)
            localLipsync != null -> Uri.fromFile(localLipsync)
            !remoteLipsyncUrl.isNullOrBlank() -> Uri.parse(remoteLipsyncUrl)
            else -> null
        }
    }

    LaunchedEffect(playbackUri) {
        if (playbackUri != null) {
            playerController.prepareUri(playbackUri, autoPlay = false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerController.pause()
        }
    }

    LaunchedEffect(isPlaying, playbackUri) {
        while (isPlaying && playbackUri != null) {
            positionMs = playerController.currentPositionMs()
            delay(40L)
        }
    }

    val progressFraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val activeCaptionWord = remember(clip?.alignedWords, positionMs) {
        clip?.alignedWords?.firstOrNull { positionMs in it.startMs..it.endMs }?.word
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Compact Review Back + Title Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("review_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Clips",
                        tint = StudioCrisp
                    )
                }
                Text(
                    text = clip?.title?.ifBlank { "Aircheck clip" } ?: "Clip",
                    style = MaterialTheme.typography.titleSmall,
                    color = StudioCrisp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (clip != null && clip.alignedWords.isNotEmpty()) {
                    IconButton(
                        onClick = { showLiveCaptionOverlay = !showLiveCaptionOverlay },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("toggle_live_caption_overlay")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ClosedCaption,
                            contentDescription = "Toggle caption preview",
                            tint = if (showLiveCaptionOverlay) tokens.channelAccent else TelemetryMuted
                        )
                    }
                }
            }

            val clipError = clip?.errorMessage
            if (!errorMessage.isNullOrBlank()) {
                StudioErrorBanner(message = errorMessage, onDismiss = onDismissError)
            } else if (!clipError.isNullOrBlank()) {
                StudioErrorBanner(message = clipError, onDismiss = onDismissError)
            }

            // Full-height unobstructed OLED Video Stage bound to singleton AircheckPlayerController
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF040507))
                    .border(1.dp, tokens.hairline, RoundedCornerShape(8.dp))
                    .clickable(enabled = playbackUri != null) {
                        playerController.togglePlayPause()
                    }
                    .testTag("review_video_stage"),
                contentAlignment = Alignment.Center
            ) {
                when {
                    clip == null -> {
                        Text(
                            text = "Clip not found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TelemetryMuted
                        )
                    }

                    playbackUri != null -> {
                        val player = remember { playerController.acquirePlayer() }
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    this.player = player
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                }
                            },
                            update = { view ->
                                if (view.player !== player) view.player = player
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (!isPlaying) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.62f),
                                shape = RoundedCornerShape(28.dp),
                                border = BorderStroke(1.dp, tokens.channelAccent.copy(alpha = 0.7f))
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.cd_play_pause),
                                    tint = tokens.channelAccent,
                                    modifier = Modifier
                                        .padding(14.dp)
                                        .size(32.dp)
                                )
                            }
                        }

                        // Optional live Compose caption/chyron preview from align_{sha8}.json
                        if (showLiveCaptionOverlay && (!activeCaptionWord.isNullOrBlank() || clip.chyronHeadline.isNotBlank())) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!activeCaptionWord.isNullOrBlank()) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.78f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = activeCaptionWord,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = tokens.channelAccent,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                                if (clip.chyronHeadline.isNotBlank()) {
                                    Surface(
                                        color = ConsoleElevated.copy(alpha = 0.92f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, tokens.hairline),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = clip.chyronHeadline,
                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                                color = StudioCrisp,
                                                maxLines = 1
                                            )
                                            if (clip.chyronSubline.isNotBlank()) {
                                                Text(
                                                    text = clip.chyronSubline,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = TelemetryMuted,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = clip.stageDetail.ifBlank { clip.status.label },
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            if (clip.status.isActive) {
                                LinearProgressIndicator(
                                    progress = { clip.progressFraction.coerceIn(0.05f, 1f) },
                                    color = tokens.channelAccent,
                                    trackColor = tokens.hairline,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                                Text(
                                    text = "${(clip.progressFraction * 100).toInt()}%",
                                    style = TelemetryTimecodeStyle,
                                    color = tokens.channelAccent
                                )
                            } else if (clip.status == RenderJobStatus.FAILED) {
                                Text(
                                    text = clip.errorMessage ?: "Cloud render job failed.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.statusRec
                                )
                            }
                        }
                    }
                }
            }

            // Pre-computed 64-bin Room waveform blob (0.1 ms Canvas draw, zero file I/O)
            TelemetryWaveform(
                peaks = clip?.waveformPeaks.orEmpty(),
                progress = progressFraction,
                height = 48.dp,
                modifier = Modifier.testTag("review_waveform")
            )

            // Single clean row of tactile hardware transport keys
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HardwareKeyButton(
                    icon = Icons.Filled.FastRewind,
                    contentDescription = stringResource(R.string.cd_rewind),
                    onClick = {
                        playerController.seekTo(0L)
                        positionMs = 0L
                    },
                    enabled = playbackUri != null,
                    label = "Rewind",
                    modifier = Modifier.testTag("transport_rewind_button")
                )

                HardwareKeyButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play_pause),
                    onClick = { playerController.togglePlayPause() },
                    enabled = playbackUri != null,
                    active = isPlaying,
                    ledColor = if (isPlaying) tokens.statusLive else tokens.statusStandby,
                    label = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.testTag("transport_play_button")
                )

                val exportLabel = when (exportState) {
                    ExportState.IDLE -> if (clip?.exportedUri != null) "Exported ✓" else "Export"
                    ExportState.EXPORTING -> "Exporting…"
                    ExportState.EXPORTED -> "Exported ✓"
                }
                val isExported = exportState == ExportState.EXPORTED || clip?.exportedUri != null
                HardwareKeyButton(
                    icon = if (isExported) Icons.Filled.Check else Icons.Filled.FileDownload,
                    contentDescription = stringResource(R.string.cd_export_video),
                    onClick = { if (clip != null) onExport(clip, false) },
                    enabled = clip != null && playbackUri != null && exportState != ExportState.EXPORTING,
                    active = isExported,
                    ledColor = when {
                        isExported -> tokens.statusLive
                        exportState == ExportState.EXPORTING -> tokens.evidenceAmber
                        else -> null
                    },
                    label = exportLabel,
                    modifier = Modifier.testTag("transport_export_button")
                )

                HardwareKeyButton(
                    icon = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.cd_share_video),
                    onClick = { if (clip != null) onExport(clip, true) },
                    enabled = clip != null && playbackUri != null && exportState != ExportState.EXPORTING,
                    label = "Share",
                    modifier = Modifier.testTag("review_share_button")
                )
            }
        }
    }
}
