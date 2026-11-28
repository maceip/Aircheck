package com.example.ui.clipstudio

import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.Notes
import androidx.compose.material.icons.automirrored.sharp.Undo
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.CheckCircle
import androidx.compose.material.icons.sharp.ChevronRight
import androidx.compose.material.icons.sharp.ClosedCaption
import androidx.compose.material.icons.sharp.ContentCut
import androidx.compose.material.icons.sharp.CropLandscape
import androidx.compose.material.icons.sharp.CropPortrait
import androidx.compose.material.icons.sharp.Edit
import androidx.compose.material.icons.sharp.Layers
import androidx.compose.material.icons.sharp.Movie
import androidx.compose.material.icons.sharp.Pause
import androidx.compose.material.icons.sharp.Person
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.material.icons.sharp.RecordVoiceOver
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.Share
import androidx.compose.material.icons.sharp.Sync
import androidx.compose.material.icons.sharp.SyncProblem
import androidx.compose.material.icons.sharp.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.example.R
import com.example.data.AlignedWord
import com.example.data.BrollSegmentMode
import com.example.data.ClipRecord
import com.example.data.ClipTransitionType
import com.example.data.CoreRenderOptions
import com.example.data.CoreStoryClause
import com.example.data.ExportState
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import java.io.File
import kotlinx.coroutines.delay
import androidx.compose.ui.text.withStyle
import com.example.ui.design.AcCard
import com.example.ui.design.AcDisplay
import com.example.ui.design.AcField
import com.example.ui.design.AcKey
import com.example.ui.design.AcKeySize
import com.example.ui.design.AcMediaKey
import com.example.ui.design.AcMediaWell
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType
import com.example.ui.design.AcVideoOverlayKey

/**
 * 03 CUT:
 * - 56dp top bar (`01 · 02 · 03 CUT`, New, Undo, Settings).
 * - Compact 4-stage pipeline progress strip (`1.Voice · 2.Head · 3.Cut · 4.Export`) + explicit
 *   re-voice / re-render status banner whenever edits require a refresh.
 * - 218dp sunken `#000` ExoPlayer monitor that visibly reshapes between `9:16` portrait and
 *   `16:9` widescreen frames and previews `Auto Zoom` & `Captions` live.
 * - Clean dark-graphite 2-row controls deck (zero gray OP-1 bar, zero cryptic LEDs):
 *   - Row 1 toggles: `Captions`, `Trim` (with real detected silence gap count), `Auto Zoom`, `9:16` / `16:9`.
 *   - Row 2 studio editors: `Overlays` (Newsroom Overlays Editor) and `B-Roll & Cuts` (B-Roll & Transition Studio).
 * - Sunken `words` · `b-roll` workspace + single sequential primary pipeline dock key.
 */
@Composable
fun CutScreen(
    state: StudioUiState,
    vm: StudioViewModel,
    onOpenSettings: () -> Unit,
    onOpenNewsroom: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenBrollStudio: () -> Unit = {},
    onNavigate: (Int) -> Unit = {},
    onNewIdea: () -> Unit = { onNavigate(1) },
    renderGlobalOverlays: Boolean = true
) {
    val session = state.corePipelineSession
    val opts = session.renderOptions
    val defaultLook = state.newsroomLooks.firstOrNull { it.isDefault }
    val player = remember { vm.playerController.acquirePlayer() }
    val isPlaying by vm.playerController.isPlaying.collectAsStateWithLifecycle()
    val durationMs by vm.playerController.durationMs.collectAsStateWithLifecycle()
    var positionMs by remember { mutableLongStateOf(0L) }
    var mode by rememberSaveable { mutableStateOf("transcript") }
    var editingClause by rememberSaveable { mutableStateOf<Int?>(null) }

    BackHandler(enabled = editingClause != null) { editingClause = null }
    BackHandler(enabled = editingClause == null && state.openClause != null) { vm.setOpenCutClause(null) }

    val clipFlow = remember(session.finalClipId) { vm.observeClip(session.finalClipId.orEmpty()) }
    val clip: ClipRecord? by clipFlow.collectAsStateWithLifecycle(initialValue = null)

    val sourceInfo: Pair<Uri?, Boolean> = remember(
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
            finalFile != null -> Uri.fromFile(finalFile) to true
            !session.finalVideoUrl.isNullOrBlank() -> Uri.parse(session.finalVideoUrl) to true
            headFile != null -> Uri.fromFile(headFile) to true
            !session.talkingHeadVideoUrl.isNullOrBlank() -> Uri.parse(session.talkingHeadVideoUrl) to true
            wavFile != null -> Uri.fromFile(wavFile) to false
            !session.ttsAudioUrl.isNullOrBlank() -> Uri.parse(session.ttsAudioUrl) to false
            else -> null to false
        }
    }
    val sourceUri = sourceInfo.first
    val sourceHasVideo = sourceInfo.second
    val anchorPhotoFile: File? = remember(
        state.activeProject.stageConfig.photoSha,
        state.settings.persona.defaultPhotoSha
    ) {
        vm.resolveAnchorPhotoFile()
    }

    LaunchedEffect(sourceUri) {
        sourceUri?.let { vm.playerController.prepareUri(it, autoPlay = false) }
    }
    LaunchedEffect(isPlaying) {
        while (true) {
            positionMs = vm.playerController.currentPositionMs()
            if (!isPlaying) break
            delay(100L)
        }
    }
    DisposableEffect(Unit) { onDispose { vm.playerController.pause() } }

    // Auto-detect silence once a talking head exists and gaps are unknown for it.
    LaunchedEffect(session.talkingHeadJobId) {
        if (!session.talkingHeadJobId.isNullOrBlank() && session.silenceGapsJobId != session.talkingHeadJobId) {
            vm.refreshSilenceGaps()
        }
    }

    val unsynced = session.unsyncedClauseIndices
    val activeClause = clauseAtPlayhead(session.clauses, positionMs)
    val effectiveDurationMs = remember(durationMs, session.ttsDurationMs, session.clauses) {
        when {
            durationMs > 0L -> durationMs
            session.ttsDurationMs > 0L -> session.ttsDurationMs
            else -> session.clauses.maxOfOrNull { it.endMs } ?: 0L
        }
    }

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

            val dirtyReason: String? = when {
                unsynced.isNotEmpty() -> {
                    val unit = if (unsynced.size == 1) "line" else "lines"
                    "${unsynced.size} $unit edited — tap re-voice below to sync voice & video"
                }
                session.hasFinalVideo && !cutCurrent -> "Overlays, B-roll, or cut settings changed — tap render the video to apply"
                session.hasTalkingHead && !session.hasFinalVideo -> "Talking head ready — customize overlays or B-roll, then tap render the video"
                else -> null
            }
            if (dirtyReason != null) {
                val isWarning = unsynced.isNotEmpty() || (session.hasFinalVideo && !cutCurrent)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                        .testTag("cs_cut_dirty_banner"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        imageVector = if (isWarning) Icons.Sharp.SyncProblem else Icons.Sharp.Movie,
                        contentDescription = null,
                        tint = if (isWarning) AcTokens.Red else AcTokens.Ink2,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = dirtyReason,
                        style = AcType.BodySmall.copy(color = if (isWarning) AcTokens.Red else AcTokens.Ink2),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            VideoMonitorPanel(
                player = player,
                sourceUri = sourceUri,
                sourceHasVideo = sourceHasVideo,
                waveformPeaks = session.waveformPeaks,
                anchorPhotoFile = anchorPhotoFile,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = effectiveDurationMs,
                aspect = opts.aspect,
                punchIn = opts.punchIn,
                liveCaption = if (opts.captions && session.clauses.isNotEmpty()) {
                    session.clauses.getOrNull(activeClause)?.spokenText.orEmpty()
                } else {
                    ""
                },
                onTogglePlay = {
                    vm.playerController.togglePlayPause()
                    positionMs = vm.playerController.currentPositionMs()
                }
            )

            val isVertical = opts.aspect == CoreRenderOptions.ASPECT_VERTICAL
            val gapCount = if (session.silenceGapsJobId == session.talkingHeadJobId) session.silenceGaps.size else 0
            val activeOverlayCount = (if (opts.showLowerThird) 1 else 0) +
                (if (opts.showBrandBug && opts.cornerBugText.isNotBlank()) 1 else 0) +
                session.selectedOverlayIds.size
            val brollCutawayCount = session.clauses.count { it.segmentMode == BrollSegmentMode.BROLL_CUTAWAY }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 10.dp, end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AcMediaWell(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AcMediaKey(
                        icon = Icons.Sharp.ClosedCaption,
                        contentDescription = null,
                        caption = "Captions",
                        toggle = opts.captions,
                        onClick = { vm.setCoreCaptions(!opts.captions) },
                        size = 56.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_captions")
                    )
                    AcMediaKey(
                        icon = Icons.Sharp.ContentCut,
                        contentDescription = null,
                        caption = if (gapCount > 0) "Trim ($gapCount)" else "Trim",
                        toggle = opts.stripSilence,
                        onClick = { vm.setCoreStripSilence(!opts.stripSilence) },
                        size = 56.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_strip_silence")
                    )
                    AcMediaKey(
                        icon = Icons.Sharp.ZoomIn,
                        contentDescription = null,
                        caption = "Auto Zoom",
                        toggle = opts.punchIn,
                        onClick = { vm.setCorePunchIn(!opts.punchIn) },
                        size = 56.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_punch_in")
                    )
                    AcMediaKey(
                        icon = if (isVertical) Icons.Sharp.CropPortrait else Icons.Sharp.CropLandscape,
                        contentDescription = null,
                        caption = if (isVertical) "9:16" else "16:9",
                        onClick = {
                            vm.setCoreAspect(if (isVertical) CoreRenderOptions.ASPECT_HORIZONTAL else CoreRenderOptions.ASPECT_VERTICAL)
                        },
                        size = 56.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_aspect")
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AcKey(
                        label = if (activeOverlayCount > 0) "Overlays ($activeOverlayCount)" else "Overlays",
                        icon = Icons.Sharp.Layers,
                        trailingIcon = Icons.Sharp.ChevronRight,
                        onClick = onOpenNewsroom,
                        size = AcKeySize.Small,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_look")
                    )
                    AcKey(
                        label = if (brollCutawayCount > 0) "B-Roll ($brollCutawayCount)" else "B-Roll & Cuts",
                        icon = Icons.Sharp.Movie,
                        trailingIcon = Icons.Sharp.ChevronRight,
                        onClick = onOpenBrollStudio,
                        size = AcKeySize.Small,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_broll_studio")
                    )
                }
            }

            AcCard(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                verticalArrangement = Arrangement.Top,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 10.dp, end = 14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AcKey(
                        label = "words",
                        icon = Icons.AutoMirrored.Sharp.Notes,
                        selected = mode == "transcript",
                        onClick = { mode = "transcript" },
                        size = AcKeySize.Small,
                        role = Role.Tab,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_mode_transcript")
                    )
                    AcKey(
                        label = "b-roll",
                        icon = Icons.Sharp.Movie,
                        selected = mode == "timeline",
                        onClick = { mode = "timeline" },
                        size = AcKeySize.Small,
                        role = Role.Tab,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_mode_timeline")
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .hairlineTop()
                ) {
                    if (session.clauses.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No story loaded yet. Go to 01 SPARK and tap make the video.", style = AcType.Body.copy(color = AcTokens.Ink2))
                        }
                    } else if (mode == "transcript") {
                        TranscriptWordsPane(
                            state = state,
                            vm = vm,
                            positionMs = positionMs,
                            unsynced = unsynced,
                            editingClause = editingClause,
                            onEdit = { editingClause = it },
                            onSeekLocal = { ms -> positionMs = ms }
                        )
                    } else {
                        BrollModePane(
                            clauses = session.clauses,
                            vm = vm,
                            onOpenBrollStudio = onOpenBrollStudio,
                            onSeekLocal = { ms -> positionMs = ms }
                        )
                    }
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

            StudioDock(
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
    onTogglePlay: () -> Unit
) {
    val progressFraction = remember(positionMs, durationMs) {
        if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    }
    val isVertical = aspect == CoreRenderOptions.ASPECT_VERTICAL

    AcDisplay(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(256.dp)
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
                    style = AcType.BodySmall.copy(color = AcTokens.Ui),
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
        Text(
            text = topRightLabel,
            style = AcType.Caption.copy(color = if (sourceUri != null && !sourceHasVideo) AcTokens.OpGreen else AcTokens.OpGrey),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 12.dp, top = 12.dp)
                .testTag("cs_cut_monitor_mode")
        )

        if (!isPlaying || !sourceHasVideo) {
            AcVideoOverlayKey(
                icon = if (isPlaying) Icons.Sharp.Pause else Icons.Sharp.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                onClick = onTogglePlay,
                enabled = sourceUri != null,
                modifier = Modifier.testTag("cs_cut_play")
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 18.dp, end = 18.dp, bottom = 5.dp)
                .fillMaxWidth()
                .height(1.5.dp)
                .background(AcTokens.MediaKey)
        ) {
            if (progressFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressFraction)
                        .background(AcTokens.OpRed)
                )
            }
        }
    }
}

/** Clause under the playhead; before Stage 1 timing exists (all windows 0) it is the first clause. */
internal fun clauseAtPlayhead(clauses: List<CoreStoryClause>, positionMs: Long): Int {
    if (clauses.isEmpty()) return 0
    if (clauses.all { it.endMs <= 0L }) return 0
    return clauses.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
}

@Composable
private fun TranscriptWordsPane(
    state: StudioUiState,
    vm: StudioViewModel,
    positionMs: Long,
    unsynced: List<Int>,
    editingClause: Int?,
    onEdit: (Int?) -> Unit,
    onSeekLocal: (Long) -> Unit
) {
    val session = state.corePipelineSession
    val listState = rememberLazyListState()
    val activeClause = clauseAtPlayhead(session.clauses, positionMs)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("cs_transcript"),
        state = listState
    ) {
        itemsIndexed(session.clauses, key = { _, c -> c.id }) { idx, clause ->
            val dirty = idx in unsynced
            val broll = clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
            val isOpen = state.openClause == idx
            val isActive = activeClause == idx && session.clauses.any { it.endMs > 0L }

            val clauseWords = remember(dirty, session.alignedWords, clause.startMs, clause.endMs) {
                if (dirty) {
                    emptyList()
                } else {
                    session.alignedWords.filter {
                        it.startMs >= clause.startMs && it.startMs < clause.endMs.coerceAtLeast(clause.startMs + 1L)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isActive) AcTokens.Chassis.copy(alpha = 0.5f) else AcTokens.Ui.copy(alpha = 0f))
                    .hairlineBottom()
                    .then(
                        if (editingClause != idx) {
                            Modifier.clickable(role = Role.Button) {
                                vm.toggleOpenCutClause(idx)
                                onSeekLocal(clause.startMs)
                            }
                        } else {
                            Modifier
                        }
                    )
                    .padding(start = 12.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)
                    .testTag("cs_clause_$idx")
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = mmss(clause.startMs),
                        style = AcType.Caption.copy(lineHeight = AcType.Body.lineHeight),
                        maxLines = 1,
                        modifier = Modifier.width(44.dp)
                    )
                    if (editingClause == idx) {
                        InlineClauseWordsEditor(
                            idx = idx,
                            initialText = clause.spokenText,
                            onSave = { updated ->
                                vm.editCutClauseText(idx, updated)
                                onEdit(null)
                                vm.setOpenCutClause(null)
                            },
                            onCancel = { onEdit(null) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        ClauseKaraokeWords(
                            spokenText = clause.spokenText,
                            words = clauseWords,
                            positionMs = positionMs,
                            clauseStartMs = clause.startMs,
                            dirty = dirty,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (dirty && editingClause != idx) {
                    Row(
                        modifier = Modifier.padding(start = 52.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Sharp.SyncProblem, contentDescription = null, tint = AcTokens.Red, modifier = Modifier.size(16.dp))
                        Text(
                            text = if (broll) "edited · voice not synced · b-roll" else "edited · voice not synced",
                            style = AcType.Caption.copy(color = AcTokens.Red),
                            maxLines = 1
                        )
                    }
                } else if (broll && editingClause != idx) {
                    Row(
                        modifier = Modifier.padding(start = 52.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Sharp.Movie, contentDescription = null, tint = AcTokens.Ink2, modifier = Modifier.size(16.dp))
                        SectionCaption("B-roll")
                    }
                }

                if (isOpen && editingClause != idx) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 52.dp, top = 10.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AcKey(
                            label = "edit words",
                            icon = Icons.Sharp.Edit,
                            onClick = { onEdit(idx) },
                            size = AcKeySize.Small,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cs_clause_edit_btn_$idx")
                        )
                        AcKey(
                            label = if (broll) "back to face" else "b-roll here",
                            icon = Icons.Sharp.Movie,
                            selected = broll,
                            onClick = { vm.toggleCutClauseBroll(idx) },
                            size = AcKeySize.Small,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cs_clause_broll_btn_$idx")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClauseKaraokeWords(
    spokenText: String,
    words: List<AlignedWord>,
    positionMs: Long,
    clauseStartMs: Long,
    dirty: Boolean,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val built: Pair<AnnotatedString, IntRange?> = remember(spokenText, words, positionMs, clauseStartMs, dirty) {
        if (words.isEmpty()) {
            val color = if (dirty || positionMs >= clauseStartMs) AcTokens.Ink else AcTokens.Ink2
            buildAnnotatedString {
                pushStyle(SpanStyle(color = color))
                append(spokenText)
                pop()
            } to null
        } else {
            var activeRange: IntRange? = null
            val str = buildAnnotatedString {
                words.forEachIndexed { idx, w ->
                    val startChar = length
                    val spoken = dirty || positionMs >= w.startMs
                    val isCurrent = !dirty && positionMs >= w.startMs && positionMs < w.endMs
                    pushStyle(SpanStyle(color = if (spoken) AcTokens.Ink else AcTokens.Ink2))
                    append(w.word)
                    pop()
                    val endCharExcl = length
                    if (isCurrent && endCharExcl > startChar) activeRange = startChar until endCharExcl
                    if (idx < words.lastIndex) append(" ")
                }
            }
            str to activeRange
        }
    }

    val activeRange = built.second
    Text(
        text = built.first,
        style = AcType.Body,
        onTextLayout = { layout = it },
        modifier = modifier.drawBehind {
            val l = layout ?: return@drawBehind
            val range = activeRange ?: return@drawBehind
            if (range.isEmpty() || l.layoutInput.text.isEmpty()) return@drawBehind
            val safeStart = range.first.coerceIn(0, l.layoutInput.text.length - 1)
            val safeEnd = range.last.coerceIn(safeStart, l.layoutInput.text.length - 1)
            val a = l.getBoundingBox(safeStart)
            val b = l.getBoundingBox(safeEnd)
            val y = maxOf(a.bottom, b.bottom) - 1.dp.toPx()
            if (b.right > a.left) {
                drawLine(AcTokens.Red, Offset(a.left, y), Offset(b.right, y), 2.dp.toPx())
            }
        }
    )
}

@Composable
private fun InlineClauseWordsEditor(
    idx: Int,
    initialText: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AcField(
            value = text,
            onValueChange = { text = it },
            minLines = 2,
            maxLines = 5,
            textStyle = AcType.Body,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cs_clause_editor_$idx")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AcKey(
                label = "save words",
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
                size = AcKeySize.Small,
                modifier = Modifier.testTag("cs_clause_apply_$idx")
            )
            AcKey(
                label = "cancel",
                onClick = onCancel,
                size = AcKeySize.Small,
                modifier = Modifier.testTag("cs_clause_cancel_$idx")
            )
        }
    }
}

@Composable
private fun BrollModePane(
    clauses: List<CoreStoryClause>,
    vm: StudioViewModel,
    onOpenBrollStudio: () -> Unit,
    onSeekLocal: (Long) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .testTag("cs_timeline"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(key = "broll_studio_banner") {
            AcKey(
                label = "Open B-Roll & Transition Studio",
                icon = Icons.Sharp.Movie,
                trailingIcon = Icons.Sharp.ChevronRight,
                onClick = onOpenBrollStudio,
                size = AcKeySize.Small,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cs_open_broll_studio_inline")
            )
        }

        itemsIndexed(clauses, key = { _, c -> c.id }) { idx, clause ->
            val broll = clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
            val transition = ClipTransitionType.fromWire(clause.transitionType)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .hairlineBottom()
                    .clickable(role = Role.Button) {
                        vm.playerController.seekTo(clause.startMs)
                        onSeekLocal(clause.startMs)
                    }
                    .padding(start = 8.dp, top = 6.dp, end = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(mmss(clause.startMs), style = AcType.Caption, maxLines = 1, modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(clause.spokenText, style = AcType.BodySmall.copy(color = AcTokens.Ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (idx > 0 && transition != ClipTransitionType.CUT) {
                        SectionCaption("Transition: ${transition.label} (${clause.transitionDurationMs}ms)")
                    }
                }
                AcKey(
                    label = if (broll) "b-roll" else "face",
                    icon = if (broll) Icons.Sharp.Movie else Icons.Sharp.Person,
                    selected = broll,
                    onClick = { vm.toggleCutClauseBroll(idx) },
                    size = AcKeySize.Small,
                    modifier = Modifier.testTag("cs_timeline_toggle_$idx")
                )
            }
        }
    }
}
