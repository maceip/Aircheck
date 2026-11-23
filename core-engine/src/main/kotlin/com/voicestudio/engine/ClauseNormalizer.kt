package com.voicestudio.engine

import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.DEFAULT_BASE_GAP_SAMPLES
import com.voicestudio.edl.MAX_CLAUSE_DURATION_SEC
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Deterministic Milestone 1 G2P Normalizer & `< 14.0s` Clause Splitter.
 *
 * - Loads OTA `lexicon.json` IPA pronunciation overrides (plus per-call inline overrides).
 * - Deterministically expands English numbers, decimals, currency, percentages, ordinals,
 *   contractions, broadcast units, and acronyms before IPA grapheme-to-phoneme conversion.
 * - Splits raw draft scripts into sequential [ClauseNode]s (`c_001`, `c_002`, ...) guaranteed
 *   strictly `< 14.0s` estimated duration each while preserving natural prosody boundaries.
 * - Chains `acousticPrefixPcmPath` from clause `k - 1` (`takes/c_{k-1}_winner_tail_1500ms.pcm`).
 */
class ClauseNormalizer(
    private val takesDirPrefix: String = "takes",
    private val conservativeSyllablesPerSec: Double = CONSERVATIVE_SYLLABLES_PER_SEC,
    private val maxTargetClauseSeconds: Double = TARGET_MAX_CLAUSE_SEC
) {
    init {
        require(conservativeSyllablesPerSec > 0.0) { "conservativeSyllablesPerSec must be > 0" }
        require(maxTargetClauseSeconds > 1.0 && maxTargetClauseSeconds < MAX_CLAUSE_DURATION_SEC) {
            "maxTargetClauseSeconds must be strictly in (1.0, $MAX_CLAUSE_DURATION_SEC)"
        }
    }

    /**
     * Loads `lexicon.json` into a deterministic term -> IPA map.
     * Supports flat JSON objects (`{"Aircheck": "ˈɛərˌtʃɛk"}`) and wrapped schemas
     * (`{"entries": {...}}` or `{"lexicon": {...}}`).
     */
    fun loadLexicon(lexiconFile: File?): Map<String, String> {
        if (lexiconFile == null || !lexiconFile.isFile || !lexiconFile.canRead()) {
            return emptyMap()
        }
        val raw = lexiconFile.readText(Charsets.UTF_8).trim()
        if (raw.isEmpty()) {
            return emptyMap()
        }
        val root = json.parseToJsonElement(raw).jsonObject
        val entriesObj: JsonObject = when {
            root["entries"] is JsonObject -> root["entries"]!!.jsonObject
            root["lexicon"] is JsonObject -> root["lexicon"]!!.jsonObject
            else -> root
        }
        val result = LinkedHashMap<String, String>(entriesObj.size)
        for ((key, value) in entriesObj) {
            val ipa = (value as? JsonPrimitive)?.contentOrNull?.trim()
            if (!ipa.isNullOrEmpty() && key.isNotBlank()) {
                result[key.trim()] = ipa
            }
        }
        return result
    }

    /**
     * Normalizes [rawDraft] using OTA [lexiconFile] + inline [phoneticOverrides] and splits into
     * sequential [ClauseNode]s strictly `< 14.0s` each with chained `acousticPrefixPcmPath`.
     */
    fun normalizeAndSplit(
        projectId: String,
        rawDraft: String,
        lexiconFile: File? = null,
        phoneticOverrides: Map<String, String> = emptyMap()
    ): List<ClauseNode> {
        require(projectId.isNotBlank()) { "projectId must not be blank" }
        val lexicon = loadLexicon(lexiconFile)
        return normalizeAndSplitWithLexicon(rawDraft, lexicon, phoneticOverrides)
    }

    fun normalizeAndSplitWithLexicon(
        rawDraft: String,
        lexicon: Map<String, String> = emptyMap(),
        phoneticOverrides: Map<String, String> = emptyMap()
    ): List<ClauseNode> {
        val cleaned = rawDraft
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (cleaned.isEmpty()) {
            return emptyList()
        }

        val sentences = splitIntoSentences(cleaned)
        val rawClauses = ArrayList<String>(sentences.size)
        for (sentence in sentences) {
            splitClauseToDurationLimit(sentence, rawClauses)
        }

        val normalizedPrefixDir = takesDirPrefix.trimEnd('/')
        return rawClauses.mapIndexed { idx, clauseText ->
            val clauseId = formatClauseId(idx + 1)
            val prefixPath = if (idx == 0) {
                null
            } else {
                val prevId = formatClauseId(idx)
                "$normalizedPrefixDir/${prevId}_winner_tail_1500ms.pcm"
            }
            ClauseNode(
                clauseId = clauseId,
                index = idx,
                rawText = clauseText,
                normalizedPhonemes = normalizeClauseToIpa(clauseText, lexicon, phoneticOverrides),
                acousticPrefixPcmPath = prefixPath,
                selectedTakeIndex = 0,
                baseGapAfterSamples = DEFAULT_BASE_GAP_SAMPLES,
                candidates = emptyList()
            )
        }
    }

    /**
     * Deterministically converts a single clause string into IPA phonemes, applying
     * [phoneticOverrides] first, then OTA [lexicon], then deterministic English expansion + G2P.
     */
    fun normalizeClauseToIpa(
        clauseText: String,
        lexicon: Map<String, String> = emptyMap(),
        phoneticOverrides: Map<String, String> = emptyMap()
    ): String {
        val exactLookup = LinkedHashMap<String, String>()
        val lowerLookup = LinkedHashMap<String, String>()
        for ((k, v) in lexicon) {
            val trimmedKey = k.trim()
            val trimmedVal = v.trim()
            if (trimmedKey.isNotEmpty() && trimmedVal.isNotEmpty()) {
                exactLookup[trimmedKey] = trimmedVal
                lowerLookup[trimmedKey.lowercase(Locale.US)] = trimmedVal
            }
        }
        for ((k, v) in phoneticOverrides) {
            val trimmedKey = k.trim()
            val trimmedVal = v.trim()
            if (trimmedKey.isNotEmpty() && trimmedVal.isNotEmpty()) {
                exactLookup[trimmedKey] = trimmedVal
                lowerLookup[trimmedKey.lowercase(Locale.US)] = trimmedVal
            }
        }

        val tokens = TOKEN_REGEX.findAll(clauseText).map { it.value }.toList()
        val out = ArrayList<String>(tokens.size)

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.isBlank()) {
                i++
                continue
            }

            // Try 2-word phrase override/lexicon match first (e.g. "Aircheck Studio").
            if (i + 1 < tokens.size && isWordLike(token) && isWordLike(tokens[i + 1])) {
                val phrase = "$token ${tokens[i + 1]}"
                val phraseIpa = exactLookup[phrase] ?: lowerLookup[phrase.lowercase(Locale.US)]
                if (phraseIpa != null) {
                    out += phraseIpa
                    i += 2
                    continue
                }
            }

            // Preserve natural prosodic punctuation markers.
            if (token.length == 1 && token[0] in PROSODY_PUNCTUATION) {
                out += token
                i++
                continue
            }

            // Direct exact or case-insensitive lexicon / override match (takes top precedence).
            val directIpa = exactLookup[token] ?: lowerLookup[token.lowercase(Locale.US)]
            if (directIpa != null) {
                out += directIpa
                i++
                continue
            }

            // Expand English text constructs (numbers, currency, contractions, abbreviations, acronyms).
            val expandedWords = expandEnglishToken(token)
            for (word in expandedWords) {
                val mapped = exactLookup[word] ?: lowerLookup[word.lowercase(Locale.US)]
                if (mapped != null) {
                    out += mapped
                } else {
                    out += graphemeToIpa(word)
                }
            }
            i++
        }

        return out.joinToString(" ").replace(MULTI_SPACE_REGEX, " ").trim()
    }

    /**
     * Conservative clause duration estimator in seconds based on expanded English syllable count
     * at the lower Gate-4 cadence bound (4.2 syl/s) plus natural punctuation pauses.
     */
    fun estimateDurationSeconds(text: String): Double {
        if (text.isBlank()) return 0.0
        val tokens = TOKEN_REGEX.findAll(text).map { it.value }.toList()
        var syllables = 0
        var pauseSec = 0.0
        for (token in tokens) {
            when {
                token == "," -> pauseSec += COMMA_PAUSE_SEC
                token == ";" || token == ":" || token == "—" || token == "–" -> pauseSec += SEMICOLON_PAUSE_SEC
                token == "." || token == "!" || token == "?" -> pauseSec += SENTENCE_END_PAUSE_SEC
                isWordLike(token) || token.any { it.isDigit() } -> {
                    val expanded = expandEnglishToken(token)
                    for (w in expanded) {
                        syllables += countWordSyllables(w)
                    }
                }
            }
        }
        return (syllables.toDouble() / conservativeSyllablesPerSec) + pauseSec
    }

    fun countSyllables(text: String): Int {
        if (text.isBlank()) return 0
        var total = 0
        for (match in TOKEN_REGEX.findAll(text)) {
            val token = match.value
            if (isWordLike(token) || token.any { it.isDigit() }) {
                for (w in expandEnglishToken(token)) {
                    total += countWordSyllables(w)
                }
            }
        }
        return total
    }

    private fun splitIntoSentences(text: String): List<String> {
        val result = ArrayList<String>()
        val paragraphs = text.split(Regex("\\n+"))
        for (paragraph in paragraphs) {
            val trimmed = paragraph.replace(MULTI_SPACE_REGEX, " ").trim()
            if (trimmed.isEmpty()) continue

            val current = StringBuilder()
            var i = 0
            while (i < trimmed.length) {
                val ch = trimmed[i]
                current.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '…') {
                    // Consume consecutive terminal punctuation (e.g. "..." or "?!").
                    while (i + 1 < trimmed.length && trimmed[i + 1] in ".!?…") {
                        i++
                        current.append(trimmed[i])
                    }
                    val nextIsBoundary = i + 1 >= trimmed.length || trimmed[i + 1].isWhitespace()
                    val isDecimal = ch == '.' &&
                        i > 0 && trimmed[i - 1].isDigit() &&
                        i + 1 < trimmed.length && trimmed[i + 1].isDigit()
                    val isAbbreviation = ch == '.' && endsWithAbbreviation(current.toString())

                    if (nextIsBoundary && !isDecimal && !isAbbreviation) {
                        // Include closing quote/bracket if immediately attached.
                        while (i + 1 < trimmed.length && trimmed[i + 1] in "\"')»”’]") {
                            i++
                            current.append(trimmed[i])
                        }
                        val sentence = current.toString().trim()
                        if (sentence.isNotEmpty()) {
                            result += sentence
                        }
                        current.clear()
                    }
                }
                i++
            }
            val remainder = current.toString().trim()
            if (remainder.isNotEmpty()) {
                result += remainder
            }
        }
        return result
    }

    private fun endsWithAbbreviation(prefix: String): Boolean {
        val lastToken = prefix.trimEnd().substringAfterLast(' ').lowercase(Locale.US)
        if (lastToken in ABBREVIATIONS) return true
        // Single uppercase initial like "J." or dotted acronym like "U.S."
        if (lastToken.length == 2 && lastToken[0].isLetter() && lastToken[1] == '.') return true
        if (Regex("([a-z]\\.){2,}").matches(lastToken)) return true
        return false
    }

    private fun splitClauseToDurationLimit(text: String, out: MutableList<String>) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return

        if (estimateDurationSeconds(cleaned) <= maxTargetClauseSeconds) {
            out += cleaned
            return
        }

        // Priority 1: Semicolons, colons, em-dashes, en-dashes.
        splitAtBestDelimiter(cleaned, STRONG_PROSODY_SPLIT_REGEX)?.let { (left, right) ->
            splitClauseToDurationLimit(left, out)
            splitClauseToDurationLimit(right, out)
            return
        }

        // Priority 2: Commas balanced near the midpoint.
        splitAtBestDelimiter(cleaned, COMMA_SPLIT_REGEX)?.let { (left, right) ->
            splitClauseToDurationLimit(left, out)
            splitClauseToDurationLimit(right, out)
            return
        }

        // Priority 3: Natural conjunction boundaries near the midpoint.
        splitAtBestConjunction(cleaned)?.let { (left, right) ->
            splitClauseToDurationLimit(left, out)
            splitClauseToDurationLimit(right, out)
            return
        }

        // Priority 4: Balanced word-boundary split guaranteeing every clause stays strictly < 14.0s.
        val words = cleaned.split(MULTI_SPACE_REGEX).filter { it.isNotEmpty() }
        if (words.size <= 1) {
            out += cleaned
            return
        }
        val mid = (words.size / 2).coerceIn(1, words.size - 1)
        val left = words.subList(0, mid).joinToString(" ")
        val right = words.subList(mid, words.size).joinToString(" ")
        splitClauseToDurationLimit(left, out)
        splitClauseToDurationLimit(right, out)
    }

    private fun splitAtBestDelimiter(text: String, delimiterRegex: Regex): Pair<String, String>? {
        val matches = delimiterRegex.findAll(text).toList()
        if (matches.isEmpty()) return null
        val midpoint = text.length / 2
        var best: Pair<String, String>? = null
        var bestDist = Int.MAX_VALUE
        for (match in matches) {
            val cut = match.range.last + 1
            if (cut <= 0 || cut >= text.length) continue
            val left = text.substring(0, cut).trim()
            val right = text.substring(cut).trim()
            if (wordCount(left) < 2 || wordCount(right) < 2) continue
            val dist = kotlin.math.abs(cut - midpoint)
            if (dist < bestDist) {
                bestDist = dist
                best = left to right
            }
        }
        return best
    }

    private fun splitAtBestConjunction(text: String): Pair<String, String>? {
        val words = text.split(MULTI_SPACE_REGEX).filter { it.isNotEmpty() }
        if (words.size < 6) return null
        val mid = words.size / 2
        var bestIdx = -1
        var bestDist = Int.MAX_VALUE
        for (i in 2 until words.size - 2) {
            val bare = words[i].lowercase(Locale.US).trim(',', ';', ':')
            if (bare in CONJUNCTIONS) {
                val dist = kotlin.math.abs(i - mid)
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }
        }
        if (bestIdx < 0) return null
        return words.subList(0, bestIdx).joinToString(" ") to
            words.subList(bestIdx, words.size).joinToString(" ")
    }

    private fun wordCount(text: String): Int =
        text.split(MULTI_SPACE_REGEX).count { it.isNotBlank() }

    private fun expandEnglishToken(rawToken: String): List<String> {
        val lower = rawToken.lowercase(Locale.US)

        // Known contractions
        CONTRACTIONS[lower]?.let { return it }

        // Known broadcast units & abbreviations (with optional trailing dot)
        UNIT_EXPANSIONS[lower]?.let { return it }
        UNIT_EXPANSIONS[lower.trimEnd('.')]?.let { return it }

        // Currency: $1,250.50 or $42
        if (rawToken.startsWith("$") && rawToken.length > 1) {
            val numeric = rawToken.substring(1).replace(",", "")
            return expandCurrency(numeric)
        }

        // Percentage: 42% or 44.1%
        if (rawToken.endsWith("%") && rawToken.length > 1) {
            val numeric = rawToken.dropLast(1).replace(",", "")
            val expanded = expandNumberOrDecimal(numeric)
            if (expanded != null) return expanded + "percent"
        }

        // Ordinals: 1st, 2nd, 3rd, 4th, 21st
        ORDINAL_REGEX.matchEntire(lower)?.let { m ->
            val n = m.groupValues[1].toLongOrNull()
            if (n != null && n in 0L..999_999L) {
                return expandOrdinal(n)
            }
        }

        // Plain signed/unsigned integer or decimal: -16, 44.1, 1,024
        val strippedCommas = rawToken.replace(",", "")
        expandNumberOrDecimal(strippedCommas)?.let { return it }

        // Hyphenated compound tokens (e.g. "real-time", "s2-pro", "44.1kHz")
        if ('-' in rawToken && rawToken.length > 1) {
            val parts = rawToken.split('-').filter { it.isNotEmpty() }
            if (parts.size > 1) {
                return parts.flatMap { expandEnglishToken(it) }
            }
        }

        // Number + unit suffix (e.g. "12ms", "5ms", "44kHz", "35MB")
        NUM_UNIT_REGEX.matchEntire(rawToken)?.let { m ->
            val num = expandNumberOrDecimal(m.groupValues[1])
            val unitKey = m.groupValues[2].lowercase(Locale.US)
            val unit = UNIT_EXPANSIONS[unitKey]
            if (num != null && unit != null) {
                return num + unit
            }
        }

        // Unknown ALL-CAPS acronyms (2..5 letters, e.g., API, GPU, PCM, GGUF, DAC, OTA)
        if (rawToken.length in 2..5 && rawToken.all { it in 'A'..'Z' }) {
            return rawToken.map { LETTER_NAMES[it] ?: it.lowercase() }
        }

        // Alphanumeric code token like "s2" or "v14"
        ALPHA_NUM_REGEX.matchEntire(rawToken)?.let { m ->
            val letters = m.groupValues[1]
            val num = expandNumberOrDecimal(m.groupValues[2])
            if (num != null) {
                val prefix = if (letters.length == 1) {
                    listOf(LETTER_NAMES[letters[0].uppercaseChar()] ?: letters.lowercase(Locale.US))
                } else {
                    listOf(letters.lowercase(Locale.US))
                }
                return prefix + num
            }
        }

        return listOf(rawToken.lowercase(Locale.US).trim('\'', '"'))
    }

    private fun expandCurrency(numeric: String): List<String> {
        val parts = numeric.split('.')
        val dollars = parts[0].toLongOrNull() ?: return listOf("dollars")
        val out = ArrayList<String>()
        out += numberToWords(dollars)
        out += if (dollars == 1L) "dollar" else "dollars"
        if (parts.size == 2 && parts[1].isNotEmpty()) {
            val centsStr = parts[1].padEnd(2, '0').take(2)
            val cents = centsStr.toLongOrNull() ?: 0L
            if (cents > 0L) {
                out += "and"
                out += numberToWords(cents)
                out += if (cents == 1L) "cent" else "cents"
            }
        }
        return out
    }

    private fun expandNumberOrDecimal(token: String): List<String>? {
        val negative = token.startsWith("-")
        val body = if (negative) token.substring(1) else token
        if (body.isEmpty()) return null
        if (!NUMBER_REGEX.matches(body)) return null

        val out = ArrayList<String>()
        if (negative) out += "minus"

        val dotIdx = body.indexOf('.')
        if (dotIdx < 0) {
            val n = body.toLongOrNull() ?: return null
            if (n > 999_999_999L) return null
            out += numberToWords(n)
        } else {
            val intPart = body.substring(0, dotIdx).toLongOrNull() ?: 0L
            val fracPart = body.substring(dotIdx + 1)
            if (intPart > 999_999_999L) return null
            out += numberToWords(intPart)
            out += "point"
            for (digit in fracPart) {
                out += ONES[digit - '0']
            }
        }
        return out
    }

    private fun expandOrdinal(n: Long): List<String> {
        val words = numberToWords(n).toMutableList()
        val last = words.removeAt(words.lastIndex)
        val ordinalLast = ORDINAL_MAP[last] ?: when {
            last.endsWith("y") -> last.dropLast(1) + "ieth"
            last.endsWith("t") -> last + "h"
            last.endsWith("e") -> last.dropLast(1) + "th"
            else -> last + "th"
        }
        words += ordinalLast
        return words
    }

    private fun numberToWords(n: Long): List<String> {
        if (n == 0L) return listOf("zero")
        val out = ArrayList<String>()
        var rem = n
        if (rem >= 1_000_000L) {
            out += numberUnderThousand((rem / 1_000_000L).toInt())
            out += "million"
            rem %= 1_000_000L
        }
        if (rem >= 1_000L) {
            out += numberUnderThousand((rem / 1_000L).toInt())
            out += "thousand"
            rem %= 1_000L
        }
        if (rem > 0L) {
            out += numberUnderThousand(rem.toInt())
        }
        return out
    }

    private fun numberUnderThousand(n: Int): List<String> {
        val out = ArrayList<String>()
        val hundreds = n / 100
        val tail = n % 100
        if (hundreds > 0) {
            out += ONES[hundreds]
            out += "hundred"
        }
        when {
            tail >= 20 -> {
                out += TENS[tail / 10]
                if (tail % 10 > 0) out += ONES[tail % 10]
            }
            tail >= 10 -> out += TEENS[tail - 10]
            tail > 0 -> out += ONES[tail]
        }
        return out
    }

    private fun countWordSyllables(word: String): Int {
        val letters = word.lowercase(Locale.US).filter { it in 'a'..'z' }
        if (letters.isEmpty()) return 0
        if (letters.length <= 3) return 1

        var groups = 0
        var prevVowel = false
        for (ch in letters) {
            val isVowel = ch in "aeiouy"
            if (isVowel && !prevVowel) {
                groups++
            }
            prevVowel = isVowel
        }
        if (letters.endsWith("e") && !letters.endsWith("le") && groups > 1) {
            groups--
        }
        if (letters.endsWith("ed") &&
            letters.length > 3 &&
            letters[letters.length - 3] !in "td" &&
            groups > 1
        ) {
            groups--
        }
        return groups.coerceAtLeast(1)
    }

    /**
     * Deterministic fallback English grapheme-to-phoneme IPA mapper for out-of-lexicon words.
     */
    internal fun graphemeToIpa(rawWord: String): String {
        val word = rawWord.lowercase(Locale.US).filter { it in 'a'..'z' }
        if (word.isEmpty()) return ""
        COMMON_ENGLISH_IPA[word]?.let { return it }

        val sb = StringBuilder(word.length * 2)
        val hasSilentFinalE = word.length >= 4 &&
            word.endsWith("e") &&
            word[word.length - 2] !in "aeiouy" &&
            word[word.length - 3] in "aeiou"

        var i = 0
        val end = if (hasSilentFinalE) word.length - 1 else word.length
        while (i < end) {
            // 4-char suffixes
            if (i + 4 <= end) {
                when (word.substring(i, i + 4)) {
                    "tion", "sion" -> { sb.append("ʃən"); i += 4; continue }
                    "ough" -> { sb.append("oʊ"); i += 4; continue }
                    "eigh" -> { sb.append("eɪ"); i += 4; continue }
                }
            }
            // 3-char clusters
            if (i + 3 <= end) {
                when (word.substring(i, i + 3)) {
                    "tch" -> { sb.append("tʃ"); i += 3; continue }
                    "igh" -> { sb.append("aɪ"); i += 3; continue }
                    "dge" -> { sb.append("dʒ"); i += 3; continue }
                    "ing" -> { sb.append("ɪŋ"); i += 3; continue }
                    "air" -> { sb.append("ɛər"); i += 3; continue }
                    "ear" -> { sb.append("ɪər"); i += 3; continue }
                }
            }
            // 2-char digraphs
            if (i + 2 <= end) {
                val di = word.substring(i, i + 2)
                val mapped = DIGRAPH_IPA[di]
                if (mapped != null) {
                    sb.append(mapped)
                    i += 2
                    continue
                }
            }

            // Single letter with silent-e vowel lengthening
            val ch = word[i]
            if (hasSilentFinalE && i == word.length - 3) {
                sb.append(
                    when (ch) {
                        'a' -> "eɪ"
                        'e' -> "iː"
                        'i' -> "aɪ"
                        'o' -> "oʊ"
                        'u' -> "juː"
                        else -> SINGLE_CHAR_IPA[ch] ?: ""
                    }
                )
            } else if (ch == 'c' && i + 1 < end && word[i + 1] in "eiy") {
                sb.append("s")
            } else if (ch == 'g' && i + 1 < end && word[i + 1] in "eiy") {
                sb.append("dʒ")
            } else if (ch == 'y' && i == end - 1 && word.length > 2) {
                sb.append("i")
            } else {
                sb.append(SINGLE_CHAR_IPA[ch] ?: "")
            }
            i++
        }
        return sb.toString()
    }

    private fun isWordLike(token: String): Boolean =
        token.any { it.isLetter() }

    companion object {
        const val CONSERVATIVE_SYLLABLES_PER_SEC: Double = 4.2
        const val TARGET_MAX_CLAUSE_SEC: Double = 13.0

        private const val COMMA_PAUSE_SEC: Double = 0.16
        private const val SEMICOLON_PAUSE_SEC: Double = 0.24
        private const val SENTENCE_END_PAUSE_SEC: Double = 0.30
        private const val PROSODY_PUNCTUATION: String = ",.;:!?—–"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val TOKEN_REGEX = Regex(
            """(?<![A-Za-z0-9])-?\$?\d[\d,]*(?:\.\d+)?%?(?:st|nd|rd|th)?(?![A-Za-z])|-?\d+(?:\.\d+)?[A-Za-z]+|[A-Za-z][A-Za-z0-9]*(?:['’-][A-Za-z0-9]+)*|[,.;:!?—–]"""
        )
        private val NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")
        private val ORDINAL_REGEX = Regex("""(\d+)(st|nd|rd|th)""")
        private val NUM_UNIT_REGEX = Regex("""(-?\d+(?:\.\d+)?)([A-Za-z]+)""")
        private val ALPHA_NUM_REGEX = Regex("""([A-Za-z]{1,4})(\d+)""")
        private val STRONG_PROSODY_SPLIT_REGEX = Regex("""[;:—–]|\s-\s""")
        private val COMMA_SPLIT_REGEX = Regex(""",""")

        fun formatClauseId(oneBasedIndex: Int): String =
            String.format(Locale.US, "c_%03d", oneBasedIndex)

        private val ABBREVIATIONS = setOf(
            "dr.", "mr.", "mrs.", "ms.", "prof.", "sr.", "jr.", "st.", "vs.",
            "etc.", "e.g.", "i.e.", "no.", "fig.", "vol.", "inc.", "ltd.", "corp."
        )

        private val CONJUNCTIONS = setOf(
            "and", "but", "or", "so", "yet", "because", "while", "which",
            "that", "when", "although", "however", "whereas", "unless", "until"
        )

        private val CONTRACTIONS = mapOf(
            "can't" to listOf("cannot"),
            "won't" to listOf("will", "not"),
            "don't" to listOf("do", "not"),
            "doesn't" to listOf("does", "not"),
            "didn't" to listOf("did", "not"),
            "isn't" to listOf("is", "not"),
            "aren't" to listOf("are", "not"),
            "wasn't" to listOf("was", "not"),
            "weren't" to listOf("were", "not"),
            "haven't" to listOf("have", "not"),
            "hasn't" to listOf("has", "not"),
            "hadn't" to listOf("had", "not"),
            "wouldn't" to listOf("would", "not"),
            "couldn't" to listOf("could", "not"),
            "shouldn't" to listOf("should", "not"),
            "i'm" to listOf("i", "am"),
            "you're" to listOf("you", "are"),
            "we're" to listOf("we", "are"),
            "they're" to listOf("they", "are"),
            "it's" to listOf("it", "is"),
            "that's" to listOf("that", "is"),
            "there's" to listOf("there", "is"),
            "what's" to listOf("what", "is"),
            "who's" to listOf("who", "is"),
            "let's" to listOf("let", "us"),
            "i've" to listOf("i", "have"),
            "we've" to listOf("we", "have"),
            "you've" to listOf("you", "have"),
            "they've" to listOf("they", "have"),
            "i'll" to listOf("i", "will"),
            "we'll" to listOf("we", "will"),
            "you'll" to listOf("you", "will"),
            "they'll" to listOf("they", "will")
        )

        private val UNIT_EXPANSIONS = mapOf(
            "dr" to listOf("doctor"),
            "mr" to listOf("mister"),
            "mrs" to listOf("missus"),
            "ms" to listOf("miz"),
            "prof" to listOf("professor"),
            "st" to listOf("street"),
            "vs" to listOf("versus"),
            "etc" to listOf("et", "cetera"),
            "khz" to listOf("kilohertz"),
            "mhz" to listOf("megahertz"),
            "ghz" to listOf("gigahertz"),
            "hz" to listOf("hertz"),
            "ms" to listOf("milliseconds"),
            "db" to listOf("decibels"),
            "dbfs" to listOf("decibels", "full", "scale"),
            "lufs" to listOf("luffs"),
            "mb" to listOf("megabytes"),
            "gb" to listOf("gigabytes"),
            "kb" to listOf("kilobytes")
        )

        private val ONES = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"
        )
        private val TEENS = arrayOf(
            "ten", "eleven", "twelve", "thirteen", "fourteen",
            "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
        )
        private val TENS = arrayOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
        )
        private val ORDINAL_MAP = mapOf(
            "one" to "first",
            "two" to "second",
            "three" to "third",
            "five" to "fifth",
            "eight" to "eighth",
            "nine" to "ninth",
            "twelve" to "twelfth"
        )

        private val LETTER_NAMES = mapOf(
            'A' to "ay", 'B' to "bee", 'C' to "see", 'D' to "dee", 'E' to "ee",
            'F' to "eff", 'G' to "jee", 'H' to "aitch", 'I' to "eye", 'J' to "jay",
            'K' to "kay", 'L' to "ell", 'M' to "em", 'N' to "en", 'O' to "oh",
            'P' to "pee", 'Q' to "cue", 'R' to "ar", 'S' to "ess", 'T' to "tee",
            'U' to "you", 'V' to "vee", 'W' to "doubleyou", 'X' to "ex", 'Y' to "why", 'Z' to "zee"
        )

        private val COMMON_ENGLISH_IPA = mapOf(
            "the" to "ðə", "a" to "ə", "an" to "æn", "and" to "ænd", "to" to "tuː",
            "of" to "ʌv", "in" to "ɪn", "is" to "ɪz", "it" to "ɪt", "that" to "ðæt",
            "for" to "fɔːr", "on" to "ɑːn", "with" to "wɪð", "as" to "æz", "are" to "ɑːr",
            "be" to "biː", "this" to "ðɪs", "from" to "frʌm", "or" to "ɔːr", "one" to "wʌn",
            "two" to "tuː", "three" to "θriː", "four" to "fɔːr", "five" to "faɪv",
            "six" to "sɪks", "seven" to "ˈsɛvən", "eight" to "eɪt", "nine" to "naɪn",
            "ten" to "tɛn", "zero" to "ˈzɪəroʊ", "point" to "pɔɪnt", "minus" to "ˈmaɪnəs",
            "percent" to "pərˈsɛnt", "you" to "juː", "we" to "wiː", "they" to "ðeɪ",
            "i" to "aɪ", "not" to "nɑːt", "have" to "hæv", "will" to "wɪl",
            "audio" to "ˈɔːdioʊ", "voice" to "vɔɪs", "studio" to "ˈstuːdioʊ",
            "broadcast" to "ˈbrɔːdkæst", "hello" to "həˈloʊ", "world" to "wɜːrld",
            "ay" to "eɪ", "bee" to "biː", "see" to "siː", "dee" to "diː", "ee" to "iː",
            "eff" to "ɛf", "jee" to "dʒiː", "aitch" to "eɪtʃ", "eye" to "aɪ", "jay" to "dʒeɪ",
            "kay" to "keɪ", "ell" to "ɛl", "em" to "ɛm", "en" to "ɛn", "oh" to "oʊ",
            "pee" to "piː", "cue" to "kjuː", "ar" to "ɑːr", "ess" to "ɛs", "tee" to "tiː",
            "vee" to "viː", "doubleyou" to "ˈdʌbəljuː", "ex" to "ɛks", "why" to "waɪ", "zee" to "ziː"
        )

        private val DIGRAPH_IPA = mapOf(
            "ch" to "tʃ", "sh" to "ʃ", "th" to "θ", "ph" to "f", "wh" to "w",
            "ck" to "k", "ng" to "ŋ", "qu" to "kw", "kn" to "n", "wr" to "r",
            "ee" to "iː", "ea" to "iː", "oo" to "uː", "ai" to "eɪ", "ay" to "eɪ",
            "oi" to "ɔɪ", "oy" to "ɔɪ", "ou" to "aʊ", "ow" to "aʊ", "au" to "ɔː",
            "aw" to "ɔː", "oa" to "oʊ", "ie" to "iː", "ar" to "ɑːr", "er" to "ɜːr",
            "ir" to "ɜːr", "ur" to "ɜːr", "or" to "ɔːr"
        )

        private val SINGLE_CHAR_IPA = mapOf(
            'a' to "æ", 'b' to "b", 'c' to "k", 'd' to "d", 'e' to "ɛ",
            'f' to "f", 'g' to "ɡ", 'h' to "h", 'i' to "ɪ", 'j' to "dʒ",
            'k' to "k", 'l' to "l", 'm' to "m", 'n' to "n", 'o' to "ɑː",
            'p' to "p", 'q' to "k", 'r' to "r", 's' to "s", 't' to "t",
            'u' to "ʌ", 'v' to "v", 'w' to "w", 'x' to "ks", 'y' to "j", 'z' to "z"
        )
    }
}
