package com.example.ui.screens

import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.ui.clipstudio.StudioStatusLine
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcMediaKey
import com.example.ui.design.AcMediaWell
import com.example.ui.design.AcPrimaryKey
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import kotlinx.coroutines.delay

/**
 * Review player bound to the single app-scoped `AircheckPlayerController` (design v2): the video on an
 * AcDisplay, the real Room waveform on a display, transport in a media well, and Share as the one
 * primary key.
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
        if (playbackUri != null) playerController.prepareUri(playbackUri, autoPlay = false)
    }
    DisposableEffect(Unit) { onDispose { playerController.pause() } }
    LaunchedEffect(isPlaying, playbackUri) {
        while (isPlaying && playbackUri != null) {
            positionMs = playerController.currentPositionMs()
            delay(40L)
        }
    }

    val progressFraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val activeCaptionWord = remember(clip?.alignedWords, positionMs) {
        clip?.alignedWords?.firstOrNull { positionMs in it.startMs..it.endMs }?.word
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AcTokens.Chassis)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AcKey(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Clips",
                    onClick = onBack,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("review_back_button")
                )
                Text(
                    text = clip?.title?.ifBlank { "Aircheck clip" } ?: "Clip",
                    style = AcType.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (clip != null && clip.alignedWords.isNotEmpty()) {
                    AcKey(
                        icon = Icons.Filled.ClosedCaption,
                        contentDescription = "Toggle caption preview",
                        onClick = { showLiveCaptionOverlay = !showLiveCaptionOverlay },
                        selected = showLiveCaptionOverlay,
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("toggle_live_caption_overlay")
                    )
                }
            }

            val clipError = clip?.errorMessage
            val shownError = errorMessage?.takeIf { it.isNotBlank() } ?: clipError?.takeIf { it.isNotBlank() }
            StudioStatusLine(error = shownError, notice = null, onDismissError = onDismissError, onDismissNotice = {})

            AcDisplay(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(enabled = playbackUri != null) { playerController.togglePlayPause() }
                    .testTag("review_video_stage")
            ) {
                when {
                    clip == null -> Text("Clip not found", style = AcType.Body.copy(color = AcTokens.OpGrey))

                    playbackUri != null -> {
                        val player = remember { playerController.acquirePlayer() }
                        AndroidView(
                            factory = { ctx ->
                                (LayoutInflater.from(ctx).inflate(R.layout.cut_texture_player_view, null) as PlayerView).apply {
                                    this.player = player
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setKeepContentOnPlayerReset(false)
                                }
                            },
                            update = { view -> if (view.player !== player) view.player = player },
                            onRelease = { view -> view.player = null },
                            modifier = Modifier.fillMaxSize()
                        )

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
                                    Text(
                                        text = activeCaptionWord,
                                        style = AcType.Title.copy(color = AcTokens.DisplayInk),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(AcTokens.Display.copy(alpha = 0.78f))
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                                if (clip.chyronHeadline.isNotBlank()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(AcTokens.Display.copy(alpha = 0.9f))
                                            .padding(10.dp)
                                    ) {
                                        Text(clip.chyronHeadline, style = AcType.Label.copy(color = AcTokens.DisplayInk), maxLines = 1)
                                        if (clip.chyronSubline.isNotBlank()) {
                                            Text(clip.chyronSubline, style = AcType.Caption.copy(color = AcTokens.OpGrey), maxLines = 1)
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
                            Text(clip.stageDetail.ifBlank { clip.status.label }, style = AcType.Label.copy(color = AcTokens.DisplayInk))
                            if (clip.status.isActive) {
                                val fraction = clip.progressFraction.coerceIn(0f, 1f)
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .drawBehind {
                                            val y = size.height / 2f
                                            drawLine(AcTokens.MediaKey, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
                                            drawLine(AcTokens.OpRed, Offset(0f, y), Offset(size.width * fraction, y), 1.5.dp.toPx())
                                        }
                                )
                                Text("${(clip.progressFraction * 100).toInt()}%", style = AcType.numeral(22.sp))
                            } else if (clip.status == RenderJobStatus.FAILED) {
                                Text(clip.errorMessage ?: "Cloud render job failed.", style = AcType.BodySmall.copy(color = AcTokens.OpRed))
                            }
                        }
                    }
                }
            }

            ReviewWaveformDisplay(
                peaks = clip?.waveformPeaks.orEmpty(),
                progress = progressFraction,
                modifier = Modifier.testTag("review_waveform")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AcMediaWell(padding = 6.dp) {
                    AcMediaKey(
                        icon = Icons.Filled.FastRewind,
                        contentDescription = stringResource(R.string.cd_rewind),
                        onClick = {
                            playerController.seekTo(0L)
                            positionMs = 0L
                        },
                        enabled = playbackUri != null,
                        size = 54.dp,
                        modifier = Modifier.testTag("transport_rewind_button")
                    )
                    AcMediaKey(
                        icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.cd_play_pause),
                        onClick = { playerController.togglePlayPause() },
                        enabled = playbackUri != null,
                        active = isPlaying,
                        size = 54.dp,
                        modifier = Modifier.testTag("transport_play_button")
                    )
                }

                val isExported = exportState == ExportState.EXPORTED || clip?.exportedUri != null
                val exportLabel = when (exportState) {
                    ExportState.IDLE -> if (clip?.exportedUri != null) "Exported ✓" else "Export"
                    ExportState.EXPORTING -> "Exporting…"
                    ExportState.EXPORTED -> "Exported ✓"
                }
                AcKey(
                    label = exportLabel,
                    icon = if (isExported) Icons.Filled.Check else Icons.Filled.FileDownload,
                    onClick = { if (clip != null) onExport(clip, false) },
                    enabled = clip != null && playbackUri != null && exportState != ExportState.EXPORTING,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("transport_export_button")
                )
                AcPrimaryKey(
                    label = "Share",
                    icon = Icons.Filled.Share,
                    onClick = { if (clip != null) onExport(clip, true) },
                    enabled = clip != null && playbackUri != null && exportState != ExportState.EXPORTING,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("review_share_button")
                )
            }
        }
    }
}

/** Real persisted waveform peaks on a display: grey ahead of the playhead, red behind it. */
@Composable
private fun ReviewWaveformDisplay(peaks: List<Float>, progress: Float, modifier: Modifier = Modifier) {
    AcDisplay(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .drawBehind {
                    if (peaks.isEmpty()) {
                        val y = size.height / 2f
                        drawLine(AcTokens.MediaKeyHi, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        return@drawBehind
                    }
                    val step = size.width / peaks.size
                    val mid = size.height / 2f
                    peaks.forEachIndexed { i, p ->
                        val x = i * step + step / 2f
                        val h = (p.coerceIn(0f, 1f) * size.height).coerceAtLeast(1.dp.toPx())
                        val played = (i.toFloat() / peaks.size) <= progress
                        drawLine(
                            if (played) AcTokens.OpRed else AcTokens.OpGrey,
                            Offset(x, mid - h / 2f),
                            Offset(x, mid + h / 2f),
                            1.5.dp.toPx()
                        )
                    }
                }
        )
    }
}
