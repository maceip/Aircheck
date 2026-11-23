package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.example.data.StudioTab
import com.example.navigation.AircheckNavigator
import com.example.navigation.ScreenKey
import com.example.navigation.rememberAircheckNavigationState
import com.example.ui.RigConnectionStatus
import com.example.ui.StudioViewModel
import com.example.ui.components.StudioDockBar
import com.example.ui.components.StudioNavigationRail
import com.example.ui.components.StudioTopBar
import com.example.ui.screens.ClipReviewScreen
import com.example.ui.screens.ClipsScreen
import com.example.ui.screens.PhotoStageScreen
import com.example.ui.screens.ScriptScreen
import com.example.ui.screens.SystemDeckSheet
import com.example.ui.theme.AircheckTheme
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )

        setContent {
            val viewModel: StudioViewModel = viewModel()
            AircheckApp(viewModel = viewModel)
        }
    }
}

@Composable
fun AircheckApp(viewModel: StudioViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val navigationState = rememberAircheckNavigationState()
    val navigator = remember(navigationState) { AircheckNavigator(navigationState) }

    LaunchedEffect(uiState.statusNotice) {
        val notice = uiState.statusNotice
        if (!notice.isNullOrBlank()) {
            snackbarHostState.showSnackbar(notice)
            viewModel.dismissStatusNotice()
        }
    }

    LaunchedEffect(uiState.pendingShareIntent) {
        val shareIntent = uiState.pendingShareIntent
        if (shareIntent != null) {
            runCatching {
                context.startActivity(Intent.createChooser(shareIntent, "Share Aircheck"))
            }
            viewModel.clearPendingShareIntent()
        }
    }

    LaunchedEffect(uiState.liveNavToClipId) {
        val clipId = uiState.liveNavToClipId
        if (!clipId.isNullOrBlank()) {
            navigator.openClipReview(clipId)
            viewModel.consumeLiveNavToClip()
        }
    }

    val currentRoute = navigationState.currentRoute
    LaunchedEffect(currentRoute) {
        snackbarHostState.currentSnackbarData?.dismiss()
    }
    val isFullScreenOverlay = currentRoute is ScreenKey.SystemDeckKey

    AircheckTheme(channel = uiState.settings.brandChannel) {
        val tokens = LocalAircheckTokens.current

        val entryProvider = entryProvider<NavKey> {
            entry<ScreenKey.ScriptKey> {
                ScriptScreen(
                    uiState = uiState,
                    onUpdateIdea = viewModel::updateStoryIdea,
                    onAiFleshOutStory = viewModel::aiFleshOutStory,
                    onUpdateBeatText = viewModel::updateBeatText,
                    onAiPolishSegment = viewModel::aiPolishSegment,
                    onToggleBeatMicRecord = { beatId -> viewModel.toggleMicRecording(beatId) },
                    onTogglePreviewWav = viewModel::togglePreviewMasterWav,
                    onClearMicTake = viewModel::clearRecordedMicTake,
                    onAddBeat = viewModel::addBeat,
                    onDeleteBeat = viewModel::deleteBeat,
                    onNewStory = viewModel::createNewProject,
                    onDismissError = viewModel::dismissError,
                    onRender = { viewModel.renderActiveProject() }
                )
            }

            entry<ScreenKey.PhotoStageKey> {
                PhotoStageScreen(
                    uiState = uiState,
                    casStore = viewModel.casStore,
                    onImportPhoto = viewModel::importPhoto,
                    onUpdateStageConfig = viewModel::updateStageConfig,
                    onAiPolishHeadline = viewModel::aiPolishHeadline,
                    onAiPolishSubline = viewModel::aiPolishSubline,
                    onEnqueueRender = { viewModel.renderActiveProject() },
                    onDismissError = viewModel::dismissError
                )
            }

            entry<ScreenKey.ClipsKey> {
                ClipsScreen(
                    uiState = uiState,
                    casStore = viewModel.casStore,
                    onOpenClip = { clipId -> navigator.openClipReview(clipId) },
                    onRefreshClips = viewModel::refreshRemoteClips,
                    onStartNewStory = { navigator.selectTab(StudioTab.SCRIPT) },
                    onDismissError = viewModel::dismissError
                )
            }

            entry<ScreenKey.ClipReviewKey> { key ->
                val clipFlow = remember(key.clipId) { viewModel.observeClip(key.clipId) }
                val clip by clipFlow.collectAsStateWithLifecycle(initialValue = null)
                ClipReviewScreen(
                    clip = clip,
                    exportState = uiState.exportState,
                    errorMessage = uiState.errorMessage,
                    casStore = viewModel.casStore,
                    playerController = viewModel.playerController,
                    onBack = { navigator.goBack() },
                    onExport = { targetClip, share -> viewModel.exportClip(targetClip, openShareSheet = share) },
                    onDismissError = viewModel::dismissError
                )
            }

            entry<ScreenKey.SystemDeckKey> {
                SystemDeckSheet(
                    uiState = uiState,
                    onSelectChannel = viewModel::setBrandChannel,
                    onClearCasCache = viewModel::clearCasCache,
                    onPreviewAudio = viewModel::togglePreviewMasterWav,
                    onSaveTailscaleRigUrl = viewModel::saveTailscaleRigUrl,
                    onTestRigConnection = viewModel::testMacRigConnection,
                    onSyncOtaBundle = viewModel::syncOtaBundleFromRig,
                    onSyncPreferences = viewModel::syncPreferencesNow,
                    onRefreshVmRss = viewModel::refreshVmRssTelemetry,
                    onCreateNewProject = {
                        viewModel.createNewProject()
                        navigator.goBack()
                    },
                    onClose = { navigator.goBack() }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OledVoid)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isExpanded = maxWidth >= 600.dp

                Scaffold(
                    containerColor = OledVoid,
                    contentColor = StudioCrisp,
                    snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { data ->
                            Snackbar(
                                snackbarData = data,
                                containerColor = ConsoleElevated,
                                contentColor = StudioCrisp,
                                actionColor = tokens.channelAccent
                            )
                        }
                    },
                    topBar = {
                        if (!isFullScreenOverlay) {
                            StudioTopBar(
                                title = when (navigationState.activeTab) {
                                    StudioTab.SCRIPT -> "Aircheck"
                                    StudioTab.PHOTO -> "News Desk"
                                    StudioTab.CLIPS -> "Clips"
                                },
                                isRigOnline = uiState.rigHealth.status == RigConnectionStatus.ONLINE,
                                onOpenSystemDeck = { navigator.navigate(ScreenKey.SystemDeckKey) }
                            )
                        }
                    },
                    bottomBar = {
                        if (!isExpanded && !isFullScreenOverlay) {
                            StudioDockBar(
                                selectedTab = navigationState.activeTab,
                                onSelectTab = navigator::selectTab
                            )
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(OledVoid)
                    ) {
                        if (isExpanded && !isFullScreenOverlay) {
                            StudioNavigationRail(
                                selectedTab = navigationState.activeTab,
                                onSelectTab = navigator::selectTab
                            )
                        }

                        NavDisplay(
                            entries = navigationState.toDecoratedEntries(entryProvider),
                            onBack = { navigator.goBack() },
                            transitionSpec = {
                                fadeIn(tween(90)) togetherWith fadeOut(tween(70))
                            },
                            popTransitionSpec = {
                                fadeIn(tween(90)) togetherWith fadeOut(tween(70))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
