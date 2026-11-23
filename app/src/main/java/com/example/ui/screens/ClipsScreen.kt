package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.data.ClipCardRow
import com.example.data.RenderJobStatus
import com.example.data.formatDurationBadge
import com.example.data.local.CasMediaStore
import com.example.engine.ThumbHashCodec
import com.example.ui.StudioUiState
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioErrorBanner
import com.example.ui.components.StudioPrimaryCta
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle

/**
 * Tab 3 — Clips:
 * Clean broadcast library showing finished videos (including renders from your Mac studio)
 * with real JPEG frame thumbnails and live progress on active renders.
 */
@Composable
fun ClipsScreen(
    uiState: StudioUiState,
    casStore: CasMediaStore,
    onOpenClip: (String) -> Unit,
    onRefreshClips: () -> Unit,
    onStartNewStory: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clips = uiState.clipCards

    LaunchedEffect(Unit) {
        onRefreshClips()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!uiState.errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                StudioErrorBanner(message = uiState.errorMessage, onDismiss = onDismissError)
            }
        }

        if (clips.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VideoLibrary,
                            contentDescription = null,
                            tint = TelemetryMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "No news clips yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = StudioCrisp
                        )
                        Text(
                            text = "Drop a story idea in Story, flesh it out with ✨ AI, and generate your first broadcast.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TelemetryMuted
                        )
                        StudioPrimaryCta(
                            text = "Write a story",
                            onClick = onStartNewStory,
                            modifier = Modifier.testTag("clips_start_story_button")
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 840.dp)
                    .testTag("clips_grid"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(clips, key = { it.id }) { clip ->
                    ClipGridTile(
                        clip = clip,
                        remoteThumbUrl = uiState.remoteThumbUrls[clip.id],
                        casStore = casStore,
                        onClick = { onOpenClip(clip.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipGridTile(
    clip: ClipCardRow,
    remoteThumbUrl: String?,
    casStore: CasMediaStore,
    onClick: () -> Unit
) {
    val tokens = LocalAircheckTokens.current
    val photoFile = remember(clip.photoSha) { casStore.fileForSha(clip.photoSha, "jpg") }
    val thumbBitmap = remember(clip.thumbHash) { ThumbHashCodec.decode(clip.thumbHash) }
    val previewModel: Any? = remoteThumbUrl?.takeIf { it.isNotBlank() } ?: photoFile

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ConsoleElevated)
            .border(1.dp, tokens.hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("clip_tile_${clip.id}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 14f)
                .background(OledVoid),
            contentAlignment = Alignment.Center
        ) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (previewModel != null) {
                AsyncImage(
                    model = previewModel,
                    contentDescription = clip.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (clip.status == RenderJobStatus.READY) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(Color.Black.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play clip",
                        tint = tokens.channelAccent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (clip.status.isActive) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(12.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = clip.stageDetail.ifBlank { clip.status.label },
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.channelAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        LinearProgressIndicator(
                            progress = { clip.progressFraction.coerceIn(0.08f, 1f) },
                            color = tokens.channelAccent,
                            trackColor = tokens.hairline,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = clip.title.ifBlank { "Breaking News" },
                style = MaterialTheme.typography.labelLarge,
                color = StudioCrisp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatDurationBadge((clip.durationMs / 1000L).toInt().coerceAtLeast(1)),
                    style = TelemetryTimecodeStyle,
                    color = TelemetryMuted
                )
                if (clip.status == RenderJobStatus.FAILED) {
                    Text(
                        text = "Failed",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.hazardCut
                    )
                }
            }
        }
    }
}
