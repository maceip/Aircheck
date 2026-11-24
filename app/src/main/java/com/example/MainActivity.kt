package com.example

import android.app.HandoffActivityData
import android.app.HandoffActivityDataRequestInfo
import android.app.HandoffActivityParams
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import com.example.ui.StudioViewModel
import com.example.ui.draft.DraftDeckViewModel
import com.example.ui.draft.TactileDraftingDeckScreen
import com.example.ui.screens.ClipReviewScreen
import com.example.ui.screens.CorePipelineScreen
import com.example.ui.screens.NewsroomStudioScreen
import com.example.ui.screens.SystemDeckSheet
import com.example.ui.screens.VoiceModelCareScreen
import com.example.ui.theme.AircheckTheme
import com.example.ui.theme.OledVoid

class MainActivity : ComponentActivity() {

    private val activityDraftViewModel: DraftDeckViewModel by viewModels()
    private val activityStudioViewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK)
        )
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            runCatching {
                setHandoffEnabled(true, HandoffActivityParams.Builder().build())
            }
        }
        if (savedInstanceState == null) {
            applyIncomingHandoffOrAppFunctionIntent(intent)
        }

        setContent {
            AircheckApp(
                draftViewModel = activityDraftViewModel,
                studioViewModel = activityStudioViewModel
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIncomingHandoffOrAppFunctionIntent(intent)
    }

    override fun onHandoffActivityDataRequested(
        requestInfo: HandoffActivityDataRequestInfo
    ): HandoffActivityData {
        val snapshot = activityDraftViewModel.uiState.value
        val extras = PersistableBundle().apply {
            putString(EXTRA_HANDOFF_STAGE, snapshot.document.stage.name)
            putString(EXTRA_HANDOFF_SEED_TEXT, snapshot.activePlainText)
        }
        return HandoffActivityData.Builder(ComponentName(this, MainActivity::class.java))
            .setExtras(extras)
            .build()
    }

    private fun applyIncomingHandoffOrAppFunctionIntent(incoming: Intent?) {
        if (incoming == null) return
        val stageRaw = incoming.getStringExtra(EXTRA_HANDOFF_STAGE)
        val seedText = incoming.getStringExtra(EXTRA_HANDOFF_SEED_TEXT)
        if (!stageRaw.isNullOrBlank() || !seedText.isNullOrBlank()) {
            activityDraftViewModel.restoreHandoffState(stageRaw, seedText)
        }
    }

    companion object {
        const val EXTRA_HANDOFF_STAGE: String = "com.example.extra.HANDOFF_STAGE"
        const val EXTRA_HANDOFF_SEED_TEXT: String = "com.example.extra.HANDOFF_SEED_TEXT"
    }
}

/**
 * Aircheck top-level navigation shell:
 * - Default surface: Tactile Plain-Text Drafting Engine (`01 SEED` · `02 PICK` · `03 CUT` · `04 SHIP`).
 * - Single canonical video pipeline (`CorePipelineScreen`):
 *   `01 TTS -> 02 Iterate Story -> 03 Speech to Talking Head -> 04 Chop into B-Roll -> 05 Final Review`.
 * - Separate optional screens kept outside the main flow:
 *   - `VoiceModelCareScreen` (phone-only voice lock, local LoRA care, quality check)
 *   - `NewsroomStudioScreen` (savable AI-generated Jetpack A2UI overlays for news briefs)
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
    var showVoiceModelCareModal by rememberSaveable { mutableStateOf(false) }
    var showNewsroomStudioModal by rememberSaveable { mutableStateOf(false) }
    var showCorePipelineModal by rememberSaveable { mutableStateOf(false) }
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

    LaunchedEffect(studioState.openCorePipelineEvent) {
        if (studioState.openCorePipelineEvent) {
            showSettingsModal = false
            showNewsroomStudioModal = false
            showCorePipelineModal = true
            studioViewModel.consumeOpenCorePipelineEvent()
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
                showVoiceModelCareModal -> {
                    VoiceModelCareScreen(
                        uiState = studioState,
                        onTogglePhoneOnlyMode = studioViewModel::setPhoneOnlyVoiceMode,
                        onVerifyFiles = {
                            studioViewModel.refreshVoiceModelCare(verifyChecksums = true)
                        },
                        onStartRecordSample = studioViewModel::startVoiceCareSampleRecording,
                        onStopRecordSample = studioViewModel::stopVoiceCareSampleRecording,
                        onDeleteSample = studioViewModel::deleteVoiceCareSample,
                        onImportModelUri = studioViewModel::importLocalVoiceModelUri,
                        onRunQualityCheck = studioViewModel::runOnPhoneVoiceQualityCheck,
                        onBack = { showVoiceModelCareModal = false }
                    )
                }

                showNewsroomStudioModal -> {
                    NewsroomStudioScreen(
                        uiState = studioState,
                        a2uiController = studioViewModel.a2uiController,
                        onDeleteSavedOverlay = studioViewModel::deleteSavedNewsroomOverlay,
                        onBack = { showNewsroomStudioModal = false }
                    )
                }

                showCorePipelineModal -> {
                    CorePipelineScreen(
                        uiState = studioState,
                        viewModel = studioViewModel,
                        onBack = { showCorePipelineModal = false },
                        onOpenOptionalOverlayBuilder = { showNewsroomStudioModal = true }
                    )
                }

                showSettingsModal -> {
                    SystemDeckSheet(
                        uiState = studioState,
                        onSelectChannel = studioViewModel::setBrandChannel,
                        onClearCasCache = studioViewModel::clearCasCache,
                        onSaveTailscaleRigUrl = { url ->
                            studioViewModel.saveTailscaleRigUrl(url)
                            if (!studioState.settings.phoneOnlyVoiceMode) {
                                draftViewModel.probeRigHealth()
                            }
                        },
                        onTestRigConnection = { url ->
                            studioViewModel.testMacRigConnection(url)
                            draftViewModel.probeRigHealth()
                        },
                        onOpenVoiceModelCare = {
                            studioViewModel.refreshVoiceModelCare(verifyChecksums = false)
                            showVoiceModelCareModal = true
                        },
                        onOpenNewsroomStudio = {
                            showNewsroomStudioModal = true
                        },
                        onOpenCorePipeline = {
                            if (studioState.corePipelineSession.clauses.isEmpty()) {
                                val draftText = draftViewModel.currentDraftPlainText()
                                if (draftText.isNotBlank()) {
                                    studioViewModel.openStoryInCorePipeline(draftText)
                                }
                            }
                            showSettingsModal = false
                            showCorePipelineModal = true
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
                            studioViewModel.openStoryInCorePipeline(plainText, headline)
                        }
                    )
                }
            }
        }
    }
}
