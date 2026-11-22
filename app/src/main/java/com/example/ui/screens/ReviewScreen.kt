package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.data.ExportState
import com.example.data.local.MediaStorageManager
import com.example.ui.StudioUiState
import com.example.ui.components.HardwareKeyButton
import com.example.ui.components.LoopingBrandVideoPlayer
import com.example.ui.components.StudioErrorBanner
import com.example.ui.components.StudioPrimaryCta
import com.example.ui.components.TelemetryWaveform
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle
import kotlinx.coroutines.delay

@Composable
fun ReviewScreen(
    uiState: StudioUiState,
    mediaStorage: MediaStorageManager,
    onRenderVideo: () -> Unit,
    onToggleMicRecord: () -> Unit,
    onPreviewAudio: () -> Unit,
    onExportVideo: (Boolean) -> Unit,
    onSwitchProject: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    val context = LocalContext.current
    val project = uiState.activeProject
    val videoFile = remember(project.renderedVideoKey) {
        mediaStorage.fileForKey(project.renderedVideoKey)
    }

    var videoPlaying by remember { mutableStateOf(false) }
    var videoProgress by remember { mutableStateOf(0f) }

    val exoPlayer = remember(videoFile?.absolutePath) {
        if (videoFile == null) {
            null
        } else {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
                repeatMode = Player.REPEAT_MODE_ONE
                prepare()
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                videoPlaying = isPlaying
            }
        }
        exoPlayer?.addListener(listener)
        onDispose {
            exoPlayer?.removeListener(listener)
            runCatching { exoPlayer?.release() }
        }
    }

    LaunchedEffect(exoPlayer, videoPlaying) {
        while (exoPlayer != null && videoPlaying) {
            val dur = exoPlayer.duration.coerceAtLeast(1L)
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            videoProgress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            delay(50L)
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onToggleMicRecord()
    }

    fun requestOrToggleMic() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) onToggleMicRecord() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
            if (!uiState.errorMessage.isNullOrBlank()) {
                StudioErrorBanner(message = uiState.errorMessage, onDismiss = onDismissError)
            }

            // Full-height unobstructed OLED Video Stage
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF040507))
                    .border(1.dp, tokens.hairline, RoundedCornerShape(8.dp))
                    .clickable(enabled = exoPlayer != null) {
                        exoPlayer?.let { p ->
                            if (p.isPlaying) p.pause() else p.play()
                        }
                    }
                    .testTag("review_video_stage"),
                contentAlignment = Alignment.Center
            ) {
                when {
                    uiState.renderProgress.active -> {
                        LoopingBrandVideoPlayer(
                            rawResId = R.raw.brand_loop_clock,
                            contentDescription = "Rendering Aircheck broadcast",
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f))
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = uiState.renderProgress.statusLabel.ifBlank { "Rendering…" },
                                style = MaterialTheme.typography.titleSmall,
                                color = StudioCrisp
                            )
                            LinearProgressIndicator(
                                progress = { uiState.renderProgress.fraction.coerceIn(0f, 1f) },
                                color = tokens.channelAccent,
                                trackColor = tokens.hairline,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                            Text(
                                text = "${(uiState.renderProgress.fraction * 100).toInt()}%",
                                style = TelemetryTimecodeStyle,
                                color = tokens.channelAccent
                            )
                        }
                    }

                    exoPlayer != null -> {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                }
                            },
                            update = { it.player = exoPlayer },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (!videoPlaying) {
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
                    }

                    else -> {
                        LoopingBrandVideoPlayer(
                            rawResId = R.raw.brand_loop_torus,
                            contentDescription = "Aircheck broadcast idle signal",
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.58f))
                        )
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Ready to render",
                                style = MaterialTheme.typography.titleMedium,
                                color = StudioCrisp
                            )
                            Text(
                                text = "Generate your synchronized talking-head broadcast from your script and stage.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TelemetryMuted
                            )
                        }
                    }
                }
            }

            // Genuine RIFF WAV PCM Waveform Bar
            TelemetryWaveform(
                peaks = uiState.waveformPeaks,
                progress = if (exoPlayer != null) videoProgress else uiState.playbackProgress,
                isRecording = uiState.isRecordingMic,
                height = 50.dp,
                modifier = Modifier.testTag("review_waveform")
            )

            // Single clean row of tactile silver hardware transport keys
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                HardwareKeyButton(
                    icon = Icons.Filled.FastRewind,
                    contentDescription = stringResource(R.string.cd_rewind),
                    onClick = {
                        exoPlayer?.seekTo(0)
                        videoProgress = 0f
                    },
                    enabled = exoPlayer != null,
                    label = "Rewind",
                    modifier = Modifier.testTag("transport_rewind_button")
                )

                val isActivePlay = if (exoPlayer != null) videoPlaying else uiState.isPlayingAudio
                HardwareKeyButton(
                    icon = if (isActivePlay) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play_pause),
                    onClick = {
                        if (exoPlayer != null) {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        } else {
                            onPreviewAudio()
                        }
                    },
                    active = isActivePlay,
                    ledColor = if (isActivePlay) tokens.statusLive else tokens.statusStandby,
                    label = if (isActivePlay) "Pause" else "Play",
                    modifier = Modifier.testTag("transport_play_button")
                )

                HardwareKeyButton(
                    icon = if (uiState.isRecordingMic) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                    contentDescription = stringResource(R.string.cd_record_mic),
                    onClick = { requestOrToggleMic() },
                    active = uiState.isRecordingMic,
                    iconTint = if (uiState.isRecordingMic) tokens.statusRec else tokens.statusRec,
                    ledColor = if (uiState.isRecordingMic) tokens.statusRec else null,
                    label = if (uiState.isRecordingMic) "Stop" else "Mic take",
                    modifier = Modifier.testTag("transport_mic_button")
                )

                val exportLabel = when (uiState.exportState) {
                    ExportState.IDLE -> "Export"
                    ExportState.EXPORTING -> "Exporting…"
                    ExportState.EXPORTED -> "Exported ✓"
                }
                HardwareKeyButton(
                    icon = if (uiState.exportState == ExportState.EXPORTED) Icons.Filled.Check else Icons.Filled.FileDownload,
                    contentDescription = stringResource(R.string.cd_export_video),
                    onClick = { onExportVideo(false) },
                    enabled = videoFile != null && uiState.exportState != ExportState.EXPORTING,
                    active = uiState.exportState == ExportState.EXPORTED,
                    ledColor = when (uiState.exportState) {
                        ExportState.EXPORTED -> tokens.statusLive
                        ExportState.EXPORTING -> tokens.evidenceAmber
                        ExportState.IDLE -> null
                    },
                    label = exportLabel,
                    modifier = Modifier.testTag("transport_export_button")
                )
            }

            // Compact Clips Shelf + Share when multiple takes or rendered clip exist
            val renderedProjects = uiState.projects.filter { !it.renderedVideoKey.isNullOrBlank() }
            if (renderedProjects.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(renderedProjects, key = { it.id }) { clip ->
                            val selected = clip.id == project.id
                            Surface(
                                color = if (selected) tokens.channelAccentContainer else ConsoleElevated,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) tokens.channelAccent else tokens.hairline
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onSwitchProject(clip.id) }
                            ) {
                                Text(
                                    text = clip.title.ifBlank { "Clip" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) tokens.channelAccent else StudioCrisp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    if (videoFile != null) {
                        IconButton(
                            onClick = { onExportVideo(true) },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ConsoleElevated)
                                .testTag("review_share_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = stringResource(R.string.cd_share_video),
                                tint = tokens.channelAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Uniform Pinned Primary Action Slot
            StudioPrimaryCta(
                text = if (videoFile == null) {
                    stringResource(R.string.cta_render_aircheck)
                } else {
                    "Re-render Aircheck"
                },
                onClick = onRenderVideo,
                enabled = !uiState.renderProgress.active,
                modifier = Modifier.testTag("review_render_cta")
            )
        }
    }
}
