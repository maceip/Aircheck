package com.example.ui.clipstudio

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.Check
import androidx.compose.material.icons.sharp.ContentCopy
import androidx.compose.material.icons.sharp.FormatQuote
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material.icons.sharp.Remove
import androidx.compose.material.icons.sharp.Title
import androidx.compose.material.icons.sharp.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.data.BreakingNewsStyle
import com.example.data.CoreStoryClause
import com.example.data.NewsroomLook
import com.example.data.OverlayObjectKind
import com.example.data.SavedNewsroomOverlay
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.math.pow

/**
 * Video look (Clip Studio v5, replaces 04 NEWSROOM):
 * - 56dp header: `arrow_back`, `"Video look"` (Geist 500 17sp), `content_copy` (`duplicateNewsroomLook`).
 * - 232dp sunken `#000` live preview (real plate with blur, EV, warm/neutral/cool tint + optional kicker tag +
 *   `rgba(0,0,0,.82)` title box; zero `LIVE` bug or `ACTIVE:` debug clutter).
 * - Sunken 3-way `background` · `title` · `cards` control:
 *   - `background`: 48dp round `−`/`+` keys + 14dp sunken `#101013` track (`#EBEBEF` fill) for `BLUR` (±5%)
 *     and `BRIGHTNESS` (±0.2 EV), 3-way `LIGHT` (`warm` / `neutral` / `cool`), plus saved look selector &
 *     rig backdrop prompt + `generate`.
 *   - `title`: sunken editable `TITLE ON SCREEN` field (`updateTitleOnScreen`) + 48x48dp `refresh` AI key
 *     (`refreshHeadlineWithAi`) + `TAG ABOVE TITLE` 44dp pills (`none` · `breaking` · `developing` · `explainer`).
 *   - `cards`: real attached cards summary & timing from `savedOverlays` / `selectedOverlayIds`, attach toggles,
 *     and `"create / manage cards"` (`onOpenOverlayBuilder`).
 * - Dock: `"use this look"` (`check`) -> `useLookAndReturn` + `onBack()`.
 */
@Composable
fun NewsroomLookScreen(
    state: StudioUiState,
    vm: StudioViewModel,
    onBack: () -> Unit,
    onTestOnClip: (lookId: String) -> Unit = {},
    onOpenOverlayBuilder: () -> Unit,
    modifier: Modifier = Modifier,
    renderGlobalOverlays: Boolean = true
) {
    val looks = state.newsroomLooks
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(looks.map { it.id }) {
        if (selectedId == null || looks.none { it.id == selectedId }) {
            selectedId = (looks.firstOrNull { it.isDefault } ?: looks.firstOrNull())?.id
        }
    }

    val persisted = looks.firstOrNull { it.id == selectedId }
    var draft by remember(persisted?.id) {
        mutableStateOf(
            persisted ?: NewsroomLook(
                name = "LOOK_01",
                isDefault = true
            )
        )
    }

    // Pick up rig-side plate updates without discarding unsaved slider adjustments.
    LaunchedEffect(persisted?.plateId, persisted?.plateUrl) {
        val p = persisted ?: return@LaunchedEffect
        draft = draft.copy(plateId = p.plateId, plateUrl = p.plateUrl, platePrompt = p.platePrompt)
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }

    val session = state.corePipelineSession
    val breakingStyle = session.renderOptions.breakingStyle
    val resolvedTitle = remember(
        session.renderOptions.chyronHeadline,
        session.title,
        state.activeProject.stageConfig.chyronHeadline,
        state.activeProject.title
    ) {
        session.renderOptions.chyronHeadline.trim()
            .ifBlank { session.title.trim() }
            .ifBlank { state.activeProject.stageConfig.chyronHeadline.trim() }
            .ifBlank { state.activeProject.title.trim() }
    }

    val attachedOverlays = remember(state.savedOverlays, session.selectedOverlayIds) {
        val selectedSet = session.selectedOverlayIds.toSet()
        state.savedOverlays.filter { it.id in selectedSet }
    }

    val isLookBusy = state.isGeneratingBackdrop || state.isRefreshingHeadline || state.isGeneratingOverlaySet
    val busyLabel = when {
        state.isGeneratingBackdrop -> "generating backdrop…"
        state.isRefreshingHeadline -> "refreshing title…"
        state.isGeneratingOverlaySet -> "generating cards…"
        else -> null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Cs.Body)
            .testTag("cs_newsroom_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            // Clean 56dp Header (`arrow_back`, `"Video look"`, `content_copy`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 8.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CsIconKey(
                    icon = Icons.AutoMirrored.Sharp.ArrowBack,
                    contentDescription = "Back to Cut",
                    onClick = onBack,
                    modifier = Modifier.testTag("cs_newsroom_back")
                )
                Text(
                    text = "Video look",
                    style = sans(17.sp, Cs.Ink, weight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                CsIconKey(
                    icon = Icons.Sharp.ContentCopy,
                    contentDescription = "Duplicate look",
                    enabled = looks.isNotEmpty() || persisted != null,
                    onClick = {
                        val sourceId = persisted?.id ?: vm.createNewsroomLook(draft.name)
                        if (persisted != null && draft != persisted) {
                            vm.updateNewsroomLook(draft)
                        }
                        val duplicatedId = vm.duplicateNewsroomLook(sourceId)
                        if (duplicatedId != null) {
                            selectedId = duplicatedId
                            vm.showToast("look duplicated")
                        }
                    },
                    modifier = Modifier.testTag("cs_look_duplicate")
                )
            }

            CsStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

            // 206dp sunken #000 live preview (sized so BLUR + BRIGHTNESS + 3-way LIGHT all fit unclipped above the dock)
            VideoLookPreviewPanel(
                look = draft,
                breakingStyle = breakingStyle,
                titleText = resolvedTitle,
                attachedOverlays = attachedOverlays
            )

            // Sunken 3-way `background` · `title` · `cards` segmented control
            CsSegmentedRow(
                modifier = Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp)
            ) {
                val tabs = listOf(
                    Triple(0, "background", Icons.Sharp.Wallpaper),
                    Triple(1, "title", Icons.Sharp.Title),
                    Triple(2, "cards", Icons.Sharp.FormatQuote)
                )
                tabs.forEach { (idx, label, icon) ->
                    CsSegment(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        height = 44.dp,
                        modifier = Modifier.testTag("cs_newsroom_tab_$idx")
                    ) { tint ->
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        Text(label, style = mono(13.sp, tint), maxLines = 1)
                    }
                }
            }

            // Tab body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    0 -> LookBackgroundTab(
                        state = state,
                        vm = vm,
                        draft = draft,
                        looks = looks,
                        onSelectLook = { id -> selectedId = id },
                        onUpdateDraft = { updated -> draft = updated }
                    )
                    1 -> LookTitleTab(
                        state = state,
                        vm = vm,
                        currentTitle = resolvedTitle,
                        breakingStyle = breakingStyle
                    )
                    2 -> LookCardsTab(
                        state = state,
                        vm = vm,
                        attachedOverlays = attachedOverlays,
                        onOpenOverlayBuilder = onOpenOverlayBuilder
                    )
                }
            }

            // Single Primary Dock Key: "use this look"
            CsDock(
                primaryLabel = "use this look",
                primaryTrailingIcon = Icons.Sharp.Check,
                primaryRole = KeyRole.COMMIT,
                primaryEnabled = !isLookBusy,
                primaryTag = "cs_use_look",
                onPrimaryClick = {
                    vm.useLookAndReturn(draft.id, draftOverride = draft)
                    onBack()
                },
                isBusy = isLookBusy,
                busyLabel = busyLabel
            )
        }

        if (renderGlobalOverlays) {
            CsToastOverlay(
                message = state.toastMessage,
                onDismiss = vm::dismissToast
            )
        }
    }
}

@Composable
private fun VideoLookPreviewPanel(
    look: NewsroomLook,
    breakingStyle: BreakingNewsStyle,
    titleText: String,
    attachedOverlays: List<SavedNewsroomOverlay>
) {
    val ev = look.brightnessEv
    val gain = 2.0.pow(ev).toFloat()
    val (rT, bT) = when (look.light) {
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
    val blurDp = ((look.blurPercent / 100f) * 24f).dp

    val ambientCenterColor = remember(look.light, gain) {
        val base = when (look.light) {
            NewsroomLook.LIGHT_WARM -> Color(0xFF3A2A1C)
            NewsroomLook.LIGHT_COOL -> Color(0xFF1C2A3A)
            else -> Color(0xFF2A2A2E)
        }
        Color(
            red = (base.red * gain).coerceIn(0f, 1f),
            green = (base.green * gain).coerceIn(0f, 1f),
            blue = (base.blue * gain).coerceIn(0f, 1f),
            alpha = 1f
        )
    }

    val chyronOverlay = attachedOverlays.firstOrNull { it.kind == OverlayObjectKind.LOWER_THIRD }
    val displayTitle = chyronOverlay?.headline?.ifBlank { null }
        ?: titleText.ifBlank { "Untitled clip" }

    val tagKey = breakingStyle.titleTagKey
    val showKicker = tagKey != "none"
    val (kickerBg, kickerFg) = when (tagKey) {
        "breaking" -> Cs.Red to Cs.White
        "developing" -> Cs.AmberBright to Cs.Black
        "explainer" -> Cs.Ink to Cs.Black
        else -> Color.Transparent to Cs.White
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .height(206.dp)
            .sunkenPanel(radius = 18.dp)
            .testTag("cs_look_canvas")
    ) {
        val plate = look.plateUrl
        if (!plate.isNullOrBlank()) {
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(ambientCenterColor, Color(0xFF0A0A0D)),
                            radius = 520f
                        )
                    )
                    .then(if (Build.VERSION.SDK_INT >= 31 && blurDp > 0.dp) Modifier.blur(blurDp) else Modifier)
            )
        }

        if (look.border) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(14.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            )
        }

        // Optional kicker tag + rgba(0,0,0,.82) title box
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .fillMaxWidth()
                .testTag("cs_zone_headline"),
            horizontalAlignment = Alignment.Start
        ) {
            if (showKicker) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(kickerBg)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .testTag("cs_canvas_breaking_kicker")
                ) {
                    Text(
                        text = tagKey.uppercase(),
                        style = mono(10.sp, kickerFg, FontWeight.Medium, 0.06f),
                        maxLines = 1
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = if (showKicker) 0.dp else 10.dp,
                            topEnd = 10.dp,
                            bottomStart = 10.dp,
                            bottomEnd = 10.dp
                        )
                    )
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = displayTitle,
                    style = sans(15.sp, Cs.White, 20.sp, FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LookBackgroundTab(
    state: StudioUiState,
    vm: StudioViewModel,
    draft: NewsroomLook,
    looks: List<NewsroomLook>,
    onSelectLook: (String) -> Unit,
    onUpdateDraft: (NewsroomLook) -> Unit
) {
    // 1. BLUR (±5%, 0..100%)
    LookStepperSlider(
        label = "BLUR",
        valueText = "${draft.blurPercent}%",
        fraction = (draft.blurPercent / 100f).coerceIn(0f, 1f),
        onDecrement = {
            onUpdateDraft(draft.copy(blurPercent = (draft.blurPercent - 5).coerceIn(0, 100)))
        },
        onIncrement = {
            onUpdateDraft(draft.copy(blurPercent = (draft.blurPercent + 5).coerceIn(0, 100)))
        },
        tag = "cs_scrub_blur"
    )

    // 2. BRIGHTNESS (±0.2 EV, -3.0..+3.0)
    val evLabel = "${if (draft.brightnessEv > 0) "+" else ""}${"%.1f".format(draft.brightnessEv)}"
    LookStepperSlider(
        label = "BRIGHTNESS",
        valueText = evLabel,
        fraction = (((draft.brightnessEv + 3.0) / 6.0).toFloat()).coerceIn(0f, 1f),
        onDecrement = {
            val next = (Math.round((draft.brightnessEv - 0.2) * 10.0) / 10.0).coerceIn(-3.0, 3.0)
            onUpdateDraft(draft.copy(brightnessEv = next))
        },
        onIncrement = {
            val next = (Math.round((draft.brightnessEv + 0.2) * 10.0) / 10.0).coerceIn(-3.0, 3.0)
            onUpdateDraft(draft.copy(brightnessEv = next))
        },
        tag = "cs_scrub_ev"
    )

    // 3. LIGHT 3-way segmented control (`warm` · `neutral` · `cool`)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LIGHT", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        CsSegmentedRow {
            val lightChoices = listOf(
                NewsroomLook.LIGHT_WARM to "warm",
                NewsroomLook.LIGHT_NEUTRAL to "neutral",
                NewsroomLook.LIGHT_COOL to "cool"
            )
            lightChoices.forEach { (wire, label) ->
                CsSegment(
                    selected = draft.light == wire,
                    onClick = { onUpdateDraft(draft.copy(light = wire)) },
                    height = 44.dp,
                    modifier = Modifier.testTag("cs_light_$label")
                ) { tint ->
                    Text(label, style = mono(13.sp, tint), maxLines = 1)
                }
            }
        }
    }

    // 4. Backdrop plate generator & saved look selector (keeps real Mac-rig plate creation working)
    val promptState = rememberTextFieldState(initialText = draft.platePrompt)
    LaunchedEffect(draft.id, draft.platePrompt) {
        if (promptState.text.isEmpty() && draft.platePrompt.isNotBlank()) {
            promptState.setTextAndPlaceCursorAtEnd(draft.platePrompt)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BACKDROP PLATE", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .sunkenPanel(radius = 16.dp)
                .padding(start = 14.dp, top = 4.dp, end = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                state = promptState,
                textStyle = sans(14.sp, Cs.Ink, 20.sp),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_backdrop_prompt"),
                decorator = { inner ->
                    Box {
                        if (promptState.text.isEmpty()) {
                            Text("Describe a custom studio backdrop…", style = sans(14.sp, Cs.Dim, 20.sp))
                        }
                        inner()
                    }
                }
            )
            CsKeyLabel(
                label = if (state.isGeneratingBackdrop) "working…" else "generate",
                onClick = {
                    val lookId = if (looks.any { it.id == draft.id }) {
                        draft.id
                    } else {
                        vm.createNewsroomLook(draft.name).also(onSelectLook)
                    }
                    vm.generateBackdropForLook(lookId, promptState.text.toString())
                },
                enabled = promptState.text.toString().isNotBlank() && !state.isGeneratingBackdrop,
                radius = 12.dp,
                size = 12.sp,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("cs_backdrop_generate")
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            looks.forEach { item ->
                CsPill(
                    label = item.name,
                    selected = item.id == draft.id,
                    onClick = { onSelectLook(item.id) },
                    modifier = Modifier.testTag("cs_look_tile_${item.id}")
                )
            }
            CsPill(
                label = "new look",
                selected = false,
                icon = Icons.Sharp.Add,
                onClick = {
                    val createdId = vm.createNewsroomLook("LOOK_%02d".format(looks.size + 1))
                    onSelectLook(createdId)
                },
                modifier = Modifier.testTag("cs_look_new")
            )
        }
    }
}

@Composable
private fun LookStepperSlider(
    label: String,
    valueText: String,
    fraction: Float,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    tag: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = SectionLabelStyle, maxLines = 1)
            Text(
                text = valueText,
                style = mono(13.sp, Cs.Ink, FontWeight.Medium),
                maxLines = 1,
                modifier = Modifier.testTag("${tag}_value")
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CsIconKey(
                icon = Icons.Sharp.Remove,
                contentDescription = "Decrease $label",
                onClick = onDecrement,
                size = 44.dp,
                radius = 22.dp,
                iconSize = 22.dp,
                modifier = Modifier.testTag("${tag}_dec")
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(14.dp)
                    .sunkenPanel(radius = 7.dp, fill = Cs.SunkenTrack)
                    .padding(2.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .widthIn(min = 10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Cs.Ink)
                    )
                }
            }

            CsIconKey(
                icon = Icons.Sharp.Add,
                contentDescription = "Increase $label",
                onClick = onIncrement,
                size = 44.dp,
                radius = 22.dp,
                iconSize = 22.dp,
                modifier = Modifier.testTag("${tag}_inc")
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookTitleTab(
    state: StudioUiState,
    vm: StudioViewModel,
    currentTitle: String,
    breakingStyle: BreakingNewsStyle
) {
    val titleFieldState = rememberTextFieldState(initialText = currentTitle)

    // Keep text field synced when AI headline refresh updates currentTitle externally.
    LaunchedEffect(currentTitle) {
        if (currentTitle != titleFieldState.text.toString()) {
            titleFieldState.setTextAndPlaceCursorAtEnd(currentTitle)
        }
    }

    // Persist user edits back to StudioViewModel / Room cleanly.
    LaunchedEffect(titleFieldState) {
        snapshotFlow { titleFieldState.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .collect { edited ->
                if (edited.trim() != currentTitle.trim()) {
                    vm.updateTitleOnScreen(edited)
                }
            }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TITLE ON SCREEN", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .sunkenPanel(radius = 16.dp)
                .padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                state = titleFieldState,
                textStyle = sans(15.sp, Cs.Ink, 20.sp, FontWeight.Medium),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 3),
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_title_input"),
                decorator = { inner ->
                    Box {
                        if (titleFieldState.text.isEmpty()) {
                            Text("Enter title shown on screen…", style = sans(15.sp, Cs.Dim, 20.sp))
                        }
                        inner()
                    }
                }
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        enabled = !state.isRefreshingHeadline,
                        role = Role.Button,
                        onClick = vm::refreshHeadlineWithAi
                    )
                    .testTag("cs_title_refresh"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Sharp.Refresh,
                    contentDescription = "Refresh title with AI",
                    tint = if (!state.isRefreshingHeadline) Cs.Muted else Cs.Dim,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TAG ABOVE TITLE", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        val activeTagKey = breakingStyle.titleTagKey
        val tags = listOf(
            "none" to null,
            "breaking" to Cs.Red,
            "developing" to Cs.AmberBright,
            "explainer" to Cs.Ink
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tags.forEach { (tag, accent) ->
                CsPill(
                    label = tag,
                    selected = activeTagKey == tag,
                    accentBarColor = accent,
                    onClick = { vm.setTitleTagStyle(tag) },
                    modifier = Modifier.testTag("cs_tag_$tag")
                )
            }
        }
    }
}

@Composable
private fun LookCardsTab(
    state: StudioUiState,
    vm: StudioViewModel,
    attachedOverlays: List<SavedNewsroomOverlay>,
    onOpenOverlayBuilder: () -> Unit
) {
    val session = state.corePipelineSession
    val allCards = state.savedOverlays

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .testTag("cs_cards_summary"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val countHeadline = when (attachedOverlays.size) {
            0 -> "0 cards attached"
            1 -> {
                val single = attachedOverlays.first()
                when (single.kind) {
                    OverlayObjectKind.QUOTE_BRIEF -> "1 quote card attached"
                    OverlayObjectKind.STAT_CALLOUT -> "1 stat card attached"
                    OverlayObjectKind.LOWER_THIRD -> "1 lower-third card attached"
                }
            }
            else -> "${attachedOverlays.size} cards attached"
        }
        Text(text = countHeadline, style = sans(16.sp, Cs.Ink, 23.sp))

        if (attachedOverlays.isEmpty()) {
            Text(
                text = "No quote or stat cards are attached to this cut yet.",
                style = sans(14.sp, Cs.Muted, 20.sp)
            )
        } else {
            val hasVoicedTiming = session.clauses.any { it.endMs > 0L }
            attachedOverlays.forEachIndexed { idx, ovl ->
                val cleanHeadline = StudioViewModel.sanitizeCardQuoteHeadline(
                    rawPrompt = ovl.headline,
                    fallbackText = ovl.headline,
                    maxChars = 56
                )
                val summaryLine = if (hasVoicedTiming) {
                    val startMs = resolveCardAppearanceMs(session.clauses, idx, attachedOverlays.size)
                    "\"$cleanHeadline\" appears at ${mmss(startMs)} for ${ovl.durationSec} seconds."
                } else {
                    "\"$cleanHeadline\" · shown for ${ovl.durationSec} seconds once voiced."
                }
                Text(
                    text = summaryLine,
                    style = sans(14.sp, Cs.Muted, 20.sp),
                    modifier = Modifier.testTag("cs_attached_card_line_$idx")
                )
            }
        }
    }

    if (allCards.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SAVED CARDS", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
            allCards.forEach { ovl ->
                val attached = ovl.id in session.selectedOverlayIds
                val cleanHeadline = StudioViewModel.sanitizeCardQuoteHeadline(
                    rawPrompt = ovl.headline,
                    fallbackText = ovl.headline,
                    maxChars = 56
                )
                CsKey(
                    onClick = { vm.toggleSavedOverlayForPipeline(ovl.id) },
                    role = if (attached) KeyRole.DOWN else KeyRole.STD,
                    radius = 14.dp,
                    horizontalPadding = 14.dp,
                    contentAlignment = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("cs_overlay_${ovl.id}")
                ) { tint ->
                    Icon(
                        imageVector = if (attached) Icons.Sharp.Check else Icons.Sharp.Add,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = cleanHeadline,
                        style = sans(15.sp, tint, 20.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${ovl.durationSec}s",
                        style = mono(12.sp, Cs.Muted),
                        maxLines = 1
                    )
                }
            }
        }
    }

    CsKey(
        onClick = onOpenOverlayBuilder,
        role = KeyRole.STD,
        radius = 14.dp,
        horizontalPadding = 16.dp,
        contentAlignment = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("cs_open_overlay_builder")
    ) { tint ->
        Icon(Icons.Sharp.FormatQuote, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text("create / manage cards", style = mono(13.sp, tint), maxLines = 1)
    }
}

private fun resolveCardAppearanceMs(
    clauses: List<CoreStoryClause>,
    cardIndex: Int,
    totalCards: Int
): Long {
    if (clauses.isEmpty()) return 0L
    val cardClauses = clauses.filter { it.shotTypeWire.equals("card", ignoreCase = true) }
    if (cardClauses.isNotEmpty()) {
        return cardClauses[cardIndex.coerceIn(0, cardClauses.lastIndex)].startMs
    }
    val chosenIdx = if (totalCards <= 1) {
        clauses.lastIndex
    } else {
        ((cardIndex + 1) * clauses.lastIndex) / totalCards.coerceAtLeast(1)
    }
    return clauses[chosenIdx.coerceIn(0, clauses.lastIndex)].startMs
}
