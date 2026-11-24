package com.example.ui.draft

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.draft.CandidateDraft
import com.example.draft.DraftStateReducer
import com.example.draft.LockedPhrase
import com.example.ui.theme.AcidVoltLime
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.ConsoleObsidian
import com.example.ui.theme.EvidenceAmber
import com.example.ui.theme.GraphiteHairline
import com.example.ui.theme.HazardSignalOrange
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.PureBlackInk
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryMuted
import kotlinx.coroutines.flow.distinctUntilChanged

private val PickProseStyle = TextStyle(
    fontFamily = SpaceGroteskFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 19.sp,
    lineHeight = 29.sp,
    color = StudioCrisp
)

private val PickMonoStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontFeatureSettings = "tnum"
)

/**
 * Stage 2 (`02 PICK`) Eye Zone:
 * - HorizontalPager with 88% card width and 12dp gap so adjacent cards peek cleanly.
 * - Posture header (`01 DIRECT`, `02 RECEIPT`, `03 CONTRARIAN`) + exact char count.
 * - Diff-Dimming via [DraftStateReducer.computeDiffDimMask]: shared n-grams at 60% opacity,
 *   unique phrasing at 100% opacity.
 * - Single-tap any clause to salvage it into `salvagedPhrases` with Verbatim Lock.
 * - Vertical Swipe Down discards & regenerates ONLY that slot.
 * - Vertical Swipe Up promotes active card into Stage 3 (`03 CUT`).
 */
@Composable
fun PickEyeZone(
    candidates: List<CandidateDraft>,
    activeCandidateIndex: Int,
    salvagedPhrases: List<LockedPhrase>,
    isBusy: Boolean,
    busyLabel: String?,
    onSelectCandidateSlot: (Int) -> Unit,
    onSalvageClause: (candidateIdx: Int, clauseId: String) -> Unit,
    onKillAndRegenSlot: (slotIdx: Int) -> Unit,
    onCommitToCut: (slotIdx: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentActiveIdx by rememberUpdatedState(activeCandidateIndex)
    val currentSelectSlot by rememberUpdatedState(onSelectCandidateSlot)

    if (candidates.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(OledVoid)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isBusy && !busyLabel.isNullOrBlank()) busyLabel else "No synthesized drafts yet.",
                style = PickProseStyle,
                color = TelemetryMuted
            )
        }
        return
    }

    val diffMasks = remember(candidates) { DraftStateReducer.computeDiffDimMask(candidates) }
    val salvagedTextsLower = remember(salvagedPhrases) {
        salvagedPhrases.map { it.text.trim().lowercase() }.toSet()
    }

    val pagerState = rememberPagerState(
        initialPage = activeCandidateIndex.coerceIn(0, candidates.lastIndex),
        pageCount = { candidates.size }
    )

    LaunchedEffect(activeCandidateIndex, candidates.size) {
        val target = activeCandidateIndex.coerceIn(0, candidates.lastIndex)
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (page in candidates.indices && page != currentActiveIdx) {
                    currentSelectSlot(page)
                }
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid)
    ) {
        // 88% card width => 6% horizontal peek padding on each side, 12dp inter-card spacing
        val horizontalPeekPadding = (maxWidth * 0.06f).coerceAtLeast(10.dp)

        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = horizontalPeekPadding, vertical = 10.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxSize()
                .testTag("pick_candidate_pager")
        ) { pageIdx ->
            val candidate = candidates[pageIdx]
            val dimRanges = diffMasks.getOrElse(pageIdx) { emptySet() }

            PickCandidateCard(
                slotIndex = pageIdx,
                candidate = candidate,
                dimRanges = dimRanges,
                salvagedTextsLower = salvagedTextsLower,
                isActivePage = pageIdx == pagerState.currentPage,
                isBusy = isBusy,
                onSalvageClause = { clauseId -> onSalvageClause(pageIdx, clauseId) },
                onKillAndRegenSlot = { onKillAndRegenSlot(pageIdx) },
                onCommitToCut = { onCommitToCut(pageIdx) }
            )
        }
    }
}

@Composable
private fun PickCandidateCard(
    slotIndex: Int,
    candidate: CandidateDraft,
    dimRanges: Set<IntRange>,
    salvagedTextsLower: Set<String>,
    isActivePage: Boolean,
    isBusy: Boolean,
    onSalvageClause: (String) -> Unit,
    onKillAndRegenSlot: () -> Unit,
    onCommitToCut: () -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val triggerThresholdPx = with(density) { 80.dp.toPx() }

    val currentSalvage by rememberUpdatedState(onSalvageClause)
    val currentKill by rememberUpdatedState(onKillAndRegenSlot)
    val currentCommit by rememberUpdatedState(onCommitToCut)

    var rawDragY by remember { mutableFloatStateOf(0f) }
    val animatedDragY by animateFloatAsState(
        targetValue = rawDragY * 0.42f,
        animationSpec = spring(stiffness = 480f),
        label = "pick_card_drag_y"
    )

    val (fullText, flatTokens) = remember(candidate.clauses) {
        DraftStateReducer.flattenClauses(candidate.clauses)
    }

    val annotatedDraft: AnnotatedString = remember(fullText, candidate.clauses, dimRanges, salvagedTextsLower) {
        buildDiffDimmedAnnotatedDraft(
            fullText = fullText,
            candidate = candidate,
            dimRanges = dimRanges,
            salvagedTextsLower = salvagedTextsLower
        )
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val postureHeader = formatPostureHeader(slotIndex, candidate.postureLabel)
    val charCountColor = when {
        candidate.charCount > 280 -> OnAirCrimson
        candidate.charCount >= 265 -> EvidenceAmber
        else -> TelemetryMuted
    }

    val dragBorderColor = when {
        rawDragY <= -triggerThresholdPx -> AcidVoltLime
        rawDragY >= triggerThresholdPx -> HazardSignalOrange
        isActivePage -> AcidVoltLime.copy(alpha = 0.55f)
        else -> GraphiteHairline
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pick_card_$slotIndex")
            .graphicsLayer { translationY = animatedDragY }
            .clip(RoundedCornerShape(10.dp))
            .background(ConsoleObsidian)
            .border(1.dp, dragBorderColor, RoundedCornerShape(10.dp))
            .pointerInput(slotIndex, isBusy) {
                if (!isBusy) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            rawDragY = (rawDragY + dragAmount).coerceIn(-triggerThresholdPx * 1.8f, triggerThresholdPx * 1.8f)
                        },
                        onDragCancel = { rawDragY = 0f },
                        onDragEnd = {
                            val finalY = rawDragY
                            rawDragY = 0f
                            when {
                                finalY <= -triggerThresholdPx -> {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    currentCommit()
                                }
                                finalY >= triggerThresholdPx -> {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    currentKill()
                                }
                            }
                        }
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // Card Header: Posture tag (01 DIRECT / 02 RECEIPT / 03 CONTRARIAN) + exact char count
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = postureHeader,
                style = PickMonoStyle.copy(fontWeight = FontWeight.Bold),
                color = if (isActivePage) AcidVoltLime else StudioCrisp,
                maxLines = 1
            )
            Text(
                text = "${candidate.charCount} / 280",
                style = PickMonoStyle,
                color = charCountColor,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Main Diff-Dimmed Draft Body with Single-Tap Clause Salvage
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = annotatedDraft,
                style = PickProseStyle,
                onTextLayout = { layout -> textLayoutResult = layout },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pick_card_text_$slotIndex")
                    .pointerInput(candidate.clauses) {
                        detectTapGestures { tapOffset ->
                            val layout = textLayoutResult ?: return@detectTapGestures
                            if (fullText.isEmpty() || flatTokens.isEmpty()) return@detectTapGestures
                            val charOffset = layout.getOffsetForPosition(tapOffset)
                                .coerceIn(0, fullText.lastIndex)
                            val hitToken = flatTokens.firstOrNull { ft ->
                                charOffset in ft.textStart until ft.fullEndExclusive
                            } ?: flatTokens.lastOrNull()
                            if (hitToken != null) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                currentSalvage(hitToken.clauseId)
                            }
                        }
                    }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Single concise gesture cue / active swipe confirmation feedback
        val footerCue = when {
            rawDragY <= -triggerThresholdPx -> "Release to promote to 03 CUT"
            rawDragY >= triggerThresholdPx -> "Release to discard & regen v${slotIndex + 1}"
            else -> "Tap salvage · ↑ Cut · ↓ Regen"
        }
        val footerColor = when {
            rawDragY <= -triggerThresholdPx -> AcidVoltLime
            rawDragY >= triggerThresholdPx -> HazardSignalOrange
            else -> TelemetryMuted.copy(alpha = 0.75f)
        }
        Text(
            text = footerCue,
            style = PickMonoStyle,
            color = footerColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Stage 2 (`02 PICK`) Thumb Zone:
 * - Salvage Tray (`pick_salvage_tray`): horizontal strip holding pinned LockedPhrases with
 *   1dp baseline lock underline and tap-to-remove `×`.
 * - Commit Bar: `[ Regen All ]` (30% width) and `[ Lock & Cut -> ]` (70% width).
 */
@Composable
fun PickThumbZone(
    salvagedPhrases: List<LockedPhrase>,
    activeCandidateIndex: Int,
    canCommit: Boolean,
    isBusy: Boolean,
    onRemoveSalvagedPhrase: (String) -> Unit,
    onRegenAll: () -> Unit,
    onCommitToCut: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OledVoid)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Salvage Tray (Pinned Phrases)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (salvagedPhrases.isEmpty()) "SALVAGE TRAY" else "SALVAGE TRAY (${salvagedPhrases.size})",
                style = PickMonoStyle,
                color = TelemetryMuted
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("pick_salvage_tray"),
                contentAlignment = Alignment.CenterStart
            ) {
                if (salvagedPhrases.isEmpty()) {
                    Text(
                        text = "Tap any clause above to pin it with a Verbatim Lock.",
                        style = PickMonoStyle,
                        color = TelemetryMuted.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(salvagedPhrases, key = { it.id }) { phrase ->
                            Row(
                                modifier = Modifier
                                    .testTag("pick_salvage_chip_${phrase.id}")
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ConsoleElevated)
                                    .border(1.dp, GraphiteHairline, RoundedCornerShape(6.dp))
                                    .clickable(role = Role.Button) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        onRemoveSalvagedPhrase(phrase.id)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = phrase.text,
                                    style = TextStyle(
                                        fontFamily = SpaceGroteskFontFamily,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    ),
                                    color = StudioCrisp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .widthIn(max = 210.dp)
                                        .drawBehind {
                                            // 1dp Verbatim Lock baseline underline
                                            drawLine(
                                                color = AcidVoltLime,
                                                start = Offset(0f, size.height),
                                                end = Offset(size.width, size.height),
                                                strokeWidth = 1.dp.toPx()
                                            )
                                        }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove salvaged phrase",
                                    tint = TelemetryMuted,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .testTag("pick_salvage_remove_${phrase.id}")
                                )
                            }
                        }
                    }
                }
            }
        }

        // Commit Bar: [ Regen All ] (30%) and [ Lock & Cut -> ] (70%)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(0.30f)
                    .fillMaxHeight()
                    .testTag("pick_regen_all_button")
                    .clip(RoundedCornerShape(8.dp))
                    .background(ConsoleElevated)
                    .border(1.dp, GraphiteHairline, RoundedCornerShape(8.dp))
                    .clickable(enabled = !isBusy, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onRegenAll()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Regen All",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    color = if (!isBusy) StudioCrisp else TelemetryMuted,
                    maxLines = 1
                )
            }

            val commitEnabled = canCommit && !isBusy
            Box(
                modifier = Modifier
                    .weight(0.70f)
                    .fillMaxHeight()
                    .testTag("pick_lock_cut_button")
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (commitEnabled) AcidVoltLime else ConsoleObsidian)
                    .border(
                        1.dp,
                        if (commitEnabled) AcidVoltLime else GraphiteHairline,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = commitEnabled, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onCommitToCut(activeCandidateIndex)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Lock & Cut ->",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    color = if (commitEnabled) PureBlackInk else TelemetryMuted,
                    maxLines = 1
                )
            }
        }
    }
}

private fun formatPostureHeader(slotIndex: Int, rawPosture: String): String {
    val clean = rawPosture.trim().uppercase()
    val prefix = "0${slotIndex + 1}"
    return when {
        clean.isEmpty() -> when (slotIndex) {
            0 -> "01 DIRECT"
            1 -> "02 RECEIPT"
            else -> "03 CONTRARIAN"
        }
        clean.startsWith("$prefix ") -> clean
        else -> "$prefix $clean"
    }
}

private fun buildDiffDimmedAnnotatedDraft(
    fullText: String,
    candidate: CandidateDraft,
    dimRanges: Set<IntRange>,
    salvagedTextsLower: Set<String>
): AnnotatedString = buildAnnotatedString {
    append(fullText)
    // Default unique phrasing at 100% opacity
    addStyle(SpanStyle(color = StudioCrisp), 0, fullText.length)

    // Diff-Dimming: shared n-grams at 60% opacity
    for (range in dimRanges) {
        val start = range.first.coerceIn(0, fullText.length)
        val endExclusive = (range.last + 1).coerceIn(start, fullText.length)
        if (endExclusive > start) {
            addStyle(SpanStyle(color = StudioCrisp.copy(alpha = 0.60f)), start, endExclusive)
        }
    }

    // Subtle baseline lock indicator for any clause already salvaged
    if (salvagedTextsLower.isNotEmpty()) {
        val (_, flatTokens) = DraftStateReducer.flattenClauses(candidate.clauses)
        val clauseSpans = flatTokens.groupBy { it.clauseId }
        for (clause in candidate.clauses) {
            val clauseKey = DraftStateReducer.sanitizeAscii(clause.plainText()).trim().lowercase()
            if (clauseKey in salvagedTextsLower) {
                val toks = clauseSpans[clause.id].orEmpty()
                if (toks.isNotEmpty()) {
                    val start = toks.first().textStart.coerceIn(0, fullText.length)
                    val end = toks.last().textEndExclusive.coerceIn(start, fullText.length)
                    if (end > start) {
                        addStyle(
                            SpanStyle(
                                color = AcidVoltLime,
                                textDecoration = TextDecoration.Underline
                            ),
                            start,
                            end
                        )
                    }
                }
            }
        }
    }
}
