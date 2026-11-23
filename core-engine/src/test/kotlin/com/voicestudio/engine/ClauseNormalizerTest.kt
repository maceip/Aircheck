package com.voicestudio.engine

import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.DEFAULT_BASE_GAP_SAMPLES
import com.voicestudio.edl.MAX_CLAUSE_DURATION_SEC
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClauseNormalizerTest {

    private val normalizer = ClauseNormalizer()

    @Test
    fun `splits multi-sentence script and chains acoustic prefix tail paths`() {
        val draft = """
            Welcome back to Aircheck Studio.
            Today we are benchmarking local synthesis at 44.1 kHz with zero cloud round trips!
            Can every single clause stay strictly under fourteen seconds?
        """.trimIndent()

        val clauses = normalizer.normalizeAndSplit(
            projectId = "proj_001",
            rawDraft = draft,
            lexiconFile = null
        )

        assertEquals(3, clauses.size)
        assertEquals("c_001", clauses[0].clauseId)
        assertEquals("c_002", clauses[1].clauseId)
        assertEquals("c_003", clauses[2].clauseId)
        assertEquals(listOf(0, 1, 2), clauses.map { it.index })

        assertNull(clauses[0].acousticPrefixPcmPath)
        assertEquals("takes/c_001_winner_tail_1500ms.pcm", clauses[1].acousticPrefixPcmPath)
        assertEquals("takes/c_002_winner_tail_1500ms.pcm", clauses[2].acousticPrefixPcmPath)

        for (clause in clauses) {
            val est = normalizer.estimateDurationSeconds(clause.rawText)
            assertTrue("Clause ${clause.clauseId} exceeded 14s ($est s)", est < MAX_CLAUSE_DURATION_SEC)
            assertEquals(DEFAULT_BASE_GAP_SAMPLES, clause.baseGapAfterSamples)
            assertTrue(clause.normalizedPhonemes.isNotBlank())
        }
    }

    @Test
    fun `splits long prosodic sentences and punctuation-free run-ons strictly below 14 seconds`() {
        val longRunOn = buildString {
            repeat(90) { i ->
                if (i > 0) append(' ')
                append(if (i % 12 == 0) "broadcasting" else if (i % 5 == 0) "precision" else "audio")
            }
        }
        val longPunctuated =
            "First we normalize every broadcast script with deterministic phonetic overrides, " +
                "then we synthesize four discrete audio codebook rollouts through our rank thirty two adapter; " +
                "next our verifier filters dropped words and speaker drift, " +
                "and finally our five millisecond neon mastering stage locks integrated loudness to minus sixteen luffs, " +
                "while keeping every single cut aligned to sample accurate zero crossings across the entire timeline."

        for (script in listOf(longRunOn, longPunctuated)) {
            val clauses = normalizer.normalizeAndSplit(
                projectId = "proj_long",
                rawDraft = script,
                lexiconFile = null
            )
            assertTrue("Long script must split into multiple clauses", clauses.size >= 2)
            clauses.forEachIndexed { idx, clause ->
                val est = normalizer.estimateDurationSeconds(clause.rawText)
                assertTrue(
                    "Clause ${clause.clauseId} estimated $est s >= $MAX_CLAUSE_DURATION_SEC s: ${clause.rawText}",
                    est < MAX_CLAUSE_DURATION_SEC
                )
                assertEquals(ClauseNormalizer.formatClauseId(idx + 1), clause.clauseId)
                if (idx == 0) {
                    assertNull(clause.acousticPrefixPcmPath)
                } else {
                    assertEquals(
                        "takes/${ClauseNormalizer.formatClauseId(idx)}_winner_tail_1500ms.pcm",
                        clause.acousticPrefixPcmPath
                    )
                }
            }
        }
    }

    @Test
    fun `applies OTA lexicon json and inline phonetic overrides deterministically`() {
        val tmpDir = Files.createTempDirectory("voicestudio_lexicon_test").toFile()
        try {
            val flatLexicon = File(tmpDir, "lexicon_flat.json").apply {
                writeText(
                    """
                    {
                      "Aircheck": "ˈɛərˌtʃɛk",
                      "LUFS": "lʌfs",
                      "s2-pro": "ɛs tuː proʊ"
                    }
                    """.trimIndent()
                )
            }
            val nestedLexicon = File(tmpDir, "lexicon_nested.json").apply {
                writeText(
                    """
                    {
                      "schema_version": 1,
                      "entries": {
                        "Aircheck": "ˈɛərˌtʃɛk",
                        "LUFS": "lʌfs"
                      }
                    }
                    """.trimIndent()
                )
            }

            val draft = "Aircheck masters s2-pro at -16 LUFS."
            val run1 = normalizer.normalizeAndSplit("p1", draft, flatLexicon)
            val run2 = normalizer.normalizeAndSplit("p1", draft, flatLexicon)
            assertEquals("G2P output must be 100% deterministic", run1, run2)

            val ipa = run1.single().normalizedPhonemes
            assertTrue("Must apply lexicon IPA for Aircheck: $ipa", ipa.contains("ˈɛərˌtʃɛk"))
            assertTrue("Must apply lexicon IPA for s2-pro: $ipa", ipa.contains("ɛs tuː proʊ"))
            assertTrue("Must apply lexicon IPA for LUFS: $ipa", ipa.contains("lʌfs"))
            assertTrue("Must expand negative integer -16: $ipa", ipa.contains("ˈmaɪnəs"))

            val nestedRun = normalizer.normalizeAndSplit("p1", "Aircheck hits -16 LUFS.", nestedLexicon)
            assertTrue(nestedRun.single().normalizedPhonemes.contains("ˈɛərˌtʃɛk"))

            // Per-call inline WordPill phonetic override takes precedence over lexicon.json.
            val overridden = normalizer.normalizeAndSplit(
                projectId = "p1",
                rawDraft = "Aircheck hits -16 LUFS.",
                lexiconFile = flatLexicon,
                phoneticOverrides = mapOf("Aircheck" to "ˈeərˌtʃɛk_CUSTOM")
            )
            assertTrue(overridden.single().normalizedPhonemes.contains("ˈeərˌtʃɛk_CUSTOM"))
            assertFalse(overridden.single().normalizedPhonemes.contains("ˈɛərˌtʃɛk "))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `does not split on decimals or abbreviations and expands English constructs`() {
        val draft = "Dr. Smith paid $1,250.50 for 44.1 kHz GPU audio and we don't drop 1st place at 40%."
        val clauses = normalizer.normalizeAndSplit("p2", draft, null)

        assertEquals("Abbreviations and decimals must stay inside one sentence", 1, clauses.size)
        val ipa = clauses.single().normalizedPhonemes
        assertTrue(ipa.isNotBlank())
        // Verify deterministic expansion of 44.1 ("point"), percentage ("percent"), and GPU acronym.
        assertTrue("Expected decimal point IPA in: $ipa", ipa.contains("pɔɪnt"))
        assertTrue("Expected percent IPA in: $ipa", ipa.contains("pərˈsɛnt"))
        assertTrue("Expected GPU letter-spelled IPA in: $ipa", ipa.contains("dʒiː piː juː"))

        // Verify kotlinx.serialization round-trip against canonical ClauseNode schema.
        val encoded = Json.encodeToString(clauses.single())
        val decoded = Json.decodeFromString<ClauseNode>(encoded)
        assertEquals(clauses.single(), decoded)
    }
}
