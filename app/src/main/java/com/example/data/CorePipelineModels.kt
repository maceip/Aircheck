package com.example.data

import kotlinx.serialization.Serializable
import java.util.UUID

enum class CorePipelineStage(
    val stepNumber: Int,
    val shortLabel: String,
    val title: String
) {
    TTS(1, "01 TTS", "TTS Voice Pass"),
    ITERATE_STORY(2, "02 STORY", "Iterate on Story"),
    SPEECH_TO_TALKING_HEAD(3, "03 HEAD", "Speech Matched to Talking Head"),
    CHOP_INTO_BROLL(4, "04 B-ROLL", "Talking Head Chopped into B-Roll"),
    FINAL_REVIEW(5, "05 REVIEW", "Final Review");

    companion object {
        fun fromRaw(raw: String?): CorePipelineStage =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: TTS
    }
}

@Serializable
enum class BrollSegmentMode(val label: String) {
    TALKING_HEAD("Talking Head"),
    BROLL_CUTAWAY("B-Roll Cutaway")
}

@Serializable
data class CoreStoryClause(
    val id: String = "clause_" + UUID.randomUUID().toString().replace("-", "").take(10),
    val index: Int = 0,
    val spokenText: String = "",
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val segmentMode: BrollSegmentMode = BrollSegmentMode.TALKING_HEAD,
    val brollCaption: String = "",
    val brollMediaUri: String? = null,
    val bRollPrompt: String = "",
    val bRollSha: String? = null,
    val depthTextTag: String = "",
    val shotTypeWire: String = ""
) {
    val durationMs: Long
        get() = (endMs - startMs).coerceAtLeast(0L)
}

@Serializable
enum class OverlayObjectKind(val label: String) {
    LOWER_THIRD("Lower Third"),
    STAT_CALLOUT("Stat Callout"),
    QUOTE_BRIEF("Quote Brief");

    companion object {
        fun fromRaw(raw: String?): OverlayObjectKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: LOWER_THIRD
    }
}

/**
 * Broadcast breaking-news chip / kicker treatment persisted on [CoreRenderOptions] and
 * [SavedNewsroomOverlay], previewed live in `04 NEWSROOM`, and rendered by both `studio_cut.py`
 * and Remotion 4 (`BroadcastTemplate.tsx`).
 */
@Serializable
enum class BreakingNewsStyle(
    val wire: String,
    val chipLabel: String,
    val bannerText: String
) {
    BREAKING("breaking", "breaking news", "BREAKING NEWS"),
    DEVELOPING("developing", "developing", "DEVELOPING"),
    EXCLUSIVE("exclusive", "exclusive", "EXCLUSIVE"),
    ALERT_STAT("alert_stat", "data alert", "DATA ALERT"),
    CLEAN("clean", "clean desk", "");

    /** Maps to Clip Studio v5 `Video look` -> `TAG ABOVE TITLE` pill key (`none | breaking | developing | explainer`). */
    val titleTagKey: String
        get() = when (this) {
            CLEAN -> "none"
            BREAKING -> "breaking"
            DEVELOPING -> "developing"
            EXCLUSIVE, ALERT_STAT -> "explainer"
        }

    companion object {
        fun fromWire(raw: String?): BreakingNewsStyle =
            entries.firstOrNull {
                it.wire.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true)
            } ?: BREAKING

        fun fromTitleTag(tag: String?): BreakingNewsStyle = when (tag?.trim()?.lowercase()) {
            "none", "clean", "" -> CLEAN
            "breaking" -> BREAKING
            "developing" -> DEVELOPING
            "explainer", "exclusive" -> EXCLUSIVE
            "alert_stat", "data_alert", "stat" -> ALERT_STAT
            else -> fromWire(tag)
        }
    }
}

/**
 * Reusable AI-generated (or user-refined) stock Jetpack A2UI overlay object created on the
 * Newsroom Overlay page and saved in Room for attachment to news briefs during Stage 4
 * (`CHOP_INTO_BROLL`) of the Core Pipeline.
 */
@Serializable
data class SavedNewsroomOverlay(
    val id: String = "ovl_" + UUID.randomUUID().toString().replace("-", "").take(10),
    val kind: OverlayObjectKind = OverlayObjectKind.LOWER_THIRD,
    val breakingStyle: BreakingNewsStyle = BreakingNewsStyle.BREAKING,
    val headline: String,
    val subline: String,
    val calloutStat: String = "",
    val durationSec: Int = 4,
    val a2uiComponentsJson: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

/** Clip Studio v4 render options sent as `render_options` to `POST /v1/core/chop-broll`. */
@Serializable
data class CoreRenderOptions(
    val stripSilence: Boolean = false,
    val punchIn: Boolean = false,
    val captions: Boolean = false,
    /** "9:16" (720x1280) or "16:9" (1280x720). */
    val aspect: String = ASPECT_VERTICAL,
    val breakingStyle: BreakingNewsStyle = BreakingNewsStyle.BREAKING,
    val chyronHeadline: String = "",
    val chyronSubline: String = "",
    val showBrandBug: Boolean = false,
    val cornerBugText: String = "LIVE",
    /** Cut-scene B-roll framing: "pip" (Corner PiP), "full" (Full Cutaway), or "split" (50/50 Split). */
    val brollFraming: String = BROLL_FRAMING_PIP,
    val useRemotion: Boolean? = null
) {
    companion object {
        const val ASPECT_VERTICAL: String = "9:16"
        const val ASPECT_HORIZONTAL: String = "16:9"
        const val BROLL_FRAMING_PIP: String = "pip"
        const val BROLL_FRAMING_FULL: String = "full"
        const val BROLL_FRAMING_SPLIT: String = "split"
    }
}

/** An internal pause the rig's `silencedetect` found in the talking-head render. */
@Serializable
data class SilenceGap(val startMs: Long, val endMs: Long)

/** Real quality & verification metrics returned by `/v1/core/tts`, `/v1/jobs/{id}`, or local `v002`. */
@Serializable
data class RigQualityMetrics(
    val cer: Double? = null,
    val wespeakerCosine: Double? = null,
    val dnsmosP808: Double? = null,
    val tailRmsDbfs: Double? = null,
    val masteredLufs: Double? = null,
    val syllablesPerSec: Double? = null,
    val maxPhonemeDurationMs: Double? = null,
    val avSyncDriftMs: Double? = null,
    val passedAllStrictGates: Boolean? = null
)

/** Stats echoed by the rig for the last studio cut. */
@Serializable
data class StudioCutStats(
    val brollCutCount: Int = 0,
    val appliedOverlayCount: Int = 0,
    val captionCount: Int = 0,
    val punchInCount: Int = 0,
    val removedSilenceGapCount: Int = 0,
    val removedSilenceMs: Long = 0L,
    val aspect: String = CoreRenderOptions.ASPECT_VERTICAL,
    val remotionUsed: Boolean = false,
    val renderer: String? = null
)

/**
 * A saved Newsroom look (backdrop plate + studio grade) applied at render time by the rig's
 * studio cut (`render_options.look`).
 */
@Serializable
data class NewsroomLook(
    val id: String = "look_" + UUID.randomUUID().toString().replace("-", "").take(10),
    val name: String,
    /** Rig plate id (`plate_<12 hex>`) produced by `POST /v1/newsroom/backdrop`, or null for no plate. */
    val plateId: String? = null,
    val plateUrl: String? = null,
    val platePrompt: String = "",
    val blurPercent: Int = 0,
    val brightnessEv: Double = 0.0,
    /** "warm", "cool", or "neutral". */
    val light: String = LIGHT_NEUTRAL,
    /** Primary broadcast accent swatch hex (e.g. "#D71921", "#E6A100", "#3A86F2", "#44D498"). */
    val accentHex: String = DEFAULT_ACCENT_HEX,
    val grain: Boolean = false,
    val border: Boolean = false,
    val isDefault: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val LIGHT_WARM: String = "warm"
        const val LIGHT_COOL: String = "cool"
        const val LIGHT_NEUTRAL: String = "neutral"
        const val DEFAULT_ACCENT_HEX: String = "#D71921"
        const val ACCENT_CRIMSON: String = "#D71921"
        const val ACCENT_AMBER: String = "#E6A100"
        const val ACCENT_COBALT: String = "#3A86F2"
        const val ACCENT_MINT: String = "#44D498"
    }
}

/**
 * Persisted state for the single canonical 5-stage Aircheck Core Pipeline:
 * `TTS -> Iterate on Story -> Speech Matched to Talking Head -> Talking Head Chopped into B-Roll -> Final Review`.
 */
@Serializable
data class CorePipelineSession(
    val id: String = "pipe_" + UUID.randomUUID().toString().replace("-", "").take(12),
    val scriptId: String = "",
    val title: String = "",
    val stage: CorePipelineStage = CorePipelineStage.TTS,
    val clauses: List<CoreStoryClause> = emptyList(),
    val ttsVoiceSha: String? = null,
    val ttsAudioUrl: String? = null,
    val ttsDurationMs: Long = 0L,
    val waveformPeaks: List<Float> = emptyList(),
    val ttsIterationCount: Int = 0,
    val talkingHeadJobId: String? = null,
    /** Rig render job started for Stage 3 but not yet finished (resumed after process death). */
    val pendingHeadJobId: String? = null,
    /** Rig async task (`task_...`) in flight for a long stage, keyed by stage ("tts"/"chop"/"backdrop:<lookId>"). */
    val pendingRigTasks: Map<String, String> = emptyMap(),
    val talkingHeadSha: String? = null,
    val talkingHeadVideoUrl: String? = null,
    val alignedWords: List<AlignedWord> = emptyList(),
    val selectedOverlayIds: List<String> = emptyList(),
    val finalClipId: String? = null,
    val finalVideoSha: String? = null,
    val finalVideoUrl: String? = null,
    val renderOptions: CoreRenderOptions = CoreRenderOptions(),
    /** Clause texts exactly as they were last voiced by Stage 1 TTS (empty = never voiced). */
    val voicedClauseTexts: List<String> = emptyList(),
    /** Silence gaps detected in the current talking-head render ([silenceGapsJobId]). */
    val silenceGaps: List<SilenceGap> = emptyList(),
    val silenceGapsJobId: String? = null,
    val lastCutStats: StudioCutStats? = null,
    /** [cutSignature] of the inputs used for the last successful studio cut. */
    val lastCutSignature: String? = null,
    val alignUrl: String? = null,
    val ttsMetrics: RigQualityMetrics? = null,
    val headMetrics: RigQualityMetrics? = null,
    val ttsSource: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    /** Everything that changes the studio cut output: clauses, options, overlays, and the look. */
    fun cutSignature(look: NewsroomLook?): String = buildString {
        clauses.forEach {
            append(it.spokenText.trim()).append('|')
                .append(it.segmentMode.name).append('|')
                .append(it.brollCaption).append('|')
                .append(it.bRollPrompt).append('|')
                .append(it.bRollSha.orEmpty()).append('|')
                .append(it.depthTextTag).append('\n')
        }
        append(renderOptions.toString()).append('\n')
        append(selectedOverlayIds.sorted().joinToString(",")).append('\n')
        append(talkingHeadJobId.orEmpty()).append('\n')
        if (look != null) {
            append(look.plateId.orEmpty()).append(look.blurPercent).append(look.brightnessEv)
                .append(look.light).append(look.accentHex).append(look.grain).append(look.border)
        }
    }

    /** Indices of clauses edited after their audio was voiced (need re-voicing). */
    val unsyncedClauseIndices: List<Int>
        get() = if (voicedClauseTexts.isEmpty()) {
            emptyList()
        } else {
            clauses.indices.filter { idx ->
                clauses[idx].spokenText.trim() != voicedClauseTexts.getOrNull(idx)?.trim()
            }
        }

    val fullStoryText: String
        get() = clauses.map { it.spokenText.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    val hasTtsAudio: Boolean
        get() = !ttsVoiceSha.isNullOrBlank() || !ttsAudioUrl.isNullOrBlank()

    val hasTalkingHead: Boolean
        get() = !talkingHeadSha.isNullOrBlank() || !talkingHeadVideoUrl.isNullOrBlank()

    val hasFinalVideo: Boolean
        get() = !finalVideoSha.isNullOrBlank() || !finalVideoUrl.isNullOrBlank()
}
