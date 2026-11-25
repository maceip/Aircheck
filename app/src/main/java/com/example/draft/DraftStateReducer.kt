package com.example.draft

import kotlin.math.abs

/**
 * Pure, deterministic piece-table drafting engine, lock-parity guard, diff-dimmer,
 * and dual-axis (per-clause ring + global snapshot) state reducer.
 *
 * Constitutional guarantee: zero fabricated text, zero silent overwrites of
 * `verbatimLocked` user words.
 */
object DraftStateReducer {

    private const val MAX_TWEET_CHARS = 280
    private const val MAX_GLOBAL_SNAPSHOTS = 64

    private val WORD_REGEX = Regex("[A-Za-z0-9]+(?:['-][A-Za-z0-9]+)*")
    private val TAG_REGEX = Regex("</?(?:lock|replace)>", RegexOption.IGNORE_CASE)

    private val ABBREVIATIONS = setOf(
        "e.g.", "i.e.", "etc.", "vs.", "mr.", "mrs.", "ms.", "dr.", "prof.",
        "sr.", "jr.", "st.", "inc.", "ltd.", "corp.", "u.s.", "u.k.", "no.", "fig."
    )

    private val CLAUSE_CONJUNCTIONS = setOf(
        "and", "but", "so", "yet", "or", "while", "because", "although"
    )

    private val ORPHAN_LEADING_CONNECTORS = setOf(
        "and", "but", "so", "or", "yet"
    )

    private val ORPHAN_TRAILING_CONNECTORS = setOf(
        "and", "but", "so", "or", "yet"
    )

    private val DANGLING_OPENERS = setOf(
        "consequently",
        "however",
        "furthermore",
        "additionally",
        "therefore",
        "moreover",
        "nevertheless"
    )

    data class FlattenedToken(
        val clauseIndex: Int,
        val clauseId: String,
        val tokenIndex: Int,
        val token: WordToken,
        val textStart: Int,
        val textEndExclusive: Int,
        val fullEndExclusive: Int
    )

    data class LockParityResult(
        val preserved: Boolean,
        val missingPhrases: List<String>,
        val clauses: List<ClauseNode>
    )

    /**
     * Convenience factory initializing a [CandidateDraft] with its initial global snapshot.
     */
    fun createCandidateDraft(
        postureLabel: String,
        text: String,
        source: EditSource = EditSource.MODEL_INIT,
        lockedSubstrings: List<String> = emptyList()
    ): CandidateDraft {
        val clauses = tokenizeIntoClauses(
            text = sanitizeAscii(text),
            source = source,
            lockedSubstrings = lockedSubstrings
        )
        return CandidateDraft(
            postureLabel = postureLabel,
            clauses = clauses,
            globalSnapshots = listOf(clauses),
            snapshotCursor = 0,
            linkedSplitCards = emptyList()
        )
    }

    /**
     * 1. Splits text into syntactic clauses (sentence & clause boundaries preserving punctuation
     * and trailing spacing) and word tokens, tagging any substring matching [lockedSubstrings]
     * with `verbatimLocked = true`.
     */
    fun tokenizeIntoClauses(
        text: String,
        source: EditSource = EditSource.MODEL_INIT,
        lockedSubstrings: List<String> = emptyList()
    ): List<ClauseNode> {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        if (normalized.isBlank()) return emptyList()

        val lockedCharRanges = findLockedCharRanges(normalized, lockedSubstrings)
        val clauseBounds = splitIntoClauseBounds(normalized)
        if (clauseBounds.isEmpty()) return emptyList()

        val tokenRegex = Regex("\\S+")
        val result = mutableListOf<ClauseNode>()

        clauseBounds.forEachIndexed { _, bound ->
            val slice = normalized.substring(bound.start, bound.end)
            val matches = tokenRegex.findAll(slice).toList()
            if (matches.isEmpty()) return@forEachIndexed

            val tokens = mutableListOf<WordToken>()
            matches.forEachIndexed { idx, match ->
                val absStart = bound.start + match.range.first
                val absEndExclusive = bound.start + match.range.last + 1
                val trailingSpace = if (idx < matches.lastIndex) {
                    val gap = slice.substring(match.range.last + 1, matches[idx + 1].range.first)
                    if (gap.contains('\n')) gap else " "
                } else {
                    bound.trailingSeparatorAfterClause
                }

                // Determine exact character-level lock mask inside this token so a lock boundary
                // before trailing punctuation (e.g. "budget" inside "budget.") keeps the period
                // cleanly outside the locked span.
                val wordLen = match.value.length
                val charLocked = BooleanArray(wordLen) { charIdx ->
                    val absPos = absStart + charIdx
                    lockedCharRanges.any { absPos in it }
                }
                val anyLocked = charLocked.any { it }
                val allLocked = charLocked.all { it }

                if (!anyLocked || allLocked) {
                    tokens += WordToken(
                        text = match.value,
                        trailingSpace = trailingSpace,
                        verbatimLocked = allLocked
                    )
                } else {
                    // Split into contiguous locked / unlocked runs (typically leading/trailing punct).
                    var segStart = 0
                    while (segStart < wordLen) {
                        val state = charLocked[segStart]
                        var segEnd = segStart + 1
                        while (segEnd < wordLen && charLocked[segEnd] == state) segEnd++
                        val isLastSeg = segEnd == wordLen
                        tokens += WordToken(
                            text = match.value.substring(segStart, segEnd),
                            trailingSpace = if (isLastSeg) trailingSpace else "",
                            verbatimLocked = state
                        )
                        segStart = segEnd
                    }
                }
            }

            result += ClauseNode(
                id = "c_${result.size}",
                versions = listOf(ClauseVersion(tokens = tokens, source = source)),
                activeVersionIndex = 0
            )
        }

        return result
    }

    /**
     * Canonical flattening of clauses into plain text and per-token character offsets.
     */
    fun flattenClauses(clauses: List<ClauseNode>): Pair<String, List<FlattenedToken>> {
        val nonEmptyIndices = clauses.indices.filter { clauses[it].activeVersion.tokens.isNotEmpty() }
        if (nonEmptyIndices.isEmpty()) return "" to emptyList()

        val sb = StringBuilder()
        val flatTokens = mutableListOf<FlattenedToken>()
        val lastNonEmptyClauseIndex = nonEmptyIndices.last()

        for (clauseIndex in nonEmptyIndices) {
            val clause = clauses[clauseIndex]
            val tokens = clause.activeVersion.tokens
            val hasNextClause = clauseIndex < lastNonEmptyClauseIndex

            tokens.forEachIndexed { tokenIndex, token ->
                val textStart = sb.length
                sb.append(token.text)
                val textEndExclusive = sb.length

                val effectiveSeparator = when {
                    tokenIndex < tokens.lastIndex -> token.trailingSpace
                    hasNextClause -> {
                        when {
                            token.trailingSpace.contains('\n') -> token.trailingSpace
                            token.trailingSpace.isNotEmpty() -> token.trailingSpace
                            else -> " "
                        }
                    }
                    else -> ""
                }
                sb.append(effectiveSeparator)
                val fullEndExclusive = sb.length

                flatTokens += FlattenedToken(
                    clauseIndex = clauseIndex,
                    clauseId = clause.id,
                    tokenIndex = tokenIndex,
                    token = token,
                    textStart = textStart,
                    textEndExclusive = textEndExclusive,
                    fullEndExclusive = fullEndExclusive
                )
            }
        }

        return sb.toString() to flatTokens
    }

    /**
     * 2a. Renders the plain text of [clauses].
     */
    fun renderPlainText(clauses: List<ClauseNode>): String = flattenClauses(clauses).first

    /**
     * Extracts every contiguous locked run in document order (the exact substrings wrapped by
     * `<lock>...</lock>`).
     */
    fun extractLockedPhrases(clauses: List<ClauseNode>): List<String> {
        val (fullText, flatTokens) = flattenClauses(clauses)
        if (flatTokens.isEmpty()) return emptyList()

        val phrases = mutableListOf<String>()
        var runStart: Int? = null
        var runEnd = 0

        flatTokens.forEachIndexed { idx, ft ->
            if (ft.token.verbatimLocked) {
                if (runStart == null) {
                    runStart = ft.textStart
                }
                runEnd = ft.textEndExclusive
                val separator = fullText.substring(ft.textEndExclusive, ft.fullEndExclusive)
                val breaksAcrossParagraph = separator.contains("\n\n")
                val nextLocked = idx < flatTokens.lastIndex && flatTokens[idx + 1].token.verbatimLocked
                if (!nextLocked || breaksAcrossParagraph) {
                    val phrase = fullText.substring(runStart, runEnd).trim()
                    if (phrase.isNotEmpty()) phrases += phrase
                    runStart = null
                }
            } else {
                val startIdx = runStart
                if (startIdx != null) {
                    val phrase = fullText.substring(startIdx, runEnd).trim()
                    if (phrase.isNotEmpty()) phrases += phrase
                    runStart = null
                }
            }
        }
        return phrases
    }

    /**
     * 2b. Wraps contiguous locked runs in `<lock>...</lock>` and optional brushed character range
     * in `<replace>...</replace>` for Mac `:8443` LLM micro-passes.
     */
    fun renderMarkedTextWithLocks(
        clauses: List<ClauseNode>,
        replaceRange: IntRange? = null
    ): String {
        val (fullText, flatTokens) = flattenClauses(clauses)
        if (flatTokens.isEmpty()) return ""

        val effectiveReplace = replaceRange?.takeIf { !it.isEmpty() }
        val inReplace = BooleanArray(flatTokens.size) { i ->
            if (effectiveReplace == null) false
            else {
                val ft = flatTokens[i]
                ft.textStart <= effectiveReplace.last && (ft.textEndExclusive - 1) >= effectiveReplace.first
            }
        }
        val inLock = BooleanArray(flatTokens.size) { i ->
            flatTokens[i].token.verbatimLocked && !inReplace[i]
        }

        val sb = StringBuilder(fullText.length + 32)
        for (i in flatTokens.indices) {
            val ft = flatTokens[i]
            val prevSeparator = if (i > 0) {
                fullText.substring(flatTokens[i - 1].textEndExclusive, flatTokens[i - 1].fullEndExclusive)
            } else ""
            val currSeparator = fullText.substring(ft.textEndExclusive, ft.fullEndExclusive)
            val prevParagraphBreak = prevSeparator.contains("\n\n")
            val currParagraphBreak = currSeparator.contains("\n\n")

            val openReplace = inReplace[i] && (i == 0 || !inReplace[i - 1] || prevParagraphBreak)
            val openLock = inLock[i] && (i == 0 || !inLock[i - 1] || prevParagraphBreak)
            val closeReplace = inReplace[i] && (i == flatTokens.lastIndex || !inReplace[i + 1] || currParagraphBreak)
            val closeLock = inLock[i] && (i == flatTokens.lastIndex || !inLock[i + 1] || currParagraphBreak)

            if (openReplace) sb.append("<replace>")
            if (openLock) sb.append("<lock>")
            sb.append(ft.token.text)
            if (closeLock) sb.append("</lock>")
            if (closeReplace) sb.append("</replace>")
            sb.append(currSeparator)
        }
        return sb.toString()
    }

    /**
     * 3. Finds n-grams (bigrams/trigrams & shared words >= 4 chars) shared across 2+ candidates
     * so Stage 2 PICK renders shared spans at 60% opacity and unique spans at 100% opacity.
     */
    fun computeDiffDimMask(candidates: List<CandidateDraft>): List<Set<IntRange>> {
        if (candidates.size < 2) {
            return candidates.map { emptySet() }
        }

        data class IndexedWord(val normalized: String, val range: IntRange)

        val candidateTexts = candidates.map { renderPlainText(it.clauses) }
        val candidateWords = candidateTexts.map { text ->
            WORD_REGEX.findAll(text).map { match ->
                IndexedWord(match.value.lowercase(), match.range)
            }.toList()
        }

        val trigramDocFreq = HashMap<String, MutableSet<Int>>()
        val bigramDocFreq = HashMap<String, MutableSet<Int>>()
        val wordDocFreq = HashMap<String, MutableSet<Int>>()

        candidateWords.forEachIndexed { docIdx, words ->
            for (i in words.indices) {
                val w = words[i].normalized
                if (w.length >= 4) {
                    wordDocFreq.getOrPut(w) { HashSet() }.add(docIdx)
                }
                if (i + 1 < words.size) {
                    val bi = "$w ${words[i + 1].normalized}"
                    bigramDocFreq.getOrPut(bi) { HashSet() }.add(docIdx)
                }
                if (i + 2 < words.size) {
                    val tri = "$w ${words[i + 1].normalized} ${words[i + 2].normalized}"
                    trigramDocFreq.getOrPut(tri) { HashSet() }.add(docIdx)
                }
            }
        }

        return candidateWords.mapIndexed { docIdx, words ->
            val text = candidateTexts[docIdx]
            val rawRanges = mutableListOf<IntRange>()

            for (i in words.indices) {
                if (i + 2 < words.size) {
                    val tri = "${words[i].normalized} ${words[i + 1].normalized} ${words[i + 2].normalized}"
                    if ((trigramDocFreq[tri]?.size ?: 0) >= 2) {
                        rawRanges += words[i].range.first..words[i + 2].range.last
                    }
                }
                if (i + 1 < words.size) {
                    val bi = "${words[i].normalized} ${words[i + 1].normalized}"
                    if ((bigramDocFreq[bi]?.size ?: 0) >= 2) {
                        rawRanges += words[i].range.first..words[i + 1].range.last
                    }
                }
                val w = words[i].normalized
                if (w.length >= 4 && (wordDocFreq[w]?.size ?: 0) >= 2) {
                    rawRanges += words[i].range
                }
            }

            mergeDimRanges(text, rawRanges)
        }
    }

    /**
     * 4. STRICT Lock-Parity Guard: extracts every `<lock>` contiguous phrase from
     * [originalClauses]; if ANY locked phrase is missing or altered in [mutatedText], REJECTS
     * the LLM output and falls back deterministically while preserving every locked span verbatim.
     */
    fun verifyAndApplyLockedMutation(
        originalClauses: List<ClauseNode>,
        mutatedText: String,
        source: EditSource
    ): List<ClauseNode> = verifyLockParity(originalClauses, mutatedText, source).clauses

    /**
     * Detailed Lock-Parity verification returning whether all locked spans were conserved verbatim.
     */
    fun verifyLockParity(
        originalClauses: List<ClauseNode>,
        mutatedText: String,
        source: EditSource
    ): LockParityResult {
        val originalLockedPhrases = extractLockedPhrases(originalClauses)
        val strippedTags = TAG_REGEX.replace(mutatedText, "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()

        if (strippedTags.isBlank()) {
            return if (originalClauses.isEmpty()) {
                LockParityResult(preserved = true, missingPhrases = emptyList(), clauses = emptyList())
            } else {
                LockParityResult(
                    preserved = false,
                    missingPhrases = originalLockedPhrases,
                    clauses = originalClauses
                )
            }
        }

        // Only sanitize ASCII if it does not alter any original verbatim-locked phrase.
        val asciiCandidate = sanitizeAscii(strippedTags)
        val candidateText = if (findMissingLockedPhrases(asciiCandidate, originalLockedPhrases).isEmpty()) {
            asciiCandidate
        } else {
            strippedTags
        }

        val missing = findMissingLockedPhrases(candidateText, originalLockedPhrases)
        if (missing.isNotEmpty()) {
            return LockParityResult(
                preserved = false,
                missingPhrases = missing,
                clauses = originalClauses
            )
        }

        val tokenized = tokenizeIntoClauses(
            text = candidateText,
            source = source,
            lockedSubstrings = originalLockedPhrases
        )

        // Post-tokenization fail-closed verification: every original locked phrase must remain
        // present and locked verbatim in the produced clauses.
        val resultingLocked = extractLockedPhrases(tokenized)
        if (!allLockedPhrasesCovered(originalLockedPhrases, resultingLocked)) {
            return LockParityResult(
                preserved = false,
                missingPhrases = originalLockedPhrases.filter { req ->
                    resultingLocked.none { it.contains(req) }
                },
                clauses = originalClauses
            )
        }

        val reconciled = reconcileClauseHistories(originalClauses, tokenized, source)
        return LockParityResult(
            preserved = true,
            missingPhrases = emptyList(),
            clauses = reconciled
        )
    }

    /**
     * Applies an LLM mutation to a [CandidateDraft] through the strict Lock-Parity Guard and
     * pushes a global snapshot if the draft changed.
     */
    fun applyVerifiedMutation(
        draft: CandidateDraft,
        mutatedText: String,
        source: EditSource
    ): CandidateDraft {
        val verified = verifyAndApplyLockedMutation(draft.clauses, mutatedText, source)
        if (verified == draft.clauses) return draft
        return pushGlobalSnapshot(draft, verified)
    }

    /**
     * 5. Updates affected [ClauseNode](s) covered by [charRange] with [replacementText],
     * pushes a new [ClauseVersion] into each modified clause's local version ring,
     * AND pushes a snapshot into `globalSnapshots`.
     */
    fun applyBrushSwap(
        draft: CandidateDraft,
        charRange: IntRange,
        replacementText: String,
        source: EditSource = EditSource.BRUSH_SWAP
    ): CandidateDraft {
        val (fullText, flatTokens) = flattenClauses(draft.clauses)
        if (fullText.isEmpty() || flatTokens.isEmpty() || charRange.isEmpty()) return draft

        val clampedStart = charRange.first.coerceIn(0, fullText.lastIndex)
        val clampedEnd = charRange.last.coerceIn(clampedStart, fullText.lastIndex)
        val effectiveRange = clampedStart..clampedEnd

        val affected = flatTokens.filter { ft ->
            ft.textStart <= effectiveRange.last && (ft.textEndExclusive - 1) >= effectiveRange.first
        }
        if (affected.isEmpty()) return draft

        val cleanReplacement = sanitizeAscii(replacementText).trim()
        val firstAffected = affected.first()
        val lastAffected = affected.last()

        val firstClauseIdx = firstAffected.clauseIndex
        val lastClauseIdx = lastAffected.clauseIndex

        // Determine if terminal punctuation on the last affected token should be preserved.
        val lastTokenText = lastAffected.token.text
        val lastTerminalPunct = extractTerminalPunctuation(lastTokenText)
        val shouldPreserveTerminalPunct = lastTerminalPunct.isNotEmpty() &&
            cleanReplacement.isNotEmpty() &&
            extractTerminalPunctuation(cleanReplacement).isEmpty() &&
            (effectiveRange.last < lastAffected.textEndExclusive - 1 || lastTerminalPunct.any { it in ".!?" })

        // Determine if sentence/clause-start capitalization should be preserved on replacement.
        val isAtSentenceStart = firstAffected.tokenIndex == 0 && (
            firstClauseIdx == 0 ||
                endsWithSentencePunctuation(draft.clauses[firstClauseIdx - 1].plainText())
            )
        val originalStartedUpper = firstAffected.token.text.firstOrNull()?.isUpperCase() == true
        val lockInserted = source == EditSource.VOICE_SPLICE ||
            source == EditSource.SALVAGE_SPLICE ||
            source == EditSource.MANUAL_RAW

        val replacementTokens = if (cleanReplacement.isBlank()) {
            emptyList()
        } else {
            val rawWords = cleanReplacement.split(Regex("\\s+")).filter { it.isNotEmpty() }
            rawWords.mapIndexed { idx, rawWord ->
                var w = rawWord
                if (idx == 0 && isAtSentenceStart && originalStartedUpper &&
                    !w.startsWith("@") && !w.startsWith("http") &&
                    w.firstOrNull()?.isLowerCase() == true
                ) {
                    w = w.replaceFirstChar { it.uppercaseChar() }
                }
                if (idx == rawWords.lastIndex && shouldPreserveTerminalPunct) {
                    w += lastTerminalPunct
                }
                WordToken(
                    text = w,
                    trailingSpace = if (idx < rawWords.lastIndex) " " else "",
                    verbatimLocked = lockInserted
                )
            }
        }

        val updatedClauses = mutableListOf<ClauseNode>()
        var cutRequiredCleanup = false

        draft.clauses.forEachIndexed { cIdx, clause ->
            val clauseAffected = affected.filter { it.clauseIndex == cIdx }
            if (clauseAffected.isEmpty()) {
                updatedClauses += clause
                return@forEachIndexed
            }

            val tokens = clause.activeVersion.tokens
            val startTokIdx = clauseAffected.first().tokenIndex
            val endTokIdx = clauseAffected.last().tokenIndex

            val prefix = tokens.subList(0, startTokIdx)
            val suffix = tokens.subList(endTokIdx + 1, tokens.size)

            val insertedForThisClause = if (cIdx == firstClauseIdx) replacementTokens else emptyList()
            val combined = buildList {
                addAll(prefix)
                addAll(insertedForThisClause)
                addAll(suffix)
            }

            if (combined.isEmpty()) {
                cutRequiredCleanup = true
            } else {
                val normalizedTokens = normalizeClauseTokens(
                    tokens = combined,
                    preserveTerminalFromDeleted = if (
                        cleanReplacement.isBlank() &&
                        cIdx == lastClauseIdx &&
                        suffix.isEmpty() &&
                        lastTerminalPunct.any { it in ".!?" }
                    ) lastTerminalPunct else "",
                    clauseTrailingNewline = tokens.lastOrNull()?.trailingSpace?.takeIf { it.contains('\n') } ?: ""
                )
                updatedClauses += appendClauseVersion(
                    clause = clause,
                    newTokens = normalizedTokens,
                    source = source
                )
            }
        }

        val finalClauses = if (cutRequiredCleanup && updatedClauses.isNotEmpty()) {
            applyLocalGlueCleanup(
                clauses = updatedClauses,
                cutIndex = firstClauseIdx.coerceAtMost(updatedClauses.size),
                removedEndedSentence = endsWithSentencePunctuation(lastTokenText),
                removedTerminalPunct = lastTerminalPunct.firstOrNull { it in ".!?" } ?: '.',
                source = source
            )
        } else {
            updatedClauses
        }

        return pushGlobalSnapshot(draft, finalClauses)
    }

    /**
     * 6. Removes the clause with [clauseId] and runs deterministic local glue cleanup
     * (fix capitalization, strip orphaned `", and"`, `", but"`, `", so"`, trim double spaces)
     * strictly outside locked spans, pushing a global snapshot.
     */
    fun strikeCutClause(draft: CandidateDraft, clauseId: String): CandidateDraft {
        val removedIdx = draft.clauses.indexOfFirst { it.id == clauseId }
        if (removedIdx < 0) return draft

        val removedClause = draft.clauses[removedIdx]
        val removedText = removedClause.plainText().trimEnd()
        val removedEndedSentence = endsWithSentencePunctuation(removedText)
        val removedTerminalPunct = removedText.lastOrNull { it in ".!?" } ?: '.'

        val remaining = draft.clauses.filterIndexed { idx, _ -> idx != removedIdx }
        if (remaining.isEmpty()) {
            return pushGlobalSnapshot(draft, emptyList())
        }

        val cleaned = applyLocalGlueCleanup(
            clauses = remaining,
            cutIndex = removedIdx,
            removedEndedSentence = removedEndedSentence,
            removedTerminalPunct = removedTerminalPunct,
            source = EditSource.STRIKE_CUT
        )
        return pushGlobalSnapshot(draft, cleaned)
    }

    /**
     * 7. Moves a [ClauseNode] from [fromIndex] to [toIndex], strips dangling openers
     * (`Consequently,`, `However,`, `Furthermore,`, `Additionally,`, `Therefore,`) if moved
     * to index 0 (unless verbatim-locked), and pushes a global snapshot.
     */
    fun reorderClause(draft: CandidateDraft, fromIndex: Int, toIndex: Int): CandidateDraft {
        if (fromIndex !in draft.clauses.indices || toIndex !in draft.clauses.indices || fromIndex == toIndex) {
            return draft
        }

        val mutable = draft.clauses.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)

        // Check clause now at index 0 for dangling openers (strictly outside locked spans).
        if (mutable.isNotEmpty()) {
            val headClause = mutable[0]
            val headTokens = headClause.activeVersion.tokens
            if (headTokens.isNotEmpty()) {
                val first = headTokens[0]
                val bareFirst = first.text.trimEnd(',', ';', ':').lowercase()
                if (!first.verbatimLocked && bareFirst in DANGLING_OPENERS && headTokens.size > 1) {
                    val dropped = headTokens.drop(1).toMutableList()
                    val newFirst = dropped[0]
                    if (!newFirst.verbatimLocked &&
                        !newFirst.text.startsWith("@") &&
                        newFirst.text.firstOrNull()?.isLowerCase() == true
                    ) {
                        dropped[0] = newFirst.copy(
                            text = newFirst.text.replaceFirstChar { it.uppercaseChar() }
                        )
                    }
                    mutable[0] = appendClauseVersion(
                        clause = headClause,
                        newTokens = normalizeClauseTokens(dropped),
                        source = EditSource.REORDER
                    )
                } else if (!first.verbatimLocked &&
                    !first.text.startsWith("@") &&
                    first.text.firstOrNull()?.isLowerCase() == true
                ) {
                    val updated = headTokens.toMutableList()
                    updated[0] = first.copy(text = first.text.replaceFirstChar { it.uppercaseChar() })
                    mutable[0] = appendClauseVersion(
                        clause = headClause,
                        newTokens = normalizeClauseTokens(updated),
                        source = EditSource.REORDER
                    )
                }
            }

            // If the new last clause ends with an unlocked dangling comma/semicolon, promote to period.
            val lastIdx = mutable.lastIndex
            val tailClause = mutable[lastIdx]
            val tailTokens = tailClause.activeVersion.tokens
            if (tailTokens.isNotEmpty()) {
                val lastTok = tailTokens.last()
                val tailChar = lastTok.text.lastOrNull()
                if (!lastTok.verbatimLocked && tailChar != null && tailChar in charArrayOf(',', ';', ':')) {
                    val updatedTail = tailTokens.toMutableList()
                    updatedTail[updatedTail.lastIndex] = lastTok.copy(
                        text = lastTok.text.dropLast(1) + "."
                    )
                    mutable[lastIdx] = appendClauseVersion(
                        clause = tailClause,
                        newTokens = normalizeClauseTokens(updatedTail),
                        source = EditSource.REORDER
                    )
                }
            }
        }

        return pushGlobalSnapshot(draft, mutable.toList())
    }

    /**
     * 8. Strips light voice wrappers such as `"say X instead"`, `"replace it with X"`,
     * `"change to X"`, `"make it X"` -> `"X"`.
     */
    fun stripVoiceDirective(rawSpoken: String): String {
        var text = sanitizeAscii(rawSpoken).trim()
        if (text.isEmpty()) return ""

        text = stripOuterQuotes(text).trim()

        // Strip leading conversational fillers iteratively.
        val fillerRegex = Regex("^(?:(?:um|uh|well|like)[,.]?\\s+|please\\s+|(?:can|could)\\s+you\\s+)", RegexOption.IGNORE_CASE)
        while (true) {
            val next = fillerRegex.replaceFirst(text, "").trim()
            if (next == text) break
            text = next
        }

        data class WrapperPattern(val regex: Regex, val stripTrailingCommandDot: Boolean)

        val wrapperPatterns = listOf(
            WrapperPattern(Regex("^(?:just\\s+)?say\\s+(.+?)\\s+instead[.!?]*$", RegexOption.IGNORE_CASE), false),
            WrapperPattern(Regex("^(?:use|put|write|insert|add)\\s+(.+?)\\s+instead[.!?]*$", RegexOption.IGNORE_CASE), false),
            WrapperPattern(Regex("^instead\\s+(?:say|use|write)\\s+(.+?)$", RegexOption.IGNORE_CASE), true),
            WrapperPattern(Regex("^replace\\s+(?:(?:it|this|that|the\\s+word|the\\s+phrase|the\\s+clause)\\s+)?with\\s+(.+?)$", RegexOption.IGNORE_CASE), true),
            WrapperPattern(Regex("^swap\\s+(?:(?:it|this|that)\\s+)?(?:for|with|to)\\s+(.+?)$", RegexOption.IGNORE_CASE), true),
            WrapperPattern(Regex("^change\\s+(?:(?:it|this|that)\\s+)?to\\s+(.+?)$", RegexOption.IGNORE_CASE), true),
            WrapperPattern(Regex("^(?:make\\s+(?:it|this|that)|turn\\s+(?:it|this|that)\\s+into)\\s+(.+?)$", RegexOption.IGNORE_CASE), true),
            WrapperPattern(Regex("^(?:just\\s+)?say\\s+(.+?)$", RegexOption.IGNORE_CASE), true),
            WrapperPattern(Regex("^(.+?)\\s+instead[.!?]*$", RegexOption.IGNORE_CASE), false)
        )

        var stripTrailingDot = false
        for (pattern in wrapperPatterns) {
            val match = pattern.regex.matchEntire(text)
            if (match != null) {
                val captured = match.groupValues[1].trim()
                if (captured.isNotEmpty()) {
                    text = captured
                    stripTrailingDot = pattern.stripTrailingCommandDot
                    break
                }
            }
        }

        text = stripOuterQuotes(text).trim()
        if (stripTrailingDot && text.endsWith(".") && !text.endsWith("...")) {
            // Whisper appends a trailing period to voice commands like "replace it with cold caches."
            val withoutDot = text.dropLast(1).trimEnd()
            if (withoutDot.isNotEmpty()) {
                text = withoutDot
            }
        }
        return text
    }

    /**
     * 9. Marks injected words `verbatimLocked = true`, splices into [targetClauseId] or
     * inter-sentence [insertGapIndex], and pushes per-clause version & global snapshot.
     */
    fun spliceVerbatimPhrase(
        draft: CandidateDraft,
        targetClauseId: String?,
        insertGapIndex: Int?,
        spokenPhrase: String,
        source: EditSource = EditSource.VOICE_SPLICE
    ): CandidateDraft {
        val cleaned = if (source == EditSource.VOICE_SPLICE) {
            stripVoiceDirective(spokenPhrase)
        } else {
            sanitizeAscii(spokenPhrase).trim()
        }
        if (cleaned.isBlank()) return draft

        val words = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return draft

        val injectedTokens = words.mapIndexed { idx, w ->
            WordToken(
                text = w,
                trailingSpace = if (idx < words.lastIndex) " " else "",
                verbatimLocked = true
            )
        }

        if (targetClauseId != null) {
            val targetIdx = draft.clauses.indexOfFirst { it.id == targetClauseId }
            if (targetIdx >= 0) {
                val mutable = draft.clauses.toMutableList()
                val existing = mutable[targetIdx]
                mutable[targetIdx] = appendClauseVersion(
                    clause = existing,
                    newTokens = injectedTokens,
                    source = source
                )
                return pushGlobalSnapshot(draft, mutable.toList())
            }
        }

        val insertionIndex = (insertGapIndex ?: draft.clauses.size).coerceIn(0, draft.clauses.size)
        val newClause = ClauseNode(
            id = nextClauseId(draft.clauses),
            versions = listOf(ClauseVersion(tokens = injectedTokens, source = source)),
            activeVersionIndex = 0
        )
        val mutable = draft.clauses.toMutableList()
        mutable.add(insertionIndex, newClause)
        return pushGlobalSnapshot(draft, mutable.toList())
    }

    /**
     * 10. Diffs old vs new words and automatically marks newly added or modified words
     * `verbatimLocked = true`, preserving existing locks!
     */
    fun applyRawKeyboardEdit(draft: CandidateDraft, editedPlainText: String): CandidateDraft {
        val sanitized = sanitizeAscii(editedPlainText).trim()
        val currentPlain = renderPlainText(draft.clauses)
        if (sanitized == currentPlain) return draft

        if (sanitized.isBlank()) {
            return pushGlobalSnapshot(draft, emptyList())
        }

        val (_, oldFlat) = flattenClauses(draft.clauses)
        val freshClauses = tokenizeIntoClauses(sanitized, source = EditSource.MANUAL_RAW)
        val (_, newFlat) = flattenClauses(freshClauses)

        val oldWords = oldFlat.map { it.token.text }
        val newWords = newFlat.map { it.token.text }
        val matchedNewToOld = computeLcsMapping(oldWords, newWords)

        val lockedFlagsPerNewIndex = BooleanArray(newFlat.size) { newIdx ->
            val oldIdx = matchedNewToOld[newIdx]
            if (oldIdx != null) {
                oldFlat[oldIdx].token.verbatimLocked
            } else {
                true // Newly added or modified word -> automatically verbatimLocked!
            }
        }

        var flatCursor = 0
        val taggedClauses = freshClauses.map { clause ->
            val tokens = clause.activeVersion.tokens.map { tok ->
                val locked = lockedFlagsPerNewIndex[flatCursor++]
                tok.copy(verbatimLocked = locked)
            }
            clause.copy(
                versions = listOf(ClauseVersion(tokens = tokens, source = EditSource.MANUAL_RAW)),
                activeVersionIndex = 0
            )
        }

        val reconciled = reconcileClauseHistories(draft.clauses, taggedClauses, EditSource.MANUAL_RAW)
        return pushGlobalSnapshot(draft, reconciled)
    }

    /**
     * 11. Non-linear per-clause ring undo/redo without reverting other clauses!
     */
    fun stepClauseVersion(draft: CandidateDraft, clauseId: String, delta: Int): CandidateDraft {
        if (delta == 0) return draft
        val targetIdx = draft.clauses.indexOfFirst { it.id == clauseId }
        if (targetIdx < 0) return draft

        val clause = draft.clauses[targetIdx]
        if (clause.versions.isEmpty()) return draft

        val currentIdx = clause.activeVersionIndex.coerceIn(0, clause.versions.lastIndex)
        val nextIdx = (currentIdx + delta).coerceIn(0, clause.versions.lastIndex)
        if (nextIdx == currentIdx) return draft

        val updatedClauses = draft.clauses.toMutableList()
        updatedClauses[targetIdx] = clause.copy(activeVersionIndex = nextIdx)
        return pushGlobalSnapshot(draft, updatedClauses.toList())
    }

    /**
     * 12. Linear global time-travel undo/redo across `globalSnapshots`.
     */
    fun stepGlobalSnapshot(draft: CandidateDraft, delta: Int): CandidateDraft {
        if (delta == 0 || draft.globalSnapshots.isEmpty()) return draft
        val currentCursor = draft.snapshotCursor.coerceIn(0, draft.globalSnapshots.lastIndex)
        val nextCursor = (currentCursor + delta).coerceIn(0, draft.globalSnapshots.lastIndex)
        if (nextCursor == currentCursor) return draft

        return draft.copy(
            clauses = draft.globalSnapshots[nextCursor],
            snapshotCursor = nextCursor,
            linkedSplitCards = emptyList()
        )
    }

    /**
     * 13. Double-tap toggles `verbatimLocked` on the tapped locked span (or word).
     * Tapping a locked token unlocks its entire contiguous locked span; tapping an unlocked
     * token locks that single word.
     */
    fun toggleWordLock(draft: CandidateDraft, charOffset: Int): CandidateDraft {
        val (fullText, flatTokens) = flattenClauses(draft.clauses)
        if (fullText.isEmpty() || flatTokens.isEmpty()) return draft

        val clampedOffset = charOffset.coerceIn(0, fullText.lastIndex)
        val hitFlatIdx = flatTokens.indexOfFirst { ft ->
            clampedOffset in ft.textStart until ft.fullEndExclusive
        }.takeIf { it >= 0 } ?: flatTokens.lastIndex

        val targetToken = flatTokens[hitFlatIdx]
        val newLockedState = !targetToken.token.verbatimLocked

        val indicesToFlip = if (!newLockedState) {
            // Unlocking: expand across the entire contiguous locked span containing hitFlatIdx.
            var left = hitFlatIdx
            while (left > 0 && flatTokens[left - 1].token.verbatimLocked) {
                left--
            }
            var right = hitFlatIdx
            while (right < flatTokens.lastIndex && flatTokens[right + 1].token.verbatimLocked) {
                right++
            }
            (left..right).toSet()
        } else {
            setOf(hitFlatIdx)
        }

        val flipsByClause = indicesToFlip.groupBy { flatTokens[it].clauseIndex }
        val updatedClauses = draft.clauses.mapIndexed { cIdx, clause ->
            val flippedFlat = flipsByClause[cIdx] ?: return@mapIndexed clause
            val flippedTokenIndices = flippedFlat.map { flatTokens[it].tokenIndex }.toSet()
            val activeIdx = clause.activeVersionIndex.coerceIn(0, clause.versions.lastIndex)
            val activeVer = clause.versions[activeIdx]
            val updatedTokens = activeVer.tokens.mapIndexed { tIdx, tok ->
                if (tIdx in flippedTokenIndices) tok.copy(verbatimLocked = newLockedState) else tok
            }
            val updatedVersions = clause.versions.toMutableList()
            updatedVersions[activeIdx] = activeVer.copy(tokens = updatedTokens)
            clause.copy(versions = updatedVersions.toList())
        }

        return pushGlobalSnapshot(draft, updatedClauses)
    }

    /**
     * 14. When `charCount > 280`, splits clauses cleanly at the best sentence boundary near
     * midpoint into 2 linked `List<ClauseNode>` cards (`linkedSplitCards`), preserving ALL
     * verbatim locks and per-clause version histories.
     */
    fun splitOverflowThread(draft: CandidateDraft): CandidateDraft {
        val fullText = renderPlainText(draft.clauses)
        if (fullText.length <= MAX_TWEET_CHARS || draft.clauses.isEmpty()) {
            return draft.copy(linkedSplitCards = emptyList())
        }

        if (draft.clauses.size == 1) {
            val single = draft.clauses[0]
            val tokens = single.activeVersion.tokens
            if (tokens.size < 2) {
                return draft.copy(linkedSplitCards = listOf(draft.clauses))
            }
            val midChars = fullText.length / 2
            var bestSplitTok = 1
            var bestDiff = Int.MAX_VALUE
            var runningLen = 0
            for (i in 0 until tokens.lastIndex) {
                runningLen += tokens[i].text.length + (if (i < tokens.lastIndex) tokens[i].trailingSpace.length else 0)
                val diff = abs(runningLen - midChars)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestSplitTok = i + 1
                }
            }
            val leftTokens = normalizeClauseTokens(tokens.subList(0, bestSplitTok))
            val rightTokens = normalizeClauseTokens(tokens.subList(bestSplitTok, tokens.size))
            val leftClause = ClauseNode(
                id = "${single.id}_a",
                versions = single.versions.dropLast(1) + ClauseVersion(leftTokens, single.activeVersion.source),
                activeVersionIndex = (single.versions.size - 1).coerceAtLeast(0)
            )
            val rightClause = ClauseNode(
                id = "${single.id}_b",
                versions = listOf(ClauseVersion(rightTokens, single.activeVersion.source)),
                activeVersionIndex = 0
            )
            return draft.copy(linkedSplitCards = listOf(listOf(leftClause), listOf(rightClause)))
        }

        val midChars = fullText.length / 2.0

        data class SplitOption(
            val splitIndex: Int,
            val isSentenceBoundary: Boolean,
            val bothFit280: Boolean,
            val midpointDist: Double
        )

        val options = (1 until draft.clauses.size).map { i ->
            val card1 = draft.clauses.subList(0, i)
            val card2 = draft.clauses.subList(i, draft.clauses.size)
            val len1 = renderPlainText(card1).length
            val len2 = renderPlainText(card2).length
            val isSentenceBoundary = endsWithSentencePunctuation(card1.last().plainText())
            SplitOption(
                splitIndex = i,
                isSentenceBoundary = isSentenceBoundary,
                bothFit280 = len1 <= MAX_TWEET_CHARS && len2 <= MAX_TWEET_CHARS,
                midpointDist = abs(len1 - midChars)
            )
        }

        val best = options.sortedWith(
            compareByDescending<SplitOption> { it.isSentenceBoundary && it.bothFit280 }
                .thenByDescending { it.bothFit280 }
                .thenByDescending { it.isSentenceBoundary }
                .thenBy { it.midpointDist }
        ).first()

        val card1 = draft.clauses.subList(0, best.splitIndex).toList()
        val card2 = draft.clauses.subList(best.splitIndex, draft.clauses.size).toList()
        return draft.copy(linkedSplitCards = listOf(card1, card2))
    }

    /**
     * 15. Converts curly quotes to straight ASCII, replaces em/en dashes with plain ASCII
     * (` - ` or `-` between digits), normalizes ellipsis/spaces, and strips trailing whitespace
     * per line.
     */
    fun sanitizeAscii(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")

        var i = 0
        while (i < normalized.length) {
            val ch = normalized[i]
            when (ch) {
                '\u201C', '\u201D', '\u201E', '\u00AB', '\u00BB' -> sb.append('"')
                '\u2018', '\u2019', '\u201A', '\u2032' -> sb.append('\'')
                '\u2014', '\u2015' -> {
                    // Em dash -> spaced ASCII hyphen " - "
                    val prevIsSpace = sb.isNotEmpty() && sb[sb.length - 1] == ' '
                    if (!prevIsSpace && sb.isNotEmpty() && sb[sb.length - 1] != '\n') {
                        sb.append(' ')
                    }
                    sb.append('-')
                    val nextIsSpace = i + 1 < normalized.length && normalized[i + 1] == ' '
                    if (!nextIsSpace && i + 1 < normalized.length && normalized[i + 1] != '\n') {
                        sb.append(' ')
                    }
                }
                '\u2013', '\u2012' -> {
                    // En dash -> compact "-" between digits (e.g., 10-20ms), else " - "
                    val prevDigit = i > 0 && normalized[i - 1].isDigit()
                    val nextDigit = i + 1 < normalized.length && normalized[i + 1].isDigit()
                    if (prevDigit && nextDigit) {
                        sb.append('-')
                    } else {
                        val prevIsSpace = sb.isNotEmpty() && sb[sb.length - 1] == ' '
                        if (!prevIsSpace && sb.isNotEmpty() && sb[sb.length - 1] != '\n') {
                            sb.append(' ')
                        }
                        sb.append('-')
                        val nextIsSpace = i + 1 < normalized.length && normalized[i + 1] == ' '
                        if (!nextIsSpace && i + 1 < normalized.length && normalized[i + 1] != '\n') {
                            sb.append(' ')
                        }
                    }
                }
                '\u2026' -> sb.append("...")
                '\u00A0', '\u2009', '\u200A', '\u202F' -> sb.append(' ')
                '\u200B', '\u200C', '\u200D', '\uFEFF' -> { /* strip zero-width chars */ }
                '\u2022' -> sb.append('-')
                else -> sb.append(ch)
            }
            i++
        }

        return sb.toString()
            .split('\n')
            .joinToString("\n") { line ->
                line.replace(Regex(" {2,}"), " ").trimEnd(' ', '\t')
            }
    }

    /**
     * 16. Clip Studio v5 single-tap natural phrase selection:
     * Given a [charOffset] in [draft]'s plain text, selects a natural 3–6 word grammatical
     * phrase span within the tapped clause (or the exact contiguous locked span if the tapped
     * word is verbatim-locked), never crossing clause or paragraph boundaries.
     */
    fun selectNaturalPhraseRange(draft: CandidateDraft, charOffset: Int): IntRange? {
        val (fullText, flatTokens) = flattenClauses(draft.clauses)
        if (fullText.isEmpty() || flatTokens.isEmpty()) return null

        val clamped = charOffset.coerceIn(0, fullText.lastIndex)
        val hitGlobalIdx = flatTokens.indexOfFirst { ft ->
            clamped in ft.textStart until ft.fullEndExclusive
        }.takeIf { it >= 0 } ?: flatTokens.lastIndex

        val hitToken = flatTokens[hitGlobalIdx]
        if (hitToken.token.verbatimLocked) {
            var left = hitGlobalIdx
            while (left > 0 &&
                flatTokens[left - 1].clauseIndex == hitToken.clauseIndex &&
                flatTokens[left - 1].token.verbatimLocked
            ) {
                left--
            }
            var right = hitGlobalIdx
            while (right < flatTokens.lastIndex &&
                flatTokens[right + 1].clauseIndex == hitToken.clauseIndex &&
                flatTokens[right + 1].token.verbatimLocked
            ) {
                right++
            }
            return flatTokens[left].textStart until flatTokens[right].textEndExclusive
        }

        val clauseTokens = flatTokens.filter { it.clauseIndex == hitToken.clauseIndex }
        if (clauseTokens.isEmpty()) return null

        val segments = splitClauseIntoNaturalSegments(clauseTokens)
        val seg = segments.firstOrNull { s -> s.any { it.tokenIndex == hitToken.tokenIndex } }
            ?: clauseTokens

        if (seg.size <= 6) {
            return seg.first().textStart until seg.last().textEndExclusive
        }

        val localIdx = seg.indexOfFirst { it.tokenIndex == hitToken.tokenIndex }.coerceAtLeast(0)
        val windowSize = 4
        val halfLeft = 1
        var winStart = (localIdx - halfLeft).coerceAtLeast(0)
        var winEnd = (winStart + windowSize - 1).coerceAtMost(seg.lastIndex)
        winStart = (winEnd - windowSize + 1).coerceAtLeast(0)

        return seg[winStart].textStart until seg[winEnd].textEndExclusive
    }

    /**
     * Partitions [draft] into non-overlapping natural 3–6 word phrase spans for chunk-based tap UIs.
     */
    fun partitionIntoPhraseSpans(draft: CandidateDraft): List<IntRange> {
        val (_, flatTokens) = flattenClauses(draft.clauses)
        if (flatTokens.isEmpty()) return emptyList()

        val out = mutableListOf<IntRange>()
        val byClause = flatTokens.groupBy { it.clauseIndex }
        for ((_, clauseTokens) in byClause) {
            var i = 0
            while (i < clauseTokens.size) {
                if (clauseTokens[i].token.verbatimLocked) {
                    val start = i
                    while (i + 1 < clauseTokens.size && clauseTokens[i + 1].token.verbatimLocked) {
                        i++
                    }
                    out += clauseTokens[start].textStart until clauseTokens[i].textEndExclusive
                    i++
                    continue
                }
                val unlockedRun = mutableListOf<FlattenedToken>()
                while (i < clauseTokens.size && !clauseTokens[i].token.verbatimLocked) {
                    val tok = clauseTokens[i]
                    unlockedRun += tok
                    i++
                    val tokLastCh = tok.token.text.lastOrNull()
                    if (tokLastCh != null && tokLastCh in charArrayOf(',', ';', ':') && unlockedRun.size >= 2) {
                        break
                    }
                }
                var cursor = 0
                while (cursor < unlockedRun.size) {
                    val remaining = unlockedRun.size - cursor
                    val takeCount = when {
                        remaining <= 6 -> remaining
                        remaining == 7 -> 4
                        else -> 4
                    }
                    val slice = unlockedRun.subList(cursor, cursor + takeCount)
                    out += slice.first().textStart until slice.last().textEndExclusive
                    cursor += takeCount
                }
            }
        }
        return out
    }

    private fun splitClauseIntoNaturalSegments(
        clauseTokens: List<FlattenedToken>
    ): List<List<FlattenedToken>> {
        val segments = mutableListOf<List<FlattenedToken>>()
        var current = mutableListOf<FlattenedToken>()
        for (ft in clauseTokens) {
            if (ft.token.verbatimLocked) {
                if (current.isNotEmpty()) {
                    segments += current.toList()
                    current = mutableListOf()
                }
                segments += listOf(ft)
                continue
            }
            current += ft
            val lastCh = ft.token.text.lastOrNull()
            if (lastCh != null && lastCh in charArrayOf(',', ';', ':') && current.size >= 2) {
                segments += current.toList()
                current = mutableListOf()
            } else if (ft.token.text == "-" && current.size >= 2) {
                val withoutDash = current.dropLast(1)
                if (withoutDash.isNotEmpty()) segments += withoutDash
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty()) {
            segments += current.toList()
        }
        return segments
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private data class ClauseBound(
        val start: Int,
        val end: Int,
        val trailingSeparatorAfterClause: String
    )

    private fun splitIntoClauseBounds(text: String): List<ClauseBound> {
        val bounds = mutableListOf<ClauseBound>()
        val n = text.length
        var start = skipHorizontalSpace(text, 0)
        var i = start

        while (i < n) {
            val ch = text[i]

            // 1. Hard newline break
            if (ch == '\n') {
                var nlEnd = i
                while (nlEnd < n && text[nlEnd] == '\n') nlEnd++
                val nextNonSpace = skipHorizontalSpace(text, nlEnd)
                val nlSep = text.substring(i, nlEnd)
                if (i > start && text.substring(start, i).isNotBlank()) {
                    bounds += ClauseBound(
                        start = start,
                        end = trimEndHorizontalSpace(text, start, i),
                        trailingSeparatorAfterClause = if (nextNonSpace < n) nlSep else ""
                    )
                }
                start = nextNonSpace
                i = start
                continue
            }

            // 2. Sentence-ending punctuation (. ! ?)
            if ((ch == '.' || ch == '!' || ch == '?') && isSentenceBoundary(text, i)) {
                var punctEnd = i + 1
                while (punctEnd < n && text[punctEnd] in charArrayOf('"', '\'', ')', ']')) {
                    punctEnd++
                }
                val afterWs = skipHorizontalSpace(text, punctEnd)
                if (afterWs < n && text[afterWs] != '\n' && afterWs > punctEnd) {
                    bounds += ClauseBound(
                        start = start,
                        end = punctEnd,
                        trailingSeparatorAfterClause = ""
                    )
                    start = afterWs
                    i = start
                    continue
                }
            }

            // 3. Semicolon clause boundary
            if (ch == ';') {
                val afterWs = skipHorizontalSpace(text, i + 1)
                if (afterWs < n && text[afterWs] != '\n' && afterWs > i + 1) {
                    bounds += ClauseBound(
                        start = start,
                        end = i + 1,
                        trailingSeparatorAfterClause = ""
                    )
                    start = afterWs
                    i = start
                    continue
                }
            }

            // 4. Comma + coordinating conjunction clause boundary
            if (ch == ',') {
                val afterWs = skipHorizontalSpace(text, i + 1)
                if (afterWs < n && afterWs > i + 1 && text[afterWs] != '\n') {
                    val nextWordEnd = readWordEnd(text, afterWs)
                    val nextWord = text.substring(afterWs, nextWordEnd).lowercase()
                    if (nextWord in CLAUSE_CONJUNCTIONS) {
                        val beforeSlice = text.substring(start, i)
                        val afterRest = text.substring(nextWordEnd, findLineOrSentenceEnd(text, nextWordEnd))
                        val wordsBefore = countWords(beforeSlice)
                        val wordsAfter = countWords(afterRest)
                        val looksLikeList = looksLikeCommaList(beforeSlice)
                        if (wordsBefore >= 2 && wordsAfter >= 2 && !looksLikeList) {
                            bounds += ClauseBound(
                                start = start,
                                end = i + 1,
                                trailingSeparatorAfterClause = ""
                            )
                            start = afterWs
                            i = start
                            continue
                        }
                    }
                }
            }

            i++
        }

        if (start < n && text.substring(start, n).isNotBlank()) {
            bounds += ClauseBound(
                start = start,
                end = trimEndHorizontalSpace(text, start, n),
                trailingSeparatorAfterClause = ""
            )
        }

        return bounds
    }

    private fun isSentenceBoundary(text: String, punctIdx: Int): Boolean {
        val ch = text[punctIdx]
        val n = text.length
        if (punctIdx + 1 >= n) return false

        // Must be followed by optional closing quote/paren and then whitespace.
        var afterPunct = punctIdx + 1
        while (afterPunct < n && text[afterPunct] in charArrayOf('"', '\'', ')', ']')) {
            afterPunct++
        }
        if (afterPunct >= n || (text[afterPunct] != ' ' && text[afterPunct] != '\t')) {
            return false
        }

        if (ch == '!' || ch == '?') return true

        // Check decimals like "3.5" or domains/IPs like "10.0.0.242" (already rejected by whitespace check above)
        // Check ellipsis "..."
        if (punctIdx > 0 && text[punctIdx - 1] == '.') {
            val nextNonWs = skipHorizontalSpace(text, afterPunct)
            return nextNonWs < n && text[nextNonWs].isUpperCase()
        }

        // Check abbreviations & single-letter initials
        var wordStart = punctIdx
        while (wordStart > 0 && !text[wordStart - 1].isWhitespace()) {
            wordStart--
        }
        val tokenWithDot = text.substring(wordStart, punctIdx + 1).lowercase()
        if (tokenWithDot in ABBREVIATIONS) return false
        if (tokenWithDot.length == 2 && tokenWithDot[0].isLetter() && tokenWithDot[1] == '.') {
            return false
        }
        return true
    }

    private fun looksLikeCommaList(beforeComma: String): Boolean {
        val tokens = beforeComma.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return false
        // If either of the 2 preceding tokens ends with a comma (e.g. "red, white,"), this is a list.
        val tail = tokens.takeLast(2)
        return tail.any { it.endsWith(",") }
    }

    private fun findLineOrSentenceEnd(text: String, fromIndex: Int): Int {
        var i = fromIndex
        while (i < text.length) {
            if (text[i] == '\n') return i
            if ((text[i] == '.' || text[i] == '!' || text[i] == '?') && isSentenceBoundary(text, i)) {
                return i + 1
            }
            i++
        }
        return text.length
    }

    private fun countWords(slice: String): Int =
        slice.trim().split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }

    private fun skipHorizontalSpace(text: String, start: Int): Int {
        var i = start
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    private fun trimEndHorizontalSpace(text: String, minStart: Int, endExclusive: Int): Int {
        var i = endExclusive
        while (i > minStart && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
        return i
    }

    private fun readWordEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isLetter()) i++
        return i
    }

    private fun findLockedCharRanges(text: String, lockedSubstrings: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (raw in lockedSubstrings) {
            val needle = raw.trim()
            if (needle.isEmpty()) continue
            var searchFrom = 0
            while (searchFrom <= text.length - needle.length) {
                val found = text.indexOf(needle, startIndex = searchFrom)
                if (found < 0) break
                val endExclusive = found + needle.length
                val validLeft = found == 0 ||
                    !needle.first().isLetterOrDigit() ||
                    !text[found - 1].isLetterOrDigit()
                val validRight = endExclusive == text.length ||
                    !needle.last().isLetterOrDigit() ||
                    !text[endExclusive].isLetterOrDigit()
                if (validLeft && validRight) {
                    ranges += found until endExclusive
                }
                searchFrom = found + 1
            }
        }
        return ranges
    }

    private fun findMissingLockedPhrases(
        candidateText: String,
        requiredPhrases: List<String>
    ): List<String> {
        if (requiredPhrases.isEmpty()) return emptyList()

        val occupied = BooleanArray(candidateText.length)
        val missing = mutableListOf<String>()

        for (phrase in requiredPhrases) {
            val needle = phrase.trim()
            if (needle.isEmpty()) continue
            var matched = false
            var searchFrom = 0
            while (searchFrom <= candidateText.length - needle.length) {
                val idx = candidateText.indexOf(needle, startIndex = searchFrom)
                if (idx < 0) break
                val endExclusive = idx + needle.length
                val validLeft = idx == 0 ||
                    !needle.first().isLetterOrDigit() ||
                    !candidateText[idx - 1].isLetterOrDigit()
                val validRight = endExclusive == candidateText.length ||
                    !needle.last().isLetterOrDigit() ||
                    !candidateText[endExclusive].isLetterOrDigit()
                val free = (idx until endExclusive).none { occupied[it] }
                if (validLeft && validRight && free) {
                    for (pos in idx until endExclusive) occupied[pos] = true
                    matched = true
                    break
                }
                searchFrom = idx + 1
            }
            if (!matched) {
                missing += phrase
            }
        }
        return missing
    }

    private fun allLockedPhrasesCovered(
        required: List<String>,
        resultingLocked: List<String>
    ): Boolean {
        if (required.isEmpty()) return true
        val joined = resultingLocked.joinToString("\n")
        return findMissingLockedPhrases(joined, required).isEmpty()
    }

    private fun reconcileClauseHistories(
        originalClauses: List<ClauseNode>,
        newClauses: List<ClauseNode>,
        source: EditSource
    ): List<ClauseNode> {
        val usedOriginalIds = HashSet<String>()
        val matchedExact = arrayOfNulls<ClauseNode>(newClauses.size)

        // Pass 1: Preserve exact unchanged clauses first so insertions/deletions don't steal IDs.
        newClauses.forEachIndexed { idx, fresh ->
            val exact = originalClauses.firstOrNull { orig ->
                orig.id !in usedOriginalIds && orig.activeVersion.tokens == fresh.activeVersion.tokens
            }
            if (exact != null) {
                matchedExact[idx] = exact
                usedOriginalIds += exact.id
            }
        }

        // Pass 2: Reconcile modified clauses positionally, or allocate collision-free IDs for new ones.
        val assigned = mutableListOf<ClauseNode>()
        newClauses.forEachIndexed { idx, fresh ->
            val preserved = matchedExact[idx]
            if (preserved != null) {
                assigned += preserved
                return@forEachIndexed
            }

            val positional = originalClauses.getOrNull(idx)?.takeIf { it.id !in usedOriginalIds }
            if (positional != null) {
                usedOriginalIds += positional.id
                assigned += appendClauseVersion(positional, fresh.activeVersion.tokens, source)
            } else {
                val uniqueId = if (fresh.id !in usedOriginalIds) {
                    fresh.id
                } else {
                    nextClauseId(originalClauses + assigned)
                }
                usedOriginalIds += uniqueId
                assigned += fresh.copy(id = uniqueId)
            }
        }
        return assigned
    }

    private fun mergeDimRanges(text: String, rawRanges: List<IntRange>): Set<IntRange> {
        if (rawRanges.isEmpty()) return emptySet()
        val sorted = rawRanges.sortedWith(compareBy<IntRange> { it.first }.thenBy { it.last })
        val merged = LinkedHashSet<IntRange>()
        var current = sorted.first()

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val touchesOrOverlaps = next.first <= current.last + 1
            val separatedBySingleSpace = next.first == current.last + 2 &&
                current.last + 1 < text.length &&
                text[current.last + 1] == ' '
            if (touchesOrOverlaps || separatedBySingleSpace) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun applyLocalGlueCleanup(
        clauses: List<ClauseNode>,
        cutIndex: Int,
        removedEndedSentence: Boolean,
        removedTerminalPunct: Char,
        source: EditSource
    ): List<ClauseNode> {
        val mutable = clauses.toMutableList()

        // 1. Cleanup clause immediately BEFORE the cut (at cutIndex - 1)
        val prevIdx = cutIndex - 1
        if (prevIdx in mutable.indices) {
            val prevClause = mutable[prevIdx]
            val nextClause = mutable.getOrNull(cutIndex)
            val exposedToEndOrNewSentence = nextClause == null ||
                nextClause.plainText().firstOrNull()?.isUpperCase() == true ||
                removedEndedSentence

            if (exposedToEndOrNewSentence) {
                val cleanedPrev = stripOrphanedTrailingGlue(
                    tokens = prevClause.activeVersion.tokens,
                    ensureSentenceTerminal = removedEndedSentence || nextClause == null,
                    terminalPunct = removedTerminalPunct
                )
                if (cleanedPrev != prevClause.activeVersion.tokens) {
                    if (cleanedPrev.isEmpty()) {
                        mutable.removeAt(prevIdx)
                    } else {
                        mutable[prevIdx] = appendClauseVersion(prevClause, cleanedPrev, source)
                    }
                }
            }
        }

        // 2. Cleanup clause immediately AFTER the cut (now at cutIndex, or shifted if prev emptied)
        val effectiveNextIdx = cutIndex.coerceAtMost(mutable.lastIndex)
        if (effectiveNextIdx in mutable.indices) {
            val prevAfterCleanup = mutable.getOrNull(effectiveNextIdx - 1)
            val needsSentenceStart = effectiveNextIdx == 0 ||
                (prevAfterCleanup != null && endsWithSentencePunctuation(prevAfterCleanup.plainText()))

            if (needsSentenceStart) {
                val nextClause = mutable[effectiveNextIdx]
                val cleanedNext = stripOrphanedLeadingGlueAndCapitalize(nextClause.activeVersion.tokens)
                if (cleanedNext != nextClause.activeVersion.tokens) {
                    if (cleanedNext.isEmpty()) {
                        mutable.removeAt(effectiveNextIdx)
                    } else {
                        mutable[effectiveNextIdx] = appendClauseVersion(nextClause, cleanedNext, source)
                    }
                }
            }
        }

        // 3. Trim double spaces on unlocked tokens across affected clauses
        for (idx in mutable.indices) {
            val clause = mutable[idx]
            val tokens = clause.activeVersion.tokens
            val compacted = tokens.mapIndexed { tIdx, tok ->
                if (!tok.verbatimLocked) {
                    val cleanSpace = when {
                        tIdx == tokens.lastIndex -> if (tok.trailingSpace.contains('\n')) tok.trailingSpace else ""
                        tok.trailingSpace.contains('\n') -> tok.trailingSpace
                        else -> if (tok.trailingSpace.length > 1) " " else tok.trailingSpace
                    }
                    if (cleanSpace != tok.trailingSpace) tok.copy(trailingSpace = cleanSpace) else tok
                } else {
                    tok
                }
            }
            if (compacted != tokens) {
                mutable[idx] = appendClauseVersion(clause, compacted, source)
            }
        }

        return mutable.toList()
    }

    private fun stripOrphanedTrailingGlue(
        tokens: List<WordToken>,
        ensureSentenceTerminal: Boolean,
        terminalPunct: Char
    ): List<WordToken> {
        val list = tokens.toMutableList()

        // Strip trailing unlocked orphaned connector words (", and", "and", "but", "so", etc.)
        while (list.isNotEmpty()) {
            val last = list.last()
            if (last.verbatimLocked) break
            val bare = last.text.trim(',', ';', ':', ' ').lowercase()
            if (bare in ORPHAN_TRAILING_CONNECTORS) {
                list.removeAt(list.lastIndex)
            } else {
                break
            }
        }

        if (list.isEmpty()) return emptyList()

        val last = list.last()
        if (!last.verbatimLocked) {
            var newText = last.text
            if (newText.endsWith(",") || newText.endsWith(";") || newText.endsWith(":")) {
                newText = newText.dropLast(1)
                if (ensureSentenceTerminal && newText.isNotEmpty() && !endsWithSentencePunctuation(newText)) {
                    newText += terminalPunct
                }
            } else if (ensureSentenceTerminal && !endsWithSentencePunctuation(newText)) {
                newText += terminalPunct
            }
            if (newText != last.text) {
                list[list.lastIndex] = last.copy(text = newText)
            }
        }

        return normalizeClauseTokens(list)
    }

    private fun stripOrphanedLeadingGlueAndCapitalize(tokens: List<WordToken>): List<WordToken> {
        val list = tokens.toMutableList()

        // Strip leading unlocked orphaned connectors (", and", ", but", ", so", "and", "but", "so")
        while (list.isNotEmpty()) {
            val first = list.first()
            if (first.verbatimLocked) break
            val trimmed = first.text.trimStart(',', ';', ':')
            val bare = trimmed.trimEnd(',', ';', ':').lowercase()
            if (bare.isEmpty() || bare in ORPHAN_LEADING_CONNECTORS) {
                list.removeAt(0)
            } else if (trimmed != first.text) {
                list[0] = first.copy(text = trimmed)
                break
            } else {
                break
            }
        }

        if (list.isEmpty()) return emptyList()

        val head = list[0]
        if (!head.verbatimLocked &&
            !head.text.startsWith("@") &&
            !head.text.startsWith("http") &&
            head.text.firstOrNull()?.isLowerCase() == true
        ) {
            list[0] = head.copy(text = head.text.replaceFirstChar { it.uppercaseChar() })
        }

        return normalizeClauseTokens(list)
    }

    private fun appendClauseVersion(
        clause: ClauseNode,
        newTokens: List<WordToken>,
        source: EditSource
    ): ClauseNode {
        val baseVersions = if (clause.versions.isEmpty()) {
            emptyList()
        } else {
            val cursor = clause.activeVersionIndex.coerceIn(0, clause.versions.lastIndex)
            clause.versions.take(cursor + 1)
        }
        val updated = baseVersions + ClauseVersion(tokens = newTokens, source = source)
        return clause.copy(
            versions = updated,
            activeVersionIndex = updated.lastIndex
        )
    }

    private fun pushGlobalSnapshot(
        draft: CandidateDraft,
        newClauses: List<ClauseNode>
    ): CandidateDraft {
        val baseHistory = if (draft.globalSnapshots.isEmpty()) {
            listOf(draft.clauses)
        } else {
            val cursor = draft.snapshotCursor.coerceIn(0, draft.globalSnapshots.lastIndex)
            draft.globalSnapshots.take(cursor + 1)
        }
        val combined = baseHistory + listOf(newClauses)
        val trimmed = if (combined.size > MAX_GLOBAL_SNAPSHOTS) {
            combined.takeLast(MAX_GLOBAL_SNAPSHOTS)
        } else {
            combined
        }
        return draft.copy(
            clauses = newClauses,
            globalSnapshots = trimmed,
            snapshotCursor = trimmed.lastIndex,
            linkedSplitCards = emptyList()
        )
    }

    private fun normalizeClauseTokens(
        tokens: List<WordToken>,
        preserveTerminalFromDeleted: String = "",
        clauseTrailingNewline: String = ""
    ): List<WordToken> {
        if (tokens.isEmpty()) return emptyList()
        return tokens.mapIndexed { idx, tok ->
            val isLast = idx == tokens.lastIndex
            var newText = tok.text
            if (isLast && preserveTerminalFromDeleted.isNotEmpty() && !tok.verbatimLocked &&
                !endsWithSentencePunctuation(newText)
            ) {
                newText = newText.trimEnd(',', ';', ':') + preserveTerminalFromDeleted
            }
            val nextIsClosingPunct = !isLast &&
                tokens[idx + 1].text.isNotEmpty() &&
                tokens[idx + 1].text.all { it in ".,!?;:)]\"'" }
            val currIsOpeningPunct = tok.text.isNotEmpty() &&
                tok.text.all { it in "([\"'" } &&
                tok.trailingSpace.isEmpty()
            val newTrailing = when {
                !isLast && (nextIsClosingPunct || currIsOpeningPunct) -> ""
                !isLast -> if (tok.trailingSpace.isEmpty()) " " else if (tok.trailingSpace.contains('\n')) tok.trailingSpace else " "
                clauseTrailingNewline.isNotEmpty() -> clauseTrailingNewline
                tok.trailingSpace.contains('\n') -> tok.trailingSpace
                else -> ""
            }
            tok.copy(text = newText, trailingSpace = newTrailing)
        }
    }

    private fun computeLcsMapping(oldWords: List<String>, newWords: List<String>): Map<Int, Int> {
        val n = oldWords.size
        val m = newWords.size
        if (n == 0 || m == 0) return emptyMap()

        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (oldWords[i] == newWords[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val newToOld = HashMap<Int, Int>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                oldWords[i] == newWords[j] -> {
                    newToOld[j] = i
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return newToOld
    }

    private fun extractTerminalPunctuation(word: String): String {
        val sb = StringBuilder()
        var idx = word.lastIndex
        while (idx >= 0 && word[idx] in charArrayOf('.', '!', '?', ',', ';', ':', '"', '\'', ')')) {
            sb.append(word[idx])
            idx--
        }
        return sb.reverse().toString()
    }

    private fun endsWithSentencePunctuation(text: String): Boolean {
        val trimmed = text.trimEnd(' ', '\t', '\n', '"', '\'', ')', ']')
        return trimmed.isNotEmpty() && trimmed.last() in charArrayOf('.', '!', '?')
    }

    private fun stripOuterQuotes(text: String): String {
        var s = text.trim()
        while (s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s
    }

    private fun nextClauseId(existing: List<ClauseNode>): String {
        val existingIds = existing.map { it.id }.toSet()
        var candidateNum = existing.size
        while ("c_$candidateNum" in existingIds) {
            candidateNum++
        }
        return "c_$candidateNum"
    }
}
