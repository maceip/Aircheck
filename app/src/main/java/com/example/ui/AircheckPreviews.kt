package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.data.BrandChannel
import com.example.data.NewsroomStageConfig
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.ui.screens.ScriptScreen
import com.example.ui.theme.AircheckTheme

@Preview(name = "Story Canvas - Volt Lime", widthDp = 412, heightDp = 915, showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun ScriptScreenPreview() {
    AircheckTheme(channel = BrandChannel.VOLT_LIME) {
        ScriptScreen(
            uiState = StudioUiState(
                activeProject = ScriptProject(
                    ideaSpark = "Two hockey players spotted playing puck on the Golden Gate Bridge.",
                    stageConfig = NewsroomStageConfig(
                        chyronHeadline = "ILLEGAL HOCKEY ON BRIDGE",
                        chyronSubline = "SPOTTED SATURDAY · 2:33 PM"
                    ),
                    beats = listOf(
                        ScriptBeat(
                            id = "p1",
                            index = 0,
                            spokenText = "Breaking news out of San Francisco, where two hockey players were spotted playing puck on the Golden Gate Bridge.",
                            durationSec = 6
                        )
                    )
                )
            ),
            onUpdateIdea = {},
            onAiFleshOutStory = {},
            onUpdateBeatText = { _, _ -> },
            onAiPolishSegment = {},
            onToggleBeatMicRecord = {},
            onTogglePreviewWav = {},
            onClearMicTake = {},
            onAddBeat = {},
            onDeleteBeat = {},
            onNewStory = {},
            onDismissError = {},
            onRender = {}
        )
    }
}
