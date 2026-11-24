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
    val brollMediaUri: String? = null
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
 * Reusable AI-generated (or user-refined) stock Jetpack A2UI overlay object created on the
 * optional Newsroom Overlay page and saved in Room for optional attachment to news briefs
 * during Stage 4 (`CHOP_INTO_BROLL`) of the Core Pipeline.
 */
@Serializable
data class SavedNewsroomOverlay(
    val id: String = "ovl_" + UUID.randomUUID().toString().replace("-", "").take(10),
    val kind: OverlayObjectKind = OverlayObjectKind.LOWER_THIRD,
    val headline: String,
    val subline: String,
    val calloutStat: String = "",
    val durationSec: Int = 4,
    val a2uiComponentsJson: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

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
    val talkingHeadSha: String? = null,
    val talkingHeadVideoUrl: String? = null,
    val alignedWords: List<AlignedWord> = emptyList(),
    val selectedOverlayIds: List<String> = emptyList(),
    val finalClipId: String? = null,
    val finalVideoSha: String? = null,
    val finalVideoUrl: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val fullStoryText: String
        get() = clauses.map { it.spokenText.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    val hasTtsAudio: Boolean
        get() = !ttsVoiceSha.isNullOrBlank() || !ttsAudioUrl.isNullOrBlank()

    val hasTalkingHead: Boolean
        get() = !talkingHeadSha.isNullOrBlank() || !talkingHeadVideoUrl.isNullOrBlank()

    val hasFinalVideo: Boolean
        get() = !finalVideoSha.isNullOrBlank() || !finalVideoUrl.isNullOrBlank()
}
