package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.example.data.StudioTab
import com.example.data.remote.attemptAutoSignIn
import com.example.data.remote.configureAppCheck
import com.example.data.remote.onGoogleSignInClicked
import com.example.data.remote.signOutFromStudio
import com.example.navigation.AircheckNavigator
import com.example.navigation.ScreenKey
import com.example.navigation.rememberAircheckNavigationState
import com.example.ui.StudioViewModel
import com.example.ui.components.StudioDockBar
import com.example.ui.components.StudioNavigationRail
import com.example.ui.components.StudioTopBar
import com.example.ui.screens.AccountSheet
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
        // Configure Firebase App Check FIRST before any UI, ViewModel, or Firebase calls.
        configureAppCheck(this, intent)

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
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val snackbarHostState = remember { SnackbarHostState() }

    val navigationState = rememberAircheckNavigationState()
    val navigator = remember(navigationState) { AircheckNavigator(navigationState) }

    // Silent auto-sign-in for returning authenticated creators (never blocks instant offline-first boot)
    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {},
            onUnauthenticated = {},
            scope = scope
        )
    }

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
    val isFullScreenOverlay = currentRoute is ScreenKey.SettingsKey || currentRoute is ScreenKey.SystemDeckKey

    AircheckTheme(channel = uiState.settings.brandChannel) {
        val tokens = LocalAircheckTokens.current

        val entryProvider = entryProvider<NavKey> {
            entry<ScreenKey.ScriptKey> {
                ScriptScreen(
                    uiState = uiState,
                    onUpdateTitle = viewModel::updateTitle,
                    onUpdateSpark = viewModel::updateSpark,
                    onSetTargetDuration = viewModel::setTargetDuration,
                    onToggleHoneSpark = viewModel::toggleHoneSpark,
                    onUpdateBeatText = viewModel::updateBeatText,
                    onUpdateBeatMetadata = { id, mention, depth, anchor, source, sfx, broll ->
                        viewModel.updateBeatMetadata(
                            beatId = id,
                            mentionTag = mention,
                            depthTextTag = depth,
                            anchorWord = anchor,
                            evidenceSource = source,
                            sfxTag = sfx,
                            bRollPrompt = broll
                        )
                    },
                    onToggleExpandBeat = viewModel::toggleExpandBeat,
                    onFactCheckBeat = viewModel::factCheckBeat,
                    onShorten20Percent = viewModel::toggleShorten20Percent,
                    onAcceptShortProposal = viewModel::acceptShortProposal,
                    onRejectShortProposal = viewModel::rejectShortProposal,
                    onToggleCutBeat = viewModel::toggleCutBeat,
                    onToggleBeatMicRecord = { beatId -> viewModel.toggleMicRecording(beatId) },
                    onAddBeat = viewModel::addBeat,
                    onDeleteBeat = viewModel::deleteBeat,
                    onDismissError = viewModel::dismissError,
                    onPrimaryAction = {
                        val proj = uiState.activeProject
                        val hasPhoto = !proj.stageConfig.activePhotoSha.isNullOrBlank() ||
                            uiState.settings.persona.defaultPhotoSha.isNotBlank()
                        if (hasPhoto && proj.activeBeats.isNotEmpty()) {
                            viewModel.enqueueCloudRender()
                        } else {
                            navigator.selectTab(StudioTab.PHOTO)
                        }
                    }
                )
            }

            entry<ScreenKey.PhotoStageKey> {
                PhotoStageScreen(
                    uiState = uiState,
                    casStore = viewModel.casStore,
                    onImportPhoto = viewModel::importPhoto,
                    onNormalizePose = viewModel::normalizePhotoPose,
                    onUpdateStageConfig = viewModel::updateStageConfig,
                    onSetStage4Mode = viewModel::setStage4Mode,
                    onSetVoiceSource = viewModel::setVoiceSource,
                    onGenerateBeatBRoll = viewModel::generateBeatBRoll,
                    onEnqueueRender = viewModel::enqueueCloudRender,
                    onDismissError = viewModel::dismissError
                )
            }

            entry<ScreenKey.ClipsKey> {
                ClipsScreen(
                    uiState = uiState,
                    casStore = viewModel.casStore,
                    onOpenClip = { clipId -> navigator.openClipReview(clipId) },
                    onEnqueueRender = viewModel::enqueueCloudRender,
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
                    onClose = { navigator.goBack() }
                )
            }

            entry<ScreenKey.SettingsKey> {
                AccountSheet(
                    uiState = uiState,
                    onSignInWithGoogle = {
                        onGoogleSignInClicked(
                            context = context,
                            credentialManager = credentialManager,
                            onAuthSuccess = { viewModel.triggerManualCloudSync() },
                            onAuthError = { err -> viewModel.reportError(err) },
                            scope = scope
                        )
                    },
                    onSignOut = {
                        signOutFromStudio(
                            credentialManager = credentialManager,
                            scope = scope
                        )
                    },
                    onSyncNow = viewModel::triggerManualCloudSync,
                    onCreateNewProject = {
                        viewModel.createNewProject()
                        navigator.selectTab(StudioTab.SCRIPT)
                    },
                    onSavePersonaIds = viewModel::savePersonaIds,
                    onSaveProviders = viewModel::saveProviderSettings,
                    onStartPersonaTraining = viewModel::startPersonaTraining,
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
                                    StudioTab.PHOTO -> "Photo"
                                    StudioTab.CLIPS -> "Clips"
                                },
                                isSignedIn = uiState.isSignedIn,
                                onOpenSystemDeck = { navigator.navigate(ScreenKey.SystemDeckKey) },
                                onOpenAccount = { navigator.navigate(ScreenKey.SettingsKey) }
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
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
