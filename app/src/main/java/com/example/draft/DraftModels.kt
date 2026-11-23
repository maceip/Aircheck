package com.example.draft

import androidx.compose.runtime.Immutable

enum class Stage(val code: String, val label: String) {
    SEED("01", "SEED"),
    PICK("02", "PICK"),
    CUT("03", "CUT"),
    SHIP("04", "SHIP")
}

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

@Immutable
data class DraftDocument(
    val id: String,
    val stage: Stage = Stage.SEED,
    val seedTranscript: String = "",
    val steeringConstraints: List<String> = emptyList(),
    val extractedAngles: List<AngleChip> = emptyList(),
    val blindspotAngles: List<AngleChip> = emptyList(),
    val candidates: List<CandidateDraft> = emptyList(),
    val activeCandidateIndex: Int = 0,
    val salvagedPhrases: List<LockedPhrase> = emptyList(),
    val attachment: XAttachment? = null
) {
    val activeCandidate: CandidateDraft?
        get() = candidates.getOrNull(activeCandidateIndex)
}
