package com.example.data

import androidx.annotation.DrawableRes
import com.example.R
import kotlinx.serialization.Serializable
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

enum class VoiceSource(val label: String, val subtitle: String) {
    MIC_VOICEOVER("Mic Voiceover", "Record live 16-bit PCM take on your phone"),
    ENROLLED_VOICE_CLONE("Local Voice Clone", "Synthesize on Mac Rig with s2-pro + LoRA");

    companion object {
        fun fromRaw(raw: String?): VoiceSource = when (raw) {
            "ENROLLED_VOICE_CLONE" -> ENROLLED_VOICE_CLONE
            else -> MIC_VOICEOVER
        }
    }
}

enum class Stage4Mode(val id: String, val label: String, val subtitle: String) {
    PHOTO_HEAD_LORA(
        id = "photo_head_lora",
        label = "Photo + Head LoRA",
        subtitle = "1 phone photo + personal head_model_id"
    ),
    SAVED_LOOP_LATENTSYNC(
        id = "saved_loop_latentsync",
        label = "Saved Desk Loop",
        subtitle = "LatentSync over enrolled studio loop"
    ),
    LIBRECLIP_CROP(
        id = "libreclip_crop",
        label = "LibreClip 9:16",
        subtitle = "Active-speaker crop from long-form footage"
    );

    companion object {
        fun fromRaw(raw: String?): Stage4Mode =
            entries.firstOrNull { it.name == raw || it.id == raw } ?: PHOTO_HEAD_LORA
    }
}

enum class RenderJobStatus(val label: String) {
    QUEUED("Queued"),
    VOICE("Voice"),
    ALIGN("Align"),
    LIPSYNC("Head Render"),
    PACKAGING("Packaging"),
    READY("Ready"),
    FAILED("Failed");

    val isTerminal: Boolean get() = this == READY || this == FAILED
    val isActive: Boolean get() = !isTerminal

    companion object {
        fun fromRaw(raw: String?): RenderJobStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: QUEUED
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

@Serializable
data class AlignedWord(
    val word: String,
    val startMs: Long,
    val endMs: Long
)

@Serializable
data class PoseGateResult(
    val checked: Boolean = false,
    val valid: Boolean = false,
    val pitchDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val mouthOccluded: Boolean = false,
    val canNormalize: Boolean = false,
    val note: String = ""
)

data class ScriptBeat(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(12),
    val index: Int = 0,
    val spokenText: String = "",
    val proposedShortText: String? = null,
    val durationSec: Int = 5,
    val cut: Boolean = false,
    val mentionTag: String = "",
    val depthTextTag: String = "",
    val anchorWord: String = "",
    val evidenceSource: String = "",
    val sfxTag: String = "",
    val phoneticOverride: String = "",
    val bRollPrompt: String = "",
    val bRollSha: String? = null,
    val beatAudioSha: String? = null
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
    val photoSha: String? = null,
    val normalizedPhotoSha: String? = null,
    val poseGate: PoseGateResult = PoseGateResult(),
    val stage4Mode: Stage4Mode = Stage4Mode.PHOTO_HEAD_LORA,
    val chyronHeadline: String = "",
    val chyronSubline: String = "",
    val cornerBugText: String = "AIRCHECK",
    val showBrandBug: Boolean = true
) {
    val activePhotoSha: String?
        get() = normalizedPhotoSha ?: photoSha
}

data class ScriptProject(
    val id: String = "proj_" + UUID.randomUUID().toString().replace("-", "").take(12),
    val userId: String = "",
    val title: String = "",
    val ideaSpark: String = "",
    val sourceUrl: String = "",
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
    val voiceSource: VoiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
    val voiceIdOverride: String = "",
    val masterAudioSha: String? = null,
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

data class ClipRecord(
    val id: String,
    val scriptId: String,
    val jobId: String,
    val title: String,
    val photoSha: String?,
    val voiceSha: String?,
    val alignSha: String?,
    val lipsyncSha: String?,
    val finalSha: String?,
    val status: RenderJobStatus,
    val progressFraction: Float,
    val stageDetail: String,
    val errorMessage: String?,
    val durationMs: Long,
    val waveformPeaks: List<Float>,
    val thumbHash: String?,
    val alignedWords: List<AlignedWord>,
    val chyronHeadline: String,
    val chyronSubline: String,
    val remoteLipsyncUrl: String?,
    val remoteFinalUrl: String?,
    val exportedUri: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class ClipCardRow(
    val id: String,
    val scriptId: String,
    val jobId: String,
    val title: String,
    val photoSha: String?,
    val finalSha: String?,
    val status: RenderJobStatus,
    val progressFraction: Float,
    val stageDetail: String,
    val errorMessage: String?,
    val durationMs: Long,
    val thumbHash: String?,
    val remoteFinalUrl: String?,
    val updatedAtMillis: Long
)

data class PersonaProfile(
    val headModelId: String = "",
    val voiceId: String = "",
    val defaultPhotoSha: String = "",
    val trainingJobId: String = "",
    val trainingStatus: String = "",
    val trainingError: String = ""
) {
    val isEnrolled: Boolean get() = headModelId.isNotBlank() && voiceId.isNotBlank()
}

data class StageProviderSettings(
    val voiceProviderId: String = "voicestudio_s2pro_local",
    val alignProviderId: String = "sherpa_onnx_local",
    val headProviderId: String = "mac_rig_wan30",
    val packageProviderId: String = "mac_rig_ffmpeg",
    val lanGatewayUrl: String = "http://10.0.0.242:8443",
    val useLanVoice: Boolean = true,
    val useLanAlign: Boolean = true,
    val useLanHead: Boolean = true,
    val useLanPackage: Boolean = true
)

enum class ExportState {
    IDLE,
    EXPORTING,
    EXPORTED
}

fun estimateDurationSeconds(text: String, fallback: Int = 5): Int {
    val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    if (words == 0) return max(3, fallback)
    return max(3, (words / 2.6f).roundToInt())
}
