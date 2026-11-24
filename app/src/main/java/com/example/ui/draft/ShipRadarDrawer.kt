package com.example.ui.draft

import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.draft.CandidateDraft
import com.example.draft.XAttachment
import com.example.graph.GraphMatch
import com.example.graph.MatchReason
import com.example.graph.XShipIntentHelper
import com.example.ui.theme.AcidVoltLime
import com.example.ui.theme.AcidVoltLimeDim
import com.example.ui.theme.BroadcastCobalt
import com.example.ui.theme.BroadcastCobaltDim
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.ConsoleObsidian
import com.example.ui.theme.EvidenceAmber
import com.example.ui.theme.GraphiteHairline
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.PureBlackInk
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMuted

private val ShipProseStyle = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 20.sp,
    lineHeight = 32.sp,
    color = StudioCrisp
)

private val ShipMonoStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontFeatureSettings = "tnum"
)

/**
 * Stage 4 (`04 SHIP`) Eye Zone: unobstructed sanitized ASCII final draft readout with
 * optional attached Quote/Reply context card rendered directly below the draft.
 */
@Composable
fun ShipEyeZone(
    candidate: CandidateDraft?,
    fallbackSeedText: String,
    activeAttachment: XAttachment?,
    onClearAttachment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val finalText = remember(candidate, fallbackSeedText) {
        val raw = candidate?.plainText()?.takeIf { it.isNotBlank() } ?: fallbackSeedText
        XShipIntentHelper.sanitizeAscii(raw)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (finalText.isBlank()) "Draft is empty." else finalText,
            style = ShipProseStyle,
            color = if (finalText.isBlank()) TelemetryMuted else StudioCrisp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ship_eye_zone_text")
        )

        if (activeAttachment != null) {
            val headerText = when (activeAttachment) {
                is XAttachment.QuotePost -> "Quoting @${activeAttachment.handle}"
                is XAttachment.ReplyTarget -> "Replying to @${activeAttachment.handle}"
            }
            val snippetText = when (activeAttachment) {
                is XAttachment.QuotePost -> activeAttachment.snippet
                is XAttachment.ReplyTarget -> activeAttachment.snippet
            }
            val accentColor = when (activeAttachment) {
                is XAttachment.QuotePost -> AcidVoltLime
                is XAttachment.ReplyTarget -> BroadcastCobalt
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ship_attachment_preview")
                    .clip(RoundedCornerShape(8.dp))
                    .background(ConsoleObsidian)
                    .border(1.dp, accentColor.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = headerText,
                        style = ShipMonoStyle.copy(fontWeight = FontWeight.Bold),
                        color = accentColor,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear attachment",
                        tint = TelemetryMuted,
                        modifier = Modifier
                            .size(16.dp)
                            .testTag("ship_clear_attachment_button")
                            .clickable(role = Role.Button) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onClearAttachment()
                            }
                    )
                }
                if (snippetText.isNotBlank()) {
                    Text(
                        text = "\"$snippetText\"",
                        style = TextStyle(
                            fontFamily = SpaceGroteskFontFamily,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        ),
                        color = TelemetryMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Stage 4 (`04 SHIP`) Local Network Graph Radar Drawer + Final Ship Action Bar.
 *
 * - Collapsed 40dp drawer header (`Graph matches (N) ^`) anchored cleanly above the Ship Bar;
 *   tap or swipe up expands the half-height sheet without obscuring the Eye Zone draft.
 * - Honest empty state when `graphContactCount == 0` with SAF `[ Import X archive ]` picker.
 * - Real 72dp+ plain-text Contact Match rows with `[ Quote ]`, `[ Reply ]`, `[ Weave @ ]`,
 *   and swipe-left `[ Mute Match ]`.
 * - Final Ship Action Bar: `[ Open in X ]`, `[ Copy Sanitized ASCII ]`, `[ Broadcast Clip ]`.
 */
@Composable
fun ShipRadarDrawer(
    graphMatches: List<GraphMatch>,
    graphContactCount: Int,
    isExpanded: Boolean,
    isImportingArchive: Boolean,
    activeAttachment: XAttachment?,
    canShip: Boolean,
    isBusy: Boolean,
    onSetRadarExpanded: (Boolean) -> Unit,
    onImportXArchiveUri: (Uri) -> Unit,
    onToggleQuote: (GraphMatch) -> Unit,
    onToggleReply: (GraphMatch) -> Unit,
    onWeaveMention: (GraphMatch) -> Unit,
    onMuteMatch: (GraphMatch) -> Unit,
    onOpenInX: () -> Unit,
    onCopySanitizedAscii: () -> Unit,
    onBroadcastClip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 24.dp.toPx() }
    val currentSetExpanded by rememberUpdatedState(onSetRadarExpanded)

    val archivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportXArchiveUri(uri)
        }
    }

    val launchArchivePicker: () -> Unit = {
        archivePickerLauncher.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/json",
                "text/javascript",
                "application/javascript",
                "text/plain",
                "*/*"
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OledVoid)
    ) {
        // Collapsed / Expandable 40dp Drawer Header: `Graph matches (N) ^`
        var headerDragAccumPx by remember { mutableFloatStateOf(0f) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .testTag("radar_drawer_header")
                .background(ConsoleObsidian)
                .drawBehind {
                    drawLine(
                        color = GraphiteHairline,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = GraphiteHairline,
                        start = Offset(0f, size.height - 1.dp.toPx()),
                        end = Offset(size.width, size.height - 1.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                .pointerInput(isExpanded) {
                    detectVerticalDragGestures(
                        onDragStart = { headerDragAccumPx = 0f },
                        onVerticalDrag = { _, dragAmount -> headerDragAccumPx += dragAmount },
                        onDragEnd = {
                            if (headerDragAccumPx <= -dragThresholdPx && !isExpanded) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                currentSetExpanded(true)
                            } else if (headerDragAccumPx >= dragThresholdPx && isExpanded) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                currentSetExpanded(false)
                            }
                            headerDragAccumPx = 0f
                        },
                        onDragCancel = { headerDragAccumPx = 0f }
                    )
                }
                .clickable(role = Role.Button) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSetRadarExpanded(!isExpanded)
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Graph matches (${graphMatches.size})",
                style = ShipMonoStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                color = if (graphMatches.isNotEmpty()) StudioCrisp else TelemetryMuted,
                maxLines = 1
            )

            Icon(
                imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp,
                contentDescription = if (isExpanded) "Collapse graph radar" else "Expand graph radar",
                tint = if (graphMatches.isNotEmpty()) AcidVoltLime else TelemetryMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        // Expanded Half-Height Radar Sheet (stays inside Thumb Zone, never obscures Eye Zone)
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 260.dp)
                    .testTag("radar_drawer_sheet")
                    .background(ConsoleObsidian)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (graphContactCount == 0) {
                    // Honest empty state — ZERO fabricated @handles
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "No local graph imported yet",
                            style = TextStyle(
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            ),
                            color = StudioCrisp
                        )
                        Text(
                            text = "Import your X archive (.zip, .json, or .js) to surface peers on-device.",
                            style = TextStyle(
                                fontFamily = SpaceGroteskFontFamily,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            ),
                            color = TelemetryMuted
                        )
                        Box(
                            modifier = Modifier
                                .testTag("import_x_archive_button")
                                .clip(RoundedCornerShape(6.dp))
                                .background(ConsoleElevated)
                                .border(1.dp, AcidVoltLime, RoundedCornerShape(6.dp))
                                .clickable(enabled = !isImportingArchive, role = Role.Button) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    launchArchivePicker()
                                }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isImportingArchive) "Importing archive..." else "Import X archive",
                                style = TextStyle(
                                    fontFamily = SpaceGroteskFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                ),
                                color = AcidVoltLime
                            )
                        }
                    }
                } else {
                    // Compact indexed count + [ Import ] refresh action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$graphContactCount contacts local",
                            style = ShipMonoStyle,
                            color = TelemetryMuted,
                            maxLines = 1
                        )
                        Text(
                            text = if (isImportingArchive) "Importing..." else "Import",
                            style = ShipMonoStyle.copy(fontWeight = FontWeight.SemiBold),
                            color = AcidVoltLime,
                            modifier = Modifier
                                .testTag("import_x_archive_button")
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, GraphiteHairline, RoundedCornerShape(4.dp))
                                .clickable(enabled = !isImportingArchive, role = Role.Button) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    launchArchivePicker()
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (graphMatches.isEmpty()) {
                        Text(
                            text = "No matching contacts for this draft topic yet.",
                            style = TextStyle(
                                fontFamily = SpaceGroteskFontFamily,
                                fontSize = 13.sp
                            ),
                            color = TelemetryMuted,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(graphMatches, key = { "${it.handle}_${it.tweetId}" }) { match ->
                                GraphMatchRow(
                                    match = match,
                                    isQuoted = activeAttachment is XAttachment.QuotePost &&
                                        activeAttachment.handle == match.handle &&
                                        activeAttachment.tweetId == match.tweetId,
                                    isReplying = activeAttachment is XAttachment.ReplyTarget &&
                                        activeAttachment.handle == match.handle &&
                                        activeAttachment.tweetId == match.tweetId,
                                    onToggleQuote = { onToggleQuote(match) },
                                    onToggleReply = { onToggleReply(match) },
                                    onWeaveMention = { onWeaveMention(match) },
                                    onMuteMatch = { onMuteMatch(match) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Final Ship Action Bar: [ Open in X ], [ Copy Sanitized ASCII ], [ Broadcast Clip ]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OledVoid)
                .drawBehind {
                    drawLine(
                        color = GraphiteHairline,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .height(54.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shipEnabled = canShip && !isBusy

            // [ Open in X ]
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .testTag("ship_open_in_x_button")
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (shipEnabled) AcidVoltLime else ConsoleObsidian)
                    .border(
                        1.dp,
                        if (shipEnabled) AcidVoltLime else GraphiteHairline,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = shipEnabled, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onOpenInX()
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Open in X",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    color = if (shipEnabled) PureBlackInk else TelemetryMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }

            // [ Copy Sanitized ASCII ]
            Box(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .testTag("ship_copy_ascii_button")
                    .clip(RoundedCornerShape(8.dp))
                    .background(ConsoleElevated)
                    .border(1.dp, GraphiteHairline, RoundedCornerShape(8.dp))
                    .clickable(enabled = shipEnabled, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onCopySanitizedAscii()
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Copy Sanitized ASCII",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    ),
                    color = if (shipEnabled) StudioCrisp else TelemetryMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // [ Broadcast Clip ] — hands draft directly to Aircheck's Mac :8443 v002 renderer
            Box(
                modifier = Modifier
                    .weight(1.0f)
                    .fillMaxHeight()
                    .testTag("ship_broadcast_clip_button")
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (shipEnabled) BroadcastCobaltDim else ConsoleObsidian)
                    .border(
                        1.dp,
                        if (shipEnabled) BroadcastCobalt else GraphiteHairline,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = shipEnabled, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onBroadcastClip()
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Broadcast Clip",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    ),
                    color = if (shipEnabled) StudioCrisp else TelemetryMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun GraphMatchRow(
    match: GraphMatch,
    isQuoted: Boolean,
    isReplying: Boolean,
    onToggleQuote: () -> Unit,
    onToggleReply: () -> Unit,
    onWeaveMention: () -> Unit,
    onMuteMatch: () -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val revealWidthPx = with(density) { 96.dp.toPx() }
    val autoMuteThresholdPx = with(density) { 132.dp.toPx() }

    val currentMute by rememberUpdatedState(onMuteMatch)
    var rawOffsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = rawOffsetX,
        animationSpec = spring(stiffness = 500f),
        label = "radar_row_swipe_x"
    )

    val reasonColor = when (match.reason) {
        MatchReason.COUNTER_ARGUMENT -> EvidenceAmber
        MatchReason.MAINTAINER -> AcidVoltLime
        MatchReason.ACTIVE_THREAD -> TelemetryCyan
        MatchReason.PEER -> TelemetryMuted
    }

    val primaryHeader = if (match.lastReplyAgeLabel.isNotBlank()) {
        "@${match.handle} · Last reply ${match.lastReplyAgeLabel}"
    } else {
        "@${match.handle}"
    }
    val receiptLine = if (match.postAgeLabel.isNotBlank()) {
        "${match.postAgeLabel}: '${match.snippet}'"
    } else {
        "'${match.snippet}'"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("graph_match_row_${match.handle}")
            .clip(RoundedCornerShape(8.dp))
    ) {
        // Swipe-left revealed Mute Match action behind the row
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(OnAirCrimson.copy(alpha = 0.22f))
                .padding(end = 10.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .testTag("radar_mute_${match.handle}")
                    .clip(RoundedCornerShape(6.dp))
                    .background(OnAirCrimson)
                    .clickable(role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        rawOffsetX = 0f
                        onMuteMatch()
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Mute Match",
                    style = ShipMonoStyle.copy(fontWeight = FontWeight.Bold),
                    color = StudioCrisp,
                    maxLines = 1
                )
            }
        }

        // Foreground Contact Match row (>= 72dp height)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .graphicsLayer { translationX = animatedOffsetX }
                .clip(RoundedCornerShape(8.dp))
                .background(OledVoid)
                .border(1.dp, GraphiteHairline, RoundedCornerShape(8.dp))
                .pointerInput(match.handle) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            rawOffsetX = (rawOffsetX + dragAmount).coerceIn(-autoMuteThresholdPx * 1.2f, 0f)
                        },
                        onDragEnd = {
                            when {
                                rawOffsetX <= -autoMuteThresholdPx -> {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    rawOffsetX = 0f
                                    currentMute()
                                }
                                rawOffsetX <= -revealWidthPx * 0.45f -> {
                                    rawOffsetX = -revealWidthPx
                                }
                                else -> {
                                    rawOffsetX = 0f
                                }
                            }
                        },
                        onDragCancel = { rawOffsetX = 0f }
                    )
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Row 1: @handle · Last reply Xd ago + match.reason.label (strictly 1 secondary label)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = primaryHeader,
                    style = ShipMonoStyle.copy(fontWeight = FontWeight.SemiBold),
                    color = StudioCrisp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = match.reason.label,
                    style = ShipMonoStyle,
                    color = reasonColor,
                    maxLines = 1
                )
            }

            // Row 2 (The Receipt): verbatim 1-line snippet + post age
            Text(
                text = receiptLine,
                style = TextStyle(
                    fontFamily = SpaceGroteskFontFamily,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                color = TelemetryMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Zero-Clipboard Actions: [ Quote ], [ Reply ], [ Weave @ ]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadarRowActionPill(
                    label = "Quote",
                    active = isQuoted,
                    activeColor = AcidVoltLime,
                    activeBg = AcidVoltLimeDim,
                    enabled = match.postUrl.isNotBlank(),
                    testTag = "radar_quote_${match.handle}",
                    onClick = onToggleQuote
                )
                RadarRowActionPill(
                    label = "Reply",
                    active = isReplying,
                    activeColor = BroadcastCobalt,
                    activeBg = BroadcastCobaltDim,
                    enabled = match.tweetId.isNotBlank(),
                    testTag = "radar_reply_${match.handle}",
                    onClick = onToggleReply
                )
                RadarRowActionPill(
                    label = "Weave @",
                    active = false,
                    activeColor = StudioCrisp,
                    activeBg = ConsoleElevated,
                    enabled = true,
                    testTag = "radar_weave_${match.handle}",
                    onClick = onWeaveMention
                )
            }
        }
    }
}

@Composable
private fun RadarRowActionPill(
    label: String,
    active: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    activeBg: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .testTag(testTag)
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) activeBg else ConsoleElevated)
            .border(
                1.dp,
                if (active) activeColor else GraphiteHairline,
                RoundedCornerShape(5.dp)
            )
            .clickable(enabled = enabled, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = ShipMonoStyle.copy(
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
            ),
            color = when {
                !enabled -> TelemetryMuted.copy(alpha = 0.45f)
                active -> activeColor
                else -> StudioCrisp
            },
            maxLines = 1
        )
    }
}
