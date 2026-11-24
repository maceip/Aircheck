package com.example.ui.draft

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.draft.AngleChip
import com.example.draft.VadAudioCaptureEngine
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
import com.example.ui.theme.TelemetryMuted
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private val SeedProseStyle = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 20.sp,
    lineHeight = 32.sp,
    color = StudioCrisp
)

private val SeedMonoLabelStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontFeatureSettings = "tnum"
)

/**
 * Stage 1 (`01 SEED`) Eye Zone: streaming plain-text voice seed buffer (20sp / 32sp),
 * optional explicit `[Raw Kbd]` plain BasicTextField, and quiet bottom steering constraint chips.
 */
@Composable
fun SeedEyeZone(
    seedTranscript: String,
    steeringConstraints: List<String>,
    rawKeyboardMode: Boolean,
    isRecordingSeed: Boolean,
    isRecordingSteer: Boolean,
    recordingElapsedMs: Long,
    liveRmsLevel: Float,
    isBusy: Boolean,
    busyLabel: String?,
    onUpdateSeedTranscript: (String) -> Unit,
    onCommitRawSeed: (String) -> Unit,
    onToggleRawKeyboardMode: () -> Unit,
    onRemoveSteeringConstraint: (Int) -> Unit,
    onRefreshAngles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(rawKeyboardMode) {
        if (rawKeyboardMode) {
            runCatching { focusRequester.requestFocus() }
            keyboardController?.show()
        } else {
            keyboardController?.hide()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Quiet top strip: live recording/busy status on left, Raw Kbd toggle on right (max 2 labels)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val statusText = when {
                isRecordingSeed -> "REC ${formatElapsedClock(recordingElapsedMs)}"
                isRecordingSteer -> "STEERING ${formatElapsedClock(recordingElapsedMs)}"
                isBusy && !busyLabel.isNullOrBlank() -> busyLabel
                else -> ""
            }
            val statusColor = when {
                isRecordingSeed -> OnAirCrimson
                isRecordingSteer -> BroadcastCobalt
                else -> TelemetryMuted
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecordingSeed || isRecordingSteer) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        style = SeedMonoLabelStyle,
                        color = statusColor,
                        maxLines = 1
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!rawKeyboardMode && seedTranscript.isNotBlank() && !isBusy && !isRecordingSeed) {
                    Text(
                        text = "Refresh Angles",
                        style = SeedMonoLabelStyle,
                        color = TelemetryMuted,
                        modifier = Modifier
                            .testTag("seed_refresh_angles_button")
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, GraphiteHairline, RoundedCornerShape(4.dp))
                            .clickable(role = Role.Button) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onRefreshAngles()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (rawKeyboardMode) {
                    Text(
                        text = "Done",
                        style = SeedMonoLabelStyle.copy(fontWeight = FontWeight.Bold),
                        color = PureBlackInk,
                        modifier = Modifier
                            .testTag("seed_raw_kbd_toggle")
                            .clip(RoundedCornerShape(4.dp))
                            .background(AcidVoltLime)
                            .border(1.dp, AcidVoltLime, RoundedCornerShape(4.dp))
                            .clickable(role = Role.Button) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onCommitRawSeed(seedTranscript)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Streaming plain-text seed buffer (or BasicTextField when rawKeyboardMode == true)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (rawKeyboardMode) {
                val currentSeedText by rememberUpdatedState(seedTranscript)
                val currentUpdateSeed by rememberUpdatedState(onUpdateSeedTranscript)
                val textFieldState = rememberTextFieldState(initialText = seedTranscript)

                LaunchedEffect(seedTranscript) {
                    if (textFieldState.text.toString() != seedTranscript) {
                        textFieldState.setTextAndPlaceCursorAtEnd(seedTranscript)
                    }
                }

                LaunchedEffect(textFieldState) {
                    snapshotFlow { textFieldState.text.toString() }
                        .distinctUntilChanged()
                        .collect { latest ->
                            if (latest != currentSeedText) {
                                currentUpdateSeed(latest)
                            }
                        }
                }

                BasicTextField(
                    state = textFieldState,
                    textStyle = SeedProseStyle,
                    cursorBrush = SolidColor(AcidVoltLime),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .testTag("seed_raw_input"),
                    decorator = TextFieldDecorator { innerTextField ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (textFieldState.text.isEmpty()) {
                                Text(
                                    text = "Type a raw thought or voice note...",
                                    style = SeedProseStyle,
                                    color = TelemetryMuted.copy(alpha = 0.65f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (seedTranscript.isBlank()) {
                        Text(
                            text = if (isRecordingSeed) {
                                "Listening... pause briefly to stream your thought."
                            } else {
                                "Tap Latch Mic to think out loud, or Raw Kbd to type."
                            },
                            style = SeedProseStyle,
                            color = TelemetryMuted.copy(alpha = 0.7f),
                            modifier = Modifier.testTag("seed_transcript_display")
                        )
                    } else {
                        Text(
                            text = seedTranscript,
                            style = SeedProseStyle,
                            color = StudioCrisp,
                            modifier = Modifier.testTag("seed_transcript_display")
                        )
                    }
                }
            }
        }

        // Real hardware RMS level bar during live mic capture (strictly real AudioRecord RMS)
        if (isRecordingSeed || isRecordingSteer) {
            val normalizedLevel = (liveRmsLevel * 4f).coerceIn(0.02f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(2.dp)
                    .background(GraphiteHairline)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(normalizedLevel)
                        .fillMaxHeight()
                        .background(if (isRecordingSeed) OnAirCrimson else BroadcastCobalt)
                )
            }
        }

        // Active steering constraint chips shown quietly at the bottom edge of the Eye Zone
        if (steeringConstraints.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("seed_steering_constraints_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                steeringConstraints.forEachIndexed { idx, constraint ->
                    Row(
                        modifier = Modifier
                            .testTag("steering_constraint_$idx")
                            .clip(RoundedCornerShape(6.dp))
                            .background(BroadcastCobaltDim)
                            .border(1.dp, BroadcastCobalt.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onRemoveSteeringConstraint(idx)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = constraint,
                            style = SeedMonoLabelStyle,
                            color = StudioCrisp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove steering constraint",
                            tint = TelemetryMuted,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stage 1 (`01 SEED`) Thumb Zone:
 * - Row A: Extracted Angles (ON by default)
 * - Row B: Blindspots & Counter-Angles (OFF by default)
 * - Continuous Thumb-Sweep Chip Selection + 200ms Long-Press Preview Callout
 * - Bottom 64dp Action Bar: `[ Latch Mic ]`, conditional `[ Distill Last 15s ]` (>= 20s),
 *   momentary `[ Hold to Steer ]`, and primary Acid Volt Lime `[ Synthesize 3 -> ]`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeedThumbZone(
    extractedAngles: List<AngleChip>,
    blindspotAngles: List<AngleChip>,
    canSynthesize: Boolean,
    isRecordingSeed: Boolean,
    isRecordingSteer: Boolean,
    recordingElapsedMs: Long,
    isBusy: Boolean,
    onToggleAngleChip: (String) -> Unit,
    onToggleLatchMic: () -> Unit,
    onDistillLast15Seconds: () -> Unit,
    onStartSteerClutch: () -> Unit,
    onStopSteerClutch: () -> Unit,
    onSynthesizeThree: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val currentAllChips by rememberUpdatedState(extractedAngles + blindspotAngles)
    val currentToggleChip by rememberUpdatedState(onToggleAngleChip)
    val currentStartSteer by rememberUpdatedState(onStartSteerClutch)
    val currentStopSteer by rememberUpdatedState(onStopSteerClutch)

    val chipCoords = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    var containerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var previewChip by remember { mutableStateOf<AngleChip?>(null) }
    var previewAnchorPx by remember { mutableStateOf(Offset.Zero) }

    val sweepSlopPx = with(density) { 10.dp.toPx() }
    val calloutLiftPx = with(density) { 46.dp.toPx() }

    // Clean out coordinates for removed chips when angles refresh
    LaunchedEffect(extractedAngles, blindspotAngles) {
        val validIds = (extractedAngles + blindspotAngles).map { it.id }.toSet()
        chipCoords.keys.retainAll(validIds)
        if (previewChip != null && previewChip?.id !in validIds) {
            previewChip = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OledVoid)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Angle Matrix with continuous Thumb-Sweep selection + 200ms Long-Press Preview Callout
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords -> containerCoords = coords }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPos = down.position

                        fun chipAt(pos: Offset): AngleChip? {
                            val parent = containerCoords?.takeIf { it.isAttached } ?: return null
                            val hitId = chipCoords.entries.firstOrNull { (_, coords) ->
                                coords.isAttached &&
                                    parent.localBoundingBoxOf(coords, clipBounds = false).contains(pos)
                            }?.key ?: return null
                            return currentAllChips.firstOrNull { it.id == hitId }
                        }

                        val downChip = chipAt(downPos)
                        var isSweeping = false
                        var inLongPressPreview = false
                        val crossedIds = HashSet<String>()

                        var longPressJob: Job? = if (downChip != null) {
                            coroutineScope.launch {
                                delay(200L)
                                if (!isSweeping) {
                                    inLongPressPreview = true
                                    previewChip = downChip
                                    previewAnchorPx = downPos
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                }
                            }
                        } else {
                            null
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    longPressJob?.cancel()
                                    if (inLongPressPreview) {
                                        previewChip = null
                                    } else if (!isSweeping && downChip != null) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        currentToggleChip(downChip.id)
                                    }
                                    break
                                }

                                val pos = change.position
                                val movedDist = hypot(pos.x - downPos.x, pos.y - downPos.y)
                                if (!isSweeping && movedDist >= sweepSlopPx) {
                                    longPressJob?.cancel()
                                    inLongPressPreview = false
                                    previewChip = null
                                    isSweeping = true
                                    if (downChip != null && crossedIds.add(downChip.id)) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        currentToggleChip(downChip.id)
                                    }
                                }

                                if (isSweeping) {
                                    change.consume()
                                    val hit = chipAt(pos)
                                    if (hit != null && crossedIds.add(hit.id)) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        currentToggleChip(hit.id)
                                    }
                                }
                            }
                        } finally {
                            longPressJob?.cancel()
                            previewChip = null
                        }
                    }
                }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (extractedAngles.isEmpty() && blindspotAngles.isEmpty()) {
                    Text(
                        text = "Voice seed extracts claims and structural blindspots here.",
                        style = SeedMonoLabelStyle,
                        color = TelemetryMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    // Row A — Extracted Angles (ON by default)
                    if (extractedAngles.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "EXTRACTED ANGLES",
                                style = SeedMonoLabelStyle,
                                color = TelemetryMuted
                            )
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("seed_extracted_angles_row"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                extractedAngles.forEach { chip ->
                                    AngleToggleChipView(
                                        chip = chip,
                                        isBlindspot = false,
                                        onToggle = { onToggleAngleChip(chip.id) },
                                        onCoordinatesMeasured = { coords -> chipCoords[chip.id] = coords }
                                    )
                                }
                            }
                        }
                    }

                    // Row B — Blindspots & Counter-Angles (OFF by default)
                    if (blindspotAngles.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "BLINDSPOTS & COUNTERS",
                                style = SeedMonoLabelStyle,
                                color = TelemetryMuted
                            )
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("seed_blindspot_angles_row"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                blindspotAngles.forEach { chip ->
                                    AngleToggleChipView(
                                        chip = chip,
                                        isBlindspot = true,
                                        onToggle = { onToggleAngleChip(chip.id) },
                                        onCoordinatesMeasured = { coords -> chipCoords[chip.id] = coords }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 200ms Long-Press Floating 1-Line Preview Callout above finger
            val activePreview = previewChip
            if (activePreview != null) {
                val yOffset = (previewAnchorPx.y - calloutLiftPx).roundToInt().coerceAtLeast(0)
                Box(
                    modifier = Modifier
                        .zIndex(10f)
                        .fillMaxWidth()
                        .offset { IntOffset(0, yOffset) }
                        .testTag("angle_preview_callout")
                        .clip(RoundedCornerShape(8.dp))
                        .background(ConsoleElevated)
                        .border(
                            1.dp,
                            if (activePreview.isBlindspot) BroadcastCobalt else AcidVoltLime,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = activePreview.preview,
                        style = TextStyle(
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        color = StudioCrisp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Bottom 64dp Action Bar
        val showDistillLast15 = recordingElapsedMs >= VadAudioCaptureEngine.DISTILL_UNLOCK_MS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [ Latch Mic ]
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("seed_latch_mic_button")
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isRecordingSeed) OnAirCrimson else ConsoleElevated)
                    .border(
                        1.dp,
                        if (isRecordingSeed) OnAirCrimson else GraphiteHairline,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = !isRecordingSteer, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onToggleLatchMic()
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isRecordingSeed) "Stop Mic" else "Latch Mic",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    ),
                    color = StudioCrisp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // [ Distill Last 15s ] — ONLY appears once recordingElapsedMs >= 20_000L
            if (showDistillLast15) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("seed_distill_15s_button")
                        .clip(RoundedCornerShape(8.dp))
                        .background(ConsoleElevated)
                        .border(1.dp, EvidenceAmber, RoundedCornerShape(8.dp))
                        .clickable(enabled = !isBusy, role = Role.Button) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onDistillLast15Seconds()
                        }
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Distill Last 15s",
                        style = TextStyle(
                            fontFamily = SpaceGroteskFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        ),
                        color = EvidenceAmber,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // [ Hold to Steer ] — momentary press-and-hold clutch mic
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("seed_hold_steer_button")
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isRecordingSteer) BroadcastCobalt else ConsoleElevated)
                    .border(
                        1.dp,
                        if (isRecordingSteer) BroadcastCobalt else GraphiteHairline,
                        RoundedCornerShape(8.dp)
                    )
                    .pointerInput(isRecordingSeed) {
                        if (!isRecordingSeed) {
                            detectTapGestures(
                                onPress = {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    currentStartSteer()
                                    try {
                                        tryAwaitRelease()
                                    } finally {
                                        currentStopSteer()
                                    }
                                }
                            )
                        }
                    }
                    .semantics {
                        role = Role.Button
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isRecordingSteer) "Steering..." else "Hold to Steer",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    ),
                    color = StudioCrisp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // [ Synthesize 3 -> ] — primary Acid Volt Lime commit CTA
            val synthesizeEnabled = canSynthesize && !isBusy && !isRecordingSteer
            Box(
                modifier = Modifier
                    .weight(if (showDistillLast15) 1.05f else 1.25f)
                    .height(52.dp)
                    .testTag("seed_synthesize_button")
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (synthesizeEnabled) AcidVoltLime else ConsoleObsidian)
                    .border(
                        1.dp,
                        if (synthesizeEnabled) AcidVoltLime else GraphiteHairline,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = synthesizeEnabled, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onSynthesizeThree()
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Synthesize 3 ->",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        lineHeight = 15.sp
                    ),
                    color = if (synthesizeEnabled) PureBlackInk else TelemetryMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AngleToggleChipView(
    chip: AngleChip,
    isBlindspot: Boolean,
    onToggle: () -> Unit,
    onCoordinatesMeasured: (LayoutCoordinates) -> Unit
) {
    val activeFill = if (isBlindspot) BroadcastCobaltDim else AcidVoltLimeDim
    val activeBorder = if (isBlindspot) BroadcastCobalt else AcidVoltLime
    val activeInk = if (isBlindspot) StudioCrisp else AcidVoltLime

    Box(
        modifier = Modifier
            .testTag("angle_chip_${chip.id}")
            .onGloballyPositioned { coords -> onCoordinatesMeasured(coords) }
            .clip(RoundedCornerShape(6.dp))
            .background(if (chip.selected) activeFill else ConsoleObsidian)
            .border(
                width = 1.dp,
                color = if (chip.selected) activeBorder else GraphiteHairline,
                shape = RoundedCornerShape(6.dp)
            )
            .semantics {
                role = Role.Checkbox
                selected = chip.selected
                onClick {
                    onToggle()
                    true
                }
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = chip.label,
            style = TextStyle(
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = if (chip.selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            color = if (chip.selected) activeInk else TelemetryMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatElapsedClock(elapsedMs: Long): String {
    val totalSecs = (elapsedMs / 1000L).coerceAtLeast(0L)
    val mins = totalSecs / 60L
    val secs = totalSecs % 60L
    return "%02d:%02d".format(mins, secs)
}
