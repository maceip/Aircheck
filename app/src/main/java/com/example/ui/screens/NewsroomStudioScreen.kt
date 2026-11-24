package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.StudioUiState
import com.example.ui.a2ui.AircheckA2uiController
import com.example.ui.components.StudioCard
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LiveTallyGreen
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle

/**
 * Separate dedicated screen for Newsroom broadcast generation and stock Jetpack A2UI
 * (`androidx.compose.material3:material3-a2ui`) video overlays (speaking news pop-ups,
 * transitions, and B-roll). Kept completely outside the primary script-drafting flow.
 */
@Composable
fun NewsroomStudioScreen(
    uiState: StudioUiState,
    a2uiController: AircheckA2uiController,
    onBack: () -> Unit,
    onOpenClip: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val tokens = LocalAircheckTokens.current
    val surfaces by a2uiController.activeSurfaces.collectAsStateWithLifecycle()
    val primarySurface = surfaces.firstOrNull()

    val modeLabel = if (uiState.isRemotionUnused) "Setup Needed" else "Remotion Active"
    val modeColor = if (uiState.isRemotionUnused) TelemetryCyan else LiveTallyGreen

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
                    .testTag("newsroom_studio_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = StudioCrisp
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Newsroom & Overlay Studio",
                style = MaterialTheme.typography.titleMedium,
                color = StudioCrisp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = modeLabel,
                style = TelemetryTimecodeStyle,
                color = modeColor,
                modifier = Modifier.testTag("newsroom_remotion_mode_label")
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
                .testTag("newsroom_studio_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "a2ui_newsroom_surface") {
                if (primarySurface != null) {
                    A2uiSurface(
                        surfaceModel = primarySurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("newsroom_a2ui_surface"),
                        errorContent = { err ->
                            StudioCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Overlay surface error",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = OnAirCrimson
                                    )
                                    Text(
                                        text = "${err.code}: ${err.message.orEmpty()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TelemetryMuted
                                    )
                                }
                            }
                        }
                    )
                } else {
                    StudioCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Preparing Jetpack A2UI surface…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TelemetryMuted
                        )
                    }
                }
            }

            val errMsg = uiState.errorMessage
            if (!errMsg.isNullOrBlank()) {
                item(key = "newsroom_error_card") {
                    StudioCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Studio status",
                                style = MaterialTheme.typography.titleSmall,
                                color = OnAirCrimson
                            )
                            Text(
                                text = errMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioCrisp,
                                modifier = Modifier.testTag("newsroom_error_text")
                            )
                        }
                    }
                }
            }

            if (uiState.clipCards.isNotEmpty()) {
                item(key = "recent_clips_header") {
                    Text(
                        text = "Rendered Newsroom Clips",
                        style = MaterialTheme.typography.titleSmall,
                        color = StudioCrisp
                    )
                }
                items(
                    items = uiState.clipCards,
                    key = { it.id }
                ) { clip ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ConsoleElevated)
                            .clickable { onOpenClip(clip.id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = clip.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioCrisp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = clip.status.label,
                            style = TelemetryTimecodeStyle,
                            color = tokens.channelAccent
                        )
                    }
                }
            }
        }
    }
}
