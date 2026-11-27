package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.BreakingNewsStyle
import com.example.ui.StudioUiState
import com.example.ui.a2ui.AircheckA2uiController
import com.example.ui.clipstudio.SectionCaption
import com.example.ui.design.AcCard
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import androidx.compose.material.icons.outlined.AutoAwesome

/**
 * Separate optional page for authoring AI-generated stock Jetpack A2UI
 * (`androidx.compose.material3:material3-a2ui`) overlay objects and saving them to Room
 * (`SavedNewsroomOverlay`) for optional attachment to News Briefs in Stage 4 (`04 B-ROLL`)
 * of the Core Story Pipeline. Never required for standard Core Pipeline runs.
 */
@Composable
fun NewsroomStudioScreen(
    uiState: StudioUiState,
    a2uiController: AircheckA2uiController,
    onDeleteSavedOverlay: (String) -> Unit = {},
    onSelectBreakingStyle: (BreakingNewsStyle) -> Unit = {},
    onGenerateOverlaySet: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val surfaces by a2uiController.activeSurfaces.collectAsStateWithLifecycle()
    val primarySurface = surfaces.firstOrNull()

    val modeLabel = if (uiState.isRemotionUnused) "Setup Needed" else "Overlay Builder"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AcTokens.Chassis)
            .windowInsetsPadding(WindowInsets.systemBars),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AcKey(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                size = AcKeySize.Small,
                modifier = Modifier.testTag("newsroom_studio_back_button")
            )
            Text(
                text = "Newsroom Overlays",
                style = AcType.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SectionCaption(modeLabel, modifier = Modifier.testTag("newsroom_remotion_mode_label"))
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .testTag("newsroom_studio_list"),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "breaking_style_chips_card") {
                val activeStyle = uiState.corePipelineSession.renderOptions.breakingStyle
                AcCard(tag = "Breaking news style", modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BreakingNewsStyle.entries.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { style ->
                                AcKey(
                                    label = style.chipLabel.uppercase(),
                                    selected = activeStyle == style,
                                    onClick = { onSelectBreakingStyle(style) },
                                    size = AcKeySize.Small,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("newsroom_bstyle_${style.wire}")
                                )
                            }
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    AcKey(
                        label = if (uiState.isGeneratingOverlaySet) {
                            "Generating Overlay Set…"
                        } else {
                            "Generate 3-Overlay Set (${activeStyle.chipLabel})"
                        },
                        icon = Icons.Outlined.AutoAwesome,
                        onClick = onGenerateOverlaySet,
                        enabled = !uiState.isGeneratingOverlaySet,
                        selected = uiState.isGeneratingOverlaySet,
                        size = AcKeySize.Small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("newsroom_generate_set_button")
                    )
                }
            }

            item(key = "a2ui_newsroom_surface") {
                if (primarySurface != null) {
                    A2uiSurface(
                        surfaceModel = primarySurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("newsroom_a2ui_surface"),
                        errorContent = { err ->
                            AcCard(tag = "Overlay surface error", modifier = Modifier.fillMaxWidth()) {
                                Text("${err.code}: ${err.message.orEmpty()}", style = AcType.BodySmall.copy(color = AcTokens.Red))
                            }
                        }
                    )
                } else {
                    AcCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Preparing Jetpack A2UI surface…", style = AcType.BodySmall)
                    }
                }
            }

            val errMsg = uiState.errorMessage
            if (!errMsg.isNullOrBlank()) {
                item(key = "newsroom_error_card") {
                    AcCard(tag = "Studio status", modifier = Modifier.fillMaxWidth()) {
                        Text(errMsg, style = AcType.BodySmall.copy(color = AcTokens.Red), modifier = Modifier.testTag("newsroom_error_text"))
                    }
                }
            }

            item(key = "saved_overlays_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionCaption("Saved news brief overlays")
                    SectionCaption("${uiState.savedOverlays.size} saved", modifier = Modifier.testTag("saved_overlays_count_label"))
                }
            }

            if (uiState.savedOverlays.isEmpty()) {
                item(key = "saved_overlays_empty") {
                    AcCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "No saved overlays yet. Standard Core Pipeline clips need zero overlays; save one above only when creating a News Brief.",
                            style = AcType.BodySmall
                        )
                    }
                }
            } else {
                items(items = uiState.savedOverlays, key = { it.id }) { overlay ->
                    AcCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("saved_overlay_card_${overlay.id}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(overlay.headline, style = AcType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (overlay.subline.isNotBlank()) {
                                    Text(overlay.subline, style = AcType.BodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            AcKey(
                                label = "Remove",
                                onClick = { onDeleteSavedOverlay(overlay.id) },
                                size = AcKeySize.Small,
                                modifier = Modifier.testTag("delete_saved_overlay_${overlay.id}")
                            )
                        }
                    }
                }
            }
        }
    }
}
