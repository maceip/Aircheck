package com.example.ui.draft

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.draft.CandidateDraft
import com.example.draft.ClauseNode
import com.example.draft.LockedPhrase
import com.example.ui.theme.AcidVoltLime
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.ConsoleObsidian
import com.example.ui.theme.GraphiteHairline
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted

/**
 * Stage 3 (`03 CUT`) Thumb Zone instrument bay.
 *
 * Houses:
 * 1. Focused Clause Version Ring Trackpad (vertical flick up/down + `<` / `>` + `•••` pagination)
 * 2. Salvage Tray (Stage 2 pinned phrases spliced on tap into the focused clause or gap)
 * 3. Global Time-Travel Undo/Redo Scrubber Bar (`candidate.snapshotCursor` across `globalSnapshots`)
 * 4. Primary `[ Hold to Splice Voice ]` press-and-hold clutch + `[ -> 04 SHIP ]` CTA
 */
@Composable
fun CutThumbInstrumentBay(
    candidate: CandidateDraft,
    focusedClauseId: String?,
    focusedGapIndex: Int?,
    salvagedPhrases: List<LockedPhrase>,
    isVoiceSplicing: Boolean,
    isBusy: Boolean,
    onStepClauseVersion: (String, Int) -> Unit,
    onSpliceSalvagePhrase: (LockedPhrase) -> Unit,
    onStepGlobalSnapshot: (Int) -> Unit,
    onStartVoiceSplice: () -> Unit,
    onStopVoiceSplice: () -> Unit,
    onAdvanceToShip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusedClause = remember(candidate.clauses, focusedClauseId) {
        candidate.clauses.firstOrNull { it.id == focusedClauseId }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OledVoid)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Focused Clause Version Ring Trackpad (or Insert Gap indicator when a gap is focused)
        if (focusedClause != null) {
            ClauseVersionRingTrackpad(
                clause = focusedClause,
                isBusy = isBusy,
                onStepClauseVersion = onStepClauseVersion
            )
        } else if (focusedGapIndex != null) {
            FocusedGapIndicatorRow(gapIndex = focusedGapIndex)
        }

        // 2. Salvage Tray (Pinned Phrases from Stage 2)
        if (salvagedPhrases.isNotEmpty()) {
            CutSalvageTrayRow(
                salvagedPhrases = salvagedPhrases,
                enabled = !isBusy,
                onSpliceSalvagePhrase = onSpliceSalvagePhrase
            )
        }

        // 3. Global Time-Travel Undo/Redo Scrubber Bar
        GlobalHistoryScrubberBar(
            candidate = candidate,
            enabled = !isBusy,
            onStepGlobalSnapshot = onStepGlobalSnapshot
        )

        // 4. Primary Voice Splice Clutch Button + [ -> 04 SHIP ]
        CutPrimaryActionRow(
            isVoiceSplicing = isVoiceSplicing,
            isBusy = isBusy,
            canAdvanceToShip = candidate.clauses.isNotEmpty() && !isBusy && !isVoiceSplicing,
            onStartVoiceSplice = onStartVoiceSplice,
            onStopVoiceSplice = onStopVoiceSplice,
            onAdvanceToShip = onAdvanceToShip
        )
    }
}

@Composable
private fun ClauseVersionRingTrackpad(
    clause: ClauseNode,
    isBusy: Boolean,
    onStepClauseVersion: (String, Int) -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val accent = LocalAircheckTokens.current.channelAccent

    val currentClause by rememberUpdatedState(clause)
    val currentIsBusy by rememberUpdatedState(isBusy)
    val currentOnStep by rememberUpdatedState(onStepClauseVersion)

    val versionCount = clause.versions.size.coerceAtLeast(1)
    val activeIdx = clause.activeVersionIndex.coerceIn(0, (versionCount - 1).coerceAtLeast(0))
    val stepThresholdPx = with(density) { 22.dp.toPx() }
    var accumulatedDy by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ConsoleObsidian)
            .border(
                width = 1.dp,
                color = if (versionCount > 1) accent.copy(alpha = 0.48f) else GraphiteHairline,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 4.dp)
            .testTag("clause_version_ring_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Compact `<` backward tap fallback
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (clause.canStepBackward && !isBusy) ConsoleElevated else ConsoleObsidian)
                .clickable(
                    enabled = clause.canStepBackward && !isBusy,
                    role = Role.Button
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onStepClauseVersion(clause.id, -1)
                }
                .semantics { contentDescription = "Previous clause version" }
                .testTag("clause_ring_prev"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "<",
                style = TextStyle(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFeatureSettings = "tnum"
                ),
                color = if (clause.canStepBackward && !isBusy) StudioCrisp else TelemetryMuted.copy(alpha = 0.35f)
            )
        }

        // Vertical flick trackpad center + `•••` pagination dots
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { accumulatedDy = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            if (currentIsBusy || currentClause.versions.size <= 1) return@detectVerticalDragGestures
                            change.consume()
                            accumulatedDy += dragAmount
                            // Vertical flick UP -> step backward (-1); flick DOWN -> step forward (+1)
                            if (accumulatedDy <= -stepThresholdPx) {
                                accumulatedDy = 0f
                                if (currentClause.canStepBackward) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    currentOnStep(currentClause.id, -1)
                                }
                            } else if (accumulatedDy >= stepThresholdPx) {
                                accumulatedDy = 0f
                                if (currentClause.canStepForward) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    currentOnStep(currentClause.id, +1)
                                }
                            }
                        },
                        onDragEnd = { accumulatedDy = 0f },
                        onDragCancel = { accumulatedDy = 0f }
                    )
                }
                .semantics {
                    contentDescription = "Clause version ring trackpad"
                    stateDescription = "Version ${activeIdx + 1} of $versionCount"
                }
                .testTag("clause_ring_trackpad"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "v${activeIdx + 1}/$versionCount",
                style = TextStyle(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    fontFeatureSettings = "tnum"
                ),
                color = if (versionCount > 1) StudioCrisp else TelemetryMuted,
                maxLines = 1
            )
            if (versionCount > 1) {
                Spacer(modifier = Modifier.width(10.dp))
                ClauseVersionDots(
                    total = versionCount,
                    activeIndex = activeIdx,
                    accent = accent
                )
            }
        }

        // Compact `>` forward tap fallback
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (clause.canStepForward && !isBusy) ConsoleElevated else ConsoleObsidian)
                .clickable(
                    enabled = clause.canStepForward && !isBusy,
                    role = Role.Button
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onStepClauseVersion(clause.id, +1)
                }
                .semantics { contentDescription = "Next clause version" }
                .testTag("clause_ring_next"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = ">",
                style = TextStyle(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFeatureSettings = "tnum"
                ),
                color = if (clause.canStepForward && !isBusy) StudioCrisp else TelemetryMuted.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
private fun ClauseVersionDots(
    total: Int,
    activeIndex: Int,
    accent: androidx.compose.ui.graphics.Color
) {
    val visibleCount = total.coerceAtMost(7)
    val startWindow = if (total <= 7) 0 else (activeIndex - 3).coerceIn(0, total - visibleCount)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("clause_ring_dots")
    ) {
        for (i in 0 until visibleCount) {
            val idx = startWindow + i
            val isActive = idx == activeIndex
            Box(
                modifier = Modifier
                    .size(if (isActive) 6.dp else 4.dp)
                    .clip(CircleShape)
                    .background(if (isActive) accent else TelemetryMuted.copy(alpha = 0.45f))
            )
        }
    }
}

@Composable
private fun FocusedGapIndicatorRow(gapIndex: Int) {
    val accent = LocalAircheckTokens.current.channelAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ConsoleObsidian)
            .border(1.dp, accent.copy(alpha = 0.50f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp)
            .testTag("cut_focused_gap_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "INSERT GAP ${gapIndex + 1}",
            style = TextStyle(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                fontFeatureSettings = "tnum"
            ),
            color = accent,
            maxLines = 1
        )
        Text(
            text = "Tap pin or hold voice",
            style = TextStyle(
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            ),
            color = TelemetryMuted,
            maxLines = 1
        )
    }
}

@Composable
private fun CutSalvageTrayRow(
    salvagedPhrases: List<LockedPhrase>,
    enabled: Boolean,
    onSpliceSalvagePhrase: (LockedPhrase) -> Unit
) {
    val view = LocalView.current

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cut_salvage_tray"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(items = salvagedPhrases, key = { it.id }) { phrase ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ConsoleElevated)
                    .border(1.dp, GraphiteHairline, RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSpliceSalvagePhrase(phrase)
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .widthIn(max = 220.dp)
                    .testTag("cut_salvage_chip_${phrase.id}"),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = phrase.text,
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        lineHeight = 16.sp
                    ),
                    color = if (enabled) StudioCrisp else TelemetryMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.drawBehind {
                        val underlineY = size.height + 1.dp.toPx()
                        drawLine(
                            color = if (enabled) StudioCrisp else TelemetryMuted,
                            start = Offset(0f, underlineY),
                            end = Offset(size.width, underlineY),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun GlobalHistoryScrubberBar(
    candidate: CandidateDraft,
    enabled: Boolean,
    onStepGlobalSnapshot: (Int) -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val accent = LocalAircheckTokens.current.channelAccent

    val currentCandidate by rememberUpdatedState(candidate)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnStep by rememberUpdatedState(onStepGlobalSnapshot)

    val total = candidate.globalSnapshots.size.coerceAtLeast(1)
    val cursor = candidate.snapshotCursor.coerceIn(0, (total - 1).coerceAtLeast(0))
    val stepPx = with(density) { 24.dp.toPx() }
    var dragAccumX by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ConsoleObsidian)
            .border(1.dp, GraphiteHairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp)
            .testTag("global_history_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // < Undo button
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (candidate.canUndoGlobal && enabled) ConsoleElevated else ConsoleObsidian)
                .clickable(
                    enabled = candidate.canUndoGlobal && enabled,
                    role = Role.Button
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onStepGlobalSnapshot(-1)
                }
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = "Global undo" }
                .testTag("global_undo_button"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "< Undo",
                style = TextStyle(
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                ),
                color = if (candidate.canUndoGlobal && enabled) StudioCrisp else TelemetryMuted.copy(alpha = 0.38f),
                maxLines = 1
            )
        }

        // Interactive horizontal timeline scrub rail
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccumX = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            if (!currentEnabled || currentCandidate.globalSnapshots.size <= 1) {
                                return@detectHorizontalDragGestures
                            }
                            change.consume()
                            dragAccumX += dragAmount
                            if (dragAccumX <= -stepPx) {
                                dragAccumX = 0f
                                if (currentCandidate.canUndoGlobal) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    currentOnStep(-1)
                                }
                            } else if (dragAccumX >= stepPx) {
                                dragAccumX = 0f
                                if (currentCandidate.canRedoGlobal) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    currentOnStep(+1)
                                }
                            }
                        },
                        onDragEnd = { dragAccumX = 0f },
                        onDragCancel = { dragAccumX = 0f }
                    )
                }
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
            ) {
                val midY = size.height / 2f
                val inset = 6.dp.toPx()
                val trackWidth = (size.width - inset * 2f).coerceAtLeast(1f)

                drawLine(
                    color = GraphiteHairline,
                    start = Offset(inset, midY),
                    end = Offset(size.width - inset, midY),
                    strokeWidth = 1.dp.toPx()
                )

                val progress = if (total <= 1) 1f else cursor.toFloat() / (total - 1).toFloat()
                val headX = inset + trackWidth * progress

                drawLine(
                    color = accent.copy(alpha = 0.65f),
                    start = Offset(inset, midY),
                    end = Offset(headX, midY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Draw snapshot pips when count is compact
                val pipCount = total.coerceAtMost(12)
                for (i in 0 until pipCount) {
                    val frac = if (pipCount <= 1) 1f else i.toFloat() / (pipCount - 1).toFloat()
                    val pipX = inset + trackWidth * frac
                    drawCircle(
                        color = if (pipX <= headX + 1f) accent.copy(alpha = 0.7f) else GraphiteHairline,
                        radius = 1.5.dp.toPx(),
                        center = Offset(pipX, midY)
                    )
                }

                // Active cursor head
                drawCircle(
                    color = accent,
                    radius = 4.dp.toPx(),
                    center = Offset(headX, midY)
                )
            }

            Text(
                text = "${cursor + 1}/$total",
                style = TextStyle(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                    fontFeatureSettings = "tnum"
                ),
                color = TelemetryMuted,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 1.dp),
                maxLines = 1
            )
        }

        // Redo > button
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (candidate.canRedoGlobal && enabled) ConsoleElevated else ConsoleObsidian)
                .clickable(
                    enabled = candidate.canRedoGlobal && enabled,
                    role = Role.Button
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onStepGlobalSnapshot(+1)
                }
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = "Global redo" }
                .testTag("global_redo_button"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Redo >",
                style = TextStyle(
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                ),
                color = if (candidate.canRedoGlobal && enabled) StudioCrisp else TelemetryMuted.copy(alpha = 0.38f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CutPrimaryActionRow(
    isVoiceSplicing: Boolean,
    isBusy: Boolean,
    canAdvanceToShip: Boolean,
    onStartVoiceSplice: () -> Unit,
    onStopVoiceSplice: () -> Unit,
    onAdvanceToShip: () -> Unit
) {
    val view = LocalView.current
    val accent = LocalAircheckTokens.current.channelAccent

    val currentIsBusy by rememberUpdatedState(isBusy)
    val currentIsVoiceSplicing by rememberUpdatedState(isVoiceSplicing)
    val currentOnStart by rememberUpdatedState(onStartVoiceSplice)
    val currentOnStop by rememberUpdatedState(onStopVoiceSplice)

    var isLocallyHeld by remember { mutableStateOf(false) }
    val activeListening = isVoiceSplicing || isLocallyHeld

    val listeningPulse = if (activeListening) {
        val transition = rememberInfiniteTransition(label = "voice_splice_pulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 420, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "voice_splice_dot_alpha"
        ).value
    } else {
        1f
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Primary Voice Splice Clutch Button: [ Hold to Splice Voice ]
        Box(
            modifier = Modifier
                .weight(0.64f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (activeListening) {
                        OnAirCrimson.copy(alpha = 0.16f)
                    } else {
                        ConsoleElevated
                    }
                )
                .border(
                    width = if (activeListening) 1.5.dp else 1.dp,
                    color = when {
                        activeListening -> OnAirCrimson
                        isBusy -> GraphiteHairline
                        else -> accent.copy(alpha = 0.65f)
                    },
                    shape = RoundedCornerShape(10.dp)
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (currentIsBusy && !currentIsVoiceSplicing) return@awaitEachGesture
                        down.consume()
                        isLocallyHeld = true
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        currentOnStart()
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            isLocallyHeld = false
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            currentOnStop()
                        }
                    }
                }
                .semantics {
                    contentDescription = if (activeListening) {
                        "Listening for voice splice"
                    } else {
                        "Hold to splice voice"
                    }
                    stateDescription = if (activeListening) "Listening" else "Idle"
                }
                .testTag("cut_hold_splice_voice"),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (activeListening) {
                                OnAirCrimson.copy(alpha = listeningPulse)
                            } else if (isBusy) {
                                TelemetryMuted.copy(alpha = 0.45f)
                            } else {
                                accent
                            }
                        )
                )
                Text(
                    text = if (activeListening) "Listening..." else "Hold to Splice Voice",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        lineHeight = 18.sp
                    ),
                    color = when {
                        activeListening -> StudioCrisp
                        isBusy -> TelemetryMuted
                        else -> StudioCrisp
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Compact [ -> 04 SHIP ] CTA button
        Box(
            modifier = Modifier
                .weight(0.36f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(if (canAdvanceToShip) AcidVoltLime else ConsoleElevated)
                .border(
                    width = 1.dp,
                    color = if (canAdvanceToShip) AcidVoltLime else GraphiteHairline,
                    shape = RoundedCornerShape(10.dp)
                )
                .clickable(enabled = canAdvanceToShip, role = Role.Button) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onAdvanceToShip()
                }
                .semantics { contentDescription = "Advance to 04 SHIP" }
                .testTag("cut_to_ship_button"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "-> 04 SHIP",
                style = TextStyle(
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                ),
                color = if (canAdvanceToShip) OledVoid else TelemetryMuted,
                maxLines = 1
            )
        }
    }
}
