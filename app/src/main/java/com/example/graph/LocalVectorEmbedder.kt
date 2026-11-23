package com.example.graph

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic 128-dimensional on-device text embedder combining word unigrams,
 * lightweight morphological stems, content bigrams, and character 3-5-grams via
 * signed feature hashing with sublinear TF weighting and L2 normalization.
 *
 * Runs purely locally in <1ms with zero external API calls.
 */
object LocalVectorEmbedder {

    const val VECTOR_DIM: Int = 128

    private val STOPWORDS: Set<String> = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "being", "but", "by",
        "can", "could", "did", "do", "does", "doing", "done", "for", "from", "get",
        "got", "had", "has", "have", "having", "he", "her", "here", "hers", "him",
        "his", "how", "i", "if", "in", "into", "is", "it", "its", "just", "me",
        "more", "most", "my", "no", "not", "now", "of", "on", "one", "only", "or",
        "other", "our", "out", "over", "re", "s", "same", "she", "should", "so",
        "some", "such", "t", "than", "that", "the", "their", "them", "then", "there",
        "these", "they", "this", "those", "through", "to", "too", "under", "up",
        "us", "very", "was", "we", "were", "what", "when", "where", "which", "while",
        "who", "why", "will", "with", "would", "you", "your", "yours", "rt", "http",
        "https", "tco", "amp"
    )

    private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val TOKEN_REGEX = Regex("[a-z0-9]+")

    fun tokenizeWords(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val cleaned = URL_REGEX.replace(text, " ").lowercase()
        val tokens = ArrayList<String>(24)
        for (match in TOKEN_REGEX.findAll(cleaned)) {
            val token = match.value
            if (token.length >= 2 || token == "c" || token == "r" || token == "x") {
                tokens.add(token)
            }
        }
        return tokens
    }

    fun tokenizeContentWords(text: String): List<String> {
        return tokenizeWords(text).filter { token ->
            token !in STOPWORDS && !token.all { it.isDigit() }
        }
    }

    internal fun lightStem(token: String): String {
        if (token.length <= 4) return token
        return when {
            token.endsWith("ation") && token.length > 7 -> token.dropLast(5)
            token.endsWith("ition") && token.length > 7 -> token.dropLast(5)
            token.endsWith("ment") && token.length > 6 -> token.dropLast(4)
            token.endsWith("ness") && token.length > 6 -> token.dropLast(4)
            token.endsWith("ing") && token.length > 5 -> token.dropLast(3)
            token.endsWith("ies") && token.length > 5 -> token.dropLast(3) + "y"
            token.endsWith("ed") && token.length > 4 -> token.dropLast(2)
            token.endsWith("es") && token.length > 4 -> token.dropLast(2)
            token.endsWith("er") && token.length > 5 -> token.dropLast(2)
            token.endsWith("ly") && token.length > 5 -> token.dropLast(2)
            token.endsWith("s") && !token.endsWith("ss") && token.length > 4 -> token.dropLast(1)
            else -> token
        }
    }

    fun embed(text: String): FloatArray {
        val vector = FloatArray(VECTOR_DIM)
        val allTokens = tokenizeWords(text)
        if (allTokens.isEmpty()) return vector

        val unigramCounts = HashMap<String, Int>(allTokens.size)
        val stemCounts = HashMap<String, Int>(allTokens.size)
        val contentTokens = ArrayList<String>(allTokens.size)

        for (token in allTokens) {
            unigramCounts[token] = (unigramCounts[token] ?: 0) + 1
            if (token !in STOPWORDS) {
                contentTokens.add(token)
                val stemmed = lightStem(token)
                stemCounts[stemmed] = (stemCounts[stemmed] ?: 0) + 1
            }
        }

        // 1. Word unigrams (sublinear TF, stopwords heavily downweighted)
        for ((token, count) in unigramCounts) {
            val tf = 1.0f + ln(count.toDouble()).toFloat()
            val baseWeight = if (token in STOPWORDS) 0.12f else 2.10f
            accumulateFeature(vector, "u:", token, tf * baseWeight)
        }

        // 2. Lightweight stems for every content token (equal word energy + morphological recall)
        for ((stem, count) in stemCounts) {
            val tf = 1.0f + ln(count.toDouble()).toFloat()
            accumulateFeature(vector, "s:", stem, tf * 1.90f)
        }

        // 3. Content word bigrams
        if (contentTokens.size >= 2) {
            val bigramCounts = HashMap<String, Int>(contentTokens.size)
            for (i in 0 until contentTokens.size - 1) {
                val bigram = "${contentTokens[i]}_${contentTokens[i + 1]}"
                bigramCounts[bigram] = (bigramCounts[bigram] ?: 0) + 1
            }
            for ((bigram, count) in bigramCounts) {
                val tf = 1.0f + ln(count.toDouble()).toFloat()
                accumulateFeature(vector, "b:", bigram, tf * 1.80f)
            }
        }

        // 4. Character 3-5-grams on content tokens (normalized per token to prevent hash-collision dilution)
        val ngramSource = if (contentTokens.isNotEmpty()) contentTokens else allTokens
        val charNgramWeights = HashMap<String, Float>(ngramSource.size * 6)
        for (token in ngramSource) {
            val padded = "^$token$"
            val maxN = min(5, padded.length)
            var tokenGrams = 0
            for (n in 3..maxN) {
                tokenGrams += (padded.length - n + 1).coerceAtLeast(0)
            }
            if (tokenGrams <= 0) continue
            val invSqrtGrams = (1.0 / sqrt(tokenGrams.toDouble())).toFloat()
            for (n in 3..maxN) {
                val lengthWeight = when (n) {
                    3 -> 0.55f
                    4 -> 0.72f
                    else -> 0.88f
                }
                for (start in 0..(padded.length - n)) {
                    val gram = padded.substring(start, start + n)
                    charNgramWeights[gram] = (charNgramWeights[gram] ?: 0f) + (lengthWeight * invSqrtGrams)
                }
            }
        }
        for ((gram, weight) in charNgramWeights) {
            accumulateFeature(vector, "c:", gram, weight)
        }

        // L2 normalize
        var sumSq = 0.0
        for (i in 0 until VECTOR_DIM) {
            val v = vector[i].toDouble()
            sumSq += v * v
        }
        val norm = sqrt(sumSq)
        if (norm > 1e-12) {
            val invNorm = (1.0 / norm).toFloat()
            for (i in 0 until VECTOR_DIM) {
                vector[i] *= invNorm
            }
        }
        return vector
    }

    fun toByteArray(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (value in vector) {
            buffer.putFloat(value)
        }
        return buffer.array()
    }

    fun fromByteArray(bytes: ByteArray?): FloatArray {
        if (bytes == null || bytes.size < Float.SIZE_BYTES) {
            return FloatArray(VECTOR_DIM)
        }
        val count = bytes.size / Float.SIZE_BYTES
        val buffer = ByteBuffer.wrap(bytes, 0, count * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buffer.float }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        val len = min(a.size, b.size)
        if (len == 0) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until len) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        if (normA <= 1e-12 || normB <= 1e-12) return 0.0
        return (dot / (sqrt(normA) * sqrt(normB))).coerceIn(-1.0, 1.0)
    }

    fun cosineSimilarity(textA: String, textB: String): Double {
        return cosineSimilarity(embed(textA), embed(textB))
    }

    private fun accumulateFeature(vector: FloatArray, namespace: String, feature: String, weight: Float) {
        val mixed = hash64(namespace, feature)
        val bucket = ((mixed ushr 32).toInt() and (VECTOR_DIM - 1))
        val sign = if (((mixed ushr 63) and 1L) == 0L) 1.0f else -1.0f
        vector[bucket] += sign * weight
    }

    /**
     * Deterministic 64-bit FNV-1a followed by SplitMix64 avalanche finalizer.
     */
    private fun hash64(prefix: String, value: String): Long {
        var h = -3750763034362895579L // 0xcbf29ce484222325
        val prime = 1099511628211L    // 0x100000001b3
        for (i in 0 until prefix.length) {
            h = (h xor prefix[i].code.toLong()) * prime
        }
        for (i in 0 until value.length) {
            h = (h xor value[i].code.toLong()) * prime
        }
        // SplitMix64 finalizer for uniform bucket + sign independence
        h = (h xor (h ushr 30)) * -4658895280553007687L
        h = (h xor (h ushr 27)) * -7723592293110705685L
        return h xor (h ushr 31)
    }
}
