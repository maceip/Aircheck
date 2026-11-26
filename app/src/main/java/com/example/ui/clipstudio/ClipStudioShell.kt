package com.example.ui.clipstudio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.draft.XAttachment
import com.example.ui.PostCardSource
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import com.example.ui.draft.DraftDeckUiState
import com.example.ui.draft.DraftDeckViewModel
import kotlinx.coroutines.flow.StateFlow

/** Clip Studio v5 screens (`01 SPARK`, `02 SCRIPT`, `03 CUT`, and `Video look`). */
enum class ClipStudioScreen { SPARK, SCRIPT, CUT, NEWSROOM }

/**
 * Default Aircheck surface (Clip Studio v5): `01 SPARK` -> `02 SCRIPT` -> `03 CUT`, with
 * `Video look` reachable from `03 CUT` and System Settings / Voice Care / LLM Router reachable
 * via the top-bar settings key.
 */
@Composable
fun ClipStudioShell(
    draftState: DraftDeckUiState,
    draftVm: DraftDeckViewModel,
    studioState: StudioUiState,
    studioVm: StudioViewModel,
    screen: ClipStudioScreen,
    onScreen: (ClipStudioScreen) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOverlayBuilder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            // Do not auto-fire a press-and-hold latch after the OS dialog closes with no finger down.
            draftVm.showToast("mic ready — hold to record")
        } else {
            draftVm.showToast("microphone permission required to record")
        }
    }
    val requestMic: (() -> Unit) -> Unit = { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Background renders report progress in a notification: ask once, when the first job starts.
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val backgroundJobActive = studioState.isCoreStageBusy || studioState.isGeneratingBackdrop
    LaunchedEffect(backgroundJobActive) {
        if (backgroundJobActive && !askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // A freshly written script opens 02 SCRIPT once.
    val doc = draftState.document
    val scriptKey = if (doc.isVideoScript) doc.candidates.firstOrNull()?.globalSnapshots?.firstOrNull()?.hashCode() else null
    var lastOpenedScript by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(scriptKey) {
        if (scriptKey != null && scriptKey != lastOpenedScript) {
            lastOpenedScript = scriptKey
            if (screen == ClipStudioScreen.SPARK) onScreen(ClipStudioScreen.SCRIPT)
        }
    }

    BackHandler(enabled = screen != ClipStudioScreen.SPARK) {
        onScreen(
            when (screen) {
                ClipStudioScreen.NEWSROOM -> ClipStudioScreen.CUT
                ClipStudioScreen.CUT -> ClipStudioScreen.SCRIPT
                else -> ClipStudioScreen.SPARK
            }
        )
    }

    val navigate: (Int) -> Unit = { n ->
        when (n) {
            1 -> onScreen(ClipStudioScreen.SPARK)
            2 -> onScreen(ClipStudioScreen.SCRIPT)
            3 -> onScreen(ClipStudioScreen.CUT)
        }
    }

    val activeToast = draftState.toastMessage ?: studioState.toastMessage

    Box(
        modifier
            .fillMaxSize()
            .background(Cs.Body)
            .testTag("clip_studio_shell")
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            when (screen) {
                ClipStudioScreen.SPARK -> SparkScreen(
                    state = draftState,
                    vm = draftVm,
                    onRequestMic = requestMic,
                    onOpenSettings = onOpenSettings,
                    onNavigate = navigate,
                    lastCompletedVideo = studioState.lastCompletedVideo,
                    renderGlobalOverlays = false
                )
                ClipStudioScreen.SCRIPT -> ScriptScreen(
                    state = draftState,
                    vm = draftVm,
                    onRequestMic = requestMic,
                    onNavigate = navigate,
                    onCreateVideo = { text ->
                        val quote = draftState.document.attachment as? XAttachment.QuotePost
                        // Persist the new CorePipelineSession to Room BEFORE auto-starting Stage 1 TTS
                        // so CoreStageWorker never races against an old or empty session.
                        studioVm.openStoryInCorePipeline(
                            plainText = text,
                            headline = draftState.document.arcBeats.firstOrNull()?.text.orEmpty().take(40),
                            postCard = quote?.let { PostCardSource(it.handle, it.tweetId, it.snippet) },
                            shotPlan = draftState.document.shotPlan,
                            autoStartTts = true
                        )
                        onScreen(ClipStudioScreen.CUT)
                    },
                    renderGlobalOverlays = false
                )
                ClipStudioScreen.CUT -> CutScreen(
                    state = studioState,
                    vm = studioVm,
                    onOpenSettings = onOpenSettings,
                    onOpenNewsroom = { onScreen(ClipStudioScreen.NEWSROOM) },
                    onNavigate = navigate,
                    renderGlobalOverlays = false
                )
                ClipStudioScreen.NEWSROOM -> NewsroomLookScreen(
                    state = studioState,
                    vm = studioVm,
                    onBack = { onScreen(ClipStudioScreen.CUT) },
                    onTestOnClip = { lookId ->
                        studioVm.runCoreStage4ChopBroll(lookIdOverride = lookId)
                        onScreen(ClipStudioScreen.CUT)
                    },
                    onOpenOverlayBuilder = onOpenOverlayBuilder,
                    renderGlobalOverlays = false
                )
            }

            CsToastOverlay(
                message = activeToast,
                onDismiss = {
                    if (draftState.toastMessage != null) {
                        draftVm.dismissToast()
                    } else {
                        studioVm.dismissToast()
                    }
                }
            )
        }

        // Isolated 50 Hz full-bleed edge-to-edge dither voice glow rising directly from the physical
        // bottom bezel without triggering 50 Hz recompositions on Spark/Script/Cut screens.
        VoiceGlowDitherHost(
            draftRecording = draftState.isAnyRecording,
            studioRecording = studioState.isRecordingCoreVoiceover,
            draftRmsFlow = draftVm.liveRmsFlow,
            studioRmsFlow = studioVm.liveRmsFlow
        )
    }
}

@Composable
private fun VoiceGlowDitherHost(
    draftRecording: Boolean,
    studioRecording: Boolean,
    draftRmsFlow: StateFlow<Float>,
    studioRmsFlow: StateFlow<Float>
) {
    val active = draftRecording || studioRecording
    val draftRms by draftRmsFlow.collectAsStateWithLifecycle()
    val studioRms by studioRmsFlow.collectAsStateWithLifecycle()
    val liveLevel = when {
        draftRecording -> draftRms
        studioRecording -> studioRms
        else -> 0f
    }
    VoiceGlowDitherOverlay(
        active = active,
        level = liveLevel
    )
}
