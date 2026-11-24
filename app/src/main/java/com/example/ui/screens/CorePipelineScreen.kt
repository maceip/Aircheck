package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.BrollSegmentMode
import com.example.data.ClipRecord
import com.example.data.CorePipelineStage
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioErrorBanner
import com.example.ui.components.TelemetryWaveform
import com.example.ui.components.studioFieldColors
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LiveTallyGreen
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TelemetryMuted
import com.example.ui.theme.TelemetryTimecodeStyle

/**
 * Single Canonical 5-Stage Aircheck Core Story Pipeline:
 * 1. `01 TTS` -> Synthesize or record spoken voice WAV for the story.
 * 2. `02 STORY` -> Audition TTS, edit/split/add/remove clauses, and re-run TTS until locked.
 * 3. `03 HEAD` -> Match the locked TTS WAV to a clean 9:16 talking-head video.
 * 4. `04 B-ROLL` -> Chop the speech-matched talking head into B-roll cutaway windows
 *    (plus 0 or more optional `SavedNewsroomOverlay` objects for news briefs).
 * 5. `05 REVIEW` -> Unobstructed 9:16 final playback, Export to Photos, and Share.
 */
@Composable
fun CorePipelineScreen(
    uiState: StudioUiState,
    viewModel: StudioViewModel,
    onBack: () -> Unit,
    onOpenOptionalOverlayBuilder: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val tokens = LocalAircheckTokens.current
    val session = uiState.corePipelineSession
    val activeStage = session.stage

    var rawStoryDraft by remember(session.id, session.clauses.size) {
        mutableStateOf(session.fullStoryText)
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startCoreVoiceoverRecording()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledVoid)
            .windowInsetsPadding(WindowInsets.systemBars)
            .testTag("core_pipeline_screen_root"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header (1 primary title + 1 stage status label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("core_pipeline_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = StudioCrisp
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Core Story Pipeline",
                style = MaterialTheme.typography.titleMedium,
                color = StudioCrisp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Stage ${activeStage.stepNumber}/5",
                style = TelemetryTimecodeStyle,
                color = tokens.channelAccent,
                modifier = Modifier.testTag("core_pipeline_stage_indicator")
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(tokens.hairline)
        )

        // 2-per-row Stage Switcher (strictly <= 2 buttons per row, 1 label per button)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PipelineStageTabButton(
                    stage = CorePipelineStage.TTS,
                    selected = activeStage == CorePipelineStage.TTS,
                    onClick = { viewModel.selectCoreStage(CorePipelineStage.TTS) },
                    modifier = Modifier.weight(1f)
                )
                PipelineStageTabButton(
                    stage = CorePipelineStage.ITERATE_STORY,
                    selected = activeStage == CorePipelineStage.ITERATE_STORY,
                    onClick = { viewModel.selectCoreStage(CorePipelineStage.ITERATE_STORY) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PipelineStageTabButton(
                    stage = CorePipelineStage.SPEECH_TO_TALKING_HEAD,
                    selected = activeStage == CorePipelineStage.SPEECH_TO_TALKING_HEAD,
                    onClick = { viewModel.selectCoreStage(CorePipelineStage.SPEECH_TO_TALKING_HEAD) },
                    modifier = Modifier.weight(1f)
                )
                PipelineStageTabButton(
                    stage = CorePipelineStage.CHOP_INTO_BROLL,
                    selected = activeStage == CorePipelineStage.CHOP_INTO_BROLL,
                    onClick = { viewModel.selectCoreStage(CorePipelineStage.CHOP_INTO_BROLL) },
                    modifier = Modifier.weight(1f)
                )
            }
            PipelineStageTabButton(
                stage = CorePipelineStage.FINAL_REVIEW,
                selected = activeStage == CorePipelineStage.FINAL_REVIEW,
                onClick = { viewModel.selectCoreStage(CorePipelineStage.FINAL_REVIEW) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (activeStage == CorePipelineStage.FINAL_REVIEW) {
            val clipId = session.finalClipId.orEmpty()
            val clipFlow = remember(clipId) { viewModel.observeClip(clipId) }
            val clip: ClipRecord? by clipFlow.collectAsStateWithLifecycle(initialValue = null)
            ClipReviewScreen(
                clip = clip,
                exportState = uiState.exportState,
                errorMessage = uiState.errorMessage,
                casStore = viewModel.casStore,
                playerController = viewModel.playerController,
                onBack = { viewModel.selectCoreStage(CorePipelineStage.CHOP_INTO_BROLL) },
                onExport = { targetClip, share ->
                    viewModel.exportClip(targetClip, openShareSheet = share)
                },
                onDismissError = viewModel::dismissError,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .testTag("core_pipeline_lazy_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val err = uiState.errorMessage
                if (!err.isNullOrBlank()) {
                    item(key = "core_error_banner") {
                        StudioErrorBanner(
                            message = err,
                            onDismiss = viewModel::dismissError
                        )
                    }
                }

                when (activeStage) {
                    CorePipelineStage.TTS -> {
                        item(key = "stage_01_tts_card") {
                            StudioCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "01 · TTS Voice Pass",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = StudioCrisp
                                        )
                                        Text(
                                            text = if (session.hasTtsAudio) {
                                                "Pass #${session.ttsIterationCount}"
                                            } else {
                                                "Awaiting TTS"
                                            },
                                            style = TelemetryTimecodeStyle,
                                            color = if (session.hasTtsAudio) LiveTallyGreen else TelemetryMuted
                                        )
                                    }

                                    OutlinedTextField(
                                        value = rawStoryDraft,
                                        onValueChange = { updated ->
                                            rawStoryDraft = updated
                                            viewModel.updateCoreStoryRawText(updated)
                                        },
                                        label = { Text("Spoken story text") },
                                        minLines = 4,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                                        colors = studioFieldColors(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("core_tts_story_input")
                                    )

                                    if (uiState.isCoreStageBusy) {
                                        LinearProgressIndicator(
                                            progress = { uiState.coreStageProgress.coerceIn(0.08f, 1f) },
                                            color = tokens.channelAccent,
                                            trackColor = tokens.hairline,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            text = uiState.coreStageStatusLabel ?: "Synthesizing…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TelemetryCyan
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.runCoreStage1Tts() },
                                            enabled = !uiState.isCoreStageBusy && rawStoryDraft.isNotBlank(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = tokens.channelAccent,
                                                contentColor = OledVoid
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("core_run_tts_button")
                                        ) {
                                            Text("Synthesize TTS")
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                if (uiState.isRecordingCoreVoiceover) {
                                                    viewModel.stopCoreVoiceoverRecordingAndRunTts()
                                                } else {
                                                    val hasPerm = ContextCompat.checkSelfPermission(
                                                        context,
                                                        Manifest.permission.RECORD_AUDIO
                                                    ) == PackageManager.PERMISSION_GRANTED
                                                    if (hasPerm) {
                                                        viewModel.startCoreVoiceoverRecording()
                                                    } else {
                                                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                    }
                                                }
                                            },
                                            enabled = !uiState.isCoreStageBusy,
                                            border = BorderStroke(1.dp, tokens.hairline),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("core_record_voiceover_button")
                                        ) {
                                            Text(if (uiState.isRecordingCoreVoiceover) "Stop & Lock" else "Record Mic")
                                        }
                                    }

                                    if (session.hasTtsAudio) {
                                        TelemetryWaveform(
                                            peaks = session.waveformPeaks,
                                            progress = 0f,
                                            height = 44.dp,
                                            modifier = Modifier.testTag("core_tts_waveform")
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = viewModel::playCoreTtsPreview,
                                                border = BorderStroke(1.dp, tokens.hairline),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(46.dp)
                                                    .testTag("core_play_tts_button")
                                            ) {
                                                Text("Play TTS")
                                            }
                                            Button(
                                                onClick = { viewModel.selectCoreStage(CorePipelineStage.ITERATE_STORY) },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = tokens.channelAccent,
                                                    contentColor = OledVoid
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(46.dp)
                                                    .testTag("core_to_story_button")
                                            ) {
                                                Text("Iterate Story ->")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    CorePipelineStage.ITERATE_STORY -> {
                        item(key = "stage_02_story_header") {
                            StudioCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "02 · Iterate on Story",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = StudioCrisp
                                        )
                                        Text(
                                            text = "TTS Pass #${session.ttsIterationCount}",
                                            style = TelemetryTimecodeStyle,
                                            color = LiveTallyGreen,
                                            modifier = Modifier.testTag("core_story_iteration_badge")
                                        )
                                    }
                                    TelemetryWaveform(
                                        peaks = session.waveformPeaks,
                                        progress = 0f,
                                        height = 40.dp
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = viewModel::playCoreTtsPreview,
                                            border = BorderStroke(1.dp, tokens.hairline),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp)
                                                .testTag("core_story_play_tts_button")
                                        ) {
                                            Text("Play Current TTS")
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.addCoreClause("") },
                                            border = BorderStroke(1.dp, tokens.hairline),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp)
                                                .testTag("core_add_clause_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Add,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Add Clause")
                                        }
                                    }
                                }
                            }
                        }

                        itemsIndexed(
                            items = session.clauses,
                            key = { _, clause -> clause.id }
                        ) { idx, clause ->
                            StudioCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Clause ${idx + 1}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = StudioCrisp
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${clause.startMs}–${clause.endMs} ms",
                                                style = TelemetryTimecodeStyle,
                                                color = TelemetryMuted
                                            )
                                            if (session.clauses.size > 1) {
                                                IconButton(
                                                    onClick = { viewModel.removeCoreClause(idx) },
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .testTag("core_remove_clause_$idx")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.DeleteOutline,
                                                        contentDescription = "Remove clause",
                                                        tint = TelemetryMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = clause.spokenText,
                                        onValueChange = { viewModel.updateCoreClauseText(idx, it) },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = StudioCrisp),
                                        colors = studioFieldColors(),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("core_clause_input_$idx")
                                    )
                                }
                            }
                        }

                        item(key = "stage_02_story_actions") {
                            StudioCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = { viewModel.runCoreStage1Tts() },
                                        enabled = !uiState.isCoreStageBusy &&
                                            session.clauses.any { it.spokenText.isNotBlank() },
                                        border = BorderStroke(1.dp, tokens.hairline),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("core_rerun_tts_button")
                                    ) {
                                        Text(
                                            if (uiState.isCoreStageBusy) {
                                                "Re-synthesizing TTS…"
                                            } else {
                                                "Re-run TTS on Updated Story"
                                            }
                                        )
                                    }
                                    Button(
                                        onClick = viewModel::lockStoryAndAdvanceToHead,
                                        enabled = !uiState.isCoreStageBusy && session.hasTtsAudio,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.channelAccent,
                                            contentColor = OledVoid
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("core_to_head_button")
                                    ) {
                                        Text("Match Speech to Talking Head ->")
                                    }
                                }
                            }
                        }
                    }

                    CorePipelineStage.SPEECH_TO_TALKING_HEAD -> {
                        item(key = "stage_03_head_card") {
                            StudioCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "03 · Speech Matched to Talking Head",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = StudioCrisp
                                        )
                                        Text(
                                            text = if (session.hasTalkingHead) "Matched" else "Ready",
                                            style = TelemetryTimecodeStyle,
                                            color = if (session.hasTalkingHead) LiveTallyGreen else TelemetryCyan
                                        )
                                    }

                                    Text(
                                        text = "Locks TTS Pass #${session.ttsIterationCount} (${session.ttsDurationMs} ms, ${session.clauses.size} clauses) onto a clean 9:16 talking-head video ready for B-roll chopping.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TelemetryMuted
                                    )

                                    if (uiState.isCoreStageBusy) {
                                        LinearProgressIndicator(
                                            progress = { uiState.coreStageProgress.coerceIn(0.08f, 1f) },
                                            color = tokens.channelAccent,
                                            trackColor = tokens.hairline,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text(
                                            text = uiState.coreStageStatusLabel ?: "Matching speech to talking head…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TelemetryCyan
                                        )
                                    }

                                    Button(
                                        onClick = { viewModel.runCoreStage3SpeechToHead() },
                                        enabled = !uiState.isCoreStageBusy && session.hasTtsAudio,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.channelAccent,
                                            contentColor = OledVoid
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("core_run_head_button")
                                    ) {
                                        Text(
                                            if (session.hasTalkingHead) {
                                                "Re-Match Speech to Talking Head"
                                            } else {
                                                "Match Speech to Talking Head"
                                            }
                                        )
                                    }

                                    if (session.hasTalkingHead) {
                                        OutlinedButton(
                                            onClick = { viewModel.selectCoreStage(CorePipelineStage.CHOP_INTO_BROLL) },
                                            border = BorderStroke(1.dp, tokens.channelAccent),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = tokens.channelAccent),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                                .testTag("core_to_broll_button")
                                        ) {
                                            Text("Chop into B-Roll ->")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    CorePipelineStage.CHOP_INTO_BROLL -> {
                        val brollCount = session.clauses.count {
                            it.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
                        }
                        item(key = "stage_04_broll_header") {
                            StudioCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "04 · Chop Talking Head into B-Roll",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = StudioCrisp
                                    )
                                    Text(
                                        text = "$brollCount B-Roll cuts",
                                        style = TelemetryTimecodeStyle,
                                        color = tokens.channelAccent,
                                        modifier = Modifier.testTag("core_broll_cut_count_label")
                                    )
                                }
                            }
                        }

                        itemsIndexed(
                            items = session.clauses,
                            key = { _, clause -> clause.id }
                        ) { idx, clause ->
                            val isBroll = clause.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
                            StudioCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Clause ${idx + 1} (${clause.startMs}–${clause.endMs} ms)",
                                            style = TelemetryTimecodeStyle,
                                            color = TelemetryMuted
                                        )
                                        OutlinedButton(
                                            onClick = { viewModel.toggleClauseBrollMode(idx) },
                                            border = BorderStroke(
                                                1.dp,
                                                if (isBroll) tokens.channelAccent else tokens.hairline
                                            ),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = if (isBroll) tokens.channelAccent else StudioCrisp
                                            ),
                                            modifier = Modifier.testTag("core_clause_mode_toggle_$idx")
                                        ) {
                                            Text(clause.segmentMode.label)
                                        }
                                    }
                                    Text(
                                        text = clause.spokenText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = StudioCrisp
                                    )
                                    if (isBroll) {
                                        OutlinedTextField(
                                            value = clause.brollCaption,
                                            onValueChange = { viewModel.updateClauseBrollCaption(idx, it) },
                                            label = { Text("B-Roll visual cutaway caption") },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall.copy(color = StudioCrisp),
                                            colors = studioFieldColors(),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("core_clause_broll_caption_$idx")
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "stage_04_optional_overlays") {
                            StudioCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "News Brief Overlays (Optional)",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = StudioCrisp
                                        )
                                        Text(
                                            text = "${session.selectedOverlayIds.size} attached",
                                            style = TelemetryTimecodeStyle,
                                            color = if (session.selectedOverlayIds.isEmpty()) {
                                                TelemetryMuted
                                            } else {
                                                LiveTallyGreen
                                            },
                                            modifier = Modifier.testTag("core_attached_overlay_count")
                                        )
                                    }
                                    Text(
                                        text = "Skip for standard stories, or toggle saved AI overlays when publishing a news brief.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TelemetryMuted
                                    )
                                    uiState.savedOverlays.forEach { ovl ->
                                        val attached = ovl.id in session.selectedOverlayIds
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(ConsoleElevated)
                                                .clickable { viewModel.toggleSavedOverlayForPipeline(ovl.id) }
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                                .testTag("core_overlay_toggle_${ovl.id}"),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = ovl.headline,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = StudioCrisp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (attached) "Attached" else "Attach",
                                                style = TelemetryTimecodeStyle,
                                                color = if (attached) LiveTallyGreen else TelemetryMuted
                                            )
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = onOpenOptionalOverlayBuilder,
                                        border = BorderStroke(1.dp, tokens.hairline),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StudioCrisp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .testTag("core_open_overlay_builder_button")
                                    ) {
                                        Text("Create Newsroom Overlays")
                                    }
                                }
                            }
                        }

                        item(key = "stage_04_commit_chop") {
                            StudioCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (uiState.isCoreStageBusy) {
                                        LinearProgressIndicator(
                                            progress = { uiState.coreStageProgress.coerceIn(0.1f, 1f) },
                                            color = tokens.channelAccent,
                                            trackColor = tokens.hairline,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Button(
                                        onClick = viewModel::runCoreStage4ChopBroll,
                                        enabled = !uiState.isCoreStageBusy && session.hasTalkingHead,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = tokens.channelAccent,
                                            contentColor = OledVoid
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(50.dp)
                                            .testTag("core_run_chop_broll_button")
                                    ) {
                                        Text("Chop into B-Roll & Final Review ->")
                                    }
                                }
                            }
                        }
                    }

                    CorePipelineStage.FINAL_REVIEW -> Unit
                }
            }
        }
    }
}

@Composable
private fun PipelineStageTabButton(
    stage: CorePipelineStage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = LocalAircheckTokens.current
    Surface(
        color = if (selected) tokens.channelAccent.copy(alpha = 0.16f) else ConsoleElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) tokens.channelAccent else tokens.hairline
        ),
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("core_stage_tab_${stage.name}")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Text(
                text = stage.shortLabel,
                style = TelemetryTimecodeStyle,
                color = if (selected) tokens.channelAccent else StudioCrisp,
                maxLines = 1
            )
        }
    }
}
