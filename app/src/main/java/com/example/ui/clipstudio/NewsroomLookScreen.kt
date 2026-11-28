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
import com.example.ui.design.AcCard
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcField
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcMediaKey
import com.example.ui.design.AcMediaWell
import com.example.ui.design.AcPrimaryKey
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType

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
            .background(AcTokens.Chassis)
            .testTag("cs_newsroom_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(start = 8.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AcKey(
                    icon = Icons.AutoMirrored.Sharp.ArrowBack,
                    contentDescription = "Back to Cut",
                    onClick = onBack,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_newsroom_back")
                )
                Text(
                    text = "Newsroom Overlays",
                    style = AcType.Title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AcKey(
                    icon = Icons.AutoMirrored.Sharp.Undo,
                    contentDescription = "Undo overlay change",
                    enabled = state.coreUndoDepth > 0,
                    onClick = vm::undoCoreEdit,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_newsroom_undo")
                )
                AcKey(
                    label = "save",
                    onClick = {
                        vm.useLookAndReturn(draft.id, draftOverride = draft)
                        onBack()
                    },
                    enabled = !isLookBusy,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_save_look_only")
                )
            }

            StudioStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 8.dp, end = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Triple(0, "Labels", Icons.Sharp.Title),
                    Triple(1, "Overlays (${attachedOverlays.size})", Icons.Sharp.FormatQuote),
                    Triple(2, "Look", Icons.Sharp.Wallpaper)
                ).forEach { (idx, label, icon) ->
                    AcKey(
                        label = label,
                        icon = icon,
                        selected = tab == idx,
                        onClick = { tab = idx },
                        size = AcKeySize.Small,
                        role = Role.Tab,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_newsroom_tab_$idx")
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 10.dp),
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
                        attachedOverlays = attachedOverlays
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

            val canRenderNow = session.hasTalkingHead && !state.isCoreStageBusy
            StudioDock(
                leading = {
                    AcKey(
                        label = "Tweak AI",
                        icon = Icons.Outlined.AutoAwesome,
                        enabled = !isLookBusy,
                        onClick = {
                            vm.dismissToast()
                            aiTweakSheetOpen = true
                        },
                        modifier = Modifier.testTag("cs_tweak_ai_button")
                    )
                },
                primaryLabel = if (canRenderNow) "apply & render" else "save overlays",
                primaryIcon = if (canRenderNow) Icons.Sharp.Movie else Icons.Sharp.Check,
                primaryEnabled = !isLookBusy && !state.isCoreStageBusy,
                primaryTag = "cs_use_look",
                onPrimaryClick = {
                    vm.useLookAndReturn(draft.id, draftOverride = draft)
                    if (canRenderNow) onTestOnClip(draft.id) else onBack()
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
            StudioToast(message = state.toastMessage, onDismiss = vm::dismissToast)
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
            NewsroomLook.LIGHT_WARM -> androidx.compose.ui.graphics.lerp(AcTokens.Display, AcTokens.OpOrange, 0.22f)
            NewsroomLook.LIGHT_COOL -> androidx.compose.ui.graphics.lerp(AcTokens.Display, AcTokens.OpBlue, 0.22f)
            else -> AcTokens.MediaKey
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
    val accentFg = if (accentLuma >= 0.6f) AcTokens.Display else AcTokens.Ui
    val (kickerBg, kickerFg) = when (breakingStyle) {
        BreakingNewsStyle.BREAKING, BreakingNewsStyle.ALERT_STAT -> accentColor to accentFg
        BreakingNewsStyle.DEVELOPING -> AcTokens.OpOrange to AcTokens.Display
        BreakingNewsStyle.EXCLUSIVE -> AcTokens.Ui to AcTokens.Display
        BreakingNewsStyle.CLEAN -> Color.Transparent to AcTokens.Ui
    }

    val isVertical = options.aspect == CoreRenderOptions.ASPECT_VERTICAL

    AcDisplay(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(194.dp)
            .testTag("cs_look_canvas"),
        contentAlignment = Alignment.Center
    ) {
        // Aspect-ratio preview badge top-right of outer stage when vertical
        if (isVertical) {
            Text(
                text = "9:16",
                style = AcType.Caption.copy(color = AcTokens.OpGrey),
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
                .border(1.dp, AcTokens.MediaKey, RoundedCornerShape(10.dp))
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
                                colors = listOf(ambientCenterColor, AcTokens.Display),
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
                        .border(1.5.dp, AcTokens.Ui.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                )
            }

            // Top-left Corner Bug
            if (options.showBrandBug) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(AcTokens.Display.copy(alpha = 0.82f))
                        .border(
                            width = if (activeTab == 0) 1.dp else 0.5.dp,
                            color = if (activeTab == 0) AcTokens.Ui else AcTokens.Ui.copy(alpha = 0.25f),
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
                        style = AcType.Step.copy(color = AcTokens.Ui, fontSize = 9.5.sp),
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
                        .background(AcTokens.Display.copy(alpha = 0.82f))
                        .border(1.dp, accentColor, RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClick = onTapCard)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("cs_preview_card_slot"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = cardOverlay.calloutStat.ifBlank { cardOverlay.kind.label }.uppercase(),
                        style = AcType.Caption.copy(color = accentColor, fontSize = 7.5.sp, letterSpacing = 0.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = cardOverlay.headline,
                        style = AcType.BodySmall.copy(color = AcTokens.Ui, fontSize = 9.5.sp, lineHeight = 12.sp),
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
                                style = AcType.Caption.copy(color = kickerFg, fontSize = 8.sp, letterSpacing = 0.sp),
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
                            .background(AcTokens.Display.copy(alpha = 0.86f))
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
                            style = AcType.BodySmall.copy(color = AcTokens.Ui, fontSize = if (isVertical) 10.sp else 13.5.sp, lineHeight = if (isVertical) 13.sp else 17.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (displaySubline.isNotBlank()) {
                            Text(
                                text = displaySubline,
                                style = AcType.Caption.copy(color = accentColor, fontSize = if (isVertical) 7.5.sp else 10.sp, letterSpacing = 0.sp),
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

    AcCard(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("LOWER-THIRD CHYRON", style = AcType.Caption)
            AcKey(
                label = if (options.showLowerThird) "ON" else "OFF",
                onClick = { vm.setCoreShowLowerThird(!options.showLowerThird) },
                selected = options.showLowerThird,
                size = AcKeySize.Small,
                modifier = Modifier
                    .testTag("cs_chyron_toggle")
            )
        }

        if (options.showLowerThird) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AcField(
                    state = titleFieldState,
                    placeholder = "Headline on screen…",
                    minLines = 1,
                    maxLines = 2,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_title_input")
                )
                AcKey(
                    icon = Icons.Outlined.AutoAwesome,
                    contentDescription = "Polish headline with AI",
                    onClick = vm::refreshHeadlineWithAi,
                    selected = state.isRefreshingHeadline,
                    enabled = !state.isRefreshingHeadline,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_title_refresh")
                )
            }

            AcField(
                state = sublineFieldState,
                placeholder = "Subline or source label (optional)…",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cs_subline_input")
            )

            Text("KICKER BADGE STYLE", style = AcType.Caption)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BreakingNewsStyle.entries.forEach { style ->
                    val selected = breakingStyle == style
                    AcKey(
                        label = style.chipLabel,
                        onClick = { vm.setCoreBreakingStyle(style) },
                        selected = selected,
                        size = AcKeySize.Small,
                        modifier = Modifier
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

    AcCard(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("TOP-LEFT CORNER BUG", style = AcType.Caption)
            AcKey(
                label = if (options.showBrandBug) "ON" else "OFF",
                onClick = {
                    vm.setCoreBrandBug(
                        show = !options.showBrandBug,
                        text = bugFieldState.text.toString()
                    )
                },
                selected = options.showBrandBug,
                size = AcKeySize.Small,
                modifier = Modifier
                    .testTag("cs_bug_toggle")
            )
        }
        AcField(
            state = bugFieldState,
            placeholder = "Channel label (e.g. AIRCHECK, LIVE)…",
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cs_bug_input")
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlaysManagerTab(
    state: StudioUiState,
    vm: StudioViewModel,
    attachedOverlays: List<SavedNewsroomOverlay>
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
        AcKey(
            onClick = { creatingNew = !creatingNew },
            selected = creatingNew,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            size = AcKeySize.Small,
            modifier = Modifier
                .weight(1f)
                .testTag("cs_new_overlay_toggle")
        ) { tint ->
            Icon(Icons.Sharp.Add, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Text("new overlay", style = AcType.Label.copy(color = tint), maxLines = 1)
        }
        AcKey(
            onClick = { vm.generateNewsroomOverlaySet() },
            enabled = !state.isGeneratingOverlaySet,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            size = AcKeySize.Small,
            modifier = Modifier
                .weight(1f)
                .testTag("cs_generate_overlay_set")
        ) { tint ->
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            Text(
                text = if (state.isGeneratingOverlaySet) "generating…" else "AI 3-pack",
                style = AcType.Label.copy(color = tint),
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
            Text("OVERLAYS ON THIS CLIP", style = AcType.Caption)
            Text(
                text = "${attachedOverlays.size} active",
                style = AcType.Caption.copy(color = if (attachedOverlays.isNotEmpty()) AcTokens.Red else AcTokens.Ink2)
            )
        }

        if (allOverlays.isEmpty()) {
            AcCard(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("No saved overlays yet.", style = AcType.BodySmall.copy(color = AcTokens.Ink))
                Text(
                    text = "Tap 'new overlay' to build a custom lower-third, stat card, or quote card, or generate a 3-pack with AI.",
                    style = AcType.BodySmall.copy(color = AcTokens.Ink2)
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
                    AcCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { vm.toggleSavedOverlayForPipeline(ovl.id) }
                            .testTag("cs_overlay_${ovl.id}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (attached) AcTokens.Red else AcTokens.Chassis),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (attached) Icons.Sharp.Check else Icons.Sharp.Add,
                                    contentDescription = if (attached) "Attached" else "Attach",
                                    tint = if (attached) AcTokens.Ui else AcTokens.Ink2,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = ovl.headline,
                                    style = AcType.BodySmall.copy(color = AcTokens.Ink),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${ovl.kind.label} · ${ovl.durationSec}s",
                                    style = AcType.Caption.copy(color = AcTokens.Ink2),
                                    maxLines = 1
                                )
                            }
                            AcKey(
                                icon = Icons.Sharp.Edit,
                                contentDescription = "Edit overlay",
                                onClick = { editingId = ovl.id },
                                size = AcKeySize.Small,
                                modifier = Modifier.testTag("cs_overlay_edit_${ovl.id}")
                            )
                            AcKey(
                                icon = Icons.Sharp.Delete,
                                contentDescription = "Delete overlay",
                                onClick = { vm.deleteSavedNewsroomOverlay(ovl.id) },
                                size = AcKeySize.Small,
                                modifier = Modifier.testTag("cs_overlay_delete_${ovl.id}")
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

    AcCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cs_new_overlay_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("CREATE NEW OVERLAY", style = AcType.Caption)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OverlayObjectKind.entries.forEach { k ->
                AcKey(
                    label = k.label,
                    onClick = { kind = k },
                    selected = kind == k,
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_new_ovl_kind_${k.name}")
                )
            }
        }

        AcField(
            state = headlineState,
            placeholder = "Headline text…",
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cs_new_ovl_headline")
        )

        AcField(
            state = sublineState,
            placeholder = "Subline / caption (optional)…",
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cs_new_ovl_subline")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AcField(
                state = statState,
                placeholder = "Stat / badge (e.g. 42%)…",
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_new_ovl_stat")
            )
            listOf(3, 5, 8).forEach { sec ->
                AcKey(
                    label = "${sec}s",
                    onClick = { durationSec = sec },
                    selected = durationSec == sec,
                    size = AcKeySize.Small
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BreakingNewsStyle.entries.forEach { st ->
                AcKey(
                    label = st.chipLabel,
                    onClick = { style = st },
                    selected = style == st,
                    size = AcKeySize.Small
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AcKey(
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
                size = AcKeySize.Small,
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_new_ovl_create")
            )
            AcKey(
                label = "cancel",
                onClick = onCancel,
                size = AcKeySize.Small
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

    AcCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cs_card_inline_editor_${overlay.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("EDIT OVERLAY", style = AcType.Caption)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OverlayObjectKind.entries.forEach { k ->
                AcKey(
                    label = k.label,
                    onClick = { kind = k },
                    selected = kind == k,
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .weight(1f)
                )
            }
        }
        AcField(
            state = headState,
            minLines = 1,
            maxLines = 2,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cs_card_headline_field_${overlay.id}")
        )
        AcField(
            state = subState,
            placeholder = "Subline…",
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AcField(
                state = statState,
                placeholder = "Stat / badge…",
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
            )
            listOf(3, 5, 8).forEach { sec ->
                AcKey(
                    label = "${sec}s",
                    onClick = { durationSec = sec },
                    selected = durationSec == sec,
                    size = AcKeySize.Small
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BreakingNewsStyle.entries.forEach { st ->
                AcKey(
                    label = st.chipLabel,
                    onClick = { style = st },
                    selected = style == st,
                    size = AcKeySize.Small
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AcKey(
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
                size = AcKeySize.Small,
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_card_save_${overlay.id}")
            )
            AcKey(
                label = "cancel",
                onClick = onCancel,
                size = AcKeySize.Small,
                modifier = Modifier
                    .testTag("cs_card_cancel_${overlay.id}")
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
        Text("ACCENT COLOR", style = AcType.Caption, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Swatches show the overlay's real accent hex (rendered into the video), not UI colors.
            val accents = listOf(
                "crimson" to NewsroomLook.ACCENT_CRIMSON,
                "amber" to NewsroomLook.ACCENT_AMBER,
                "cobalt" to NewsroomLook.ACCENT_COBALT,
                "mint" to NewsroomLook.ACCENT_MINT
            )
            accents.forEach { (name, hex) ->
                val color = parseAccentHex(hex)
                val selected = draft.accentHex.equals(hex, ignoreCase = true)
                AcKey(
                    onClick = { onUpdateDraft(draft.copy(accentHex = hex)) },
                    selected = selected,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_accent_$name")
                ) { tint ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Text(name, style = AcType.Caption.copy(color = tint), maxLines = 1)
                }
            }
        }
    }

    // 2. Studio Lighting + Film Grain / Frame Border
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LIGHTING & FINISH", style = AcType.Caption, modifier = Modifier.padding(horizontal = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                NewsroomLook.LIGHT_WARM to "warm",
                NewsroomLook.LIGHT_NEUTRAL to "neutral",
                NewsroomLook.LIGHT_COOL to "cool"
            ).forEach { (wire, label) ->
                AcKey(
                    label = label,
                    onClick = { onUpdateDraft(draft.copy(light = wire)) },
                    selected = draft.light == wire,
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_light_$label")
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AcKey(
                label = if (draft.grain) "film grain: ON" else "film grain: OFF",
                onClick = { onUpdateDraft(draft.copy(grain = !draft.grain)) },
                selected = draft.grain,
                size = AcKeySize.Small,
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_look_grain")
            )
            AcKey(
                label = if (draft.border) "frame border: ON" else "frame border: OFF",
                onClick = { onUpdateDraft(draft.copy(border = !draft.border)) },
                selected = draft.border,
                size = AcKeySize.Small,
                modifier = Modifier
                    .weight(1f)
                    .testTag("cs_look_border")
            )
        }
    }

    // 3. Blur & Brightness Rulers
    StudioTickRuler(
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
    StudioTickRuler(
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
        Text("PRESENTER PHOTO", style = AcType.Caption, modifier = Modifier.padding(horizontal = 4.dp))
        AcCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AcTokens.Chassis),
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
                        Icon(Icons.Sharp.PhotoCamera, contentDescription = null, tint = AcTokens.Ink2, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    text = if (anchorPhotoFile != null) "Custom phone photo" else "Default studio portrait",
                    style = AcType.BodySmall.copy(color = AcTokens.Ink),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (anchorPhotoFile != null) {
                    AcKey(
                        label = "reset",
                        onClick = vm::clearAnchorPhoto,
                        size = AcKeySize.Small,
                        modifier = Modifier
                            .testTag("cs_reset_photo")
                    )
                }
                AcKey(
                    label = "choose photo",
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .testTag("cs_choose_photo")
                )
            }
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
        Text("STUDIO BACKDROP PLATE", style = AcType.Caption, modifier = Modifier.padding(horizontal = 4.dp))
        AcCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AcField(
                    state = promptState,
                    placeholder = "Describe a studio backdrop…",
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_backdrop_prompt")
                )
                AcKey(
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
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .testTag("cs_backdrop_generate")
                )
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            looks.forEach { item ->
                AcKey(
                    label = item.name,
                    selected = item.id == draft.id,
                    onClick = { onSelectLook(item.id) },
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_look_tile_${item.id}")
                )
            }
            AcKey(
                label = "new preset",
                selected = false,
                icon = Icons.Sharp.Add,
                onClick = {
                    val createdId = vm.createNewsroomLook("LOOK_%02d".format(looks.size + 1))
                    onSelectLook(createdId)
                },
                size = AcKeySize.Small,
                modifier = Modifier.testTag("cs_look_new")
            )
            AcKey(
                label = "duplicate",
                selected = false,
                onClick = {
                    val dupId = vm.duplicateNewsroomLook(draft.id)
                    if (dupId != null) onSelectLook(dupId)
                },
                size = AcKeySize.Small,
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
                .background(AcTokens.Ink.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(AcTokens.Chassis)
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
                    tint = AcTokens.Ink,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "  Tweak overlays & look with AI",
                    style = AcType.Title.copy(color = AcTokens.Ink),
                    modifier = Modifier.weight(1f)
                )
                AcKey(
                    icon = Icons.Sharp.Close,
                    contentDescription = "Close AI tweak sheet",
                    onClick = onDismiss,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_ai_tweak_close")
                )
            }
            Text(
                text = "Change accent color, top-left bug, lighting, blur, breaking style, or chyron headline in plain English.",
                style = AcType.BodySmall.copy(color = AcTokens.Ink2)
            )
            AcField(
                state = fieldState,
                placeholder = "e.g. \"Cobalt accent, top-left logo RYAN LIVE, developing style, +0.4 EV\"",
                minLines = 3,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cs_ai_tweak_input")
            )
            StudioDock(
                primaryLabel = "apply AI tweak",
                primaryIcon = Icons.Outlined.AutoAwesome,
                primaryEnabled = fieldState.text.toString().isNotBlank() && !isBusy,
                primaryTag = "cs_ai_tweak_submit",
                onPrimaryClick = { onSubmit(fieldState.text.toString()) },
                isBusy = isBusy,
                busyLabel = "tweaking overlays…"
            )
        }
    }
}
