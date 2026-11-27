package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CutTimelineTest {
    private fun session(vararg clauses: CoreStoryClause, ttsMs: Long = 6_000L) =
        CorePipelineSession(id = "pipe_t", clauses = clauses.toList(), ttsDurationMs = ttsMs)

    @Test
    fun emptyClipsMeanWholeSource_andNoEditRanges() {
        val s = session(CoreStoryClause(id = "a", spokenText = "One.", startMs = 0, endMs = 6_000))
        val clips = CutTimeline.effectiveClips(s)
        assertEquals(1, clips.size)
        assertEquals(0L to 6_000L, clips[0].sourceStartMs to clips[0].sourceEndMs)
        assertNull(CutTimeline.editRanges(s))
    }

    @Test
    fun splitAtPlayhead_makesTwoClips_andIgnoresEdges() {
        val clips = CutTimeline.effectiveClips(emptyList(), 6_000L)
        val split = CutTimeline.split(clips, 2_500L)
        assertEquals(listOf(0L to 2_500L, 2_500L to 6_000L), split.map { it.sourceStartMs to it.sourceEndMs })
        assertTrue(split[0].id != split[1].id)
        // Too close to a clip edge, or outside the video: unchanged.
        assertEquals(split, CutTimeline.split(split, 2_550L))
        assertEquals(split, CutTimeline.split(split, 9_000L))
    }

    @Test
    fun trimClampsToNeighboursAndSource_andMapsTimeline() {
        val split = CutTimeline.split(CutTimeline.effectiveClips(emptyList(), 6_000L), 3_000L)
        // Trim the head of clip 2 and the tail of clip 1 (removes 2.0s..3.5s).
        var clips = CutTimeline.trim(split, split[0].id, 0L, 2_000L, 6_000L)
        clips = CutTimeline.trim(clips, split[1].id, 3_500L, 6_000L, 6_000L)
        assertEquals(listOf(0L to 2_000L, 3_500L to 6_000L), clips.map { it.sourceStartMs to it.sourceEndMs })
        // Cannot grow into the neighbour or past the source.
        val clamped = CutTimeline.trim(clips, clips[0].id, -50L, 5_000L, 6_000L)
        assertEquals(0L to 3_500L, clamped[0].sourceStartMs to clamped[0].sourceEndMs)
        // A trim leaving < MIN_CLIP_MS is refused.
        assertEquals(clips, CutTimeline.trim(clips, clips[0].id, 1_950L, 2_000L, 6_000L))

        assertEquals(4_500L, CutTimeline.timelineDurationMs(clips))
        assertEquals(3_600L, CutTimeline.timelineToSource(clips, 2_100L))
        assertEquals(2_100L, CutTimeline.sourceToTimeline(clips, 3_600L))
        assertNull(CutTimeline.sourceToTimeline(clips, 2_500L))
        assertNull(CutTimeline.timelineToSource(clips, 4_500L))

        val s = session(CoreStoryClause(id = "a", spokenText = "x", endMs = 6_000)).copy(cutClips = clips)
        assertEquals(listOf(0L to 2_000L, 3_500L to 6_000L), CutTimeline.editRanges(s))
        assertEquals(clips.size - 1, CutTimeline.remove(clips, clips[0].id).size)
        assertEquals(1, CutTimeline.remove(CutTimeline.remove(clips, clips[0].id), clips[1].id).size)
    }

    @Test
    fun sentenceTagsMigrateToLockedSegments_thatFollowRetiming() {
        val tagged = CoreStoryClause(
            id = "b", spokenText = "Two.", startMs = 2_000, endMs = 4_000,
            segmentMode = BrollSegmentMode.BROLL_CUTAWAY, bRollPrompt = "Harbour", brollMediaId = "bmedia_1",
            brollFramingOverride = "full"
        )
        val s = CutTimeline.normalize(session(CoreStoryClause(id = "a", spokenText = "One.", endMs = 2_000), tagged))
        val seg = s.brollSegments.single()
        assertEquals(2_000L to 2_000L, seg.startMs to seg.durationMs)
        assertEquals("Harbour", seg.prompt)
        assertEquals("bmedia_1", seg.mediaId)
        assertEquals("full", seg.framingOverride)
        assertEquals("b", seg.originClauseId)
        assertTrue(seg.lockedToClause)
        assertTrue(CutTimeline.isSentenceAligned(s, seg))
        // Idempotent.
        assertEquals(s, CutTimeline.normalize(s))

        // Re-voicing moves the sentence: the locked segment follows it.
        val retimed = CutTimeline.normalize(
            s.copy(clauses = s.clauses.map { if (it.id == "b") it.copy(startMs = 2_400, endMs = 5_000) else it })
        )
        assertEquals(2_400L to 2_600L, retimed.brollSegments.single().let { it.startMs to it.durationMs })

        // Untagging the sentence removes its segment.
        val untagged = CutTimeline.normalize(
            retimed.copy(clauses = retimed.clauses.map { it.copy(segmentMode = BrollSegmentMode.TALKING_HEAD) })
        )
        assertTrue(untagged.brollSegments.isEmpty())
    }

    @Test
    fun movedSegmentUnlocks_staysLinked_andRemovalClearsTheTag() {
        val tagged = CoreStoryClause(
            id = "b", spokenText = "Two.", startMs = 2_000, endMs = 4_000, segmentMode = BrollSegmentMode.BROLL_CUTAWAY
        )
        val s = CutTimeline.normalize(session(tagged))
        val id = s.brollSegments.single().id
        val moved = CutTimeline.normalize(s.copy(brollSegments = CutTimeline.moveSegment(s.brollSegments, id, 500L, 6_000L)))
        val seg = moved.brollSegments.single()
        assertEquals(500L, seg.startMs)
        assertFalse(seg.lockedToClause)
        assertFalse(CutTimeline.isSentenceAligned(moved, seg))
        assertEquals(BrollSegmentMode.BROLL_CUTAWAY, moved.clauses.single().segmentMode)
        // Moves clamp so the segment ends inside the source.
        assertEquals(4_000L, CutTimeline.moveSegment(moved.brollSegments, id, 5_500L, 6_000L).single().startMs)

        val removed = CutTimeline.removeSegment(moved, id)
        assertTrue(removed.brollSegments.isEmpty())
        assertEquals(BrollSegmentMode.TALKING_HEAD, removed.clauses.single().segmentMode)
    }

    @Test
    fun freeSegments_addResizeAndSurviveNormalize() {
        val s = session(CoreStoryClause(id = "a", spokenText = "One.", endMs = 6_000))
        val added = CutTimeline.addSegment(s.brollSegments, 5_000L, 6_000L, prompt = "Rain")
        assertEquals(4_000L to 2_000L, added.single().let { it.startMs to it.durationMs })
        val resized = CutTimeline.resizeSegment(added, added.single().id, 50L, 6_000L)
        assertEquals(CutTimeline.MIN_SEGMENT_MS, resized.single().durationMs)
        val normalized = CutTimeline.normalize(s.copy(brollSegments = resized))
        assertEquals(resized, normalized.brollSegments)
        assertEquals(BrollSegmentMode.TALKING_HEAD, normalized.clauses.single().segmentMode)
    }

    @Test
    fun cutSignatureChangesWithTrimsAndSegments() {
        val s = session(CoreStoryClause(id = "a", spokenText = "One.", endMs = 6_000))
        val base = s.cutSignature(null)
        val split = s.copy(cutClips = CutTimeline.split(CutTimeline.effectiveClips(s), 3_000L))
        assertTrue(base != split.cutSignature(null))
        val withSeg = s.copy(brollSegments = CutTimeline.addSegment(emptyList(), 1_000L, 6_000L))
        assertTrue(base != withSeg.cutSignature(null))
    }
}
