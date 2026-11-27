package com.example.ui.clipstudio

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.data.BrollSegmentMode
import com.example.data.ClipTransitionType
import com.example.data.CoreRenderOptions
import com.example.data.CoreStoryClause
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import com.example.ui.design.AcCard
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcField
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import java.io.File
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

/**
 * Dedicated full-view B-Roll & Transition Studio (design v2):
 * - Global default cutaway layout (`Corner PiP` / `Full Screen` / `Split 50/50`) and default transition.
 * - Per-scene cards: transition entering the cut + speed, Talking Head vs B-Roll Cutaway, framing override,
 *   the B-roll prompt, a real B-roll still generated on the Mac rig, or real media picked from the phone.
 * - The dock's one primary key runs the next real pipeline stage.
 */
@OptIn(ExperimentalLayoutApi::class)
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
            .background(AcTokens.Chassis)
            .testTag("cs_broll_studio_root")
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
                    modifier = Modifier.testTag("cs_broll_studio_back")
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("B-Roll & Transitions", style = AcType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SectionCaption(
                        "$brollCount cutaway${if (brollCount == 1) "" else "s"} · ${session.clauses.size} scene${if (session.clauses.size == 1) "" else "s"}"
                    )
                }
                AcKey(
                    icon = Icons.AutoMirrored.Sharp.Undo,
                    contentDescription = "Undo B-roll change",
                    enabled = state.coreUndoDepth > 0,
                    onClick = vm::undoCoreEdit,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_broll_studio_undo")
                )
            }

            StudioStatusLine(
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
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "broll_global_defaults") {
                    AcCard(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionCaption("Default cutaway layout")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                CoreRenderOptions.BROLL_FRAMING_PIP to "Corner PiP",
                                CoreRenderOptions.BROLL_FRAMING_FULL to "Full Screen",
                                CoreRenderOptions.BROLL_FRAMING_SPLIT to "Split 50/50"
                            ).forEach { (wire, label) ->
                                AcKey(
                                    label = label,
                                    onClick = { vm.setCoreBrollFraming(wire) },
                                    selected = opts.brollFraming == wire,
                                    size = AcKeySize.Small,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("cs_broll_framing_$wire")
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SectionCaption("Default transition", modifier = Modifier.weight(1f))
                            AcKey(
                                label = "apply to all",
                                onClick = { vm.applyTransitionToAllCuts(defaultTransition) },
                                enabled = session.clauses.isNotEmpty(),
                                size = AcKeySize.Small,
                                modifier = Modifier.testTag("cs_apply_all_transitions")
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ClipTransitionType.entries.forEach { t ->
                                AcKey(
                                    label = t.label,
                                    selected = defaultTransition == t,
                                    onClick = { vm.setCoreDefaultTransition(t) },
                                    size = AcKeySize.Small,
                                    modifier = Modifier.testTag("cs_default_trans_${t.wire}")
                                )
                            }
                        }
                    }
                }

                if (session.clauses.isEmpty()) {
                    item(key = "broll_empty") {
                        AcCard(modifier = Modifier.fillMaxWidth()) {
                            Text("No scenes in this clip yet.", style = AcType.Body)
                            Text(
                                "Outline beats in 01 SPARK and tap make the video to build your B-roll timeline.",
                                style = AcType.BodySmall
                            )
                        }
                    }
                } else {
                    itemsIndexed(session.clauses, key = { _, c -> c.id }) { idx, clause ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val clauseTrans = ClipTransitionType.fromWire(clause.transitionType)
                            val transExpanded = expandedTransitionClause == idx
                            if (idx > 0 || clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY) {
                                TransitionConnectorRow(
                                    index = idx,
                                    transition = clauseTrans,
                                    durationMs = clause.transitionDurationMs,
                                    expanded = transExpanded,
                                    onToggleExpand = { expandedTransitionClause = if (transExpanded) null else idx },
                                    onSelect = { nextTrans, durMs -> vm.setClauseTransition(idx, nextTrans, durMs) }
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

            data class DockAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val enabled: Boolean, val onClick: () -> Unit)
            val dock = when {
                session.clauses.isEmpty() -> DockAction("load a script first", Icons.Sharp.Movie, false) {}
                !session.hasTtsAudio -> DockAction("voice script first", Icons.Sharp.RecordVoiceOver, true) {
                    vm.runCoreStage1Tts()
                    onRenderStarted()
                }
                unsynced.isNotEmpty() -> DockAction(
                    "re-voice ${unsynced.size} edited line${if (unsynced.size == 1) "" else "s"}",
                    Icons.Sharp.Sync,
                    true
                ) {
                    vm.resyncEditedVoice()
                    onRenderStarted()
                }
                !session.hasTalkingHead -> DockAction("render talking head", Icons.Sharp.Person, true) {
                    vm.runCoreStage3SpeechToHead()
                    onRenderStarted()
                }
                else -> DockAction("generate video", Icons.Sharp.Movie, true) {
                    vm.runCoreStage4ChopBroll()
                    onRenderStarted()
                }
            }

            StudioDock(
                primaryLabel = dock.label,
                primaryIcon = dock.icon,
                primaryEnabled = dock.enabled && !state.isCoreStageBusy,
                primaryTag = "cs_broll_generate_video",
                onPrimaryClick = dock.onClick,
                isBusy = state.isCoreStageBusy,
                busyLabel = state.coreStageStatusLabel ?: "rendering…"
            )
        }

        if (renderGlobalOverlays) {
            StudioToast(message = state.toastMessage, onDismiss = vm::dismissToast)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AcKey(
            onClick = onToggleExpand,
            selected = expanded,
            size = AcKeySize.Small,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cs_clause_trans_chip_$index")
        ) { fg ->
            Icon(Icons.Sharp.SwapHoriz, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Text("Transition: ${transition.label}", style = AcType.Label.copy(color = fg), maxLines = 1, modifier = Modifier.weight(1f))
            Text(if (active) "${durationMs}ms" else "edit", style = AcType.Caption.copy(color = fg), maxLines = 1)
        }

        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ClipTransitionType.entries.forEach { t ->
                    AcKey(
                        label = t.label,
                        selected = transition == t,
                        onClick = { onSelect(t, durationMs) },
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_clause_trans_opt_${index}_${t.wire}")
                    )
                }
            }
            if (active) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionCaption("Speed", modifier = Modifier.weight(1f))
                    listOf(200 to "fast 0.2s", 350 to "med 0.35s", 600 to "slow 0.6s").forEach { (ms, lbl) ->
                        AcKey(
                            label = lbl,
                            onClick = { onSelect(transition, ms) },
                            selected = durationMs == ms,
                            size = AcKeySize.Small,
                            modifier = Modifier.testTag("cs_clause_trans_dur_${index}_$ms")
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
    var prompt by rememberSaveable(clause.id) { mutableStateOf(initialPrompt) }
    LaunchedEffect(clause.id, clause.bRollPrompt) {
        if (clause.bRollPrompt.isNotBlank() && prompt != clause.bRollPrompt) prompt = clause.bRollPrompt
    }

    AcCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cs_broll_card_$index"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionCaption("Scene ${index + 1} · ${mmss(clause.startMs)}", modifier = Modifier.weight(1f))
            AcKey(
                label = if (isBroll) "B-Roll Cutaway" else "Talking Head",
                icon = if (isBroll) Icons.Sharp.Movie else Icons.Sharp.Person,
                selected = isBroll,
                onClick = onToggleMode,
                size = AcKeySize.Small,
                modifier = Modifier.testTag("cs_broll_mode_$index")
            )
        }

        Text(clause.spokenText, style = AcType.Body)

        if (isBroll) {
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
                AcDisplay(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(128.dp)
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
                AcDisplay(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Sharp.Movie, contentDescription = null, tint = AcTokens.OpOrange, modifier = Modifier.size(18.dp))
                        Text(
                            "Custom video clip attached (.${clause.customBrollExt})",
                            style = AcType.Label.copy(color = AcTokens.Ui),
                            maxLines = 1
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "" to "Default ($globalFraming)",
                    CoreRenderOptions.BROLL_FRAMING_PIP to "PiP",
                    CoreRenderOptions.BROLL_FRAMING_FULL to "Full",
                    CoreRenderOptions.BROLL_FRAMING_SPLIT to "Split"
                ).forEach { (wire, lbl) ->
                    AcKey(
                        label = lbl,
                        onClick = { onSelectFraming(wire) },
                        selected = clause.brollFramingOverride == wire,
                        size = AcKeySize.Small,
                        modifier = Modifier
                            .weight(if (wire.isEmpty()) 1.4f else 1f)
                            .testTag("cs_clause_framing_${index}_${wire.ifEmpty { "default" }}")
                    )
                }
            }

            AcField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = "Describe B-roll footage to show…",
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cs_broll_prompt_$index")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AcKey(
                    onClick = {
                        val txt = prompt.trim()
                        onCommitPrompt(txt)
                        onGenerateStill(txt)
                    },
                    selected = isGenerating,
                    enabled = !isGenerating && !isUploading,
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_broll_generate_$index")
                ) { fg ->
                    if (isGenerating) {
                        ThinkingOrbSolving(size = 18.dp, tint = fg)
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                    }
                    Text(if (isGenerating) "generating…" else "generate B-roll", style = AcType.Label.copy(color = fg), maxLines = 1)
                }
                AcKey(
                    label = if (isUploading) "loading…" else "pick media",
                    icon = Icons.Sharp.PhotoLibrary,
                    onClick = onPickMedia,
                    enabled = !isUploading && !isGenerating,
                    size = AcKeySize.Small,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cs_broll_pick_$index")
                )
                if (hasMedia) {
                    AcKey(
                        icon = Icons.Sharp.Close,
                        contentDescription = "Clear B-roll media",
                        onClick = onClearMedia,
                        size = AcKeySize.Small,
                        modifier = Modifier.testTag("cs_broll_clear_$index")
                    )
                }
            }
        }
    }
}
