package com.example.data

/**
 * Cut editor model: per-clip trim in/out, split at the playhead, and timed B-roll segments.
 *
 * Times:
 * - "source" ms = position in the talking-head render (same clock as clause windows and words).
 * - "timeline" ms = position in the edited video (kept clips played back to back).
 *
 * B-roll segments are stored in source ms so trimming a clip never silently moves a cutaway; a
 * segment that ends up inside a removed range is simply not rendered.
 */
object CutTimeline {
    /** Shortest clip a trim or split may leave. */
    const val MIN_CLIP_MS = 100L

    /** Shortest B-roll segment. */
    const val MIN_SEGMENT_MS = 200L

    /** Length of a segment added from the timeline. */
    const val DEFAULT_SEGMENT_MS = 2_000L

    /** Talking-head source length: the voiced duration, or the last clause end before TTS timing. */
    fun sourceDurationMs(session: CorePipelineSession): Long =
        maxOf(session.ttsDurationMs, session.clauses.maxOfOrNull { it.endMs } ?: 0L)

    /** Kept clips; an empty list means one clip covering the whole source. */
    fun effectiveClips(session: CorePipelineSession): List<CutClip> =
        effectiveClips(session.cutClips, sourceDurationMs(session))

    fun effectiveClips(clips: List<CutClip>, sourceDurationMs: Long): List<CutClip> =
        if (clips.isEmpty()) {
            if (sourceDurationMs > 0L) listOf(CutClip(id = FULL_CLIP_ID, sourceStartMs = 0L, sourceEndMs = sourceDurationMs)) else emptyList()
        } else {
            clips.sortedBy { it.sourceStartMs }
        }

    fun timelineDurationMs(clips: List<CutClip>): Long = clips.sumOf { it.durationMs }

    /** Source position played at [timelineMs], or null outside the edited video. */
    fun timelineToSource(clips: List<CutClip>, timelineMs: Long): Long? {
        if (timelineMs < 0L) return null
        var offset = 0L
        for (clip in clips) {
            if (timelineMs < offset + clip.durationMs) return clip.sourceStartMs + (timelineMs - offset)
            offset += clip.durationMs
        }
        return null
    }

    /** Timeline position of source [sourceMs], or null when that moment was trimmed away. */
    fun sourceToTimeline(clips: List<CutClip>, sourceMs: Long): Long? {
        var offset = 0L
        for (clip in clips) {
            if (sourceMs >= clip.sourceStartMs && sourceMs < clip.sourceEndMs) return offset + (sourceMs - clip.sourceStartMs)
            offset += clip.durationMs
        }
        return null
    }

    /**
     * Splits the clip under the playhead ([timelineMs]) into two clips. Returns the clips unchanged
     * when the playhead is outside the video or either side would be shorter than [MIN_CLIP_MS].
     */
    fun split(clips: List<CutClip>, timelineMs: Long): List<CutClip> {
        val sourceMs = timelineToSource(clips, timelineMs) ?: return clips
        val target = clips.firstOrNull { sourceMs >= it.sourceStartMs && sourceMs < it.sourceEndMs } ?: return clips
        if (sourceMs - target.sourceStartMs < MIN_CLIP_MS || target.sourceEndMs - sourceMs < MIN_CLIP_MS) return clips
        return clips.flatMap { clip ->
            if (clip !== target) {
                listOf(clip)
            } else {
                listOf(
                    clip.copy(id = if (clip.id == FULL_CLIP_ID) CutClip(sourceStartMs = 0L, sourceEndMs = 0L).id else clip.id, sourceEndMs = sourceMs),
                    CutClip(sourceStartMs = sourceMs, sourceEndMs = clip.sourceEndMs)
                )
            }
        }
    }

    /**
     * Moves the trim in/out points of clip [clipId] to [newStartMs]/[newEndMs] (source ms), clamped
     * so it never overlaps its neighbours, stays inside the source, and keeps [MIN_CLIP_MS].
     */
    fun trim(clips: List<CutClip>, clipId: String, newStartMs: Long, newEndMs: Long, sourceDurationMs: Long): List<CutClip> {
        val sorted = clips.sortedBy { it.sourceStartMs }
        val idx = sorted.indexOfFirst { it.id == clipId }
        if (idx < 0) return clips
        val lowerBound = sorted.getOrNull(idx - 1)?.sourceEndMs ?: 0L
        val upperBound = sorted.getOrNull(idx + 1)?.sourceStartMs ?: sourceDurationMs
        val start = newStartMs.coerceIn(lowerBound, upperBound)
        val end = newEndMs.coerceIn(lowerBound, upperBound)
        if (end - start < MIN_CLIP_MS) return clips
        return sorted.mapIndexed { i, c -> if (i == idx) c.copy(sourceStartMs = start, sourceEndMs = end) else c }
    }

    /** Removes clip [clipId] from the edit (never the last one). */
    fun remove(clips: List<CutClip>, clipId: String): List<CutClip> =
        if (clips.size <= 1) clips else clips.filterNot { it.id == clipId }

    /** Kept source ranges for the renderer, or null when the edit keeps the whole source. */
    fun editRanges(session: CorePipelineSession): List<Pair<Long, Long>>? {
        val total = sourceDurationMs(session)
        if (session.cutClips.isEmpty() || total <= 0L) return null
        val ranges = session.cutClips.sortedBy { it.sourceStartMs }.map { it.sourceStartMs to it.sourceEndMs }
        val coversAll = ranges.size == 1 && ranges[0].first <= 0L && ranges[0].second >= total
        return if (coversAll) null else ranges
    }

    // ------------------------------------------------------------------ B-roll segments

    /**
     * Keeps segments and the legacy per-sentence tags consistent:
     * 1. a sentence tagged as B-roll without a linked segment gets one (this migrates old sessions);
     *    a linked segment whose sentence is no longer tagged is removed;
     * 2. segments locked to a sentence follow its window, and take the sentence's B-roll source;
     * 3. each sentence's tag mirrors whether it has a linked segment.
     *
     * Segment-native edits ([moveSegment] etc.) only touch segments and then go through this mirror,
     * so the sentence tags never fight them.
     */
    fun normalize(session: CorePipelineSession): CorePipelineSession {
        val clauseIds = session.clauses.map { it.id }.toSet()
        val kept = session.brollSegments.filter { seg ->
            val origin = seg.originClauseId ?: return@filter true
            if (origin !in clauseIds) return@filter false
            session.clauses.first { it.id == origin }.segmentMode == BrollSegmentMode.BROLL_CUTAWAY
        }.toMutableList()
        session.clauses.forEach { clause ->
            if (clause.segmentMode != BrollSegmentMode.BROLL_CUTAWAY) return@forEach
            val idx = kept.indexOfFirst { it.originClauseId == clause.id }
            if (idx < 0) {
                kept += segmentFromClause(clause)
            } else {
                kept[idx] = withClauseSource(kept[idx], clause)
            }
        }
        val segments = kept.sortedWith(compareBy({ it.startMs }, { it.id }))
        val linked = segments.mapNotNull { it.originClauseId }.toSet()
        val clauses = session.clauses.map { c ->
            val mode = if (c.id in linked) BrollSegmentMode.BROLL_CUTAWAY else BrollSegmentMode.TALKING_HEAD
            if (c.segmentMode == mode) c else c.copy(segmentMode = mode)
        }
        if (segments == session.brollSegments && clauses == session.clauses) return session
        return session.copy(clauses = clauses, brollSegments = segments)
    }

    private fun segmentFromClause(clause: CoreStoryClause): BrollSegment = withClauseSource(
        BrollSegment(
            startMs = clause.startMs,
            durationMs = clause.durationMs,
            originClauseId = clause.id,
            lockedToClause = true
        ),
        clause
    )

    private fun withClauseSource(seg: BrollSegment, clause: CoreStoryClause): BrollSegment = seg.copy(
        startMs = if (seg.lockedToClause) clause.startMs else seg.startMs,
        durationMs = if (seg.lockedToClause) clause.durationMs else seg.durationMs,
        caption = clause.brollCaption,
        prompt = clause.bRollPrompt,
        mediaId = clause.brollMediaId,
        sha = clause.bRollSha,
        customExt = clause.customBrollExt,
        previewUrl = clause.brollPreviewUrl,
        framingOverride = clause.brollFramingOverride,
        transitionType = clause.transitionType,
        transitionDurationMs = clause.transitionDurationMs
    )

    /** True when the segment covers exactly its sentence window (renderable as a sentence cutaway). */
    fun isSentenceAligned(session: CorePipelineSession, seg: BrollSegment): Boolean {
        val clause = session.clauses.firstOrNull { it.id == seg.originClauseId } ?: return false
        return clause.startMs == seg.startMs && clause.endMs == seg.endMs
    }

    /** Adds a [durationMs] cutaway starting at source [startMs] (clamped into the source). */
    fun addSegment(
        segments: List<BrollSegment>,
        startMs: Long,
        sourceDurationMs: Long,
        durationMs: Long = DEFAULT_SEGMENT_MS,
        prompt: String = ""
    ): List<BrollSegment> {
        if (sourceDurationMs < MIN_SEGMENT_MS) return segments
        val dur = durationMs.coerceIn(MIN_SEGMENT_MS, sourceDurationMs)
        val start = startMs.coerceIn(0L, sourceDurationMs - dur)
        return (segments + BrollSegment(startMs = start, durationMs = dur, prompt = prompt, caption = prompt))
            .sortedWith(compareBy({ it.startMs }, { it.id }))
    }

    /** Moves segment [id] to start at source [newStartMs], keeping its length inside the source. */
    fun moveSegment(segments: List<BrollSegment>, id: String, newStartMs: Long, sourceDurationMs: Long): List<BrollSegment> =
        segments.map { seg ->
            if (seg.id != id) seg else seg.copy(
                startMs = newStartMs.coerceIn(0L, (sourceDurationMs - seg.durationMs).coerceAtLeast(0L)),
                lockedToClause = false
            )
        }.sortedWith(compareBy({ it.startMs }, { it.id }))

    /** Sets segment [id] to [newDurationMs] (at least [MIN_SEGMENT_MS], ending inside the source). */
    fun resizeSegment(segments: List<BrollSegment>, id: String, newDurationMs: Long, sourceDurationMs: Long): List<BrollSegment> =
        segments.map { seg ->
            if (seg.id != id) seg else seg.copy(
                durationMs = newDurationMs.coerceIn(MIN_SEGMENT_MS, (sourceDurationMs - seg.startMs).coerceAtLeast(MIN_SEGMENT_MS)),
                lockedToClause = false
            )
        }

    fun removeSegment(segments: List<BrollSegment>, id: String): List<BrollSegment> = segments.filterNot { it.id == id }

    /** Removes segment [id]; a sentence-linked segment also clears that sentence's B-roll tag. */
    fun removeSegment(session: CorePipelineSession, id: String): CorePipelineSession {
        val seg = session.brollSegments.firstOrNull { it.id == id } ?: return session
        val clauses = if (seg.originClauseId == null) {
            session.clauses
        } else {
            session.clauses.map { if (it.id == seg.originClauseId) it.copy(segmentMode = BrollSegmentMode.TALKING_HEAD) else it }
        }
        return normalize(session.copy(clauses = clauses, brollSegments = removeSegment(session.brollSegments, id)))
    }

    private const val FULL_CLIP_ID = "cut_full"
}
