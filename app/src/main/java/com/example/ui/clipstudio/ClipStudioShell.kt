package com.example.ui.clipstudio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.draft.XAttachment
import com.example.ui.PostCardSource
import com.example.ui.StudioUiState
import com.example.ui.StudioViewModel
import com.example.ui.draft.DraftDeckUiState
import com.example.ui.draft.DraftDeckViewModel
import kotlinx.coroutines.flow.StateFlow

/** Clip Studio screens: `01 SPARK`, `02 SCRIPT`, `03 CUT`, `Newsroom`. */
enum class ClipStudioScreen { SPARK, SCRIPT, CUT, NEWSROOM }

private const val DOUBLE_BACK_EXIT_WINDOW_MS = 2_500L

/**
 * Default Aircheck surface: `01 SPARK` -> `02 SCRIPT` -> `03 CUT`, with the `Newsroom` graphics
 * screen reachable from `03 CUT`, real Back
 * navigation history, and a double-back-to-exit guard on root `01 SPARK`.
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

    // Navigation history stack + double-back-to-exit guard so Back never accidentally dumps the user out.
    var navHistory by remember { mutableStateOf<List<ClipStudioScreen>>(emptyList()) }
    var lastRootBackAtMs by remember { mutableLongStateOf(0L) }

    val navigateTo: (ClipStudioScreen) -> Unit = { target ->
        if (target != screen) {
            navHistory = (navHistory + screen).takeLast(12)
            onScreen(target)
        }
    }

    val hasActiveIdea = draftState.document.seedTranscript.isNotBlank() || draftState.document.arcBeats.isNotEmpty()

    val startNewIdea: () -> Unit = {
        navHistory = emptyList()
        draftVm.resetNewDraft()
        onScreen(ClipStudioScreen.SPARK)
    }

    val navigateBack: () -> Unit = {
        when {
            screen == ClipStudioScreen.NEWSROOM -> {
                val prev = navHistory.lastOrNull()
                navHistory = if (navHistory.isNotEmpty()) navHistory.dropLast(1) else emptyList()
                onScreen(prev ?: ClipStudioScreen.CUT)
            }
            screen == ClipStudioScreen.SPARK && hasActiveIdea -> {
                // Back from the 4-beat outline returns to the "+ new" empty idea view instead of exiting.
                startNewIdea()
            }
            navHistory.isNotEmpty() -> {
                val prev = navHistory.last()
                navHistory = navHistory.dropLast(1)
                onScreen(prev)
            }
            screen == ClipStudioScreen.CUT -> onScreen(ClipStudioScreen.SPARK)
            screen == ClipStudioScreen.SCRIPT -> onScreen(ClipStudioScreen.SPARK)
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastRootBackAtMs <= DOUBLE_BACK_EXIT_WINDOW_MS) {
                    (context as? Activity)?.finish()
                } else {
                    lastRootBackAtMs = now
                    draftVm.showToast("Press back again to exit Aircheck")
                }
            }
        }
    }

    // Always intercept Back so 4-beat SPARK returns to "+ new" and empty SPARK gets double-back protection.
    BackHandler(enabled = true) {
        navigateBack()
    }

    val navigate: (Int) -> Unit = { n ->
        when (n) {
            1 -> navigateTo(ClipStudioScreen.SPARK)
            2, 3 -> navigateTo(ClipStudioScreen.CUT)
        }
    }

    val createVideoFromText: (String) -> Unit = { text ->
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
        navigateTo(ClipStudioScreen.CUT)
    }

    val activeToast = draftState.toastMessage ?: studioState.toastMessage

    Box(
        modifier
            .fillMaxSize()
            .background(com.example.ui.design.AcTokens.Chassis)
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
                    onNewIdea = startNewIdea,
                    onCreateVideo = createVideoFromText,
                    lastCompletedVideo = studioState.lastCompletedVideo,
                    renderGlobalOverlays = false
                )
                ClipStudioScreen.SCRIPT -> ScriptScreen(
                    state = draftState,
                    vm = draftVm,
                    onRequestMic = requestMic,
                    onNavigate = navigate,
                    onNewIdea = startNewIdea,
                    onCreateVideo = createVideoFromText,
                    renderGlobalOverlays = false
                )
                ClipStudioScreen.CUT -> CutScreen(
                    state = studioState,
                    vm = studioVm,
                    onOpenSettings = onOpenSettings,
                    onOpenNewsroom = { navigateTo(ClipStudioScreen.NEWSROOM) },
                    onNavigate = navigate,
                    onNewIdea = startNewIdea,
                    renderGlobalOverlays = false
                )
                ClipStudioScreen.NEWSROOM -> NewsroomLookScreen(
                    state = studioState,
                    vm = studioVm,
                    onBack = navigateBack,
                    onTestOnClip = { lookId ->
                        studioVm.runCoreStage4ChopBroll(lookIdOverride = lookId)
                        navHistory = navHistory.filterNot { it == ClipStudioScreen.NEWSROOM }
                        onScreen(ClipStudioScreen.CUT)
                    },
                    renderGlobalOverlays = false
                )
            }

            StudioToast(
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
