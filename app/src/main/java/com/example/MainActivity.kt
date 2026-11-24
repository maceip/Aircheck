package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.StudioViewModel
import com.example.ui.draft.DraftDeckViewModel
import com.example.ui.draft.TactileDraftingDeckScreen
import com.example.ui.screens.ClipReviewScreen
import com.example.ui.screens.SystemDeckSheet
import com.example.ui.theme.AircheckTheme
import com.example.ui.theme.OledVoid

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )

        setContent {
            val draftViewModel: DraftDeckViewModel = viewModel()
            val studioViewModel: StudioViewModel = viewModel()
            AircheckApp(
                draftViewModel = draftViewModel,
                studioViewModel = studioViewModel
            )
        }
    }
}

/**
 * Single non-scrolling 100% viewport height Tactile Plain-Text Drafting Engine activity.
 * The legacy 3-tab shell (`Script` / `Photo` / `Clips`) has been completely replaced by the
 * 4-stage fixed-viewport instrument deck (`01 SEED` · `02 PICK` · `03 CUT` · `04 SHIP`),
 * with clean full-screen modals for Studio Settings and rendered Broadcast Clip playback.
 */
@Composable
fun AircheckApp(
    draftViewModel: DraftDeckViewModel,
    studioViewModel: StudioViewModel
) {
    val draftState by draftViewModel.uiState.collectAsStateWithLifecycle()
    val studioState by studioViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showSettingsModal by rememberSaveable { mutableStateOf(false) }
    var activeReviewClipId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(studioState.pendingShareIntent) {
        val shareIntent = studioState.pendingShareIntent
        if (shareIntent != null) {
            runCatching {
                context.startActivity(Intent.createChooser(shareIntent, "Share Aircheck"))
            }
            studioViewModel.clearPendingShareIntent()
        }
    }

    LaunchedEffect(studioState.liveNavToClipId) {
        val clipId = studioState.liveNavToClipId
        if (!clipId.isNullOrBlank()) {
            activeReviewClipId = clipId
            studioViewModel.consumeLiveNavToClip()
        }
    }

    AircheckTheme(channel = studioState.settings.brandChannel) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OledVoid)
        ) {
            when {
                showSettingsModal -> {
                    SystemDeckSheet(
                        uiState = studioState,
                        onSelectChannel = studioViewModel::setBrandChannel,
                        onClearCasCache = studioViewModel::clearCasCache,
                        onPreviewAudio = studioViewModel::togglePreviewMasterWav,
                        onSaveTailscaleRigUrl = { url ->
                            studioViewModel.saveTailscaleRigUrl(url)
                            draftViewModel.probeRigHealth()
                        },
                        onTestRigConnection = { url ->
                            studioViewModel.testMacRigConnection(url)
                            draftViewModel.probeRigHealth()
                        },
                        onSyncOtaBundle = studioViewModel::syncOtaBundleFromRig,
                        onSyncPreferences = studioViewModel::syncPreferencesNow,
                        onRefreshVmRss = studioViewModel::refreshVmRssTelemetry,
                        onCreateNewProject = {
                            draftViewModel.resetNewDraft()
                            showSettingsModal = false
                        },
                        onClose = { showSettingsModal = false }
                    )
                }

                !activeReviewClipId.isNullOrBlank() -> {
                    val clipId = activeReviewClipId!!
                    val clipFlow = remember(clipId) { studioViewModel.observeClip(clipId) }
                    val clip by clipFlow.collectAsStateWithLifecycle(initialValue = null)
                    ClipReviewScreen(
                        clip = clip,
                        exportState = studioState.exportState,
                        errorMessage = studioState.errorMessage,
                        casStore = studioViewModel.casStore,
                        playerController = studioViewModel.playerController,
                        onBack = { activeReviewClipId = null },
                        onExport = { targetClip, share ->
                            studioViewModel.exportClip(targetClip, openShareSheet = share)
                        },
                        onDismissError = studioViewModel::dismissError
                    )
                }

                else -> {
                    TactileDraftingDeckScreen(
                        uiState = draftState,
                        viewModel = draftViewModel,
                        onOpenSettings = { showSettingsModal = true },
                        onBroadcastClip = { plainText, headline ->
                            studioViewModel.renderDraftAsBroadcastClip(plainText, headline)
                        }
                    )
                }
            }
        }
    }
}
