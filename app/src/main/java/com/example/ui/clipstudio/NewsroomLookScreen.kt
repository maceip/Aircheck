package com.example.ui.clipstudio

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.sharp.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.Check
import androidx.compose.material.icons.sharp.Close
import androidx.compose.material.icons.sharp.Delete
import androidx.compose.material.icons.sharp.Edit
import androidx.compose.material.icons.sharp.FormatQuote
import androidx.compose.material.icons.sharp.Movie
import androidx.compose.material.icons.sharp.PhotoCamera
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
import com.example.data.NewsroomLook
import com.example.data.OverlayObjectKind
import com.example.data.SavedNewsroomOverlay
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import java.io.File
import kotlin.math.pow

/**
 * Dedicated full-view Newsroom Overlays Editor:
 * - Live aspect-accurate (`9:16` / `16:9`) broadcast monitor preview showing presenter photo or
 *   backdrop plate, real lighting/blur/brightness grade, top-left corner bug, upper-left quote/stat
 *   card, and lower-third chyron with breaking style kicker.
 * - Three focused tabs:
 *   - 0 `Labels`: toggle & edit Lower-Third Chyron (headline, subline, style, AI polish) + Top-Left Corner Bug.
 *   - 1 `Overlays`: create new custom overlays (Lower Third / Stat Card / Quote Card), AI-generate a 3-overlay
 *     set, inspect/edit every field inline, toggle attach/detach on this clip, or delete.
 *   - 2 `Look`: accent color, lighting tone, blur & brightness rulers, film grain & frame border,
 *     presenter photo picker, AI backdrop generator, and saved look presets.
 * - Clear dock: `Tweak AI` + `Apply & Render Video` (actually triggers render when talking head exists!).
 */
@Composable
fun NewsroomLookScreen(
    state: StudioUiState,
    vm: StudioViewModel,
    onBack: () -> Unit,
    onTestOnClip: (lookId: String) -> Unit = {},
    onOpenOverlayBuilder: () -> Unit = {},
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

    LaunchedEffect(persisted?.plateId, persisted?.plateUrl, persisted?.accentHex, persisted?.light, persisted?.blurPercent, persisted?.brightnessEv, persisted?.grain, persisted?.border) {
        val p = persisted ?: return@LaunchedEffect
        draft = p
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

    val anchorPhotoFile = remember(
        state.activeProject.stageConfig.photoSha,
        state.settings.persona.defaultPhotoSha
    ) {
        vm.resolveAnchorPhotoFile()
    }

    val isLookBusy = state.isGeneratingBackdrop || state.isRefreshingHeadline ||
        state.isGeneratingOverlaySet || state.isTweakingLook
    val busyLabel = when {
        state.isTweakingLook -> "tweaking with AI…"
        state.isGeneratingBackdrop -> "generating backdrop…"
        state.isRefreshingHeadline -> "polishing headline…"
        state.isGeneratingOverlaySet -> "generating overlay set…"
        else -> null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Cs.Body)
            .testTag("cs_newsroom_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header: Back, Title, Undo, Save
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(start = 8.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CsIconKey(
                    icon = Icons.AutoMirrored.Sharp.ArrowBack,
                    contentDescription = "Back to Cut",
                    onClick = onBack,
                    modifier = Modifier.testTag("cs_newsroom_back")
                )
                Text(
                    text = "Newsroom Overlays",
                    style = sans(17.sp, Cs.Ink, weight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                CsIconKey(
                    icon = Icons.AutoMirrored.Sharp.Undo,
                    contentDescription = "Undo overlay change",
                    enabled = state.coreUndoDepth > 0,
                    onClick = vm::undoCoreEdit,
                    size = 40.dp,
                    radius = 11.dp,
                    iconSize = 20.dp,
                    modifier = Modifier.testTag("cs_newsroom_undo")
                )
                CsKeyLabel(
                    label = "save",
                    onClick = {
                        vm.useLookAndReturn(draft.id, draftOverride = draft)
                        onBack()
                    },
                    enabled = !isLookBusy,
                    role = KeyRole.STD,
                    radius = 11.dp,
                    size = 12.sp,
                    modifier = Modifier
                        .height(40.dp)
                        .testTag("cs_save_look_only")
                )
            }

            CsStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

            // Live aspect-accurate broadcast preview canvas
            BroadcastOverlayLivePreview(
                look = draft,
                options = opts,
                titleText = resolvedTitle,
                sublineText = opts.chyronSubline,
                anchorPhotoFile = anchorPhotoFile,
                attachedOverlays = attachedOverlays,
                activeTab = tab,
                onTapLogoBug = { tab = 0 },
                onTapTitle = { tab = 0 },
                onTapCard = { tab = 1 }
            )

            // 3 focused full-view tabs (`Labels` · `Overlays` · `Look`)
            CsSegmentedRow(
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 10.dp)
            ) {
                val tabs = listOf(
                    Triple(0, "Labels", Icons.Sharp.Title),
                    Triple(1, "Overlays (${attachedOverlays.size})", Icons.Sharp.FormatQuote),
                    Triple(2, "Look", Icons.Sharp.Wallpaper)
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

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (tab) {
                    0 -> LabelsAndChyronTab(
                        state = state,
                        vm = vm,
                        options = opts,
                        currentTitle = resolvedTitle,
                        breakingStyle = breakingStyle
                    )
                    1 -> OverlaysManagerTab(
                        state = state,
                        vm = vm,
                        attachedOverlays = attachedOverlays,
                        onOpenOverlayBuilder = onOpenOverlayBuilder
                    )
                    2 -> StudioLookTab(
                        state = state,
                        vm = vm,
                        draft = draft,
                        looks = looks,
                        anchorPhotoFile = anchorPhotoFile,
                        onSelectLook = { id -> selectedId = id },
                        onUpdateDraft = { updated ->
                            draft = updated
                            vm.updateNewsroomLook(updated)
                        }
                    )
                }
            }

            // Actionable Bottom Dock: saves & directly renders when talking head exists!
            val canRenderNow = session.hasTalkingHead && !state.isCoreStageBusy
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
                primaryLabel = if (canRenderNow) "apply & render video" else "save overlays",
                primaryTrailingIcon = if (canRenderNow) Icons.Sharp.Movie else Icons.Sharp.Check,
                primaryRole = KeyRole.COMMIT,
                primaryEnabled = !isLookBusy && !state.isCoreStageBusy,
                primaryTag = "cs_use_look",
                onPrimaryClick = {
                    vm.useLookAndReturn(draft.id, draftOverride = draft)
                    if (canRenderNow) {
                        onTestOnClip(draft.id)
                    } else {
                        onBack()
                    }
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
private fun BroadcastOverlayLivePreview(
    look: NewsroomLook,
    options: CoreRenderOptions,
    titleText: String,
    sublineText: String,
    anchorPhotoFile: File?,
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
    val blurDp = ((look.blurPercent / 100f) * 20f).dp
    val accentColor = remember(look.accentHex) { parseAccentHex(look.accentHex) }

    val ambientCenterColor = remember(look.light, gain) {
        val base = when (look.light) {
            NewsroomLook.LIGHT_WARM -> Color(0xFF3A2A1C)
            NewsroomLook.LIGHT_COOL -> Color(0xFF1C2A3A)
            else -> Color(0xFF282830)
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
    val showChyron = options.showLowerThird || chyronOverlay != null
    val displayTitle = chyronOverlay?.headline?.ifBlank { null }
        ?: titleText.ifBlank { "Untitled clip" }
    val displaySubline = chyronOverlay?.subline?.ifBlank { null }
        ?: sublineText.trim()

    val breakingStyle = chyronOverlay?.breakingStyle ?: options.breakingStyle
    val kickerLabel = breakingStyle.bannerText
    val showKicker = kickerLabel.isNotBlank()
    val accentLuma = 0.2126f * accentColor.red + 0.7152f * accentColor.green + 0.0722f * accentColor.blue
    val accentFg = if (accentLuma >= 0.6f) Cs.Black else Cs.White
    val (kickerBg, kickerFg) = when (breakingStyle) {
        BreakingNewsStyle.BREAKING, BreakingNewsStyle.ALERT_STAT -> accentColor to accentFg
        BreakingNewsStyle.DEVELOPING -> Cs.AmberBright to Cs.Black
        BreakingNewsStyle.EXCLUSIVE -> Cs.White to Cs.Black
        BreakingNewsStyle.CLEAN -> Color.Transparent to Cs.White
    }

    val isVertical = options.aspect == CoreRenderOptions.ASPECT_VERTICAL

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .height(194.dp)
            .sunkenPanel(radius = 16.dp)
            .testTag("cs_look_canvas"),
        contentAlignment = Alignment.Center
    ) {
        // Aspect-ratio preview badge top-right of outer stage when vertical
        if (isVertical) {
            Text(
                text = "9:16",
                style = mono(10.sp, Cs.Dim, FontWeight.Medium),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            )
        }

        val frameModifier = if (isVertical) {
            Modifier
                .fillMaxHeight()
                .padding(vertical = 6.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
        } else {
            Modifier.fillMaxSize()
        }

        Box(modifier = frameModifier) {
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
            } else if (anchorPhotoFile != null && anchorPhotoFile.isFile) {
                AsyncImage(
                    model = anchorPhotoFile,
                    contentDescription = "Presenter preview",
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(matrix),
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (Build.VERSION.SDK_INT >= 31 && blurDp > 0.dp) Modifier.blur(blurDp) else Modifier)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(ambientCenterColor, Color(0xFF0A0A0D)),
                                radius = 480f
                            )
                        )
                )
            }

            if (look.border) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .border(1.5.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                )
            }

            // Top-left Corner Bug
            if (options.showBrandBug) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.82f))
                        .border(
                            width = if (activeTab == 0) 1.dp else 0.5.dp,
                            color = if (activeTab == 0) Cs.White else Color.White.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable(role = Role.Button, onClick = onTapLogoBug)
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                        .testTag("cs_preview_logo_bug"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Text(
                        text = options.cornerBugText.ifBlank { "AIRCHECK" },
                        style = ndot(9.5.sp, Cs.White),
                        maxLines = 1
                    )
                }
            }

            // Attached Quote / Stat Card preview
            if (cardOverlay != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = 8.dp)
                        .width(if (isVertical) 82.dp else 148.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.82f))
                        .border(1.dp, accentColor, RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClick = onTapCard)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("cs_preview_card_slot"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = cardOverlay.calloutStat.ifBlank { cardOverlay.kind.label }.uppercase(),
                        style = mono(7.5.sp, accentColor, FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = cardOverlay.headline,
                        style = sans(9.5.sp, Cs.White, 12.sp, FontWeight.Medium),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Bottom Lower-Third Chyron
            if (showChyron) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onTapTitle)
                        .testTag("cs_zone_headline"),
                    horizontalAlignment = Alignment.Start
                ) {
                    if (showKicker) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(kickerBg)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("cs_canvas_breaking_kicker")
                        ) {
                            Text(
                                text = kickerLabel,
                                style = mono(8.sp, kickerFg, FontWeight.Bold),
                                maxLines = 1
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(
                                RoundedCornerShape(
                                    topStart = if (showKicker) 0.dp else 6.dp,
                                    topEnd = 6.dp,
                                    bottomStart = 6.dp,
                                    bottomEnd = 6.dp
                                )
                            )
                            .background(Color.Black.copy(alpha = 0.86f))
                            .border(
                                width = 1.dp,
                                color = accentColor.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(
                                    topStart = if (showKicker) 0.dp else 6.dp,
                                    topEnd = 6.dp,
                                    bottomStart = 6.dp,
                                    bottomEnd = 6.dp
                                )
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = displayTitle,
                            style = sans(if (isVertical) 10.sp else 13.5.sp, Cs.White, if (isVertical) 13.sp else 17.sp, FontWeight.Medium),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (displaySubline.isNotBlank()) {
                            Text(
                                text = displaySubline,
                                style = mono(if (isVertical) 7.5.sp else 10.sp, accentColor),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelsAndChyronTab(
    state: StudioUiState,
    vm: StudioViewModel,
    options: CoreRenderOptions,
    currentTitle: String,
    breakingStyle: BreakingNewsStyle
) {
    // 1. Lower-Third Chyron Card
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

    val sublineFieldState = rememberTextFieldState(initialText = options.chyronSubline)
    LaunchedEffect(options.chyronSubline) {
        if (options.chyronSubline != sublineFieldState.text.toString()) {
            sublineFieldState.setTextAndPlaceCursorAtEnd(options.chyronSubline)
        }
    }
    LaunchedEffect(sublineFieldState) {
        snapshotFlow { sublineFieldState.text.toString() }
            .distinctUntilChanged()
            .drop(1)
            .collect { edited ->
                if (edited.trim() != options.chyronSubline.trim()) {
                    vm.updateChyronSubline(edited)
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 16.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("LOWER-THIRD CHYRON", style = SectionLabelStyle)
            CsKeyLabel(
                label = if (options.showLowerThird) "ON" else "OFF",
                onClick = { vm.setCoreShowLowerThird(!options.showLowerThird) },
                role = if (options.showLowerThird) KeyRole.ACTIVE else KeyRole.STD,
                radius = 10.dp,
                size = 11.5.sp,
                modifier = Modifier
                    .height(34.dp)
                    .testTag("cs_chyron_toggle")
            )
        }

        if (options.showLowerThird) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cs.Body)
                    .border(1.dp, Cs.Hairline, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BasicTextField(
                    state = titleFieldState,
                    textStyle = sans(15.sp, Cs.Ink, 20.sp, FontWeight.Medium),
                    cursorBrush = SolidColor(Cs.Ink),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 2),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_title_input"),
                    decorator = { inner ->
                        Box {
                            if (titleFieldState.text.isEmpty()) {
                                Text("Headline on screen…", style = sans(15.sp, Cs.Dim, 20.sp))
                            }
                            inner()
                        }
                    }
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(
                            enabled = !state.isRefreshingHeadline,
                            role = Role.Button,
                            onClick = vm::refreshHeadlineWithAi
                        )
                        .testTag("cs_title_refresh"),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isRefreshingHeadline) {
                        ThinkingOrbSolving(size = 24.dp, tint = Cs.White)
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = "Polish headline with AI",
                            tint = Cs.InkSub,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            BasicTextField(
                state = sublineFieldState,
                textStyle = mono(13.sp, Cs.InkSub),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cs.Body)
                    .border(1.dp, Cs.Hairline, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("cs_subline_input"),
                decorator = { inner ->
                    Box {
                        if (sublineFieldState.text.isEmpty()) {
                            Text("Subline or source label (optional)…", style = mono(13.sp, Cs.Dim))
                        }
                        inner()
                    }
                }
            )

            Text("KICKER BADGE STYLE", style = SectionLabelStyle)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BreakingNewsStyle.entries.forEach { style ->
                    val selected = breakingStyle == style
                    CsKeyLabel(
                        label = style.chipLabel,
                        onClick = { vm.setCoreBreakingStyle(style) },
                        role = if (selected) KeyRole.ACTIVE else KeyRole.STD,
                        radius = 10.dp,
                        size = 11.5.sp,
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("cs_tag_${style.wire}")
                    )
                }
            }
        }
    }

    // 2. Top-Left Brand Bug Card
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 16.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("TOP-LEFT CORNER BUG", style = SectionLabelStyle)
            CsKeyLabel(
                label = if (options.showBrandBug) "ON" else "OFF",
                onClick = {
                    vm.setCoreBrandBug(
                        show = !options.showBrandBug,
                        text = bugFieldState.text.toString()
                    )
                },
                role = if (options.showBrandBug) KeyRole.ACTIVE else KeyRole.STD,
                radius = 10.dp,
                size = 11.5.sp,
                modifier = Modifier
                    .height(34.dp)
                    .testTag("cs_bug_toggle")
            )
        }
        BasicTextField(
            state = bugFieldState,
            textStyle = ndot(15.sp, Cs.White),
            cursorBrush = SolidColor(Cs.Ink),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Cs.Body)
                .border(1.dp, Cs.Hairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("cs_bug_input"),
            decorator = { inner ->
                Box {
                    if (bugFieldState.text.isEmpty()) {
                        Text("Channel label (e.g. AIRCHECK, LIVE)…", style = mono(13.sp, Cs.Dim))
                    }
                    inner()
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlaysManagerTab(
    state: StudioUiState,
    vm: StudioViewModel,
    attachedOverlays: List<SavedNewsroomOverlay>,
    onOpenOverlayBuilder: () -> Unit
) {
    val session = state.corePipelineSession
    val allOverlays = state.savedOverlays
    var creatingNew by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    // Top Action Row: Create New Overlay + AI Generate Set
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CsKey(
            onClick = { creatingNew = !creatingNew },
            role = if (creatingNew) KeyRole.ACTIVE else KeyRole.STD,
            radius = 12.dp,
            horizontalPadding = 12.dp,
            contentAlignment = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .testTag("cs_new_overlay_toggle")
        ) { tint ->
            Icon(Icons.Sharp.Add, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text("new overlay", style = mono(12.sp, tint, FontWeight.Medium), maxLines = 1)
        }
        CsKey(
            onClick = { vm.generateNewsroomOverlaySet() },
            enabled = !state.isGeneratingOverlaySet,
            role = KeyRole.STD,
            radius = 12.dp,
            horizontalPadding = 12.dp,
            contentAlignment = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .testTag("cs_generate_overlay_set")
        ) { tint ->
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            Text(
                text = if (state.isGeneratingOverlaySet) "generating…" else "AI 3-pack",
                style = mono(12.sp, tint),
                maxLines = 1
            )
        }
    }

    if (creatingNew) {
        NewOverlayCreatorCard(
            defaultStyle = session.renderOptions.breakingStyle,
            onCreate = { kind, headline, subline, stat, durationSec, style ->
                val created = vm.createAndAttachOverlay(
                    kind = kind,
                    headline = headline,
                    subline = subline,
                    calloutStat = stat,
                    durationSec = durationSec,
                    breakingStyle = style,
                    attachToCut = true
                )
                if (created != null) {
                    creatingNew = false
                }
            },
            onCancel = { creatingNew = false }
        )
    }

    // Active / Saved Overlays List
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cs_cards_summary"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("OVERLAYS ON THIS CLIP", style = SectionLabelStyle)
            Text(
                text = "${attachedOverlays.size} active",
                style = mono(11.5.sp, if (attachedOverlays.isNotEmpty()) Cs.Mint else Cs.Muted)
            )
        }

        if (allOverlays.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .sunkenPanel(radius = 14.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("No saved overlays yet.", style = sans(15.sp, Cs.Ink))
                Text(
                    text = "Tap 'new overlay' to build a custom lower-third, stat card, or quote card, or generate a 3-pack with AI.",
                    style = sans(13.5.sp, Cs.Muted, 19.sp)
                )
            }
        } else {
            allOverlays.forEach { ovl ->
                val attached = ovl.id in session.selectedOverlayIds
                if (editingId == ovl.id) {
                    InlineOverlayEditorCard(
                        overlay = ovl,
                        onSave = { kind, headline, subline, stat, durationSec, style ->
                            vm.updateSavedOverlayInline(
                                overlayId = ovl.id,
                                headline = headline,
                                subline = subline,
                                calloutStat = stat,
                                breakingStyle = style,
                                kind = kind,
                                durationSec = durationSec
                            )
                            editingId = null
                        },
                        onCancel = { editingId = null }
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sunkenPanel(radius = 14.dp)
                            .border(
                                width = 1.dp,
                                color = if (attached) Cs.Mint.copy(alpha = 0.55f) else Color.Transparent,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable(role = Role.Button) { vm.toggleSavedOverlayForPipeline(ovl.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("cs_overlay_${ovl.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (attached) Cs.Mint else Cs.Track),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (attached) Icons.Sharp.Check else Icons.Sharp.Add,
                                contentDescription = if (attached) "Attached" else "Attach",
                                tint = if (attached) Cs.Black else Cs.Muted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = ovl.headline,
                                style = sans(14.5.sp, Cs.Ink, 19.sp, FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${ovl.kind.label} · ${ovl.durationSec}s",
                                style = mono(11.sp, Cs.Muted),
                                maxLines = 1
                            )
                        }
                        CsIconKey(
                            icon = Icons.Sharp.Edit,
                            contentDescription = "Edit overlay",
                            size = 38.dp,
                            radius = 10.dp,
                            iconSize = 18.dp,
                            onClick = { editingId = ovl.id },
                            modifier = Modifier.testTag("cs_overlay_edit_${ovl.id}")
                        )
                        CsIconKey(
                            icon = Icons.Sharp.Delete,
                            contentDescription = "Delete overlay",
                            size = 38.dp,
                            radius = 10.dp,
                            iconSize = 18.dp,
                            onClick = { vm.deleteSavedNewsroomOverlay(ovl.id) },
                            modifier = Modifier.testTag("cs_overlay_delete_${ovl.id}")
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewOverlayCreatorCard(
    defaultStyle: BreakingNewsStyle,
    onCreate: (OverlayObjectKind, String, String, String, Int, BreakingNewsStyle) -> Unit,
    onCancel: () -> Unit
) {
    var kind by remember { mutableStateOf(OverlayObjectKind.STAT_CALLOUT) }
    var style by remember { mutableStateOf(defaultStyle) }
    var durationSec by remember { mutableIntStateOf(5) }
    val headlineState = rememberTextFieldState()
    val sublineState = rememberTextFieldState()
    val statState = rememberTextFieldState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 16.dp)
            .border(1.dp, Cs.Cobalt.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(12.dp)
            .testTag("cs_new_overlay_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("CREATE NEW OVERLAY", style = SectionLabelStyle)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OverlayObjectKind.entries.forEach { k ->
                CsKeyLabel(
                    label = k.label,
                    onClick = { kind = k },
                    role = if (kind == k) KeyRole.ACTIVE else KeyRole.STD,
                    radius = 10.dp,
                    size = 11.sp,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("cs_new_ovl_kind_${k.name}")
                )
            }
        }

        BasicTextField(
            state = headlineState,
            textStyle = sans(14.5.sp, Cs.Ink, 20.sp),
            cursorBrush = SolidColor(Cs.Ink),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Cs.Body)
                .border(1.dp, Cs.Hairline, RoundedCornerShape(10.dp))
                .padding(10.dp)
                .testTag("cs_new_ovl_headline"),
            decorator = { inner ->
                Box {
                    if (headlineState.text.isEmpty()) Text("Headline text…", style = sans(14.5.sp, Cs.Dim))
                    inner()
                }
            }
        )

        BasicTextField(
            state = sublineState,
            textStyle = sans(13.5.sp, Cs.InkSub, 19.sp),
            cursorBrush = SolidColor(Cs.Ink),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Cs.Body)
                .border(1.dp, Cs.Hairline, RoundedCornerShape(10.dp))
                .padding(10.dp)
                .testTag("cs_new_ovl_subline"),
            decorator = { inner ->
                Box {
                    if (sublineState.text.isEmpty()) Text("Subline / caption (optional)…", style = sans(13.5.sp, Cs.Dim))
                    inner()
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                state = statState,
                textStyle = mono(13.sp, Cs.AmberBright, FontWeight.SemiBold),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Cs.Body)
                    .border(1.dp, Cs.Hairline, RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .testTag("cs_new_ovl_stat"),
                decorator = { inner ->
                    Box {
                        if (statState.text.isEmpty()) Text("Stat / badge (e.g. 42%)…", style = mono(12.sp, Cs.Dim))
                        inner()
                    }
                }
            )
            listOf(3, 5, 8).forEach { sec ->
                CsKeyLabel(
                    label = "${sec}s",
                    onClick = { durationSec = sec },
                    role = if (durationSec == sec) KeyRole.ACTIVE else KeyRole.STD,
                    radius = 10.dp,
                    size = 11.5.sp,
                    modifier = Modifier.height(38.dp)
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BreakingNewsStyle.entries.forEach { st ->
                CsKeyLabel(
                    label = st.chipLabel,
                    onClick = { style = st },
                    role = if (style == st) KeyRole.ACTIVE else KeyRole.STD,
                    radius = 8.dp,
                    size = 11.sp,
                    modifier = Modifier.height(34.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CsKeyLabel(
                label = "create & attach",
                onClick = {
                    onCreate(
                        kind,
                        headlineState.text.toString(),
                        sublineState.text.toString(),
                        statState.text.toString(),
                        durationSec,
                        style
                    )
                },
                enabled = headlineState.text.toString().isNotBlank(),
                role = KeyRole.COMMIT,
                radius = 10.dp,
                size = 12.sp,
                weight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("cs_new_ovl_create")
            )
            CsKeyLabel(
                label = "cancel",
                onClick = onCancel,
                role = KeyRole.STD,
                radius = 10.dp,
                size = 12.sp,
                modifier = Modifier.height(42.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineOverlayEditorCard(
    overlay: SavedNewsroomOverlay,
    onSave: (OverlayObjectKind, String, String, String, Int, BreakingNewsStyle) -> Unit,
    onCancel: () -> Unit
) {
    var kind by remember(overlay.id) { mutableStateOf(overlay.kind) }
    var style by remember(overlay.id) { mutableStateOf(overlay.breakingStyle) }
    var durationSec by remember(overlay.id) { mutableIntStateOf(overlay.durationSec) }
    val headState = rememberTextFieldState(initialText = overlay.headline)
    val subState = rememberTextFieldState(initialText = overlay.subline)
    val statState = rememberTextFieldState(initialText = overlay.calloutStat)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 14.dp)
            .border(1.dp, Cs.Amber, RoundedCornerShape(14.dp))
            .padding(12.dp)
            .testTag("cs_card_inline_editor_${overlay.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("EDIT OVERLAY", style = SectionLabelStyle)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OverlayObjectKind.entries.forEach { k ->
                CsKeyLabel(
                    label = k.label,
                    onClick = { kind = k },
                    role = if (kind == k) KeyRole.ACTIVE else KeyRole.STD,
                    radius = 8.dp,
                    size = 11.sp,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                )
            }
        }
        BasicTextField(
            state = headState,
            textStyle = sans(14.5.sp, Cs.Ink, 20.sp),
            cursorBrush = SolidColor(Cs.Ink),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 2),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Cs.Body)
                .border(1.dp, Cs.Hairline, RoundedCornerShape(10.dp))
                .padding(10.dp)
                .testTag("cs_card_headline_field_${overlay.id}")
        )
        BasicTextField(
            state = subState,
            textStyle = sans(13.5.sp, Cs.InkSub, 19.sp),
            cursorBrush = SolidColor(Cs.Ink),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Cs.Body)
                .border(1.dp, Cs.Hairline, RoundedCornerShape(10.dp))
                .padding(10.dp),
            decorator = { inner ->
                Box {
                    if (subState.text.isEmpty()) Text("Subline…", style = sans(13.5.sp, Cs.Dim))
                    inner()
                }
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                state = statState,
                textStyle = mono(13.sp, Cs.AmberBright, FontWeight.SemiBold),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Cs.Body)
                    .border(1.dp, Cs.Hairline, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                decorator = { inner ->
                    Box {
                        if (statState.text.isEmpty()) Text("Stat / badge…", style = mono(12.sp, Cs.Dim))
                        inner()
                    }
                }
            )
            listOf(3, 5, 8).forEach { sec ->
                CsKeyLabel(
                    label = "${sec}s",
                    onClick = { durationSec = sec },
                    role = if (durationSec == sec) KeyRole.ACTIVE else KeyRole.STD,
                    radius = 8.dp,
                    size = 11.5.sp,
                    modifier = Modifier.height(36.dp)
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BreakingNewsStyle.entries.forEach { st ->
                CsKeyLabel(
                    label = st.chipLabel,
                    onClick = { style = st },
                    role = if (style == st) KeyRole.ACTIVE else KeyRole.STD,
                    radius = 8.dp,
                    size = 11.sp,
                    modifier = Modifier.height(32.dp)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CsKeyLabel(
                label = "save overlay",
                onClick = {
                    onSave(
                        kind,
                        headState.text.toString(),
                        subState.text.toString(),
                        statState.text.toString(),
                        durationSec,
                        style
                    )
                },
                role = KeyRole.ACTIVE,
                radius = 10.dp,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("cs_card_save_${overlay.id}")
            )
            CsKeyLabel(
                label = "cancel",
                onClick = onCancel,
                role = KeyRole.STD,
                radius = 10.dp,
                modifier = Modifier
                    .height(40.dp)
                    .testTag("cs_card_cancel_${overlay.id}")
            )
        }
    }
}

@Composable
private fun StudioLookTab(
    state: StudioUiState,
    vm: StudioViewModel,
    draft: NewsroomLook,
    looks: List<NewsroomLook>,
    anchorPhotoFile: File?,
    onSelectLook: (String) -> Unit,
    onUpdateDraft: (NewsroomLook) -> Unit
) {
    // 1. Accent Color Palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ACCENT COLOR", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val accents = listOf(
                Triple("crimson", NewsroomLook.ACCENT_CRIMSON, Cs.Red),
                Triple("amber", NewsroomLook.ACCENT_AMBER, Cs.Amber),
                Triple("cobalt", NewsroomLook.ACCENT_COBALT, Cs.Cobalt),
                Triple("mint", NewsroomLook.ACCENT_MINT, Cs.Mint)
            )
            accents.forEach { (name, hex, color) ->
                val selected = draft.accentHex.equals(hex, ignoreCase = true)
                CsKey(
                    onClick = { onUpdateDraft(draft.copy(accentHex = hex)) },
                    role = if (selected) KeyRole.ACTIVE else KeyRole.STD,
                    radius = 10.dp,
                    horizontalPadding = 8.dp,
                    contentAlignment = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("cs_accent_$name")
                ) { tint ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Text(name, style = mono(11.sp, tint), maxLines = 1)
                }
            }
        }
    }

    // 2. Studio Lighting + Film Grain / Frame Border
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LIGHTING & FINISH", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                NewsroomLook.LIGHT_WARM to "warm",
                NewsroomLook.LIGHT_NEUTRAL to "neutral",
                NewsroomLook.LIGHT_COOL to "cool"
            ).forEach { (wire, label) ->
                CsKeyLabel(
                    label = label,
                    onClick = { onUpdateDraft(draft.copy(light = wire)) },
                    role = if (draft.light == wire) KeyRole.ACTIVE else KeyRole.STD,
                    radius = 10.dp,
                    size = 12.sp,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("cs_light_$label")
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CsKeyLabel(
                label = if (draft.grain) "film grain: ON" else "film grain: OFF",
                onClick = { onUpdateDraft(draft.copy(grain = !draft.grain)) },
                role = if (draft.grain) KeyRole.ACTIVE else KeyRole.STD,
                radius = 10.dp,
                size = 11.5.sp,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("cs_look_grain")
            )
            CsKeyLabel(
                label = if (draft.border) "frame border: ON" else "frame border: OFF",
                onClick = { onUpdateDraft(draft.copy(border = !draft.border)) },
                role = if (draft.border) KeyRole.ACTIVE else KeyRole.STD,
                radius = 10.dp,
                size = 11.5.sp,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("cs_look_border")
            )
        }
    }

    // 3. Blur & Brightness Rulers
    CsTickRuler(
        label = "BACKDROP BLUR",
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
        label = "EXPOSURE",
        valueText = evLabel,
        normalizedValue = (((draft.brightnessEv + 3.0) / 6.0).toFloat()).coerceIn(0f, 1f),
        enabled = true,
        onStep = { delta ->
            val next = (Math.round((draft.brightnessEv + delta * 0.2) * 10.0) / 10.0).coerceIn(-3.0, 3.0)
            onUpdateDraft(draft.copy(brightnessEv = next))
        },
        tag = "cs_scrub_ev"
    )

    // 4. Presenter Photo
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.importAnchorPhoto(uri)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("PRESENTER PHOTO", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .sunkenPanel(radius = 14.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Cs.Track),
                contentAlignment = Alignment.Center
            ) {
                if (anchorPhotoFile != null && anchorPhotoFile.isFile) {
                    AsyncImage(
                        model = anchorPhotoFile,
                        contentDescription = "Presenter photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Sharp.PhotoCamera, contentDescription = null, tint = Cs.Muted, modifier = Modifier.size(18.dp))
                }
            }
            Text(
                text = if (anchorPhotoFile != null) "Custom phone photo" else "Default studio portrait",
                style = sans(13.5.sp, Cs.Ink),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (anchorPhotoFile != null) {
                CsKeyLabel(
                    label = "reset",
                    onClick = vm::clearAnchorPhoto,
                    role = KeyRole.STD,
                    radius = 10.dp,
                    size = 11.5.sp,
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("cs_reset_photo")
                )
            }
            CsKeyLabel(
                label = "choose photo",
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                role = KeyRole.ACTIVE,
                radius = 10.dp,
                size = 11.5.sp,
                modifier = Modifier
                    .height(38.dp)
                    .testTag("cs_choose_photo")
            )
        }
    }

    // 5. Custom AI Backdrop Plate & Saved Presets
    val promptState = rememberTextFieldState(initialText = draft.platePrompt)
    LaunchedEffect(draft.id, draft.platePrompt) {
        if (promptState.text.isEmpty() && draft.platePrompt.isNotBlank()) {
            promptState.setTextAndPlaceCursorAtEnd(draft.platePrompt)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("STUDIO BACKDROP PLATE", style = SectionLabelStyle, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .sunkenPanel(radius = 14.dp)
                .padding(start = 12.dp, top = 4.dp, end = 6.dp, bottom = 4.dp),
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
                            Text("Describe a studio backdrop…", style = sans(14.sp, Cs.Dim, 20.sp))
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
                role = KeyRole.ACTIVE,
                radius = 10.dp,
                size = 11.5.sp,
                modifier = Modifier
                    .height(40.dp)
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
                label = "new preset",
                selected = false,
                icon = Icons.Sharp.Add,
                onClick = {
                    val createdId = vm.createNewsroomLook("LOOK_%02d".format(looks.size + 1))
                    onSelectLook(createdId)
                },
                modifier = Modifier.testTag("cs_look_new")
            )
            CsPill(
                label = "duplicate",
                selected = false,
                onClick = {
                    val dupId = vm.duplicateNewsroomLook(draft.id)
                    if (dupId != null) onSelectLook(dupId)
                },
                modifier = Modifier.testTag("cs_look_duplicate")
            )
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
                    text = "  Tweak overlays & look with AI",
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
                text = "Change accent color, top-left bug, lighting, blur, breaking style, or chyron headline in plain English.",
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
                                "e.g. \"Cobalt accent, top-left logo RYAN LIVE, developing style, +0.4 EV\"",
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
                busyLabel = "tweaking overlays…"
            )
        }
    }
}
