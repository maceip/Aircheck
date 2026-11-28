package com.example.ui.clipstudio

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.automirrored.sharp.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.Check
import androidx.compose.material.icons.sharp.Delete
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.example.ui.design.AcCard
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcField
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcLed
import com.example.ui.design.AcPrimaryKey
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import java.io.File
import kotlin.math.pow

private const val KEY_CHYRON = "chyron"
private const val KEY_BUG = "bug"
private const val KEY_NEW = "new"
private const val OVERLAY_PREFIX = "ovl:"

/**
 * Newsroom (studio mockup, approved 2026-11-28): one screen, no tabs.
 * - Preview display takes about 60% of the height and shows the look plus every graphic on this clip;
 *   the selected graphic is outlined.
 * - One row: + new and AI 3-pack keys, then a card per graphic (chyron, corner bug, each saved
 *   overlay), each drawn as it will look, with an LED for on/off on this clip (tap the LED).
 *   Tapping a card selects it and opens an edit sheet with only that kind's fields.
 * - Bottom: backdrop key (the Backdrop screen) and done (saves the look, back to Cut).
 * Timing lives on Cut's GFX lane; renders start only from Cut.
 */
@Composable
fun NewsroomLookScreen(
    state: StudioUiState,
    vm: StudioViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onOpenBackdrop: () -> Unit,
    modifier: Modifier = Modifier,
    renderGlobalOverlays: Boolean = true
) {
    val look = state.newsroomLooks.firstOrNull { it.isDefault }
        ?: state.newsroomLooks.firstOrNull()
        ?: NewsroomLook(name = "LOOK_01", isDefault = true)
    val session = state.corePipelineSession
    val opts = session.renderOptions
    val resolvedTitle = resolvedChyronTitle(state)
    val attached = remember(state.savedOverlays, session.selectedOverlayIds) {
        val ids = session.selectedOverlayIds.toSet()
        state.savedOverlays.filter { it.id in ids }
    }
    val anchorPhotoFile = remember(state.activeProject.stageConfig.photoSha, state.settings.persona.defaultPhotoSha) {
        vm.resolveAnchorPhotoFile()
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedOverlay = selected?.takeIf { it.startsWith(OVERLAY_PREFIX) }?.removePrefix(OVERLAY_PREFIX)
        ?.let { id -> state.savedOverlays.firstOrNull { it.id == id } }
    LaunchedEffect(selected, state.savedOverlays) {
        if (selected?.startsWith(OVERLAY_PREFIX) == true && selectedOverlay == null) selected = null
    }
    BackHandler(enabled = selected != null) { selected = null }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AcTokens.Chassis)
            .testTag("cs_newsroom_root")
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val previewHeight = minOf(maxHeight * 0.6f, maxHeight - 290.dp).coerceAtLeast(200.dp)
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
                        contentDescription = "Back",
                        onClick = onBack,
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_newsroom_back")
                    )
                    Text("Newsroom", style = AcType.Title, maxLines = 1, modifier = Modifier.weight(1f))
                    AcKey(
                        icon = Icons.AutoMirrored.Sharp.Undo,
                        contentDescription = "Undo",
                        enabled = state.coreUndoDepth > 0,
                        onClick = vm::undoCoreEdit,
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_newsroom_undo")
                    )
                }

                StudioStatusLine(
                    error = state.errorMessage,
                    notice = state.statusNotice,
                    onDismissError = vm::dismissError,
                    onDismissNotice = vm::dismissNotice
                )

                NewsroomPreview(
                    look = look,
                    options = opts,
                    titleText = resolvedTitle,
                    anchorPhotoFile = anchorPhotoFile,
                    attachedOverlays = attached,
                    selectedKey = selected,
                    selectedOverlay = selectedOverlay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(previewHeight)
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 4.dp)
                        .testTag("cs_graphic_cards"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AcKey(
                            icon = Icons.Sharp.Add,
                            contentDescription = "New graphic",
                            onClick = { selected = KEY_NEW },
                            modifier = Modifier.testTag("cs_newsroom_new")
                        )
                        AcKey(
                            onClick = { vm.generateNewsroomOverlaySet() },
                            circle = true,
                            selected = state.isGeneratingOverlaySet,
                            enabled = !state.isGeneratingOverlaySet,
                            modifier = Modifier.testTag("cs_generate_overlay_set")
                        ) { fg ->
                            if (state.isGeneratingOverlaySet) {
                                ThinkingOrbSolving(size = 20.dp, tint = fg)
                            } else {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = "Generate 3 graphics with AI", tint = fg, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    GraphicCard(
                        key = KEY_CHYRON,
                        name = "Chyron",
                        kindCaption = "LOWER THIRD",
                        timingCaption = "ALL",
                        on = opts.showLowerThird,
                        onToggle = { vm.setCoreShowLowerThird(!opts.showLowerThird) },
                        onSelect = { selected = KEY_CHYRON }
                    ) { ChyronMiniature(opts.breakingStyle, look.accentHex, resolvedTitle) }
                    GraphicCard(
                        key = KEY_BUG,
                        name = "Corner bug",
                        kindCaption = "BUG",
                        timingCaption = "ALL",
                        on = opts.showBrandBug,
                        onToggle = { vm.setCoreBrandBug(show = !opts.showBrandBug) },
                        onSelect = { selected = KEY_BUG }
                    ) { BugMiniature(opts.cornerBugText, look.accentHex) }
                    state.savedOverlays.forEach { ovl ->
                        GraphicCard(
                            key = ovl.id,
                            name = overlayName(ovl.kind),
                            kindCaption = overlayKindCaption(ovl.kind),
                            timingCaption = "${ovl.durationSec}s",
                            on = ovl.id in session.selectedOverlayIds,
                            onToggle = { vm.toggleSavedOverlayForPipeline(ovl.id) },
                            onSelect = { selected = OVERLAY_PREFIX + ovl.id }
                        ) { OverlayMiniature(ovl, look.accentHex) }
                    }
                }

                StudioDock(
                    leading = {
                        AcKey(
                            label = "backdrop",
                            icon = Icons.Sharp.Wallpaper,
                            onClick = onOpenBackdrop,
                            modifier = Modifier.testTag("cs_newsroom_backdrop")
                        )
                    },
                    primaryLabel = "done",
                    primaryIcon = Icons.Sharp.Check,
                    primaryTag = "cs_use_look",
                    onPrimaryClick = {
                        vm.useLookAndReturn(look.id, draftOverride = look)
                        onDone()
                    }
                )
            }
        }

        when {
            selected == KEY_CHYRON -> ChyronEditSheet(state, vm, resolvedTitle, onClose = { selected = null })
            selected == KEY_BUG -> BugEditSheet(opts, vm, onClose = { selected = null })
            selected == KEY_NEW -> OverlayEditSheet(overlay = null, defaultStyle = opts.breakingStyle, vm = vm, onClose = { selected = null })
            selectedOverlay != null -> OverlayEditSheet(overlay = selectedOverlay, defaultStyle = opts.breakingStyle, vm = vm, onClose = { selected = null })
        }

        if (renderGlobalOverlays) {
            StudioToast(message = state.toastMessage, onDismiss = vm::dismissToast)
        }
    }
}

/** Headline the chyron shows: the edited chyron text, else the clip / project title. */
internal fun resolvedChyronTitle(state: StudioUiState): String {
    val session = state.corePipelineSession
    return session.renderOptions.chyronHeadline.trim()
        .ifBlank { session.title.trim() }
        .ifBlank { state.activeProject.stageConfig.chyronHeadline.trim() }
        .ifBlank { state.activeProject.title.trim() }
}

private fun overlayName(kind: OverlayObjectKind) = when (kind) {
    OverlayObjectKind.QUOTE_BRIEF -> "Quote"
    OverlayObjectKind.STAT_CALLOUT -> "Stat"
    OverlayObjectKind.LOWER_THIRD -> "Lower third"
}

private fun overlayKindCaption(kind: OverlayObjectKind) = when (kind) {
    OverlayObjectKind.QUOTE_BRIEF -> "CARD"
    OverlayObjectKind.STAT_CALLOUT -> "CALLOUT"
    OverlayObjectKind.LOWER_THIRD -> "LOWER THIRD"
}

@Composable
private fun GraphicCard(
    key: String,
    name: String,
    kindCaption: String,
    timingCaption: String,
    on: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    miniature: @Composable BoxScope.() -> Unit
) {
    AcCard(
        onClick = onSelect,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        modifier = Modifier
            .width(156.dp)
            .testTag("cs_graphic_card_$key")
    ) {
        Box(Modifier.fillMaxWidth().height(72.dp)) {
            AcDisplay(
                radius = 10.dp,
                contentAlignment = Alignment.BottomStart,
                modifier = Modifier.fillMaxSize(),
                content = miniature
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(36.dp)
                    .clickable(role = Role.Switch, onClick = onToggle)
                    .semantics {
                        contentDescription = "$name on this clip"
                        stateDescription = if (on) "on" else "off"
                    }
                    .testTag("cs_graphic_led_$key"),
                contentAlignment = Alignment.Center
            ) {
                AcLed(on = on)
            }
        }
        Text(name, style = AcType.BodySmall.copy(color = AcTokens.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(kindCaption, style = AcType.Caption, maxLines = 1)
            Text(timingCaption, style = AcType.Caption, maxLines = 1)
        }
    }
}

private val MiniCaption = AcType.Caption.copy(fontSize = 7.sp, letterSpacing = 0.sp)
private val MiniText
    get() = AcType.BodySmall.copy(fontSize = 8.5.sp, lineHeight = 10.sp)

@Composable
private fun kickerColors(style: BreakingNewsStyle, accentHex: String): Pair<Color, Color> {
    val accent = parseAccentHex(accentHex)
    val luma = 0.2126f * accent.red + 0.7152f * accent.green + 0.0722f * accent.blue
    val accentFg = if (luma >= 0.6f) AcTokens.Display else AcTokens.DisplayInk
    return when (style) {
        BreakingNewsStyle.BREAKING, BreakingNewsStyle.ALERT_STAT -> accent to accentFg
        BreakingNewsStyle.DEVELOPING -> AcTokens.OpOrange to AcTokens.Display
        BreakingNewsStyle.EXCLUSIVE -> AcTokens.DisplayInk to AcTokens.Display
        BreakingNewsStyle.CLEAN -> Color.Transparent to AcTokens.DisplayInk
    }
}

@Composable
private fun BoxScope.ChyronMiniature(style: BreakingNewsStyle, accentHex: String, headline: String, subline: String = "") {
    val (kBg, kFg) = kickerColors(style, accentHex)
    Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
        if (style.bannerText.isNotBlank()) {
            Text(style.bannerText, style = MiniCaption.copy(color = kFg), maxLines = 1, modifier = Modifier.background(kBg).padding(horizontal = 3.dp, vertical = 1.dp))
        }
        Text(
            headline.ifBlank { "Headline" },
            style = MiniText.copy(color = AcTokens.Display),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(AcTokens.DisplayInk).padding(horizontal = 3.dp, vertical = 1.dp)
        )
        if (subline.isNotBlank()) {
            Text(subline, style = MiniCaption.copy(color = AcTokens.DisplayInk), maxLines = 1, modifier = Modifier.fillMaxWidth().background(AcTokens.Display).padding(horizontal = 3.dp))
        }
    }
}

@Composable
private fun BoxScope.BugMiniature(text: String, accentHex: String) {
    Row(
        Modifier.align(Alignment.TopStart).padding(8.dp).background(AcTokens.Display).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(parseAccentHex(accentHex)))
        Text(text.ifBlank { "LIVE" }.uppercase(), style = MiniCaption.copy(color = AcTokens.DisplayInk), maxLines = 1)
    }
}

@Composable
private fun BoxScope.OverlayMiniature(ovl: SavedNewsroomOverlay, accentHex: String) {
    val accent = parseAccentHex(accentHex)
    when (ovl.kind) {
        OverlayObjectKind.LOWER_THIRD -> ChyronMiniature(ovl.breakingStyle, accentHex, ovl.headline, ovl.subline)
        OverlayObjectKind.QUOTE_BRIEF -> Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text("QUOTE", style = MiniCaption.copy(color = accent), maxLines = 1)
            Text(ovl.headline, style = MiniText.copy(color = AcTokens.DisplayInk), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        OverlayObjectKind.STAT_CALLOUT -> Column(Modifier.align(Alignment.CenterStart).padding(8.dp)) {
            Text(ovl.calloutStat.ifBlank { "—" }, style = AcType.numeral(20.sp).copy(color = AcTokens.DisplayInk), maxLines = 1)
            Text(ovl.headline.uppercase(), style = MiniCaption.copy(color = accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Backdrop plate, else presenter photo, else lit studio colour, with the look's grade and border. */
@Composable
internal fun LookBackdropLayer(look: NewsroomLook, anchorPhotoFile: File?, modifier: Modifier = Modifier) {
    val gain = 2.0.pow(look.brightnessEv).toFloat()
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
    val ambient = remember(look.light, gain) {
        val base = when (look.light) {
            NewsroomLook.LIGHT_WARM -> androidx.compose.ui.graphics.lerp(AcTokens.Display, AcTokens.OpOrange, 0.22f)
            NewsroomLook.LIGHT_COOL -> androidx.compose.ui.graphics.lerp(AcTokens.Display, AcTokens.OpBlue, 0.22f)
            else -> AcTokens.MediaKey
        }
        Color((base.red * gain).coerceIn(0f, 1f), (base.green * gain).coerceIn(0f, 1f), (base.blue * gain).coerceIn(0f, 1f), 1f)
    }
    val blurMod = if (Build.VERSION.SDK_INT >= 31 && blurDp > 0.dp) Modifier.blur(blurDp) else Modifier
    Box(modifier) {
        val plate = look.plateUrl
        when {
            !plate.isNullOrBlank() -> AsyncImage(
                model = plate,
                contentDescription = "Backdrop plate",
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(matrix),
                modifier = Modifier.fillMaxSize().then(blurMod).testTag("cs_look_plate")
            )
            anchorPhotoFile != null && anchorPhotoFile.isFile -> AsyncImage(
                model = anchorPhotoFile,
                contentDescription = "Presenter preview",
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(matrix),
                modifier = Modifier.fillMaxSize().then(blurMod)
            )
            else -> Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(ambient, AcTokens.Display), radius = 480f)))
        }
        if (look.border) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(6.dp)
                    .border(1.5.dp, AcTokens.DisplayInk.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            )
        }
    }
}

/** Aspect-accurate 9:16 / 16:9 frame inside an [AcDisplay]. */
@Composable
internal fun BoxScope.AspectFrame(aspect: String, content: @Composable BoxScope.() -> Unit) {
    val isVertical = aspect == CoreRenderOptions.ASPECT_VERTICAL
    if (isVertical) {
        Text(
            "9:16",
            style = AcType.Caption.copy(color = AcTokens.OpGrey),
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
        )
    }
    Box(
        modifier = if (isVertical) {
            Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, AcTokens.MediaKey, RoundedCornerShape(10.dp))
        } else {
            Modifier.fillMaxSize()
        },
        content = content
    )
}

/** Dashed outline drawn around the graphic being edited (thin line inside the display). */
private fun Modifier.selectedOutline(selected: Boolean): Modifier = if (!selected) this else drawWithContent {
    drawContent()
    drawRoundRect(
        color = AcTokens.OpOrange,
        cornerRadius = CornerRadius(4.dp.toPx()),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
    )
}

@Composable
private fun NewsroomPreview(
    look: NewsroomLook,
    options: CoreRenderOptions,
    titleText: String,
    anchorPhotoFile: File?,
    attachedOverlays: List<SavedNewsroomOverlay>,
    selectedKey: String?,
    selectedOverlay: SavedNewsroomOverlay?,
    modifier: Modifier
) {
    val accentColor = remember(look.accentHex) { parseAccentHex(look.accentHex) }
    val chyronOverlay = selectedOverlay?.takeIf { it.kind == OverlayObjectKind.LOWER_THIRD }
        ?: attachedOverlays.firstOrNull { it.kind == OverlayObjectKind.LOWER_THIRD }
    val cardOverlay = selectedOverlay?.takeIf { it.kind != OverlayObjectKind.LOWER_THIRD }
        ?: attachedOverlays.firstOrNull { it.kind != OverlayObjectKind.LOWER_THIRD }
    val showChyron = options.showLowerThird || chyronOverlay != null || selectedKey == KEY_CHYRON
    val displayTitle = chyronOverlay?.headline?.ifBlank { null } ?: titleText.ifBlank { "Untitled clip" }
    val displaySubline = chyronOverlay?.subline?.ifBlank { null } ?: options.chyronSubline.trim()
    val breakingStyle = chyronOverlay?.breakingStyle ?: options.breakingStyle
    val (kickerBg, kickerFg) = kickerColors(breakingStyle, look.accentHex)
    val isVertical = options.aspect == CoreRenderOptions.ASPECT_VERTICAL
    val chyronSelected = selectedKey == KEY_CHYRON || (selectedOverlay != null && selectedOverlay.id == chyronOverlay?.id)

    AcDisplay(modifier = modifier.testTag("cs_look_canvas"), contentAlignment = Alignment.Center) {
        AspectFrame(options.aspect) {
            LookBackdropLayer(look, anchorPhotoFile, Modifier.fillMaxSize())

            if (options.showBrandBug || selectedKey == KEY_BUG) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp)
                        .selectedOutline(selectedKey == KEY_BUG)
                        .background(AcTokens.Display.copy(alpha = 0.82f))
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                        .testTag("cs_preview_logo_bug"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(accentColor))
                    Text(options.cornerBugText.ifBlank { "AIRCHECK" }, style = AcType.Step.copy(color = AcTokens.DisplayInk, fontSize = 9.5.sp), maxLines = 1)
                }
            }

            if (cardOverlay != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = 8.dp)
                        .width(if (isVertical) 96.dp else 160.dp)
                        .selectedOutline(selectedOverlay?.id == cardOverlay.id)
                        .background(AcTokens.Display.copy(alpha = 0.82f))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("cs_preview_card_slot"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = (if (cardOverlay.kind == OverlayObjectKind.QUOTE_BRIEF) "QUOTE" else cardOverlay.calloutStat.ifBlank { cardOverlay.kind.label }).uppercase(),
                        style = AcType.Caption.copy(color = accentColor, fontSize = 7.5.sp, letterSpacing = 0.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(cardOverlay.headline, style = AcType.BodySmall.copy(color = AcTokens.DisplayInk, fontSize = 9.5.sp, lineHeight = 12.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }

            if (showChyron) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, end = 8.dp, bottom = 10.dp)
                        .fillMaxWidth()
                        .selectedOutline(chyronSelected)
                        .testTag("cs_zone_headline")
                ) {
                    if (breakingStyle.bannerText.isNotBlank()) {
                        Text(
                            breakingStyle.bannerText,
                            style = AcType.Caption.copy(color = kickerFg, fontSize = 8.sp, letterSpacing = 0.sp),
                            maxLines = 1,
                            modifier = Modifier
                                .background(kickerBg)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("cs_canvas_breaking_kicker")
                        )
                    }
                    Text(
                        displayTitle,
                        style = AcType.BodySmall.copy(color = AcTokens.Display, fontSize = if (isVertical) 11.sp else 14.sp, lineHeight = if (isVertical) 14.sp else 18.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().background(AcTokens.DisplayInk).padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                    if (displaySubline.isNotBlank()) {
                        Text(
                            displaySubline,
                            style = AcType.Caption.copy(color = AcTokens.DisplayInk, fontSize = if (isVertical) 7.5.sp else 10.sp, letterSpacing = 0.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().background(AcTokens.Display).padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------- edit sheets

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChyronEditSheet(state: StudioUiState, vm: StudioViewModel, currentTitle: String, onClose: () -> Unit) {
    val opts = state.corePipelineSession.renderOptions
    var headline by rememberSaveable { mutableStateOf(currentTitle) }
    var subline by rememberSaveable { mutableStateOf(opts.chyronSubline) }
    var style by rememberSaveable { mutableStateOf(opts.breakingStyle) }
    // An AI polish rewrites the saved headline; show the new text.
    LaunchedEffect(opts.chyronHeadline) {
        if (opts.chyronHeadline.isNotBlank() && opts.chyronHeadline != headline) headline = opts.chyronHeadline
    }
    StudioSheet(title = "Chyron", onDismiss = onClose, closeTag = "cs_graphic_sheet_close", closeDescription = "Close", modifier = Modifier.testTag("cs_graphic_sheet_chyron")) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AcField(
                value = headline,
                onValueChange = { headline = it },
                placeholder = "Headline on screen…",
                maxLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("cs_title_input"),
                trailing = {
                    AcKey(
                        onClick = {
                            if (headline.trim() != currentTitle.trim()) vm.updateTitleOnScreen(headline)
                            vm.refreshHeadlineWithAi()
                        },
                        circle = true,
                        size = AcKeySize.Small,
                        selected = state.isRefreshingHeadline,
                        enabled = !state.isRefreshingHeadline,
                        modifier = Modifier.testTag("cs_title_refresh")
                    ) { fg ->
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = "Polish headline with AI", tint = fg, modifier = Modifier.size(18.dp))
                    }
                }
            )
            AcField(
                value = subline,
                onValueChange = { subline = it },
                placeholder = "Subline or source (optional)…",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("cs_subline_input")
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BreakingNewsStyle.entries.forEach { st ->
                    AcKey(
                        label = st.chipLabel,
                        selected = style == st,
                        onClick = { style = st },
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_tag_${st.wire}")
                    )
                }
            }
            AcPrimaryKey(
                label = "save",
                onClick = {
                    if (headline.trim() != currentTitle.trim()) vm.updateTitleOnScreen(headline)
                    if (subline.trim() != opts.chyronSubline.trim()) vm.updateChyronSubline(subline)
                    if (style != opts.breakingStyle) vm.setCoreBreakingStyle(style)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().testTag("cs_graphic_save")
            )
        }
    }
}

@Composable
private fun BugEditSheet(opts: CoreRenderOptions, vm: StudioViewModel, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(opts.cornerBugText) }
    StudioSheet(title = "Corner bug", onDismiss = onClose, closeTag = "cs_graphic_sheet_close", closeDescription = "Close", modifier = Modifier.testTag("cs_graphic_sheet_bug")) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AcField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Channel label (e.g. AIRCHECK, LIVE)…",
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("cs_bug_input")
            )
            AcPrimaryKey(
                label = "save",
                enabled = text.isNotBlank(),
                onClick = {
                    vm.setCoreBrandBug(show = opts.showBrandBug, text = text)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().testTag("cs_graphic_save")
            )
        }
    }
}

/** Edits a saved overlay ([overlay] non-null) or creates and attaches a new one. */
@Composable
private fun OverlayEditSheet(overlay: SavedNewsroomOverlay?, defaultStyle: BreakingNewsStyle, vm: StudioViewModel, onClose: () -> Unit) {
    val sheetKey = overlay?.id ?: KEY_NEW
    var kind by rememberSaveable(sheetKey) { mutableStateOf(overlay?.kind ?: OverlayObjectKind.QUOTE_BRIEF) }
    var text by rememberSaveable(sheetKey) { mutableStateOf(overlay?.headline.orEmpty()) }
    var subline by rememberSaveable(sheetKey) { mutableStateOf(overlay?.subline.orEmpty()) }
    var number by rememberSaveable(sheetKey) { mutableStateOf(overlay?.calloutStat.orEmpty()) }
    var seconds by rememberSaveable(sheetKey) { mutableIntStateOf(overlay?.durationSec ?: 5) }
    StudioSheet(
        title = if (overlay == null) "New graphic" else overlayName(overlay.kind),
        onDismiss = onClose,
        closeTag = "cs_graphic_sheet_close",
        closeDescription = "Close",
        modifier = Modifier.testTag("cs_graphic_sheet_overlay")
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (overlay == null) {
                    listOf(OverlayObjectKind.QUOTE_BRIEF, OverlayObjectKind.STAT_CALLOUT, OverlayObjectKind.LOWER_THIRD).forEach { k ->
                        AcKey(
                            label = overlayName(k).lowercase(),
                            selected = kind == k,
                            onClick = { kind = k },
                            size = AcKeySize.Small,
                            modifier = Modifier.testTag("cs_new_ovl_kind_${k.name}")
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (overlay != null) {
                    AcKey(
                        icon = Icons.Sharp.Delete,
                        contentDescription = "Delete this graphic",
                        onClick = {
                            vm.deleteSavedNewsroomOverlay(overlay.id)
                            onClose()
                        },
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_graphic_delete")
                    )
                }
            }
            AcField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Text…",
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("cs_overlay_text")
            )
            if (kind == OverlayObjectKind.LOWER_THIRD) {
                AcField(
                    value = subline,
                    onValueChange = { subline = it },
                    placeholder = "Subline…",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("cs_overlay_subline")
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AcField(
                    value = number,
                    onValueChange = { number = it },
                    placeholder = "Number (e.g. 42%)",
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("cs_overlay_number")
                )
                listOf(3, 5, 8).forEach { sec ->
                    AcKey(
                        label = "${sec}s",
                        selected = seconds == sec,
                        onClick = { seconds = sec },
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_overlay_secs_$sec")
                    )
                }
            }
            AcPrimaryKey(
                label = "save",
                enabled = text.isNotBlank(),
                onClick = {
                    if (overlay == null) {
                        vm.createAndAttachOverlay(
                            kind = kind,
                            headline = text,
                            subline = subline,
                            calloutStat = number,
                            durationSec = seconds,
                            breakingStyle = defaultStyle,
                            attachToCut = true
                        )
                    } else {
                        vm.updateSavedOverlayInline(
                            overlayId = overlay.id,
                            headline = text,
                            subline = subline,
                            calloutStat = number,
                            durationSec = seconds
                        )
                    }
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().testTag("cs_graphic_save")
            )
        }
    }
}
