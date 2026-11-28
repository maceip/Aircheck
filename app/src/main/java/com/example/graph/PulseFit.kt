package com.example.graph

import kotlin.math.roundToInt

/** Fit of an idea to the creator's areas, measured on the phone with [LocalVectorEmbedder]. */
data class PulseFit(val score: Int, val bestArea: String)

/** Where an idea sits among the creator's saved ideas, ranked by fit only. */
data class PulseRank(val position: Int, val of: Int)

object PulseFitScorer {
    /**
     * 0–100 word-overlap similarity against the best-matching area, or null when there are no
     * areas or no text (shown as unknown, never as 0).
     */
    fun fit(idea: String, areas: List<String>): PulseFit? {
        val cleanAreas = areas.map { it.trim() }.filter { it.isNotEmpty() }
        if (idea.isBlank() || cleanAreas.isEmpty()) return null
        val ideaVec = LocalVectorEmbedder.embed(idea)
        // No usable words (only a link, emoji, or a script the embedder doesn't read): unknown, not 0.
        if (ideaVec.none { it != 0f }) return null
        var best: String? = null
        var bestSim = Double.NEGATIVE_INFINITY
        for (area in cleanAreas) {
            val areaVec = LocalVectorEmbedder.embed(area)
            if (areaVec.none { it != 0f }) continue
            val sim = LocalVectorEmbedder.cosineSimilarity(ideaVec, areaVec)
            if (sim > bestSim) {
                bestSim = sim
                best = area
            }
        }
        val area = best ?: return null
        return PulseFit((bestSim.coerceIn(0.0, 1.0) * 100).roundToInt(), area)
    }

    /**
     * Rank among [otherIdeas] (excluding exact duplicates of [idea]) that can be measured; null when
     * no other idea has a known fit or there are no areas. Unknown fits are left out, never ranked as 0.
     */
    fun rank(idea: String, otherIdeas: List<String>, areas: List<String>): PulseRank? {
        val mine = fit(idea, areas) ?: return null
        val others = otherIdeas.map { it.trim() }.filter { it.isNotEmpty() && it != idea.trim() }.distinct()
            .mapNotNull { fit(it, areas)?.score }
        if (others.isEmpty()) return null
        return PulseRank(position = others.count { it > mine.score } + 1, of = others.size + 1)
    }

    /**
     * Adds a Bluesky mention, keeping its dots ("@alice.bsky.social"); the X weaver would strip them.
     * Appended at the end, and left alone when the draft already mentions that handle.
     */
    fun weaveBskyHandle(draft: String, handle: String): String {
        val clean = handle.trim().removePrefix("@")
        if (clean.isBlank()) return draft
        val already = Regex("(?<![\\w.@])@" + Regex.escape(clean) + "(?![\\w.-])", RegexOption.IGNORE_CASE)
        if (already.containsMatchIn(draft)) return draft
        val trimmed = draft.trimEnd()
        return if (trimmed.isEmpty()) "@$clean " else "$trimmed @$clean"
    }

    /** Heat ratio as a signed percent change ("+40%"), or null when unmeasured. */
    fun heatLabel(ratio: Double?): String? {
        ratio ?: return null
        val pct = ((ratio - 1.0) * 100).roundToInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    /** Replaces whole-word [from] with [to] in [text], ignoring case, for the "did you mean" fix. */
    fun applyCorrection(text: String, from: String, to: String): String {
        if (from.isBlank() || to.isBlank()) return text
        // Whole words only, so fixing "Ann" never rewrites "announcement".
        val pattern = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(from) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        return pattern.replace(text) { to }
    }
}

object NameDropArchiveMatcher {
    private fun norm(s: String) = s.lowercase().filter { it.isLetter() }

    /**
     * Fills an unknown X handle from the creator's own imported X archive when exactly one contact
     * there has the same display name. Marked "in_archive" (never "verified") so the UI says why.
     */
    fun merge(result: NameDropResult, contacts: List<GraphContactRecord>): NameDropResult {
        if (contacts.isEmpty()) return result
        val byName = contacts.filter { it.displayName.isNotBlank() }.groupBy { norm(it.displayName) }
        val people = result.people.map { person ->
            val x = person.handles["x"]
            if (person.name.isBlank() || (x != null && x.status != "unknown")) return@map person
            val match = byName[norm(person.name)]?.singleOrNull() ?: return@map person
            person.copy(
                handles = person.handles + ("x" to NameDropHandle(
                    handle = "@" + match.handle,
                    status = "in_archive",
                    evidence = "same name in your X archive (${match.mutualRepliesCount} replies between you)"
                ))
            )
        }
        return result.copy(people = people)
    }
}
