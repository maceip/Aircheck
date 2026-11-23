package com.voicestudio.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicestudio.edl.ClauseNode
import kotlin.math.abs
import kotlin.math.roundToInt

private val MonoBoldStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum"
)

private val MonoMediumStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum"
)

/**
 * Single-screen OLED Broadcast Deck Hairline Clipper editor.
 *
 * Layout:
 * - Header (52.dp): Back trigger, screen title, live timecode (when ready), and Export action.
 * - Top Row: Monospace `WordPill` lazy row synced to [ClipperUiState.playheadSample].
 *   Swipe down or tap the take badge to hot-swap takes in 0ms; tap the word to edit text/phonemes.
 * - Center + Bottom Left: [VernierScrubCanvas] + [VernierScrubBar] + play/pause transport.
 * - Right Edge: Vertical [GapCompressionRail] (`100% -> 25%`) with live compressed duration delta.
 *
 * Strictly obeys:
 * - Constitutional Law 2: Missing OTA bundle or unsynthesized manifest renders an honest status
 *   panel with Tailscale OTA sync trigger — zero fake waveforms, zero hardcoded metrics.
 * - UI Label Density Limit: Strictly <= 2 adjacent labels per primary label across every row/card.
 */
@Composable
fun ClipperScreen(
    state: ClipperUiState,
    actions: ClipperActions,
    modifier: Modifier = Modifier,
    snapToZeroCrossing: ((Long) -> Long)? = null,
    onHapticTick: (() -> Unit)? = null
) {
    val manifest = state.manifest
    val projection = remember(manifest) {
        ClipperTimelineProjection.fromManifest(manifest)
    }
    val editableManifest = if (state.otaReady && manifest != null && manifest.clauses.isNotEmpty()) {
        manifest
    } else {
        null
    }
    val hasEditableManifest = editableManifest != null

    var localEditingClauseId by remember(state.editingClauseId) {
        mutableStateOf(state.editingClauseId)
    }
    var editingWordIndex by remember { mutableIntStateOf(0) }

    val effectiveEditingClauseId = if (actions.onEditingClauseChange != null) {
        state.editingClauseId
    } else {
        localEditingClauseId
    }

    fun setEditingClause(clauseId: String?, wordIndex: Int = 0) {
        editingWordIndex = wordIndex
        if (actions.onEditingClauseChange != null) {
            actions.onEditingClauseChange.invoke(clauseId)
        } else {
            localEditingClauseId = clauseId
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ClipperDeckColors.OledVoid)
            .testTag("clipper_screen_root")
    ) {
        ClipperHeaderBar(
            hasEditableManifest = hasEditableManifest,
            timecode = if (hasEditableManifest) {
                formatSampleTimecode(state.playheadSample, projection.sampleRate)
            } else {
                null
            },
            onBack = actions.onBack,
            onExportClip = actions.onExportClip
        )

        if (!state.synthesisError.isNullOrBlank()) {
            ClipperErrorBanner(
                message = state.synthesisError,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        if (editableManifest == null) {
            ClipperEmptyOrMissingOtaPanel(
                otaReady = state.otaReady,
                otaStatusText = state.otaStatusText,
                onOpenOtaSync = actions.onOpenOtaSync,
                onBack = actions.onBack,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        } else {
            val activeManifest = editableManifest
            val activeWordIndex = remember(projection, state.playheadSample) {
                projection.activeWordIndexForSample(state.playheadSample)
            }
            val pillListState = rememberLazyListState()
            LaunchedEffect(activeWordIndex) {
                if (activeWordIndex >= 0 && activeWordIndex < projection.wordSpans.size) {
                    pillListState.animateScrollToItem(activeWordIndex)
                }
            }

            // Top Row: Monospace WordPill LazyRow synced to playheadSample
            LazyRow(
                state = pillListState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("word_pill_row")
            ) {
                itemsIndexed(
                    items = projection.wordSpans,
                    key = { _, span -> "${span.clauseId}_${span.wordIndex}" }
                ) { idx, span ->
                    WordPill(
                        span = span,
                        isActive = idx == activeWordIndex,
                        onTap = {
                            setEditingClause(span.clauseId, span.wordIndex)
                        },
                        onSwapTake = {
                            if (span.candidateCount > 1) {
                                val nextIdx = (span.selectedTakeIndex + 1) % span.candidateCount
                                actions.onSwapTake(span.clauseId, nextIdx)
                            }
                        }
                    )
                }
            }

            // Compact inline text/phoneme override editor for a single ClauseNode
            val editingClause = remember(activeManifest, effectiveEditingClauseId) {
                effectiveEditingClauseId?.let { id ->
                    activeManifest.clauses.firstOrNull { it.clauseId == id }
                }
            }
            if (editingClause != null) {
                InlineClauseOverrideEditor(
                    clause = editingClause,
                    selectedWordIndex = editingWordIndex,
                    onSubmit = { newText, newPhonemes ->
                        actions.onOverrideClauseText(editingClause.clauseId, newText, newPhonemes)
                        setEditingClause(null)
                    },
                    onToggleVideoAnchor = { wordIdx, newAnchorId ->
                        actions.onToggleVideoAnchor(editingClause.clauseId, wordIdx, newAnchorId)
                    },
                    onDismiss = { setEditingClause(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Center + Bottom Zone paired with Right-Edge Vertical GapCompressionRail
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    VernierScrubCanvas(
                        projection = projection,
                        playheadSample = state.playheadSample,
                        waveformMinMaxPeaks = state.waveformMinMaxPeaks,
                        samplesPerPeakBucket = state.samplesPerPeakBucket,
                        onSeekSample = actions.onSeekSample,
                        snapToZeroCrossing = snapToZeroCrossing,
                        onHapticTick = onHapticTick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )

                    VernierScrubBar(
                        projection = projection,
                        playheadSample = state.playheadSample,
                        onSeekSample = actions.onSeekSample,
                        snapToZeroCrossing = snapToZeroCrossing,
                        onHapticTick = onHapticTick,
                        modifier = Modifier.fillMaxWidth()
                    )

                    ClipperTransportBar(
                        isPlaying = state.isPlaying,
                        playheadSample = state.playheadSample,
                        sampleRate = projection.sampleRate,
                        onPlayPause = actions.onPlayPause
                    )
                }

                GapCompressionRail(
                    globalGapScale = activeManifest.globalGapScale,
                    compressedDeltaMs = projection.compressedDeltaMs,
                    onGlobalGapScaleChange = actions.onGlobalGapScaleChange,
                    modifier = Modifier
                        .width(64.dp)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun ClipperHeaderBar(
    hasEditableManifest: Boolean,
    timecode: String?,
    onBack: () -> Unit,
    onExportClip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ClipperDeckColors.OledVoid)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("clipper_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = ClipperDeckColors.StudioCrisp
                )
            }
            Text(
                text = "Clipper",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = ClipperDeckColors.StudioCrisp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (timecode != null) {
                Text(
                    text = timecode,
                    style = MonoBoldStyle.copy(
                        fontSize = 13.sp,
                        color = ClipperDeckColors.AcidVoltLime
                    ),
                    maxLines = 1,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .testTag("clipper_header_timecode")
                )
            }
            IconButton(
                onClick = onExportClip,
                enabled = hasEditableManifest,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("clipper_export_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.FileDownload,
                    contentDescription = "Export clip",
                    tint = if (hasEditableManifest) {
                        ClipperDeckColors.AcidVoltLime
                    } else {
                        ClipperDeckColors.GraphiteHairline
                    }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ClipperDeckColors.GraphiteHairline)
        )
    }
}

@Composable
private fun ClipperErrorBanner(
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ClipperDeckColors.OnAirCrimsonSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, ClipperDeckColors.OnAirCrimson.copy(alpha = 0.85f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("clipper_error_banner")
    ) {
        Text(
            text = message,
            style = TextStyle(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = ClipperDeckColors.StudioCrisp
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

/**
 * Honest unsynthesized or missing-OTA state panel. Never renders synthetic waveforms or
 * hardcoded verification scores.
 */
@Composable
private fun ClipperEmptyOrMissingOtaPanel(
    otaReady: Boolean,
    otaStatusText: String,
    onOpenOtaSync: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val headline = if (!otaReady) {
        "Voice OTA bundle not synced"
    } else {
        "No synthesized clauses ready"
    }
    val detail = otaStatusText.ifBlank {
        if (!otaReady) {
            "Sync the 44.1 kHz voice bundle over Tailscale to enable the Hairline Clipper."
        } else {
            "Synthesize a script to inspect real 44.1 kHz waveforms, swap takes, and lock beat cuts."
        }
    }

    Box(
        modifier = modifier.testTag("clipper_empty_ota_state"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = ClipperDeckColors.ConsoleObsidian,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, ClipperDeckColors.GraphiteHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudSync,
                    contentDescription = null,
                    tint = if (!otaReady) {
                        ClipperDeckColors.BroadcastCobalt
                    } else {
                        ClipperDeckColors.AcidVoltLime
                    },
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = headline,
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = ClipperDeckColors.StudioCrisp
                    )
                )
                Text(
                    text = detail,
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = ClipperDeckColors.TelemetryMuted
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (!otaReady) {
                    Button(
                        onClick = onOpenOtaSync,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClipperDeckColors.AcidVoltLime,
                            contentColor = ClipperDeckColors.PureBlackInk
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("clipper_open_ota_sync_button")
                    ) {
                        Text(
                            text = "Sync OTA Bundle",
                            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ClipperDeckColors.AcidVoltLime,
                                contentColor = ClipperDeckColors.PureBlackInk
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("clipper_empty_back_button")
                        ) {
                            Text(
                                text = "Back to Script",
                                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenOtaSync,
                            border = BorderStroke(1.dp, ClipperDeckColors.GraphiteHairline),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("clipper_open_ota_sync_button")
                        ) {
                            Text(
                                text = "OTA Sync",
                                style = TextStyle(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = ClipperDeckColors.StudioCrisp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Monospace WordPill synced to playhead sample.
 * - Vertical swipe down or tapping the take badge swaps `selectedTakeIndex` in 0ms.
 * - Tap opens the inline text/phoneme override editor for that clause.
 * - Strictly contains at most 2 labels: `token.word` (primary) + `T{n}/{m}` (1 secondary).
 */
@Composable
private fun WordPill(
    span: TimelineWordSpan,
    isActive: Boolean,
    onTap: () -> Unit,
    onSwapTake: () -> Unit
) {
    val view = LocalView.current
    val swipeThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }
    val latestOnSwap by rememberUpdatedState(onSwapTake)
    val latestCanSwap by rememberUpdatedState(span.candidateCount > 1)
    val isAnchored = !span.token.videoAnchorId.isNullOrBlank()

    val shape = RoundedCornerShape(8.dp)
    val bgColor = if (isActive) ClipperDeckColors.ConsoleElevated else ClipperDeckColors.ConsoleObsidian
    val borderColor = when {
        isActive -> ClipperDeckColors.AcidVoltLime
        isAnchored -> ClipperDeckColors.BroadcastCobalt
        else -> ClipperDeckColors.GraphiteHairline
    }

    Row(
        modifier = Modifier
            .testTag("word_pill_${span.clauseId}_${span.wordIndex}")
            .height(IntrinsicSize.Min)
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(bgColor)
            .border(width = if (isActive) 1.5.dp else 1.dp, color = borderColor, shape = shape)
            .clickable(role = Role.Button, onClick = onTap)
            .pointerInput(span.clauseId, span.wordIndex, swipeThresholdPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var totalDownDrag = 0f
                    var swappedInGesture = false
                    val touchSlop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dy = change.position.y - change.previousPosition.y
                        if (dy > 0f) {
                            totalDownDrag += dy
                            if (totalDownDrag > touchSlop) {
                                change.consume()
                                if (!swappedInGesture && latestCanSwap && totalDownDrag >= swipeThresholdPx) {
                                    swappedInGesture = true
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    latestOnSwap()
                                }
                            }
                        }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 68.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isAnchored) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ClipperDeckColors.BroadcastCobalt)
                        .testTag("word_pill_anchor_dot_${span.clauseId}_${span.wordIndex}")
                )
            }
            Text(
                text = span.token.word,
                style = MonoBoldStyle.copy(
                    fontSize = 14.sp,
                    color = if (isActive) ClipperDeckColors.AcidVoltLime else ClipperDeckColors.StudioCrisp
                ),
                maxLines = 1
            )
        }

        val takeLabel = "T${span.selectedTakeIndex + 1}/${span.candidateCount.coerceAtLeast(1)}"
        if (span.candidateCount > 1) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(ClipperDeckColors.GraphiteHairline)
            )
            Box(
                modifier = Modifier
                    .testTag("word_pill_swap_${span.clauseId}_${span.wordIndex}")
                    .widthIn(min = 48.dp)
                    .fillMaxHeight()
                    .background(ClipperDeckColors.AcidVoltLimeDim)
                    .clickable(role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSwapTake()
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = takeLabel,
                    style = MonoMediumStyle.copy(
                        fontSize = 11.sp,
                        color = ClipperDeckColors.AcidVoltLime
                    ),
                    maxLines = 1
                )
            }
        } else {
            Text(
                text = takeLabel,
                style = MonoMediumStyle.copy(
                    fontSize = 11.sp,
                    color = ClipperDeckColors.TelemetryMuted
                ),
                maxLines = 1,
                modifier = Modifier
                    .testTag("word_pill_take_${span.clauseId}_${span.wordIndex}")
                    .padding(end = 10.dp)
            )
        }
    }
}

@Composable
private fun InlineClauseOverrideEditor(
    clause: ClauseNode,
    selectedWordIndex: Int,
    onSubmit: (newText: String, newPhonemes: String) -> Unit,
    onToggleVideoAnchor: (wordIndex: Int, videoAnchorId: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draftText by remember(clause.clauseId, clause.rawText) { mutableStateOf(clause.rawText) }
    var draftPhonemes by remember(clause.clauseId, clause.normalizedPhonemes) {
        mutableStateOf(clause.normalizedPhonemes)
    }

    val tokens = clause.selectedCandidate?.tokens.orEmpty()
    val safeWordIndex = selectedWordIndex.coerceIn(0, (tokens.size - 1).coerceAtLeast(0))
    val activeToken = tokens.getOrNull(safeWordIndex)
    val isWordAnchored = !activeToken?.videoAnchorId.isNullOrBlank()

    Surface(
        color = ClipperDeckColors.ConsoleObsidian,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, ClipperDeckColors.BroadcastCobalt),
        modifier = modifier.testTag("clause_override_editor")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Override ${clause.clauseId}",
                    style = MonoBoldStyle.copy(
                        fontSize = 12.sp,
                        color = ClipperDeckColors.BroadcastCobalt
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("override_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close editor",
                        tint = ClipperDeckColors.TelemetryMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ClipperDeckColors.AcidVoltLime,
                unfocusedBorderColor = ClipperDeckColors.GraphiteHairline,
                focusedTextColor = ClipperDeckColors.StudioCrisp,
                unfocusedTextColor = ClipperDeckColors.StudioCrisp,
                focusedContainerColor = ClipperDeckColors.OledVoid,
                unfocusedContainerColor = ClipperDeckColors.OledVoid,
                focusedLabelColor = ClipperDeckColors.AcidVoltLime,
                unfocusedLabelColor = ClipperDeckColors.TelemetryMuted
            )

            OutlinedTextField(
                value = draftText,
                onValueChange = { draftText = it },
                singleLine = true,
                label = { Text("Clause text", fontSize = 11.sp) },
                textStyle = TextStyle(fontSize = 13.sp, color = ClipperDeckColors.StudioCrisp),
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("override_raw_text_field")
            )

            OutlinedTextField(
                value = draftPhonemes,
                onValueChange = { draftPhonemes = it },
                singleLine = true,
                label = { Text("IPA phonemes", fontSize = 11.sp) },
                textStyle = MonoMediumStyle.copy(fontSize = 12.sp, color = ClipperDeckColors.StudioCrisp),
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("override_phonemes_field")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeToken != null) {
                    OutlinedButton(
                        onClick = {
                            val nextId = if (isWordAnchored) {
                                null
                            } else {
                                "vid_${clause.clauseId}_w$safeWordIndex"
                            }
                            onToggleVideoAnchor(safeWordIndex, nextId)
                        },
                        border = BorderStroke(1.dp, ClipperDeckColors.BroadcastCobalt),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("toggle_video_anchor_button")
                    ) {
                        Text(
                            text = if (isWordAnchored) "Unlink Cut" else "Anchor Cut",
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ClipperDeckColors.BroadcastCobalt
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        onSubmit(draftText.trim(), draftPhonemes.trim())
                    },
                    enabled = draftText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClipperDeckColors.AcidVoltLime,
                        contentColor = ClipperDeckColors.PureBlackInk
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .testTag("override_submit_button")
                ) {
                    Text(
                        text = "Re-queue",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipperTransportBar(
    isPlaying: Boolean,
    playheadSample: Long,
    sampleRate: Int,
    onPlayPause: () -> Unit
) {
    Surface(
        color = ClipperDeckColors.ConsoleObsidian,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, ClipperDeckColors.GraphiteHairline),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = onPlayPause,
                color = if (isPlaying) ClipperDeckColors.AcidVoltLime else ClipperDeckColors.ConsoleElevated,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isPlaying) ClipperDeckColors.AcidVoltLime else ClipperDeckColors.GraphiteHairline
                ),
                modifier = Modifier
                    .size(40.dp)
                    .testTag("clipper_play_pause_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (isPlaying) ClipperDeckColors.PureBlackInk else ClipperDeckColors.AcidVoltLime,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Text(
                text = "${formatSampleTimecode(playheadSample, sampleRate)} · $playheadSample smpl",
                style = MonoBoldStyle.copy(
                    fontSize = 12.sp,
                    color = ClipperDeckColors.StudioCrisp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("clipper_timecode")
            )

            Text(
                text = "${"%.1f".format(sampleRate / 1000.0)}kHz",
                style = MonoMediumStyle.copy(
                    fontSize = 11.sp,
                    color = ClipperDeckColors.TelemetryMuted
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * Right-Edge Vertical GapCompressionRail (`100% -> 25%`, `globalGapScale` `1.0f..0.25f`).
 * Displays live scale percentage and real compressed duration delta (2 labels total).
 */
@Composable
fun GapCompressionRail(
    globalGapScale: Float,
    compressedDeltaMs: Long,
    onGlobalGapScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val clampedScale = globalGapScale.coerceIn(MIN_GLOBAL_GAP_SCALE, MAX_GLOBAL_GAP_SCALE)
    val latestScale by rememberUpdatedState(clampedScale)
    val latestOnChange by rememberUpdatedState(onGlobalGapScaleChange)
    val view = LocalView.current

    val shape = RoundedCornerShape(8.dp)
    val percentText = "${(clampedScale * 100f).roundToInt()}%"
    val deltaText = formatCompressedDelta(compressedDeltaMs)

    Column(
        modifier = modifier
            .clip(shape)
            .background(ClipperDeckColors.ConsoleObsidian)
            .border(1.dp, ClipperDeckColors.GraphiteHairline, shape)
            .padding(vertical = 8.dp, horizontal = 6.dp)
            .testTag("gap_compression_rail")
            .semantics {
                contentDescription = "Gap compression rail $percentText"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = clampedScale,
                    range = MIN_GLOBAL_GAP_SCALE..MAX_GLOBAL_GAP_SCALE
                )
                setProgress { target ->
                    val clamped = target.coerceIn(MIN_GLOBAL_GAP_SCALE, MAX_GLOBAL_GAP_SCALE)
                    latestOnChange(clamped)
                    true
                }
            }
            .pointerInput(Unit) {
                var activeScale = 1.0f
                detectVerticalDragGestures(
                    onDragStart = {
                        activeScale = latestScale
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val trackH = size.height.toFloat().coerceAtLeast(1f)
                        val range = MAX_GLOBAL_GAP_SCALE - MIN_GLOBAL_GAP_SCALE
                        // Dragging down compresses gaps (1.0f -> 0.25f); dragging up expands.
                        activeScale = (activeScale - (dragAmount / trackH) * range)
                            .coerceIn(MIN_GLOBAL_GAP_SCALE, MAX_GLOBAL_GAP_SCALE)
                        latestOnChange(activeScale)
                    },
                    onDragEnd = {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = percentText,
            style = MonoBoldStyle.copy(
                fontSize = 12.sp,
                color = ClipperDeckColors.AcidVoltLime
            ),
            maxLines = 1,
            modifier = Modifier.testTag("gap_scale_percent")
        )

        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val padY = 10.dp.toPx()
            val usableH = (h - padY * 2f).coerceAtLeast(1f)
            val railWidth = 6.dp.toPx()
            val centerX = w / 2f

            // Recessed vertical rail track
            drawRoundRect(
                color = ClipperDeckColors.OledVoid,
                topLeft = Offset(centerX - railWidth / 2f, padY),
                size = Size(railWidth, usableH),
                cornerRadius = CornerRadius(railWidth / 2f, railWidth / 2f)
            )

            // Normalized thumb Y: top = 100% (1.0f), bottom = 25% (0.25f)
            val range = MAX_GLOBAL_GAP_SCALE - MIN_GLOBAL_GAP_SCALE
            val fractionFromTop = ((MAX_GLOBAL_GAP_SCALE - clampedScale) / range).coerceIn(0f, 1f)
            val thumbY = padY + fractionFromTop * usableH

            // Active compression fill from top (100%) down to current thumb
            drawRoundRect(
                color = ClipperDeckColors.BroadcastCobalt,
                topLeft = Offset(centerX - railWidth / 2f, padY),
                size = Size(railWidth, (thumbY - padY).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(railWidth / 2f, railWidth / 2f)
            )

            // Graduation ticks at 100%, 75%, 50%, 25%
            val stops = floatArrayOf(0f, 1f / 3f, 2f / 3f, 1f)
            for (stop in stops) {
                val y = padY + stop * usableH
                drawLine(
                    color = ClipperDeckColors.GraphiteHairline,
                    start = Offset(centerX + railWidth, y),
                    end = Offset(w - 2.dp.toPx(), y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Tactile broadcast fader thumb
            val thumbW = (w - 8.dp.toPx()).coerceAtLeast(18.dp.toPx())
            val thumbH = 14.dp.toPx()
            drawRoundRect(
                color = ClipperDeckColors.AcidVoltLime,
                topLeft = Offset(centerX - thumbW / 2f, thumbY - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
            drawLine(
                color = ClipperDeckColors.PureBlackInk,
                start = Offset(centerX - thumbW * 0.28f, thumbY),
                end = Offset(centerX + thumbW * 0.28f, thumbY),
                strokeWidth = 1.5.dp.toPx()
            )
        }

        Text(
            text = deltaText,
            style = MonoMediumStyle.copy(
                fontSize = 10.sp,
                color = if (compressedDeltaMs < 0L) {
                    ClipperDeckColors.BroadcastCobalt
                } else {
                    ClipperDeckColors.TelemetryMuted
                }
            ),
            maxLines = 1,
            modifier = Modifier.testTag("gap_duration_delta")
        )
    }
}

internal fun formatCompressedDelta(deltaMs: Long): String {
    if (deltaMs == 0L) return "0ms"
    val sign = if (deltaMs < 0L) "-" else "+"
    val mag = abs(deltaMs)
    return if (mag >= 1_000L) {
        "$sign${"%.2f".format(mag / 1000.0)}s"
    } else {
        "$sign${mag}ms"
    }
}
