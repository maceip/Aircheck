package com.example.ui.draft

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.draft.CandidateDraft
import com.example.draft.ClauseNode
import com.example.draft.DraftStateReducer
import com.example.draft.XAttachment
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
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Floating 3-pill brush replacement strip anchored directly above a brushed word span.
 */
@Immutable
data class BrushMicroStripState(
    val charRange: IntRange,
    val alternatives: List<String>,
    val activeIndex: Int = 0,
    val anchorOffsetPx: Offset = Offset.Zero
)

/**
 * Stage 3 (`03 CUT`) Eye Zone tactile plain-text surgery surface.
 *
 * In normal gesture mode (`!rawKeyboardMode`), renders the draft as a single plain-text
 * paragraph backed by `TextLayoutResult` (never an `EditText`/`BasicTextField`) with:
 * - Verbatim lock styling (`StudioCrisp` 100% contrast + exact `1dp` baseline rule)
 * - Inter-sentence gap markers (`·`) for single-tap Insert Mode
 * - Solid `1.5dp` focused clause frame + compact `•••` version pagination dots
 * - Mutually exclusive `awaitEachGesture` state machine (Single Tap, Double Tap Lock,
 *   Slow Horizontal Scrub `BRUSH_SELECT`, Fast Leftward Flick `STRIKE_CUT`,
 *   Stationary Long-Press + Vertical Drag `SENTENCE_REORDER`, Two-Finger Horizontal Scrub)
 * - Transient 3-pill micro-strip anchored `36dp` above the brushed span
 * - 180ms replacement flash highlight (`flashRange`)
 * - Linked overflow thread split `HorizontalPager` (`1/2`, `2/2`)
 * - Compact `QuotePost` preview box underneath the draft when attached
 *
 * In explicit `[Raw Kbd]` mode (`rawKeyboardMode == true`), suspends custom `pointerInput`
 * and swaps `Text` for plain `BasicTextField` (`20sp` / `32sp`) with `[ Done & Lock ]`.
 */
@Composable
fun CutSurgeryEyeZone(
    candidate: CandidateDraft,
    focusedClauseId: String?,
    focusedGapIndex: Int?,
    rawKeyboardMode: Boolean,
    isBusy: Boolean,
    brushMicroStrip: BrushMicroStripState?,
    flashRange: IntRange?,
    activeAttachment: XAttachment?,
    onFocusClause: (String) -> Unit,
    onFocusGap: (Int) -> Unit,
    onToggleWordLock: (Int) -> Unit,
    onBrushSelectRelease: (IntRange) -> Unit,
    onSelectBrushAlternative: (Int) -> Unit,
    onDismissBrushStrip: () -> Unit,
    onStrikeCutClause: (String) -> Unit,
    onReorderClause: (Int, Int) -> Unit,
    onGlobalScrubUndo: (Int) -> Unit,
    onCommitRawKeyboardEdit: (String) -> Unit,
    onExitRawKeyboardMode: () -> Unit,
    onAiRewriteRaw: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accent = LocalAircheckTokens.current.channelAccent

    // 180ms highlight flash on newly replaced span
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flashRange) {
        if (flashRange != null && !flashRange.isEmpty()) {
            flashAlpha.snapTo(1f)
            flashAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 180, easing = LinearEasing)
            )
        } else {
            flashAlpha.snapTo(0f)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(OledVoid)
            .then(
                if (!rawKeyboardMode && brushMicroStrip != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { onDismissBrushStrip() }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (rawKeyboardMode) {
                CutRawKeyboardEditor(
                    initialPlainText = candidate.plainText(),
                    isBusy = isBusy,
                    onAiRewrite = onAiRewriteRaw,
                    onCommitRawKeyboardEdit = onCommitRawKeyboardEdit,
                    onExitRawKeyboardMode = onExitRawKeyboardMode
                )
            } else if (candidate.linkedSplitCards.size >= 2) {
                CutLinkedSplitPager(
                    candidate = candidate,
                    focusedClauseId = focusedClauseId,
                    focusedGapIndex = focusedGapIndex,
                    isBusy = isBusy,
                    brushMicroStrip = brushMicroStrip,
                    flashRange = flashRange,
                    flashAlpha = flashAlpha.value,
                    onFocusClause = onFocusClause,
                    onFocusGap = onFocusGap,
                    onToggleWordLock = onToggleWordLock,
                    onBrushSelectRelease = onBrushSelectRelease,
                    onSelectBrushAlternative = onSelectBrushAlternative,
                    onDismissBrushStrip = onDismissBrushStrip,
                    onStrikeCutClause = onStrikeCutClause,
                    onReorderClause = onReorderClause,
                    onGlobalScrubUndo = onGlobalScrubUndo
                )
            } else {
                val canonicalModel = remember(candidate.clauses) {
                    buildRenderedDraftModel(
                        clauses = candidate.clauses,
                        fullCandidateClauses = candidate.clauses,
                        globalClauseStartIndex = 0
                    )
                }
                CutGestureTextSurface(
                    renderModel = canonicalModel,
                    focusedClauseId = focusedClauseId,
                    focusedGapIndex = focusedGapIndex,
                    isBusy = isBusy,
                    brushMicroStrip = brushMicroStrip,
                    flashRange = flashRange,
                    flashAlpha = flashAlpha.value,
                    textTag = "cut_eye_zone_text",
                    onFocusClause = onFocusClause,
                    onFocusGap = onFocusGap,
                    onToggleWordLock = onToggleWordLock,
                    onBrushSelectRelease = onBrushSelectRelease,
                    onSelectBrushAlternative = onSelectBrushAlternative,
                    onDismissBrushStrip = onDismissBrushStrip,
                    onStrikeCutClause = onStrikeCutClause,
                    onReorderClause = onReorderClause,
                    onGlobalScrubUndo = onGlobalScrubUndo,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Active attachment preview underneath the draft inside the Eye Zone
            when (activeAttachment) {
                is XAttachment.QuotePost -> {
                    CutQuotePreviewBox(attachment = activeAttachment)
                }
                is XAttachment.ReplyTarget -> {
                    CutReplyPreviewBox(attachment = activeAttachment)
                }
                null -> Unit
            }
        }

        // Subtle live busy hairline at top edge of the Eye Zone while Mac :8443 pass runs
        if (isBusy) {
            val transition = rememberInfiniteTransition(label = "cut_busy_hairline")
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 0.95f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "cut_busy_alpha"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(accent.copy(alpha = alpha))
            )
        }
    }
}

// =============================================================================
// EXPLICIT [RAW KBD] FALLBACK MODE
// =============================================================================

@Composable
private fun CutRawKeyboardEditor(
    initialPlainText: String,
    isBusy: Boolean,
    onAiRewrite: () -> Unit,
    onCommitRawKeyboardEdit: (String) -> Unit,
    onExitRawKeyboardMode: () -> Unit
) {
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val textFieldState = rememberTextFieldState(initialText = initialPlainText)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ConsoleObsidian)
                .border(1.dp, AcidVoltLime.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(14.dp)
        ) {
            BasicTextField(
                state = textFieldState,
                textStyle = TextStyle(
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 20.sp,
                    lineHeight = 32.sp,
                    color = StudioCrisp
                ),
                cursorBrush = SolidColor(AcidVoltLime),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("cut_raw_keyboard_input")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isBusy) AcidVoltLime else Color(0xFF243300))
                    .border(1.dp, AcidVoltLime, RoundedCornerShape(8.dp))
                    .clickable(enabled = !isBusy, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onCommitRawKeyboardEdit(textFieldState.text.toString())
                        onAiRewrite()
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .testTag("cut_raw_ai_rewrite_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "AI rewrite draft",
                    tint = if (isBusy) OledVoid else AcidVoltLime,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ConsoleElevated)
                    .border(1.dp, GraphiteHairline, RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) {
                        keyboardController?.hide()
                        onExitRawKeyboardMode()
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .testTag("raw_kbd_cancel_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Cancel",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = TelemetryMuted
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AcidVoltLime)
                    .clickable(role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        keyboardController?.hide()
                        onCommitRawKeyboardEdit(textFieldState.text.toString())
                        onExitRawKeyboardMode()
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                    .semantics { contentDescription = "Done and lock raw keyboard edits" }
                    .testTag("raw_kbd_commit_button"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Done & Lock",
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    color = OledVoid
                )
            }
        }
    }
}

// =============================================================================
// LINKED OVERFLOW THREAD SPLIT PAGER (1/2, 2/2)
// =============================================================================

@Composable
private fun CutLinkedSplitPager(
    candidate: CandidateDraft,
    focusedClauseId: String?,
    focusedGapIndex: Int?,
    isBusy: Boolean,
    brushMicroStrip: BrushMicroStripState?,
    flashRange: IntRange?,
    flashAlpha: Float,
    onFocusClause: (String) -> Unit,
    onFocusGap: (Int) -> Unit,
    onToggleWordLock: (Int) -> Unit,
    onBrushSelectRelease: (IntRange) -> Unit,
    onSelectBrushAlternative: (Int) -> Unit,
    onDismissBrushStrip: () -> Unit,
    onStrikeCutClause: (String) -> Unit,
    onReorderClause: (Int, Int) -> Unit,
    onGlobalScrubUndo: (Int) -> Unit
) {
    val splitCards = candidate.linkedSplitCards
    val pagerState = rememberPagerState(pageCount = { splitCards.size })
    val scope = rememberCoroutineScope()
    val accent = LocalAircheckTokens.current.channelAccent

    // Compute starting global clause index for each split card
    val cardClauseStartIndices = remember(splitCards) {
        var running = 0
        splitCards.map { card ->
            val start = running
            running += card.size
            start
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Compact 1/2 | 2/2 linked pager tab strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            splitCards.forEachIndexed { idx, cardClauses ->
                val isSelected = pagerState.currentPage == idx
                val cardCharLen = remember(cardClauses) {
                    DraftStateReducer.renderPlainText(cardClauses).length
                }
                val isOver = cardCharLen > 280
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) ConsoleElevated else ConsoleObsidian)
                        .border(
                            width = 1.dp,
                            color = when {
                                isOver -> OnAirCrimson
                                isSelected -> accent
                                else -> GraphiteHairline
                            },
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable(role = Role.Tab) {
                            scope.launch { pagerState.animateScrollToPage(idx) }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("cut_split_tab_${idx + 1}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${idx + 1}/${splitCards.size} · $cardCharLen",
                        style = TextStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        color = when {
                            isOver -> OnAirCrimson
                            isSelected -> accent
                            else -> TelemetryMuted
                        }
                    )
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cut_split_pager")
        ) { page ->
            val cardClauses = splitCards[page]
            val globalStart = cardClauseStartIndices.getOrElse(page) { 0 }
            val pageModel = remember(cardClauses, candidate.clauses, globalStart) {
                buildRenderedDraftModel(
                    clauses = cardClauses,
                    fullCandidateClauses = candidate.clauses,
                    globalClauseStartIndex = globalStart
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ConsoleObsidian)
                    .border(1.dp, GraphiteHairline, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                CutGestureTextSurface(
                    renderModel = pageModel,
                    focusedClauseId = focusedClauseId,
                    focusedGapIndex = focusedGapIndex,
                    isBusy = isBusy,
                    brushMicroStrip = brushMicroStrip,
                    flashRange = flashRange,
                    flashAlpha = flashAlpha,
                    textTag = if (page == pagerState.currentPage) {
                        "cut_eye_zone_text"
                    } else {
                        "cut_eye_zone_text_page_$page"
                    },
                    onFocusClause = onFocusClause,
                    onFocusGap = onFocusGap,
                    onToggleWordLock = onToggleWordLock,
                    onBrushSelectRelease = onBrushSelectRelease,
                    onSelectBrushAlternative = onSelectBrushAlternative,
                    onDismissBrushStrip = onDismissBrushStrip,
                    onStrikeCutClause = onStrikeCutClause,
                    onReorderClause = onReorderClause,
                    onGlobalScrubUndo = onGlobalScrubUndo,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// =============================================================================
// GESTURE TEXT SURFACE + STATE MACHINE + OVERLAYS
// =============================================================================

private enum class CutGestureMode {
    PENDING,
    BRUSH_SELECT,
    STRIKE_CUT_COMMITTED,
    SENTENCE_REORDER,
    TWO_FINGER_SCRUB,
    IGNORED_VERTICAL
}

@Immutable
private data class ReorderOverlayState(
    val fromLocalIndex: Int,
    val targetLocalIndex: Int,
    val dragY: Float
)

@Composable
private fun CutGestureTextSurface(
    renderModel: RenderedDraftModel,
    focusedClauseId: String?,
    focusedGapIndex: Int?,
    isBusy: Boolean,
    brushMicroStrip: BrushMicroStripState?,
    flashRange: IntRange?,
    flashAlpha: Float,
    textTag: String,
    onFocusClause: (String) -> Unit,
    onFocusGap: (Int) -> Unit,
    onToggleWordLock: (Int) -> Unit,
    onBrushSelectRelease: (IntRange) -> Unit,
    onSelectBrushAlternative: (Int) -> Unit,
    onDismissBrushStrip: () -> Unit,
    onStrikeCutClause: (String) -> Unit,
    onReorderClause: (Int, Int) -> Unit,
    onGlobalScrubUndo: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val accent = LocalAircheckTokens.current.channelAccent

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var liveBrushDisplayRange by remember { mutableStateOf<IntRange?>(null) }
    var reorderState by remember { mutableStateOf<ReorderOverlayState?>(null) }
    val scrollState = rememberScrollState()

    // Keep latest references inside pointerInput without resetting mid-gesture
    val currentModel by rememberUpdatedState(renderModel)
    val currentLayout by rememberUpdatedState(textLayoutResult)
    val currentBrushStrip by rememberUpdatedState(brushMicroStrip)
    val currentOnFocusClause by rememberUpdatedState(onFocusClause)
    val currentOnFocusGap by rememberUpdatedState(onFocusGap)
    val currentOnToggleWordLock by rememberUpdatedState(onToggleWordLock)
    val currentOnBrushSelectRelease by rememberUpdatedState(onBrushSelectRelease)
    val currentOnDismissBrushStrip by rememberUpdatedState(onDismissBrushStrip)
    val currentOnStrikeCutClause by rememberUpdatedState(onStrikeCutClause)
    val currentOnReorderClause by rememberUpdatedState(onReorderClause)
    val currentOnGlobalScrubUndo by rememberUpdatedState(onGlobalScrubUndo)

    // Determine active inverted highlight range (either live brushing or committed micro-strip)
    val activeBrushDisplayRange = liveBrushDisplayRange
        ?: brushMicroStrip?.let { renderModel.canonicalToDisplayRange(it.charRange) }

    val flashDisplayRange = remember(flashRange, renderModel) {
        flashRange?.let { renderModel.canonicalToDisplayRange(it) }
    }

    // Build AnnotatedString with locked word contrast, gap markers, and inverted brush ink
    val annotatedText = remember(
        renderModel,
        focusedGapIndex,
        activeBrushDisplayRange,
        accent
    ) {
        buildStyledDraftAnnotatedString(
            model = renderModel,
            focusedGapIndex = focusedGapIndex,
            activeBrushDisplayRange = activeBrushDisplayRange,
            accentColor = accent
        )
    }

    // Gesture threshold constants in px
    val tapSlopPx = with(density) { 8.dp.toPx() }
    val doubleTapSlopPx = with(density) { 16.dp.toPx() }
    val longPressSlopPx = with(density) { 6.dp.toPx() }
    val brushStartPx = with(density) { 12.dp.toPx() }
    val strikeMinDistPx = with(density) { 28.dp.toPx() }
    val twoFingerStepPx = with(density) { 32.dp.toPx() }
    val brushMaxVelPxPerSec = with(density) { 500.dp.toPx() }
    val strikeMinVelPxPerSec = with(density) { 600.dp.toPx() }

    val textGestureModifier = Modifier.pointerInput(Unit) {
        var lastTapTimeMs = 0L
        var lastTapPos = Offset.Zero

        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            val layout = currentLayout ?: return@awaitEachGesture
            val model = currentModel
            if (model.displayText.isEmpty() || model.clauses.isEmpty()) return@awaitEachGesture

            val downPos = firstDown.position
            val downTimeMs = firstDown.uptimeMillis
            val velocityTracker = VelocityTracker().apply {
                addPosition(firstDown.uptimeMillis, firstDown.position)
            }

            var mode = CutGestureMode.PENDING
            var maxDistFromDown = 0f
            var stationaryForLongPress = true
            var longPressRemainingMs = 220L
            var lastEventTimeMs = firstDown.uptimeMillis

            // Two-finger state
            var twoFingerAnchorX = 0f
            val initialPressed = currentEvent.changes.filter { it.pressed }
            if (initialPressed.size >= 2) {
                mode = CutGestureMode.TWO_FINGER_SCRUB
                stationaryForLongPress = false
                lastTapTimeMs = 0L
                twoFingerAnchorX = (initialPressed[0].position.x + initialPressed[1].position.x) / 2f
                initialPressed.forEach { it.consume() }
            }

            // Brush state
            var brushAnchorDisplayOffset = -1
            var lastBrushTokenPair: Pair<Int, Int>? = null
            var latestCanonicalBrushRange: IntRange? = null

            // Reorder state
            var reorderFromLocalIdx = -1
            var reorderTargetLocalIdx = -1

            while (true) {
                val event: PointerEvent? = if (
                    mode == CutGestureMode.PENDING &&
                    stationaryForLongPress &&
                    longPressRemainingMs > 0L
                ) {
                    withTimeoutOrNull(longPressRemainingMs) {
                        awaitPointerEvent()
                    }
                } else {
                    awaitPointerEvent()
                }

                // Stationary long-press timer elapsed (>= 220ms, < 6dp movement)
                if (event == null) {
                    val hitClause = hitTestClause(downPos, layout, model)
                    if (hitClause != null && model.clauses.size >= 2) {
                        mode = CutGestureMode.SENTENCE_REORDER
                        reorderFromLocalIdx = hitClause.localClauseIndex
                        reorderTargetLocalIdx = hitClause.localClauseIndex
                        lastTapTimeMs = 0L
                        liveBrushDisplayRange = null
                        if (currentBrushStrip != null) {
                            currentOnDismissBrushStrip()
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        reorderState = ReorderOverlayState(
                            fromLocalIndex = reorderFromLocalIdx,
                            targetLocalIndex = reorderTargetLocalIdx,
                            dragY = downPos.y
                        )
                    } else {
                        stationaryForLongPress = false
                    }
                    continue
                }

                val pressedChanges = event.changes.filter { it.pressed }

                // -------------------------------------------------------------
                // MULTI-TOUCH: Two-Finger Horizontal Scrub (> 32dp -> undo/redo)
                // -------------------------------------------------------------
                if (pressedChanges.size >= 2) {
                    if (mode != CutGestureMode.TWO_FINGER_SCRUB &&
                        mode != CutGestureMode.SENTENCE_REORDER &&
                        mode != CutGestureMode.STRIKE_CUT_COMMITTED
                    ) {
                        liveBrushDisplayRange = null
                        mode = CutGestureMode.TWO_FINGER_SCRUB
                        stationaryForLongPress = false
                        lastTapTimeMs = 0L
                        twoFingerAnchorX =
                            (pressedChanges[0].position.x + pressedChanges[1].position.x) / 2f
                        pressedChanges.forEach { it.consume() }
                        continue
                    }

                    if (mode == CutGestureMode.TWO_FINGER_SCRUB) {
                        val centroidX =
                            (pressedChanges[0].position.x + pressedChanges[1].position.x) / 2f
                        val deltaX = centroidX - twoFingerAnchorX
                        if (deltaX <= -twoFingerStepPx) {
                            twoFingerAnchorX = centroidX
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            currentOnGlobalScrubUndo(-1)
                        } else if (deltaX >= twoFingerStepPx) {
                            twoFingerAnchorX = centroidX
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            currentOnGlobalScrubUndo(+1)
                        }
                        pressedChanges.forEach { it.consume() }
                        continue
                    }
                }

                // -------------------------------------------------------------
                // RELEASE: All pointers lifted
                // -------------------------------------------------------------
                if (pressedChanges.isEmpty()) {
                    val primary = event.changes.firstOrNull { it.id == firstDown.id }
                        ?: event.changes.first()
                    velocityTracker.addPosition(primary.uptimeMillis, primary.position)

                    val durationMs = (primary.uptimeMillis - downTimeMs).coerceAtLeast(1L)
                    val totalDx = primary.position.x - downPos.x
                    val totalDy = primary.position.y - downPos.y
                    val totalDist = hypot(totalDx, totalDy)
                    val angleDeg = Math.toDegrees(atan2(abs(totalDy.toDouble()), abs(totalDx.toDouble())))

                    when (mode) {
                        CutGestureMode.PENDING -> {
                            val trackerVelX = velocityTracker.calculateVelocity().x
                            val avgLeftSpeed = (-totalDx) * 1000f / durationMs
                            val effectiveLeftSpeed = maxOf(-trackerVelX, avgLeftSpeed)

                            // Check single-frame fast leftward flick (STRIKE_CUT)
                            if (totalDx < -strikeMinDistPx &&
                                angleDeg < 25.0 &&
                                effectiveLeftSpeed > strikeMinVelPxPerSec
                            ) {
                                val hitClause = hitTestClause(downPos, layout, model)
                                    ?: hitTestClause(primary.position, layout, model)
                                if (hitClause != null) {
                                    lastTapTimeMs = 0L
                                    performStrikeCutHaptic(view)
                                    currentOnStrikeCutClause(hitClause.clauseId)
                                }
                            } else if (
                                maxDistFromDown < tapSlopPx &&
                                totalDist < tapSlopPx &&
                                durationMs < 180L
                            ) {
                                // Single Tap or Double Tap
                                if (currentBrushStrip != null) {
                                    currentOnDismissBrushStrip()
                                    lastTapTimeMs = 0L
                                } else {
                                    val sinceLastTap = downTimeMs - lastTapTimeMs
                                    val distFromLastTap = (downPos - lastTapPos).getDistance()
                                    if (lastTapTimeMs > 0L &&
                                        sinceLastTap in 0L..250L &&
                                        distFromLastTap < doubleTapSlopPx
                                    ) {
                                        // Double Tap -> toggle word verbatim lock
                                        val wordCanonicalOffset = hitTestWordCanonicalOffset(downPos, layout, model)
                                        lastTapTimeMs = 0L
                                        if (wordCanonicalOffset != null) {
                                            performDoubleTickHaptic(view)
                                            currentOnToggleWordLock(wordCanonicalOffset)
                                        }
                                    } else {
                                        // Single Tap -> gap or clause focus
                                        val hitGap = hitTestGap(downPos, layout, model)
                                        if (hitGap != null) {
                                            lastTapTimeMs = 0L
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            currentOnFocusGap(hitGap.canonicalGapIndex)
                                        } else {
                                            val hitClause = hitTestClause(downPos, layout, model)
                                            if (hitClause != null) {
                                                currentOnFocusClause(hitClause.clauseId)
                                                lastTapTimeMs = primary.uptimeMillis
                                                lastTapPos = downPos
                                            } else {
                                                lastTapTimeMs = 0L
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        CutGestureMode.BRUSH_SELECT -> {
                            val trackerVelX = velocityTracker.calculateVelocity().x
                            val avgLeftSpeed = (-totalDx) * 1000f / durationMs
                            val effectiveLeftSpeed = maxOf(-trackerVelX, avgLeftSpeed)
                            if (totalDx < -strikeMinDistPx &&
                                angleDeg < 25.0 &&
                                effectiveLeftSpeed > strikeMinVelPxPerSec &&
                                durationMs < 350L
                            ) {
                                // Fast leftward flick cancels active brush and cuts clause
                                liveBrushDisplayRange = null
                                val hitClause = hitTestClause(downPos, layout, model)
                                    ?: hitTestClause(primary.position, layout, model)
                                if (hitClause != null) {
                                    performStrikeCutHaptic(view)
                                    currentOnStrikeCutClause(hitClause.clauseId)
                                }
                            } else {
                                val snappedCanonical = latestCanonicalBrushRange
                                liveBrushDisplayRange = null
                                if (snappedCanonical != null && !snappedCanonical.isEmpty()) {
                                    currentOnBrushSelectRelease(snappedCanonical)
                                }
                            }
                        }

                        CutGestureMode.SENTENCE_REORDER -> {
                            val fromGlobal = model.localToGlobalClauseIndex(reorderFromLocalIdx)
                            val toGlobal = model.localToGlobalClauseIndex(reorderTargetLocalIdx)
                            reorderState = null
                            if (fromGlobal >= 0 && toGlobal >= 0 && fromGlobal != toGlobal) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                currentOnReorderClause(fromGlobal, toGlobal)
                            }
                        }

                        CutGestureMode.TWO_FINGER_SCRUB,
                        CutGestureMode.STRIKE_CUT_COMMITTED,
                        CutGestureMode.IGNORED_VERTICAL -> Unit
                    }

                    break
                }

                // -------------------------------------------------------------
                // MOVE: Single active pointer
                // -------------------------------------------------------------
                if (mode == CutGestureMode.TWO_FINGER_SCRUB) {
                    // Wait until remaining finger also lifts
                    pressedChanges.forEach { it.consume() }
                    continue
                }

                val change = pressedChanges.firstOrNull { it.id == firstDown.id } ?: pressedChanges[0]
                velocityTracker.addPosition(change.uptimeMillis, change.position)

                val dtFromLast = (change.uptimeMillis - lastEventTimeMs).coerceAtLeast(0L)
                lastEventTimeMs = change.uptimeMillis
                if (stationaryForLongPress) {
                    longPressRemainingMs = (longPressRemainingMs - dtFromLast).coerceAtLeast(0L)
                }

                val dx = change.position.x - downPos.x
                val dy = change.position.y - downPos.y
                val dist = hypot(dx, dy)
                if (dist > maxDistFromDown) maxDistFromDown = dist
                if (dist >= longPressSlopPx) {
                    stationaryForLongPress = false
                }

                when (mode) {
                    CutGestureMode.SENTENCE_REORDER -> {
                        change.consume()
                        val newTarget = resolveReorderTargetIndex(
                            dragY = change.position.y,
                            model = model,
                            layout = layout,
                            containerHeightPx = containerSize.height.toFloat()
                        )
                        if (newTarget != reorderTargetLocalIdx) {
                            reorderTargetLocalIdx = newTarget
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        reorderState = ReorderOverlayState(
                            fromLocalIndex = reorderFromLocalIdx,
                            targetLocalIndex = reorderTargetLocalIdx,
                            dragY = change.position.y
                        )
                    }

                    CutGestureMode.STRIKE_CUT_COMMITTED -> {
                        change.consume()
                    }

                    CutGestureMode.IGNORED_VERTICAL -> {
                        // Allow ancestor vertical scroll without consuming
                    }

                    CutGestureMode.PENDING,
                    CutGestureMode.BRUSH_SELECT -> {
                        val elapsedMs = (change.uptimeMillis - downTimeMs).coerceAtLeast(1L)
                        val trackerVel = velocityTracker.calculateVelocity()
                        val effectiveLeftSpeed = maxOf(-trackerVel.x, (-dx) * 1000f / elapsedMs)
                        val angleDeg = Math.toDegrees(atan2(abs(dy.toDouble()), abs(dx.toDouble())))

                        // Priority 1: Fast Leftward Flick (STRIKE_CUT) > 600dp/s, angle < 25°
                        if (dx < -strikeMinDistPx &&
                            angleDeg < 25.0 &&
                            effectiveLeftSpeed > strikeMinVelPxPerSec &&
                            elapsedMs < 400L
                        ) {
                            mode = CutGestureMode.STRIKE_CUT_COMMITTED
                            lastTapTimeMs = 0L
                            liveBrushDisplayRange = null
                            change.consume()
                            val hitClause = hitTestClause(downPos, layout, model)
                                ?: hitTestClause(change.position, layout, model)
                            if (hitClause != null) {
                                performStrikeCutHaptic(view)
                                currentOnStrikeCutClause(hitClause.clauseId)
                            }
                        } else if (mode == CutGestureMode.PENDING && dist > brushStartPx) {
                            if (angleDeg < 30.0) {
                                val horizSpeed = maxOf(abs(trackerVel.x), abs(dx) * 1000f / elapsedMs)
                                if (horizSpeed < brushMaxVelPxPerSec) {
                                    // Enter Slow Horizontal Scrub (BRUSH_SELECT)
                                    mode = CutGestureMode.BRUSH_SELECT
                                    lastTapTimeMs = 0L
                                    if (currentBrushStrip != null) {
                                        currentOnDismissBrushStrip()
                                    }
                                    brushAnchorDisplayOffset = clampDisplayOffset(
                                        layout.getOffsetForPosition(downPos),
                                        model.displayText.length
                                    )
                                    val currOffset = clampDisplayOffset(
                                        layout.getOffsetForPosition(change.position),
                                        model.displayText.length
                                    )
                                    val snapped = snapBrushSpan(
                                        layout = layout,
                                        model = model,
                                        anchorDisplayOffset = brushAnchorDisplayOffset,
                                        currentDisplayOffset = currOffset
                                    )
                                    if (snapped != null) {
                                        lastBrushTokenPair = snapped.tokenIndexPair
                                        latestCanonicalBrushRange = snapped.canonicalRange
                                        liveBrushDisplayRange = snapped.displayRange
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                    change.consume()
                                }
                            } else {
                                // Steep vertical movement without long-press -> let scroll run
                                mode = CutGestureMode.IGNORED_VERTICAL
                            }
                        } else if (mode == CutGestureMode.BRUSH_SELECT) {
                            change.consume()
                            val currOffset = clampDisplayOffset(
                                layout.getOffsetForPosition(change.position),
                                model.displayText.length
                            )
                            val snapped = snapBrushSpan(
                                layout = layout,
                                model = model,
                                anchorDisplayOffset = brushAnchorDisplayOffset,
                                currentDisplayOffset = currOffset
                            )
                            if (snapped != null) {
                                if (snapped.tokenIndexPair != lastBrushTokenPair) {
                                    lastBrushTokenPair = snapped.tokenIndexPair
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                latestCanonicalBrushRange = snapped.canonicalRange
                                liveBrushDisplayRange = snapped.displayRange
                            }
                        }
                    }

                    CutGestureMode.TWO_FINGER_SCRUB -> Unit
                }
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { containerSize = it }
    ) {
        if (renderModel.clauses.isEmpty()) {
            Text(
                text = "Draft is empty. Undo or tap Raw Kbd to write.",
                style = TextStyle(
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 20.sp,
                    lineHeight = 32.sp
                ),
                color = TelemetryMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag(textTag)
            )
        } else {
            val focusedSpan = remember(renderModel, focusedClauseId) {
                renderModel.clauseSpans.firstOrNull { it.clauseId == focusedClauseId }
            }
            val focusedGapSpan = remember(renderModel, focusedGapIndex) {
                renderModel.gapSpans.firstOrNull { it.canonicalGapIndex == focusedGapIndex }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(top = 6.dp, bottom = 14.dp)
            ) {
                Text(
                    text = annotatedText,
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 20.sp,
                        lineHeight = 32.sp,
                        color = StudioCrisp
                    ),
                    onTextLayout = { textLayoutResult = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val layout = textLayoutResult ?: return@drawBehind
                            drawCutCanvasDecorations(
                                layout = layout,
                                model = renderModel,
                                focusedSpan = focusedSpan,
                                focusedGapSpan = focusedGapSpan,
                                activeBrushDisplayRange = activeBrushDisplayRange,
                                flashDisplayRange = flashDisplayRange,
                                flashAlpha = flashAlpha,
                                accentColor = accent
                            )
                        }
                        .then(textGestureModifier)
                        .testTag(textTag)
                )
            }
        }

        // Transient 3-Pill Brush Micro-Strip anchored 36dp above the brushed span
        if (brushMicroStrip != null) {
            val topPadPx = with(density) { 6.dp.toPx() }
            val scrollY = scrollState.value.toFloat()
            val anchorRect = remember(brushMicroStrip, textLayoutResult, renderModel, scrollY, topPadPx) {
                if (brushMicroStrip.anchorOffsetPx != Offset.Zero) {
                    Rect(
                        left = brushMicroStrip.anchorOffsetPx.x,
                        top = brushMicroStrip.anchorOffsetPx.y,
                        right = brushMicroStrip.anchorOffsetPx.x,
                        bottom = brushMicroStrip.anchorOffsetPx.y
                    )
                } else {
                    computeBrushAnchorRect(
                        layout = textLayoutResult,
                        model = renderModel,
                        canonicalRange = brushMicroStrip.charRange,
                        yOffsetPx = topPadPx - scrollY
                    )
                }
            }
            BrushMicroStripOverlay(
                state = brushMicroStrip,
                isBusy = isBusy,
                anchorRectPx = anchorRect,
                containerSize = containerSize,
                onSelectAlternative = onSelectBrushAlternative
            )
        }

        // Sentence Reorder Overlay (lifted sentence pill + crisp drop slots between clauses)
        val activeReorder = reorderState
        if (activeReorder != null && renderModel.clauses.size >= 2) {
            SentenceReorderOverlay(
                clauses = renderModel.clauses,
                state = activeReorder,
                accent = accent
            )
        }
    }
}

// =============================================================================
// CANVAS DECORATIONS: LOCK BASELINES, FOCUS FRAME, PAGINATION DOTS, BRUSH & FLASH
// =============================================================================

private fun DrawScope.drawCutCanvasDecorations(
    layout: TextLayoutResult,
    model: RenderedDraftModel,
    focusedSpan: ClauseRenderSpan?,
    focusedGapSpan: GapRenderSpan?,
    activeBrushDisplayRange: IntRange?,
    flashDisplayRange: IntRange?,
    flashAlpha: Float,
    accentColor: Color
) {
    val textLen = layout.layoutInput.text.length
    if (textLen == 0) return

    // 1. 180ms replacement flash highlight
    if (flashAlpha > 0.01f && flashDisplayRange != null && !flashDisplayRange.isEmpty()) {
        val rects = computeLineRectsForRange(layout, flashDisplayRange.first, flashDisplayRange.last + 1)
        val padH = 3.dp.toPx()
        val padV = 1.dp.toPx()
        val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        rects.forEach { rect ->
            drawRoundRect(
                color = AcidVoltLime.copy(alpha = 0.38f * flashAlpha),
                topLeft = Offset(rect.left - padH, rect.top + padV),
                size = Size(rect.width + padH * 2f, (rect.height - padV * 2f).coerceAtLeast(1f)),
                cornerRadius = corner
            )
        }
    }

    // 2. Focused Clause solid 1.5dp frame + compact pagination dots (•••) when versions.size > 1
    if (focusedSpan != null && focusedSpan.displayEndExclusive > focusedSpan.displayStart) {
        val clauseRects = computeLineRectsForRange(
            layout = layout,
            displayStart = focusedSpan.displayStart,
            displayEndExclusive = focusedSpan.displayEndExclusive
        )
        if (clauseRects.isNotEmpty()) {
            val framePadH = 4.dp.toPx()
            val framePadV = 2.dp.toPx()
            val corner = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            val strokePx = 1.5.dp.toPx()

            clauseRects.forEach { rect ->
                val topLeft = Offset((rect.left - framePadH).coerceAtLeast(0f), rect.top + framePadV)
                val rectWidth = (rect.right + framePadH - topLeft.x).coerceAtLeast(1f)
                val rectHeight = (rect.height - framePadV * 2f).coerceAtLeast(1f)

                drawRoundRect(
                    color = accentColor.copy(alpha = 0.07f),
                    topLeft = topLeft,
                    size = Size(rectWidth, rectHeight),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = accentColor,
                    topLeft = topLeft,
                    size = Size(rectWidth, rectHeight),
                    cornerRadius = corner,
                    style = Stroke(width = strokePx)
                )
            }

            // Compact pagination dots (•••) beneath focused clause when versions.size > 1
            val versionCount = focusedSpan.clause.versions.size
            if (versionCount > 1) {
                val lastRect = clauseRects.last()
                val visibleDots = versionCount.coerceAtMost(7)
                val activeVersionIdx = focusedSpan.clause.activeVersionIndex.coerceIn(0, versionCount - 1)
                val windowStart = if (versionCount <= 7) {
                    0
                } else {
                    (activeVersionIdx - 3).coerceIn(0, versionCount - visibleDots)
                }
                val dotSpacing = 7.dp.toPx()
                val totalDotsWidth = (visibleDots - 1) * dotSpacing
                val centerX = ((lastRect.left + lastRect.right) / 2f)
                    .coerceIn(totalDotsWidth / 2f + 4.dp.toPx(), size.width - totalDotsWidth / 2f - 4.dp.toPx())
                val startX = centerX - totalDotsWidth / 2f
                val dotY = lastRect.bottom + 3.dp.toPx()

                for (i in 0 until visibleDots) {
                    val idx = windowStart + i
                    val isActive = idx == activeVersionIdx
                    drawCircle(
                        color = if (isActive) accentColor else TelemetryMuted.copy(alpha = 0.55f),
                        radius = if (isActive) 2.5.dp.toPx() else 1.8.dp.toPx(),
                        center = Offset(startX + i * dotSpacing, dotY)
                    )
                }
            }
        }
    }

    // 3. Focused Inter-Sentence Gap highlight pill
    if (focusedGapSpan != null && focusedGapSpan.dotDisplayIndex in 0 until textLen) {
        val box = layout.getBoundingBox(focusedGapSpan.dotDisplayIndex)
        val pad = 4.dp.toPx()
        drawRoundRect(
            color = accentColor.copy(alpha = 0.22f),
            topLeft = Offset(box.left - pad, box.top + 3.dp.toPx()),
            size = Size(box.width + pad * 2f, (box.height - 6.dp.toPx()).coerceAtLeast(4f)),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        drawRoundRect(
            color = accentColor,
            topLeft = Offset(box.left - pad, box.top + 3.dp.toPx()),
            size = Size(box.width + pad * 2f, (box.height - 6.dp.toPx()).coerceAtLeast(4f)),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )
    }

    // 4. Inverted AcidVoltLime highlight rectangle over brushed words
    if (activeBrushDisplayRange != null && !activeBrushDisplayRange.isEmpty()) {
        val brushRects = computeLineRectsForRange(
            layout = layout,
            displayStart = activeBrushDisplayRange.first,
            displayEndExclusive = activeBrushDisplayRange.last + 1
        )
        val padH = 3.dp.toPx()
        val padV = 2.dp.toPx()
        val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        brushRects.forEach { rect ->
            drawRoundRect(
                color = AcidVoltLime,
                topLeft = Offset(rect.left - padH, rect.top + padV),
                size = Size(rect.width + padH * 2f, (rect.height - padV * 2f).coerceAtLeast(1f)),
                cornerRadius = corner
            )
        }
    }

    // 5. Crisp 1dp solid baseline rule underneath every verbatimLocked WordToken bounding box
    val ruleStrokePx = 1.dp.toPx()
    val baselineOffsetPx = 2.5.dp.toPx()
    model.tokenSpans.forEach { span ->
        if (!span.token.verbatimLocked) return@forEach
        val tokenStart = span.displayStart.coerceIn(0, textLen - 1)
        val tokenEndIncl = (span.displayEndExclusive - 1).coerceIn(tokenStart, textLen - 1)
        val firstLine = layout.getLineForOffset(tokenStart)
        val lastLine = layout.getLineForOffset(tokenEndIncl)

        val isInverted = activeBrushDisplayRange != null &&
            span.displayStart <= activeBrushDisplayRange.last &&
            (span.displayEndExclusive - 1) >= activeBrushDisplayRange.first
        val ruleColor = if (isInverted) OledVoid else StudioCrisp

        for (line in firstLine..lastLine) {
            val lineStart = layout.getLineStart(line)
            val lineEndExcl = layout.getLineEnd(line, visibleEnd = true)
            val segStart = maxOf(tokenStart, lineStart)
            val segEndIncl = minOf(tokenEndIncl, lineEndExcl - 1)
            if (segStart > segEndIncl || segStart >= textLen) continue

            val firstBox = layout.getBoundingBox(segStart)
            val lastBox = layout.getBoundingBox(segEndIncl)
            val left = minOf(firstBox.left, lastBox.left)
            val right = maxOf(firstBox.right, lastBox.right)
            if (right <= left) continue

            val baselineY = (layout.getLineBaseline(line) + baselineOffsetPx)
                .coerceAtMost(layout.getLineBottom(line) - ruleStrokePx)

            drawLine(
                color = ruleColor,
                start = Offset(left, baselineY),
                end = Offset(right, baselineY),
                strokeWidth = ruleStrokePx
            )
        }
    }
}

// =============================================================================
// TRANSIENT 3-PILL BRUSH MICRO-STRIP OVERLAY
// =============================================================================

@Composable
private fun BrushMicroStripOverlay(
    state: BrushMicroStripState,
    isBusy: Boolean,
    anchorRectPx: Rect,
    containerSize: IntSize,
    onSelectAlternative: (Int) -> Unit
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val accent = LocalAircheckTokens.current.channelAccent
    var stripSize by remember { mutableStateOf(IntSize.Zero) }

    val elevationAbovePx = with(density) { 36.dp.roundToPx() }
    val belowGapPx = with(density) { 8.dp.roundToPx() }
    val horizontalPadPx = with(density) { 4.dp.roundToPx() }
    val offset = remember(anchorRectPx, stripSize, containerSize, elevationAbovePx) {
        val centerX = (anchorRectPx.left + anchorRectPx.right) / 2f
        val desiredX = (centerX - stripSize.width / 2f).roundToInt()
        val maxX = (containerSize.width - stripSize.width - horizontalPadPx).coerceAtLeast(horizontalPadPx)
        val clampedX = if (containerSize.width > 0 && stripSize.width > 0) {
            desiredX.coerceIn(horizontalPadPx, maxX)
        } else {
            horizontalPadPx
        }
        val aboveY = (anchorRectPx.top - elevationAbovePx).roundToInt()
        val resolvedY = if (aboveY >= 0 || anchorRectPx.height <= 0f) {
            aboveY.coerceAtLeast(0)
        } else {
            // Line 0 near top edge: flip cleanly directly beneath the brushed line so text stays visible
            (anchorRectPx.bottom + belowGapPx).roundToInt()
        }
        IntOffset(clampedX, resolvedY)
    }

    val alt0 = state.alternatives.getOrNull(0)?.takeIf { it.isNotBlank() }
    val alt1 = state.alternatives.getOrNull(1)?.takeIf { it.isNotBlank() }
    val alt2Label = state.alternatives.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "Cut clean"

    Row(
        modifier = Modifier
            .zIndex(10f)
            .offset { offset }
            .onSizeChanged { stripSize = it }
            .clip(RoundedCornerShape(10.dp))
            .background(ConsoleElevated)
            .border(1.dp, accent.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .testTag("brush_micro_strip"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (alt0 != null) {
            BrushAlternativePill(
                label = alt0,
                selected = state.activeIndex == 0,
                isDestructive = false,
                enabled = !isBusy,
                tag = "brush_alt_0",
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSelectAlternative(0)
                }
            )
        }

        if (alt1 != null) {
            BrushAlternativePill(
                label = alt1,
                selected = state.activeIndex == 1,
                isDestructive = false,
                enabled = !isBusy,
                tag = "brush_alt_1",
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSelectAlternative(1)
                }
            )
        }

        if (alt0 == null && alt1 == null && isBusy) {
            Text(
                text = "Swapping...",
                style = TextStyle(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                ),
                color = TelemetryMuted,
                modifier = Modifier.padding(horizontal = 6.dp),
                maxLines = 1
            )
        }

        BrushAlternativePill(
            label = alt2Label,
            selected = state.activeIndex == 2,
            isDestructive = true,
            enabled = !isBusy,
            tag = "brush_alt_2",
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onSelectAlternative(2)
            }
        )
    }
}

@Composable
private fun BrushAlternativePill(
    label: String,
    selected: Boolean,
    isDestructive: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    val accent = LocalAircheckTokens.current.channelAccent
    val bgColor = when {
        selected && !isDestructive -> accent
        isDestructive -> OnAirCrimson.copy(alpha = 0.14f)
        else -> ConsoleObsidian
    }
    val borderColor = when {
        selected && !isDestructive -> accent
        isDestructive -> OnAirCrimson.copy(alpha = 0.7f)
        else -> GraphiteHairline
    }
    val inkColor = when {
        selected && !isDestructive -> OledVoid
        isDestructive -> OnAirCrimson
        else -> StudioCrisp
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .widthIn(max = 128.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = if (selected || isDestructive) FontWeight.Bold else FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 15.sp
            ),
            color = inkColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =============================================================================
// SENTENCE REORDER OVERLAY
// =============================================================================

@Composable
private fun SentenceReorderOverlay(
    clauses: List<ClauseNode>,
    state: ReorderOverlayState,
    accent: Color
) {
    Column(
        modifier = Modifier
            .zIndex(20f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(OledVoid.copy(alpha = 0.94f))
            .border(1.dp, GraphiteHairline, RoundedCornerShape(10.dp))
            .padding(10.dp)
            .testTag("cut_reorder_overlay"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        clauses.forEachIndexed { idx, clause ->
            val isLifted = idx == state.fromLocalIndex
            val isDropBefore = idx == state.targetLocalIndex && state.targetLocalIndex <= state.fromLocalIndex
            val isDropAfter = idx == state.targetLocalIndex && state.targetLocalIndex > state.fromLocalIndex

            if (isDropBefore && state.targetLocalIndex != state.fromLocalIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isLifted) ConsoleElevated else ConsoleObsidian)
                    .border(
                        width = if (isLifted) 1.5.dp else 1.dp,
                        color = if (isLifted) accent else GraphiteHairline,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    text = clause.plainText(),
                    style = TextStyle(
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = if (isLifted) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    ),
                    color = if (isLifted) StudioCrisp else TelemetryMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isDropAfter && state.targetLocalIndex != state.fromLocalIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent)
                )
            }
        }
    }
}

// =============================================================================
// ATTACHMENT PREVIEW CARDS (QUOTE POST / REPLY TARGET)
// =============================================================================

@Composable
private fun CutQuotePreviewBox(attachment: XAttachment.QuotePost) {
    val accent = LocalAircheckTokens.current.channelAccent
    val displayHandle = if (attachment.handle.startsWith("@")) {
        attachment.handle
    } else {
        "@${attachment.handle}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ConsoleObsidian)
            .border(1.dp, GraphiteHairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("cut_quote_preview"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(28.dp)
                .background(accent, RoundedCornerShape(1.dp))
        )
        Text(
            text = "Quoting $displayHandle: \"${attachment.snippet}\"",
            style = TextStyle(
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp
            ),
            color = TelemetryMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CutReplyPreviewBox(attachment: XAttachment.ReplyTarget) {
    val displayHandle = if (attachment.handle.startsWith("@")) {
        attachment.handle
    } else {
        "@${attachment.handle}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ConsoleObsidian)
            .border(1.dp, GraphiteHairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("cut_reply_preview"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(28.dp)
                .background(TelemetryMuted, RoundedCornerShape(1.dp))
        )
        Text(
            text = "Replying to $displayHandle: \"${attachment.snippet}\"",
            style = TextStyle(
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp
            ),
            color = TelemetryMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =============================================================================
// CANONICAL <-> DISPLAY RENDER MODEL & HIT-TESTING HELPERS
// =============================================================================

private data class RenderedTokenSpan(
    val localClauseIndex: Int,
    val globalClauseIndex: Int,
    val clauseId: String,
    val tokenIndex: Int,
    val token: com.example.draft.WordToken,
    val displayStart: Int,
    val displayEndExclusive: Int,
    val canonicalStart: Int,
    val canonicalEndExclusive: Int
)

private data class ClauseRenderSpan(
    val localClauseIndex: Int,
    val globalClauseIndex: Int,
    val clauseId: String,
    val clause: ClauseNode,
    val displayStart: Int,
    val displayEndExclusive: Int
)

private data class GapRenderSpan(
    val canonicalGapIndex: Int,
    val displayStart: Int,
    val displayEndExclusive: Int,
    val dotDisplayIndex: Int
)

private data class RenderedDraftModel(
    val clauses: List<ClauseNode>,
    val displayText: String,
    val tokenSpans: List<RenderedTokenSpan>,
    val clauseSpans: List<ClauseRenderSpan>,
    val gapSpans: List<GapRenderSpan>
) {
    fun canonicalToDisplayRange(canonicalRange: IntRange): IntRange? {
        if (canonicalRange.isEmpty() || tokenSpans.isEmpty()) return null
        val overlapping = tokenSpans.filter { span ->
            span.canonicalStart <= canonicalRange.last &&
                (span.canonicalEndExclusive - 1) >= canonicalRange.first
        }
        if (overlapping.isEmpty()) return null
        return overlapping.first().displayStart..(overlapping.last().displayEndExclusive - 1)
    }

    fun localToGlobalClauseIndex(localIdx: Int): Int {
        return clauseSpans.firstOrNull { it.localClauseIndex == localIdx }?.globalClauseIndex ?: -1
    }
}

private data class SnappedBrushResult(
    val displayRange: IntRange,
    val canonicalRange: IntRange,
    val tokenIndexPair: Pair<Int, Int>
)

private fun buildRenderedDraftModel(
    clauses: List<ClauseNode>,
    fullCandidateClauses: List<ClauseNode>,
    globalClauseStartIndex: Int
): RenderedDraftModel {
    val nonEmptyLocalIndices = clauses.indices.filter { clauses[it].activeVersion.tokens.isNotEmpty() }
    if (nonEmptyLocalIndices.isEmpty()) {
        return RenderedDraftModel(clauses, "", emptyList(), emptyList(), emptyList())
    }

    // Build lookup from (clauseId, tokenIndex) -> canonical char range in fullCandidateClauses
    val (_, fullFlatTokens) = DraftStateReducer.flattenClauses(fullCandidateClauses)
    val canonicalLookup = HashMap<Pair<String, Int>, DraftStateReducer.FlattenedToken>(fullFlatTokens.size)
    val clauseGlobalIndexById = HashMap<String, Int>(fullCandidateClauses.size)
    fullCandidateClauses.forEachIndexed { idx, clause -> clauseGlobalIndexById[clause.id] = idx }
    fullFlatTokens.forEach { ft -> canonicalLookup[ft.clauseId to ft.tokenIndex] = ft }

    // Fallback canonical offsets for local clauses if split cards synthesized `_a`/`_b` IDs
    val (_, localFlatTokens) = DraftStateReducer.flattenClauses(clauses)
    val localLookup = HashMap<Pair<Int, Int>, DraftStateReducer.FlattenedToken>(localFlatTokens.size)
    localFlatTokens.forEach { ft -> localLookup[ft.clauseIndex to ft.tokenIndex] = ft }

    val sb = StringBuilder()
    val tokenSpans = mutableListOf<RenderedTokenSpan>()
    val clauseSpans = mutableListOf<ClauseRenderSpan>()
    val gapSpans = mutableListOf<GapRenderSpan>()

    val lastNonEmptyIdx = nonEmptyLocalIndices.last()

    for (posInNonEmpty in nonEmptyLocalIndices.indices) {
        val localClauseIdx = nonEmptyLocalIndices[posInNonEmpty]
        val clause = clauses[localClauseIdx]
        val globalClauseIdx = clauseGlobalIndexById[clause.id] ?: (globalClauseStartIndex + localClauseIdx)
        val tokens = clause.activeVersion.tokens
        val hasNextClause = localClauseIdx < lastNonEmptyIdx

        val clauseDisplayStart = sb.length
        var clauseDisplayEndExcl = clauseDisplayStart

        tokens.forEachIndexed { tokenIdx, token ->
            val dispStart = sb.length
            sb.append(token.text)
            val dispEndExcl = sb.length
            clauseDisplayEndExcl = dispEndExcl

            val matchedFull = canonicalLookup[clause.id to tokenIdx]
            val matchedLocal = localLookup[localClauseIdx to tokenIdx]
            val canonStart = matchedFull?.textStart ?: matchedLocal?.textStart ?: dispStart
            val canonEndExcl = matchedFull?.textEndExclusive ?: matchedLocal?.textEndExclusive ?: dispEndExcl

            tokenSpans += RenderedTokenSpan(
                localClauseIndex = localClauseIdx,
                globalClauseIndex = globalClauseIdx,
                clauseId = clause.id,
                tokenIndex = tokenIdx,
                token = token,
                displayStart = dispStart,
                displayEndExclusive = dispEndExcl,
                canonicalStart = canonStart,
                canonicalEndExclusive = canonEndExcl
            )

            if (tokenIdx < tokens.lastIndex) {
                sb.append(token.trailingSpace)
            } else if (hasNextClause) {
                val gapStart = sb.length
                val sep = token.trailingSpace
                val dotIndex: Int
                if (sep.contains('\n')) {
                    sb.append(" ·")
                    dotIndex = gapStart + 1
                    sb.append(sep)
                } else {
                    sb.append(" · ")
                    dotIndex = gapStart + 1
                }
                val gapEndExcl = sb.length
                val nextLocalIdx = nonEmptyLocalIndices[posInNonEmpty + 1]
                val nextClause = clauses[nextLocalIdx]
                val nextGlobalIdx = clauseGlobalIndexById[nextClause.id] ?: (globalClauseStartIndex + nextLocalIdx)
                gapSpans += GapRenderSpan(
                    canonicalGapIndex = nextGlobalIdx,
                    displayStart = gapStart,
                    displayEndExclusive = gapEndExcl,
                    dotDisplayIndex = dotIndex
                )
            }
        }

        clauseSpans += ClauseRenderSpan(
            localClauseIndex = localClauseIdx,
            globalClauseIndex = globalClauseIdx,
            clauseId = clause.id,
            clause = clause,
            displayStart = clauseDisplayStart,
            displayEndExclusive = clauseDisplayEndExcl
        )
    }

    return RenderedDraftModel(
        clauses = clauses,
        displayText = sb.toString(),
        tokenSpans = tokenSpans,
        clauseSpans = clauseSpans,
        gapSpans = gapSpans
    )
}

private fun buildStyledDraftAnnotatedString(
    model: RenderedDraftModel,
    focusedGapIndex: Int?,
    activeBrushDisplayRange: IntRange?,
    accentColor: Color
): AnnotatedString = buildAnnotatedString {
    append(model.displayText)

    // Style every WordToken (100% StudioCrisp for locked words, 90% alpha for unlocked words)
    model.tokenSpans.forEach { span ->
        val isInverted = activeBrushDisplayRange != null &&
            span.displayStart <= activeBrushDisplayRange.last &&
            (span.displayEndExclusive - 1) >= activeBrushDisplayRange.first

        val color = when {
            isInverted -> OledVoid
            span.token.verbatimLocked -> StudioCrisp
            else -> StudioCrisp.copy(alpha = 0.90f)
        }
        addStyle(
            style = SpanStyle(
                color = color,
                fontWeight = if (span.token.verbatimLocked || isInverted) {
                    FontWeight.Medium
                } else {
                    FontWeight.Normal
                }
            ),
            start = span.displayStart,
            end = span.displayEndExclusive
        )
    }

    // Style inter-sentence gap markers (·)
    model.gapSpans.forEach { gap ->
        val isFocusedGap = focusedGapIndex != null && gap.canonicalGapIndex == focusedGapIndex
        addStyle(
            style = SpanStyle(
                color = if (isFocusedGap) accentColor else TelemetryMuted.copy(alpha = 0.50f),
                fontWeight = if (isFocusedGap) FontWeight.Bold else FontWeight.Normal
            ),
            start = gap.displayStart,
            end = gap.displayEndExclusive
        )
    }
}

private fun computeLineRectsForRange(
    layout: TextLayoutResult,
    displayStart: Int,
    displayEndExclusive: Int
): List<Rect> {
    val text = layout.layoutInput.text
    if (text.isEmpty() || displayEndExclusive <= displayStart) return emptyList()

    val start = displayStart.coerceIn(0, text.length - 1)
    val endIncl = (displayEndExclusive - 1).coerceIn(start, text.length - 1)
    val firstLine = layout.getLineForOffset(start)
    val lastLine = layout.getLineForOffset(endIncl)
    val rects = ArrayList<Rect>(lastLine - firstLine + 1)

    for (line in firstLine..lastLine) {
        val lineStart = layout.getLineStart(line)
        val lineEndExcl = layout.getLineEnd(line, visibleEnd = true)
        var segStart = maxOf(start, lineStart)
        var segEndIncl = minOf(endIncl, lineEndExcl - 1)
        while (segStart <= segEndIncl && text[segStart] == '\n') segStart++
        while (segEndIncl >= segStart && text[segEndIncl] == '\n') segEndIncl--
        if (segStart > segEndIncl || segStart >= text.length) continue

        val startBox = layout.getBoundingBox(segStart)
        val endBox = layout.getBoundingBox(segEndIncl)
        val left = minOf(startBox.left, endBox.left)
        val right = maxOf(startBox.right, endBox.right)
        if (right > left) {
            rects += Rect(
                left = left,
                top = layout.getLineTop(line),
                right = right,
                bottom = layout.getLineBottom(line)
            )
        }
    }
    return rects
}

private fun computeBrushAnchorRect(
    layout: TextLayoutResult?,
    model: RenderedDraftModel,
    canonicalRange: IntRange,
    yOffsetPx: Float
): Rect {
    if (layout == null) return Rect.Zero
    val displayRange = model.canonicalToDisplayRange(canonicalRange) ?: return Rect.Zero
    val rects = computeLineRectsForRange(layout, displayRange.first, displayRange.last + 1)
    val first = rects.firstOrNull() ?: return Rect.Zero
    return Rect(
        left = first.left,
        top = first.top + yOffsetPx,
        right = first.right,
        bottom = first.bottom + yOffsetPx
    )
}

private fun clampDisplayOffset(rawOffset: Int, length: Int): Int {
    if (length <= 0) return 0
    return rawOffset.coerceIn(0, length - 1)
}

private fun hitTestGap(
    pos: Offset,
    layout: TextLayoutResult,
    model: RenderedDraftModel
): GapRenderSpan? {
    if (model.gapSpans.isEmpty() || model.displayText.isEmpty()) return null
    val rawOffset = layout.getOffsetForPosition(pos)
    val clamped = clampDisplayOffset(rawOffset, model.displayText.length)
    val direct = model.gapSpans.firstOrNull { clamped in it.displayStart until it.displayEndExclusive }
    if (direct != null) return direct

    // Also allow crisp proximity hit directly on the `·` bounding box center
    return model.gapSpans.firstOrNull { gap ->
        if (gap.dotDisplayIndex !in model.displayText.indices) return@firstOrNull false
        val box = layout.getBoundingBox(gap.dotDisplayIndex)
        val expanded = Rect(
            left = box.left - 12f,
            top = box.top,
            right = box.right + 12f,
            bottom = box.bottom
        )
        expanded.contains(pos)
    }
}

private fun hitTestClause(
    pos: Offset,
    layout: TextLayoutResult,
    model: RenderedDraftModel
): ClauseRenderSpan? {
    if (model.clauseSpans.isEmpty() || model.displayText.isEmpty()) return null
    val rawOffset = layout.getOffsetForPosition(pos)
    val clamped = clampDisplayOffset(rawOffset, model.displayText.length)

    // Direct span hit
    model.clauseSpans.firstOrNull { clamped in it.displayStart until it.displayEndExclusive }?.let {
        return it
    }

    // Nearest clause span by display offset if tap landed on whitespace/trailing edge
    return model.clauseSpans.minByOrNull { span ->
        when {
            clamped < span.displayStart -> span.displayStart - clamped
            clamped >= span.displayEndExclusive -> clamped - (span.displayEndExclusive - 1)
            else -> 0
        }
    }
}

private fun hitTestWordCanonicalOffset(
    pos: Offset,
    layout: TextLayoutResult,
    model: RenderedDraftModel
): Int? {
    if (model.tokenSpans.isEmpty() || model.displayText.isEmpty()) return null
    val rawOffset = layout.getOffsetForPosition(pos)
    val clamped = clampDisplayOffset(rawOffset, model.displayText.length)

    val direct = model.tokenSpans.firstOrNull { clamped in it.displayStart until it.displayEndExclusive }
    if (direct != null) return direct.canonicalStart

    // Fall back to nearest word token on the same line
    val nearest = model.tokenSpans.minByOrNull { span ->
        when {
            clamped < span.displayStart -> span.displayStart - clamped
            clamped >= span.displayEndExclusive -> clamped - (span.displayEndExclusive - 1)
            else -> 0
        }
    }
    return nearest?.canonicalStart
}

private fun snapBrushSpan(
    layout: TextLayoutResult,
    model: RenderedDraftModel,
    anchorDisplayOffset: Int,
    currentDisplayOffset: Int
): SnappedBrushResult? {
    if (model.tokenSpans.isEmpty() || model.displayText.isEmpty()) return null
    val safeAnchor = clampDisplayOffset(anchorDisplayOffset, model.displayText.length)
    val safeCurrent = clampDisplayOffset(currentDisplayOffset, model.displayText.length)

    val anchorWord = layout.getWordBoundary(safeAnchor)
    val currentWord = layout.getWordBoundary(safeCurrent)
    val rawStart = minOf(anchorWord.start, currentWord.start)
    val rawEndExcl = maxOf(anchorWord.end, currentWord.end).coerceAtLeast(rawStart + 1)

    var firstIdx = -1
    var lastIdx = -1
    model.tokenSpans.forEachIndexed { idx, span ->
        val overlaps = span.displayStart < rawEndExcl && span.displayEndExclusive > rawStart
        if (overlaps) {
            if (firstIdx == -1) firstIdx = idx
            lastIdx = idx
        }
    }

    // If pointer sits strictly over whitespace/gap marker, snap to nearest word token
    if (firstIdx == -1) {
        val nearestIdx = model.tokenSpans.indices.minByOrNull { idx ->
            val span = model.tokenSpans[idx]
            when {
                safeCurrent < span.displayStart -> span.displayStart - safeCurrent
                safeCurrent >= span.displayEndExclusive -> safeCurrent - (span.displayEndExclusive - 1)
                else -> 0
            }
        } ?: return null
        firstIdx = nearestIdx
        lastIdx = nearestIdx
    }

    val firstToken = model.tokenSpans[firstIdx]
    val lastToken = model.tokenSpans[lastIdx]
    return SnappedBrushResult(
        displayRange = firstToken.displayStart..(lastToken.displayEndExclusive - 1),
        canonicalRange = firstToken.canonicalStart..(lastToken.canonicalEndExclusive - 1),
        tokenIndexPair = firstIdx to lastIdx
    )
}

private fun resolveReorderTargetIndex(
    dragY: Float,
    model: RenderedDraftModel,
    layout: TextLayoutResult,
    containerHeightPx: Float
): Int {
    val count = model.clauses.size
    if (count <= 1) return 0

    // Prefer evenly partitioned overlay rows when container height is known, blending with text layout
    val effectiveHeight = maxOf(containerHeightPx, layout.size.height.toFloat()).coerceAtLeast(1f)
    val bandHeight = effectiveHeight / count.toFloat()
    return (dragY / bandHeight).toInt().coerceIn(0, count - 1)
}

private fun performDoubleTickHaptic(view: android.view.View) {
    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    view.postDelayed(
        { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
        45L
    )
}

private fun performStrikeCutHaptic(view: android.view.View) {
    val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.REJECT
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }
    view.performHapticFeedback(constant)
}
