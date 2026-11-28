package com.example.data

/**
 * Which Stage 4 compositor a cut goes to, and when each attached graphic is on screen in it.
 *
 * Stage 4 uses the rig's Remotion worker unless the cut needs a feature only the FFmpeg studio cut
 * implements (see [needsFfmpegOnlyFeature]); `renderOptions.useRemotion` forces either one. The two
 * compositors time overlays slightly differently, so [spans] follows the one this cut will use:
 * - Remotion (`backend/remotion-worker/.../BroadcastTemplate.tsx` `pickActiveOverlay`): lower
 *   thirds and quote cards each get an equal slot of the clip and show for `durationSec` from the
 *   slot start; the last slot runs to the end. The chyron bar is up whenever the lower third is on.
 * - FFmpeg (`sync-trainer/home-daemon/studio_cut.py`): same slots, but a lone lower third fills the
 *   clip, others may run up to 2 s past their duration inside their slot, quote cards start 0.35 s
 *   into their slot, and the configured chyron only renders when no lower-third overlay is attached.
 * The corner bug runs the whole clip in both.
 */
object GfxLane {
    enum class Kind { CHYRON, BUG, OVERLAY }

    data class Span(
        val key: String,
        val kind: Kind,
        val label: String,
        val startMs: Long,
        val endMs: Long,
        val overlayId: String? = null
    )

    /** Same rule the Stage 4 coordinator applies before calling the rig. */
    fun needsFfmpegOnlyFeature(session: CorePipelineSession, look: NewsroomLook?): Boolean {
        val edited = CutTimeline.normalize(session)
        val activeClauses = edited.clauses.filter { it.spokenText.isNotBlank() }
        return CutTimeline.editRanges(edited) != null ||
            edited.brollSegments.any { !CutTimeline.isSentenceAligned(edited, it) } ||
            edited.renderOptions.stripSilence ||
            edited.renderOptions.defaultTransition != ClipTransitionType.CUT.wire ||
            activeClauses.any { it.transitionType != ClipTransitionType.CUT.wire } ||
            (look != null && (
                look.plateId != null || look.blurPercent != 0 || look.brightnessEv != 0.0 ||
                    look.light != NewsroomLook.LIGHT_NEUTRAL || look.grain || look.border
                ))
    }

    fun usesRemotion(session: CorePipelineSession, look: NewsroomLook?): Boolean =
        session.renderOptions.useRemotion ?: !needsFfmpegOnlyFeature(session, look)

    /**
     * Graphics on screen over a [totalMs]-long edited video: chyron, corner bug, then each attached
     * saved overlay (in the order the coordinator sends them: [savedOverlays] filtered by selection).
     */
    fun spans(
        session: CorePipelineSession,
        savedOverlays: List<SavedNewsroomOverlay>,
        look: NewsroomLook?,
        totalMs: Long
    ): List<Span> {
        if (totalMs <= 0L) return emptyList()
        val opts = session.renderOptions
        val selected = session.selectedOverlayIds.toSet()
        val attached = savedOverlays.filter { it.id in selected }
        val lower = attached.filter { it.kind != OverlayObjectKind.QUOTE_BRIEF }
        val quotes = attached.filter { it.kind == OverlayObjectKind.QUOTE_BRIEF }
        val remotion = usesRemotion(session, look)
        val out = mutableListOf<Span>()

        val chyronOn = if (remotion) {
            opts.showLowerThird
        } else {
            opts.showLowerThird && lower.isEmpty() && opts.chyronHeadline.isNotBlank()
        }
        if (chyronOn) out += Span("chyron", Kind.CHYRON, "chyron", 0L, totalMs)

        val bugOn = if (remotion) {
            val label = opts.cornerBugText.trim()
            opts.showBrandBug && (opts.breakingStyle != BreakingNewsStyle.CLEAN || (label.isNotEmpty() && !label.equals("LIVE", true)))
        } else {
            opts.showBrandBug
        }
        if (bugOn) out += Span("bug", Kind.BUG, opts.cornerBugText.ifBlank { "bug" }.lowercase(), 0L, totalMs)

        out += groupSpans(lower, totalMs, remotion, quote = false)
        out += groupSpans(quotes, totalMs, remotion, quote = true)
        return out
    }

    private fun groupSpans(items: List<SavedNewsroomOverlay>, totalMs: Long, remotion: Boolean, quote: Boolean): List<Span> {
        if (items.isEmpty()) return emptyList()
        val n = items.size
        val slot = totalMs / n
        return items.mapIndexed { idx, ovl ->
            val slotStart = idx * slot
            val slotEnd = if (idx == n - 1) totalMs else (idx + 1) * slot
            val (start, end) = if (remotion) {
                val durMs = ovl.durationSec.coerceIn(2, 20) * 1000L
                slotStart to minOf(slotEnd, slotStart + durMs)
            } else if (quote) {
                val durMs = ovl.durationSec.coerceIn(2, 20) * 1000L
                val s = minOf(slotStart + 350L, (totalMs - 500L).coerceAtLeast(0L))
                val ffSlotEnd = (idx + 1) * slot
                s to minOf(totalMs, minOf(ffSlotEnd, s + durMs)).coerceAtLeast(s + 250L)
            } else if (n == 1) {
                0L to totalMs
            } else {
                val durMs = ovl.durationSec.coerceIn(2, 30) * 1000L
                val ffSlotEnd = (idx + 1) * slot
                slotStart to minOf(totalMs, maxOf(slotStart + durMs, minOf(ffSlotEnd, slotStart + durMs + 2_000L)))
            }
            Span(
                key = "ovl_${ovl.id}",
                kind = Kind.OVERLAY,
                label = ovl.headline.ifBlank { ovl.kind.label }.lowercase(),
                startMs = start,
                endMs = end.coerceAtMost(totalMs),
                overlayId = ovl.id
            )
        }
    }
}
