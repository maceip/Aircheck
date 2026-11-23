package com.voicestudio.edl

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdlGlitchTest {

    private fun sampleMetrics(): GateMetrics = GateMetrics(
        cer = 0.0f,
        maxPhonemeDurationMs = 210.0f,
        speakerCosineSim = 0.92f,
        dnsmosP808 = 4.45f,
        tailRmsDb = -52.0f,
        syllablesPerSec = 5.1f,
        passedAllGates = true
    )

    private fun buildFiveClauseManifest(
        globalGapScale: Float = 1.0f,
        selectedTakeByClause: List<Int> = List(5) { 0 }
    ): VoiceProjectManifest {
        val clauses = (0 until 5).map { clauseIdx ->
            val takes = (0 until 3).map { takeIdx ->
                val word0In = 400L + takeIdx * 50L
                val word0Out = word0In + 8_000L
                val word1In = word0Out + 200L
                val word1Out = word1In + 9_000L + takeIdx * 300L
                val totalSamples = word1Out + 1_200L
                CandidateTake(
                    takeId = "c${clauseIdx}_t$takeIdx",
                    pcmFilePath = "/data/user/0/com.aircheck/no_backup/c${clauseIdx}_t$takeIdx.pcm",
                    totalSamples = totalSamples,
                    discreteAudioTokens = intArrayOf(clauseIdx * 10 + takeIdx, 42, 99),
                    seed = 1000L + clauseIdx * 10L + takeIdx,
                    temperature = 0.7f,
                    metrics = sampleMetrics(),
                    tokens = listOf(
                        WordToken(
                            word = "word${clauseIdx}_a",
                            startSample = word0In,
                            endSample = word0Out,
                            zeroCrossIn = word0In,
                            zeroCrossOut = word0Out,
                            f0PeakSample = word0In + 2_000L,
                            videoAnchorId = if (clauseIdx in listOf(1, 3, 4)) "cut_$clauseIdx" else null
                        ),
                        WordToken(
                            word = "word${clauseIdx}_b",
                            startSample = word1In,
                            endSample = word1Out,
                            zeroCrossIn = word1In,
                            zeroCrossOut = word1Out,
                            f0PeakSample = word1In + 3_000L,
                            videoAnchorId = null
                        )
                    )
                )
            }
            ClauseNode(
                clauseId = "clause_$clauseIdx",
                index = clauseIdx,
                rawText = "word${clauseIdx}_a word${clauseIdx}_b",
                normalizedPhonemes = "w ɜː d",
                selectedTakeIndex = selectedTakeByClause[clauseIdx],
                baseGapAfterSamples = DEFAULT_BASE_GAP_SAMPLES,
                candidates = takes
            )
        }
        return VoiceProjectManifest(
            projectId = "gate3_project",
            sampleRate = SYNTHESIS_SAMPLE_RATE_HZ,
            globalGapScale = globalGapScale,
            activeLoraVersion = 7,
            clauses = clauses
        )
    }

    @Test
    fun buildSlicesMatchesComputeAnchorTimestampsAndGapScaleSweep() {
        val scales = listOf(1.0f, 0.75f, 0.60f, 0.25f)
        for (scale in scales) {
            val manifest = buildFiveClauseManifest(
                globalGapScale = scale,
                selectedTakeByClause = listOf(0, 1, 2, 1, 0)
            )
            val slices = EdlTimelineCalculator.buildSlices(manifest)
            val spans = EdlTimelineCalculator.buildTimelineSpans(manifest)
            val expectedAnchors = computeAnchorTimestampsUs(manifest)

            assertEquals(5, slices.size)
            assertEquals(5, spans.size)

            val expectedGap = (DEFAULT_BASE_GAP_SAMPLES * scale).toInt()
            slices.forEachIndexed { idx, slice ->
                val expectedTake = manifest.clauses[idx].selectedCandidate!!
                assertEquals(expectedTake.pcmFilePath, slice.pcmFilePath)
                assertEquals(expectedTake.tokens.first().zeroCrossIn, slice.zeroCrossInSample)
                assertEquals(expectedTake.tokens.last().zeroCrossOut, slice.zeroCrossOutSample)
                assertEquals(expectedGap, slice.gapAfterSamples)
            }

            // Every videoAnchorId computed from spans must match ClauseNode.computeAnchorTimestampsUs exactly.
            for (span in spans) {
                val winner = manifest.clauses[span.clauseIndex].selectedCandidate!!
                for (token in winner.tokens) {
                    val anchorId = token.videoAnchorId ?: continue
                    val absSample = span.timelineStartSample + (token.zeroCrossIn - span.slice.zeroCrossInSample)
                    val spanUs = EdlTimelineCalculator.sampleToTimeUs(absSample, manifest.sampleRate)
                    assertEquals(expectedAnchors[anchorId], spanUs)
                }
            }
        }
    }

    @Test
    fun timelineCalculatorMapsClausesWordsGapsAndZeroCrossSnaps() {
        val manifestFull = buildFiveClauseManifest(globalGapScale = 1.0f)
        val spansFull = EdlTimelineCalculator.buildTimelineSpans(manifestFull)

        // First sample of clause 0 -> word 0, localSample == zeroCrossIn
        val posStart = EdlTimelineCalculator.mapSampleToPosition(manifestFull, 0L)
        assertFalse(posStart.inGap)
        assertEquals(0, posStart.clauseIndex)
        assertEquals("clause_0", posStart.clauseId)
        assertEquals(0, posStart.wordTokenIndex)
        assertEquals(400L, posStart.localSample)

        // Sample inside word 1 of clause 0
        val word1Local = 9_500L
        val word1Global = word1Local - spansFull[0].slice.zeroCrossInSample
        val posWord1 = EdlTimelineCalculator.mapSampleToPosition(manifestFull, word1Global)
        assertFalse(posWord1.inGap)
        assertEquals(0, posWord1.clauseIndex)
        assertEquals(1, posWord1.wordTokenIndex)
        assertEquals(word1Local, posWord1.localSample)

        // Sample inside post-clause 0 gap
        val gapSample = spansFull[0].activeEndSample + 500L
        val posGap = EdlTimelineCalculator.mapSampleToPosition(manifestFull, gapSample)
        assertTrue(posGap.inGap)
        assertEquals(0, posGap.clauseIndex)
        assertEquals(-1, posGap.wordTokenIndex)
        assertEquals(-1L, posGap.localSample)

        // Sample inside clause 2 after compressing globalGapScale 1.0 -> 0.25
        val manifestTight = buildFiveClauseManifest(globalGapScale = 0.25f)
        val spansTight = EdlTimelineCalculator.buildTimelineSpans(manifestTight)
        assertTrue(
            "Compressed timeline must be strictly shorter",
            EdlTimelineCalculator.totalTimelineSamples(manifestTight) <
                EdlTimelineCalculator.totalTimelineSamples(manifestFull)
        )
        val clause2Start = spansTight[2].timelineStartSample
        val posClause2 = EdlTimelineCalculator.mapSampleToPosition(manifestTight, clause2Start + 100L)
        assertFalse(posClause2.inGap)
        assertEquals(2, posClause2.clauseIndex)
        assertEquals("clause_2", posClause2.clauseId)
        assertEquals(0, posClause2.wordTokenIndex)
        assertEquals(spansTight[2].slice.zeroCrossInSample + 100L, posClause2.localSample)

        // Magnetic zero-crossing snap within clause 0
        val nearWord0Out = spansFull[0].timelineStartSample + (8_400L - 400L) + 25L
        val snapped = EdlTimelineCalculator.snapToNearestZeroCrossing(manifestFull, nearWord0Out)
        assertEquals(8_000L, snapped)

        // Empty manifest returns safe sentinel
        val emptyPos = EdlTimelineCalculator.mapSampleToPosition(
            VoiceProjectManifest(projectId = "empty"),
            1000L
        )
        assertEquals(-1, emptyPos.clauseIndex)
        assertNull(emptyPos.clauseId)
        assertTrue(emptyPos.inGap)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun edlControllerHandles50GapScaleAndTakeSwapMutationsAndLifecycle() {
        val recordingBackend = RecordingEdlBackend()
        val controller = EdlController(
            backendOverride = recordingBackend,
            pollDispatcher = UnconfinedTestDispatcher(),
            playheadPollIntervalMs = 0L
        )

        // Starting before init must fail honestly
        assertTrue(controller.start().isFailure)
        assertFalse(controller.isPlaying.value)

        // Blank room tone path must fail honestly
        assertTrue(controller.initPlayer("   ").isFailure)

        // Initialize and preload
        val initialManifest = buildFiveClauseManifest(globalGapScale = 1.0f)
        assertTrue(controller.initPlayer("/data/room_tone_44k.pcm").isSuccess)
        assertTrue(controller.preloadManifestFiles(initialManifest).isSuccess)
        assertEquals(15, recordingBackend.preloadedPaths.size)

        // Publish initial snapshot and start playback
        assertTrue(controller.updateFromManifest(initialManifest).isSuccess)
        assertTrue(controller.start().isSuccess)
        assertTrue(controller.isPlaying.value)

        // Run 50 mutations sweeping globalGapScale 1.0f -> 0.25f and rotating takes
        for (step in 0 until 50) {
            val scale = 1.0f - 0.75f * (step / 49.0f)
            val takes = List(5) { clauseIdx -> (clauseIdx + step) % 3 }
            val mutated = buildFiveClauseManifest(globalGapScale = scale, selectedTakeByClause = takes)
            val res = controller.updateFromManifest(mutated)
            assertTrue("Mutation $step failed: ${res.exceptionOrNull()}", res.isSuccess)
            assertEquals(5, controller.activeSlices.value.size)
            assertEquals((DEFAULT_BASE_GAP_SAMPLES * scale).toInt(), controller.activeSlices.value[0].gapAfterSamples)
        }

        // Verify 51 total snapshots published (1 initial + 50 mutations), exact last scale == 0.25f
        assertEquals(51, recordingBackend.snapshotCount)
        assertEquals(0.25f, recordingBackend.lastGapScale, 1e-4f)
        assertEquals(0, controller.getXRunCount())

        // Seek and verify playhead StateFlow tracks native sample
        controller.seekToSample(22_050L)
        assertEquals(22_050L, controller.playheadSample.value)
        assertEquals(22_050L, controller.getCurrentSample())

        // Pause updates isPlaying flow
        controller.pause()
        assertFalse(controller.isPlaying.value)

        // Native error propagation must never be masked as success
        recordingBackend.failNextUpdateWith = "mmap('/missing.pcm') failed: No such file or directory"
        val failedSwap = controller.updateFromManifest(initialManifest)
        assertTrue(failedSwap.isFailure)
        assertTrue(failedSwap.exceptionOrNull()?.message?.contains("mmap") == true)

        // Close is idempotent and blocks subsequent mutations
        controller.close()
        controller.close()
        assertEquals(1, recordingBackend.closeCount)
        assertTrue(controller.activeSlices.value.isEmpty())
        assertTrue(controller.start().isFailure)
        assertTrue(controller.updateFromManifest(initialManifest).isFailure)
    }

    @Test
    fun defaultControllerFailsHonestlyWhenAndroidNativeLibraryUnavailableOnHostJvm() {
        val controller = EdlController()
        val result = controller.initPlayer("/tmp/nonexistent_room_tone.pcm")
        assertTrue("Expected failure when libvoicestudio_edl.so is absent on host JVM", result.isFailure)
        assertNotNull(result.exceptionOrNull())
        controller.close()
    }

    private class RecordingEdlBackend : EdlNativeBackend {
        var nextHandle = 100L
        var initialized = false
        var playing = false
        var currentSample = 0L
        var snapshotCount = 0
        var lastGapScale = 1.0f
        var closeCount = 0
        var preloadedPaths: List<String> = emptyList()
        var failNextUpdateWith: String? = null

        override fun create(): Result<Long> = Result.success(nextHandle++)

        override fun init(handle: Long, roomToneLoopPath: String): String? {
            initialized = true
            return null
        }

        override fun start(handle: Long): String? {
            if (!initialized) return "EdlPlayer::Init must succeed before Start()"
            playing = true
            return null
        }

        override fun pause(handle: Long) {
            playing = false
        }

        override fun close(handle: Long) {
            playing = false
            closeCount++
        }

        override fun preloadFiles(handle: Long, pcmPaths: Array<String>): String? {
            preloadedPaths = pcmPaths.toList()
            return null
        }

        override fun updateSnapshot(
            handle: Long,
            pcmPaths: Array<String>,
            zeroCrossInSamples: LongArray,
            zeroCrossOutSamples: LongArray,
            gapAfterSamples: IntArray,
            globalGapScale: Float
        ): String? {
            failNextUpdateWith?.let { err ->
                failNextUpdateWith = null
                return err
            }
            snapshotCount++
            lastGapScale = globalGapScale
            return null
        }

        override fun seekToSample(handle: Long, timelineSample: Long) {
            currentSample = timelineSample
        }

        override fun getCurrentSample(handle: Long): Long = currentSample

        override fun getXRunCount(handle: Long): Int = 0

        override fun isPlaying(handle: Long): Boolean = playing
    }
}
