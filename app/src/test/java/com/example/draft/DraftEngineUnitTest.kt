package com.example.draft

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import kotlin.math.PI
import kotlin.math.sin

class DraftEngineUnitTest {

    // -------------------------------------------------------------------------
    // 1. Domain Schema & Model Helpers
    // -------------------------------------------------------------------------

    @Test
    fun stageEnumAndClauseNodeHelpersMatchSpec() {
        assertEquals("01", Stage.SEED.code)
        assertEquals("SEED", Stage.SEED.label)
        assertEquals("02", Stage.PICK.code)
        assertEquals("03", Stage.CUT.code)
        assertEquals("04", Stage.SHIP.code)

        val v0 = ClauseVersion(
            tokens = listOf(WordToken("Cold", " "), WordToken("caches", " "), WordToken("hurt.", "")),
            source = EditSource.MODEL_INIT
        )
        val v1 = ClauseVersion(
            tokens = listOf(
                WordToken("Cold", " ", verbatimLocked = true),
                WordToken("caches", " ", verbatimLocked = true),
                WordToken("double", " "),
                WordToken("latency.", "")
            ),
            source = EditSource.BRUSH_SWAP
        )
        val node = ClauseNode(id = "c_0", versions = listOf(v0, v1), activeVersionIndex = 1)

        assertEquals(v1, node.activeVersion)
        assertEquals("Cold caches double latency.", node.plainText())
        assertTrue(node.hasAnyLockedSpan())
        assertTrue(node.canStepBackward)
        assertFalse(node.canStepForward)

        val steppedBack = node.copy(activeVersionIndex = 0)
        assertEquals("Cold caches hurt.", steppedBack.plainText())
        assertFalse(steppedBack.hasAnyLockedSpan())
        assertFalse(steppedBack.canStepBackward)
        assertTrue(steppedBack.canStepForward)

        val quote = XAttachment.QuotePost("@peer", "123", "https://x.com/peer/status/123", "snippet")
        val doc = DraftDocument(id = "doc_1", attachment = quote)
        assertEquals(Stage.SEED, doc.stage)
        assertTrue(doc.attachment is XAttachment.QuotePost)
    }

    // -------------------------------------------------------------------------
    // 2. Tokenization, Plain Text, and Marked Text Rendering
    // -------------------------------------------------------------------------

    @Test
    fun tokenizeIntoClausesSplitsSentencesAndCompoundClausesWithoutBreakingListsOrDecimals() {
        val text = "Version 3.5 shipped e.g. with fast, cheap, and reliable kernels. " +
            "Cold caches double latency, and retries multiply tail cost."
        val clauses = DraftStateReducer.tokenizeIntoClauses(
            text = text,
            source = EditSource.MODEL_INIT,
            lockedSubstrings = listOf("Cold caches", "tail cost")
        )

        assertEquals(3, clauses.size)
        assertEquals("Version 3.5 shipped e.g. with fast, cheap, and reliable kernels.", clauses[0].plainText())
        assertEquals("Cold caches double latency,", clauses[1].plainText())
        assertEquals("and retries multiply tail cost.", clauses[2].plainText())
        assertEquals(text, DraftStateReducer.renderPlainText(clauses))

        // Verify locked tagging on exact substrings
        assertFalse(clauses[0].hasAnyLockedSpan())
        assertTrue(clauses[1].activeVersion.tokens[0].verbatimLocked) // Cold
        assertTrue(clauses[1].activeVersion.tokens[1].verbatimLocked) // caches
        assertFalse(clauses[1].activeVersion.tokens[2].verbatimLocked) // double
        assertTrue(clauses[2].activeVersion.tokens[3].verbatimLocked) // tail
        assertTrue(clauses[2].activeVersion.tokens[4].verbatimLocked) // cost.
    }

    @Test
    fun tokenizeDoesNotFalseLockSubstringInsideLargerWord() {
        val clauses = DraftStateReducer.tokenizeIntoClauses(
            text = "Inside the kernel, in production we measure p99.",
            lockedSubstrings = listOf("in")
        )
        val tokens = clauses.flatMap { it.activeVersion.tokens }
        val insideTok = tokens.first { it.text == "Inside" }
        val inTok = tokens.first { it.text == "in" }
        assertFalse(insideTok.verbatimLocked)
        assertTrue(inTok.verbatimLocked)
    }

    @Test
    fun renderMarkedTextWithLocksWrapsContiguousLocksAndReplaceSpan() {
        val clauses = DraftStateReducer.tokenizeIntoClauses(
            text = "Zero-copy ring buffers cut massive tail latency.",
            lockedSubstrings = listOf("Zero-copy ring buffers")
        )
        val plain = DraftStateReducer.renderPlainText(clauses)
        val massiveStart = plain.indexOf("massive")
        val massiveRange = massiveStart until (massiveStart + "massive".length)

        val markedNoReplace = DraftStateReducer.renderMarkedTextWithLocks(clauses)
        assertEquals("<lock>Zero-copy ring buffers</lock> cut massive tail latency.", markedNoReplace)

        val markedWithReplace = DraftStateReducer.renderMarkedTextWithLocks(clauses, replaceRange = massiveRange)
        assertEquals("<lock>Zero-copy ring buffers</lock> cut <replace>massive</replace> tail latency.", markedWithReplace)
    }

    // -------------------------------------------------------------------------
    // 3. Stage 2 PICK Diff Dim Mask
    // -------------------------------------------------------------------------

    @Test
    fun computeDiffDimMaskHighlightsSharedSpansAndLeavesUniqueClaimsBright() {
        val c1 = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            "Cold caches double latency across every edge region."
        )
        val c2 = DraftStateReducer.createCandidateDraft(
            "RECEIPT",
            "Cold caches double latency when retry storms hit origin."
        )
        val c3 = DraftStateReducer.createCandidateDraft(
            "CONTRARIAN",
            "Warm memory pools remove jitter completely."
        )

        val masks = DraftStateReducer.computeDiffDimMask(listOf(c1, c2, c3))
        assertEquals(3, masks.size)

        val text1 = c1.plainText()
        val text2 = c2.plainText()
        val dimmed1 = masks[0].map { text1.substring(it) }
        val dimmed2 = masks[1].map { text2.substring(it) }

        assertTrue("Expected shared phrase dimmed in c1, got $dimmed1", dimmed1.any { it.contains("Cold caches double latency") })
        assertTrue("Expected shared phrase dimmed in c2, got $dimmed2", dimmed2.any { it.contains("Cold caches double latency") })
        assertFalse("Unique claim in c1 should stay 100% opacity", dimmed1.any { it.contains("edge region") })
        assertFalse("Unique claim in c2 should stay 100% opacity", dimmed2.any { it.contains("retry storms") })
        assertTrue("Disjoint candidate c3 should have empty dim mask", masks[2].isEmpty())
    }

    // -------------------------------------------------------------------------
    // 4. Strict Lock-Parity Guard
    // -------------------------------------------------------------------------

    @Test
    fun lockParityGuardAcceptsValidMutationAndReTagsLockedSpans() {
        val original = DraftStateReducer.tokenizeIntoClauses(
            text = "Our zero-copy ring buffer runs inside a 40ms latency budget on mobile.",
            lockedSubstrings = listOf("zero-copy ring buffer", "40ms latency budget")
        )

        val validMutated = "The <lock>zero-copy ring buffer</lock> hits our <lock>40ms latency budget</lock>."
        val result = DraftStateReducer.verifyLockParity(original, validMutated, EditSource.DENSITY_SCRUB)

        assertTrue(result.preserved)
        assertTrue(result.missingPhrases.isEmpty())
        assertEquals("The zero-copy ring buffer hits our 40ms latency budget.", DraftStateReducer.renderPlainText(result.clauses))
        val lockedAfter = DraftStateReducer.extractLockedPhrases(result.clauses)
        assertEquals(listOf("zero-copy ring buffer", "40ms latency budget"), lockedAfter)
    }

    @Test
    fun lockParityGuardRejectsAlteredOrDroppedLockedPhraseDeterministically() {
        val original = DraftStateReducer.tokenizeIntoClauses(
            text = "Our zero-copy ring buffer runs inside a 40ms latency budget on mobile.",
            lockedSubstrings = listOf("zero-copy ring buffer", "40ms latency budget")
        )

        // LLM subtly altered "zero-copy ring buffer" -> "zero-copy memory buffer"
        val alteredOutput = "Our zero-copy memory buffer hits a 40ms latency budget."
        val check = DraftStateReducer.verifyLockParity(original, alteredOutput, EditSource.STRIKE_CUT)

        assertFalse(check.preserved)
        assertEquals(listOf("zero-copy ring buffer"), check.missingPhrases)
        assertEquals(original, check.clauses)

        // Direct API returns originalClauses unchanged
        val applied = DraftStateReducer.verifyAndApplyLockedMutation(
            originalClauses = original,
            mutatedText = "Completely rewritten prose dropping all locks.",
            source = EditSource.DENSITY_SCRUB
        )
        assertEquals(original, applied)
    }

    // -------------------------------------------------------------------------
    // 5. Brush Swap & Clean Cut
    // -------------------------------------------------------------------------

    @Test
    fun applyBrushSwapUpdatesClauseRingAndGlobalSnapshots() {
        val draft = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            "Slow pipelines break user trust. Fast loops win."
        )
        val plain = draft.plainText()
        val range = plain.indexOf("Slow") until (plain.indexOf("Slow") + "Slow".length)

        val swapped = DraftStateReducer.applyBrushSwap(draft, range, "bloated", EditSource.BRUSH_SWAP)
        // Auto-capitalizes at sentence start
        assertEquals("Bloated pipelines break user trust. Fast loops win.", swapped.plainText())
        assertEquals(2, swapped.clauses[0].versions.size)
        assertEquals(1, swapped.clauses[0].activeVersionIndex)
        assertEquals(1, swapped.clauses[1].versions.size)
        assertEquals(2, swapped.globalSnapshots.size)
        assertEquals(1, swapped.snapshotCursor)

        // Clean cut ("") removes brushed adjective cleanly
        val p2 = swapped.plainText()
        val userRange = p2.indexOf("user") until (p2.indexOf("user") + "user".length)
        val cut = DraftStateReducer.applyBrushSwap(swapped, userRange, "", EditSource.BRUSH_SWAP)
        assertEquals("Bloated pipelines break trust. Fast loops win.", cut.plainText())
        assertEquals(3, cut.clauses[0].versions.size)
        assertEquals(3, cut.globalSnapshots.size)
    }

    // -------------------------------------------------------------------------
    // 6. Strike-Cut Clause & Deterministic Glue Cleanup
    // -------------------------------------------------------------------------

    @Test
    fun strikeCutClauseCleansOrphanedConnectorsAndCapitalizationOutsideLocks() {
        val draft = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            "Cold caches double latency, and retries multiply tail cost."
        )
        assertEquals(2, draft.clauses.size)

        // Cut first clause -> strips leading orphaned "and" & capitalizes "Retries"
        val cutFirst = DraftStateReducer.strikeCutClause(draft, draft.clauses[0].id)
        assertEquals("Retries multiply tail cost.", cutFirst.plainText())
        assertEquals(2, cutFirst.globalSnapshots.size)

        // Cut second clause -> upgrades dangling trailing comma to period
        val cutSecond = DraftStateReducer.strikeCutClause(draft, draft.clauses[1].id)
        assertEquals("Cold caches double latency.", cutSecond.plainText())

        // Verify explicit trailing ", and" cleanup
        val trailingConnectorDraft = CandidateDraft(
            postureLabel = "DIRECT",
            clauses = listOf(
                ClauseNode(
                    id = "c_0",
                    versions = listOf(
                        ClauseVersion(
                            listOf(
                                WordToken("We", " "),
                                WordToken("ship", " "),
                                WordToken("daily,", " "),
                                WordToken("and", "")
                            ),
                            EditSource.MODEL_INIT
                        )
                    ),
                    activeVersionIndex = 0
                ),
                ClauseNode(
                    id = "c_1",
                    versions = listOf(
                        ClauseVersion(
                            listOf(WordToken("users", " "), WordToken("notice.", "")),
                            EditSource.MODEL_INIT
                        )
                    ),
                    activeVersionIndex = 0
                )
            ),
            globalSnapshots = emptyList(),
            snapshotCursor = 0
        )
        val afterCut = DraftStateReducer.strikeCutClause(trailingConnectorDraft, "c_1")
        assertEquals("We ship daily.", afterCut.plainText())

        // Verify locked leading "and" is NEVER stripped
        val lockedDraft = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            "Cold caches double latency, and retries multiply tail cost.",
            lockedSubstrings = listOf("and retries")
        )
        val cutWithLock = DraftStateReducer.strikeCutClause(lockedDraft, lockedDraft.clauses[0].id)
        assertEquals("and retries multiply tail cost.", cutWithLock.plainText())
    }

    // -------------------------------------------------------------------------
    // 7. Clause Reorder & Dangling Opener Stripping
    // -------------------------------------------------------------------------

    @Test
    fun reorderClauseStripsDanglingOpenersAtIndexZeroUnlessLocked() {
        val draft = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            "Caches warm slowly. Consequently, tail latency spikes on deploy."
        )
        assertEquals(2, draft.clauses.size)

        val reordered = DraftStateReducer.reorderClause(draft, fromIndex = 1, toIndex = 0)
        assertEquals("Tail latency spikes on deploy. Caches warm slowly.", reordered.plainText())
        assertEquals(2, reordered.globalSnapshots.size)

        // Locked opener must NEVER be stripped
        val lockedOpenerDraft = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            "Caches warm slowly. However, tail latency spikes on deploy.",
            lockedSubstrings = listOf("However,")
        )
        val reorderedLocked = DraftStateReducer.reorderClause(lockedOpenerDraft, fromIndex = 1, toIndex = 0)
        assertEquals("However, tail latency spikes on deploy. Caches warm slowly.", reorderedLocked.plainText())
    }

    // -------------------------------------------------------------------------
    // 8. Voice Directive Stripping & Verbatim Splice
    // -------------------------------------------------------------------------

    @Test
    fun stripVoiceDirectiveHandlesAllLightWrappersAndPreservesPlainProse() {
        assertEquals("cold caches", DraftStateReducer.stripVoiceDirective("say cold caches instead"))
        assertEquals("cold caches", DraftStateReducer.stripVoiceDirective("Say \"cold caches\" instead."))
        assertEquals("zero-copy buffers", DraftStateReducer.stripVoiceDirective("replace it with zero-copy buffers"))
        assertEquals("hard p99 limits", DraftStateReducer.stripVoiceDirective("change to hard p99 limits."))
        assertEquals("three short lines", DraftStateReducer.stripVoiceDirective("make it three short lines"))
        assertEquals("Cold caches double tail latency.", DraftStateReducer.stripVoiceDirective("Cold caches double tail latency."))
    }

    @Test
    fun spliceVerbatimPhraseLocksInjectedWordsInTargetClauseAndInterSentenceGap() {
        val draft = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            "Benchmarks lie. Production traffic tells the truth."
        )

        // Splice into existing target clause
        val targetId = draft.clauses[0].id
        val replacedClause = DraftStateReducer.spliceVerbatimPhrase(
            draft = draft,
            targetClauseId = targetId,
            insertGapIndex = null,
            spokenPhrase = "say synthetic evals hide tail latency. instead",
            source = EditSource.VOICE_SPLICE
        )
        assertEquals("synthetic evals hide tail latency. Production traffic tells the truth.", replacedClause.plainText())
        assertTrue(replacedClause.clauses[0].activeVersion.tokens.all { it.verbatimLocked })
        assertEquals(2, replacedClause.clauses[0].versions.size)

        // Splice into inter-sentence gap (index 1)
        val gapSpliced = DraftStateReducer.spliceVerbatimPhrase(
            draft = replacedClause,
            targetClauseId = null,
            insertGapIndex = 1,
            spokenPhrase = "p99 is the only honest metric.",
            source = EditSource.SALVAGE_SPLICE
        )
        assertEquals(3, gapSpliced.clauses.size)
        assertEquals("p99 is the only honest metric.", gapSpliced.clauses[1].plainText())
        assertTrue(gapSpliced.clauses[1].activeVersion.tokens.all { it.verbatimLocked })
        assertEquals(3, gapSpliced.globalSnapshots.size)
    }

    // -------------------------------------------------------------------------
    // 9. Raw Keyboard Edit Auto-Locking Diff
    // -------------------------------------------------------------------------

    @Test
    fun applyRawKeyboardEditLocksOnlyAddedOrModifiedWordsAndPreservesExistingLocks() {
        val initial = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            "Cold caches double latency. Retries multiply cost.",
            lockedSubstrings = listOf("Cold")
        )

        val edited = DraftStateReducer.applyRawKeyboardEdit(
            draft = initial,
            editedPlainText = "Cold caches triple latency. Retries multiply real cloud cost."
        )

        val tokensByText = edited.clauses.flatMap { it.activeVersion.tokens }.associateBy { it.text }
        assertTrue("Existing lock on 'Cold' preserved", tokensByText.getValue("Cold").verbatimLocked)
        assertFalse("Unchanged 'caches' stays unlocked", tokensByText.getValue("caches").verbatimLocked)
        assertTrue("Modified word 'triple' auto-locked", tokensByText.getValue("triple").verbatimLocked)
        assertFalse("Unchanged 'latency.' stays unlocked", tokensByText.getValue("latency.").verbatimLocked)
        assertTrue("Newly added 'real' auto-locked", tokensByText.getValue("real").verbatimLocked)
        assertTrue("Newly added 'cloud' auto-locked", tokensByText.getValue("cloud").verbatimLocked)
        assertFalse("Unchanged 'cost.' stays unlocked", tokensByText.getValue("cost.").verbatimLocked)
    }

    // -------------------------------------------------------------------------
    // 10. Non-Linear Per-Clause Undo/Redo & Linear Global Time-Travel
    // -------------------------------------------------------------------------

    @Test
    fun dualAxisUndoSupportsIndependentPerClauseRingAndGlobalSnapshots() {
        val d0 = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            "Slow queues stall apps. Heavy payloads drain battery."
        )
        val c0Id = d0.clauses[0].id
        val c1Id = d0.clauses[1].id

        // Edit clause 0
        val p0 = d0.plainText()
        val d1 = DraftStateReducer.applyBrushSwap(d0, p0.indexOf("Slow") until (p0.indexOf("Slow") + 4), "deep")
        assertEquals("Deep queues stall apps. Heavy payloads drain battery.", d1.plainText())

        // Edit clause 1
        val p1 = d1.plainText()
        val d2 = DraftStateReducer.applyBrushSwap(d1, p1.indexOf("Heavy") until (p1.indexOf("Heavy") + 5), "bloated")
        assertEquals("Deep queues stall apps. Bloated payloads drain battery.", d2.plainText())

        // Non-linear undo of clause 0 ONLY — clause 1 MUST remain "Bloated"!
        val d3 = DraftStateReducer.stepClauseVersion(d2, c0Id, -1)
        assertEquals("Slow queues stall apps. Bloated payloads drain battery.", d3.plainText())
        assertEquals(0, d3.clauses.first { it.id == c0Id }.activeVersionIndex)
        assertEquals(1, d3.clauses.first { it.id == c1Id }.activeVersionIndex)

        // Redo clause 0 ONLY
        val d4 = DraftStateReducer.stepClauseVersion(d3, c0Id, +1)
        assertEquals("Deep queues stall apps. Bloated payloads drain battery.", d4.plainText())

        // Linear global time-travel backward all the way to d0 and forward again
        val gBack1 = DraftStateReducer.stepGlobalSnapshot(d2, -1)
        assertEquals(d1.plainText(), gBack1.plainText())
        val gBack2 = DraftStateReducer.stepGlobalSnapshot(gBack1, -1)
        assertEquals(d0.plainText(), gBack2.plainText())
        val gFwd1 = DraftStateReducer.stepGlobalSnapshot(gBack2, +1)
        assertEquals(d1.plainText(), gFwd1.plainText())
    }

    // -------------------------------------------------------------------------
    // 11. Toggle Word / Span Lock
    // -------------------------------------------------------------------------

    @Test
    fun toggleWordLockLocksSingleWordAndUnlocksWholeContiguousLockedSpan() {
        val draft = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            "Zero-copy ring buffers cut tail latency.",
            lockedSubstrings = listOf("Zero-copy ring buffers")
        )
        val plain = draft.plainText()

        // Double-tap "ring" (middle of 3-word locked span) -> unlocks entire 3-word span
        val unlocked = DraftStateReducer.toggleWordLock(draft, plain.indexOf("ring"))
        assertTrue(DraftStateReducer.extractLockedPhrases(unlocked.clauses).isEmpty())

        // Double-tap "tail" (unlocked) -> locks only "tail"
        val lockedTail = DraftStateReducer.toggleWordLock(unlocked, plain.indexOf("tail"))
        assertEquals(listOf("tail"), DraftStateReducer.extractLockedPhrases(lockedTail.clauses))
    }

    // -------------------------------------------------------------------------
    // 12. Overflow Thread Split (>280 chars)
    // -------------------------------------------------------------------------

    @Test
    fun splitOverflowThreadSplitsAtBestSentenceBoundaryPreservingLocksAndVersionHistories() {
        val shortDraft = DraftStateReducer.createCandidateDraft("DIRECT", "Short post under 280 chars.")
        assertTrue(DraftStateReducer.splitOverflowThread(shortDraft).linkedSplitCards.isEmpty())

        val longText = "Most mobile AI drafts fail because voice capture drops context during pauses. " +
            "We built a local VAD ring buffer that keeps every spoken idea without dead air. " +
            "Locked phrases survive every rewrite pass through a strict token parity guard. " +
            "That means your sharpest sentence never gets flattened into generic corporate sludge."
        assertTrue("Fixture must exceed 280 chars (actual=${longText.length})", longText.length > 280)

        var draft = DraftStateReducer.createCandidateDraft(
            "DIRECT",
            longText,
            lockedSubstrings = listOf("local VAD ring buffer", "strict token parity guard")
        )
        // Add a local version edit to clause 0 and clause 2 to verify version histories survive split
        val p = draft.plainText()
        draft = DraftStateReducer.applyBrushSwap(draft, p.indexOf("Most") until (p.indexOf("Most") + 4), "nearly all")
        val p2 = draft.plainText()
        draft = DraftStateReducer.applyBrushSwap(draft, p2.indexOf("survive") until (p2.indexOf("survive") + 7), "outlive")

        val split = DraftStateReducer.splitOverflowThread(draft)
        assertEquals(2, split.linkedSplitCards.size)

        val card1Text = DraftStateReducer.renderPlainText(split.linkedSplitCards[0])
        val card2Text = DraftStateReducer.renderPlainText(split.linkedSplitCards[1])
        assertTrue("Card 1 (${card1Text.length}) must fit <= 280", card1Text.length <= 280)
        assertTrue("Card 2 (${card2Text.length}) must fit <= 280", card2Text.length <= 280)
        assertTrue("Card 1 should end on a sentence boundary", card1Text.endsWith("."))

        // All locks preserved across both cards
        val card1Locks = DraftStateReducer.extractLockedPhrases(split.linkedSplitCards[0])
        val card2Locks = DraftStateReducer.extractLockedPhrases(split.linkedSplitCards[1])
        assertEquals(listOf("local VAD ring buffer"), card1Locks)
        assertEquals(listOf("strict token parity guard"), card2Locks)

        // Per-clause version histories preserved verbatim
        assertEquals(2, split.linkedSplitCards[0][0].versions.size)
        assertTrue(split.linkedSplitCards[1].any { it.versions.size == 2 })
    }

    // -------------------------------------------------------------------------
    // 13. ASCII Sanitization
    // -------------------------------------------------------------------------

    @Test
    fun sanitizeAsciiNormalizesQuotesDashesEllipsisAndTrailingWhitespace() {
        val dirty = "“Latency”—not throughput–kills UX…   \r\nWe cut p99 from 10–20ms; don’t ignore it.  "
        val clean = DraftStateReducer.sanitizeAscii(dirty)
        assertEquals("\"Latency\" - not throughput - kills UX...\nWe cut p99 from 10-20ms; don't ignore it.", clean)
    }

    // -------------------------------------------------------------------------
    // 14. Pure VAD Silence Stripping & Rolling Ring Buffer Window Extraction
    // -------------------------------------------------------------------------

    @Test
    fun stripSilencePcm16DropsLongDeadAirWhilePreservingShortPausesAndSpeech() {
        val sr = 16_000
        val speech1s = generateSinePcm16(durationMs = 1000, freqHz = 440.0, amplitude = 0.25, sampleRateHz = sr)
        val shortPause300ms = ShortArray(sr * 300 / 1000)
        val longPause2000ms = ShortArray(sr * 2000 / 1000)

        // 1. Pure silence -> empty
        assertEquals(0, stripSilencePcm16(longPause2000ms, sampleRateHz = sr).size)

        // 2. Pure speech -> preserved 100%
        val pureOut = stripSilencePcm16(speech1s, sampleRateHz = sr)
        assertEquals(speech1s.size, pureOut.size)

        // 3. Natural pause 300ms <= 600ms -> preserved intact
        val withShortPause = concatArrays(speech1s, shortPause300ms, speech1s)
        val shortOut = stripSilencePcm16(withShortPause, sampleRateHz = sr, maxPauseMs = 600)
        assertEquals(withShortPause.size, shortOut.size)

        // 4. Dead-air pause 2000ms > 600ms -> excess silence stripped cleanly
        val withLongPause = concatArrays(speech1s, longPause2000ms, speech1s)
        val strippedOut = stripSilencePcm16(withLongPause, sampleRateHz = sr, maxPauseMs = 600)
        val expectedMaxSamples = sr * 2400 / 1000 // 1s + <=0.4s breath + 1s
        val expectedMinSamples = sr * 2000 / 1000 // both 1s speech segments intact
        assertTrue("Expected stripped samples in [$expectedMinSamples, $expectedMaxSamples], got ${strippedOut.size}",
            strippedOut.size in expectedMinSamples..expectedMaxSamples)
    }

    @Test
    fun vadEngineSegmentsUtterancesAfter600msPauseAndExtractsTrailing15sWav() {
        val tempDir = Files.createTempDirectory("vad_engine_test").toFile()
        try {
            val engine = VadAudioCaptureEngine(chunkCacheDir = tempDir, silenceThresholdRms = 0.015f, maxPauseMs = 600L)
            val speechFrame = generateSinePcm16(durationMs = 100, freqHz = 320.0, amplitude = 0.25)
            val silenceFrame = ShortArray(VadAudioCaptureEngine.FRAME_SAMPLES)

            // 1. Feed 1.0s of speech + 0.7s of silence -> utterance emitted on 7th silent frame (>600ms)
            var emittedCount = 0
            repeat(10) {
                assertNull(engine.ingestOpenLatchFrame(speechFrame))
            }
            for (s in 1..7) {
                val emitted = engine.ingestOpenLatchFrame(silenceFrame)
                if (s <= 6) {
                    assertNull("Should not emit before >600ms pause (at ${s * 100}ms)", emitted)
                } else {
                    assertNotNull("Should emit utterance once pause exceeds 600ms", emitted)
                    assertTrue(emitted!!.size >= VadAudioCaptureEngine.FRAME_SAMPLES * 8)
                    emittedCount++
                }
            }
            assertEquals(1, emittedCount)

            // 2. Feed remaining frames up to 22s total elapsed, with final 3s voiced so trailing 15s window has speech
            val remainingFrames = 220 - 17
            repeat(remainingFrames - 30) {
                engine.ingestOpenLatchFrame(silenceFrame)
            }
            repeat(30) {
                engine.ingestOpenLatchFrame(speechFrame)
            }

            assertEquals(22_000L, engine.totalElapsedMs.value)
            assertTrue(engine.isTrailingDistillReady)

            val trailingWav = File(tempDir, "trailing_15s.wav")
            val result = engine.extractTrailingWindowWav(trailingWav, windowMs = 15_000L)
            assertNotNull(result)
            assertTrue(trailingWav.exists())

            val bytes = trailingWav.readBytes()
            assertTrue(bytes.size > 44)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            val sampleRate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals(16_000, sampleRate)
            // Only the 3s of speech in the trailing 15s window should survive (not the 1s at t=0..1s)
            val dataBytes = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val durationMs = (dataBytes / 2L) * 1000L / 16_000L
            assertTrue("Expected ~3000ms of trailing voiced speech, got ${durationMs}ms", durationMs in 2800L..3400L)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // 15. DraftApiClient Real HTTP Contract, /health Fallback & Error Propagation
    // -------------------------------------------------------------------------

    @Test
    fun draftApiClientAutoResolvesFallbackCallsAllSixEndpointsAndThrowsOnNon2xx() = runTest {
        val capturedPayloads = HashMap<String, String>()
        var failDensityWith503 = false

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            respondJson(ex, 200, """{"status":"ok"}""")
        }
        server.createContext("/v1/draft/transcribe") { ex ->
            capturedPayloads["/v1/draft/transcribe"] = ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"transcript":"Cold caches double latency.","duration_ms":2450}""")
        }
        server.createContext("/v1/draft/angles") { ex ->
            capturedPayloads["/v1/draft/angles"] = ex.requestBody.bufferedReader().readText()
            respondJson(
                ex,
                200,
                """{
                  "extracted_angles":[{"id":"ex_1","label":"Cold caches double p99","preview":"Unwarmed caches spike tail latency."}],
                  "blindspots":[{"id":"bs_1","label":"Cost of pre-warming","preview":"Pre-warming shifts memory overhead to idle pods."}]
                }"""
            )
        }
        server.createContext("/v1/draft/synthesize") { ex ->
            capturedPayloads["/v1/draft/synthesize"] = ex.requestBody.bufferedReader().readText()
            respondJson(
                ex,
                200,
                """{"candidates":[
                  {"posture_label":"DIRECT","text":"Cold caches double p99 latency."},
                  {"posture_label":"RECEIPT","text":"Our p99 jumped 2.1x after every cold deploy."},
                  {"posture_label":"CONTRARIAN","text":"Caching hides bad query plans until restart."}
                ]}"""
            )
        }
        server.createContext("/v1/draft/brush-swap") { ex ->
            capturedPayloads["/v1/draft/brush-swap"] = ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"alternatives":["spike","double",""]}""")
        }
        server.createContext("/v1/draft/seam-heal") { ex ->
            capturedPayloads["/v1/draft/seam-heal"] = ex.requestBody.bufferedReader().readText()
            respondJson(ex, 200, """{"text":"Cold caches spike p99.","lock_parity_preserved":true}""")
        }
        server.createContext("/v1/draft/density") { ex ->
            capturedPayloads["/v1/draft/density"] = ex.requestBody.bufferedReader().readText()
            if (failDensityWith503) {
                respondJson(ex, 503, """{"detail":"bl CLI unavailable on Mac rig"}""")
            } else {
                respondJson(ex, 200, """{"text":"Cold caches spike p99.","lock_parity_preserved":true}""")
            }
        }
        server.start()

        try {
            val liveUrl = "http://127.0.0.1:${server.address.port}"
            // Point usbLoopbackUrl to an unused closed port to verify real /health failover to configuredRigUrl!
            val closedSocket = java.net.ServerSocket(0)
            val deadPort = closedSocket.localPort
            closedSocket.close()

            val client = DraftApiClient(
                configuredRigUrl = liveUrl,
                usbLoopbackUrl = "http://127.0.0.1:$deadPort",
                healthProbeTimeoutMs = 500L
            )

            assertEquals(liveUrl, client.resolveActiveBaseUrl())

            // 1. transcribe
            val sampleWavBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00)
            val tx = client.transcribeWavBytes(sampleWavBytes)
            assertEquals("Cold caches double latency.", tx.transcript)
            assertEquals(2450L, tx.durationMs)
            val txJson = Json.parseToJsonElement(capturedPayloads.getValue("/v1/draft/transcribe")).jsonObject
            assertEquals(
                Base64.getEncoder().encodeToString(sampleWavBytes),
                txJson["audio_wav_base64"]?.jsonPrimitive?.content
            )

            // 2. angles
            val angles = client.extractAngles("Cold caches double latency.", listOf("no fluff"))
            assertEquals(1, angles.extractedAngles.size)
            assertEquals(1, angles.blindspots.size)
            assertFalse(angles.toExtractedAngleChips().first().isBlindspot)
            assertTrue(angles.toBlindspotAngleChips().first().isBlindspot)

            // 3. synthesize (with slot = null serialized explicitly)
            val synth = client.synthesize(
                seedTranscript = "Cold caches double latency.",
                selectedAngles = listOf("Cold caches double p99"),
                steeringConstraints = listOf("under 220 chars"),
                slot = null,
                existingDrafts = emptyList()
            )
            assertEquals(3, synth.candidates.size)
            assertEquals("DIRECT", synth.candidates[0].postureLabel)
            val synthJson = Json.parseToJsonElement(capturedPayloads.getValue("/v1/draft/synthesize")).jsonObject
            assertEquals(JsonNull, synthJson["slot"])

            // 4. brush-swap
            val swap = client.brushSwap("<lock>Cold caches</lock> <replace>increase</replace> p99.")
            assertEquals(listOf("spike", "double", ""), swap.alternatives)

            // 5. seam-heal
            val healed = client.seamHeal("<lock>Cold caches</lock> spike p99.", "strike_cut")
            assertEquals("Cold caches spike p99.", healed.text)
            assertTrue(healed.lockParityPreserved)
            val healJson = Json.parseToJsonElement(capturedPayloads.getValue("/v1/draft/seam-heal")).jsonObject
            assertEquals("strike_cut", healJson["operation"]?.jsonPrimitive?.content)

            // 6. density (200 OK)
            val dense = client.adjustDensity("<lock>Cold caches</lock> greatly increase p99.", -20)
            assertEquals("Cold caches spike p99.", dense.text)
            assertTrue(dense.lockParityPreserved)
            val denseJson = Json.parseToJsonElement(capturedPayloads.getValue("/v1/draft/density")).jsonObject
            assertEquals("-20", denseJson["delta_percent"]?.jsonPrimitive?.content)
            assertEquals(0, denseJson["steering_constraints"]?.jsonArray?.size)

            // 7. Non-2xx MUST throw explicit IOException with server detail (never fabricate fallback!)
            failDensityWith503 = true
            try {
                client.adjustDensity("<lock>Cold caches</lock> spike p99.", -15)
                fail("Expected IOException on HTTP 503")
            } catch (e: IOException) {
                assertTrue("Expected HTTP 503 detail, got: ${e.message}", e.message!!.contains("503"))
                assertTrue("Expected server detail, got: ${e.message}", e.message!!.contains("bl CLI unavailable on Mac rig"))
            }

            // 8. Unreachable server MUST throw IOException
            val deadClient = DraftApiClient(
                configuredRigUrl = "http://127.0.0.1:$deadPort",
                usbLoopbackUrl = "http://127.0.0.1:$deadPort",
                healthProbeTimeoutMs = 300L
            )
            try {
                deadClient.resolveActiveBaseUrl()
                fail("Expected IOException when all endpoints unreachable")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("unreachable", ignoreCase = true))
            }
        } finally {
            server.stop(0)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun generateSinePcm16(
        durationMs: Int,
        freqHz: Double,
        amplitude: Double,
        sampleRateHz: Int = 16_000
    ): ShortArray {
        val count = (sampleRateHz * durationMs) / 1000
        return ShortArray(count) { i ->
            val value = sin(2.0 * PI * freqHz * i / sampleRateHz) * amplitude * 32767.0
            value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun concatArrays(vararg arrays: ShortArray): ShortArray {
        val total = arrays.sumOf { it.size }
        val out = ShortArray(total)
        var offset = 0
        for (a in arrays) {
            a.copyInto(out, destinationOffset = offset)
            offset += a.size
        }
        return out
    }

    private fun respondJson(exchange: com.sun.net.httpserver.HttpExchange, statusCode: Int, jsonBody: String) {
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
