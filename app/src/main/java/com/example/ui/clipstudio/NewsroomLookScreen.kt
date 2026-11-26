package com.example.ui.clipstudio

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.sharp.PhotoCamera
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.Check
import androidx.compose.material.icons.sharp.Close
import androidx.compose.material.icons.sharp.ContentCopy
import androidx.compose.material.icons.sharp.Edit
import androidx.compose.material.icons.sharp.FormatQuote
import androidx.compose.material.icons.sharp.Movie
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.example.data.CoreRenderOptions
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
 * 04 Video look (Clip Studio v5+ Hardware):
 * - Tappable live broadcast preview showing top-left News Logo Bug (`cornerBugText` / `showBrandBug`),
 *   top-right Cut-Scene Card slot, and bottom Breaking Kicker + Title Bar tinted by live `accentHex`.
 * - 4 raised hardware tabs:
 *   - 0 `Look`: Accent Palette swatches (`crimson`/`amber`/`cobalt`/`mint`), restored old-school
 *     red-needle drag `CsTickRuler` for `BLUR` & `BRIGHTNESS`, 3-way `LIGHT` (`warm`/`neutral`/`cool`),
 *     plus backdrop prompt + saved looks.
 *   - 1 `Style`: Editable top-left News Logo Bug text + `Show Bug` toggle, `BreakingNewsStyle` presets,
 *     and editable `TITLE ON SCREEN` with flat unfilled sparkle AI polish.
 *   - 2 `Cards`: Inline editable Quote/Stat cut-scene cards (edit text/stat/style in place, attach/detach,
 *     or open full A2UI Overlay Builder / generate 3-card set).
 *   - 3 `B-Roll`: Cut-Scene B-Roll framing (`Corner PiP`, `Full Cutaway`, `Split 50/50`) + cutaway preview.
 * - Soft-neumorphic etched-frame dock: `Tweak w/ AI` (natural-language LLM sheet) + `Use Look ✓`.
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

    LaunchedEffect(persisted?.plateId, persisted?.plateUrl) {
        val p = persisted ?: return@LaunchedEffect
        draft = draft.copy(plateId = p.plateId, plateUrl = p.plateUrl, platePrompt = p.platePrompt)
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var aiTweakSheetOpen by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = aiTweakSheetOpen) { aiTweakSheetOpen = false }

    val session = state.corePipelineSession
    val opts = session.renderOptions
    val breakingStyle = opts.breakingStyle
    val resolvedTitle = remember(
        opts.chyronHeadline,
        session.title,
        state.activeProject.stageConfig.chyronHeadline,
        state.activeProject.title
    ) {
        opts.chyronHeadline.trim()
            .ifBlank { session.title.trim() }
            .ifBlank { state.activeProject.stageConfig.chyronHeadline.trim() }
            .ifBlank { state.activeProject.title.trim() }
    }

    val attachedOverlays = remember(state.savedOverlays, session.selectedOverlayIds) {
        val selectedSet = session.selectedOverlayIds.toSet()
        state.savedOverlays.filter { it.id in selectedSet }
    }

    val isLookBusy = state.isGeneratingBackdrop || state.isRefreshingHeadline ||
        state.isGeneratingOverlaySet || state.isTweakingLook
    val busyLabel = when {
        state.isTweakingLook -> "tweaking look with AI…"
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
            // 52dp Header (`arrow_back`, `"Video look"`, `content_copy`)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
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
                    enabled = !isLookBusy,
                    onClick = {
                        val sourceId = persisted?.id ?: vm.createNewsroomLook(draft.name).also { id ->
                            vm.updateNewsroomLook(draft.copy(id = id, isDefault = looks.isEmpty()))
                        }
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

            // 184dp tappable live broadcast preview (tap Logo Bug, Cut-Scene Card, or Title to edit directly!)
            VideoLookPreviewPanel(
                look = draft,
                options = opts,
                titleText = resolvedTitle,
                attachedOverlays = attachedOverlays,
                activeTab = tab,
                onTapLogoBug = { tab = 1 },
                onTapTitle = { tab = 1 },
                onTapCard = { tab = 2 }
            )

            // Raised 4-way hardware tab switch well (`Look` · `Style` · `Cards` · `B-Roll`)
            CsSegmentedRow(
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp)
            ) {
                val tabs = listOf(
                    Triple(0, "Look", Icons.Sharp.Wallpaper),
                    Triple(1, "Style", Icons.Sharp.Title),
                    Triple(2, "Cards", Icons.Sharp.FormatQuote),
                    Triple(3, "B-Roll", Icons.Sharp.Movie)
                )
                tabs.forEach { (idx, label, icon) ->
                    CsSegment(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        height = 42.dp,
                        modifier = Modifier.testTag("cs_newsroom_tab_$idx")
                    ) { tint ->
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                        Text(label, style = mono(11.5.sp, tint), maxLines = 1, softWrap = false)
                    }
                }
            }

            // Tab body (100% unclipped above-the-fold on Look tab: Accent + Red-Needle Blur + Brightness + Light)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    1 -> LookStyleAndLogoTab(
                        state = state,
                        vm = vm,
                        options = opts,
                        currentTitle = resolvedTitle,
                        breakingStyle = breakingStyle
                    )
                    2 -> LookCardsTab(
                        state = state,
                        vm = vm,
                        attachedOverlays = attachedOverlays,
                        onOpenOverlayBuilder = onOpenOverlayBuilder
                    )
                    3 -> LookBrollFramingTab(
                        state = state,
                        vm = vm,
                        options = opts
                    )
                }
            }

            // Soft-neumorphic etched-frame Bottom Dock: `Tweak w/ AI` + `Use Look ✓`
            CsDock(
                leading = {
                    CsDockSideKey(
                        label = "Tweak AI",
                        icon = Icons.Outlined.AutoAwesome,
                        enabled = !isLookBusy,
                        horizontal = true,
                        onClick = {
                            vm.dismissToast()
                            aiTweakSheetOpen = true
                        },
                        modifier = Modifier.testTag("cs_tweak_ai_button")
                    )
                },
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

        if (aiTweakSheetOpen) {
            AiLookTweakSheet(
                isBusy = state.isTweakingLook,
                onDismiss = { aiTweakSheetOpen = false },
                onSubmit = { instruction ->
                    aiTweakSheetOpen = false
                    vm.tweakVideoLookWithAi(
                        instruction = instruction,
                        currentLook = draft,
                        onLookUpdated = { updated -> draft = updated }
                    )
                }
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
    options: CoreRenderOptions,
    titleText: String,
    attachedOverlays: List<SavedNewsroomOverlay>,
    activeTab: Int,
    onTapLogoBug: () -> Unit,
    onTapTitle: () -> Unit,
    onTapCard: () -> Unit
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
    val accentColor = remember(look.accentHex) { parseAccentHex(look.accentHex) }

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
    val cardOverlay = attachedOverlays.firstOrNull { it.kind != OverlayObjectKind.LOWER_THIRD }
        ?: attachedOverlays.firstOrNull()
    val displayTitle = chyronOverlay?.headline?.ifBlank { null }
        ?: titleText.ifBlank { "Untitled clip" }

    val breakingStyle = options.breakingStyle
    val tagKey = breakingStyle.titleTagKey
    val showKicker = tagKey != "none"
    val accentLuma = 0.2126f * accentColor.red + 0.7152f * accentColor.green + 0.0722f * accentColor.blue
    val accentFg = if (accentLuma >= 0.6f) Cs.Black else Cs.White
    val (kickerBg, kickerFg) = when (tagKey) {
        "breaking" -> accentColor to accentFg
        "developing" -> Cs.AmberBright to Cs.Black
        "explainer" -> Cs.Ink to Cs.Black
        else -> Color.Transparent to Cs.White
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .height(184.dp)
            .sunkenPanel(radius = 16.dp)
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
                    .padding(12.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            )
        }

        // Top-left tappable News Logo / Brand Bug
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = if (options.showBrandBug) 0.78f else 0.42f))
                .border(
                    width = if (activeTab == 1) 1.5.dp else 1.dp,
                    color = if (activeTab == 1) Cs.White else Color.White.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable(role = Role.Button, onClick = onTapLogoBug)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("cs_preview_logo_bug"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (options.showBrandBug) accentColor else Cs.Dim)
            )
            Text(
                text = if (options.showBrandBug) {
                    options.cornerBugText.ifBlank { "AIRCHECK" }
                } else {
                    "LOGO OFF"
                },
                style = ndot(11.sp, if (options.showBrandBug) Cs.White else Cs.Muted),
                maxLines = 1
            )
        }

        // Top-right tappable Cut-Scene Card preview slot
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 12.dp)
                .width(138.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.78f))
                .border(
                    width = if (activeTab == 2) 1.5.dp else 1.dp,
                    color = if (activeTab == 2) Cs.White else accentColor.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable(role = Role.Button, onClick = onTapCard)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("cs_preview_card_slot"),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = cardOverlay?.kind?.name?.replace("_", " ") ?: "CUT-SCENE CARD",
                style = mono(8.5.sp, accentColor, FontWeight.Bold, 0.06f),
                maxLines = 1
            )
            Text(
                text = cardOverlay?.headline?.ifBlank { null } ?: "Tap to add/edit",
                style = sans(11.5.sp, Cs.White, 15.sp, FontWeight.Medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Bottom tappable Breaking Kicker + Title Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onTapTitle)
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
                    .border(
                        width = if (activeTab == 1) 1.dp else 0.75.dp,
                        color = if (activeTab == 1) Cs.White.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(
                            topStart = if (showKicker) 0.dp else 10.dp,
                            topEnd = 10.dp,
                            bottomStart = 10.dp,
                            bottomEnd = 10.dp
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = displayTitle,
                    style = sans(14.5.sp, Cs.White, 19.sp, FontWeight.Medium),
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
    // 1. Editable Design System Accent Palette (unified silver-bezel recessed-OLED hardware strip)
    VideoEditOledStrip(height = 44.dp) {
        val accents = listOf(
            Triple("crimson", NewsroomLook.ACCENT_CRIMSON, HwLed.Red),
            Triple("amber", NewsroomLook.ACCENT_AMBER, HwLed.Amber),
            Triple("cobalt", NewsroomLook.ACCENT_COBALT, HwLed.Blue),
            Triple("mint", NewsroomLook.ACCENT_MINT, HwLed.Green)
        )
        accents.forEach { (name, hex, color) ->
            val selected = draft.accentHex.equals(hex, ignoreCase = true)
            VideoEditStripInset(
                label = name,
                selected = selected,
                ledColor = color,
                dimLedPreview = color,
                onClick = { onUpdateDraft(draft.copy(accentHex = hex)) },
                modifier = Modifier.testTag("cs_accent_$name")
            )
        }
    }

    // 2. Restored v4 Old-School Red-Needle Drag Tick Rulers for BLUR & BRIGHTNESS!
    CsTickRuler(
        label = "BLUR",
        valueText = "${draft.blurPercent}%",
        normalizedValue = (draft.blurPercent / 100f).coerceIn(0f, 1f),
        enabled = true,
        onStep = { delta ->
            onUpdateDraft(draft.copy(blurPercent = (draft.blurPercent + delta * 5).coerceIn(0, 100)))
        },
        tag = "cs_scrub_blur"
    )

    val evLabel = "${if (draft.brightnessEv > 0) "+" else ""}${"%.1f".format(draft.brightnessEv)} EV"
    CsTickRuler(
        label = "BRIGHTNESS",
        valueText = evLabel,
        normalizedValue = (((draft.brightnessEv + 3.0) / 6.0).toFloat()).coerceIn(0f, 1f),
        enabled = true,
        onStep = { delta ->
            val next = (Math.round((draft.brightnessEv + delta * 0.2) * 10.0) / 10.0).coerceIn(-3.0, 3.0)
            onUpdateDraft(draft.copy(brightnessEv = next))
        },
        tag = "cs_scrub_ev"
    )

    // 3. LIGHT 3-way silver-bezel OLED hardware strip (`warm` · `neutral` · `cool`)
    VideoEditOledStrip(height = 44.dp) {
        val lightChoices = listOf(
            Triple(NewsroomLook.LIGHT_WARM, "warm", HwLed.Orange),
            Triple(NewsroomLook.LIGHT_NEUTRAL, "neutral", Color.White),
            Triple(NewsroomLook.LIGHT_COOL, "cool", HwLed.Blue)
        )
        lightChoices.forEach { (wire, label, ledCol) ->
            VideoEditStripInset(
                label = label,
                selected = draft.light == wire,
                ledColor = ledCol,
                onClick = { onUpdateDraft(draft.copy(light = wire)) },
                modifier = Modifier.testTag("cs_light_$label")
            )
        }
    }

    // 4. Presenter Photo on Device (PickVisualMedia -> CAS JPEG -> HeyGen talking-head portrait)
    val anchorPhotoFile = remember(
        state.activeProject.stageConfig.photoSha,
        state.settings.persona.defaultPhotoSha
    ) {
        vm.resolveAnchorPhotoFile()
    }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            vm.importAnchorPhoto(uri)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PRESENTER PHOTO", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .sunkenPanel(radius = 14.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Cs.Track)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (anchorPhotoFile != null && anchorPhotoFile.isFile) {
                    AsyncImage(
                        model = anchorPhotoFile,
                        contentDescription = "Selected presenter photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Sharp.PhotoCamera,
                        contentDescription = null,
                        tint = Cs.Muted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = if (anchorPhotoFile != null) "Custom phone photo" else "Default studio portrait",
                style = sans(13.5.sp, Cs.Ink, 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (anchorPhotoFile != null) {
                CsKeyLabel(
                    label = "default",
                    onClick = vm::clearAnchorPhoto,
                    role = KeyRole.STD,
                    radius = 10.dp,
                    size = 11.5.sp,
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("cs_reset_photo")
                )
            }
            VideoEditOledKey(
                label = "choose photo",
                active = anchorPhotoFile != null,
                ledColor = HwLed.Green,
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                height = 40.dp,
                fontSize = 11.5.sp,
                modifier = Modifier.testTag("cs_choose_photo")
            )
        }
    }

    // 5. Backdrop plate generator & saved looks (scrolls cleanly right below fold)
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
                .sunkenPanel(radius = 14.dp)
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
            VideoEditOledKey(
                label = if (state.isGeneratingBackdrop) "working…" else "generate",
                active = promptState.text.toString().isNotBlank() && !state.isGeneratingBackdrop,
                ledColor = HwLed.Blue,
                onClick = {
                    val lookId = if (looks.any { it.id == draft.id }) {
                        draft.id
                    } else {
                        vm.createNewsroomLook(draft.name).also(onSelectLook)
                    }
                    vm.generateBackdropForLook(lookId, promptState.text.toString())
                },
                enabled = promptState.text.toString().isNotBlank() && !state.isGeneratingBackdrop,
                height = 42.dp,
                fontSize = 11.5.sp,
                modifier = Modifier.testTag("cs_backdrop_generate")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookStyleAndLogoTab(
    state: StudioUiState,
    vm: StudioViewModel,
    options: CoreRenderOptions,
    currentTitle: String,
    breakingStyle: BreakingNewsStyle
) {
    // 1. Top-Left News Logo / Brand Bug editor
    val latestOptions by rememberUpdatedState(options)
    val bugFieldState = rememberTextFieldState(initialText = options.cornerBugText)
    LaunchedEffect(options.cornerBugText) {
        if (options.cornerBugText != bugFieldState.text.toString()) {
            bugFieldState.setTextAndPlaceCursorAtEnd(options.cornerBugText)
        }
    }
    LaunchedEffect(bugFieldState) {
        snapshotFlow { bugFieldState.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .collect { edited ->
                val cur = latestOptions
                if (edited.trim() != cur.cornerBugText.trim() && edited.isNotBlank()) {
                    vm.setCoreBrandBug(show = cur.showBrandBug, text = edited)
                }
            }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TOP-LEFT NEWS LOGO BUG", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .sunkenPanel(radius = 14.dp)
                .padding(start = 14.dp, top = 4.dp, end = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                state = bugFieldState,
                textStyle = ndot(15.sp, Cs.White),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_bug_input"),
                decorator = { inner ->
                    Box {
                        if (bugFieldState.text.isEmpty()) {
                            Text("Channel bug text…", style = mono(13.sp, Cs.Dim))
                        }
                        inner()
                    }
                }
            )
            VideoEditOledKey(
                label = if (options.showBrandBug) "bug on" else "bug off",
                active = options.showBrandBug,
                ledColor = HwLed.Green,
                height = 42.dp,
                fontSize = 11.5.sp,
                onClick = {
                    vm.setCoreBrandBug(
                        show = !options.showBrandBug,
                        text = bugFieldState.text.toString()
                    )
                },
                modifier = Modifier.testTag("cs_bug_toggle")
            )
        }
    }

    // 2. Breaking News Style / Tag Above Title
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("BREAKING NEWS STYLE", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        val activeTagKey = breakingStyle.titleTagKey
        val tags = listOf(
            "none" to Color.White,
            "breaking" to HwLed.Red,
            "developing" to HwLed.Amber,
            "explainer" to HwLed.Blue
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tags.forEach { (tag, accent) ->
                VideoEditOledKey(
                    label = tag,
                    active = activeTagKey == tag,
                    ledColor = accent,
                    dimLedPreview = if (tag == "none") null else accent,
                    onClick = { vm.setTitleTagStyle(tag) },
                    height = 42.dp,
                    fontSize = 11.5.sp,
                    modifier = Modifier.testTag("cs_tag_$tag")
                )
            }
        }
    }

    // 3. Title Bar Text + Flat Unfilled Sparkle AI Refresh
    val titleFieldState = rememberTextFieldState(initialText = currentTitle)
    LaunchedEffect(currentTitle) {
        if (currentTitle != titleFieldState.text.toString()) {
            titleFieldState.setTextAndPlaceCursorAtEnd(currentTitle)
        }
    }
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("TITLE ON SCREEN", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .sunkenPanel(radius = 14.dp)
                .padding(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
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
                    .size(44.dp)
                    .clickable(
                        enabled = !state.isRefreshingHeadline,
                        role = Role.Button,
                        onClick = vm::refreshHeadlineWithAi
                    )
                    .testTag("cs_title_refresh"),
                contentAlignment = Alignment.Center
            ) {
                if (state.isRefreshingHeadline) {
                    ThinkingOrbSolving(size = 26.dp, tint = Cs.White)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = "Refresh title with AI",
                        tint = Cs.InkSub,
                        modifier = Modifier.size(22.dp)
                    )
                }
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
    var editingCardId by rememberSaveable { mutableStateOf<String?>(null) }

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
        Text(text = countHeadline, style = sans(15.sp, Cs.Ink, 21.sp))

        if (attachedOverlays.isEmpty()) {
            Text(
                text = "No quote or stat cards are attached to this cut yet.",
                style = sans(13.5.sp, Cs.Muted, 19.sp)
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
                    style = sans(13.5.sp, Cs.Muted, 19.sp),
                    modifier = Modifier.testTag("cs_attached_card_line_$idx")
                )
            }
        }
    }

    if (allCards.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SAVED CARDS · TAP TO TOGGLE OR EDIT", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
            allCards.forEach { ovl ->
                val attached = ovl.id in session.selectedOverlayIds
                val cleanHeadline = StudioViewModel.sanitizeCardQuoteHeadline(
                    rawPrompt = ovl.headline,
                    fallbackText = ovl.headline,
                    maxChars = 56
                )
                if (editingCardId == ovl.id) {
                    InlineCardEditor(
                        overlay = ovl,
                        onSave = { newHeadline, newStat ->
                            vm.updateSavedOverlayInline(
                                overlayId = ovl.id,
                                headline = newHeadline,
                                calloutStat = newStat
                            )
                            editingCardId = null
                        },
                        onCancel = { editingCardId = null }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VideoEditOledKey(
                            label = cleanHeadline,
                            icon = if (attached) Icons.Sharp.Check else Icons.Sharp.Add,
                            active = attached,
                            ledColor = HwLed.Green,
                            trailingText = "${ovl.durationSec}s",
                            onClick = { vm.toggleSavedOverlayForPipeline(ovl.id) },
                            height = 46.dp,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cs_overlay_${ovl.id}")
                        )
                        CsIconKey(
                            icon = Icons.Sharp.Edit,
                            contentDescription = "Edit card text",
                            size = 46.dp,
                            radius = 12.dp,
                            iconSize = 18.dp,
                            onClick = { editingCardId = ovl.id },
                            modifier = Modifier.testTag("cs_overlay_edit_${ovl.id}")
                        )
                    }
                }
            }
        }
    }

    VideoEditOledKey(
        label = "create / generate cards",
        icon = Icons.Sharp.FormatQuote,
        active = attachedOverlays.isNotEmpty(),
        ledColor = HwLed.Blue,
        onClick = onOpenOverlayBuilder,
        height = 46.dp,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cs_open_overlay_builder")
    )
}

@Composable
private fun InlineCardEditor(
    overlay: SavedNewsroomOverlay,
    onSave: (String, String) -> Unit,
    onCancel: () -> Unit
) {
    val headState = rememberTextFieldState(initialText = overlay.headline)
    val statState = rememberTextFieldState(initialText = overlay.calloutStat)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 14.dp)
            .padding(12.dp)
            .testTag("cs_card_inline_editor_${overlay.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("EDIT ${overlay.kind.name.replace("_", " ")}", style = SectionLabelStyle)
        BasicTextField(
            state = headState,
            textStyle = sans(15.sp, Cs.Ink, 20.sp),
            cursorBrush = SolidColor(Cs.Ink),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 3),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Cs.Body)
                .border(1.dp, Cs.Hairline, RoundedCornerShape(10.dp))
                .padding(10.dp)
                .testTag("cs_card_headline_field_${overlay.id}")
        )
        if (overlay.kind == OverlayObjectKind.STAT_CALLOUT || overlay.calloutStat.isNotBlank()) {
            BasicTextField(
                state = statState,
                textStyle = mono(14.sp, Cs.AmberBright, FontWeight.SemiBold),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Cs.Body)
                    .border(1.dp, Cs.Hairline, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CsKeyLabel(
                label = "save card",
                onClick = { onSave(headState.text.toString(), statState.text.toString()) },
                role = KeyRole.ACTIVE,
                radius = 10.dp,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("cs_card_save_${overlay.id}")
            )
            CsKeyLabel(
                label = "cancel",
                onClick = onCancel,
                role = KeyRole.STD,
                radius = 10.dp,
                modifier = Modifier
                    .height(42.dp)
                    .testTag("cs_card_cancel_${overlay.id}")
            )
        }
    }
}

@Composable
private fun LookBrollFramingTab(
    state: StudioUiState,
    vm: StudioViewModel,
    options: CoreRenderOptions
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("CUT-SCENE B-ROLL FRAMING", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        VideoEditOledStrip(height = 46.dp) {
            val modes = listOf(
                CoreRenderOptions.BROLL_FRAMING_PIP to "Corner PiP",
                CoreRenderOptions.BROLL_FRAMING_FULL to "Full Cut",
                CoreRenderOptions.BROLL_FRAMING_SPLIT to "Split 50/50"
            )
            modes.forEach { (wire, label) ->
                VideoEditStripInset(
                    label = label,
                    selected = options.brollFraming == wire,
                    ledColor = HwLed.Green,
                    onClick = { vm.setCoreBrollFraming(wire) },
                    modifier = Modifier.testTag("cs_broll_framing_$wire")
                )
            }
        }
    }

    val brollClauses = state.corePipelineSession.clauses.filter {
        it.segmentMode == com.example.data.BrollSegmentMode.BROLL_CUTAWAY
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 14.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "${brollClauses.size} B-ROLL CUT-SCENE${if (brollClauses.size == 1) "" else "S"} IN THIS CLIP",
            style = SectionLabelStyle
        )
        if (brollClauses.isEmpty()) {
            Text(
                text = "Mark lines as B-roll in 03 CUT -> b-roll tab to cut away with ${options.brollFraming} framing.",
                style = sans(13.5.sp, Cs.Muted, 19.sp)
            )
        } else {
            brollClauses.take(4).forEach { cl ->
                Text(
                    text = "${mmss(cl.startMs)} · ${cl.bRollPrompt.ifBlank { cl.brollCaption.ifBlank { cl.spokenText } }}",
                    style = sans(13.5.sp, Cs.InkSub, 19.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AiLookTweakSheet(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    val fieldState = rememberTextFieldState()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(onClick = onDismiss)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Cs.Sheet)
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp)
                .testTag("cs_ai_tweak_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = Cs.Ink,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "  Tweak video look with AI",
                    style = sans(18.sp, Cs.Ink, 24.sp, FontWeight.Medium),
                    modifier = Modifier.weight(1f)
                )
                CsIconKey(
                    icon = Icons.Sharp.Close,
                    contentDescription = "Close AI tweak sheet",
                    onClick = onDismiss,
                    modifier = Modifier.testTag("cs_ai_tweak_close")
                )
            }
            Text(
                text = "Change colors, top-left logo, brightness/blur, breaking style, B-roll framing, or headline in plain English.",
                style = sans(13.5.sp, Cs.Muted, 19.sp)
            )
            BasicTextField(
                state = fieldState,
                textStyle = sans(15.5.sp, Cs.Ink, 22.sp),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 6),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .sunkenPanel(radius = 14.dp)
                    .padding(12.dp)
                    .testTag("cs_ai_tweak_input"),
                decorator = { inner ->
                    Box {
                        if (fieldState.text.isEmpty()) {
                            Text(
                                "e.g. \"Cobalt blue accent, show top-left logo RYAN LIVE, full cut B-roll, +0.4 EV\"",
                                style = sans(14.5.sp, Cs.Dim, 20.sp)
                            )
                        }
                        inner()
                    }
                }
            )
            CsDock(
                primaryLabel = "apply AI tweak",
                primaryTrailingIcon = Icons.Outlined.AutoAwesome,
                primaryEnabled = fieldState.text.toString().isNotBlank() && !isBusy,
                primaryTag = "cs_ai_tweak_submit",
                onPrimaryClick = { onSubmit(fieldState.text.toString()) },
                isBusy = isBusy,
                busyLabel = "tweaking look…"
            )
        }
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
