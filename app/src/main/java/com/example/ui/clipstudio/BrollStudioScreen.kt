package com.example.ui.clipstudio

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.automirrored.sharp.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.sharp.Close
import androidx.compose.material.icons.sharp.Movie
import androidx.compose.material.icons.sharp.Person
import androidx.compose.material.icons.sharp.PhotoLibrary
import androidx.compose.material.icons.sharp.RecordVoiceOver
import androidx.compose.material.icons.sharp.SwapHoriz
import androidx.compose.material.icons.sharp.Sync
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.data.BrollSegmentMode
import com.example.data.ClipTransitionType
import com.example.data.CoreRenderOptions
import com.example.data.CoreStoryClause
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import java.io.File

/**
 * Dedicated full-view B-Roll & Transition Studio:
 * - Configure global default B-roll layout (`Corner PiP` / `Full Screen` / `Split 50/50`) and default transition.
 * - Inspect & edit every clause cut:
 *   - Change transition type (`Hard Cut`, `Crossfade`, `Wipe Left`, `Wipe Right`, `Slide Up`, `Dissolve`, `Dip Black`)
 *     and duration (`200ms` / `350ms` / `600ms`) entering each cut.
 *   - Toggle Talking Head vs B-Roll Cutaway and optional per-cut framing override.
 *   - Edit the B-roll visual prompt, generate a real B-roll still on the Mac rig (non-blocking per cut),
 *     or attach a real photo/video clip directly from phone storage.
 * - Trigger video generation directly from the studio dock.
 */
@Composable
fun BrollStudioScreen(
    state: StudioUiState,
    vm: StudioViewModel,
    onBack: () -> Unit,
    onRenderStarted: () -> Unit = onBack,
    modifier: Modifier = Modifier,
    renderGlobalOverlays: Boolean = true
) {
    val session = state.corePipelineSession
    val opts = session.renderOptions
    val defaultTransition = ClipTransitionType.fromWire(opts.defaultTransition)
    val brollCount = session.clauses.count { it.segmentMode == BrollSegmentMode.BROLL_CUTAWAY }
    val unsynced = session.unsyncedClauseIndices

    var expandedTransitionClause by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingMediaClauseIdx by rememberSaveable { mutableStateOf<Int?>(null) }

    BackHandler(enabled = expandedTransitionClause != null) { expandedTransitionClause = null }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val idx = pendingMediaClauseIdx
        pendingMediaClauseIdx = null
        if (uri != null && idx != null) {
            vm.importCustomBrollMediaForClause(idx, uri)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Cs.Body)
            .testTag("cs_broll_studio_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            // 52dp Header
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
                    modifier = Modifier.testTag("cs_broll_studio_back")
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "B-Roll & Transitions",
                        style = sans(17.sp, Cs.Ink, weight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$brollCount cutaway${if (brollCount == 1) "" else "s"} · ${session.clauses.size} scene${if (session.clauses.size == 1) "" else "s"}",
                        style = mono(11.sp, Cs.Muted),
                        maxLines = 1
                    )
                }
                CsIconKey(
                    icon = Icons.AutoMirrored.Sharp.Undo,
                    contentDescription = "Undo B-roll change",
                    enabled = state.coreUndoDepth > 0,
                    onClick = vm::undoCoreEdit,
                    size = 40.dp,
                    radius = 11.dp,
                    iconSize = 20.dp,
                    modifier = Modifier.testTag("cs_broll_studio_undo")
                )
            }

            CsStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("cs_broll_studio_list"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Global Layout & Transition Defaults Card
                item(key = "broll_global_defaults") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sunkenPanel(radius = 16.dp)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("DEFAULT CUTAWAY LAYOUT", style = SectionLabelStyle)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                CoreRenderOptions.BROLL_FRAMING_PIP to "Corner PiP",
                                CoreRenderOptions.BROLL_FRAMING_FULL to "Full Screen",
                                CoreRenderOptions.BROLL_FRAMING_SPLIT to "Split 50/50"
                            ).forEach { (wire, label) ->
                                CsKeyLabel(
                                    label = label,
                                    onClick = { vm.setCoreBrollFraming(wire) },
                                    role = if (opts.brollFraming == wire) KeyRole.ACTIVE else KeyRole.STD,
                                    radius = 10.dp,
                                    size = 11.5.sp,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .testTag("cs_broll_framing_$wire")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("DEFAULT TRANSITION", style = SectionLabelStyle)
                            CsKeyLabel(
                                label = "apply to all",
                                onClick = { vm.applyTransitionToAllCuts(defaultTransition) },
                                enabled = session.clauses.isNotEmpty(),
                                role = KeyRole.STD,
                                radius = 8.dp,
                                size = 11.sp,
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("cs_apply_all_transitions")
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ClipTransitionType.entries.forEach { t ->
                                CsPill(
                                    label = t.label,
                                    selected = defaultTransition == t,
                                    onClick = { vm.setCoreDefaultTransition(t) },
                                    modifier = Modifier.testTag("cs_default_trans_${t.wire}")
                                )
                            }
                        }
                    }
                }

                if (session.clauses.isEmpty()) {
                    item(key = "broll_empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sunkenPanel(radius = 16.dp)
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("No scenes in this clip yet.", style = sans(15.5.sp, Cs.Ink, weight = FontWeight.Medium))
                            Text(
                                text = "Outline beats in 01 SPARK and tap make the video to build your B-roll timeline.",
                                style = sans(13.5.sp, Cs.Muted, 19.sp)
                            )
                        }
                    }
                } else {
                    itemsIndexed(session.clauses, key = { _, c -> c.id }) { idx, clause ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Transition selector entering this cut
                            val clauseTrans = ClipTransitionType.fromWire(clause.transitionType)
                            val transExpanded = expandedTransitionClause == idx
                            if (idx > 0 || clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY) {
                                TransitionConnectorRow(
                                    index = idx,
                                    transition = clauseTrans,
                                    durationMs = clause.transitionDurationMs,
                                    expanded = transExpanded,
                                    onToggleExpand = {
                                        expandedTransitionClause = if (transExpanded) null else idx
                                    },
                                    onSelect = { nextTrans, durMs ->
                                        vm.setClauseTransition(idx, nextTrans, durMs)
                                    }
                                )
                            }

                            BrollSceneCard(
                                index = idx,
                                clause = clause,
                                globalFraming = opts.brollFraming,
                                isGenerating = idx in state.generatingBrollClauseIndices,
                                isUploading = idx in state.uploadingBrollClauseIndices,
                                onToggleMode = { vm.toggleCutClauseBroll(idx) },
                                onSelectFraming = { framing -> vm.setClauseBrollFraming(idx, framing) },
                                onCommitPrompt = { prompt -> vm.updateClauseBrollPrompt(idx, prompt) },
                                onGenerateStill = { prompt -> vm.generateBrollForClause(idx, prompt) },
                                onPickMedia = {
                                    pendingMediaClauseIdx = idx
                                    mediaPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                },
                                onClearMedia = { vm.clearClauseCustomBroll(idx) }
                            )
                        }
                    }
                }
            }

            // Bottom Dock: Direct Generate Video action honoring exact pipeline stage
            val (dockLabel, dockIcon, dockRole, dockEnabled, dockClick) = when {
                session.clauses.isEmpty() -> Quintuple(
                    "load a script first",
                    Icons.Sharp.Movie,
                    KeyRole.STD,
                    false,
                    {}
                )
                !session.hasTtsAudio -> Quintuple(
                    "voice script first",
                    Icons.Sharp.RecordVoiceOver,
                    KeyRole.COMMIT,
                    true,
                    {
                        vm.runCoreStage1Tts()
                        onRenderStarted()
                    }
                )
                unsynced.isNotEmpty() -> Quintuple(
                    "re-voice ${unsynced.size} edited line${if (unsynced.size == 1) "" else "s"}",
                    Icons.Sharp.Sync,
                    KeyRole.REPAIR,
                    true,
                    {
                        vm.resyncEditedVoice()
                        onRenderStarted()
                    }
                )
                !session.hasTalkingHead -> Quintuple(
                    "render talking head",
                    Icons.Sharp.Person,
                    KeyRole.COMMIT,
                    true,
                    {
                        vm.runCoreStage3SpeechToHead()
                        onRenderStarted()
                    }
                )
                else -> Quintuple(
                    "generate video",
                    Icons.Sharp.Movie,
                    KeyRole.COMMIT,
                    true,
                    {
                        vm.runCoreStage4ChopBroll()
                        onRenderStarted()
                    }
                )
            }

            CsDock(
                primaryLabel = dockLabel,
                primaryLeadingIcon = dockIcon,
                primaryRole = dockRole,
                primaryEnabled = dockEnabled && !state.isCoreStageBusy,
                primaryTag = "cs_broll_generate_video",
                onPrimaryClick = dockClick,
                isBusy = state.isCoreStageBusy,
                busyLabel = state.coreStageStatusLabel ?: "rendering…"
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

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)

@Composable
private fun TransitionConnectorRow(
    index: Int,
    transition: ClipTransitionType,
    durationMs: Int,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: (ClipTransitionType, Int) -> Unit
) {
    val active = transition != ClipTransitionType.CUT
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (expanded) Cs.Raised else Cs.SunkenTrack)
            .border(
                width = 1.dp,
                color = if (active || expanded) Cs.Cobalt.copy(alpha = 0.6f) else Color(0xFF22222A),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggleExpand)
                .testTag("cs_clause_trans_chip_$index"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Sharp.SwapHoriz,
                contentDescription = null,
                tint = if (active) Cs.Cobalt else Cs.Muted,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Transition: ${transition.label}",
                style = mono(11.5.sp, if (active) Cs.Ink else Cs.Muted, FontWeight.Medium),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (active) "${durationMs}ms" else "edit",
                style = mono(11.sp, if (active) Cs.Cobalt else Cs.Muted),
                maxLines = 1
            )
        }

        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ClipTransitionType.entries.forEach { t ->
                    CsPill(
                        label = t.label,
                        selected = transition == t,
                        onClick = { onSelect(t, durationMs) },
                        modifier = Modifier.testTag("cs_clause_trans_opt_${index}_${t.wire}")
                    )
                }
            }
            if (transition != ClipTransitionType.CUT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SPEED", style = SectionLabelStyle, modifier = Modifier.weight(1f))
                    listOf(200 to "fast 0.2s", 350 to "med 0.35s", 600 to "slow 0.6s").forEach { (ms, lbl) ->
                        CsKeyLabel(
                            label = lbl,
                            onClick = { onSelect(transition, ms) },
                            role = if (durationMs == ms) KeyRole.ACTIVE else KeyRole.STD,
                            radius = 8.dp,
                            size = 11.sp,
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("cs_clause_trans_dur_${index}_$ms")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrollSceneCard(
    index: Int,
    clause: CoreStoryClause,
    globalFraming: String,
    isGenerating: Boolean,
    isUploading: Boolean,
    onToggleMode: () -> Unit,
    onSelectFraming: (String) -> Unit,
    onCommitPrompt: (String) -> Unit,
    onGenerateStill: (String) -> Unit,
    onPickMedia: () -> Unit,
    onClearMedia: () -> Unit
) {
    val isBroll = clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
    val initialPrompt = clause.bRollPrompt.ifBlank { clause.brollCaption.ifBlank { clause.spokenText } }
    val promptState = rememberTextFieldState(initialText = initialPrompt)
    LaunchedEffect(clause.id, clause.bRollPrompt) {
        if (clause.bRollPrompt.isNotBlank() && promptState.text.toString() != clause.bRollPrompt) {
            promptState.setTextAndPlaceCursorAtEnd(clause.bRollPrompt)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sunkenPanel(radius = 16.dp)
            .border(
                width = 1.dp,
                color = if (isBroll) Cs.Mint.copy(alpha = 0.45f) else Color(0xFF23232B),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(12.dp)
            .testTag("cs_broll_card_$index"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header row: Scene time + Mode switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "SCENE ${index + 1} · ${mmss(clause.startMs)}",
                style = mono(11.sp, Cs.Muted, FontWeight.Medium),
                maxLines = 1
            )
            CsKey(
                onClick = onToggleMode,
                role = if (isBroll) KeyRole.ACTIVE else KeyRole.STD,
                radius = 10.dp,
                horizontalPadding = 10.dp,
                contentAlignment = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("cs_broll_mode_$index")
            ) { tint ->
                Icon(
                    imageVector = if (isBroll) Icons.Sharp.Movie else Icons.Sharp.Person,
                    contentDescription = null,
                    tint = if (isBroll) Cs.Mint else tint,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (isBroll) "B-Roll Cutaway" else "Talking Head",
                    style = mono(11.5.sp, if (isBroll) Cs.White else tint, FontWeight.Medium),
                    maxLines = 1
                )
            }
        }

        // Spoken script line for this scene
        Text(
            text = clause.spokenText,
            style = sans(14.5.sp, Cs.Ink, 20.sp)
        )

        if (isBroll) {
            // Live Media Preview (generated still or user-picked photo/video)
            val previewModel: Any? = remember(clause.brollPreviewUrl, clause.customBrollExt) {
                val raw = clause.brollPreviewUrl?.trim().orEmpty()
                when {
                    raw.isEmpty() -> null
                    clause.customBrollExt in setOf("mp4", "mov") -> null
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    else -> File(raw).takeIf { it.isFile }
                }
            }
            val isCustomVideo = clause.customBrollExt in setOf("mp4", "mov") && !clause.bRollSha.isNullOrBlank()
            val hasMedia = previewModel != null || isCustomVideo || !clause.brollMediaId.isNullOrBlank()

            if (previewModel != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(128.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Cs.Body)
                        .testTag("cs_broll_preview_$index")
                ) {
                    AsyncImage(
                        model = previewModel,
                        contentDescription = "B-roll preview for scene ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else if (isCustomVideo) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Cs.Track)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Sharp.Movie, contentDescription = null, tint = Cs.Mint, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Custom video clip attached (.${clause.customBrollExt})",
                        style = mono(12.sp, Cs.Ink),
                        maxLines = 1
                    )
                }
            }

            // Per-scene layout override (`Default` | `PiP` | `Full` | `Split`)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "" to "Default ($globalFraming)",
                    CoreRenderOptions.BROLL_FRAMING_PIP to "PiP",
                    CoreRenderOptions.BROLL_FRAMING_FULL to "Full",
                    CoreRenderOptions.BROLL_FRAMING_SPLIT to "Split"
                ).forEach { (wire, lbl) ->
                    val selected = clause.brollFramingOverride == wire
                    CsKeyLabel(
                        label = lbl,
                        onClick = { onSelectFraming(wire) },
                        role = if (selected) KeyRole.ACTIVE else KeyRole.STD,
                        radius = 8.dp,
                        size = 10.5.sp,
                        horizontalPadding = 6.dp,
                        modifier = Modifier
                            .weight(if (wire.isEmpty()) 1.4f else 1f)
                            .height(34.dp)
                            .testTag("cs_clause_framing_${index}_${wire.ifEmpty { "default" }}")
                    )
                }
            }

            // Editable B-roll Visual Prompt
            BasicTextField(
                state = promptState,
                textStyle = sans(13.5.sp, Cs.Ink, 19.sp),
                cursorBrush = SolidColor(Cs.Ink),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 3),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Cs.Body)
                    .border(1.dp, Cs.Hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("cs_broll_prompt_$index"),
                decorator = { inner ->
                    Box {
                        if (promptState.text.isEmpty()) {
                            Text("Describe B-roll footage to show…", style = sans(13.5.sp, Cs.Dim))
                        }
                        inner()
                    }
                }
            )

            // Action Buttons: Generate Still (AI) + Pick Photo/Video + Optional Clear
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CsKey(
                    onClick = {
                        val txt = promptState.text.toString().trim()
                        onCommitPrompt(txt)
                        onGenerateStill(txt)
                    },
                    enabled = !isGenerating && !isUploading,
                    role = KeyRole.ACTIVE,
                    radius = 10.dp,
                    horizontalPadding = 10.dp,
                    contentAlignment = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("cs_broll_generate_$index")
                ) { tint ->
                    if (isGenerating) {
                        ThinkingOrbSolving(size = 18.dp, tint = Cs.White)
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        text = if (isGenerating) "generating…" else "generate B-roll",
                        style = mono(11.5.sp, tint),
                        maxLines = 1
                    )
                }

                CsKey(
                    onClick = onPickMedia,
                    enabled = !isUploading && !isGenerating,
                    role = KeyRole.STD,
                    radius = 10.dp,
                    horizontalPadding = 10.dp,
                    contentAlignment = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("cs_broll_pick_$index")
                ) { tint ->
                    Icon(Icons.Sharp.PhotoLibrary, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (isUploading) "loading…" else "pick media",
                        style = mono(11.5.sp, tint),
                        maxLines = 1
                    )
                }

                if (hasMedia) {
                    CsIconKey(
                        icon = Icons.Sharp.Close,
                        contentDescription = "Clear B-roll media",
                        onClick = onClearMedia,
                        size = 40.dp,
                        radius = 10.dp,
                        iconSize = 18.dp,
                        modifier = Modifier.testTag("cs_broll_clear_$index")
                    )
                }
            }
        }
    }
}
