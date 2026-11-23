package com.example.graph

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Stage 4 Local Network Graph Radar ranking & matching engine.
 *
 * Combines local SQLite FTS5 BM25 keyword scores, 128-dim local vector cosine similarity,
 * and mutual-reply recency weighting with an explicit broadcast-account penalty so
 * high-follower broadcast accounts with zero mutual replies never crowd out peers,
 * collaborators, maintainers, and active sparring partners.
 *
 * Returns an empty list honestly whenever the local graph has 0 imported contacts.
 */
class GraphRadarEngine(
    private val database: LocalGraphDatabase
) {

    fun computeRankScore(
        semanticSimilarity: Double,
        keywordBm25Normalized: Double,
        mutualRecencyWeight: Double,
        followerCount: Int,
        mutualRepliesCount: Int
    ): Double = Companion.computeRankScore(
        semanticSimilarity = semanticSimilarity,
        keywordBm25Normalized = keywordBm25Normalized,
        mutualRecencyWeight = mutualRecencyWeight,
        followerCount = followerCount,
        mutualRepliesCount = mutualRepliesCount
    )

    fun computeTopicKey(draftText: String): String = Companion.computeTopicKey(draftText)

    fun muteHandleForDraft(
        handle: String,
        draftText: String,
        mutedAtMs: Long = System.currentTimeMillis()
    ): String {
        val topicKey = computeTopicKey(draftText)
        database.muteContactForTopic(handle = handle, topicKey = topicKey, mutedAtMs = mutedAtMs)
        return topicKey
    }

    fun weaveHandleIntoDraft(draftText: String, handle: String): String =
        Companion.weaveHandleIntoDraft(draftText, handle)

    fun weaveHandleIntoDraft(draftText: String, handle: String, matchContext: String?): String =
        Companion.weaveHandleIntoDraft(draftText, handle, matchContext)

    fun findMatches(
        draftText: String,
        limit: Int = 8,
        nowMs: Long = System.currentTimeMillis()
    ): List<GraphMatch> {
        if (draftText.isBlank() || limit <= 0) return emptyList()
        if (database.contactCount() == 0) return emptyList()

        val topicKey = computeTopicKey(draftText)
        val mutedHandles = database.getMutedHandlesForTopic(topicKey)

        val draftVector = LocalVectorEmbedder.embed(draftText)
        val queryTerms = LocalVectorEmbedder.tokenizeContentWords(draftText)
        val queryStems = queryTerms.map { LocalVectorEmbedder.lightStem(it) }.toSet()

        val rawFtsScores = database.searchPostsFts(
            queryTerms = queryTerms,
            limit = maxOf(limit * 12, 64)
        )
        val normalizedBm25 = normalizeBm25Scores(rawFtsScores)

        val contactsByHandle = database.getAllContacts().associateBy { it.record.handle }
        if (contactsByHandle.isEmpty()) return emptyList()

        val postsByHandle = database.getAllPosts().groupBy { it.record.handle }

        val matches = ArrayList<GraphMatch>()
        for ((handle, storedContact) in contactsByHandle) {
            if (handle in mutedHandles) continue
            val contact = storedContact.record

            val bioSim = LocalVectorEmbedder.cosineSimilarity(draftVector, storedContact.bioVector)
                .coerceAtLeast(0.0)
            val mutualRecencyWeight = computeMutualRecencyWeight(
                mutualRepliesCount = contact.mutualRepliesCount,
                lastInteractionEpochMs = contact.lastInteractionEpochMs,
                nowMs = nowMs
            )

            val contactPosts = postsByHandle[handle].orEmpty()
            if (contactPosts.isNotEmpty()) {
                var bestPost: GraphPostRecord? = null
                var bestScore = -1.0

                for (storedPost in contactPosts) {
                    val post = storedPost.record
                    val postSim = LocalVectorEmbedder.cosineSimilarity(draftVector, storedPost.postVector)
                        .coerceAtLeast(0.0)
                    val semanticSim = (0.80 * postSim + 0.20 * bioSim).coerceIn(0.0, 1.0)
                    val kwNorm = normalizedBm25[post.tweetId] ?: 0.0

                    if (semanticSim < MIN_SEMANTIC_THRESHOLD && kwNorm <= 0.0) continue

                    val score = computeRankScore(
                        semanticSimilarity = semanticSim,
                        keywordBm25Normalized = kwNorm,
                        mutualRecencyWeight = mutualRecencyWeight,
                        followerCount = contact.followerCount,
                        mutualRepliesCount = contact.mutualRepliesCount
                    )
                    if (score > bestScore) {
                        bestScore = score
                        bestPost = post
                    }
                }

                if (bestPost != null && bestScore > 0.0) {
                    val reason = classifyMatchReason(contact, bestPost, nowMs)
                    val lastReplyAge = if (contact.mutualRepliesCount > 0 && contact.lastInteractionEpochMs > 0L) {
                        formatAgeLabel(contact.lastInteractionEpochMs, nowMs)
                    } else {
                        ""
                    }
                    matches.add(
                        GraphMatch(
                            handle = contact.handle,
                            tweetId = bestPost.tweetId,
                            postUrl = "https://x.com/${contact.handle}/status/${bestPost.tweetId}",
                            snippet = formatSnippet(bestPost.text),
                            postAgeLabel = formatAgeLabel(bestPost.createdAtEpochMs, nowMs),
                            lastReplyAgeLabel = lastReplyAge,
                            reason = reason,
                            score = bestScore
                        )
                    )
                }
            } else if (contact.bio.isNotBlank()) {
                val bioKeywordOverlap = computeTokenOverlapRatio(queryStems, contact.bio)
                if (bioSim >= MIN_SEMANTIC_THRESHOLD || bioKeywordOverlap > 0.0) {
                    val score = computeRankScore(
                        semanticSimilarity = bioSim,
                        keywordBm25Normalized = bioKeywordOverlap,
                        mutualRecencyWeight = mutualRecencyWeight,
                        followerCount = contact.followerCount,
                        mutualRepliesCount = contact.mutualRepliesCount
                    )
                    if (score > 0.0) {
                        val reason = classifyMatchReason(contact, null, nowMs)
                        val lastReplyAge = if (contact.mutualRepliesCount > 0 && contact.lastInteractionEpochMs > 0L) {
                            formatAgeLabel(contact.lastInteractionEpochMs, nowMs)
                        } else {
                            ""
                        }
                        matches.add(
                            GraphMatch(
                                handle = contact.handle,
                                tweetId = "",
                                postUrl = "https://x.com/${contact.handle}",
                                snippet = formatSnippet(contact.bio),
                                postAgeLabel = formatAgeLabel(contact.lastInteractionEpochMs, nowMs),
                                lastReplyAgeLabel = lastReplyAge,
                                reason = reason,
                                score = score
                            )
                        )
                    }
                }
            }
        }

        return matches
            .sortedWith(compareByDescending<GraphMatch> { it.score }.thenBy { it.handle })
            .take(limit)
    }

    companion object {
        private const val MIN_SEMANTIC_THRESHOLD = 0.05
        private const val DAY_MS = 86_400_000L
        private const val ACTIVE_THREAD_WINDOW_MS = 14L * DAY_MS

        private val COUNTER_ARGUMENT_PATTERNS: List<Regex> = listOf(
            Regex("\\b(disagree|pushback|push\\s+back|counterpoint|counter[-\\s]?argument)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(actually|however|contrary|myth|overrated|skeptical|flawed|misleading)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(not\\s+true|wrong\\s+about|problem\\s+with|doesn'?t\\s+scale|fails\\s+when|breaks\\s+down)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(trade[-\\s]?off|caveat|unpopular\\s+opinion|hot\\s+take|beg\\s+to\\s+differ|the\\s+opposite)\\b", RegexOption.IGNORE_CASE)
        )

        private val MAINTAINER_PATTERNS: List<Regex> = listOf(
            Regex("\\b(maintainer|maintaining|core\\s+team|committer|lead\\s+dev|co-?creator)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(creator\\s+of|author\\s+of|founder\\s+of|shipped|open[-\\s]?source|oss|repo|repository)\\b", RegexOption.IGNORE_CASE),
            Regex("github\\.com/", RegexOption.IGNORE_CASE)
        )

        private val RESPONSE_OPENER_REGEX = Regex(
            pattern = "^(agree|disagree|good\\s+point|great\\s+point|fair\\s+point|counterpoint|building\\s+on|adding\\s+to|exactly|spot\\s+on|totally|seconding)\\b",
            option = RegexOption.IGNORE_CASE
        )

        private val LEADING_MENTIONS_REGEX = Regex("^((?:@[A-Za-z0-9_]{1,15}\\s+)+)")
        private val SENTENCE_CLAUSE_REGEX = Regex("[^.!?]+(?:[.!?]+|$)")
        private val TRAILING_PUNCT_REGEX = Regex("([.!?;:]+)$")
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Pure scoring function combining semantic similarity (55%), normalized keyword BM25 (25%),
         * and mutual reply/recency weight (20%), with an explicit broadcast penalty for high-follower
         * accounts with zero mutual interaction.
         */
        fun computeRankScore(
            semanticSimilarity: Double,
            keywordBm25Normalized: Double,
            mutualRecencyWeight: Double,
            followerCount: Int,
            mutualRepliesCount: Int
        ): Double {
            val sem = semanticSimilarity.coerceIn(0.0, 1.0)
            val kw = keywordBm25Normalized.coerceIn(0.0, 1.0)
            val rec = mutualRecencyWeight.coerceIn(0.0, 1.0)

            val baseScore = (sem * 0.55) + (kw * 0.25) + (rec * 0.20)
            val broadcastMultiplier = when {
                mutualRepliesCount <= 0 && followerCount >= 100_000 -> 0.35
                mutualRepliesCount <= 0 && followerCount >= 25_000 -> 0.55
                else -> 1.0
            }
            return baseScore * broadcastMultiplier
        }

        /**
         * Computes a stable topic-cluster key from the draft's top content tokens so
         * muting a contact suppresses them for that topic cluster without muting them globally.
         */
        fun computeTopicKey(draftText: String): String {
            val contentWords = LocalVectorEmbedder.tokenizeContentWords(draftText)
            if (contentWords.isEmpty()) return "general"

            val counts = HashMap<String, Int>()
            for (word in contentWords) {
                val stem = LocalVectorEmbedder.lightStem(word)
                counts[stem] = (counts[stem] ?: 0) + 1
            }

            val topTokens = counts.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenByDescending { it.key.length }
                        .thenBy { it.key }
                )
                .take(3)
                .map { it.key }
                .sorted()

            return if (topTokens.isEmpty()) "general" else topTokens.joinToString(separator = ":")
        }

        fun classifyMatchReason(
            contact: GraphContactRecord,
            post: GraphPostRecord?,
            nowMs: Long
        ): MatchReason {
            val postText = post?.text.orEmpty()
            if (postText.isNotBlank() && COUNTER_ARGUMENT_PATTERNS.any { it.containsMatchIn(postText) }) {
                return MatchReason.COUNTER_ARGUMENT
            }

            val maintainerText = "${contact.bio} $postText"
            if (maintainerText.isNotBlank() && MAINTAINER_PATTERNS.any { it.containsMatchIn(maintainerText) }) {
                return MatchReason.MAINTAINER
            }

            val recentReplyPost = post != null &&
                post.isReplyToUser &&
                post.createdAtEpochMs > 0L &&
                (nowMs - post.createdAtEpochMs) in 0L..ACTIVE_THREAD_WINDOW_MS
            val recentMutualInteraction = contact.mutualRepliesCount > 0 &&
                contact.lastInteractionEpochMs > 0L &&
                (nowMs - contact.lastInteractionEpochMs) in 0L..ACTIVE_THREAD_WINDOW_MS

            if (recentReplyPost || recentMutualInteraction) {
                return MatchReason.ACTIVE_THREAD
            }

            return MatchReason.PEER
        }

        fun formatAgeLabel(epochMs: Long, nowMs: Long): String {
            if (epochMs <= 0L) return ""
            val diffMs = max(0L, nowMs - epochMs)
            val minuteMs = 60_000L
            val hourMs = 3_600_000L
            return when {
                diffMs < minuteMs -> "<1m ago"
                diffMs < hourMs -> "${diffMs / minuteMs}m ago"
                diffMs < DAY_MS -> "${diffMs / hourMs}h ago"
                diffMs < 30L * DAY_MS -> "${diffMs / DAY_MS}d ago"
                diffMs < 365L * DAY_MS -> "${(diffMs / (30L * DAY_MS)).coerceAtLeast(1L)}mo ago"
                else -> "${(diffMs / (365L * DAY_MS)).coerceAtLeast(1L)}y ago"
            }
        }

        /**
         * Inserts `@handle` naturally into the most relevant clause of [draftText]
         * (or as a leading `.@handle` attribution for response openers) without breaking
         * terminal punctuation or duplicating an existing mention.
         */
        fun weaveHandleIntoDraft(
            draftText: String,
            handle: String,
            matchContext: String? = null
        ): String {
            val cleanHandle = normalizeGraphHandle(handle)
            if (cleanHandle.isBlank()) return draftText

            val mention = "@$cleanHandle"
            val trimmed = draftText.trim()
            if (trimmed.isEmpty()) return ".$mention "

            val alreadyMentioned = Regex(
                "(^|[^A-Za-z0-9_])@${Regex.escape(cleanHandle)}(?![A-Za-z0-9_])",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(trimmed)
            if (alreadyMentioned) return draftText

            // If draft starts with existing @mentions (reply chain), append cleanly after them.
            val leadingMentions = LEADING_MENTIONS_REGEX.find(trimmed)
            if (leadingMentions != null) {
                val prefix = leadingMentions.value.trimEnd()
                val rest = trimmed.substring(leadingMentions.value.length).trimStart()
                return "$prefix $mention $rest".trimEnd()
            }

            // If draft opens with a direct response / agreement cue, use classic X `.@handle` lead attribution.
            if (RESPONSE_OPENER_REGEX.containsMatchIn(trimmed)) {
                return ".$mention $trimmed"
            }

            // Split into sentence clauses preserving exact terminal punctuation.
            val clauses = SENTENCE_CLAUSE_REGEX.findAll(trimmed)
                .map { it.value.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()

            if (clauses.isEmpty()) {
                return ".$mention $trimmed"
            }

            val targetIndex = selectBestClauseIndex(clauses, matchContext)
            val targetClause = clauses[targetIndex]
            val punctMatch = TRAILING_PUNCT_REGEX.find(targetClause)
            val punctuation = punctMatch?.value.orEmpty()
            val body = if (punctuation.isNotEmpty()) {
                targetClause.removeSuffix(punctuation).trimEnd()
            } else {
                targetClause.trimEnd()
            }

            val tagPhrase = if (punctuation.contains('?')) {
                " (cc $mention)"
            } else {
                " (via $mention)"
            }
            clauses[targetIndex] = "$body$tagPhrase$punctuation"
            return clauses.joinToString(separator = " ")
        }

        private fun selectBestClauseIndex(clauses: List<String>, matchContext: String?): Int {
            if (clauses.size == 1) return 0
            if (!matchContext.isNullOrBlank()) {
                val contextVec = LocalVectorEmbedder.embed(matchContext)
                val contextStems = LocalVectorEmbedder.tokenizeContentWords(matchContext)
                    .map { LocalVectorEmbedder.lightStem(it) }
                    .toSet()

                var bestIdx = clauses.lastIndex
                var bestScore = -1.0
                for (i in clauses.indices) {
                    val sim = LocalVectorEmbedder.cosineSimilarity(
                        LocalVectorEmbedder.embed(clauses[i]),
                        contextVec
                    ).coerceAtLeast(0.0)
                    val overlap = computeTokenOverlapRatio(contextStems, clauses[i])
                    val combined = 0.65 * sim + 0.35 * overlap
                    if (combined > bestScore) {
                        bestScore = combined
                        bestIdx = i
                    }
                }
                return bestIdx
            }

            // Without explicit matchContext, prefer the concluding clause (strongest attribution anchor).
            return clauses.lastIndex
        }

        private fun normalizeBm25Scores(rawScores: Map<String, Double>): Map<String, Double> {
            if (rawScores.isEmpty()) return emptyMap()
            val maxScore = rawScores.values.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0
            val out = HashMap<String, Double>(rawScores.size)
            for ((tweetId, raw) in rawScores) {
                val positive = raw.coerceAtLeast(0.0)
                val relative = (positive / maxScore).coerceIn(0.0, 1.0)
                val saturated = (positive / (positive + 1.5)).coerceIn(0.0, 1.0)
                out[tweetId] = (0.55 * relative + 0.45 * saturated).coerceIn(0.0, 1.0)
            }
            return out
        }

        private fun computeMutualRecencyWeight(
            mutualRepliesCount: Int,
            lastInteractionEpochMs: Long,
            nowMs: Long
        ): Double {
            val recency = if (lastInteractionEpochMs > 0L) {
                val ageDays = max(0.0, (nowMs - lastInteractionEpochMs).toDouble() / DAY_MS.toDouble())
                exp(-ageDays / 30.0).coerceIn(0.0, 1.0)
            } else {
                0.0
            }

            if (mutualRepliesCount <= 0) {
                return (0.20 * recency).coerceIn(0.0, 0.20)
            }

            val mutualStrength = (ln(1.0 + mutualRepliesCount.toDouble()) / ln(1.0 + 10.0))
                .coerceIn(0.0, 1.0)
            return (0.60 * mutualStrength + 0.40 * recency).coerceIn(0.0, 1.0)
        }

        private fun computeTokenOverlapRatio(queryStems: Set<String>, text: String): Double {
            if (queryStems.isEmpty() || text.isBlank()) return 0.0
            val docStems = LocalVectorEmbedder.tokenizeContentWords(text)
                .map { LocalVectorEmbedder.lightStem(it) }
                .toSet()
            if (docStems.isEmpty()) return 0.0
            val hits = queryStems.count { it in docStems }
            return (hits.toDouble() / queryStems.size.toDouble()).coerceIn(0.0, 1.0)
        }

        private fun formatSnippet(rawText: String, maxChars: Int = 160): String {
            val normalized = WHITESPACE_REGEX.replace(rawText.trim(), " ")
            if (normalized.length <= maxChars) return normalized
            val cutoff = normalized.lastIndexOf(' ', startIndex = maxChars - 3)
            val sliceEnd = if (cutoff >= maxChars / 2) cutoff else maxChars - 3
            return normalized.substring(0, sliceEnd).trimEnd() + "..."
        }
    }
}
