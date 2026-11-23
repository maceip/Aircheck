package com.example.graph

import androidx.compose.runtime.Immutable

enum class MatchReason(val label: String) {
    COUNTER_ARGUMENT("Counter-argument"),
    MAINTAINER("Maintainer"),
    ACTIVE_THREAD("Active thread"),
    PEER("Peer")
}

@Immutable
data class GraphContactRecord(
    val handle: String,
    val displayName: String,
    val bio: String,
    val followerCount: Int,
    val mutualRepliesCount: Int,
    val lastInteractionEpochMs: Long
)

@Immutable
data class GraphPostRecord(
    val tweetId: String,
    val handle: String,
    val text: String,
    val createdAtEpochMs: Long,
    val isReplyToUser: Boolean = false
)

@Immutable
data class GraphMatch(
    val handle: String,
    val tweetId: String,
    val postUrl: String,
    val snippet: String,
    val postAgeLabel: String,
    val lastReplyAgeLabel: String,
    val reason: MatchReason,
    val score: Double
)

@Immutable
data class ImportSummary(
    val contactsImported: Int,
    val postsImported: Int
)

@Immutable
data class ParsedGraphSnapshot(
    val contacts: List<GraphContactRecord>,
    val posts: List<GraphPostRecord>
)

private val HANDLE_CLEAN_REGEX = Regex("[^a-z0-9_]")

/**
 * Normalizes an X/Twitter handle into canonical lowercase form without a leading '@'.
 */
fun normalizeGraphHandle(raw: String): String {
    val trimmed = raw.trim().trimStart('@').lowercase()
    return HANDLE_CLEAN_REGEX.replace(trimmed, "")
}
