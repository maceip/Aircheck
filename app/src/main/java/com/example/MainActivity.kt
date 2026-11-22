package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.StudioTab
import com.example.data.remote.attemptAutoSignIn
import com.example.data.remote.configureAppCheck
import com.example.data.remote.onGoogleSignInClicked
import com.example.data.remote.signOutFromStudio
import com.example.ui.StudioViewModel
import com.example.ui.components.StudioDockBar
import com.example.ui.components.StudioNavigationRail
import com.example.ui.components.StudioTopBar
import com.example.ui.screens.AccountSheet
import com.example.ui.screens.ReviewScreen
import com.example.ui.screens.ScriptScreen
import com.example.ui.screens.StageScreen
import com.example.ui.screens.SystemDeckSheet
import com.example.ui.theme.AircheckTheme
import com.example.ui.theme.ConsoleElevated
import com.example.ui.theme.LocalAircheckTokens
import com.example.ui.theme.OledVoid
import com.example.ui.theme.StudioCrisp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // CRITICAL: Configure Firebase App Check FIRST before any UI, ViewModel, or Firebase calls.
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

    // Silent auto-sign-in for returning authenticated creators (never blocks instant offline-first Script boot)
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

    AircheckTheme(channel = uiState.settings.brandChannel) {
        val tokens = LocalAircheckTokens.current

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
                        StudioTopBar(
                            title = when (uiState.selectedTab) {
                                StudioTab.SCRIPT -> "Aircheck"
                                StudioTab.STAGE -> "Stage"
                                StudioTab.REVIEW -> "Review"
                            },
                            isSignedIn = uiState.isSignedIn,
                            onOpenSystemDeck = { viewModel.setShowSystemDeck(true) },
                            onOpenAccount = { viewModel.setShowAccountSheet(true) }
                        )
                    },
                    bottomBar = {
                        if (!isExpanded) {
                            StudioDockBar(
                                selectedTab = uiState.selectedTab,
                                onSelectTab = viewModel::selectTab
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
                        if (isExpanded) {
                            StudioNavigationRail(
                                selectedTab = uiState.selectedTab,
                                onSelectTab = viewModel::selectTab
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            if (uiState.selectedTab != StudioTab.SCRIPT) {
                                BackHandler {
                                    viewModel.selectTab(StudioTab.SCRIPT)
                                }
                            }

                            when (uiState.selectedTab) {
                                StudioTab.SCRIPT -> {
                                    ScriptScreen(
                                        uiState = uiState,
                                        onUpdateTitle = viewModel::updateTitle,
                                        onUpdateSpark = viewModel::updateSpark,
                                        onSetTargetDuration = viewModel::setTargetDuration,
                                        onToggleHoneSpark = viewModel::toggleHoneSpark,
                                        onUpdateBeatText = viewModel::updateBeatText,
                                        onUpdateBeatMetadata = { id, mention, depth, source, sfx, broll ->
                                            viewModel.updateBeatMetadata(
                                                beatId = id,
                                                mentionTag = mention,
                                                depthTextTag = depth,
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
                                            val hasStage = !proj.stageConfig.compositedAnchorKey.isNullOrBlank() ||
                                                !proj.stageConfig.selfieImageKey.isNullOrBlank()
                                            if (hasStage && proj.activeBeats.isNotEmpty()) {
                                                viewModel.renderTalkingHeadMp4()
                                            } else {
                                                viewModel.selectTab(StudioTab.STAGE)
                                            }
                                        }
                                    )
                                }

                                StudioTab.STAGE -> {
                                    StageScreen(
                                        uiState = uiState,
                                        mediaStorage = viewModel.mediaStorage,
                                        onImportSelfie = viewModel::importSelfie,
                                        onUpdateStageConfig = viewModel::updateStageConfig,
                                        onComposeAnchor = viewModel::composeStudioAnchor,
                                        onSetVoiceSource = viewModel::setVoiceSource,
                                        onSetVoiceSelectionId = viewModel::setVoiceSelectionId,
                                        onGenerateBeatBRoll = viewModel::generateBeatBRoll,
                                        onRenderVideo = viewModel::renderTalkingHeadMp4,
                                        onDismissError = viewModel::dismissError
                                    )
                                }

                                StudioTab.REVIEW -> {
                                    ReviewScreen(
                                        uiState = uiState,
                                        mediaStorage = viewModel.mediaStorage,
                                        onRenderVideo = viewModel::renderTalkingHeadMp4,
                                        onToggleMicRecord = { viewModel.toggleMicRecording(null) },
                                        onPreviewAudio = viewModel::previewMasterOrSynthesize,
                                        onExportVideo = { share -> viewModel.exportRenderedMp4(openShareSheet = share) },
                                        onSwitchProject = viewModel::switchProject,
                                        onDismissError = viewModel::dismissError
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Full-screen OLED System Deck Inspector Overlay
            if (uiState.showSystemDeck) {
                BackHandler { viewModel.setShowSystemDeck(false) }
                SystemDeckSheet(
                    uiState = uiState,
                    onSelectChannel = viewModel::setBrandChannel,
                    onSynthesizeAudioTest = viewModel::previewMasterOrSynthesize,
                    onClose = { viewModel.setShowSystemDeck(false) }
                )
            }

            // Full-screen OLED Account & Cloud Sync Overlay
            if (uiState.showAccountSheet) {
                BackHandler { viewModel.setShowAccountSheet(false) }
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
                    onCreateNewProject = viewModel::createNewProject,
                    onSaveVoiceCloneCredentials = viewModel::setVoiceCloneCredentials,
                    onClose = { viewModel.setShowAccountSheet(false) }
                )
            }
        }
    }
}
