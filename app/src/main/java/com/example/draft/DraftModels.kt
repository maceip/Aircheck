package com.example.draft

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class Stage(val code: String, val label: String) {
    SEED("01", "SEED"),
    PICK("02", "PICK"),
    CUT("03", "CUT"),
    SHIP("04", "SHIP")
}

/** Clip Studio v5 tone selected on 01 SPARK and propagated across all drafting micro-passes. */
@Serializable
enum class DraftTone(val wireValue: String, val label: String) {
    DIRECT("direct", "direct"),
    HOT_TAKE("hot_take", "hot take"),
    STORY("story", "story"),
    EXPLAINER("explainer", "explainer"),
    SHITPOST("shitpost", "shitpost"),
    SOLARPUNK("solarpunk", "solarpunk");

    companion object {
        fun fromWire(raw: String?): DraftTone {
            val normalized = raw?.trim()?.lowercase()?.replace(Regex("[\\s-]+"), "_").orEmpty()
            return entries.firstOrNull {
                it.wireValue == normalized || it.name.equals(normalized, ignoreCase = true)
            } ?: HOT_TAKE
        }
    }
}

/**
 * Carries the active session's [DraftTone] across suspend boundaries (including through
 * [com.example.ai.LlmStageRouter]) into [DraftApiClient] without shared mutable state races.
 */
class DraftToneContext(val tone: DraftTone) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DraftToneContext>
}

/**
 * Carries existing per-beat shot plans on single-beat arc rerolls so non-rerolled beats preserve
 * user-customized shots when routed through [com.example.ai.LlmStageRouter].
 */
class ExistingBeatShotsContext(
    val beatShots: List<List<ShotSpec>>
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExistingBeatShotsContext>
}

/** Clip Studio v5 per-beat shot types (`face -> pip -> broll -> card`). */
@Serializable
enum class ShotType(val wireValue: String, val label: String) {
    FACE("face", "face"),
    PIP("pip", "corner head"),
    BROLL("broll", "b-roll"),
    CARD("card", "card");

    fun nextInCycle(): ShotType = when (this) {
        FACE -> PIP
        PIP -> BROLL
        BROLL -> CARD
        CARD -> FACE
    }

    companion object {
        val DEFAULT_V5_BEAT_SHOTS: List<List<ShotType>> = listOf(
            listOf(FACE, PIP),
            listOf(PIP, BROLL, BROLL),
            listOf(BROLL, PIP),
            listOf(FACE, CARD)
        )

        fun defaultTypesForBeat(beatIndex: Int): List<ShotType> =
            DEFAULT_V5_BEAT_SHOTS[beatIndex.coerceAtLeast(0) % DEFAULT_V5_BEAT_SHOTS.size]

        fun fromWire(raw: String?): ShotType {
            val clean = raw?.trim()?.lowercase()?.replace(Regex("[\\s_-]+"), "").orEmpty()
            return when (clean) {
                "face", "talkinghead", "head" -> FACE
                "pip", "cornerhead", "pictureinpicture" -> PIP
                "broll", "cutaway" -> BROLL
                "card", "quotecard", "statcard" -> CARD
                else -> FACE
            }
        }
    }
}

/** One shot block in an [ArcBeat]'s shot plan. */
@Immutable
@Serializable
data class ShotSpec(
    val id: String = "shot_" + UUID.randomUUID().toString().replace("-", "").take(12),
    val type: ShotType = ShotType.FACE,
    val prompt: String = ""
)

enum class EditSource {
    MODEL_INIT,
    BRUSH_SWAP,
    VOICE_SPLICE,
    DENSITY_SCRUB,
    MANUAL_RAW,
    SALVAGE_SPLICE,
    REORDER,
    STRIKE_CUT,
    WEAVE_MENTION
}

@Immutable
data class WordToken(
    val text: String,
    val trailingSpace: String = " ",
    val verbatimLocked: Boolean = false
)

@Immutable
data class ClauseVersion(
    val tokens: List<WordToken>,
    val source: EditSource
) {
    fun plainText(): String {
        if (tokens.isEmpty()) return ""
        return buildString {
            tokens.forEachIndexed { index, token ->
                append(token.text)
                if (index < tokens.lastIndex) {
                    append(token.trailingSpace)
                } else if (token.trailingSpace.contains('\n')) {
                    append(token.trailingSpace.trimEnd(' ', '\t'))
                }
            }
        }
    }
}

@Immutable
data class ClauseNode(
    val id: String,
    val versions: List<ClauseVersion>,
    val activeVersionIndex: Int
) {
    val activeVersion: ClauseVersion
        get() = if (versions.isEmpty()) {
            ClauseVersion(emptyList(), EditSource.MODEL_INIT)
        } else {
            versions[activeVersionIndex.coerceIn(0, versions.lastIndex)]
        }

    fun plainText(): String = activeVersion.plainText()

    fun hasAnyLockedSpan(): Boolean = activeVersion.tokens.any { it.verbatimLocked }

    val canStepBackward: Boolean
        get() = versions.isNotEmpty() && activeVersionIndex.coerceIn(0, versions.lastIndex) > 0

    val canStepForward: Boolean
        get() = versions.isNotEmpty() && activeVersionIndex.coerceIn(0, versions.lastIndex) < versions.lastIndex
}

@Immutable
data class LockedPhrase(
    val id: String,
    val text: String,
    val sourcePosture: String = ""
)

@Immutable
data class AngleChip(
    val id: String,
    val label: String,
    val preview: String,
    val isBlindspot: Boolean,
    val selected: Boolean
)

@Immutable
data class CandidateDraft(
    val postureLabel: String,
    val clauses: List<ClauseNode>,
    val globalSnapshots: List<List<ClauseNode>>,
    val snapshotCursor: Int,
    val linkedSplitCards: List<List<ClauseNode>> = emptyList()
) {
    val canUndoGlobal: Boolean
        get() = globalSnapshots.isNotEmpty() && snapshotCursor.coerceIn(0, globalSnapshots.lastIndex) > 0

    val canRedoGlobal: Boolean
        get() = globalSnapshots.isNotEmpty() && snapshotCursor.coerceIn(0, globalSnapshots.lastIndex) < globalSnapshots.lastIndex

    fun plainText(): String = DraftStateReducer.renderPlainText(clauses)

    val charCount: Int
        get() = plainText().length

    val isOverflowing280: Boolean
        get() = charCount > 280
}

@Immutable
sealed interface XAttachment {
    @Immutable
    data class QuotePost(
        val handle: String,
        val tweetId: String,
        val url: String,
        val snippet: String
    ) : XAttachment

    @Immutable
    data class ReplyTarget(
        val handle: String,
        val tweetId: String,
        val snippet: String
    ) : XAttachment
}

/** One beat of the proposed spoken arc returned by `POST /v1/draft/arc` or `/v1/draft/followup`. */
@Immutable
data class ArcBeat(
    val label: String,
    val startSec: Int,
    val endSec: Int,
    val text: String,
    val shots: List<ShotSpec> = emptyList(),
    val id: String = "",
    val window: String = "${startSec}-${endSec}s"
) {
    val shotSummaryLabel: String
        get() = shots.map { it.type.label }.distinct().joinToString(" · ")
}

/** One LLM-authored themed direction card returned by `POST /v1/draft/followup` (`angle="overview"`). */
@Immutable
data class FollowupDirectionCard(
    val id: String,
    val theme: String,
    val words: String,
    val beats: List<ArcBeat> = emptyList()
)

@Immutable
data class DraftDocument(
    val id: String,
    val stage: Stage = Stage.SEED,
    val tone: DraftTone = DraftTone.HOT_TAKE,
    val seedTranscript: String = "",
    val steeringConstraints: List<String> = emptyList(),
    val extractedAngles: List<AngleChip> = emptyList(),
    val blindspotAngles: List<AngleChip> = emptyList(),
    val candidates: List<CandidateDraft> = emptyList(),
    val activeCandidateIndex: Int = 0,
    val salvagedPhrases: List<LockedPhrase> = emptyList(),
    val attachment: XAttachment? = null,
    val arcBeats: List<ArcBeat> = emptyList(),
    val arcUndoStack: List<List<ArcBeat>> = emptyList(),
    /** Target runtime and word budget echoed by the rig for the current arc/script (0 = none yet). */
    val arcTargetSec: Int = 0,
    val arcWordBudget: Int = 0,
    /** Index of the most recently re-rolled arc beat, or -1. */
    val lastRerolledBeat: Int = -1,
    /** True once candidates came from `/v1/draft/synthesize-script` (video script, not a 280-char post). */
    val isVideoScript: Boolean = false,
    /** True when the current SPARK outline was loaded from a follow-up draft. */
    val isFollowupLoaded: Boolean = false,
    /** Display label for the follow-up note strip (`"${video.title} · $angleLabel"`). */
    val followupSourceLabel: String = ""
) {
    val activeCandidate: CandidateDraft?
        get() = candidates.getOrNull(activeCandidateIndex)

    val canUndoArc: Boolean
        get() = arcUndoStack.isNotEmpty()

    val shotPlan: List<List<ShotSpec>>
        get() = arcBeats.map { it.shots }
}
