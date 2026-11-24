package com.example.ui

import android.app.Application
import android.os.Looper
import androidx.a2ui.compose.ui.testing.A2uiTestController
import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.a2ui.model.protocol.A2uiEventAction
import androidx.a2ui.model.protocol.A2uiException
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.AircheckApp
import com.example.AircheckApplication
import com.example.ui.a2ui.createStockAircheckA2uiCatalog
import com.example.ui.draft.DraftDeckViewModel
import com.example.ui.screens.NewsroomStudioScreen
import com.example.ui.screens.VoiceModelCareScreen
import com.example.ui.theme.AircheckTheme
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w411dp-h915dp-xxhdpi")
class AircheckSeparateScreensAndA2uiTest {

    private lateinit var app: AircheckApplication
    private var httpServer: HttpServer? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>() as AircheckApplication
        File(app.noBackupFilesDir, "voice_care").deleteRecursively()
        runBlocking {
            app.container.profileStore.setPhoneOnlyVoiceMode(true)
        }
    }

    @After
    fun tearDown() {
        httpServer?.stop(0)
        httpServer = null
    }

    @Test
    fun stockA2uiTestController_verifiesBasicCatalogAndDefensiveErrorBoundary() = runComposeUiTest {
        val catalog = createStockAircheckA2uiCatalog(app)
        val controller = A2uiTestController(
            catalog = catalog,
            initialComponents = listOf(
                A2uiComponentPayload(
                    id = "root",
                    type = "Card",
                    properties = mapOf("child" to "col")
                ),
                A2uiComponentPayload(
                    id = "col",
                    type = "Column",
                    properties = mapOf("children" to listOf("headline", "cta_btn"))
                ),
                A2uiComponentPayload(
                    id = "headline",
                    type = "Text",
                    properties = mapOf(
                        "text" to mapOf("path" to "/banner"),
                        "variant" to "h4"
                    )
                ),
                A2uiComponentPayload(
                    id = "cta_txt",
                    type = "Text",
                    properties = mapOf("text" to "Ask to Install Remotion", "variant" to "body")
                ),
                A2uiComponentPayload(
                    id = "cta_btn",
                    type = "Button",
                    properties = mapOf(
                        "child" to "cta_txt",
                        "variant" to "primary",
                        "action" to mapOf(
                            "event" to mapOf(
                                "name" to "ask_install_remotion",
                                "context" to mapOf("banner" to mapOf("path" to "/banner"))
                            )
                        )
                    )
                )
            ),
            initialData = mapOf("banner" to "Remotion Ready Check")
        )

        val surface = controller.start()
        setContent {
            AircheckTheme {
                A2uiSurface(
                    surfaceModel = surface,
                    errorContent = { err ->
                        Text(text = "A2UI boundary caught: ${err.code}")
                    }
                )
            }
        }
        controller.waitForIdle()

        onNodeWithText("Remotion Ready Check").assertIsDisplayed()
        onNodeWithText("Ask to Install Remotion").performClick()
        controller.waitForIdle()

        val dispatched = controller.dispatchedActions.filterIsInstance<A2uiEventAction>()
        assertEquals(1, dispatched.size)
        assertEquals("ask_install_remotion", dispatched.first().eventName)
        assertEquals(1, controller.outboundEvents.size)
        assertEquals("ask_install_remotion", controller.outboundEvents.first().type)
        assertEquals("Remotion Ready Check", controller.outboundEvents.first().context["banner"])

        controller.failComponent(
            "root",
            A2uiException.A2uiRuntimeException("Simulated malformed payload")
        )
        controller.waitForIdle()
        onNodeWithText("A2UI boundary caught: RUNTIME_ERROR").assertIsDisplayed()
    }

    @Test
    fun newsroomStudioScreen_whenRemotionUnused_userAsksToInstallRemotion_persistsAndSwitchesSurface() = runComposeUiTest {
        val capturedBodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/remotion/install") { exchange ->
            val reqBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            capturedBodies.add(reqBody)
            val resp = """
                {
                  "status": "installed",
                  "remotion_enabled": true,
                  "remotion_version": "4.0.242",
                  "package_provider_id": "mac_rig_remotion",
                  "user_request": "Please install Remotion for live news pop-ups and B-roll",
                  "enable_broll_popups": true
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.start()
        httpServer = server
        val port = server.address.port

        runBlocking {
            app.container.profileStore.setTailscaleRigUrl("http://127.0.0.1:$port")
        }

        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val state = studioVm.uiState.collectAsStateWithLifecycle().value
            AircheckTheme {
                NewsroomStudioScreen(
                    uiState = state,
                    a2uiController = studioVm.a2uiController,
                    onBack = {},
                    onOpenClip = {}
                )
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onAllNodesWithText("Remotion Video Overlays Not Installed Yet")
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Remotion Video Overlays Not Installed Yet").assertIsDisplayed()

        val promptInput = onNode(hasSetTextAction())
        promptInput.performScrollTo()
        promptInput.performTextClearance()
        promptInput.performTextInput("Please install Remotion for live news pop-ups and B-roll")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        onNodeWithText("Ask to Install Remotion")
            .performScrollTo()
            .performClick()

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            capturedBodies.isNotEmpty() &&
                onAllNodesWithText("Remotion News Pop-Ups & B-Roll Cue Sheet")
                    .fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(capturedBodies.first().contains("Please install Remotion for live news pop-ups and B-roll"))
        assertTrue(capturedBodies.first().contains("\"enable_broll_popups\":true"))

        val savedSettings = runBlocking { app.container.profileStore.current() }
        assertTrue(savedSettings.remotionInstallRequested)
        assertEquals("mac_rig_remotion", savedSettings.providers.packageProviderId)
        assertTrue(savedSettings.remotionUsedCount >= 1)
    }

    @Test
    fun newsroomStudioScreen_whenRigOffline_savesRequestHonestlyWithoutFakeSuccess() = runComposeUiTest {
        val closedPort = ServerSocket(0).use { it.localPort }
        runBlocking {
            app.container.profileStore.setTailscaleRigUrl("http://127.0.0.1:$closedPort")
        }

        val current = runBlocking { app.container.profileStore.current() }
        if (current.remotionUsedCount == 0 && current.providers.packageProviderId != "mac_rig_remotion") {
            var studioVmRef: StudioViewModel? = null
            setContent {
                val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
                val state = studioVm.uiState.collectAsStateWithLifecycle().value
                AircheckTheme {
                    NewsroomStudioScreen(
                        uiState = state,
                        a2uiController = studioVm.a2uiController,
                        onBack = {},
                        onOpenClip = {}
                    )
                }
            }

            waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                onAllNodesWithText("Ask to Install Remotion")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Ask to Install Remotion")
                .performScrollTo()
                .performClick()

            waitUntil(timeoutMillis = 5_000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                studioVmRef?.uiState?.value?.errorMessage?.contains("unreachable") == true
            }
            val after = runBlocking { app.container.profileStore.current() }
            assertTrue(after.remotionInstallRequested)
            assertEquals(0, after.remotionUsedCount)
        }
    }

    @Test
    fun voiceModelCareScreen_enforcesPhoneOnlyMode_showsExactLocation_importsLora_andRunsRealQualityCheck() = runComposeUiTest {
        val pcm16k = ShortArray(16_000) { i ->
            val t = i.toDouble() / 16_000.0
            (0.5 * sin(2.0 * PI * 440.0 * t) * 32767.0).toInt().toShort()
        }
        val tempLora = File(app.cacheDir, "voice_lora_v009.gguf").apply {
            writeBytes("GGUF_LOCAL_PHONE_ONLY_LORA_WEIGHTS_V009".toByteArray(Charsets.UTF_8))
        }
        runBlocking {
            app.container.voiceModelCareManager.writeWavSampleForCare(pcm16k, "phone_check_440hz.wav")
            app.container.voiceModelCareManager.importLocalModelOrLoraFile(tempLora)
        }

        var studioVmRef: StudioViewModel? = null
        setContent {
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            val state = studioVm.uiState.collectAsStateWithLifecycle().value
            AircheckTheme {
                VoiceModelCareScreen(
                    uiState = state,
                    onTogglePhoneOnlyMode = studioVm::setPhoneOnlyVoiceMode,
                    onVerifyFiles = { studioVm.refreshVoiceModelCare(verifyChecksums = true) },
                    onStartRecordSample = studioVm::startVoiceCareSampleRecording,
                    onStopRecordSample = studioVm::stopVoiceCareSampleRecording,
                    onDeleteSample = studioVm::deleteVoiceCareSample,
                    onImportModelUri = studioVm::importLocalVoiceModelUri,
                    onRunQualityCheck = studioVm::runOnPhoneVoiceQualityCheck,
                    onBack = {}
                )
            }
        }
        val studioVm = checkNotNull(studioVmRef)

        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.voiceStorageSnapshot?.importedFiles?.any {
                it.fileName == "voice_lora_v009.gguf"
            } == true
        }

        onNodeWithTag("voice_care_bundles_path_text").assertIsDisplayed()
        onNodeWithTag("voice_care_root_path_text").assertIsDisplayed()

        onNodeWithTag("voice_care_phone_only_switch").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            !studioVm.uiState.value.settings.phoneOnlyVoiceMode &&
                runBlocking { !app.container.profileStore.current().phoneOnlyVoiceMode }
        }

        onNodeWithTag("voice_care_phone_only_switch").performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.settings.phoneOnlyVoiceMode &&
                runBlocking { app.container.profileStore.current().phoneOnlyVoiceMode }
        }
        val lockedSettings = runBlocking { app.container.profileStore.current() }
        assertTrue(lockedSettings.phoneOnlyVoiceMode)
        assertFalse(lockedSettings.providers.useLanVoice)

        onNodeWithTag("voice_care_lazy_list").performScrollToKey("storage_location_card")
        onNodeWithTag("verify_on_phone_files_button")
            .performScrollTo()
            .performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.voiceStorageSnapshot?.lastVerifiedChecksums == true
        }

        onNodeWithTag("voice_care_lazy_list").performScrollToKey("quality_check_card")
        onNodeWithTag("run_voice_quality_check_button")
            .performScrollTo()
            .performClick()
        waitUntil(timeoutMillis = 5_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            studioVm.uiState.value.voiceQualityReport?.hasAudio == true
        }

        val report = studioVm.uiState.value.voiceQualityReport
        assertNotNull(report)
        assertEquals("phone_check_440hz.wav", report!!.inspectedFileName)
        assertEquals(0, report.clippedSampleCount)
        assertTrue("Expected real peak ~ -6 dBFS, got ${report.peakDbfs}", report.peakDbfs!! in -6.5f..-5.5f)
        assertTrue("Expected real RMS ~ -9 dBFS, got ${report.rmsDbfs}", report.rmsDbfs!! in -9.5f..-8.5f)
        onNodeWithTag("voice_quality_summary_text")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun systemDeckSheet_opensSeparateNewsroomAndVoiceCareScreens_keepingMainScriptFlowClean() = runComposeUiTest {
        var draftVmRef: DraftDeckViewModel? = null
        var studioVmRef: StudioViewModel? = null

        setContent {
            val draftVm = draftVmRef ?: DraftDeckViewModel(app).also { draftVmRef = it }
            val studioVm = studioVmRef ?: StudioViewModel(app).also { studioVmRef = it }
            AircheckApp(
                draftViewModel = draftVm,
                studioViewModel = studioVm
            )
        }

        assertTrue(onAllNodesWithText("Voice Model Care").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithText("Newsroom & Overlay Studio").fetchSemanticsNodes().isEmpty())

        onNodeWithTag("top_settings_button").performClick()
        waitForIdle()

        onNodeWithTag("open_voice_model_care_button")
            .performScrollTo()
            .performClick()
        waitForIdle()
        onNodeWithTag("voice_care_back_button").assertIsDisplayed()
        onNodeWithTag("voice_care_back_button").performClick()
        waitForIdle()

        onNodeWithTag("open_newsroom_studio_button")
            .performScrollTo()
            .performClick()
        waitForIdle()
        onNodeWithTag("newsroom_studio_back_button").assertIsDisplayed()
        onNodeWithTag("newsroom_studio_back_button").performClick()
        waitForIdle()

        onNodeWithTag("close_system_deck_button").performClick()
        waitForIdle()
    }
}
