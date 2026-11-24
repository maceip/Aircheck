package com.example.ui.a2ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider
import androidx.a2ui.model.processor.A2uiActionInterceptor
import androidx.a2ui.model.processor.A2uiMessageProcessor
import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage
import androidx.a2ui.model.protocol.A2uiDeleteSurfaceMessage
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import androidx.a2ui.model.protocol.A2uiUpdateDataModelMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.catalog.A2uiAudioPlayerRenderer
import androidx.compose.material3.a2ui.catalog.A2uiImageRenderer
import androidx.compose.material3.a2ui.catalog.A2uiVideoRenderer
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface NewsroomA2uiAction {
    data class AskInstallRemotion(
        val userPrompt: String,
        val enableBrollPopups: Boolean
    ) : NewsroomA2uiAction

    data class GenerateNewsroomOverlayClip(
        val chyronHeadline: String,
        val chyronSubline: String,
        val spokenScript: String
    ) : NewsroomA2uiAction
}

/**
 * Builds the 100% stock Jetpack `material3-a2ui` Basic Catalog V1 (`androidx.compose.material3:material3-a2ui`)
 * with Coil 3 image loading, Media3 ExoPlayer video playback, local audio playback, and Android `IntentUrlOpener`.
 *
 * Strictly adheres to user constraint: ZERO `com.google.adk` and ZERO custom A2UI component catalogs.
 */
fun createStockAircheckA2uiCatalog(context: Context): A2uiCatalog {
    val appContext = context.applicationContext
    val imageRenderer = A2uiImageRenderer { url, description, contentScale, modifier, onError ->
        AsyncImage(
            model = url,
            contentDescription = description,
            contentScale = contentScale,
            modifier = modifier,
            onError = { err -> onError(err.result.throwable) }
        )
    }
    val videoRenderer = A2uiVideoRenderer { url, modifier, onError ->
        val exoPlayer = remember(url) {
            ExoPlayer.Builder(appContext).build().apply {
                runCatching {
                    setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                    prepare()
                }.onFailure(onError)
            }
        }
        DisposableEffect(exoPlayer) {
            onDispose { exoPlayer.release() }
        }
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = exoPlayer
                }
            },
            update = { it.player = exoPlayer },
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp)
        )
    }
    val audioRenderer = A2uiAudioPlayerRenderer { url, description, modifier, onError ->
        var isPlaying by remember(url) { mutableStateOf(false) }
        val mediaPlayer = remember(url) { MediaPlayer() }
        DisposableEffect(mediaPlayer, url) {
            onDispose {
                runCatching { mediaPlayer.release() }
            }
        }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = description.orEmpty().ifBlank { "Audio preview" })
            OutlinedButton(
                onClick = {
                    runCatching {
                        if (isPlaying) {
                            mediaPlayer.pause()
                            isPlaying = false
                        } else {
                            mediaPlayer.reset()
                            mediaPlayer.setDataSource(appContext, Uri.parse(url))
                            mediaPlayer.prepare()
                            mediaPlayer.start()
                            isPlaying = true
                        }
                    }.onFailure(onError)
                }
            ) {
                Text(text = if (isPlaying) "Pause" else "Play")
            }
        }
    }

    return materialA2uiBasicCatalogV1(
        image = MaterialA2uiBasicCatalogV1Defaults.image(imageRenderer),
        video = MaterialA2uiBasicCatalogV1Defaults.video(videoRenderer),
        audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer(audioRenderer),
        functions = A2uiBasicCatalogV1.Functions(
            appContext,
            A2uiLocaleProvider.Default
        )
    )
}

/**
 * Stock Jetpack A2UI controller managing the Newsroom Studio agent surface:
 * - When Remotion has NOT been used yet (`remotionUnused == true`), renders a stock Basic Catalog V1
 *   surface prompting the user to ask to install Remotion for speaking pop-ups, transitions, and B-roll.
 * - Once Remotion is active (`remotionUnused == false`), transitions the surface to the Newsroom
 *   overlay cue-sheet (`chyronHeadline`, `chyronSubline`, `spokenScript`) for broadcast generation.
 */
class AircheckA2uiController(
    context: Context,
    scope: CoroutineScope,
    private val onAction: (NewsroomA2uiAction) -> Unit
) {
    val catalog: A2uiCatalog = createStockAircheckA2uiCatalog(context)
    val parser = A2uiMessageParser()

    val processor: A2uiMessageProcessor = A2uiMessageProcessor(
        catalogs = listOf(catalog),
        interceptors = emptyList<A2uiActionInterceptor>()
    )

    val activeSurfaces: StateFlow<List<A2uiSurfaceModel>> = processor.activeSurfaces

    private var currentSurfaceModeIsRemotionUnused: Boolean? = null

    init {
        scope.launch {
            processor.collectMessages()
        }
        scope.launch {
            processor.outboundEvents.collect { message ->
                if (message is A2uiClientEventMessage) {
                    handleResolvedClientEvent(message)
                }
            }
        }
    }

    private fun handleResolvedClientEvent(event: A2uiClientEventMessage) {
        when (event.type) {
            EVENT_ASK_INSTALL_REMOTION -> {
                val prompt = (event.context["userPrompt"] as? String)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { DEFAULT_REMOTION_INSTALL_PROMPT }
                val enablePopups = (event.context["enableBrollPopups"] as? Boolean) ?: true
                onAction(
                    NewsroomA2uiAction.AskInstallRemotion(
                        userPrompt = prompt,
                        enableBrollPopups = enablePopups
                    )
                )
            }

            EVENT_GENERATE_NEWSROOM_CLIP -> {
                val headline = (event.context["chyronHeadline"] as? String)?.trim().orEmpty()
                val subline = (event.context["chyronSubline"] as? String)?.trim().orEmpty()
                val script = (event.context["spokenScript"] as? String)?.trim().orEmpty()
                onAction(
                    NewsroomA2uiAction.GenerateNewsroomOverlayClip(
                        chyronHeadline = headline,
                        chyronSubline = subline,
                        spokenScript = script
                    )
                )
            }
        }
    }

    fun syncNewsroomSurface(
        remotionUnused: Boolean,
        statusMessage: String,
        defaultHeadline: String = "",
        defaultSubline: String = "AIRCHECK LIVE DESK",
        defaultScript: String = ""
    ) {
        val modeChanged = currentSurfaceModeIsRemotionUnused != remotionUnused
        if (modeChanged) {
            if (currentSurfaceModeIsRemotionUnused != null) {
                processor.processMessage(A2uiDeleteSurfaceMessage(SURFACE_ID_NEWSROOM))
            }
            currentSurfaceModeIsRemotionUnused = remotionUnused
            processor.processMessage(
                A2uiCreateSurfaceMessage(
                    surfaceId = SURFACE_ID_NEWSROOM,
                    catalogId = catalog.id,
                    theme = emptyMap(),
                    shouldSendDataModel = true
                )
            )
            if (remotionUnused) {
                processor.processMessage(
                    A2uiUpdateDataModelMessage(
                        surfaceId = SURFACE_ID_NEWSROOM,
                        path = "/remotion",
                        value = mapOf(
                            "userPrompt" to DEFAULT_REMOTION_INSTALL_PROMPT,
                            "enableBrollPopups" to true,
                            "statusMessage" to statusMessage
                        )
                    )
                )
                processor.processMessage(
                    A2uiUpdateComponentsMessage(
                        surfaceId = SURFACE_ID_NEWSROOM,
                        components = buildRemotionInstallPromptComponents()
                    )
                )
            } else {
                processor.processMessage(
                    A2uiUpdateDataModelMessage(
                        surfaceId = SURFACE_ID_NEWSROOM,
                        path = "/overlay",
                        value = mapOf(
                            "chyronHeadline" to defaultHeadline.ifBlank { "BREAKING NEWS" },
                            "chyronSubline" to defaultSubline.ifBlank { "AIRCHECK LIVE DESK" },
                            "spokenScript" to defaultScript,
                            "statusMessage" to statusMessage
                        )
                    )
                )
                processor.processMessage(
                    A2uiUpdateComponentsMessage(
                        surfaceId = SURFACE_ID_NEWSROOM,
                        components = buildActiveOverlayStudioComponents()
                    )
                )
            }
        } else {
            val statusPath = if (remotionUnused) "/remotion/statusMessage" else "/overlay/statusMessage"
            processor.processMessage(
                A2uiUpdateDataModelMessage(
                    surfaceId = SURFACE_ID_NEWSROOM,
                    path = statusPath,
                    value = statusMessage
                )
            )
        }
    }

    private fun buildRemotionInstallPromptComponents(): List<A2uiComponentPayload> = listOf(
        A2uiComponentPayload(
            id = "root",
            type = "Card",
            properties = mapOf("child" to "remotion_col")
        ),
        A2uiComponentPayload(
            id = "remotion_col",
            type = "Column",
            properties = mapOf(
                "children" to listOf(
                    "remotion_title",
                    "remotion_subtitle",
                    "remotion_prompt_field",
                    "remotion_broll_checkbox",
                    "remotion_status_text",
                    "remotion_install_button"
                )
            )
        ),
        A2uiComponentPayload(
            id = "remotion_title",
            type = "Text",
            properties = mapOf(
                "text" to "Remotion Video Overlays Not Installed Yet",
                "variant" to "h4"
            )
        ),
        A2uiComponentPayload(
            id = "remotion_subtitle",
            type = "Text",
            properties = mapOf(
                "text" to "Ask to install Remotion so Newsroom clips can render timed speaking pop-ups, transitions, and B-roll.",
                "variant" to "body"
            )
        ),
        A2uiComponentPayload(
            id = "remotion_prompt_field",
            type = "TextField",
            properties = mapOf(
                "label" to "Your Remotion setup request",
                "value" to mapOf("path" to "/remotion/userPrompt"),
                "variant" to "shortText"
            )
        ),
        A2uiComponentPayload(
            id = "remotion_broll_checkbox",
            type = "CheckBox",
            properties = mapOf(
                "label" to "Enable live speaking news pop-ups, transitions, and B-roll",
                "value" to mapOf("path" to "/remotion/enableBrollPopups")
            )
        ),
        A2uiComponentPayload(
            id = "remotion_status_text",
            type = "Text",
            properties = mapOf(
                "text" to mapOf("path" to "/remotion/statusMessage"),
                "variant" to "caption"
            )
        ),
        A2uiComponentPayload(
            id = "remotion_install_button_label",
            type = "Text",
            properties = mapOf(
                "text" to "Ask to Install Remotion",
                "variant" to "body"
            )
        ),
        A2uiComponentPayload(
            id = "remotion_install_button",
            type = "Button",
            properties = mapOf(
                "child" to "remotion_install_button_label",
                "variant" to "primary",
                "action" to mapOf(
                    "event" to mapOf(
                        "name" to EVENT_ASK_INSTALL_REMOTION,
                        "context" to mapOf(
                            "userPrompt" to mapOf("path" to "/remotion/userPrompt"),
                            "enableBrollPopups" to mapOf("path" to "/remotion/enableBrollPopups")
                        )
                    )
                )
            )
        )
    )

    private fun buildActiveOverlayStudioComponents(): List<A2uiComponentPayload> = listOf(
        A2uiComponentPayload(
            id = "root",
            type = "Card",
            properties = mapOf("child" to "overlay_col")
        ),
        A2uiComponentPayload(
            id = "overlay_col",
            type = "Column",
            properties = mapOf(
                "children" to listOf(
                    "overlay_title",
                    "overlay_headline_field",
                    "overlay_subline_field",
                    "overlay_script_field",
                    "overlay_status_text",
                    "overlay_generate_button"
                )
            )
        ),
        A2uiComponentPayload(
            id = "overlay_title",
            type = "Text",
            properties = mapOf(
                "text" to "Remotion News Pop-Ups & B-Roll Cue Sheet",
                "variant" to "h4"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_headline_field",
            type = "TextField",
            properties = mapOf(
                "label" to "Chyron headline",
                "value" to mapOf("path" to "/overlay/chyronHeadline"),
                "variant" to "shortText"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_subline_field",
            type = "TextField",
            properties = mapOf(
                "label" to "Lower-third subline",
                "value" to mapOf("path" to "/overlay/chyronSubline"),
                "variant" to "shortText"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_script_field",
            type = "TextField",
            properties = mapOf(
                "label" to "Spoken newsroom copy",
                "value" to mapOf("path" to "/overlay/spokenScript"),
                "variant" to "longText"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_status_text",
            type = "Text",
            properties = mapOf(
                "text" to mapOf("path" to "/overlay/statusMessage"),
                "variant" to "caption"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_generate_button_label",
            type = "Text",
            properties = mapOf(
                "text" to "Generate Newsroom Broadcast",
                "variant" to "body"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_generate_button",
            type = "Button",
            properties = mapOf(
                "child" to "overlay_generate_button_label",
                "variant" to "primary",
                "action" to mapOf(
                    "event" to mapOf(
                        "name" to EVENT_GENERATE_NEWSROOM_CLIP,
                        "context" to mapOf(
                            "chyronHeadline" to mapOf("path" to "/overlay/chyronHeadline"),
                            "chyronSubline" to mapOf("path" to "/overlay/chyronSubline"),
                            "spokenScript" to mapOf("path" to "/overlay/spokenScript")
                        )
                    )
                )
            )
        )
    )

    companion object {
        const val SURFACE_ID_NEWSROOM: String = "newsroom_remotion_surface"
        const val EVENT_ASK_INSTALL_REMOTION: String = "ask_install_remotion"
        const val EVENT_GENERATE_NEWSROOM_CLIP: String = "generate_newsroom_clip"
        const val DEFAULT_REMOTION_INSTALL_PROMPT: String =
            "Install Remotion for live speaking news pop-ups, transitions, and B-roll."
    }
}
