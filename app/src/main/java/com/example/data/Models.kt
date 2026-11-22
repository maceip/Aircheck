package com.example.data

import androidx.annotation.DrawableRes
import com.example.R
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

enum class BrandChannel(val label: String, val accentHex: String) {
    VOLT_LIME("Volt Lime", "#B8FF00"),
    BROADCAST_COBALT("Broadcast Cobalt", "#0088FF");

    companion object {
        fun fromRaw(raw: String?): BrandChannel =
            entries.firstOrNull { it.name == raw } ?: VOLT_LIME
    }
}

enum class StudioTab(val label: String) {
    SCRIPT("Script"),
    STAGE("Stage"),
    REVIEW("Review")
}

enum class VoiceSource(val label: String, val subtitle: String) {
    MALE_TTS_DRY_RUN("Male Dry TTS", "Verified adult-male on-device WAV"),
    MIC_VOICEOVER("Mic Voiceover", "Record live 16-bit PCM studio take"),
    VOICE_CLONE("Voice Clone", "Personal voice clone endpoint");

    companion object {
        fun fromRaw(raw: String?): VoiceSource =
            entries.firstOrNull { it.name == raw } ?: MALE_TTS_DRY_RUN
    }
}

enum class BackdropPreset(
    val id: String,
    val label: String,
    val promptHint: String,
    @DrawableRes val bundledDrawable: Int? = null
) {
    VOLT_TRUSS_GLOBE(
        id = "volt_truss_globe",
        label = "Volt Truss Globe",
        promptHint = "Dark obsidian global newsroom stage with glowing acid-volt lime broadcast globe and steel lighting trusses in crisp out-of-focus background bokeh",
        bundledDrawable = R.drawable.stage_truss_globe_lime
    ),
    COBALT_TRUSS_GLOBE(
        id = "cobalt_truss_globe",
        label = "Cobalt Truss Globe",
        promptHint = "Dark obsidian global newsroom stage with glowing electric cobalt-blue broadcast globe and steel studio lighting trusses in crisp out-of-focus background bokeh",
        bundledDrawable = R.drawable.stage_truss_globe_cobalt
    ),
    NEON_TRIANGLE_RIG(
        id = "neon_triangle_rig",
        label = "Neon Triangle Rig",
        promptHint = "Industrial dark broadcast soundstage with a softly blurred glowing neon triangle lighting rig behind the anchor",
        bundledDrawable = R.drawable.stage_triangle_rig_lime
    ),
    OBSIDIAN_NEWSDESK(
        id = "obsidian_newsdesk",
        label = "Obsidian Newsdesk",
        promptHint = "Ultramodern pitch-black obsidian television newsdesk studio with subtle cool architectural rim lights and soft depth of field"
    ),
    CYBER_TELEMETRY_WALL(
        id = "cyber_telemetry_wall",
        label = "Telemetry Wall",
        promptHint = "High-tech broadcast studio with soft dark glass data wall and subtle cyan telemetry accents far out of focus behind the speaker"
    ),
    WARM_EDITORIAL_LOFT(
        id = "warm_editorial_loft",
        label = "Editorial Loft",
        promptHint = "Architectural dark walnut and acoustic slate editorial loft studio with warm tungsten key lighting and shallow depth of field"
    ),
    MINIMAL_MONOCHROME(
        id = "minimal_monochrome",
        label = "Minimal Monochrome",
        promptHint = "Minimal matte charcoal studio cyclorama with crisp three-point editorial broadcast lighting and soft rim separation"
    );

    companion object {
        fun fromId(id: String?): BackdropPreset =
            entries.firstOrNull { it.id == id } ?: VOLT_TRUSS_GLOBE
    }
}

data class ScriptBeat(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(12),
    val index: Int = 0,
    val spokenText: String = "",
    val proposedShortText: String? = null,
    val durationSec: Int = 5,
    val cut: Boolean = false,
    val mentionTag: String = "",
    val depthTextTag: String = "",
    val evidenceSource: String = "",
    val sfxTag: String = "",
    val phoneticOverride: String = "",
    val bRollPrompt: String = "",
    val bRollImageKey: String? = null,
    val beatAudioKey: String? = null
) {
    val wordCount: Int
        get() = spokenText.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    val effectiveDurationSec: Int
        get() = if (cut) 0 else max(3, estimateDurationSeconds(spokenText, durationSec))

    val hasCitation: Boolean
        get() = evidenceSource.isNotBlank()
}

data class NewsroomStageConfig(
    val backdropPresetId: String = BackdropPreset.VOLT_TRUSS_GLOBE.id,
    val customBackdropPrompt: String = "",
    val selfieImageKey: String? = null,
    val compositedAnchorKey: String? = null,
    val faceValidated: Boolean = false,
    val faceValidationNote: String = "",
    val mouthCenterX: Float = 0.5f,
    val mouthCenterY: Float = 0.54f,
    val mouthWidthNorm: Float = 0.14f,
    val chyronHeadline: String = "",
    val chyronSubline: String = "",
    val cornerBugText: String = "AIRCHECK",
    val showBrandBug: Boolean = true
)

data class ScriptProject(
    val id: String = "proj_" + UUID.randomUUID().toString().replace("-", "").take(12),
    val userId: String = "",
    val title: String = "",
    val ideaSpark: String = "",
    val targetDurationSec: Int = 30,
    val beats: List<ScriptBeat> = listOf(
        ScriptBeat(
            id = "beat_initial_1",
            index = 0,
            spokenText = "",
            durationSec = 6
        )
    ),
    val stageConfig: NewsroomStageConfig = NewsroomStageConfig(),
    val voiceSource: VoiceSource = VoiceSource.MALE_TTS_DRY_RUN,
    val voiceSelectionId: String = "",
    val renderedVideoKey: String? = null,
    val masterAudioKey: String? = null,
    val exportedUri: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val activeBeats: List<ScriptBeat>
        get() = beats.filter { !it.cut && it.spokenText.isNotBlank() }

    val totalActiveSeconds: Int
        get() = activeBeats.sumOf { it.effectiveDurationSec }

    val totalActiveWords: Int
        get() = activeBeats.sumOf { it.wordCount }
}

data class StudioPreset(
    val id: String = "preset_" + UUID.randomUUID().toString().replace("-", "").take(10),
    val userId: String = "",
    val name: String = "",
    val backdropPresetId: String = BackdropPreset.VOLT_TRUSS_GLOBE.id,
    val targetDurationSec: Int = 30,
    val voiceSource: VoiceSource = VoiceSource.MALE_TTS_DRY_RUN
)

enum class ExportState {
    IDLE,
    EXPORTING,
    EXPORTED
}

data class RenderProgress(
    val active: Boolean = false,
    val fraction: Float = 0f,
    val statusLabel: String = ""
)

fun estimateDurationSeconds(text: String, fallback: Int = 5): Int {
    val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    if (words == 0) return max(3, fallback)
    // Conversational newsroom cadence ~2.6 words/sec, minimum 3s
    return max(3, (words / 2.6f).roundToInt())
}

fun formatDurationBadge(seconds: Int): String {
    val clamped = max(0, seconds)
    return if (clamped < 10) "0${clamped}s" else "${clamped}s"
}

fun formatTimecode(seconds: Float): String {
    val total = max(0, seconds.toInt())
    val mins = total / 60
    val secs = total % 60
    return "%02d:%02d".format(mins, secs)
}
