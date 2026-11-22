package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.data.BrandChannel
import com.example.data.ClipCardRow
import com.example.data.PersonaProfile
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.local.StudioSettings
import com.example.ui.screens.ScriptScreen
import com.example.ui.screens.SystemDeckSheet
import com.example.ui.theme.AircheckTheme

@Preview(name = "Script Canvas - Volt Lime", widthDp = 412, heightDp = 915, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun ScriptScreenPreview() {
    AircheckTheme(channel = BrandChannel.VOLT_LIME) {
        ScriptScreen(
            uiState = StudioUiState(
                activeProject = ScriptProject(
                    title = "Cloud GPU Inference Pricing Shift",
                    ideaSpark = "Serverless H100 video generation just crossed under $1 per 30-second broadcast.",
                    targetDurationSec = 30,
                    beats = listOf(
                        ScriptBeat(
                            id = "p1",
                            index = 0,
                            spokenText = "Cloud video synthesis just dropped below a dollar per thirty-second news segment.",
                            durationSec = 6,
                            evidenceSource = "Q4 GPU Index"
                        ),
                        ScriptBeat(
                            id = "p2",
                            index = 1,
                            spokenText = "Every stage now writes immutable SHA-256 artifacts so stale clips can never leak.",
                            durationSec = 6
                        )
                    )
                )
            ),
            onUpdateTitle = {},
            onUpdateSpark = {},
            onSetTargetDuration = {},
            onToggleHoneSpark = {},
            onUpdateBeatText = { _, _ -> },
            onUpdateBeatMetadata = { _, _, _, _, _, _, _ -> },
            onToggleExpandBeat = {},
            onFactCheckBeat = {},
            onShorten20Percent = {},
            onAcceptShortProposal = {},
            onRejectShortProposal = {},
            onToggleCutBeat = {},
            onToggleBeatMicRecord = {},
            onAddBeat = {},
            onDeleteBeat = {},
            onDismissError = {},
            onPrimaryAction = {}
        )
    }
}

@Preview(name = "System Deck - Broadcast Cobalt", widthDp = 412, heightDp = 915, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun SystemDeckPreview() {
    AircheckTheme(channel = BrandChannel.BROADCAST_COBALT) {
        SystemDeckSheet(
            uiState = StudioUiState(
                settings = StudioSettings(
                    brandChannel = BrandChannel.BROADCAST_COBALT,
                    persona = PersonaProfile(
                        headModelId = "ryan_head_v1",
                        voiceId = "ryan_voice_v1"
                    )
                ),
                casTotalBytes = 48L * 1024L * 1024L,
                clipCards = listOf(
                    ClipCardRow(
                        id = "clip_1",
                        scriptId = "s1",
                        jobId = "job_1",
                        title = "Broadcast Update",
                        photoSha = null,
                        finalSha = "abcd1234",
                        status = RenderJobStatus.READY,
                        progressFraction = 1f,
                        stageDetail = "Ready",
                        errorMessage = null,
                        durationMs = 30_000L,
                        thumbHash = null,
                        remoteFinalUrl = null,
                        updatedAtMillis = 0L
                    )
                )
            ),
            onSelectChannel = {},
            onClearCasCache = {},
            onPreviewAudio = {},
            onClose = {}
        )
    }
}
