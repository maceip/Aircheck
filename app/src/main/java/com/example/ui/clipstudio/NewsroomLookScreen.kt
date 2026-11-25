package com.example.ui.clipstudio

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.data.BreakingNewsStyle
import com.example.data.BrollSegmentMode
import com.example.data.NewsroomLook
import com.example.data.OverlayObjectKind
import com.example.data.SavedNewsroomOverlay
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import kotlin.math.pow

private val TABS = listOf("01 BACKDROP", "02 B-ROLL", "03 HEADLINES", "04 CARDS")

/**
 * 04 NEWSROOM DESIGNER STUDIO: persisted looks (rig-generated backdrop plate + draggable tick-ruler
 * blur & EV + warm/cool light, grain, border), B-roll cutaways, persisted Breaking News Style Chips,
 * and AI-generated headline / stat / quote-card overlay sets previewed live on the stage canvas and
 * rendered into the studio cut.
 */
@Composable
fun NewsroomLookScreen(
    state: StudioUiState,
    vm: StudioViewModel,
    onBack: () -> Unit,
    onTestOnClip: (lookId: String) -> Unit,
    onOpenOverlayBuilder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val looks = state.newsroomLooks
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(looks.map { it.id }) {
        if (selectedId == null || looks.none { it.id == selectedId }) {
            selectedId = (looks.firstOrNull { it.isDefault } ?: looks.firstOrNull())?.id
        }
    }
    val persisted = looks.firstOrNull { it.id == selectedId }
    var draft by remember(persisted?.id) { mutableStateOf(persisted) }
    // Pick up rig-side changes (a freshly generated plate) without discarding unsaved slider edits.
    LaunchedEffect(persisted?.plateId) {
        val p = persisted ?: return@LaunchedEffect
        draft = draft?.copy(plateId = p.plateId, plateUrl = p.plateUrl, platePrompt = p.platePrompt) ?: p
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var prompt by rememberSaveable(persisted?.id) { mutableStateOf(persisted?.platePrompt.orEmpty()) }
    val dirty = draft != null && persisted != null && draft!!.copy(updatedAtMillis = 0, isDefault = false) !=
        persisted.copy(updatedAtMillis = 0, isDefault = false)

    val attachedOverlays = remember(state.savedOverlays, state.corePipelineSession.selectedOverlayIds) {
        val selectedSet = state.corePipelineSession.selectedOverlayIds.toSet()
        state.savedOverlays.filter { it.id in selectedSet }
    }
    val breakingStyle = state.corePipelineSession.renderOptions.breakingStyle

    Box(modifier.fillMaxSize().background(Cs.Black).testTag("cs_newsroom_root")) {
        Column(Modifier.fillMaxSize()) {
            CsHeader {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "<- back",
                        style = mono(11.sp, Cs.Muted),
                        modifier = Modifier.clickable(onClick = onBack).testTag("cs_newsroom_back")
                    )
                    Text(
                        draft?.name ?: "NO LOOK",
                        style = ndot(12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                CsWell(padding = 2.dp) {
                    CsKeyLabel(
                        when {
                            draft == null -> "new look"
                            dirty -> "save look"
                            else -> "saved ✓"
                        },
                        onClick = {
                            val d = draft
                            if (d == null) selectedId = vm.createNewsroomLook("LOOK_01") else if (dirty) vm.updateNewsroomLook(d)
                        },
                        role = KeyRole.COMMIT,
                        size = 11.sp,
                        weight = FontWeight.Medium,
                        enabled = draft == null || dirty,
                        modifier = Modifier.width(92.dp).height(28.dp).testTag("cs_look_save")
                    )
                }
            }
            val busy = when {
                state.isGeneratingBackdrop -> "Generating backdrop plate on rig…"
                state.isGeneratingOverlaySet -> "Generating breaking overlay set on rig…"
                else -> null
            }
            CsStatusLine(busy, state.errorMessage, state.statusNotice, vm::dismissError, vm::dismissNotice)

            // Stage canvas preview: live backdrop + live Breaking News Style + live attached overlays
            Box(Modifier.fillMaxWidth().weight(0.45f).hairlineBottom().padding(12.dp)) {
                LookCanvas(
                    look = draft,
                    tab = tab,
                    breakingStyle = breakingStyle,
                    attachedOverlays = attachedOverlays,
                    fallbackHeadline = state.corePipelineSession.renderOptions.chyronHeadline
                        .ifBlank { state.corePipelineSession.title },
                    onPickZone = { tab = it }
                )
            }

            CsWell(Modifier.fillMaxWidth().height(44.dp).hairlineBottom(), radius = 0.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TABS.forEachIndexed { i, label ->
                        CsKeyLabel(
                            label,
                            onClick = { tab = i },
                            role = if (tab == i) KeyRole.ACTIVE else KeyRole.STD,
                            size = 10.sp,
                            weight = FontWeight.Medium,
                            horizontalPadding = 4.dp,
                            modifier = Modifier.weight(1f).fillMaxSize().testTag("cs_newsroom_tab_$i")
                        )
                    }
                }
            }

            Column(Modifier.weight(0.55f).fillMaxWidth().dotPanel().verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> BackdropTab(
                        state = state,
                        vm = vm,
                        draft = draft,
                        looks = looks,
                        prompt = prompt,
                        onPrompt = { prompt = it },
                        onSelect = { selectedId = it },
                        onDraft = { draft = it }
                    )
                    1 -> BrollTab(state, vm)
                    else -> OverlayTab(
                        state = state,
                        vm = vm,
                        kinds = if (tab == 2) {
                            setOf(OverlayObjectKind.LOWER_THIRD, OverlayObjectKind.STAT_CALLOUT)
                        } else {
                            setOf(OverlayObjectKind.QUOTE_BRIEF)
                        },
                        onOpenOverlayBuilder = onOpenOverlayBuilder
                    )
                }
            }

            CsBottomBar {
                CsKeyLabel(
                    "duplicate look",
                    onClick = { draft?.let { d -> vm.duplicateNewsroomLook(d.id)?.let { selectedId = it } } },
                    enabled = draft != null && !dirty,
                    size = 11.sp,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.weight(130f).fillMaxSize().testTag("cs_look_duplicate")
                )
                CsKeyLabel(
                    "test on clip",
                    onClick = {
                        draft?.let { d ->
                            if (dirty) vm.updateNewsroomLook(d)
                            onTestOnClip(d.id)
                        }
                    },
                    enabled = draft != null && state.corePipelineSession.hasTalkingHead && !state.isCoreStageBusy,
                    size = 11.sp,
                    horizontalPadding = 6.dp,
                    modifier = Modifier.weight(114f).fillMaxSize().testTag("cs_look_test")
                )
                CsKeyLabel(
                    if (draft?.isDefault == true && !dirty) "default look ✓" else "set default look",
                    onClick = {
                        draft?.let { d ->
                            if (dirty) vm.updateNewsroomLook(d)
                            vm.setDefaultNewsroomLook(d.id)
                        }
                    },
                    role = KeyRole.COMMIT,
                    size = 11.sp,
                    weight = FontWeight.Medium,
                    horizontalPadding = 8.dp,
                    trailingArrow = true,
                    enabled = draft != null && (draft?.isDefault != true || dirty),
                    modifier = Modifier.weight(168f).fillMaxSize().testTag("cs_look_default")
                )
            }
        }
    }
}

/**
 * On-device live preview of the backdrop plate, grade, Breaking News Style kicker chip, and
 * attached quote-card / lower-third overlays; the rig's studio cut & Remotion 4 worker render
 * the matching broadcast graphics in the MP4.
 */
@Composable
private fun LookCanvas(
    look: NewsroomLook?,
    tab: Int,
    breakingStyle: BreakingNewsStyle,
    attachedOverlays: List<SavedNewsroomOverlay>,
    fallbackHeadline: String,
    onPickZone: (Int) -> Unit
) {
    val ev = look?.brightnessEv ?: 0.0
    val gain = 2.0.pow(ev).toFloat()
    val (rT, bT) = when (look?.light) {
        NewsroomLook.LIGHT_WARM -> 1.08f to 0.9f
        NewsroomLook.LIGHT_COOL -> 0.9f to 1.08f
        else -> 1f to 1f
    }
    val matrix = ColorMatrix(
        floatArrayOf(
            gain * rT, 0f, 0f, 0f, 0f,
            0f, gain, 0f, 0f, 0f,
            0f, 0f, gain * bT, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    val quoteOverlay = attachedOverlays.firstOrNull { it.kind == OverlayObjectKind.QUOTE_BRIEF }
        ?: attachedOverlays.firstOrNull { it.kind == OverlayObjectKind.STAT_CALLOUT && attachedOverlays.size > 1 }
    val chyronOverlay = attachedOverlays.firstOrNull { it.kind == OverlayObjectKind.LOWER_THIRD }
        ?: attachedOverlays.firstOrNull { it.kind == OverlayObjectKind.STAT_CALLOUT }
    val effectiveStyle = breakingStyle
    val styleAccent = breakingAccentColor(effectiveStyle)

    Box(
        Modifier
            .fillMaxSize()
            .background(Cs.Surface)
            .border(1.dp, Cs.Hairline)
            .drawBehind {
                val inset = 16.dp.toPx()
                val dash = 4.dp.toPx()
                drawRect(
                    color = Cs.Dim,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - 2f * inset, size.height - 2f * inset),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f)
                    )
                )
                val cx = size.width / 2f
                val cy = size.height / 2f
                val arm = 4.dp.toPx()
                drawLine(Cs.Dim, Offset(cx - arm, cy), Offset(cx + arm, cy), 1.dp.toPx())
                drawLine(Cs.Dim, Offset(cx, cy - arm), Offset(cx, cy + arm), 1.dp.toPx())
            }
            .clickable { onPickZone(0) }
            .then(if (tab == 0) Modifier.cornerBrackets(all = true) else Modifier)
            .testTag("cs_look_canvas")
    ) {
        val plate = look?.plateUrl
        if (!plate.isNullOrBlank()) {
            val blurDp = ((look.blurPercent / 100f) * 24f).dp
            AsyncImage(
                model = plate,
                contentDescription = "Backdrop plate",
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(matrix),
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= 31 && blurDp > 0.dp) Modifier.blur(blurDp) else Modifier)
                    .testTag("cs_look_plate")
            )
        } else {
            Text(
                if (look == null) "Create a look to start." else "Describe a studio backdrop below & tap generate.",
                style = sans(12.sp, Cs.Muted, 16.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp)
            )
        }
        if (look?.border == true) {
            Box(Modifier.fillMaxSize().padding(16.dp).border(1.dp, Cs.White))
        }

        // Top-left active zone tag
        Text(
            "ACTIVE: ${listOf("STUDIO_BACKDROP", "B_ROLL", "HEADLINE_BAR", "QUOTE_CARD")[tab]}",
            style = mono(9.sp, Cs.Black, FontWeight.Medium),
            modifier = Modifier
                .padding(start = 8.dp, top = 6.dp)
                .background(Cs.Ink)
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )

        // Top-right broadcast LIVE tally badge
        val liveActive = effectiveStyle != BreakingNewsStyle.CLEAN
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
                .background(if (liveActive) Cs.Red else Cs.Black.copy(alpha = 0.78f), RoundedCornerShape(2.dp))
                .border(1.dp, if (liveActive) Cs.Red else Cs.Hairline, RoundedCornerShape(2.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp)
                .testTag("cs_canvas_live_bug"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (liveActive) {
                Box(Modifier.size(5.dp).background(Cs.White, RoundedCornerShape(50)))
            }
            Text(
                if (liveActive) "LIVE" else "ON AIR",
                style = mono(9.sp, if (liveActive) Cs.White else Cs.Muted, FontWeight.Medium),
                maxLines = 1
            )
        }

        // Upper-left Quote / Stat Card zone (tappable -> 04 CARDS)
        Box(
            Modifier
                .padding(start = 16.dp, top = 28.dp)
                .width(224.dp)
                .background(Cs.Black.copy(alpha = if (quoteOverlay != null) 0.86f else 0.55f), RoundedCornerShape(3.dp))
                .border(1.dp, if (tab == 3) Cs.White else Cs.Hairline, RoundedCornerShape(3.dp))
                .then(if (tab == 3) Modifier.cornerBrackets(all = true) else Modifier)
                .drawBehind {
                    if (quoteOverlay != null) {
                        drawRect(
                            color = breakingAccentColor(quoteOverlay.breakingStyle),
                            topLeft = Offset.Zero,
                            size = Size(3.dp.toPx(), size.height)
                        )
                    }
                }
                .clickable { onPickZone(3) }
                .padding(start = if (quoteOverlay != null) 9.dp else 6.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
                .testTag("cs_zone_card")
        ) {
            if (quoteOverlay != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val kicker = buildString {
                        append(quoteOverlay.kind.label.uppercase())
                        if (quoteOverlay.calloutStat.isNotBlank()) {
                            append(" · ").append(quoteOverlay.calloutStat.uppercase())
                        }
                    }
                    Text(
                        kicker,
                        style = mono(9.sp, breakingAccentColor(quoteOverlay.breakingStyle), FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        quoteOverlay.headline,
                        style = sans(12.sp, Cs.White, weight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (quoteOverlay.subline.isNotBlank()) {
                        Text(
                            quoteOverlay.subline,
                            style = mono(9.sp, Cs.Muted),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text("TAP TO STYLE: QUOTE_CARD", style = mono(9.sp, Cs.Muted), maxLines = 1)
            }
        }

        // Bottom Broadcast Lower-Third Chyron + Breaking News Style Kicker Chip (tappable -> 03 HEADLINES)
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .fillMaxWidth()
                .clickable { onPickZone(2) }
                .testTag("cs_zone_headline")
        ) {
            if (effectiveStyle != BreakingNewsStyle.CLEAN) {
                val (kickerBg, kickerFg) = when (effectiveStyle) {
                    BreakingNewsStyle.BREAKING -> Cs.Red to Cs.White
                    BreakingNewsStyle.DEVELOPING -> Cs.AmberBright to Cs.Black
                    BreakingNewsStyle.EXCLUSIVE -> Cs.White to Cs.Black
                    BreakingNewsStyle.ALERT_STAT -> Cs.Red to Cs.White
                    BreakingNewsStyle.CLEAN -> Color.Transparent to Cs.White
                }
                val kickerText = buildString {
                    append(effectiveStyle.bannerText)
                    val stat = chyronOverlay?.calloutStat.orEmpty().trim()
                    if (stat.isNotEmpty() && !effectiveStyle.bannerText.contains(stat, ignoreCase = true)) {
                        append(" · ").append(stat.uppercase())
                    }
                }
                Row(
                    Modifier
                        .background(kickerBg, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("cs_canvas_breaking_kicker"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.size(5.dp).background(kickerFg, RoundedCornerShape(50)))
                    Text(
                        kickerText,
                        style = mono(9.sp, kickerFg, FontWeight.Medium, 0.04f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Cs.Black.copy(alpha = 0.88f),
                        RoundedCornerShape(
                            topStart = if (effectiveStyle == BreakingNewsStyle.CLEAN) 3.dp else 0.dp,
                            topEnd = 3.dp,
                            bottomStart = 3.dp,
                            bottomEnd = 3.dp
                        )
                    )
                    .border(1.dp, if (tab == 2) Cs.White else Cs.Hairline)
                    .then(if (tab == 2) Modifier.cornerBrackets(all = true) else Modifier)
                    .drawBehind {
                        drawRect(
                            color = styleAccent,
                            topLeft = Offset.Zero,
                            size = Size(4.dp.toPx(), size.height)
                        )
                    }
                    .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            ) {
                val shownHeadline = chyronOverlay?.headline?.ifBlank { null }
                    ?: fallbackHeadline.trim().ifBlank { null }
                if (shownHeadline != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            shownHeadline.uppercase(),
                            style = sans(13.sp, Cs.White, weight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val shownSubline = chyronOverlay?.subline?.ifBlank { null }
                            ?: "TAP 03 HEADLINES TO GENERATE OVERLAY SET"
                        Text(
                            shownSubline,
                            style = mono(9.sp, if (chyronOverlay != null) styleAccent else Cs.Muted),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text("TAP TO STYLE: HEADLINE_BAR", style = mono(9.sp, Cs.Muted), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BackdropTab(
    state: StudioUiState,
    vm: StudioViewModel,
    draft: NewsroomLook?,
    looks: List<NewsroomLook>,
    prompt: String,
    onPrompt: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDraft: (NewsroomLook) -> Unit
) {
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .fillMaxWidth()
            .height(40.dp)
            .background(Cs.Black, RoundedCornerShape(2.dp))
            .border(1.dp, Cs.Hairline, RoundedCornerShape(2.dp))
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = prompt,
            onValueChange = onPrompt,
            singleLine = true,
            textStyle = mono(11.sp, Cs.Ink),
            cursorBrush = SolidColor(Cs.Ink),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("cs_backdrop_prompt"),
            decorationBox = { inner ->
                if (prompt.isEmpty()) Text("describe the studio backdrop…", style = mono(11.sp, Cs.Dim))
                inner()
            }
        )
        CsKeyLabel(
            if (state.isGeneratingBackdrop) "working…" else "generate",
            onClick = { draft?.let { vm.generateBackdropForLook(it.id, prompt) } },
            enabled = draft != null && prompt.isNotBlank() && !state.isGeneratingBackdrop,
            size = 11.sp,
            horizontalPadding = 6.dp,
            modifier = Modifier.width(80.dp).height(30.dp).testTag("cs_backdrop_generate")
        )
    }
    LazyRow(
        Modifier.padding(top = 12.dp).fillMaxWidth().height(76.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        items(looks, key = { it.id }) { look ->
            val on = look.id == draft?.id
            Box(
                Modifier
                    .width(116.dp)
                    .height(76.dp)
                    .background(Cs.Surface, RoundedCornerShape(2.dp))
                    .border(1.dp, if (on) Cs.White else Cs.Hairline, RoundedCornerShape(2.dp))
                    .clickable { onSelect(look.id) }
                    .testTag("cs_look_tile_${look.id}")
            ) {
                if (!look.plateUrl.isNullOrBlank()) {
                    AsyncImage(look.plateUrl, contentDescription = look.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(
                    (if (look.isDefault) "★ " else "") + look.name,
                    style = mono(10.sp, if (on) Cs.Ink else Cs.Muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).background(Cs.Black.copy(alpha = 0.65f)).padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        item {
            Box(
                Modifier
                    .width(76.dp)
                    .height(76.dp)
                    .border(1.dp, Cs.Hairline, RoundedCornerShape(2.dp))
                    .clickable { onSelect(vm.createNewsroomLook("LOOK_%02d".format(looks.size + 1))) }
                    .testTag("cs_look_new"),
                contentAlignment = Alignment.Center
            ) { Text("+ new", style = mono(10.sp, Cs.Ink)) }
        }
    }
    if (draft == null) return
    Spacer(Modifier.height(12.dp))
    CsTickRuler(
        label = "BACKGROUND BLUR",
        valueText = "${draft.blurPercent}%",
        normalizedValue = draft.blurPercent / 100f,
        enabled = !draft.plateId.isNullOrBlank(),
        onStep = { delta ->
            onDraft(draft.copy(blurPercent = (draft.blurPercent + delta * 5).coerceIn(0, 100)))
        },
        tag = "cs_scrub_blur"
    )
    CsTickRuler(
        label = "STUDIO BRIGHTNESS",
        valueText = "${if (draft.brightnessEv > 0) "+" else ""}${"%.1f".format(draft.brightnessEv)} EV",
        normalizedValue = ((draft.brightnessEv + 3.0) / 6.0).toFloat().coerceIn(0f, 1f),
        enabled = true,
        onStep = { delta ->
            val next = (Math.round((draft.brightnessEv + delta * 0.1) * 10.0) / 10.0).coerceIn(-3.0, 3.0)
            onDraft(draft.copy(brightnessEv = next))
        },
        tag = "cs_scrub_ev"
    )
    CsWell(Modifier.padding(12.dp).fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CsChip(
                    "light: warm",
                    draft.light == NewsroomLook.LIGHT_WARM,
                    { onDraft(draft.copy(light = if (draft.light == NewsroomLook.LIGHT_WARM) NewsroomLook.LIGHT_NEUTRAL else NewsroomLook.LIGHT_WARM)) },
                    height = 32.dp,
                    size = 11.sp,
                    horizontalPadding = 10.dp,
                    modifier = Modifier.weight(1f).testTag("cs_pill_warm")
                )
                CsChip(
                    "light: cool",
                    draft.light == NewsroomLook.LIGHT_COOL,
                    { onDraft(draft.copy(light = if (draft.light == NewsroomLook.LIGHT_COOL) NewsroomLook.LIGHT_NEUTRAL else NewsroomLook.LIGHT_COOL)) },
                    height = 32.dp,
                    size = 11.sp,
                    horizontalPadding = 10.dp,
                    modifier = Modifier.weight(1f).testTag("cs_pill_cool")
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CsChip(
                    "grain: ${if (draft.grain) "low" else "off"}",
                    draft.grain,
                    { onDraft(draft.copy(grain = !draft.grain)) },
                    height = 32.dp,
                    size = 11.sp,
                    horizontalPadding = 10.dp,
                    modifier = Modifier.weight(1f).testTag("cs_pill_grain")
                )
                CsChip(
                    "border: ${if (draft.border) "1dp" else "off"}",
                    draft.border,
                    { onDraft(draft.copy(border = !draft.border)) },
                    height = 32.dp,
                    size = 11.sp,
                    horizontalPadding = 10.dp,
                    modifier = Modifier.weight(1f).testTag("cs_pill_border")
                )
            }
        }
    }
}

@Composable
private fun BrollTab(state: StudioUiState, vm: StudioViewModel) {
    val clauses = state.corePipelineSession.clauses
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CsSectionLabel("B-ROLL CUTAWAYS · TAP TO SWAP A LINE TO A CAPTION PLATE")
        if (clauses.isEmpty()) Text("No story loaded yet.", style = sans(13.sp, Cs.Muted))
        clauses.forEachIndexed { idx, c ->
            val on = c.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
            CsKey(
                onClick = { vm.toggleCutClauseBroll(idx) },
                role = if (on) KeyRole.ACTIVE else KeyRole.STD,
                contentAlignment = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().height(40.dp).testTag("cs_newsroom_broll_$idx")
            ) { tint ->
                Text(if (on) "B-ROLL" else "HEAD", style = mono(10.sp, tint), modifier = Modifier.width(48.dp))
                Text(c.spokenText, style = sans(13.sp, tint), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OverlayTab(
    state: StudioUiState,
    vm: StudioViewModel,
    kinds: Set<OverlayObjectKind>,
    onOpenOverlayBuilder: () -> Unit
) {
    val overlays = state.savedOverlays.filter { it.kind in kinds }
    val attached = state.corePipelineSession.selectedOverlayIds
    val activeStyle = state.corePipelineSession.renderOptions.breakingStyle
    val seedTopic = state.corePipelineSession.fullStoryText.ifBlank { state.activeProject.ideaSpark }
    var briefTopic by rememberSaveable(state.corePipelineSession.id) { mutableStateOf(seedTopic) }
    LaunchedEffect(seedTopic) {
        if (briefTopic.isBlank() && seedTopic.isNotBlank()) {
            briefTopic = seedTopic
        }
    }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CsSectionLabel("BREAKING NEWS STYLE CHIPS · LIVE ON STAGE & CUT")
        CsWell(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BreakingNewsStyle.entries.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { style ->
                            CsBreakingChip(
                                style = style,
                                selected = activeStyle == style,
                                onSelect = { vm.setCoreBreakingStyle(style) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("cs_bstyle_${style.wire}")
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        CsSectionLabel("AI OVERLAY SET GENERATOR · HEADLINE + STAT + QUOTE CARD")
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(Cs.Black, RoundedCornerShape(2.dp))
                .border(1.dp, Cs.Hairline, RoundedCornerShape(2.dp))
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = briefTopic,
                onValueChange = { briefTopic = it },
                singleLine = true,
                textStyle = mono(11.sp, Cs.Ink),
                cursorBrush = SolidColor(Cs.Ink),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("cs_overlay_topic"),
                decorationBox = { inner ->
                    if (briefTopic.isEmpty()) {
                        Text("enter news brief topic to generate overlays…", style = mono(11.sp, Cs.Dim))
                    }
                    inner()
                }
            )
            CsKeyLabel(
                if (state.isGeneratingOverlaySet) "working…" else "generate set",
                onClick = { vm.generateNewsroomOverlaySet(briefTopic) },
                role = KeyRole.COMMIT,
                enabled = (briefTopic.isNotBlank() || seedTopic.isNotBlank()) && !state.isGeneratingOverlaySet,
                size = 11.sp,
                weight = FontWeight.Medium,
                horizontalPadding = 8.dp,
                modifier = Modifier.width(108.dp).height(30.dp).testTag("cs_overlay_generate_set")
            )
        }

        CsSectionLabel("SAVED ${kinds.joinToString(" / ") { it.label.uppercase() }} · TAP TO ATTACH TO THE CUT")
        if (overlays.isEmpty()) {
            Text(
                "None saved yet. Tap generate set above or open the A2UI builder.",
                style = sans(13.sp, Cs.Muted)
            )
        }
        overlays.forEach { ovl ->
            val on = ovl.id in attached
            val ovlAccent = breakingAccentColor(ovl.breakingStyle)
            CsKey(
                onClick = { vm.toggleSavedOverlayForPipeline(ovl.id) },
                role = if (on) KeyRole.ACTIVE else KeyRole.STD,
                contentAlignment = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .drawBehind {
                        drawRect(
                            color = ovlAccent,
                            topLeft = Offset(3.dp.toPx(), 6.dp.toPx()),
                            size = Size(3.dp.toPx(), (size.height - 12.dp.toPx()).coerceAtLeast(4f))
                        )
                    }
                    .testTag("cs_overlay_${ovl.id}")
            ) { tint ->
                Text(
                    if (on) "ON" else "OFF",
                    style = mono(10.sp, tint, FontWeight.Medium),
                    modifier = Modifier.width(32.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        ovl.headline,
                        style = sans(13.sp, tint, weight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaLine = buildString {
                        append(ovl.breakingStyle.bannerText.ifBlank { ovl.kind.label.uppercase() })
                        if (ovl.calloutStat.isNotBlank()) append(" · ").append(ovl.calloutStat)
                        append(" · ").append(ovl.durationSec).append("s")
                        if (ovl.subline.isNotBlank()) append(" — ").append(ovl.subline)
                    }
                    Text(
                        metaLine,
                        style = mono(9.sp, tint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        CsKeyLabel(
            "open overlay builder",
            onClick = onOpenOverlayBuilder,
            modifier = Modifier.fillMaxWidth().height(40.dp).testTag("cs_open_overlay_builder")
        )
    }
}
