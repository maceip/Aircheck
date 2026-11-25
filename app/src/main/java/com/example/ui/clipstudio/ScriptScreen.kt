package com.example.ui.clipstudio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.draft.BrushNudge
import com.example.draft.CandidateDraft
import com.example.draft.DraftApiClient
import com.example.draft.DraftStateReducer
import com.example.draft.XAttachment
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

private fun wordRangeAt(text: String, offset: Int): IntRange? =
    Regex("\\S+").findAll(text).firstOrNull { offset in it.range }?.range

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
 * 02 SCRIPT: three rig-written postures, unobstructed script eye zone, tap-to-select phrase swaps
 * with tone nudges, pin (verbatim lock), per-clause prior versions, density scrub, X network posts,
 * and voice splice.
 */
@Composable
fun ScriptScreen(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel,
    onRequestMic: (() -> Unit) -> Unit,
    onCreateVideo: (plainText: String) -> Unit,
    onNavigate: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val doc = state.document
    val candidate = doc.activeCandidate
    var anchor by remember(doc.id, doc.activeCandidateIndex) { mutableStateOf<IntRange?>(null) }
    var focusedPara by rememberSaveable(doc.id) { mutableStateOf<Int?>(null) }
    var netOpen by rememberSaveable { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Cs.Black).testTag("cs_script_root")) {
        Column(Modifier.fillMaxSize()) {
            CsHeader {
                Text(
                    "02 SCRIPT",
                    style = ndot(12.sp),
                    maxLines = 1,
                    modifier = Modifier.clickable { onNavigate(1) }.testTag("cs_crumb_1")
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (doc.candidates.isNotEmpty()) {
                        CsWell(padding = 2.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                doc.candidates.forEachIndexed { i, c ->
                                    val on = i == doc.activeCandidateIndex
                                    CsKeyLabel(
                                        "v${i + 1} ${c.postureLabel.lowercase()}",
                                        onClick = { vm.selectCandidateSlot(i) },
                                        role = if (on) KeyRole.ACTIVE else KeyRole.STD,
                                        size = 10.sp,
                                        horizontalPadding = 6.dp,
                                        modifier = Modifier.height(24.dp).testTag("cs_version_$i")
                                    )
                                }
                            }
                        }
                    }
                }
                val words = candidate?.plainText()?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0
                Text("$words WDS", style = mono(11.sp, Cs.Ink, FontWeight.Medium), modifier = Modifier.testTag("cs_word_count"))
                CsWell(padding = 2.dp) {
                    CsKeyLabel(
                        "↶",
                        onClick = { vm.stepGlobalSnapshot(-1) },
                        enabled = candidate?.canUndoGlobal == true,
                        size = 14.sp,
                        horizontalPadding = 0.dp,
                        modifier = Modifier.width(30.dp).height(28.dp).testTag("cs_script_undo")
                    )
                }
            }
            CsStatusLine(state.busyLabel, state.errorMessage, state.statusNotice, vm::dismissError, vm::dismissNotice)

            if (candidate == null) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    Text("No script yet. Go to 01 SPARK and tap write script.", style = sans(15.sp, Cs.Muted, 20.sp))
                }
            } else if (state.rawKeyboardMode) {
                RawEditor(candidate.plainText(), onCommit = vm::commitRawKeyboardEdit, modifier = Modifier.weight(1f))
            } else {
                val fullText = candidate.plainText()
                val paras = remember(fullText) { paragraphsOf(fullText) }
                val locks = remember(candidate) { lockedRuns(candidate) }
                val beatLabels = doc.arcBeats.takeIf { doc.isVideoScript && it.size == paras.size }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("cs_script_body")) {
                    itemsIndexed(paras, key = { i, _ -> i }) { i, para ->
                        val paraClause = clauseAt(candidate, para.start)?.let { (cid, _) ->
                            candidate.clauses.firstOrNull { it.id == cid }
                        }
                        val historyDots = paraClause?.activeVersionIndex ?: 0
                        ScriptParagraph(
                            para = para,
                            label = beatLabels?.getOrNull(i)?.let { "[${mmss(it.startSec * 1000L)}]" } ?: "[P${i + 1}]",
                            historyDots = historyDots,
                            focused = focusedPara == i,
                            selection = state.activeBrushRange,
                            anchor = anchor,
                            locks = locks,
                            onLabelTap = {
                                if (historyDots > 0 && focusedPara == i && paraClause != null) {
                                    vm.stepClauseVersion(paraClause.id, -1)
                                } else {
                                    focusedPara = if (focusedPara == i) null else i
                                }
                            },
                            onWordTap = { word ->
                                val a = anchor
                                if (a == null) {
                                    anchor = word
                                } else {
                                    anchor = null
                                    vm.performBrushSwap(minOf(a.first, word.first)..maxOf(a.last, word.last))
                                }
                            },
                            last = i == paras.lastIndex
                        )
                    }
                }

                DensityRuler(
                    previewPercent = state.densityDeltaPercent,
                    enabled = !state.isBusy,
                    onPreview = vm::previewDensityDelta,
                    onCommit = vm::commitDensityScrub,
                    wordsLabel = "${fullText.split(Regex("\\s+")).count { it.isNotBlank() }}" +
                        (if (doc.arcWordBudget > 0) " / ~${doc.arcWordBudget} WDS" else " WDS")
                )

                ThumbPad(state, vm, candidate, anchor, paras, focusedPara, onClearAnchor = { anchor = null })
            }

            // X network bar
            val matches = state.graphMatches
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(Cs.Raised)
                    .hairlineTop()
                    .hairlineBottom()
                    .clickable { netOpen = !netOpen; if (netOpen) vm.refreshGraphMatches() }
                    .padding(horizontal = 12.dp)
                    .testTag("cs_x_network_bar"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val label = when {
                    state.graphContactCount == 0 -> "X NETWORK: NO ARCHIVE IMPORTED"
                    matches.isEmpty() -> "X NETWORK: 0 MATCHING POSTS"
                    else -> {
                        val handles = matches.take(2).joinToString(", ") { "@${it.handle.uppercase()}" }
                        val more = if (matches.size > 2) " +${matches.size - 2}" else ""
                        "X NETWORK: ${matches.size} MATCHING POST${if (matches.size == 1) "" else "S"} ($handles$more)"
                    }
                }
                Text(label, style = mono(10.sp, Cs.Ink, FontWeight.Medium, 0.04f), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("pull up ▲", style = mono(10.sp, Cs.Muted))
            }

            CsBottomBar {
                CsHoldKey(
                    label = if (state.isRecordingSplice) "● listening…" else "● hold to speak own words",
                    onPress = {
                        onRequestMic {
                            val c = candidate
                            val range = state.activeBrushRange
                            when {
                                c != null && range != null -> vm.focusClause(clauseAt(c, range.first)?.first)
                                c != null && focusedPara != null -> {
                                    val paras = paragraphsOf(c.plainText())
                                    val p = paras.getOrNull(focusedPara!!)
                                    vm.focusGap(p?.let { (clauseAt(c, it.range.last)?.second ?: c.clauses.lastIndex) + 1 })
                                }
                                else -> vm.focusGap(null)
                            }
                            vm.startVoiceSplice()
                        }
                    },
                    onRelease = vm::stopVoiceSplice,
                    held = state.isRecordingSplice,
                    enabled = candidate != null && !state.isRecordingSeed && !state.isRecordingSteer,
                    modifier = Modifier.weight(208f).fillMaxSize().testTag("cs_speak_own_words")
                )
                CsKeyLabel(
                    if (state.rawKeyboardMode) "cancel" else "keyboard",
                    onClick = vm::toggleRawKeyboardMode,
                    enabled = candidate != null,
                    modifier = Modifier.weight(76f).fillMaxSize().testTag("cs_keyboard")
                )
                CsKeyLabel(
                    "create video",
                    onClick = { candidate?.let { onCreateVideo(it.plainText()) } },
                    role = KeyRole.COMMIT,
                    weight = FontWeight.Medium,
                    trailingArrow = true,
                    enabled = candidate != null && !state.isBusy,
                    modifier = Modifier.weight(128f).fillMaxSize().testTag("cs_create_video")
                )
            }
        }

        if (netOpen) {
            XNetworkSheet(state, vm, onClose = { netOpen = false }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
        }
        if (state.isAnyRecording) Box(Modifier.fillMaxSize().border(2.dp, Cs.Red))
    }
}

@Composable
private fun ScriptParagraph(
    para: Para,
    label: String,
    historyDots: Int,
    focused: Boolean,
    selection: IntRange?,
    anchor: IntRange?,
    locks: List<IntRange>,
    onLabelTap: () -> Unit,
    onWordTap: (IntRange) -> Unit,
    last: Boolean
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val annotated: AnnotatedString = remember(para, selection, anchor, locks) {
        buildAnnotatedString {
            append(para.text)
            fun local(r: IntRange): IntRange? {
                val s = maxOf(r.first, para.start) - para.start
                val e = minOf(r.last, para.range.last) - para.start
                return if (s <= e) s..e else null
            }
            locks.mapNotNull(::local).forEach { addStyle(SpanStyle(background = Cs.Pinned), it.first, it.last + 1) }
            selection?.let(::local)?.let { addStyle(SpanStyle(background = Cs.Ink, color = Cs.Black), it.first, it.last + 1) }
            anchor?.let(::local)?.let {
                addStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = Cs.White), it.first, it.last + 1)
            }
        }
    }
    val localLocks = remember(para, locks) {
        locks.mapNotNull { r ->
            val s = maxOf(r.first, para.start) - para.start
            val e = minOf(r.last, para.range.last) - para.start
            if (s <= e) s..e else null
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (!last) Modifier.hairlineBottom() else Modifier)
            .padding(top = 10.dp, end = 12.dp, bottom = 10.dp)
            .testTag("cs_para_${para.index}")
    ) {
        Column(
            Modifier.width(58.dp).padding(start = 12.dp).clickable(onClick = onLabelTap).testTag("cs_para_label_${para.index}")
        ) {
            Text(label, style = mono(10.sp, if (focused) Cs.Ink else Cs.Muted).copy(lineHeight = 29.sp))
            if (historyDots > 0) {
                Text(
                    "•".repeat(historyDots.coerceAtMost(4)),
                    style = mono(14.sp, Cs.White, tracking = 0.14f)
                )
            } else if (focused) {
                Text("▸ beat", style = mono(9.sp, Cs.Ink))
            }
        }
        Text(
            annotated,
            style = sans(19.sp, Cs.Ink, 29.sp, FontWeight(370)),
            onTextLayout = { layout = it },
            modifier = Modifier
                .weight(1f)
                .drawBehind {
                    val l = layout ?: return@drawBehind
                    localLocks.forEach { r ->
                        if (r.last >= l.layoutInput.text.length) return@forEach
                        val a = l.getBoundingBox(r.first)
                        val b = l.getBoundingBox(r.last)
                        val len = 6.dp.toPx()
                        val w = 1.dp.toPx()
                        drawLine(Cs.White, Offset(a.left, a.top), Offset(a.left + len, a.top), w)
                        drawLine(Cs.White, Offset(a.left, a.top), Offset(a.left, a.top + len), w)
                        drawLine(Cs.White, Offset(b.right, b.bottom), Offset(b.right - len, b.bottom), w)
                        drawLine(Cs.White, Offset(b.right, b.bottom), Offset(b.right, b.bottom - len), w)
                    }
                }
                .pointerInput(para) {
                    detectTapGestures { pos ->
                        val l = layout ?: return@detectTapGestures
                        val off = l.getOffsetForPosition(pos)
                        wordRangeAt(para.text, off)?.let { r -> onWordTap((r.first + para.start)..(r.last + para.start)) }
                    }
                }
        )
    }
}

@Composable
private fun DensityRuler(
    previewPercent: Int,
    enabled: Boolean,
    onPreview: (Int) -> Unit,
    onCommit: (Int) -> Unit,
    wordsLabel: String
) {
    var dragPx by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(Cs.Surface)
            .hairlineTop()
            .hairlineBottom()
            .drawBehind {
                widthPx = size.width
                val major = 20.dp.toPx()
                val minor = 4.dp.toPx()
                val shift = ((dragPx % major) + major) % major
                var x = ((dragPx % minor) + minor) % minor
                while (x < size.width) {
                    drawLine(Cs.Hairline, Offset(x, size.height - 4.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                    x += minor
                }
                x = shift
                while (x < size.width) {
                    drawLine(Cs.Muted, Offset(x, size.height - 10.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                    x += major
                }
                drawLine(Cs.Red, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val pct = (dragPx / widthPx * 100f).roundToInt()
                            .coerceIn(DraftApiClient.MIN_DENSITY_DELTA, DraftApiClient.MAX_DENSITY_DELTA)
                        dragPx = 0f
                        onCommit(pct)
                    },
                    onDragCancel = { dragPx = 0f; onPreview(0) }
                ) { change, dx ->
                    change.consume()
                    dragPx += dx
                    onPreview(
                        (dragPx / widthPx * 100f).roundToInt()
                            .coerceIn(DraftApiClient.MIN_DENSITY_DELTA, DraftApiClient.MAX_DENSITY_DELTA)
                    )
                }
            }
            .testTag("cs_density_ruler")
    ) {
        val left = if (previewPercent < 0) "SHORTER $previewPercent%" else if (previewPercent > 0) "LONGER +$previewPercent%" else "SHORTER -15S"
        Text(left, style = mono(10.sp, Cs.Muted), modifier = Modifier.align(Alignment.TopStart).background(Cs.Surface).padding(start = 12.dp, top = 3.dp, end = 4.dp))
        Text(wordsLabel, style = mono(10.sp, Cs.Muted), modifier = Modifier.align(Alignment.TopEnd).background(Cs.Surface).padding(end = 12.dp, top = 3.dp, start = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThumbPad(
    state: DraftDeckUiState,
    vm: DraftDeckViewModel,
    candidate: CandidateDraft,
    anchor: IntRange?,
    paras: List<Para>,
    focusedPara: Int?,
    onClearAnchor: () -> Unit
) {
    val range = state.activeBrushRange
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp, max = 270.dp)
            .dotPanel()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (range != null && state.brushAlternatives.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                CsSectionLabel("SELECTED PHRASE · TAP OPTION TO SWAP IN PLACE", Modifier.weight(1f))
                Text("✕", style = mono(10.sp, Cs.Ink), modifier = Modifier.clickable { vm.dismissBrushStrip() }.testTag("cs_selection_close"))
            }
            CsWell(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.brushAlternatives.forEachIndexed { i, alt ->
                        val on = i == state.brushActiveAltIndex
                        CsKey(
                            onClick = { vm.selectBrushAlternative(i) },
                            role = if (on) KeyRole.ACTIVE else KeyRole.STD,
                            contentAlignment = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().height(36.dp).testTag("cs_option_$i")
                        ) { tint ->
                            Text(if (on) "▶" else "${i + 1}", style = mono(12.sp, tint), modifier = Modifier.width(16.dp))
                            Text(
                                if (alt.isBlank()) "⌫ cut phrase completely" else alt,
                                style = sans(15.sp, tint, 20.sp),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (on) Text("previewing", style = mono(10.sp, tint, tracking = 0.04f))
                        }
                    }
                }
            }
            CsWell(Modifier.fillMaxWidth()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrushNudge.entries.forEach { n ->
                        CsChip(
                            label = (if (n == BrushNudge.MORE) "↻ " else "▶ ") + n.label,
                            on = state.activeBrushNudge == n,
                            onToggle = { vm.performBrushNudge(n) },
                            height = 28.dp,
                            size = 11.sp,
                            horizontalPadding = 10.dp,
                            modifier = Modifier.testTag("cs_nudge_${n.wire}")
                        )
                    }
                }
            }
            val clause = clauseAt(candidate, range.first)
            val clauseNode = clause?.let { (id, _) -> candidate.clauses.firstOrNull { it.id == id } }
            val priorCount = clauseNode?.activeVersionIndex ?: 0
            val pinned = vm.isRangeLocked(range)
            CsWell(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CsKeyLabel(
                        "⌜⌝ ${if (pinned) "unpin phrase" else "pin phrase"}",
                        onClick = { vm.togglePhraseLock(range) },
                        role = if (pinned) KeyRole.ACTIVE else KeyRole.STD,
                        modifier = Modifier.weight(1f).height(36.dp).testTag("cs_pin_phrase")
                    )
                    CsKeyLabel(
                        "•• prior version ($priorCount)",
                        onClick = { clauseNode?.let { vm.stepClauseVersion(it.id, -1) } },
                        enabled = priorCount > 0,
                        modifier = Modifier.weight(1f).height(36.dp).testTag("cs_prior_version")
                    )
                }
            }
        } else {
            CsSectionLabel("THUMB PAD")
            Text(
                when {
                    anchor != null -> "Tap the last word of the phrase (or the same word again) to get replacements."
                    else -> "Tap a phrase in the script to see replacements here. The text above stays unobstructed."
                },
                style = sans(15.sp, Cs.Muted, 20.sp),
                modifier = Modifier.testTag("cs_thumb_hint")
            )
            if (anchor != null) {
                Text("clear selection", style = mono(10.sp, Cs.Ink), modifier = Modifier.clickable(onClick = onClearAnchor))
            }
            CsWell(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val p = focusedPara?.let { paras.getOrNull(it) }
                    CsKeyLabel(
                        if (p != null) "↻ rewrite beat ${p.index + 1}" else "↻ rewrite beat",
                        onClick = { p?.let { vm.performBrushSwap(it.start..(it.start + it.text.trimEnd().length - 1)) } },
                        enabled = p != null && !state.isBusy,
                        pip = true,
                        modifier = Modifier.weight(1f).height(48.dp).testTag("cs_rewrite_beat")
                    )
                    CsKeyLabel(
                        "▶ tighten -10%",
                        onClick = { vm.commitDensityScrub(-10) },
                        enabled = !state.isBusy,
                        pip = true,
                        modifier = Modifier.weight(1f).height(48.dp).testTag("cs_tighten")
                    )
                }
            }
        }
    }
}

@Composable
private fun RawEditor(initial: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(initial) }
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CsSectionLabel("KEYBOARD EDIT · LOCKED WORDS MUST STAY")
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = sans(17.sp, Cs.Ink, 25.sp),
            cursorBrush = SolidColor(Cs.Ink),
            modifier = Modifier.weight(1f).fillMaxWidth().background(Cs.Surface).padding(8.dp).testTag("cs_raw_editor")
        )
        CsKeyLabel("apply edit", onClick = { onCommit(text) }, role = KeyRole.COMMIT, modifier = Modifier.fillMaxWidth().height(40.dp).testTag("cs_raw_apply"))
    }
}

@Composable
private fun XNetworkSheet(state: DraftDeckUiState, vm: DraftDeckViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importXArchiveUri(uri)
    }
    val quoted = (state.document.attachment as? XAttachment.QuotePost)?.tweetId
    Column(
        modifier.fillMaxWidth().heightIn(max = 320.dp).background(Cs.Sheet).hairlineTop(Cs.White).testTag("cs_x_sheet")
    ) {
        Row(
            Modifier.fillMaxWidth().height(38.dp).background(Cs.Raised).hairlineBottom().clickable(onClick = onClose).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("X NETWORK · ${state.graphMatches.size} MATCHING POSTS", style = mono(10.sp, Cs.Ink, FontWeight.Medium, 0.04f), modifier = Modifier.weight(1f))
            Text("close ▼", style = mono(10.sp, Cs.Muted))
        }
        if (state.graphContactCount == 0) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Import your X archive (.zip) to match posts from people you talk to.", style = sans(13.sp, Cs.Muted, 18.sp))
                CsKeyLabel(
                    if (state.isImportingArchive) "importing…" else "import X archive",
                    onClick = { importer.launch(arrayOf("application/zip", "application/json", "*/*")) },
                    enabled = !state.isImportingArchive,
                    modifier = Modifier.fillMaxWidth().height(36.dp).testTag("cs_x_import")
                )
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            itemsIndexed(state.graphMatches, key = { _, m -> m.tweetId }) { i, m ->
                Row(
                    Modifier.fillMaxWidth().height(64.dp).hairlineBottom().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "@${m.handle} · ${m.postAgeLabel} · [${m.reason.label.uppercase()}]",
                            style = mono(10.sp, Cs.Muted),
                            maxLines = 1
                        )
                        Text("\"${m.snippet}\"", style = sans(13.sp, Cs.Ink, 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CsWell(padding = 3.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CsKeyLabel("mention", onClick = { vm.weaveMention(m) }, size = 10.sp, horizontalPadding = 8.dp, modifier = Modifier.height(26.dp).testTag("cs_x_mention_$i"))
                            CsKeyLabel(
                                "show post",
                                onClick = { vm.toggleQuoteAttachment(m) },
                                role = if (quoted == m.tweetId) KeyRole.ACTIVE else KeyRole.STD,
                                size = 10.sp,
                                horizontalPadding = 8.dp,
                                modifier = Modifier.height(26.dp).testTag("cs_x_show_$i")
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                if (quoted != null) "1 POST CARD ON TIMELINE" else "0 POST CARDS ON TIMELINE",
                style = mono(10.sp, Cs.Muted)
            )
        }
    }
}
