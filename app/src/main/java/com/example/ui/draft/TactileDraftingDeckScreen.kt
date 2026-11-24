package com.example.ui.draft

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.draft.CandidateDraft
import com.example.draft.DraftStateReducer
import com.example.draft.Stage
import com.example.ui.components.StudioErrorBanner
import com.example.ui.theme.AcidVoltLime
import com.example.ui.theme.AcidVoltLimeDim
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.ConsoleObsidian
import com.example.ui.theme.GraphiteHairline
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.OledVoid
import com.example.ui.theme.OnAirCrimson
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMuted

private enum class PendingMicAction {
    LATCH_SEED,
    STEER_CLUTCH,
    VOICE_SPLICE
}

private val FooterMonoStyle = TextStyle(
    fontFamily = JetBrainsMonoFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontFeatureSettings = "tnum"
)

/**
 * Single non-scrolling 100% viewport height Tactile Plain-Text Drafting Engine.
 *
 * Three-band vertical allocation:
 * - Top Telemetry Strip: 36dp fixed tabular monospace stepper, v1|v2|v3 toggle, live /280 counter,
 *   and concurrent multi-draft session switcher pill.
 * - Upper Eye Zone: 56% viewport height, active plain-text canvas never obscured by hand
 *   (pull-down on 01 SEED or Back spins up a new concurrent subject card asynchronously).
 * - Divider Scrubber Rule: 24dp touch target / 1dp hairline that transforms into a stretched
 *   iridescent Siri-style light ribbon while any AI pass is thinking.
 * - Lower Thumb Zone: 44% viewport height, stage instrument bay + permanent bottom-right [Raw Kbd].
 * - Full-viewport 2dp high-contrast solid border while live voice capture is active.
 */
@Composable
fun TactileDraftingDeckScreen(
    uiState: DraftDeckUiState,
    viewModel: DraftDeckViewModel,
    onOpenSettings: () -> Unit,
    onBroadcastClip: (plainText: String, headline: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val doc = uiState.document
    val stage = doc.stage

    var pendingMicAction by remember { mutableStateOf<PendingMicAction?>(null) }

    // Hardware/gesture Back: close switcher -> step back stage -> or drop active seed into live
    // async session stack and spin up a fresh subject card!
    val interceptBack = uiState.isSessionSwitcherOpen ||
        stage != Stage.SEED ||
        doc.seedTranscript.isNotBlank() ||
        uiState.isBusy
    BackHandler(enabled = interceptBack) {
        when {
            uiState.isSessionSwitcherOpen -> viewModel.setSessionSwitcherOpen(false)
            stage == Stage.SHIP -> viewModel.setStage(Stage.CUT)
            stage == Stage.CUT -> viewModel.setStage(Stage.PICK)
            stage == Stage.PICK -> viewModel.setStage(Stage.SEED)
            else -> viewModel.spinUpNewDraftSession()
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted && action != null) {
            when (action) {
                PendingMicAction.LATCH_SEED -> viewModel.toggleLatchMic()
                PendingMicAction.STEER_CLUTCH -> viewModel.startSteerClutch()
                PendingMicAction.VOICE_SPLICE -> viewModel.startVoiceSplice()
            }
        }
    }

    fun runWithMicPermission(action: PendingMicAction) {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            when (action) {
                PendingMicAction.LATCH_SEED -> viewModel.toggleLatchMic()
                PendingMicAction.STEER_CLUTCH -> viewModel.startSteerClutch()
                PendingMicAction.VOICE_SPLICE -> viewModel.startVoiceSplice()
            }
        } else {
            pendingMicAction = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val activeCandidate: CandidateDraft = doc.activeCandidate
        ?: remember(doc.seedTranscript) {
            DraftStateReducer.createCandidateDraft(
                postureLabel = "DIRECT",
                text = doc.seedTranscript
            )
        }

    val brushMicroStrip: BrushMicroStripState? = remember(
        uiState.activeBrushRange,
        uiState.brushAlternatives,
        uiState.brushActiveAltIndex
    ) {
        val range = uiState.activeBrushRange
        if (range != null && uiState.brushAlternatives.isNotEmpty()) {
            BrushMicroStripState(
                charRange = range,
                alternatives = uiState.brushAlternatives,
                activeIndex = uiState.brushActiveAltIndex
            )
        } else {
            null
        }
    }

    val activeSessionIndex = uiState.sessions.indexOfFirst { it.id == doc.id }.coerceAtLeast(0)

    val recordingPerimeterModifier = if (uiState.isAnyRecording) {
        Modifier.border(width = 2.dp, color = OnAirCrimson)
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid)
            .windowInsetsPadding(WindowInsets.systemBars)
            .then(recordingPerimeterModifier)
            .testTag("tactile_drafting_deck_root")
    ) {
        // 1. Top Telemetry Strip (36dp fixed height, tabular monospace)
        TopTelemetryStrip(
            currentStage = stage,
            candidatesCount = doc.candidates.size,
            activeCandidateIndex = doc.activeCandidateIndex,
            charCount = uiState.activeCharCount,
            isSplitActive = activeCandidate.linkedSplitCards.size >= 2,
            isRigOnline = uiState.lastSeedRigOnline,
            sessionCount = uiState.sessions.size.coerceAtLeast(1),
            activeSessionIndex = activeSessionIndex,
            hasBackgroundThinking = uiState.hasAnyBackgroundThinking,
            onSelectStage = viewModel::setStage,
            onSelectCandidateSlot = viewModel::selectCandidateSlot,
            onSplitOverflow = viewModel::splitOverflowThread,
            onToggleSessionSwitcher = viewModel::toggleSessionSwitcher,
            onOpenSettings = onOpenSettings
        )

        // Instant Async Multi-Draft Session Switcher Strip
        AnimatedVisibility(
            visible = uiState.isSessionSwitcherOpen && uiState.sessions.size > 1,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ConsoleObsidian)
                    .border(width = 0.5.dp, color = GraphiteHairline)
                    .padding(vertical = 6.dp)
                    .testTag("session_switcher_sheet"),
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(uiState.sessions, key = { _, item -> item.id }) { idx, session ->
                    val isCurrent = session.id == doc.id
                    Row(
                        modifier = Modifier
                            .widthIn(min = 96.dp, max = 180.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isCurrent) AcidVoltLimeDim else ConsoleElevated)
                            .border(
                                1.dp,
                                when {
                                    isCurrent -> AcidVoltLime
                                    session.isThinking -> TelemetryCyan
                                    else -> GraphiteHairline
                                },
                                RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                viewModel.switchToSession(session.id)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("session_card_$idx"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (session.isThinking) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(TelemetryCyan)
                            )
                        }
                        Text(
                            text = session.title,
                            style = FooterMonoStyle,
                            color = if (isCurrent) AcidVoltLime else StudioCrisp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = session.stage.code,
                            style = FooterMonoStyle,
                            color = TelemetryMuted,
                            maxLines = 1
                        )
                    }
                }

                item(key = "session_new_btn") {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ConsoleElevated)
                            .border(1.dp, GraphiteHairline, RoundedCornerShape(6.dp))
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                viewModel.spinUpNewDraftSession()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("session_new_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "New draft",
                            tint = AcidVoltLime,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "New",
                            style = FooterMonoStyle,
                            color = StudioCrisp,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        if (!uiState.errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                StudioErrorBanner(
                    message = uiState.errorMessage,
                    onDismiss = viewModel::dismissError
                )
            }
        }

        // 2. Upper Eye Zone (56% viewport height) + Divider (24dp) + Lower Thumb Zone (44% height)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .weight(0.56f)
                    .fillMaxWidth()
                    .background(OledVoid)
                    .testTag("upper_eye_zone")
            ) {
                when (stage) {
                    Stage.SEED -> {
                        SeedEyeZone(
                            seedTranscript = doc.seedTranscript,
                            steeringConstraints = doc.steeringConstraints,
                            rawKeyboardMode = uiState.rawKeyboardMode,
                            isRecordingSeed = uiState.isRecordingSeed,
                            isRecordingSteer = uiState.isRecordingSteer,
                            recordingElapsedMs = uiState.recordingElapsedMs,
                            liveRmsLevel = uiState.liveRmsLevel,
                            isBusy = uiState.isBusy,
                            busyLabel = uiState.busyLabel,
                            onUpdateSeedTranscript = viewModel::updateSeedTranscriptManual,
                            onCommitRawSeed = {
                                viewModel.toggleRawKeyboardMode()
                            },
                            onToggleRawKeyboardMode = viewModel::toggleRawKeyboardMode,
                            onRemoveSteeringConstraint = viewModel::removeSteeringConstraint,
                            onRefreshAngles = viewModel::refreshAnglesFromSeed,
                            onAiRewriteSeed = viewModel::aiRewriteCurrentInput,
                            onPullDownNewDraft = viewModel::spinUpNewDraftSession,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Stage.PICK -> {
                        PickEyeZone(
                            candidates = doc.candidates,
                            activeCandidateIndex = doc.activeCandidateIndex,
                            salvagedPhrases = doc.salvagedPhrases,
                            isBusy = uiState.isBusy,
                            busyLabel = uiState.busyLabel,
                            onSelectCandidateSlot = viewModel::selectCandidateSlot,
                            onSalvageClause = viewModel::salvageClauseFromCandidate,
                            onKillAndRegenSlot = viewModel::killAndRegenSingleSlot,
                            onCommitToCut = viewModel::commitCandidateToCut,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Stage.CUT -> {
                        CutSurgeryEyeZone(
                            candidate = activeCandidate,
                            focusedClauseId = uiState.focusedClauseId,
                            focusedGapIndex = uiState.focusedGapIndex,
                            rawKeyboardMode = uiState.rawKeyboardMode,
                            isBusy = uiState.isBusy,
                            brushMicroStrip = brushMicroStrip,
                            flashRange = uiState.flashRange,
                            activeAttachment = doc.attachment,
                            onFocusClause = viewModel::focusClause,
                            onFocusGap = viewModel::focusGap,
                            onToggleWordLock = viewModel::toggleWordLock,
                            onBrushSelectRelease = viewModel::performBrushSwap,
                            onSelectBrushAlternative = viewModel::selectBrushAlternative,
                            onDismissBrushStrip = viewModel::dismissBrushStrip,
                            onStrikeCutClause = viewModel::strikeCutClause,
                            onReorderClause = viewModel::reorderClause,
                            onGlobalScrubUndo = viewModel::stepGlobalSnapshot,
                            onCommitRawKeyboardEdit = viewModel::commitRawKeyboardEdit,
                            onExitRawKeyboardMode = {
                                if (uiState.rawKeyboardMode) viewModel.toggleRawKeyboardMode()
                            },
                            onAiRewriteRaw = viewModel::aiRewriteCurrentInput,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Stage.SHIP -> {
                        ShipEyeZone(
                            candidate = doc.activeCandidate,
                            fallbackSeedText = doc.seedTranscript,
                            activeAttachment = doc.attachment,
                            onClearAttachment = viewModel::clearAttachment,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // 3. Divider Scrubber Rule (24dp touch target, 1dp hairline -> stretched Siri iridescent glow while thinking)
            DensityScrubberRule(
                active = stage == Stage.CUT && !uiState.rawKeyboardMode,
                currentDeltaPercent = uiState.densityDeltaPercent,
                isApplying = uiState.isBusy && uiState.densityDeltaPercent != 0,
                isThinking = uiState.isBusy,
                onDeltaPreview = viewModel::previewDensityDelta,
                onDeltaCommit = viewModel::commitDensityScrub
            )

            // 4. Lower Thumb Zone (44% viewport height minus top strip)
            Column(
                modifier = Modifier
                    .weight(0.44f)
                    .fillMaxWidth()
                    .background(ConsoleObsidian)
                    .testTag("lower_thumb_zone")
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (stage) {
                        Stage.SEED -> {
                            SeedThumbZone(
                                extractedAngles = doc.extractedAngles,
                                blindspotAngles = doc.blindspotAngles,
                                canSynthesize = doc.seedTranscript.isNotBlank() && !uiState.isBusy,
                                isRecordingSeed = uiState.isRecordingSeed,
                                isRecordingSteer = uiState.isRecordingSteer,
                                recordingElapsedMs = uiState.recordingElapsedMs,
                                isBusy = uiState.isBusy,
                                onToggleAngleChip = viewModel::toggleAngleChip,
                                onToggleLatchMic = {
                                    if (uiState.isRecordingSeed) {
                                        viewModel.toggleLatchMic()
                                    } else {
                                        runWithMicPermission(PendingMicAction.LATCH_SEED)
                                    }
                                },
                                onDistillLast15Seconds = viewModel::distillLast15Seconds,
                                onStartSteerClutch = { runWithMicPermission(PendingMicAction.STEER_CLUTCH) },
                                onStopSteerClutch = viewModel::stopSteerClutch,
                                onSynthesizeThree = viewModel::synthesizeThreeCandidates,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Stage.PICK -> {
                            PickThumbZone(
                                salvagedPhrases = doc.salvagedPhrases,
                                activeCandidateIndex = doc.activeCandidateIndex,
                                canCommit = doc.candidates.isNotEmpty() && !uiState.isBusy,
                                isBusy = uiState.isBusy,
                                onRemoveSalvagedPhrase = viewModel::removeSalvagedPhrase,
                                onRegenAll = viewModel::regenAllCandidates,
                                onCommitToCut = { viewModel.commitCandidateToCut(doc.activeCandidateIndex) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Stage.CUT -> {
                            CutThumbInstrumentBay(
                                candidate = activeCandidate,
                                focusedClauseId = uiState.focusedClauseId,
                                focusedGapIndex = uiState.focusedGapIndex,
                                salvagedPhrases = doc.salvagedPhrases,
                                isVoiceSplicing = uiState.isRecordingSplice,
                                isBusy = uiState.isBusy,
                                onStepClauseVersion = viewModel::stepClauseVersion,
                                onSpliceSalvagePhrase = viewModel::spliceSalvagePhrase,
                                onStepGlobalSnapshot = viewModel::stepGlobalSnapshot,
                                onStartVoiceSplice = { runWithMicPermission(PendingMicAction.VOICE_SPLICE) },
                                onStopVoiceSplice = viewModel::stopVoiceSplice,
                                onAdvanceToShip = { viewModel.setStage(Stage.SHIP) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Stage.SHIP -> {
                            ShipRadarDrawer(
                                graphMatches = uiState.graphMatches,
                                graphContactCount = uiState.graphContactCount,
                                isExpanded = uiState.isRadarExpanded,
                                isImportingArchive = uiState.isImportingArchive,
                                activeAttachment = doc.attachment,
                                canShip = uiState.activePlainText.isNotBlank() && !uiState.isBusy,
                                isBusy = uiState.isBusy,
                                onSetRadarExpanded = viewModel::setRadarExpanded,
                                onImportXArchiveUri = viewModel::importXArchiveUri,
                                onToggleQuote = viewModel::toggleQuoteAttachment,
                                onToggleReply = viewModel::toggleReplyAttachment,
                                onWeaveMention = viewModel::weaveMention,
                                onMuteMatch = viewModel::muteGraphMatch,
                                onOpenInX = { viewModel.openInX(context) },
                                onCopySanitizedAscii = { viewModel.copySanitizedAscii(context) },
                                onBroadcastClip = {
                                    val draft = viewModel.currentDraftPlainText()
                                    if (draft.isNotBlank()) {
                                        val headline = draft
                                            .split('.', '!', '?')
                                            .firstOrNull()
                                            .orEmpty()
                                            .trim()
                                            .take(36)
                                            .uppercase()
                                            .ifBlank { "BREAKING NEWS" }
                                        onBroadcastClip(draft, headline)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // Permanent bottom utility rail: quiet status/radar cue on left + anchored [Raw Kbd] escape hatch on bottom-right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(OledVoid)
                        .border(width = 0.5.dp, color = GraphiteHairline)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val leftLabel = when {
                        !uiState.statusNotice.isNullOrBlank() -> uiState.statusNotice
                        !uiState.busyLabel.isNullOrBlank() -> uiState.busyLabel
                        stage == Stage.CUT -> "Graph matches (${uiState.graphMatches.size}) ^"
                        else -> ""
                    }
                    Text(
                        text = leftLabel,
                        style = FooterMonoStyle,
                        color = if (stage == Stage.CUT && uiState.statusNotice.isNullOrBlank() && uiState.busyLabel.isNullOrBlank()) {
                            StudioCrisp
                        } else {
                            TelemetryMuted
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .let { base ->
                                if (stage == Stage.CUT && uiState.statusNotice.isNullOrBlank()) {
                                    base
                                        .clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            viewModel.setStage(Stage.SHIP)
                                            viewModel.setRadarExpanded(true)
                                        }
                                        .testTag("cut_radar_drawer_peek")
                                } else if (!uiState.statusNotice.isNullOrBlank()) {
                                    base.clickable(onClick = viewModel::dismissNotice)
                                } else {
                                    base
                                }
                            }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val rawActive = uiState.rawKeyboardMode
                    Row(
                        modifier = Modifier
                            .height(26.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (rawActive) AcidVoltLimeDim else ConsoleElevated)
                            .border(
                                width = 1.dp,
                                color = if (rawActive) AcidVoltLime else GraphiteHairline,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                if (stage == Stage.PICK || stage == Stage.SHIP) {
                                    viewModel.setStage(Stage.CUT)
                                }
                                viewModel.toggleRawKeyboardMode()
                            }
                            .padding(horizontal = 8.dp)
                            .testTag("raw_kbd_toggle_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (rawActive) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
                            contentDescription = "Toggle raw keyboard",
                            tint = if (rawActive) AcidVoltLime else StudioCrisp,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Raw Kbd",
                            style = FooterMonoStyle,
                            color = if (rawActive) AcidVoltLime else StudioCrisp
                        )
                    }
                }
            }
        }
    }
}
