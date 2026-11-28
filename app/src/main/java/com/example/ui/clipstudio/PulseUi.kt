@file:OptIn(ExperimentalLayoutApi::class)

package com.example.ui.clipstudio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.AircheckApplication
import com.example.AircheckContainer
import com.example.graph.FirstLookList
import com.example.graph.FirstLookPerson
import com.example.graph.FirstLookSendResponse
import com.example.graph.NameDropArchiveMatcher
import com.example.graph.NameDropHandle
import com.example.graph.NameDropPerson
import com.example.graph.NameDropResult
import com.example.graph.PulseFit
import com.example.graph.PulseFitScorer
import com.example.graph.PulseHeatSource
import com.example.graph.PulseRank
import com.example.graph.PulseScore
import com.example.ui.design.AcCard
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcField
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcPrimaryKey
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which text field Pulse is reading; sent to the rig so boosts fit the field. */
enum class PulseField(val wire: String) { NOTE("note"), BEAT("beat"), HEADLINE("headline") }

private sealed interface PulseUiState {
    data object Idle : PulseUiState
    data object Checking : PulseUiState
    data class Failed(val reason: String) : PulseUiState
    data class Ready(val score: PulseScore, val fit: PulseFit?, val rank: PulseRank?) : PulseUiState
}

private const val PULSE_MIN_CHARS = 12
private const val PULSE_DEBOUNCE_MS = 1200L

@Composable
private fun rememberContainer(): AircheckContainer {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as AircheckApplication).container }
}

private suspend fun runPulse(container: AircheckContainer, text: String, field: PulseField): PulseUiState {
    val result = container.pulseClient.score(text, field.wire)
    val score = result.getOrElse { return PulseUiState.Failed(it.message ?: "rig offline") }
    val ideas = runCatching { container.localRepo.scripts.first().map { it.ideaSpark } }.getOrDefault(emptyList())
    return PulseUiState.Ready(
        score = score,
        fit = PulseFitScorer.fit(text, score.areas),
        rank = PulseFitScorer.rank(text, ideas, score.areas)
    )
}

/**
 * Pulse status line under a text field (Caption style, like the Cut status line). Appears a moment
 * after typing stops; tap opens the Pulse sheet. Every unmeasured value reads "unknown".
 */
@Composable
fun PulseLine(
    text: String,
    field: PulseField,
    onReplace: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val container = rememberContainer()
    var state by remember { mutableStateOf<PulseUiState>(PulseUiState.Idle) }
    var open by rememberSaveable { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(text, refreshKey) {
        if (text.trim().length < PULSE_MIN_CHARS) {
            state = PulseUiState.Idle
            return@LaunchedEffect
        }
        // Drop the old score right away so a stale boost can't be applied to new text.
        state = PulseUiState.Checking
        delay(PULSE_DEBOUNCE_MS)
        state = runPulse(container, text.trim(), field)
    }

    if (state == PulseUiState.Idle) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Open Pulse") { open = true }
            .testTag("pulse_line_${field.wire}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCaption("Pulse")
        when (val s = state) {
            PulseUiState.Checking -> SectionCaption("checking…")
            is PulseUiState.Failed -> SectionCaption("rig offline · unknown", color = AcTokens.Red)
            is PulseUiState.Ready -> {
                if (s.score.boostsHeld) SectionCaption("! claim check", color = AcTokens.Red)
                SectionCaption(s.fit?.let { "fit ${it.score}" } ?: "fit · unknown")
                SectionCaption(PulseFitScorer.heatLabel(s.score.heat.ratio)?.let { "heat $it" } ?: "heat · unknown")
            }
            PulseUiState.Idle -> Unit
        }
    }

    if (open) {
        PulseSheet(
            text = text,
            state = state,
            onRefresh = { refreshKey++ },
            onReplace = { onReplace(it); open = false },
            onClose = { open = false }
        )
    }
}

@Composable
private fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) { content() }
}

@Composable
private fun PulseSheet(
    text: String,
    state: PulseUiState,
    onRefresh: () -> Unit,
    onReplace: (String) -> Unit,
    onClose: () -> Unit
) {
    var nameDrop by rememberSaveable { mutableStateOf(false) }
    var sourceDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current
    FullScreenDialog(onDismiss = onClose) {
        StudioSheet(
            title = "Pulse",
            onDismiss = onClose,
            closeTag = "pulse_sheet_close",
            closeDescription = "Close Pulse",
            fill = true,
            modifier = Modifier.testTag("pulse_sheet")
        ) {
            Column(
                Modifier
                    .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state) {
                    PulseUiState.Idle, PulseUiState.Checking -> Text("Checking…", style = AcType.BodySmall)
                    is PulseUiState.Failed -> AcCard {
                        SectionCaption("Rig offline", color = AcTokens.Red)
                        Text("Pulse needs the Mac rig. Nothing was measured: ${state.reason}", style = AcType.BodySmall)
                    }
                    is PulseUiState.Ready -> {
                        val score = state.score
                        if (score.boostsHeld) {
                            AcCard(modifier = Modifier.fillMaxWidth().testTag("pulse_claim_held")) {
                                SectionCaption("Claim about a real person", color = AcTokens.Red)
                                val allegations = score.claims.filter { it.kind == "allegation" && it.status != "cited" }
                                val failed = allegations.filter { it.status == "unknown" }
                                Text(
                                    when {
                                        failed.isNotEmpty() ->
                                            "${score.heldReason}. The news check didn't run (${failed.first().detail}), so it's unknown whether a source exists. Boosts stay off until you add a source or use a reframe."
                                        allegations.any { it.status == "sourced" } ->
                                            "${score.heldReason}. News matched the search, but a match isn't proof. Read the articles below; boosts stay off until you cite one that backs the claim, or use a reframe."
                                        allegations.isNotEmpty() ->
                                            "${score.heldReason}. No news source found. Boosts stay off until you add a source or use a reframe."
                                        else -> "${score.heldReason}. Boosts stay off; use a reframe or shorten the text."
                                    },
                                    style = AcType.Body
                                )
                            }
                        }
                        score.claims.filter { it.sources.isNotEmpty() }.forEach { claim ->
                            AcCard(modifier = Modifier.fillMaxWidth(), tag = if (claim.status == "cited") "You cited" else "News mentions") {
                                Text(claim.detail, style = AcType.BodySmall)
                                claim.sources.forEach { src ->
                                    Text(
                                        src.title.ifBlank { src.domain.ifBlank { src.url } },
                                        style = AcType.BodySmall.copy(color = AcTokens.Ink),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .clickable(role = Role.Button) { runCatching { uri.openUri(src.url) } }
                                    )
                                }
                            }
                        }
                        score.entities.filter { it.correctedName.isNotBlank() && !it.correctedName.equals(it.name, ignoreCase = true) }
                            .forEach { e ->
                                AcCard(modifier = Modifier.fillMaxWidth(), tag = "Did you mean") {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("\"${e.name}\" → ${e.correctedName}?", style = AcType.Body, modifier = Modifier.weight(1f))
                                        AcKey(
                                            label = "yes",
                                            size = AcKeySize.Small,
                                            onClick = { onReplace(PulseFitScorer.applyCorrection(text, e.name, e.correctedName)) },
                                            modifier = Modifier.testTag("pulse_correct_name")
                                        )
                                    }
                                }
                            }
                        PulseMeters(state.fit, score.heat.ratio, score.heat.sources, state.rank)
                        if (score.boosts.isNotEmpty()) {
                            SectionCaption(if (score.boostsHeld) "Reframe" else "Boost")
                            score.boosts.forEachIndexed { i, b ->
                                AcCard(modifier = Modifier.fillMaxWidth().testTag("pulse_boost_$i"), tag = b.kind) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(b.text, style = AcType.Body)
                                            if (b.why.isNotBlank()) Text(b.why, style = AcType.BodySmall)
                                        }
                                        AcKey(label = "use", size = AcKeySize.Small, onClick = { onReplace(b.text) })
                                    }
                                }
                            }
                        } else {
                            Text(
                                if (score.analysisVia.startsWith("LLM") || score.analysisVia.startsWith("no LLM")) "No boosts: ${score.analysisVia}." else "No boosts came back.",
                                style = AcType.BodySmall
                            )
                        }
                    }
                }
                val draft = sourceDraft
                if (draft != null) {
                    AcField(
                        value = draft,
                        onValueChange = { sourceDraft = it },
                        placeholder = "Paste a link to your source",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pulse_source_input")
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft == null) {
                        AcKey(label = "add a source", size = AcKeySize.Small, onClick = { sourceDraft = "" }, modifier = Modifier.testTag("pulse_add_source"))
                    } else {
                        AcKey(
                            label = "save source",
                            size = AcKeySize.Small,
                            enabled = draft.trim().startsWith("http"),
                            onClick = { onReplace(text.trimEnd() + "\n" + draft.trim()) }
                        )
                    }
                    AcKey(label = "name-drop", size = AcKeySize.Small, onClick = { nameDrop = true }, modifier = Modifier.testTag("pulse_open_namedrop"))
                    AcKey(label = "check again", size = AcKeySize.Small, onClick = onRefresh)
                }
            }
        }
    }
    if (nameDrop) {
        NameDropSheet(
            draftText = text,
            onWeave = { onReplace(it); nameDrop = false },
            onClose = { nameDrop = false }
        )
    }
}

@Composable
private fun PulseMeters(fit: PulseFit?, heatRatio: Double?, sources: List<PulseHeatSource>, rank: PulseRank?) {
    AcDisplay(modifier = Modifier.fillMaxWidth().testTag("pulse_meters")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MeterRow("FIT", fit?.score?.div(100f), fit?.score?.toString(), AcTokens.OpBlue)
            MeterRow(
                "HEAT",
                heatRatio?.let { (it / 3.0).toFloat().coerceIn(0f, 1f) },
                PulseFitScorer.heatLabel(heatRatio),
                AcTokens.OpOrange
            )
            MeterRow(
                "RANK",
                rank?.let { 1f - (it.position - 1).toFloat() / it.of.toFloat() },
                rank?.let { "${it.position} / ${it.of}" },
                AcTokens.OpRed
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(AcTokens.OpGrey.copy(alpha = 0.25f)))
            val lines = buildList {
                add(fit?.let { "FIT: WORD OVERLAP WITH YOUR AREA \"${it.bestArea}\"" } ?: "FIT: SET YOUR AREAS IN SETTINGS")
                add(if (rank != null) "RANK: BY FIT, AMONG YOUR ${rank.of} SAVED IDEAS" else "RANK: NEEDS AREAS AND 2+ SAVED IDEAS")
                sources.forEach { s ->
                    add(
                        if (s.ok) {
                            val count = s.count24h?.let { "%,.0f".format(it) + if (s.capped) "+" else "" } ?: "?"
                            val avg = s.avg7d?.let { " VS %,.1f/DAY".format(it) } ?: ""
                            "${s.name.uppercase()}: $count IN 24H$avg"
                        } else {
                            "${s.name.uppercase()}: UNKNOWN (${s.detail.uppercase()})"
                        }
                    )
                }
            }
            lines.forEach { Text(it, style = AcType.Caption.copy(color = AcTokens.OpGrey)) }
        }
    }
}

@Composable
private fun MeterRow(name: String, fraction: Float?, value: String?, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(name, style = AcType.Caption.copy(color = AcTokens.OpGrey), modifier = Modifier.width(44.dp))
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .border(1.dp, AcTokens.OpGrey.copy(alpha = if (fraction == null) 0.35f else 0.6f), RoundedCornerShape(2.dp))
        ) {
            if (fraction != null && fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(color.copy(alpha = 0.25f))
                        .border(1.dp, color, RoundedCornerShape(2.dp))
                )
            }
        }
        Text(
            value ?: "UNKNOWN",
            style = if (value != null) AcType.Numeral.copy(color = AcTokens.DisplayInk) else AcType.Caption.copy(color = AcTokens.OpGrey),
            modifier = Modifier.width(72.dp)
        )
    }
}

// ------------------------------------------------------------------------------------------
// Name-drop
// ------------------------------------------------------------------------------------------

private val TAGGABLE = setOf("verified", "in_archive", "in_text")

private fun NameDropPerson.bestTag(): NameDropHandle? =
    listOf("x", "bsky").mapNotNull { handles[it] }.firstOrNull { it.status in TAGGABLE && it.handle.isNotBlank() }

private fun statusLabel(h: NameDropHandle): String = when (h.status) {
    "verified" -> "✓ verified"
    "in_archive" -> "✓ your archive"
    "in_text" -> "✓ in text"
    "likely" -> "? likely"
    else -> "· unknown"
}

@Composable
private fun NameDropSheet(draftText: String, onWeave: (String) -> Unit, onClose: () -> Unit) {
    val container = rememberContainer()
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NameDropResult?>(null) }
    var resultFor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    var firstLookNote by remember { mutableStateOf<String?>(null) }

    FullScreenDialog(onDismiss = onClose) {
        StudioSheet(
            title = "Name-drop",
            onDismiss = onClose,
            closeTag = "namedrop_close",
            closeDescription = "Close Name-drop",
            fill = true,
            modifier = Modifier.testTag("namedrop_sheet")
        ) {
            Column(
                Modifier
                    .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AcField(
                    value = input,
                    onValueChange = {
                        input = it
                        // A result belongs to the text it was found for; editing drops it.
                        if (it != resultFor) {
                            result = null
                            picked = emptySet()
                        }
                    },
                    placeholder = "Paste a paper, thread or post link",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("namedrop_input"),
                    trailing = {
                        AcKey(
                            label = "find",
                            size = AcKeySize.Small,
                            selected = busy,
                            enabled = !busy && input.isNotBlank(),
                            onClick = {
                                busy = true
                                error = null
                                result = null
                                firstLookNote = null
                                picked = emptySet()
                                val submitted = input
                                scope.launch {
                                    val res = container.pulseClient.resolve(submitted)
                                    res.onSuccess { r ->
                                        val contacts = withContext(Dispatchers.IO) {
                                            runCatching { container.localGraphDatabase.getAllContacts().map { it.record } }.getOrDefault(emptyList())
                                        }
                                        if (input == submitted) {
                                            result = NameDropArchiveMatcher.merge(r, contacts)
                                            resultFor = submitted
                                        }
                                    }.onFailure { if (input == submitted) error = it.message ?: "rig offline" }
                                    busy = false
                                }
                            },
                            modifier = Modifier.testTag("namedrop_find")
                        )
                    }
                )
                error?.let { Text("Couldn't check: $it", style = AcType.BodySmall.copy(color = AcTokens.Red)) }
                result?.let { r ->
                    val taggableX = r.people.count { p -> p.handles["x"]?.status in TAGGABLE }
                    SectionCaption("Source: ${r.kind.replace('_', ' ')} · ${r.people.size} people · $taggableX taggable on X")
                    if (r.title.isNotBlank()) Text(r.title, style = AcType.BodySmall)
                    r.notes.forEach { Text(it, style = AcType.BodySmall) }
                    r.people.forEachIndexed { i, person ->
                        val tag = person.bestTag()
                        AcCard(modifier = Modifier.fillMaxWidth().testTag("namedrop_person_$i"), tag = person.role) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(person.name.ifBlank { tag?.handle ?: "Unnamed" }, style = AcType.Title, modifier = Modifier.weight(1f))
                                AcKey(
                                    label = "tag",
                                    size = AcKeySize.Small,
                                    selected = i in picked,
                                    enabled = tag != null,
                                    onClick = { picked = if (i in picked) picked - i else picked + i },
                                    modifier = Modifier.testTag("namedrop_tag_$i")
                                )
                            }
                            listOf("x" to "X", "bsky" to "BSKY").forEach { (key, label) ->
                                val h = person.handles[key] ?: NameDropHandle()
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(label, style = AcType.Caption, modifier = Modifier.width(36.dp))
                                    Text(
                                        h.handle.ifBlank { "unknown" },
                                        style = AcType.BodySmall.copy(color = if (h.handle.isBlank()) AcTokens.Ink2 else AcTokens.Ink),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(statusLabel(h).uppercase(), style = AcType.Caption.copy(color = if (h.status in TAGGABLE) AcTokens.Ink else AcTokens.Ink2))
                                }
                            }
                        }
                    }
                    firstLookNote?.let { Text(it, style = AcType.BodySmall) }
                    val chosen = picked.sorted().mapNotNull { r.people.getOrNull(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AcKey(
                            label = "add to first look",
                            size = AcKeySize.Small,
                            enabled = chosen.isNotEmpty(),
                            onClick = {
                                scope.launch {
                                    val current = container.pulseClient.firstLook().getOrElse {
                                        firstLookNote = "Couldn't reach the rig: ${it.message}"
                                        return@launch
                                    }
                                    val additions = chosen.mapNotNull { p ->
                                        val t = p.bestTag() ?: return@mapNotNull null
                                        val platform = if (p.handles["x"] == t) "x" else "bsky"
                                        FirstLookPerson(name = p.name, platform = platform, address = t.handle)
                                    }.filter { add -> current.people.none { it.address.equals(add.address, ignoreCase = true) } }
                                    firstLookNote = container.pulseClient.saveFirstLook(current.people + additions).fold(
                                        { "Added ${additions.size} to First look." },
                                        { "Couldn't save: ${it.message}" }
                                    )
                                }
                            },
                            modifier = Modifier.testTag("namedrop_add_first_look")
                        )
                        AcPrimaryKey(
                            label = "weave ${chosen.size} tag${if (chosen.size == 1) "" else "s"}",
                            enabled = chosen.isNotEmpty(),
                            onClick = {
                                var woven = draftText
                                chosen.forEach { p ->
                                    val tag = p.bestTag() ?: return@forEach
                                    woven = if (p.handles["x"] == tag) {
                                        container.graphRadarEngine.weaveHandleIntoDraft(woven, tag.handle)
                                    } else {
                                        PulseFitScorer.weaveBskyHandle(woven, tag.handle)
                                    }
                                }
                                onWeave(woven)
                            },
                            modifier = Modifier.weight(1f).testTag("namedrop_weave")
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------
// Settings: areas, X archive, First look
// ------------------------------------------------------------------------------------------

/** Settings entry for Pulse areas, the X archive and the First look list. */
@Composable
fun PulseSettingsCard(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    AcCard(onClick = { open = true }, modifier = modifier.fillMaxWidth().testTag("open_pulse_settings")) {
        Text("Pulse and First look", style = AcType.Body)
        Text("Your areas, X archive, and who gets your posts first", style = AcType.BodySmall)
    }
    if (open) PulseSettingsSheet(onClose = { open = false })
}

@Composable
private fun PulseSettingsSheet(onClose: () -> Unit) {
    val container = rememberContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var areasText by rememberSaveable { mutableStateOf("") }
    // Set once the creator types, so a slow rig reply never overwrites their edits.
    var areasEdited by rememberSaveable { mutableStateOf(false) }
    var areasNote by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<FirstLookList?>(null) }
    var listNote by remember { mutableStateOf<String?>(null) }
    var contacts by remember { mutableStateOf<Int?>(null) }
    var archiveNote by remember { mutableStateOf<String?>(null) }
    var newAddress by rememberSaveable { mutableStateOf("") }
    var newPlatform by rememberSaveable { mutableStateOf("x") }
    var postLink by rememberSaveable { mutableStateOf("") }
    var sendResult by remember { mutableStateOf<FirstLookSendResponse?>(null) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        container.pulseClient.areas()
            .onSuccess { if (!areasEdited) areasText = it.areas.joinToString("\n") }
            .onFailure { areasNote = "Couldn't load from the rig: ${it.message}" }
        container.pulseClient.firstLook()
            .onSuccess { list = it }
            .onFailure { listNote = "Couldn't load from the rig: ${it.message}" }
        contacts = withContext(Dispatchers.IO) { runCatching { container.localGraphDatabase.contactCount() }.getOrNull() }
    }

    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked: Uri? ->
        picked ?: return@rememberLauncherForActivityResult
        archiveNote = "Importing…"
        scope.launch {
            archiveNote = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(picked)?.use { input ->
                        container.xArchiveImporter.importStream(input, picked.lastPathSegment ?: "archive.zip", container.localGraphDatabase)
                    } ?: error("couldn't open the file")
                }.fold(
                    { "Imported ${it.contactsImported} contacts and ${it.postsImported} posts." },
                    { "Import failed: ${it.message}" }
                )
            }
            contacts = withContext(Dispatchers.IO) { runCatching { container.localGraphDatabase.contactCount() }.getOrNull() }
        }
    }

    var saving by remember { mutableStateOf(false) }
    // One save at a time: each edit builds on the list the rig last confirmed, and keys stay
    // disabled until that save lands, so two quick edits can't overwrite each other.
    fun saveList(people: List<FirstLookPerson>) {
        if (saving) return
        saving = true
        val before = list
        list = before?.copy(people = people)
        scope.launch {
            container.pulseClient.saveFirstLook(people)
                .onSuccess { list = it; listNote = null }
                .onFailure { list = before; listNote = "Couldn't save: ${it.message}" }
            saving = false
        }
    }

    FullScreenDialog(onDismiss = onClose) {
        StudioSheet(
            title = "Pulse and First look",
            onDismiss = onClose,
            closeTag = "pulse_settings_close",
            closeDescription = "Close",
            fill = true,
            modifier = Modifier.testTag("pulse_settings_sheet")
        ) {
            Column(
                Modifier
                    .padding(start = 14.dp, end = 14.dp, bottom = 18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionCaption("Your areas")
                Text("One per line. Pulse scores fit against these.", style = AcType.BodySmall)
                AcField(
                    value = areasText,
                    onValueChange = { areasText = it; areasEdited = true },
                    placeholder = "AI chips\nRust\nHockey",
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("pulse_areas_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AcKey(
                        label = "save areas",
                        size = AcKeySize.Small,
                        onClick = {
                            scope.launch {
                                val areas = areasText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                                areasNote = container.pulseClient.saveAreas(areas).fold(
                                    { "Saved ${it.areas.size} areas." },
                                    { "Couldn't save: ${it.message}" }
                                )
                            }
                        },
                        modifier = Modifier.testTag("pulse_areas_save")
                    )
                    areasNote?.let { Text(it, style = AcType.BodySmall, modifier = Modifier.weight(1f)) }
                }

                SectionCaption("X archive")
                Text(
                    "Lets Name-drop find X handles of people you already talk to. Contacts on this phone: ${contacts?.toString() ?: "unknown"}.",
                    style = AcType.BodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AcKey(
                        label = "import X archive",
                        size = AcKeySize.Small,
                        onClick = { archivePicker.launch(arrayOf("application/zip", "application/json", "application/javascript", "*/*")) },
                        modifier = Modifier.testTag("pulse_import_archive")
                    )
                    archiveNote?.let { Text(it, style = AcType.BodySmall, modifier = Modifier.weight(1f)) }
                }

                SectionCaption("First look")
                Text("These people get your post right away when you send it below.", style = AcType.BodySmall)
                listNote?.let { Text(it, style = AcType.BodySmall.copy(color = AcTokens.Red)) }
                val current = list
                current?.people?.forEach { p ->
                    AcCard(modifier = Modifier.fillMaxWidth().testTag("first_look_${p.id}")) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(p.name.ifBlank { p.address }, style = AcType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(deliveryLabel(p, current.bskyDmReady).uppercase(), style = AcType.Caption)
                            }
                            AcKey(
                                label = if (p.on) "on" else "off",
                                enabled = !saving,
                                size = AcKeySize.Small,
                                selected = p.on,
                                onClick = { saveList(current.people.map { if (it.id == p.id) it.copy(on = !it.on) else it }) }
                            )
                            AcKey(
                                label = "remove",
                                enabled = !saving,
                                size = AcKeySize.Small,
                                onClick = { saveList(current.people.filterNot { it.id == p.id }) }
                            )
                        }
                    }
                }
                AcField(
                    value = newAddress,
                    onValueChange = { newAddress = it },
                    placeholder = "@handle, name.bsky.social or email",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("first_look_new_address")
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("x" to "X", "bsky" to "Bluesky", "email" to "Email").forEach { (key, label) ->
                        AcKey(label = label, size = AcKeySize.Small, selected = newPlatform == key, onClick = { newPlatform = key })
                    }
                    AcKey(
                        label = "add person",
                        size = AcKeySize.Small,
                        enabled = current != null && newAddress.isNotBlank() && !saving,
                        onClick = {
                            val people = current?.people.orEmpty() + FirstLookPerson(platform = newPlatform, address = newAddress.trim())
                            newAddress = ""
                            saveList(people)
                        },
                        modifier = Modifier.testTag("first_look_add")
                    )
                }

                SectionCaption("Send a post to First look")
                AcField(
                    value = postLink,
                    onValueChange = { postLink = it },
                    placeholder = "Paste the link to your post",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("first_look_post_link")
                )
                AcPrimaryKey(
                    label = if (sending) "sending…" else "send to first look",
                    enabled = !sending && postLink.trim().startsWith("http") && current?.people?.any { it.on } == true,
                    onClick = {
                        if (sending) return@AcPrimaryKey
                        // One send at a time: a double tap must not DM everyone twice.
                        sending = true
                        sendResult = null
                        listNote = null
                        scope.launch {
                            container.pulseClient.sendFirstLook(postLink.trim(), "")
                                .onSuccess { sendResult = it }
                                .onFailure { listNote = "Couldn't send: ${it.message}" }
                            sending = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("first_look_send")
                )
                sendResult?.let { r ->
                    SectionCaption("${r.summary.sent} sent · ${r.summary.needsYou} waiting on you · ${r.summary.failed} failed")
                    r.results.forEach { res ->
                        AcCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(res.name.ifBlank { res.address }, style = AcType.Body)
                                    Text(
                                        "${res.status.replace('_', ' ')} · ${res.detail}".uppercase(),
                                        style = AcType.Caption.copy(color = if (res.status == "failed") AcTokens.Red else AcTokens.Ink2)
                                    )
                                }
                                val link = res.openUrl
                                if (res.status == "needs_you" && link != null) {
                                    AcKey(label = "open", size = AcKeySize.Small, onClick = {
                                        res.copyText?.let { clipboard.setText(AnnotatedString(it)) }
                                        runCatching { uri.openUri(link) }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun deliveryLabel(p: FirstLookPerson, bskyReady: Boolean): String = when (p.platform) {
    "bsky" -> if (bskyReady) "Bluesky · sends DM automatically" else "Bluesky · needs app password on the rig"
    "email" -> "Email · opens your mail app"
    else -> "X · opens their profile, you send"
}
