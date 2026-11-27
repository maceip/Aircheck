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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import com.example.data.OverlayObjectKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

sealed interface NewsroomA2uiAction {
    data class AskInstallRemotion(
        val userPrompt: String,
        val enableBrollPopups: Boolean
    ) : NewsroomA2uiAction

    data class GenerateAiOverlayBrief(
        val briefTopic: String
    ) : NewsroomA2uiAction

    data class SaveOverlayObject(
        val kind: OverlayObjectKind,
        val headline: String,
        val subline: String,
        val calloutStat: String,
        val durationSec: Int,
        val a2uiComponentsJson: String
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
            com.example.ui.design.AcMediaWell(padding = 5.dp) {
                com.example.ui.design.AcMediaKey(
                    icon = if (isPlaying) androidx.compose.material.icons.Icons.Filled.Pause else androidx.compose.material.icons.Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    active = isPlaying,
                    size = 48.dp,
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
                )
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
 * Stock Jetpack A2UI controller managing the optional Newsroom Overlay Studio surface:
 * - When Remotion has NOT been used yet (`remotionUnused == true`), renders a stock Basic Catalog V1
 *   surface prompting the user to ask to install Remotion for speaking pop-ups, transitions, and B-roll.
 * - Once Remotion is active (`remotionUnused == false`), renders the Savable News Brief Overlay Builder
 *   where AI-generated or user-edited stock A2UI objects are previewed and saved to Room (`SavedNewsroomOverlay`)
 *   for optional attachment in Stage 4 (`04 B-ROLL`) of the Core Pipeline.
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

            EVENT_GENERATE_AI_OVERLAY -> {
                val topic = (event.context["briefTopic"] as? String)?.trim().orEmpty()
                onAction(
                    NewsroomA2uiAction.GenerateAiOverlayBrief(
                        briefTopic = topic
                    )
                )
            }

            EVENT_SAVE_OVERLAY_OBJECT -> {
                val headline = (event.context["headline"] as? String)?.trim().orEmpty()
                val subline = (event.context["subline"] as? String)?.trim().orEmpty()
                val calloutStat = (event.context["calloutStat"] as? String)?.trim().orEmpty()
                val kind = if (calloutStat.isNotEmpty()) {
                    OverlayObjectKind.STAT_CALLOUT
                } else {
                    OverlayObjectKind.LOWER_THIRD
                }
                val serialized = serializeOverlayAsA2uiComponentsJson(
                    headline = headline,
                    subline = subline,
                    calloutStat = calloutStat
                )
                onAction(
                    NewsroomA2uiAction.SaveOverlayObject(
                        kind = kind,
                        headline = headline,
                        subline = subline,
                        calloutStat = calloutStat,
                        durationSec = 4,
                        a2uiComponentsJson = serialized
                    )
                )
            }
        }
    }

    fun syncNewsroomSurface(
        remotionUnused: Boolean,
        statusMessage: String,
        defaultHeadline: String = "",
        defaultSubline: String = "AIRCHECK NEWS BRIEF",
        defaultBriefTopic: String = ""
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
                            "briefTopic" to defaultBriefTopic,
                            "headline" to defaultHeadline.ifBlank { "BREAKING BRIEF" },
                            "subline" to defaultSubline.ifBlank { "AIRCHECK NEWS BRIEF" },
                            "calloutStat" to "",
                            "statusMessage" to statusMessage
                        )
                    )
                )
                processor.processMessage(
                    A2uiUpdateComponentsMessage(
                        surfaceId = SURFACE_ID_NEWSROOM,
                        components = buildSavableOverlayStudioComponents()
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

    fun updateOverlayDraftFields(
        headline: String,
        subline: String,
        calloutStat: String,
        statusMessage: String
    ) {
        if (currentSurfaceModeIsRemotionUnused != false) return
        processor.processMessage(
            A2uiUpdateDataModelMessage(
                surfaceId = SURFACE_ID_NEWSROOM,
                path = "/overlay/headline",
                value = headline
            )
        )
        processor.processMessage(
            A2uiUpdateDataModelMessage(
                surfaceId = SURFACE_ID_NEWSROOM,
                path = "/overlay/subline",
                value = subline
            )
        )
        processor.processMessage(
            A2uiUpdateDataModelMessage(
                surfaceId = SURFACE_ID_NEWSROOM,
                path = "/overlay/calloutStat",
                value = calloutStat
            )
        )
        processor.processMessage(
            A2uiUpdateDataModelMessage(
                surfaceId = SURFACE_ID_NEWSROOM,
                path = "/overlay/statusMessage",
                value = statusMessage
            )
        )
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
                "text" to "Ask to install Remotion so News Briefs can attach reusable A2UI pop-up overlays.",
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

    private fun buildSavableOverlayStudioComponents(): List<A2uiComponentPayload> = listOf(
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
                    "overlay_topic_field",
                    "overlay_ai_generate_button",
                    "overlay_headline_field",
                    "overlay_subline_field",
                    "overlay_stat_field",
                    "overlay_preview_card",
                    "overlay_status_text",
                    "overlay_save_button"
                )
            )
        ),
        A2uiComponentPayload(
            id = "overlay_title",
            type = "Text",
            properties = mapOf(
                "text" to "Create Savable News Brief Overlay",
                "variant" to "h4"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_topic_field",
            type = "TextField",
            properties = mapOf(
                "label" to "News brief topic for AI draft",
                "value" to mapOf("path" to "/overlay/briefTopic"),
                "variant" to "longText"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_ai_generate_button_label",
            type = "Text",
            properties = mapOf(
                "text" to "Generate AI Overlay",
                "variant" to "body"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_ai_generate_button",
            type = "Button",
            properties = mapOf(
                "child" to "overlay_ai_generate_button_label",
                // Stock A2uiBasicCatalogV1.Button.Variant.Secondary serializes as "default".
                "variant" to "default",
                "action" to mapOf(
                    "event" to mapOf(
                        "name" to EVENT_GENERATE_AI_OVERLAY,
                        "context" to mapOf(
                            "briefTopic" to mapOf("path" to "/overlay/briefTopic")
                        )
                    )
                )
            )
        ),
        A2uiComponentPayload(
            id = "overlay_headline_field",
            type = "TextField",
            properties = mapOf(
                "label" to "Overlay headline",
                "value" to mapOf("path" to "/overlay/headline"),
                "variant" to "shortText"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_subline_field",
            type = "TextField",
            properties = mapOf(
                "label" to "Overlay subline",
                "value" to mapOf("path" to "/overlay/subline"),
                "variant" to "shortText"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_stat_field",
            type = "TextField",
            properties = mapOf(
                "label" to "Callout stat (optional)",
                "value" to mapOf("path" to "/overlay/calloutStat"),
                "variant" to "shortText"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_preview_card",
            type = "Card",
            properties = mapOf("child" to "overlay_preview_col")
        ),
        A2uiComponentPayload(
            id = "overlay_preview_col",
            type = "Column",
            properties = mapOf(
                "children" to listOf(
                    "overlay_preview_headline",
                    "overlay_preview_subline"
                )
            )
        ),
        A2uiComponentPayload(
            id = "overlay_preview_headline",
            type = "Text",
            properties = mapOf(
                "text" to mapOf("path" to "/overlay/headline"),
                "variant" to "h5"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_preview_subline",
            type = "Text",
            properties = mapOf(
                "text" to mapOf("path" to "/overlay/subline"),
                "variant" to "caption"
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
            id = "overlay_save_button_label",
            type = "Text",
            properties = mapOf(
                "text" to "Save Overlay for News Briefs",
                "variant" to "body"
            )
        ),
        A2uiComponentPayload(
            id = "overlay_save_button",
            type = "Button",
            properties = mapOf(
                "child" to "overlay_save_button_label",
                "variant" to "primary",
                "action" to mapOf(
                    "event" to mapOf(
                        "name" to EVENT_SAVE_OVERLAY_OBJECT,
                        "context" to mapOf(
                            "headline" to mapOf("path" to "/overlay/headline"),
                            "subline" to mapOf("path" to "/overlay/subline"),
                            "calloutStat" to mapOf("path" to "/overlay/calloutStat")
                        )
                    )
                )
            )
        )
    )

    companion object {
        const val SURFACE_ID_NEWSROOM: String = "newsroom_remotion_surface"
        const val EVENT_ASK_INSTALL_REMOTION: String = "ask_install_remotion"
        const val EVENT_GENERATE_AI_OVERLAY: String = "generate_ai_overlay"
        const val EVENT_SAVE_OVERLAY_OBJECT: String = "save_overlay_object"
        const val DEFAULT_REMOTION_INSTALL_PROMPT: String =
            "Install Remotion for live speaking news pop-ups, transitions, and B-roll."

        fun serializeOverlayAsA2uiComponentsJson(
            headline: String,
            subline: String,
            calloutStat: String
        ): String {
            val childIds = buildList {
                add("saved_headline")
                if (subline.isNotBlank()) add("saved_subline")
                if (calloutStat.isNotBlank()) add("saved_stat")
            }
            val array = buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", "root")
                        put("component", "Card")
                        put("child", "saved_col")
                    }
                )
                add(
                    buildJsonObject {
                        put("id", "saved_col")
                        put("component", "Column")
                        putJsonArray("children") {
                            childIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        }
                    }
                )
                add(
                    buildJsonObject {
                        put("id", "saved_headline")
                        put("component", "Text")
                        putJsonObject("properties") {
                            put("text", headline)
                            put("variant", "h5")
                        }
                    }
                )
                if (subline.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("id", "saved_subline")
                            put("component", "Text")
                            putJsonObject("properties") {
                                put("text", subline)
                                put("variant", "caption")
                            }
                        }
                    )
                }
                if (calloutStat.isNotBlank()) {
                    add(
                        buildJsonObject {
                            put("id", "saved_stat")
                            put("component", "Text")
                            putJsonObject("properties") {
                                put("text", calloutStat)
                                put("variant", "body")
                            }
                        }
                    )
                }
            }
            return array.toString()
        }
    }
}
