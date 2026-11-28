package com.example.ui.clipstudio

import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.Undo
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.ClosedCaption
import androidx.compose.material.icons.sharp.ContentCut
import androidx.compose.material.icons.sharp.CropLandscape
import androidx.compose.material.icons.sharp.CropPortrait
import androidx.compose.material.icons.sharp.Layers
import androidx.compose.material.icons.sharp.Movie
import androidx.compose.material.icons.sharp.Pause
import androidx.compose.material.icons.sharp.Person
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.material.icons.sharp.RecordVoiceOver
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.Share
import androidx.compose.material.icons.sharp.SwapHoriz
import androidx.compose.material.icons.sharp.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.ClipRecord
import com.example.data.CoreRenderOptions
import com.example.data.CoreStoryClause
import com.example.data.CutTimeline
import com.example.data.ExportState
import com.example.data.GfxLane
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcMediaKey
import com.example.ui.design.AcMediaWell
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import com.example.ui.design.AcVideoOverlayKey
import java.io.File
import kotlinx.coroutines.delay

/**
 * 02 CUT (cut mockup + studio mockup, approved 2026-11-28):
 * - Header `01 · 02 CUT` with new / undo / settings (rig dot), one status Caption line.
 * - AcDisplay player (drag grip resizes it 220–470dp), flat play key over the video.
 * - AcDisplay timeline: VOICE clips with trim brackets and ◆ transition joins, B-ROLL blocks you
 *   drag / resize / tap, read-only GFX lane showing when each graphic is on screen.
 * - Media well: play, split at the playhead, add b-roll at the playhead, aspect (9:16 / 16:9), captions.
 * - Bottom: overlays key (Newsroom), blue-icon transitions key (all joins), the one primary key.
 *   Renders start only here.
 */
@Composable
fun CutScreen(
    state: StudioUiState,
    vm: StudioViewModel,
    onOpenSettings: () -> Unit,
    onOpenNewsroom: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (Int) -> Unit = {},
    onNewIdea: () -> Unit = { onNavigate(1) },
    renderGlobalOverlays: Boolean = true
) {
    val session = remember(state.corePipelineSession) { CutTimeline.normalize(state.corePipelineSession) }
    val opts = session.renderOptions
    val defaultLook = state.newsroomLooks.firstOrNull { it.isDefault }
    val player = remember { vm.playerController.acquirePlayer() }
    val isPlaying by vm.playerController.isPlaying.collectAsStateWithLifecycle()
    val durationMs by vm.playerController.durationMs.collectAsStateWithLifecycle()
    var positionMs by remember { mutableLongStateOf(0L) }
    var playerHeightDp by rememberSaveable { mutableFloatStateOf(330f) }
    // null = closed, -1 = every join, n = the join entering clause n.
    var transitionJoin by rememberSaveable { mutableStateOf<Int?>(null) }
    var brollSheetSegment by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPickSegment by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = transitionJoin != null) { transitionJoin = null }
    BackHandler(enabled = transitionJoin == null && brollSheetSegment != null) { brollSheetSegment = null }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val segId = pendingPickSegment
        pendingPickSegment = null
        if (uri != null && segId != null) vm.importCustomBrollMediaForSegment(segId, uri)
    }

    val clipFlow = remember(session.finalClipId) { vm.observeClip(session.finalClipId.orEmpty()) }
    val clip: ClipRecord? by clipFlow.collectAsStateWithLifecycle(initialValue = null)

    // Player source: the rendered cut (edited timeline), else the talking head or voice (source time).
    data class PlayerSource(val uri: Uri?, val hasVideo: Boolean, val edited: Boolean)
    val source: PlayerSource = remember(
        session.finalVideoSha,
        session.finalVideoUrl,
        session.talkingHeadSha,
        session.talkingHeadVideoUrl,
        session.ttsVoiceSha,
        session.ttsAudioUrl
    ) {
        val finalFile = vm.casStore.fileForSha(session.finalVideoSha, "mp4")
        val headFile = vm.casStore.fileForSha(session.talkingHeadSha, "mp4")
        val wavFile = vm.casStore.fileForSha(session.ttsVoiceSha, "wav")
        when {
            finalFile != null -> PlayerSource(Uri.fromFile(finalFile), true, true)
            !session.finalVideoUrl.isNullOrBlank() -> PlayerSource(Uri.parse(session.finalVideoUrl), true, true)
            headFile != null -> PlayerSource(Uri.fromFile(headFile), true, false)
            !session.talkingHeadVideoUrl.isNullOrBlank() -> PlayerSource(Uri.parse(session.talkingHeadVideoUrl), true, false)
            wavFile != null -> PlayerSource(Uri.fromFile(wavFile), false, false)
            !session.ttsAudioUrl.isNullOrBlank() -> PlayerSource(Uri.parse(session.ttsAudioUrl), false, false)
            else -> PlayerSource(null, false, false)
        }
    }
    val anchorPhotoFile: File? = remember(
        state.activeProject.stageConfig.photoSha,
        state.settings.persona.defaultPhotoSha
    ) {
        vm.resolveAnchorPhotoFile()
    }

    LaunchedEffect(source.uri) {
        source.uri?.let { vm.playerController.prepareUri(it, autoPlay = false) }
    }
    LaunchedEffect(isPlaying) {
        while (true) {
            positionMs = vm.playerController.currentPositionMs()
            if (!isPlaying) break
            delay(100L)
        }
    }
    DisposableEffect(Unit) { onDispose { vm.playerController.pause() } }

    val clips = remember(session.cutClips, session.ttsDurationMs, session.clauses) { CutTimeline.effectiveClips(session) }
    val timelineTotalMs = CutTimeline.timelineDurationMs(clips)
    val playheadMs = if (source.edited) positionMs.coerceAtMost(timelineTotalMs) else sourceToTimelineClamped(clips, positionMs)
    val playheadSourceMs = if (source.edited) CutTimeline.timelineToSource(clips, positionMs) ?: positionMs else positionMs
    val seekTimeline: (Long) -> Unit = { tl ->
        val target = if (source.edited) tl else CutTimeline.timelineToSource(clips, tl) ?: clips.lastOrNull()?.sourceEndMs ?: 0L
        runCatching { vm.playerController.seekTo(target) }
        positionMs = target
    }
    val gfxSpans = remember(session, state.savedOverlays, defaultLook, timelineTotalMs) {
        GfxLane.spans(session, state.savedOverlays, defaultLook, timelineTotalMs)
    }

    val unsynced = session.unsyncedClauseIndices
    val activeClause = clauseAtPlayhead(session.clauses, playheadSourceMs)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AcTokens.Chassis)
            .testTag("cs_cut_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            // Real signature check including overlay content, B-roll media, and transitions
            val signature = session.cutSignature(defaultLook, state.savedOverlays)
            val cutCurrent = session.hasFinalVideo && session.lastCutSignature == signature
            val leds = com.example.ui.pipelineLedStateFor(state)

            StudioTopBar(
                activeStage = 2,
                onNavigate = onNavigate
            ) {
                if (unsynced.isNotEmpty()) {
                    val badgeText = if (unsynced.size == 1) "1 LINE NEEDS RE-VOICE" else "${unsynced.size} LINES NEED RE-VOICE"
                    Text(
                        text = badgeText,
                        style = AcType.Caption.copy(color = AcTokens.Red),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = 2.dp)
                            .testTag("cs_cut_badge")
                    )
                }
                AcKey(
                    icon = Icons.Sharp.Add,
                    contentDescription = "New idea",
                    onClick = onNewIdea,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_cut_new_idea")
                )
                AcKey(
                    icon = Icons.AutoMirrored.Sharp.Undo,
                    contentDescription = "Undo cut edit",
                    onClick = vm::undoCoreEdit,
                    enabled = state.coreUndoDepth > 0,
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_cut_undo")
                )
                // Rig connection lives as a dot on the settings key: ink = online, red = offline.
                val rigDot = when (state.rigHealth.status) {
                    com.example.ui.RigConnectionStatus.ONLINE -> AcTokens.Ink
                    com.example.ui.RigConnectionStatus.ERROR -> AcTokens.Red
                    else -> null
                }
                val rigWord = when (state.rigHealth.status) {
                    com.example.ui.RigConnectionStatus.ONLINE -> "rig online"
                    com.example.ui.RigConnectionStatus.ERROR -> "rig offline"
                    com.example.ui.RigConnectionStatus.CHECKING -> "checking rig"
                    com.example.ui.RigConnectionStatus.IDLE -> "rig not tested"
                }
                AcKey(
                    icon = Icons.Sharp.Settings,
                    contentDescription = "Settings, $rigWord",
                    onClick = onOpenSettings,
                    size = AcKeySize.Small,
                    badgeColor = rigDot,
                    modifier = Modifier.testTag("cs_cut_settings")
                )
            }

            CutPipelineStatusLine(
                hasTtsAudio = session.hasTtsAudio,
                hasTalkingHead = session.hasTalkingHead,
                hasFinalVideo = session.hasFinalVideo,
                cutCurrent = cutCurrent,
                unsyncedCount = unsynced.size,
                exported = state.exportState == ExportState.EXPORTED && cutCurrent,
                leds = leds
            )

            StudioStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VideoMonitorPanel(
                    player = player,
                    sourceUri = source.uri,
                    sourceHasVideo = source.hasVideo,
                    waveformPeaks = session.waveformPeaks,
                    anchorPhotoFile = anchorPhotoFile,
                    isPlaying = isPlaying,
                    positionMs = playheadMs,
                    durationMs = timelineTotalMs.takeIf { it > 0L } ?: durationMs,
                    aspect = opts.aspect,
                    punchIn = opts.punchIn,
                    liveCaption = if (opts.captions && session.clauses.isNotEmpty()) {
                        session.clauses.getOrNull(activeClause)?.spokenText.orEmpty()
                    } else {
                        ""
                    },
                    heightDp = playerHeightDp,
                    onResize = { deltaDp -> playerHeightDp = (playerHeightDp + deltaDp).coerceIn(220f, 470f) },
                    onTogglePlay = {
                        vm.playerController.togglePlayPause()
                        positionMs = vm.playerController.currentPositionMs()
                    }
                )

                if (session.clauses.isEmpty()) {
                    Text(
                        "No story loaded yet. Go to 01 SPARK and tap make the video.",
                        style = AcType.Body.copy(color = AcTokens.Ink2),
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                } else {
                    CutTimelinePanel(
                        session = session,
                        clips = clips,
                        gfxSpans = gfxSpans,
                        playheadMs = playheadMs,
                        onSeek = seekTimeline,
                        onTrim = vm::trimCutClip,
                        onMoveSegment = vm::moveBrollSegment,
                        onResizeSegment = vm::resizeBrollSegment,
                        onOpenSegment = { brollSheetSegment = it },
                        onOpenJoin = { transitionJoin = it }
                    )
                }

                AcMediaWell(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AcMediaKey(
                        icon = if (isPlaying) Icons.Sharp.Pause else Icons.Sharp.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        active = isPlaying,
                        enabled = source.uri != null,
                        onClick = {
                            vm.playerController.togglePlayPause()
                            positionMs = vm.playerController.currentPositionMs()
                        },
                        size = 56.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_well_play")
                    )
                    AcMediaKey(
                        icon = Icons.Sharp.ContentCut,
                        contentDescription = "Split at the playhead",
                        caption = "SPLIT",
                        enabled = timelineTotalMs > 0L,
                        onClick = { vm.splitCutAtPlayhead(playheadMs) },
                        size = 56.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_split")
                    )
                    AcMediaKey(
                        icon = Icons.Sharp.Movie,
                        contentDescription = "Add B-roll at the playhead",
                        caption = "B-ROLL",
                        enabled = timelineTotalMs > 0L,
                        onClick = { vm.addBrollSegmentAtPlayhead(playheadMs) },
                        size = 56.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_add_broll")
                    )
                    val isVertical = opts.aspect == CoreRenderOptions.ASPECT_VERTICAL
                    // Two-state choice, not on/off: no LED; the caption names the current aspect.
                    AcMediaKey(
                        icon = if (isVertical) Icons.Sharp.CropPortrait else Icons.Sharp.CropLandscape,
                        contentDescription = if (isVertical) "Aspect 9:16, switch to 16:9" else "Aspect 16:9, switch to 9:16",
                        caption = if (isVertical) "9:16" else "16:9",
                        onClick = {
                            vm.setCoreAspect(if (isVertical) CoreRenderOptions.ASPECT_HORIZONTAL else CoreRenderOptions.ASPECT_VERTICAL)
                        },
                        size = 56.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_aspect")
                    )
                    AcMediaKey(
                        icon = Icons.Sharp.ClosedCaption,
                        contentDescription = "Captions",
                        caption = "CAPS",
                        toggle = opts.captions,
                        onClick = { vm.setCoreCaptions(!opts.captions) },
                        size = 56.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_captions")
                    )
                }
            }

            val isCutBusy = state.isCoreStageBusy || state.exportState == ExportState.EXPORTING
            val busyLabel = when {
                state.exportState == ExportState.EXPORTING -> "exporting…"
                else -> state.coreStageStatusLabel ?: "rendering…"
            }
            // Only a measured fraction is shown; unknown progress (<= 0) renders the label with no percent.
            val busyFraction = if (state.isCoreStageBusy) state.coreStageProgress.takeIf { it > 0f } else null

            val dockSpec = resolveCutPrimarySpec(
                hasClauses = session.clauses.isNotEmpty(),
                hasTtsAudio = session.hasTtsAudio,
                unsyncedCount = unsynced.size,
                hasTalkingHead = session.hasTalkingHead,
                cutCurrent = cutCurrent,
                exportState = state.exportState,
                clip = clip,
                vm = vm
            )

            val activeOverlayCount = gfxSpans.size
            StudioDock(
                leading = {
                    AcKey(
                        icon = Icons.Sharp.Layers,
                        contentDescription = if (activeOverlayCount > 0) "Overlays, $activeOverlayCount on screen" else "Overlays",
                        onClick = onOpenNewsroom,
                        modifier = Modifier.testTag("cs_cut_look")
                    )
                    AcKey(
                        onClick = { transitionJoin = -1 },
                        circle = true,
                        enabled = session.clauses.size > 1,
                        modifier = Modifier.testTag("cs_cut_transitions")
                    ) { _ ->
                        // Blue glyph kept from the original transitions button (Ryan, 2026-11-28).
                        Icon(Icons.Sharp.SwapHoriz, contentDescription = "Transitions for every join", tint = AcTokens.OpBlue, modifier = Modifier.size(22.dp))
                    }
                },
                primaryLabel = dockSpec.label,
                primaryIcon = dockSpec.icon,
                primaryEnabled = dockSpec.enabled && !isCutBusy,
                primaryTag = "cs_cut_primary",
                onPrimaryClick = dockSpec.onClick,
                isBusy = isCutBusy,
                busyLabel = busyLabel,
                busyFraction = busyFraction
            )
        }

        transitionJoin?.let { join ->
            TransitionSheet(
                session = session,
                clips = clips,
                clauseIndex = join.takeIf { it >= 0 },
                onSetJoin = vm::setClauseTransition,
                onSetDefault = vm::setCoreDefaultTransition,
                onApplyAll = vm::applyTransitionToAllCuts,
                onDismiss = { transitionJoin = null }
            )
        }

        brollSheetSegment?.let { segId ->
            val seg = session.brollSegments.firstOrNull { it.id == segId }
            if (seg == null) {
                LaunchedEffect(segId) { brollSheetSegment = null }
            } else {
                val linkedIdx = seg.originClauseId?.let { o -> session.clauses.indexOfFirst { it.id == o } }?.takeIf { it >= 0 }
                BrollSegmentSheet(
                    segment = seg,
                    clips = clips,
                    defaultFraming = opts.brollFraming,
                    isGenerating = segId in state.generatingBrollSegmentIds || (linkedIdx != null && linkedIdx in state.generatingBrollClauseIndices),
                    isUploading = segId in state.uploadingBrollSegmentIds || (linkedIdx != null && linkedIdx in state.uploadingBrollClauseIndices),
                    onCommitPrompt = { vm.updateBrollSegmentPrompt(segId, it) },
                    onGenerate = { vm.generateBrollForSegment(segId, it) },
                    onPickMedia = {
                        pendingPickSegment = segId
                        mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    },
                    onSetFraming = { vm.setBrollSegmentFraming(segId, it) },
                    onDelete = {
                        brollSheetSegment = null
                        vm.removeBrollSegment(segId)
                    },
                    onDismiss = { brollSheetSegment = null }
                )
            }
        }

        if (renderGlobalOverlays) {
            StudioToast(message = state.toastMessage, onDismiss = vm::dismissToast)
        }
    }
}

private data class CutPrimarySpec(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val onClick: () -> Unit
)

private fun resolveCutPrimarySpec(
    hasClauses: Boolean,
    hasTtsAudio: Boolean,
    unsyncedCount: Int,
    hasTalkingHead: Boolean,
    cutCurrent: Boolean,
    exportState: ExportState,
    clip: ClipRecord?,
    vm: StudioViewModel
): CutPrimarySpec {
    return when {
        !hasClauses -> CutPrimarySpec("voice the script", Icons.Sharp.RecordVoiceOver, false) {}
        !hasTtsAudio -> CutPrimarySpec("voice the script", Icons.Sharp.RecordVoiceOver, true, vm::runCoreStage1Tts)
        unsyncedCount > 0 -> CutPrimarySpec(
            "re-voice $unsyncedCount edited line${if (unsyncedCount == 1) "" else "s"}",
            Icons.Sharp.Sync,
            true,
            vm::resyncEditedVoice
        )
        !hasTalkingHead -> CutPrimarySpec("render talking head", Icons.Sharp.Person, true, vm::runCoreStage3SpeechToHead)
        !cutCurrent -> CutPrimarySpec("render the video", Icons.Sharp.Movie, true) { vm.runCoreStage4ChopBroll() }
        exportState != ExportState.EXPORTED -> CutPrimarySpec("export & share", Icons.Sharp.Share, clip != null) {
            clip?.let { vm.exportClip(it, openShareSheet = true) }
        }
        else -> CutPrimarySpec("share again", Icons.Sharp.Share, clip != null) {
            clip?.let { vm.exportClip(it, openShareSheet = true) }
        }
    }
}

/**
 * Option C (approved 2026-11-28): one Caption line under the step header instead of the old black
 * RIG/VOX/HEAD/CUT lights and 1.Voice-4.Export strip. Same state: each step is done (ink ✓),
 * working (red, while that stage runs), needs redo after an edit (red), or not yet (muted).
 */
@Composable
private fun CutPipelineStatusLine(
    hasTtsAudio: Boolean,
    hasTalkingHead: Boolean,
    hasFinalVideo: Boolean,
    cutCurrent: Boolean,
    unsyncedCount: Int,
    exported: Boolean,
    leds: PipelineLedState
) {
    data class Step(val label: String, val done: Boolean, val stale: Boolean, val working: Boolean)
    val steps = listOf(
        Step("voice", hasTtsAudio && unsyncedCount == 0, unsyncedCount > 0, leds.voice == LedState.WORKING),
        Step("head", hasTalkingHead && unsyncedCount == 0, hasTalkingHead && unsyncedCount > 0, leds.head == LedState.WORKING),
        Step("cut", cutCurrent, hasFinalVideo && !cutCurrent, leds.cut == LedState.WORKING),
        Step("export", exported, false, false)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
            .testTag("cs_cut_pipeline_strip"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEach { step ->
            val (text, color) = when {
                step.working -> "${step.label}…" to AcTokens.Red
                step.stale -> "${step.label} · redo" to AcTokens.Red
                step.done -> "✓ ${step.label}" to AcTokens.Ink
                else -> "· ${step.label}" to AcTokens.Ink2
            }
            Text(text.uppercase(), style = AcType.Caption.copy(color = color), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun VideoMonitorPanel(
    player: androidx.media3.common.Player,
    sourceUri: Uri?,
    sourceHasVideo: Boolean,
    waveformPeaks: List<Float>,
    anchorPhotoFile: File?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    aspect: String,
    punchIn: Boolean,
    liveCaption: String,
    heightDp: Float,
    onResize: (deltaDp: Float) -> Unit,
    onTogglePlay: () -> Unit
) {
    val density = LocalDensity.current
    val latestResize by rememberUpdatedState(onResize)
    val progressFraction = remember(positionMs, durationMs) {
        if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    }
    val isVertical = aspect == CoreRenderOptions.ASPECT_VERTICAL

    AcDisplay(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(heightDp.dp)
            .clickable(enabled = sourceUri != null, role = Role.Button, onClick = onTogglePlay)
            .testTag("cs_cut_monitor")
    ) {
        val frameModifier = if (isVertical) {
            Modifier
                .padding(vertical = 8.dp)
                .fillMaxHeight()
                .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
        } else {
            Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .fillMaxHeight()
                .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
        }

        Box(
            modifier = frameModifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, AcTokens.MediaKey, RoundedCornerShape(6.dp))
                .clipToBounds()
                .testTag(if (isVertical) "cs_cut_frame_vertical" else "cs_cut_frame_horizontal"),
            contentAlignment = Alignment.Center
        ) {
            if (sourceUri != null && sourceHasVideo) {
                val punchScale = if (punchIn) 1.12f else 1.0f
                AndroidView(
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(R.layout.cut_texture_player_view, null) as PlayerView).apply {
                            this.player = player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            setKeepContentOnPlayerReset(false)
                        }
                    },
                    update = { view -> if (view.player !== player) view.player = player },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = punchScale
                            scaleY = punchScale
                        }
                )
            } else if (sourceUri != null) {
                if (anchorPhotoFile != null && anchorPhotoFile.isFile) {
                    val punchScale = if (punchIn) 1.12f else 1.0f
                    AsyncImage(
                        model = anchorPhotoFile,
                        contentDescription = "Presenter photo preview",
                        contentScale = ContentScale.Crop,
                        alpha = 0.32f,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = punchScale
                                scaleY = punchScale
                            }
                    )
                }
                if (waveformPeaks.isNotEmpty()) {
                    // TP-7 radial tape-reel spindle driven strictly by real playback progressFraction.
                    Canvas(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp)
                            .size(56.dp)
                            .testTag("cs_cut_tp7_spindle")
                    ) {
                        val r = size.minDimension / 2f - 2.dp.toPx()
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val angleRad = progressFraction * 2f * Math.PI.toFloat() * 4f
                        val dx = kotlin.math.cos(angleRad) * r * 0.85f
                        val dy = kotlin.math.sin(angleRad) * r * 0.85f
                        drawCircle(AcTokens.OpGrey.copy(alpha = 0.45f), radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                        drawLine(AcTokens.OpGrey.copy(alpha = 0.75f), Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), 1.dp.toPx())
                        drawCircle(AcTokens.MediaKeyHi, radius = 6.dp.toPx(), center = c)
                        drawCircle(if (isPlaying) AcTokens.OpRed else AcTokens.OpGrey, radius = 2.dp.toPx(), center = Offset(c.x + dx * 0.75f, c.y + dy * 0.75f))
                    }
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .padding(horizontal = 12.dp)
                    ) {
                        val count = waveformPeaks.size
                        val step = size.width / count.coerceAtLeast(1).toFloat()
                        val strokeW = 1.5.dp.toPx()
                        val centerY = size.height / 2f
                        waveformPeaks.forEachIndexed { idx, rawPeak ->
                            val frac = idx.toFloat() / count.coerceAtLeast(1).toFloat()
                            val barH = (size.height * (0.14f + 0.82f * rawPeak.coerceIn(0f, 1f)))
                            val x = idx * step + step / 2f
                            drawLine(
                                color = if (frac <= progressFraction) AcTokens.OpRed else AcTokens.OpGrey,
                                start = Offset(x, centerY - barH / 2f),
                                end = Offset(x, centerY + barH / 2f),
                                strokeWidth = strokeW,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            }

            if (liveCaption.isNotBlank()) {
                Text(
                    text = liveCaption,
                    style = AcType.BodySmall.copy(color = AcTokens.DisplayInk),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 8.dp, end = 8.dp, bottom = 10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(AcTokens.Display.copy(alpha = 0.62f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("cs_cut_live_caption")
                )
            }
        }

        Text(
            text = buildAnnotatedString {
                append(mmss(positionMs))
                withStyle(AcType.numeral(15.sp).copy(color = AcTokens.OpGrey).toSpanStyle()) { append(" / ${mmss(durationMs)}") }
            },
            style = AcType.numeral(20.sp),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 8.dp)
                .testTag("cs_cut_timecode")
        )

        val topRightLabel = buildString {
            if (sourceUri != null && !sourceHasVideo) append("VOICE · ")
            append(if (isVertical) "9:16" else "16:9")
            if (punchIn) append(" · ZOOM")
        }
        // SIGMA-style recessed OLED pill readout in the top-right bezel.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 10.dp, top = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(AcTokens.Well)
                .border(1.dp, AcTokens.MediaKey, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = topRightLabel,
                style = AcType.Caption.copy(color = if (sourceUri != null && !sourceHasVideo) AcTokens.OpGreen else AcTokens.OpGrey),
                maxLines = 1,
                modifier = Modifier.testTag("cs_cut_monitor_mode")
            )
        }

        // TX-6 style L/R level meter docked vertically in the right bezel, driven strictly by real waveformPeaks.
        if (waveformPeaks.isNotEmpty()) {
            StudioLevelMeter(
                peaks = waveformPeaks,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp, top = 36.dp, bottom = 28.dp)
                    .fillMaxHeight()
            )
        }

        if (!isPlaying || !sourceHasVideo) {
            AcVideoOverlayKey(
                icon = if (isPlaying) Icons.Sharp.Pause else Icons.Sharp.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                onClick = onTogglePlay,
                enabled = sourceUri != null,
                modifier = Modifier.testTag("cs_cut_play")
            )
        }

        // Drag grip: resizes the player between 220 and 470 dp.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(width = 72.dp, height = 24.dp)
                .semantics { contentDescription = "Resize player" }
                .testTag("cs_cut_player_grip")
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dy ->
                        change.consume()
                        latestResize(with(density) { dy.toDp().value })
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 34.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AcTokens.OpGrey)
            )
        }
    }
}

/** Clause under the playhead; before Stage 1 timing exists (all windows 0) it is the first clause. */
internal fun clauseAtPlayhead(clauses: List<CoreStoryClause>, positionMs: Long): Int {
    if (clauses.isEmpty()) return 0
    if (clauses.all { it.endMs <= 0L }) return 0
    return clauses.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
}
