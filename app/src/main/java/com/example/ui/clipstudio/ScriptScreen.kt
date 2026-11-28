package com.example.ui.clipstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.draft.BrushNudge
import com.example.draft.CandidateDraft
import com.example.draft.DraftStateReducer
import com.example.ui.design.AcCard
import com.example.ui.design.AcField
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcMediaKey
import com.example.ui.design.AcMediaWell
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
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
 * 02 SCRIPT (design v2): step header with new / length / keyboard / undo keys, posture keys,
 * the length row, the script as Body text on cards, the phrase swap card, and the dock
 * (`pin` + `keep this` while a phrase is selected; hold-to-say media key + `make the video` otherwise).
 */
@Composable
fun ScriptScreen(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel,
    onRequestMic: (() -> Unit) -> Unit,
    onCreateVideo: (plainText: String) -> Unit,
    onNavigate: (Int) -> Unit = {},
    onNewIdea: () -> Unit = {
        vm.resetNewDraft()
        onNavigate(1)
    },
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
            .background(AcTokens.Chassis)
            .testTag("cs_script_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            StudioTopBar(activeStage = 2, onNavigate = onNavigate) {
                AcKey(
                    icon = Icons.Sharp.Add,
                    contentDescription = "New idea",
                    onClick = onNewIdea,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_new_idea")
                )
                AcKey(
                    label = mmss(estimatedMs),
                    icon = Icons.Sharp.Timer,
                    onClick = vm::toggleLengthRow,
                    selected = state.lengthRowOpen,
                    enabled = candidate != null,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_length_toggle")
                )
                AcKey(
                    icon = Icons.Sharp.Keyboard,
                    contentDescription = "Edit full script",
                    onClick = vm::toggleRawKeyboardMode,
                    selected = state.rawKeyboardMode,
                    enabled = candidate != null || doc.seedTranscript.isNotBlank(),
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_keyboard")
                )
                AcKey(
                    icon = Icons.AutoMirrored.Sharp.Undo,
                    contentDescription = "Undo script change",
                    onClick = { vm.stepGlobalSnapshot(-1) },
                    enabled = candidate?.canUndoGlobal == true,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_script_undo")
                )
            }

            StudioStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

            if (state.outlineChangedSinceScript && candidate != null && !state.rawKeyboardMode) {
                AcCard(
                    contentPadding = PaddingValues(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
                        .testTag("cs_script_outline_stale")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Outline changed in 01 SPARK",
                            style = AcType.BodySmall.copy(color = AcTokens.Red),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        AcKey(
                            label = "regenerate",
                            onClick = vm::writeVideoScript,
                            enabled = !state.isBusy,
                            size = AcKeySize.Small,
                            modifier = Modifier.testTag("cs_script_regen_from_outline")
                        )
                    }
                }
            }

            if (state.rawKeyboardMode) {
                FullScriptKeyboardEditor(
                    initialText = activeText.ifBlank { doc.seedTranscript },
                    onCancel = vm::toggleRawKeyboardMode,
                    onApply = vm::applyFullScriptKeyboardEdit,
                    modifier = Modifier.weight(1f)
                )
            } else {
                if (doc.candidates.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        doc.candidates.forEachIndexed { i, c ->
                            val selected = i == doc.activeCandidateIndex
                            val candWords = countWords(c.plainText())
                            AcKey(
                                onClick = { vm.selectCandidateSlot(i) },
                                selected = selected,
                                size = AcKeySize.Small,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("cs_version_$i")
                            ) { fg ->
                                Text(c.postureLabel.lowercase(), style = AcType.Label.copy(color = fg), maxLines = 1)
                                Text(
                                    text = candWords.toString(),
                                    style = AcType.Caption.copy(color = fg),
                                    maxLines = 1,
                                    modifier = if (selected && !state.lengthRowOpen) Modifier.testTag("cs_word_count") else Modifier
                                )
                            }
                        }
                    }
                }

                if (state.lengthRowOpen && candidate != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
                            .testTag("cs_length_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AcKey(
                            label = "shorter",
                            icon = Icons.Sharp.Remove,
                            onClick = { vm.stepScriptLength(-12) },
                            enabled = !state.isBusy,
                            size = AcKeySize.Small,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cs_length_shorter")
                        )
                        Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${mmss(estimatedMs)} · $words words",
                                style = AcType.Label,
                                maxLines = 1,
                                modifier = Modifier.testTag("cs_word_count")
                            )
                            Text("target ${mmss(targetSec * 1000L)}".uppercase(), style = AcType.Caption, maxLines = 1)
                        }
                        AcKey(
                            label = "longer",
                            trailingIcon = Icons.Sharp.Add,
                            onClick = { vm.stepScriptLength(12) },
                            enabled = !state.isBusy,
                            size = AcKeySize.Small,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cs_length_longer")
                        )
                    }
                }

                AcCard(
                    contentPadding = PaddingValues(0.dp),
                    verticalArrangement = Arrangement.Top,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                ) {
                    if (candidate == null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No script yet. Go to 01 SPARK and tap write the script.", style = AcType.Body.copy(color = AcTokens.Ink2))
                            AcKey(
                                label = "start a new idea",
                                onClick = onNewIdea,
                                modifier = Modifier.testTag("cs_script_empty_new_idea")
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
                                            .hairlineBottom()
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                            .testTag("cs_script_hint"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Sharp.TouchApp, contentDescription = null, tint = AcTokens.Ink2, modifier = Modifier.size(20.dp))
                                        Text("Tap any phrase to swap or pin it.", style = AcType.BodySmall)
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

                val selectedRange = state.selectedPhrase
                if (selectedRange != null && candidate != null) {
                    PhraseSwapSheet(state = state, vm = vm)
                }

                if (selectedRange != null && candidate != null) {
                    val pinned = vm.isRangeLocked(selectedRange)
                    StudioDock(
                        leading = {
                            AcKey(
                                label = if (pinned) "pinned" else "pin",
                                icon = Icons.Sharp.PushPin,
                                selected = pinned,
                                onClick = { vm.togglePhraseLock(selectedRange) },
                                modifier = Modifier.testTag("cs_pin_phrase")
                            )
                        },
                        primaryLabel = "keep this",
                        primaryIcon = Icons.Sharp.Check,
                        primaryTag = "cs_keep_phrase",
                        onPrimaryClick = vm::keepSelectedPhrase
                    )
                } else {
                    StudioDock(
                        leading = {
                            AcMediaWell(padding = 6.dp) {
                                AcMediaKey(
                                    icon = Icons.Sharp.Mic,
                                    contentDescription = null,
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
                                    held = state.isRecordingSplice,
                                    enabled = candidate != null && !state.isRecordingSeed && !state.isRecordingSteer,
                                    caption = if (state.isRecordingSplice) "stop" else "hold",
                                    semanticsLabel = if (state.isRecordingSplice) "STOP" else "HOLD",
                                    size = 54.dp,
                                    modifier = Modifier.testTag("cs_speak_own_words")
                                )
                            }
                        },
                        primaryLabel = "make the video",
                        primaryIcon = Icons.AutoMirrored.Sharp.ArrowForward,
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
            StudioToast(message = state.toastMessage, onDismiss = vm::dismissToast)
            VoiceGlowDitherOverlay(active = state.isAnyRecording, level = state.liveRmsLevel)
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
                    addStyle(SpanStyle(background = AcTokens.Hairline), r.first, endExcl)
                }
            }
            localSelection?.let { r ->
                val endExcl = (r.last + 1).coerceAtMost(para.text.length)
                if (r.first < endExcl) {
                    addStyle(SpanStyle(background = AcTokens.Red, color = AcTokens.DisplayInk), r.first, endExcl)
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (!isLast) Modifier.hairlineBottom() else Modifier)
            .padding(top = 14.dp, end = 14.dp, bottom = 14.dp)
            .testTag("cs_para_${para.index}"),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = timeLabel,
            style = AcType.Caption.copy(lineHeight = AcType.Body.lineHeight),
            maxLines = 1,
            modifier = Modifier
                .width(56.dp)
                .padding(start = 16.dp)
                .testTag("cs_para_label_${para.index}")
        )

        Text(
            text = annotated,
            style = AcType.Body,
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
                            val leftX = if (line == startLine) l.getBoundingBox(safeStart).left else l.getLineLeft(line)
                            val rightX = if (line == endLine) l.getBoundingBox(safeEnd).right else l.getLineRight(line)
                            val y = l.getLineBottom(line) - strokePx
                            if (rightX > leftX) {
                                drawLine(AcTokens.Ink, Offset(leftX, y), Offset(rightX, y), strokePx)
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
    AcCard(
        tag = null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, top = 12.dp, end = 14.dp)
            .testTag("cs_phrase_sheet"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionCaption("Swap this phrase", modifier = Modifier.weight(1f))
            AcKey(
                icon = Icons.Sharp.Close,
                contentDescription = "Close phrase options",
                onClick = vm::dismissBrushStrip,
                size = AcKeySize.Small,
                modifier = Modifier.testTag("cs_selection_close")
            )
        }

        if (state.brushAlternatives.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.isBusy) ThinkingOrbSolving(size = 24.dp, tint = AcTokens.Ink)
                Text(
                    text = if (state.isBusy) (state.busyLabel ?: "Finding alternatives…") else "No alternatives yet.",
                    style = AcType.BodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            state.brushAlternatives.take(4).forEachIndexed { i, alt ->
                val isCurrent = i == state.brushActiveAltIndex
                AcKey(
                    onClick = { vm.selectBrushAlternative(i) },
                    selected = isCurrent,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cs_option_$i")
                ) { fg ->
                    Text(if (isCurrent) "▶" else "${i + 1}", style = AcType.Label.copy(color = fg), modifier = Modifier.width(18.dp))
                    Text(
                        text = if (alt.isBlank()) "cut this phrase" else alt,
                        style = AcType.BodySmall.copy(color = fg),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple("punchier", Icons.Sharp.Bolt, BrushNudge.SHARPER),
                Triple("plainer", Icons.AutoMirrored.Sharp.ShortText, BrushNudge.SIMPLER),
                Triple("more", Icons.Sharp.Refresh, BrushNudge.MORE)
            ).forEach { (label, icon, nudge) ->
                AcKey(
                    label = label,
                    icon = icon,
                    selected = state.activeBrushNudge == nudge,
                    enabled = !state.isBusy,
                    onClick = {
                        if (nudge == BrushNudge.MORE) vm.refreshPhraseAlternatives() else vm.performBrushNudge(nudge)
                    },
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_nudge_${nudge.wire}")
                )
            }
        }
    }
}

@Composable
private fun FullScriptKeyboardEditor(
    initialText: String,
    onCancel: () -> Unit,
    onApply: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    LaunchedEffect(initialText) {
        if (text.isEmpty() && initialText.isNotEmpty()) text = initialText
    }
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionCaption("Edit full script · pinned words stay locked")
            AcField(
                value = text,
                onValueChange = { text = it },
                minLines = 6,
                textStyle = AcType.Body,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("cs_raw_editor")
            )
        }

        StudioDock(
            leading = {
                AcKey(
                    label = "cancel",
                    icon = Icons.Sharp.Close,
                    onClick = onCancel,
                    modifier = Modifier.testTag("cs_raw_cancel")
                )
            },
            primaryLabel = "save script",
            primaryIcon = Icons.Sharp.Check,
            primaryEnabled = text.isNotBlank(),
            primaryTag = "cs_raw_apply",
            onPrimaryClick = { onApply(text) }
        )
    }
}
