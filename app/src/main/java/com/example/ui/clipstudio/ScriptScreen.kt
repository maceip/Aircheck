package com.example.ui.clipstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowForward
import androidx.compose.material.icons.automirrored.sharp.ShortText
import androidx.compose.material.icons.automirrored.sharp.Undo
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.Bolt
import androidx.compose.material.icons.sharp.Check
import androidx.compose.material.icons.sharp.Close
import androidx.compose.material.icons.sharp.Keyboard
import androidx.compose.material.icons.sharp.Mic
import androidx.compose.material.icons.sharp.PushPin
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material.icons.sharp.Remove
import androidx.compose.material.icons.sharp.Timer
import androidx.compose.material.icons.sharp.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.draft.BrushNudge
import com.example.draft.CandidateDraft
import com.example.draft.DraftStateReducer
import com.example.ui.draft.DraftDeckUiState
import com.example.ui.draft.DraftDeckViewModel
import kotlin.math.roundToInt

/** A paragraph of the active script with its absolute offset in the plain text. */
private data class Para(val index: Int, val start: Int, val text: String) {
    val range: IntRange get() = start until start + text.length
}

private fun paragraphsOf(text: String): List<Para> {
    val out = mutableListOf<Para>()
    var cursor = 0
    val sep = Regex("\\n\\s*\\n")
    sep.findAll(text).forEach { m ->
        if (m.range.first > cursor) out += Para(out.size, cursor, text.substring(cursor, m.range.first))
        cursor = m.range.last + 1
    }
    if (cursor < text.length) out += Para(out.size, cursor, text.substring(cursor))
    return out.filter { it.text.isNotBlank() }.mapIndexed { i, p -> p.copy(index = i) }
}

private fun countWords(text: String): Int =
    text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

private fun lockedRuns(candidate: CandidateDraft): List<IntRange> {
    val tokens = DraftStateReducer.flattenClauses(candidate.clauses).second
    val runs = mutableListOf<IntRange>()
    var start = -1
    var end = -1
    tokens.forEach { ft ->
        if (ft.token.verbatimLocked) {
            if (start < 0) start = ft.textStart
            end = ft.textEndExclusive - 1
        } else if (start >= 0) {
            runs += start..end
            start = -1
        }
    }
    if (start >= 0) runs += start..end
    return runs
}

private fun clauseAt(candidate: CandidateDraft, offset: Int): Pair<String, Int>? {
    val tokens = DraftStateReducer.flattenClauses(candidate.clauses).second
    val hit = tokens.firstOrNull { offset < it.fullEndExclusive } ?: tokens.lastOrNull() ?: return null
    return hit.clauseId to hit.clauseIndex
}

/**
 * 02 SCRIPT (Clip Studio v5):
 * - 56dp top bar: `01 · 02 SCRIPT · 03`, length key (`timer` + `1:00`), `keyboard` key, and `undo` (no settings gear).
 * - Sunken 3-way posture segmented control (`direct 148 · punchy 112 · story 161`).
 * - Expandable 52dp Length Row (`− shorter` / `0:59 · 148 words` / `longer +`, ±12 words).
 * - Sunken #000 scrollable script panel with first-tap hint, 56dp time gutter, 20/30sp Geist w370 text,
 *   single-tap phrase selection (`#EBEBEF` bg / `#000` text), and `#1E1E24` + 1dp white underline on pinned phrases.
 * - Raised `#1A1A1F` `SWAP THIS PHRASE` card (46dp options + `punchier` / `plainer` / `more` nudges).
 * - Context-switching dock (`pin` + `keep this` while a phrase is selected; `hold: say` + `make the video` otherwise).
 */
@Composable
fun ScriptScreen(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel,
    onRequestMic: (() -> Unit) -> Unit,
    onCreateVideo: (plainText: String) -> Unit,
    onNavigate: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    renderGlobalOverlays: Boolean = true
) {
    val doc = state.document
    val candidate = doc.activeCandidate
    val activeText = candidate?.plainText().orEmpty()
    val words = remember(activeText) { countWords(activeText) }
    val estimatedMs = remember(words) { ((words / 2.5f).roundToInt() * 1000L).coerceAtLeast(0L) }
    val targetSec = if (doc.arcTargetSec > 0) doc.arcTargetSec else DraftDeckViewModel.ARC_TARGET_SEC

    BackHandler(enabled = state.rawKeyboardMode) { vm.toggleRawKeyboardMode() }
    BackHandler(enabled = !state.rawKeyboardMode && state.selectedPhrase != null) { vm.dismissBrushStrip() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Cs.Body)
            .testTag("cs_script_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            CsTopBar(
                activeStage = 2,
                onNavigate = onNavigate
            ) {
                // Length key (`timer` + estimated runtime)
                CsKey(
                    onClick = vm::toggleLengthRow,
                    role = if (state.lengthRowOpen) KeyRole.DOWN else KeyRole.STD,
                    enabled = candidate != null,
                    radius = 12.dp,
                    horizontalPadding = 12.dp,
                    contentAlignment = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("cs_length_toggle")
                ) { tint ->
                    Icon(
                        imageVector = Icons.Sharp.Timer,
                        contentDescription = "Adjust script length",
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = mmss(estimatedMs),
                        style = mono(13.sp, tint, FontWeight.Medium),
                        maxLines = 1
                    )
                }

                // Full-script keyboard edit key
                CsIconKey(
                    icon = Icons.Sharp.Keyboard,
                    contentDescription = "Edit full script",
                    onClick = vm::toggleRawKeyboardMode,
                    active = state.rawKeyboardMode,
                    enabled = candidate != null || doc.seedTranscript.isNotBlank(),
                    modifier = Modifier.testTag("cs_keyboard")
                )

                // Undo key
                CsIconKey(
                    icon = Icons.AutoMirrored.Sharp.Undo,
                    contentDescription = "Undo script change",
                    onClick = { vm.stepGlobalSnapshot(-1) },
                    enabled = candidate?.canUndoGlobal == true,
                    modifier = Modifier.testTag("cs_script_undo")
                )
            }

            CsStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

            if (state.rawKeyboardMode) {
                FullScriptKeyboardEditor(
                    initialText = activeText.ifBlank { doc.seedTranscript },
                    onCancel = vm::toggleRawKeyboardMode,
                    onApply = vm::applyFullScriptKeyboardEdit,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Sunken 3-way posture segmented control
                if (doc.candidates.isNotEmpty()) {
                    CsSegmentedRow(
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    ) {
                        doc.candidates.forEachIndexed { i, c ->
                            val selected = i == doc.activeCandidateIndex
                            val candWords = countWords(c.plainText())
                            CsSegment(
                                selected = selected,
                                onClick = { vm.selectCandidateSlot(i) },
                                height = 48.dp,
                                modifier = Modifier.testTag("cs_version_$i")
                            ) { tint ->
                                Text(
                                    text = c.postureLabel.lowercase(),
                                    style = mono(13.sp, tint, FontWeight.Medium),
                                    maxLines = 1
                                )
                                Text(
                                    text = candWords.toString(),
                                    style = mono(11.sp, tint.copy(alpha = 0.65f)),
                                    maxLines = 1,
                                    modifier = if (selected && !state.lengthRowOpen) {
                                        Modifier.testTag("cs_word_count")
                                    } else {
                                        Modifier
                                    }
                                )
                            }
                        }
                    }
                }

                // Expandable 52dp Length Row (±12 words)
                if (state.lengthRowOpen && candidate != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                            .height(52.dp)
                            .testTag("cs_length_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CsKey(
                            onClick = { vm.stepScriptLength(-12) },
                            enabled = !state.isBusy,
                            radius = 14.dp,
                            horizontalPadding = 10.dp,
                            contentAlignment = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("cs_length_shorter")
                        ) { tint ->
                            Icon(Icons.Sharp.Remove, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                            Text("shorter", style = mono(13.sp, tint), maxLines = 1)
                        }

                        Column(
                            modifier = Modifier.weight(1.3f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${mmss(estimatedMs)} · $words words",
                                style = mono(15.sp, Cs.Ink, FontWeight.Medium),
                                maxLines = 1,
                                modifier = Modifier.testTag("cs_word_count")
                            )
                            Text(
                                text = "target ${mmss(targetSec * 1000L)}",
                                style = mono(11.sp, Cs.Muted),
                                maxLines = 1
                            )
                        }

                        CsKey(
                            onClick = { vm.stepScriptLength(12) },
                            enabled = !state.isBusy,
                            radius = 14.dp,
                            horizontalPadding = 10.dp,
                            contentAlignment = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("cs_length_longer")
                        ) { tint ->
                            Text("longer", style = mono(13.sp, tint), maxLines = 1)
                            Icon(Icons.Sharp.Add, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Sunken #000 scrollable script panel
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .sunkenPanel(radius = 18.dp)
                ) {
                    if (candidate == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No script yet. Go to 01 SPARK and tap write the script.",
                                style = sans(16.sp, Cs.Muted, 23.sp)
                            )
                        }
                    } else {
                        val paras = remember(activeText) { paragraphsOf(activeText) }
                        val locks = remember(candidate) { lockedRuns(candidate) }
                        val showHint = !state.scriptHintSeen && state.selectedPhrase == null

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("cs_script_body")
                        ) {
                            if (showHint) {
                                item(key = "script_tap_hint") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .hairlineBottom(Cs.RowDivider)
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                            .testTag("cs_script_hint"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Sharp.TouchApp,
                                            contentDescription = null,
                                            tint = Cs.Muted,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "Tap any phrase to swap or pin it.",
                                            style = sans(14.sp, Cs.Muted, 19.sp)
                                        )
                                    }
                                }
                            }

                            itemsIndexed(paras, key = { i, _ -> i }) { i, para ->
                                val timeLabel = remember(i, paras.size, doc.arcBeats, estimatedMs) {
                                    val beatStart = doc.arcBeats.getOrNull(i)?.startSec
                                    if (beatStart != null) {
                                        mmss(beatStart * 1000L)
                                    } else {
                                        val approxMs = if (paras.size <= 1) 0L else (estimatedMs * i) / paras.size
                                        mmss(approxMs)
                                    }
                                }
                                ScriptParagraphRow(
                                    para = para,
                                    timeLabel = timeLabel,
                                    selectedRange = state.selectedPhrase,
                                    locks = locks,
                                    onOffsetTap = { globalOffset ->
                                        val curSel = state.selectedPhrase
                                        if (curSel != null && globalOffset in curSel) {
                                            vm.keepSelectedPhrase()
                                        } else {
                                            vm.selectPhraseSingleTap(globalOffset)
                                        }
                                    },
                                    isLast = i == paras.lastIndex
                                )
                            }
                        }
                    }
                }

                // Phrase Sheet (raised card above the dock while a phrase is selected)
                val selectedRange = state.selectedPhrase
                if (selectedRange != null && candidate != null) {
                    PhraseSwapSheet(
                        state = state,
                        vm = vm
                    )
                }

                // Context-switching Bottom Dock
                if (selectedRange != null && candidate != null) {
                    val pinned = vm.isRangeLocked(selectedRange)
                    CsDock(
                        leading = {
                            CsDockSideKey(
                                label = if (pinned) "pinned" else "pin",
                                icon = Icons.Sharp.PushPin,
                                active = pinned,
                                onClick = { vm.togglePhraseLock(selectedRange) },
                                modifier = Modifier.testTag("cs_pin_phrase")
                            )
                        },
                        primaryLabel = "keep this",
                        primaryTrailingIcon = Icons.Sharp.Check,
                        primaryRole = KeyRole.COMMIT,
                        primaryEnabled = true,
                        primaryTag = "cs_keep_phrase",
                        onPrimaryClick = vm::keepSelectedPhrase
                    )
                } else {
                    CsDock(
                        leading = {
                            CsVoiceDockKey(
                                label = if (state.isRecordingSplice) "STOP" else "HOLD",
                                icon = Icons.Sharp.Mic,
                                held = state.isRecordingSplice,
                                enabled = candidate != null && !state.isRecordingSeed && !state.isRecordingSteer,
                                onPress = {
                                    onRequestMic {
                                        val c = candidate
                                        val range = state.activeBrushRange
                                        if (c != null && range != null) {
                                            vm.focusClause(clauseAt(c, range.first)?.first)
                                        } else {
                                            vm.focusGap(null)
                                        }
                                        vm.startVoiceSplice()
                                    }
                                },
                                onRelease = vm::stopVoiceSplice,
                                modifier = Modifier.testTag("cs_speak_own_words")
                            )
                        },
                        primaryLabel = "make the video",
                        primaryTrailingIcon = Icons.AutoMirrored.Sharp.ArrowForward,
                        primaryRole = KeyRole.COMMIT,
                        primaryEnabled = candidate != null && !state.isBusy,
                        primaryTag = "cs_create_video",
                        onPrimaryClick = { candidate?.let { onCreateVideo(it.plainText()) } },
                        isBusy = state.isBusy,
                        busyLabel = state.busyLabel ?: "rewriting script…"
                    )
                }
            }
        }

        if (renderGlobalOverlays) {
            CsToastOverlay(
                message = state.toastMessage,
                onDismiss = vm::dismissToast
            )
            VoiceGlowDitherOverlay(
                active = state.isAnyRecording,
                level = state.liveRmsLevel
            )
        }
    }
}

@Composable
private fun ScriptParagraphRow(
    para: Para,
    timeLabel: String,
    selectedRange: IntRange?,
    locks: List<IntRange>,
    onOffsetTap: (Int) -> Unit,
    isLast: Boolean
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val localLocks = remember(para, locks) {
        locks.mapNotNull { r ->
            val s = maxOf(r.first, para.start) - para.start
            val e = minOf(r.last, para.range.last) - para.start
            if (s <= e) s..e else null
        }
    }

    val localSelection = remember(para, selectedRange) {
        selectedRange?.let { r ->
            val s = maxOf(r.first, para.start) - para.start
            val e = minOf(r.last, para.range.last) - para.start
            if (s <= e) s..e else null
        }
    }

    val annotated: AnnotatedString = remember(para.text, localLocks, localSelection) {
        buildAnnotatedString {
            append(para.text)
            localLocks.forEach { r ->
                val endExcl = (r.last + 1).coerceAtMost(para.text.length)
                if (r.first < endExcl) {
                    addStyle(SpanStyle(background = Cs.Pinned), r.first, endExcl)
                }
            }
            localSelection?.let { r ->
                val endExcl = (r.last + 1).coerceAtMost(para.text.length)
                if (r.first < endExcl) {
                    addStyle(SpanStyle(background = Cs.Ink, color = Cs.Black), r.first, endExcl)
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!isLast) Modifier.hairlineBottom(Cs.RowDivider) else Modifier)
            .padding(top = 14.dp, end = 14.dp, bottom = 14.dp)
            .testTag("cs_para_${para.index}"),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = timeLabel,
            style = mono(11.sp, Cs.Muted).copy(lineHeight = 30.sp),
            maxLines = 1,
            modifier = Modifier
                .width(56.dp)
                .padding(start = 16.dp)
                .testTag("cs_para_label_${para.index}")
        )

        Text(
            text = annotated,
            style = sans(20.sp, Cs.Ink, 30.sp, FontWeight(370)),
            onTextLayout = { layout = it },
            modifier = Modifier
                .weight(1f)
                .drawBehind {
                    val l = layout ?: return@drawBehind
                    val strokePx = 1.dp.toPx()
                    localLocks.forEach { r ->
                        if (localSelection != null && r.first <= localSelection.last && localSelection.first <= r.last) {
                            return@forEach
                        }
                        val safeStart = r.first.coerceIn(0, (l.layoutInput.text.length - 1).coerceAtLeast(0))
                        val safeEnd = r.last.coerceIn(safeStart, (l.layoutInput.text.length - 1).coerceAtLeast(0))
                        val startLine = l.getLineForOffset(safeStart)
                        val endLine = l.getLineForOffset(safeEnd)
                        for (line in startLine..endLine) {
                            val leftX = if (line == startLine) {
                                l.getBoundingBox(safeStart).left
                            } else {
                                l.getLineLeft(line)
                            }
                            val rightX = if (line == endLine) {
                                l.getBoundingBox(safeEnd).right
                            } else {
                                l.getLineRight(line)
                            }
                            val y = l.getLineBottom(line) - strokePx
                            if (rightX > leftX) {
                                drawLine(Cs.White, Offset(leftX, y), Offset(rightX, y), strokePx)
                            }
                        }
                    }
                }
                .pointerInput(para.index, para.start, para.text) {
                    detectTapGestures { pos ->
                        val l = layout ?: return@detectTapGestures
                        if (para.text.isEmpty()) return@detectTapGestures
                        val localOff = l.getOffsetForPosition(pos).coerceIn(0, para.text.length - 1)
                        onOffsetTap(para.start + localOff)
                    }
                }
        )
    }
}

@Composable
private fun PhraseSwapSheet(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, top = 12.dp, end = 10.dp)
            .raisedCard(radius = 18.dp)
            .padding(12.dp)
            .testTag("cs_phrase_sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SWAP THIS PHRASE", style = SectionLabelStyle, modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = vm::dismissBrushStrip)
                    .testTag("cs_selection_close"),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Sharp.Close, contentDescription = "Close phrase options", tint = Cs.Muted, modifier = Modifier.size(22.dp))
            }
        }

        if (state.brushAlternatives.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .neumorphicSurface(role = KeyRole.DOWN, pressed = true, radius = 14.dp, enabled = true)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.isBusy) {
                    ThinkingOrbSolving(size = 20.dp, tint = Cs.Ink)
                }
                Text(
                    text = if (state.isBusy) (state.busyLabel ?: "Finding alternatives…") else "No alternatives yet.",
                    style = sans(15.sp, Cs.Muted, 20.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            state.brushAlternatives.take(4).forEachIndexed { i, alt ->
                val isCurrent = i == state.brushActiveAltIndex
                CsKey(
                    onClick = { vm.selectBrushAlternative(i) },
                    role = if (isCurrent) KeyRole.DOWN else KeyRole.STD,
                    radius = 14.dp,
                    horizontalPadding = 14.dp,
                    contentAlignment = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .testTag("cs_option_$i")
                ) { tint ->
                    Text(
                        text = if (isCurrent) "▶" else "${i + 1}",
                        style = mono(13.sp, tint),
                        modifier = Modifier.width(18.dp)
                    )
                    Text(
                        text = if (alt.isBlank()) "cut this phrase" else alt,
                        style = sans(16.sp, tint, 21.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                    )
                }
            }
        }

        // Three 44dp nudge pills: punchier (`sharper`), plainer (`simpler`), more (`more`)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NudgePillKey(
                label = "punchier",
                icon = Icons.Sharp.Bolt,
                active = state.activeBrushNudge == BrushNudge.SHARPER,
                enabled = !state.isBusy,
                onClick = { vm.performBrushNudge(BrushNudge.SHARPER) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_nudge_${BrushNudge.SHARPER.wire}")
            )
            NudgePillKey(
                label = "plainer",
                icon = Icons.AutoMirrored.Sharp.ShortText,
                active = state.activeBrushNudge == BrushNudge.SIMPLER,
                enabled = !state.isBusy,
                onClick = { vm.performBrushNudge(BrushNudge.SIMPLER) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_nudge_${BrushNudge.SIMPLER.wire}")
            )
            NudgePillKey(
                label = "more",
                icon = Icons.Sharp.Refresh,
                active = state.activeBrushNudge == BrushNudge.MORE,
                enabled = !state.isBusy,
                onClick = vm::refreshPhraseAlternatives,
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_nudge_${BrushNudge.MORE.wire}")
            )
        }
    }
}

@Composable
private fun NudgePillKey(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CsKey(
        onClick = onClick,
        role = if (active) KeyRole.ACTIVE else KeyRole.STD,
        enabled = enabled,
        radius = 12.dp,
        pip = !active,
        horizontalPadding = 8.dp,
        contentAlignment = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        modifier = modifier.height(44.dp)
    ) { tint ->
        if (active) {
            CsLedDot(color = Cs.Voice, size = 6.dp)
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, style = mono(12.sp, tint), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun FullScriptKeyboardEditor(
    initialText: String,
    onCancel: () -> Unit,
    onApply: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val fieldState = rememberTextFieldState(initialText = initialText)
    LaunchedEffect(initialText) {
        if (fieldState.text.isEmpty() && initialText.isNotEmpty()) {
            fieldState.setTextAndPlaceCursorAtEnd(initialText)
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .sunkenPanel(radius = 18.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("EDIT FULL SCRIPT · PINNED WORDS STAY LOCKED", style = SectionLabelStyle)
            BasicTextField(
                state = fieldState,
                textStyle = sans(20.sp, Cs.Ink, 30.sp, FontWeight(370)),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 6),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("cs_raw_editor")
            )
        }

        CsDock(
            leading = {
                CsDockSideKey(
                    label = "cancel",
                    icon = Icons.Sharp.Close,
                    onClick = onCancel,
                    modifier = Modifier.testTag("cs_raw_cancel")
                )
            },
            primaryLabel = "save script",
            primaryTrailingIcon = Icons.Sharp.Check,
            primaryEnabled = fieldState.text.toString().isNotBlank(),
            primaryTag = "cs_raw_apply",
            onPrimaryClick = { onApply(fieldState.text.toString()) }
        )
    }
}
