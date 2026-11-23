package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.data.BrandChannel
import com.example.data.ClipCardRow
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
                    title = "Breaking Broadcast",
                    targetDurationSec = 10,
                    beats = listOf(
                        ScriptBeat(
                            id = "p1",
                            index = 0,
                            spokenText = "Record your voice or type a line and render directly on your Mac Studio.",
                            durationSec = 5
                        )
                    )
                )
            ),
            onUpdateTitle = {},
            onSetTargetDuration = {},
            onUpdateBeatText = { _, _ -> },
            onToggleCutBeat = {},
            onToggleMicRecord = {},
            onTogglePreviewWav = {},
            onSetVoiceSource = {},
            onAddBeat = {},
            onDeleteBeat = {},
            onDismissError = {},
            onRender = {}
        )
    }
}

@Preview(name = "System Deck - Broadcast Cobalt", widthDp = 412, heightDp = 915, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun SystemDeckPreview() {
    AircheckTheme(channel = BrandChannel.BROADCAST_COBALT) {
        SystemDeckSheet(
            uiState = StudioUiState(
                settings = StudioSettings(brandChannel = BrandChannel.BROADCAST_COBALT),
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
                        durationMs = 10_000L,
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
