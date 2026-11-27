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
            .background(Cs.Body)
            .testTag("cs_cut_root")
    ) {
        Column(Modifier.fillMaxSize()) {
            CsTopBar(
                activeStage = 2,
                onNavigate = onNavigate,
                leds = com.example.ui.pipelineLedStateFor(state)
            ) {
                if (unsynced.isNotEmpty()) {
                    val badgeText = if (unsynced.size == 1) {
                        "1 LINE NEEDS RE-VOICE"
                    } else {
                        "${unsynced.size} LINES NEED RE-VOICE"
                    }
                    Text(
                        text = badgeText,
                        style = ndot(10.sp, Cs.Amber),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = 2.dp)
                            .testTag("cs_cut_badge")
                    )
                    CsIconKey(
                        icon = Icons.Sharp.Add,
                        contentDescription = "New idea",
                        onClick = onNewIdea,
                        size = 40.dp,
                        radius = 11.dp,
                        iconSize = 20.dp,
                        modifier = Modifier.testTag("cs_cut_new_idea")
                    )
                } else {
                    CsKey(
                        onClick = onNewIdea,
                        role = KeyRole.STD,
                        radius = 11.dp,
                        horizontalPadding = 8.dp,
                        contentAlignment = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("cs_cut_new_idea")
                    ) { tint ->
                        Icon(
                            imageVector = Icons.Sharp.Add,
                            contentDescription = "New idea",
                            tint = tint,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "new",
                            style = mono(11.5.sp, tint, FontWeight.Medium),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                CsIconKey(
                    icon = Icons.AutoMirrored.Sharp.Undo,
                    contentDescription = "Undo cut edit",
                    onClick = vm::undoCoreEdit,
                    enabled = state.coreUndoDepth > 0,
                    modifier = Modifier.testTag("cs_cut_undo")
                )
                CsIconKey(
                    icon = Icons.Sharp.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("cs_cut_settings")
                )
            }

            CsStatusLine(
                error = state.errorMessage,
                notice = state.statusNotice,
                onDismissError = vm::dismissError,
                onDismissNotice = vm::dismissNotice
            )

            // Real signature check including overlay content, B-roll media, and transitions
            val signature = session.cutSignature(defaultLook, state.savedOverlays)
            val cutCurrent = session.hasFinalVideo && session.lastCutSignature == signature

            // Pipeline progress strip + explicit stale/re-render guidance
            CutPipelineProgressStrip(
                hasTtsAudio = session.hasTtsAudio,
                hasTalkingHead = session.hasTalkingHead,
                hasFinalVideo = session.hasFinalVideo,
                cutCurrent = cutCurrent,
                unsyncedCount = unsynced.size,
                exported = state.exportState == ExportState.EXPORTED && cutCurrent
            )

            val dirtyReason: String? = when {
                unsynced.isNotEmpty() -> {
                    val unit = if (unsynced.size == 1) "line" else "lines"
                    "${unsynced.size} $unit edited — tap re-voice below to sync voice & video"
                }
                session.hasFinalVideo && !cutCurrent -> {
                    "Overlays, B-roll, or cut settings changed — tap render the video to apply"
                }
                session.hasTalkingHead && !session.hasFinalVideo -> {
                    "Talking head ready — customize overlays or B-roll, then tap render the video"
                }
                else -> null
            }
            if (dirtyReason != null) {
                val isWarning = unsynced.isNotEmpty() || (session.hasFinalVideo && !cutCurrent)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isWarning) Cs.Amber.copy(alpha = 0.14f) else Cs.Raised)
                        .border(
                            1.dp,
                            if (isWarning) Cs.Amber.copy(alpha = 0.45f) else Cs.Hairline,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("cs_cut_dirty_banner"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        imageVector = if (isWarning) Icons.Sharp.SyncProblem else Icons.Sharp.Movie,
                        contentDescription = null,
                        tint = if (isWarning) Cs.Amber else Cs.Ink,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = dirtyReason,
                        style = sans(12.sp, if (isWarning) Cs.Amber else Cs.Ink, 16.sp, FontWeight.Medium),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 206dp sunken #000 ExoPlayer monitor that visibly reshapes for 9:16 vs 16:9
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

            // Clean dark-graphite 2-row Cut Controls Deck (every button backed by real persisted state)
            val isVertical = opts.aspect == CoreRenderOptions.ASPECT_VERTICAL
            val gapCount = if (session.silenceGapsJobId == session.talkingHeadJobId) session.silenceGaps.size else 0
            val activeOverlayCount = (if (opts.showLowerThird) 1 else 0) +
                (if (opts.showBrandBug && opts.cornerBugText.isNotBlank()) 1 else 0) +
                session.selectedOverlayIds.size
            val brollCutawayCount = session.clauses.count { it.segmentMode == BrollSegmentMode.BROLL_CUTAWAY }
            val lookAccentColor = remember(defaultLook?.accentHex) { parseAccentHex(defaultLook?.accentHex) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 8.dp, end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CutToggleKey(
                        label = "Captions",
                        icon = Icons.Sharp.ClosedCaption,
                        active = opts.captions,
                        accent = HwLed.Blue,
                        onClick = { vm.setCoreCaptions(!opts.captions) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_captions")
                    )
                    CutToggleKey(
                        label = if (gapCount > 0) "Trim ($gapCount)" else "Trim",
                        icon = Icons.Sharp.ContentCut,
                        active = opts.stripSilence,
                        accent = HwLed.Orange,
                        onClick = { vm.setCoreStripSilence(!opts.stripSilence) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_strip_silence")
                    )
                    CutToggleKey(
                        label = "Auto Zoom",
                        icon = Icons.Sharp.ZoomIn,
                        active = opts.punchIn,
                        accent = HwLed.Green,
                        onClick = { vm.setCorePunchIn(!opts.punchIn) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_punch_in")
                    )
                    CutToggleKey(
                        label = if (isVertical) "9:16" else "16:9",
                        icon = if (isVertical) Icons.Sharp.CropPortrait else Icons.Sharp.CropLandscape,
                        active = true,
                        accent = if (isVertical) HwLed.Orange else HwLed.Blue,
                        onClick = {
                            vm.setCoreAspect(
                                if (isVertical) CoreRenderOptions.ASPECT_HORIZONTAL else CoreRenderOptions.ASPECT_VERTICAL
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_aspect")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CutStudioActionKey(
                        label = if (activeOverlayCount > 0) "Overlays ($activeOverlayCount)" else "Overlays",
                        icon = Icons.Sharp.Layers,
                        accent = lookAccentColor,
                        onClick = onOpenNewsroom,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_look")
                    )
                    CutStudioActionKey(
                        label = if (brollCutawayCount > 0) "B-Roll ($brollCutawayCount)" else "B-Roll & Cuts",
                        icon = Icons.Sharp.Movie,
                        accent = HwLed.Green,
                        onClick = onOpenBrollStudio,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_broll_studio")
                    )
                }
            }

            // Unified Sunken #000 Transcript / B-roll Workspace
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 10.dp, top = 8.dp, end = 10.dp)
                    .sunkenPanel(radius = 18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Color(0xFF0A0A0E))
                        .hairlineBottom(Cs.RowDivider),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CutWorkspaceTab(
                        label = "words",
                        icon = Icons.AutoMirrored.Sharp.Notes,
                        selected = mode == "transcript",
                        onClick = { mode = "transcript" },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_mode_transcript")
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(Cs.RowDivider)
                    )
                    CutWorkspaceTab(
                        label = "b-roll",
                        icon = Icons.Sharp.Movie,
                        selected = mode == "timeline",
                        onClick = { mode = "timeline" },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cs_cut_mode_timeline")
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (session.clauses.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No story loaded yet. Go to 01 SPARK and tap make the video.",
                                style = sans(15.sp, Cs.Muted, 21.sp)
                            )
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

            // Single sequential primary pipeline dock key
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

            CsDock(
                primaryLabel = dockSpec.label,
                primaryLeadingIcon = dockSpec.icon,
                primaryRole = dockSpec.role,
                primaryEnabled = dockSpec.enabled && !isCutBusy,
                primaryTag = "cs_cut_primary",
                onPrimaryClick = dockSpec.onClick,
                isBusy = isCutBusy,
                busyLabel = busyLabel,
                busyFraction = busyFraction
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

private data class CutPrimarySpec(
    val label: String,
    val icon: ImageVector,
    val role: KeyRole,
    val enabled: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun CutWorkspaceTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) Cs.White else Cs.Muted
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clickable(role = Role.Tab, onClick = onClick)
            .drawBehind {
                if (selected) {
                    val barW = (size.width * 0.42f).coerceIn(36.dp.toPx(), 72.dp.toPx())
                    val barH = 2.dp.toPx()
                    drawRect(
                        color = Cs.InkBright,
                        topLeft = Offset((size.width - barW) / 2f, size.height - barH),
                        size = Size(barW, barH)
                    )
                }
            }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Text(
            text = label,
            style = mono(12.5.sp, tint, if (selected) FontWeight.SemiBold else FontWeight.Normal),
            maxLines = 1
        )
    }
}

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
        !hasClauses -> CutPrimarySpec(
            label = "voice the script",
            icon = Icons.Sharp.RecordVoiceOver,
            role = KeyRole.COMMIT,
            enabled = false,
            onClick = {}
        )
        !hasTtsAudio -> CutPrimarySpec(
            label = "voice the script",
            icon = Icons.Sharp.RecordVoiceOver,
            role = KeyRole.COMMIT,
            enabled = true,
            onClick = vm::runCoreStage1Tts
        )
        unsyncedCount > 0 -> CutPrimarySpec(
            label = "re-voice $unsyncedCount edited line${if (unsyncedCount == 1) "" else "s"}",
            icon = Icons.Sharp.Sync,
            role = KeyRole.REPAIR,
            enabled = true,
            onClick = vm::resyncEditedVoice
        )
        !hasTalkingHead -> CutPrimarySpec(
            label = "render talking head",
            icon = Icons.Sharp.Person,
            role = KeyRole.COMMIT,
            enabled = true,
            onClick = vm::runCoreStage3SpeechToHead
        )
        !cutCurrent -> CutPrimarySpec(
            label = "render the video",
            icon = Icons.Sharp.Movie,
            role = KeyRole.COMMIT,
            enabled = true,
            onClick = { vm.runCoreStage4ChopBroll() }
        )
        exportState != ExportState.EXPORTED -> CutPrimarySpec(
            label = "export & share",
            icon = Icons.Sharp.Share,
            role = KeyRole.COMMIT,
            enabled = clip != null,
            onClick = { clip?.let { vm.exportClip(it, openShareSheet = true) } }
        )
        else -> CutPrimarySpec(
            label = "share again",
            icon = Icons.Sharp.Share,
            role = KeyRole.STD,
            enabled = clip != null,
            onClick = { clip?.let { vm.exportClip(it, openShareSheet = true) } }
        )
    }
}

@Composable
private fun CutPipelineProgressStrip(
    hasTtsAudio: Boolean,
    hasTalkingHead: Boolean,
    hasFinalVideo: Boolean,
    cutCurrent: Boolean,
    unsyncedCount: Int,
    exported: Boolean
) {
    val steps = listOf(
        Triple("1.Voice", hasTtsAudio && unsyncedCount == 0, unsyncedCount > 0),
        Triple("2.Head", hasTalkingHead && unsyncedCount == 0, hasTalkingHead && unsyncedCount > 0),
        Triple("3.Cut", cutCurrent, hasFinalVideo && !cutCurrent),
        Triple("4.Export", exported, false)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 6.dp)
            .sunkenPanel(radius = 12.dp, fill = Cs.SunkenTrack)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("cs_cut_pipeline_strip"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEach { (label, done, stale) ->
            val tint = when {
                stale -> Cs.Amber
                done -> HwLed.Green
                else -> Cs.Dim
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = when {
                        stale -> Icons.Sharp.SyncProblem
                        done -> Icons.Sharp.CheckCircle
                        else -> Icons.Sharp.ChevronRight
                    },
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = label,
                    style = mono(10.5.sp, tint, if (done || stale) FontWeight.SemiBold else FontWeight.Normal),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun CutToggleKey(
    label: String,
    icon: ImageVector,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val role = if (active) KeyRole.ACTIVE else KeyRole.STD
    val tint = if (active) Cs.White else Cs.Muted
    Column(
        modifier = modifier
            .height(52.dp)
            .neumorphicSurface(
                role = role,
                pressed = false,
                radius = 13.dp,
                enabled = true,
                accentBarColor = if (active) accent else null
            )
            .clip(RoundedCornerShape(13.dp))
            .clickable(role = Role.Switch, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (active) {
                CsLedDot(color = accent, size = 5.dp)
            }
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) Cs.White else Cs.Muted,
                modifier = Modifier.size(17.dp)
            )
        }
        Text(
            text = label,
            style = mono(10.5.sp, tint, if (active) FontWeight.SemiBold else FontWeight.Normal),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CutStudioActionKey(
    label: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .neumorphicSurface(
                role = KeyRole.STD,
                pressed = false,
                radius = 13.dp,
                enabled = true,
                accentBarColor = accent
            )
            .clip(RoundedCornerShape(13.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(17.dp)
        )
        Text(
            text = label,
            style = mono(11.5.sp, Cs.Ink, FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Sharp.ChevronRight,
            contentDescription = null,
            tint = Cs.Muted,
            modifier = Modifier.size(16.dp)
        )
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .height(206.dp)
            .sunkenPanel(radius = 18.dp)
            .clickable(enabled = sourceUri != null, role = Role.Button, onClick = onTogglePlay)
            .testTag("cs_cut_monitor"),
        contentAlignment = Alignment.Center
    ) {
        // Aspect-accurate inner frame so switching 9:16 <-> 16:9 visibly reshapes the preview immediately.
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
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0A0A0F))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
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
                    update = { view ->
                        if (view.player !== player) {
                            view.player = player
                        }
                    },
                    onRelease = { view ->
                        view.player = null
                    },
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
                        val strokeW = (step * 0.56f).coerceIn(1.5.dp.toPx(), 4.dp.toPx())
                        val centerY = size.height / 2f
                        waveformPeaks.forEachIndexed { idx, rawPeak ->
                            val frac = idx.toFloat() / count.coerceAtLeast(1).toFloat()
                            val barH = (size.height * (0.14f + 0.82f * rawPeak.coerceIn(0f, 1f)))
                            val x = idx * step + step / 2f
                            drawLine(
                                color = if (frac <= progressFraction) Cs.Red else Color.White.copy(alpha = 0.34f),
                                start = Offset(x, centerY - barH / 2f),
                                end = Offset(x, centerY + barH / 2f),
                                strokeWidth = strokeW,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            }

            // Live caption positioned cleanly inside the active aspect frame
            if (liveCaption.isNotBlank()) {
                Text(
                    text = liveCaption,
                    style = sans(12.5.sp, Cs.White, 17.sp, FontWeight.Medium),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 8.dp, end = 8.dp, bottom = 10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("cs_cut_live_caption")
                )
            }
        }

        // Top-left timecode (`m:ss / m:ss`)
        Text(
            text = "${mmss(positionMs)} / ${mmss(durationMs)}",
            style = mono(11.sp, Cs.Muted),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 10.dp)
                .testTag("cs_cut_timecode")
        )

        // Top-right single status label (aspect + voice readiness)
        val topRightLabel = buildString {
            if (sourceUri != null && !sourceHasVideo) append("VOICE · ")
            append(if (isVertical) "9:16" else "16:9")
            if (punchIn) append(" · ZOOM")
        }
        Text(
            text = topRightLabel,
            style = mono(10.sp, if (sourceUri != null && !sourceHasVideo) HwLed.Green else Cs.Muted, FontWeight.Medium),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 14.dp, top = 10.dp)
                .testTag("cs_cut_monitor_mode")
        )

        // Centered 56dp circular Play/Pause button (auto-hides during active video playback so face stays clear)
        if (!isPlaying || !sourceHasVideo) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Cs.Raised.copy(alpha = 0.88f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .clickable(
                        enabled = sourceUri != null,
                        role = Role.Button,
                        onClick = onTogglePlay
                    )
                    .testTag("cs_cut_play"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Sharp.Pause else Icons.Sharp.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (sourceUri != null) Cs.Ink else Cs.Dim,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // 3dp progress bar (#D71921 over #2C2C32)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 18.dp, end = 18.dp, bottom = 5.dp)
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Cs.Hairline)
        ) {
            if (progressFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressFraction)
                        .background(Cs.Red)
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
                    .background(if (isActive) Cs.ActiveClauseBg else Color.Transparent)
                    .drawBehind {
                        if (dirty) {
                            drawRect(
                                color = Cs.Amber,
                                topLeft = Offset.Zero,
                                size = Size(2.dp.toPx(), size.height)
                            )
                        }
                        drawLine(
                            color = Cs.ClauseDivider,
                            start = Offset(0f, size.height - 0.5f),
                            end = Offset(size.width, size.height - 0.5f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = mmss(clause.startMs),
                        style = mono(11.sp, Cs.Muted).copy(lineHeight = 25.sp),
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
                        Icon(
                            imageVector = Icons.Sharp.SyncProblem,
                            contentDescription = null,
                            tint = Cs.Amber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (broll) "edited · voice not synced · b-roll" else "edited · voice not synced",
                            style = mono(12.sp, Cs.Amber),
                            maxLines = 1
                        )
                    }
                } else if (broll && editingClause != idx) {
                    Row(
                        modifier = Modifier
                            .padding(start = 52.dp, top = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Cs.Track)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Sharp.Movie,
                            contentDescription = null,
                            tint = Cs.Ink,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("B-ROLL", style = mono(11.sp, Cs.Ink), maxLines = 1)
                    }
                }

                if (isOpen && editingClause != idx) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 52.dp, top = 10.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoEditOledKey(
                            label = "edit words",
                            icon = Icons.Sharp.Edit,
                            active = dirty,
                            ledColor = HwLed.Orange,
                            onClick = { onEdit(idx) },
                            height = 44.dp,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cs_clause_edit_btn_$idx")
                        )

                        VideoEditOledKey(
                            label = if (broll) "back to face" else "b-roll here",
                            icon = Icons.Sharp.Movie,
                            active = broll,
                            ledColor = HwLed.Green,
                            onClick = { vm.toggleCutClauseBroll(idx) },
                            height = 44.dp,
                            fontSize = 12.sp,
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

    data class WordSpanRange(val charRange: IntRange, val active: Boolean)

    val built: Pair<AnnotatedString, IntRange?> = remember(spokenText, words, positionMs, clauseStartMs, dirty) {
        if (words.isEmpty()) {
            val color = if (dirty || positionMs >= clauseStartMs) Cs.Ink else Cs.Muted
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
                    pushStyle(SpanStyle(color = if (spoken) Cs.Ink else Cs.Muted))
                    append(w.word)
                    pop()
                    val endCharExcl = length
                    if (isCurrent && endCharExcl > startChar) {
                        activeRange = startChar until endCharExcl
                    }
                    if (idx < words.lastIndex) append(" ")
                }
            }
            str to activeRange
        }
    }

    val activeRange = built.second
    Text(
        text = built.first,
        style = sans(17.sp, Cs.Ink, 25.sp),
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
                drawLine(Cs.Red, Offset(a.left, y), Offset(b.right, y), 2.dp.toPx())
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
    val fieldState = rememberTextFieldState(initialText = initialText)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BasicTextField(
            state = fieldState,
            textStyle = sans(17.sp, Cs.Ink, 25.sp),
            cursorBrush = SolidColor(Cs.Ink),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 5),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Cs.Body)
                .border(1.dp, Cs.Amber, RoundedCornerShape(12.dp))
                .padding(10.dp)
                .testTag("cs_clause_editor_$idx")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoEditOledKey(
                label = "save words",
                active = fieldState.text.toString().isNotBlank(),
                ledColor = HwLed.Green,
                onClick = { onSave(fieldState.text.toString()) },
                enabled = fieldState.text.toString().isNotBlank(),
                height = 44.dp,
                modifier = Modifier.testTag("cs_clause_apply_$idx")
            )
            CsKeyLabel(
                label = "cancel",
                onClick = onCancel,
                role = KeyRole.STD,
                radius = 12.dp,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("cs_clause_cancel_$idx")
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
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item(key = "broll_studio_banner") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HwLed.Green.copy(alpha = 0.12f))
                    .border(1.dp, HwLed.Green.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onOpenBrollStudio)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("cs_open_broll_studio_inline"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Sharp.Movie,
                    contentDescription = null,
                    tint = HwLed.Green,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Open B-Roll & Transition Studio",
                    style = sans(13.5.sp, Cs.Ink, 18.sp, FontWeight.Medium),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Sharp.ChevronRight,
                    contentDescription = null,
                    tint = HwLed.Green,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        itemsIndexed(clauses, key = { _, c -> c.id }) { idx, clause ->
            val broll = clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
            val transition = ClipTransitionType.fromWire(clause.transitionType)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cs.BrollRow)
                    .clickable(role = Role.Button) {
                        vm.playerController.seekTo(clause.startMs)
                        onSeekLocal(clause.startMs)
                    }
                    .padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = mmss(clause.startMs),
                    style = mono(11.sp, Cs.Muted),
                    maxLines = 1,
                    modifier = Modifier.width(40.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = clause.spokenText,
                        style = sans(14.sp, Cs.Ink, 19.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (idx > 0 && transition != ClipTransitionType.CUT) {
                        Text(
                            text = "Transition: ${transition.label} (${clause.transitionDurationMs}ms)",
                            style = mono(10.5.sp, HwLed.Blue),
                            maxLines = 1
                        )
                    }
                }
                VideoEditOledKey(
                    label = if (broll) "b-roll" else "face",
                    icon = if (broll) Icons.Sharp.Movie else Icons.Sharp.Person,
                    active = broll,
                    ledColor = HwLed.Green,
                    onClick = { vm.toggleCutClauseBroll(idx) },
                    height = 44.dp,
                    fontSize = 11.5.sp,
                    modifier = Modifier.testTag("cs_timeline_toggle_$idx")
                )
            }
        }
    }
}
