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
import com.example.ui.clipstudio.ClipStudioScreen
import com.example.ui.clipstudio.ClipStudioShell
import com.example.ui.draft.DraftDeckViewModel
import com.example.ui.screens.ClipReviewScreen
import com.example.ui.screens.NewsroomStudioScreen
import com.example.ui.screens.SystemDeckSheet
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
        consumeStagedRigEndpointFile()

        setContent {
            AircheckApp(
                draftViewModel = activityDraftViewModel,
                studioViewModel = activityStudioViewModel
            )
        }
    }

    override fun onResume() {
        super.onResume()
        consumeStagedRigEndpointFile()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIncomingHandoffOrAppFunctionIntent(intent)
        consumeStagedRigEndpointFile()
    }

    private fun consumeStagedRigEndpointFile() {
        val dir = getExternalFilesDir(null) ?: return
        val marker = java.io.File(dir, RIG_ENDPOINT_BOOTSTRAP_FILE)
        if (!marker.isFile) return
        val candidate = runCatching { marker.readText(Charsets.UTF_8).trim() }.getOrDefault("")
        runCatching { marker.delete() }
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            activityStudioViewModel.saveTailscaleRigUrl(candidate)
            activityStudioViewModel.showToast("studio rig synced")
        }
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
        const val RIG_ENDPOINT_BOOTSTRAP_FILE: String = "rig_endpoint.txt"
    }
}

/**
 * Aircheck top-level navigation shell:
 * - Default surface: Clip Studio v4 (`01 SPARK` · `02 SCRIPT` · `03 CUT` + `04 NEWSROOM` looks).
 * - Single canonical video pipeline (Clip Studio: Spark -> Script -> Cut, driven by CoreStageWorker):
 *   `01 TTS -> 02 Iterate Story -> 03 Speech to Talking Head -> 04 Chop into B-Roll -> 05 Final Review`.
 * - Separate optional screens kept outside the main flow:
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
    var showNewsroomStudioModal by rememberSaveable { mutableStateOf(false) }
    var activeReviewClipId by rememberSaveable { mutableStateOf<String?>(null) }
    var clipStudioScreen by rememberSaveable { mutableStateOf(ClipStudioScreen.SPARK) }

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
            clipStudioScreen = ClipStudioScreen.CUT
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
                showNewsroomStudioModal -> {
                    NewsroomStudioScreen(
                        uiState = studioState,
                        a2uiController = studioViewModel.a2uiController,
                        onDeleteSavedOverlay = studioViewModel::deleteSavedNewsroomOverlay,
                        onSelectBreakingStyle = studioViewModel::setCoreBreakingStyle,
                        onGenerateOverlaySet = { studioViewModel.generateNewsroomOverlaySet() },
                        onBack = { showNewsroomStudioModal = false }
                    )
                }

                showSettingsModal -> {
                    SystemDeckSheet(
                        uiState = studioState,
                        onSelectChannel = studioViewModel::setBrandChannel,
                        onClearCasCache = studioViewModel::clearCasCache,
                        onSaveTailscaleRigUrl = { url ->
                            studioViewModel.saveTailscaleRigUrl(url)
                            draftViewModel.probeRigHealth()
                        },
                        onTestRigConnection = { url ->
                            studioViewModel.testMacRigConnection(url)
                            draftViewModel.probeRigHealth()
                        },
                        onOpenNewsroomStudio = {
                            showNewsroomStudioModal = true
                        },
                        onLoadActivity = { studioViewModel.loadActivityLog() },
                        onDeviceModels = {
                            val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
                            val container = (appCtx as AircheckApplication).container
                            val download by container.modelDownloadManager.state.collectAsStateWithLifecycle()
                            val aicore by androidx.compose.runtime.produceState(
                                initialValue = com.example.ui.screens.AicoreModelStatus(checked = false, baseModelName = null)
                            ) {
                                value = com.example.ui.screens.AicoreModelStatus(
                                    checked = true,
                                    baseModelName = container.onDevicePromptEngine.baseModelName()
                                )
                            }
                            com.example.ui.screens.OnDeviceModelsCard(
                                aicore = aicore,
                                download = download,
                                onDownload = { com.example.engine.ModelDownloadWorker.enqueue(appCtx) },
                                onPause = { com.example.engine.ModelDownloadWorker.pause(appCtx) },
                                onDelete = { container.deleteGemmaFallback() }
                            )
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
                    ClipStudioShell(
                        draftState = draftState,
                        draftVm = draftViewModel,
                        studioState = studioState,
                        studioVm = studioViewModel,
                        screen = clipStudioScreen,
                        onScreen = { clipStudioScreen = it },
                        onOpenSettings = { showSettingsModal = true },
                        onOpenOverlayBuilder = { showNewsroomStudioModal = true }
                    )
                }
            }
        }
    }
}
