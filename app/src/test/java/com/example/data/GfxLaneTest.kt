package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GfxLaneTest {
    private fun ovl(id: String, kind: OverlayObjectKind, sec: Int) =
        SavedNewsroomOverlay(id = id, kind = kind, headline = id, subline = "", durationSec = sec, a2uiComponentsJson = "")

    private fun session(opts: CoreRenderOptions, selected: List<String>, transition: String = "cut") = CorePipelineSession(
        id = "pipe_g",
        clauses = listOf(CoreStoryClause(id = "a", spokenText = "One.", startMs = 0, endMs = 12_000, transitionType = transition)),
        ttsDurationMs = 12_000L,
        renderOptions = opts,
        selectedOverlayIds = selected
    )

    private val overlays = listOf(
        ovl("stat", OverlayObjectKind.STAT_CALLOUT, 4),
        ovl("quote", OverlayObjectKind.QUOTE_BRIEF, 3),
        ovl("unused", OverlayObjectKind.LOWER_THIRD, 5)
    )

    @Test
    fun plainCut_usesRemotionTiming() {
        val s = session(CoreRenderOptions(showLowerThird = true, chyronHeadline = "Headline", showBrandBug = true), listOf("stat", "quote"))
        assertTrue(GfxLane.usesRemotion(s, null))
        val spans = GfxLane.spans(s, overlays, null, 12_000L).associateBy { it.key }
        assertEquals(0L to 12_000L, spans.getValue("chyron").let { it.startMs to it.endMs })
        assertEquals(0L to 12_000L, spans.getValue("bug").let { it.startMs to it.endMs })
        // One lower and one quote: each owns the whole clip as its slot and shows for its duration.
        assertEquals(0L to 4_000L, spans.getValue("ovl_stat").let { it.startMs to it.endMs })
        assertEquals(0L to 3_000L, spans.getValue("ovl_quote").let { it.startMs to it.endMs })
        assertFalse(spans.containsKey("ovl_unused"))
    }

    @Test
    fun transitionCut_usesFfmpegTiming() {
        val s = session(CoreRenderOptions(showLowerThird = true, chyronHeadline = "Headline"), listOf("stat", "quote"), transition = "fade")
        assertFalse(GfxLane.usesRemotion(s, null))
        val spans = GfxLane.spans(s, overlays, null, 12_000L).associateBy { it.key }
        // FFmpeg skips the configured chyron when a lower-third overlay is attached; a lone one fills the clip.
        assertFalse(spans.containsKey("chyron"))
        assertEquals(0L to 12_000L, spans.getValue("ovl_stat").let { it.startMs to it.endMs })
        assertEquals(350L to 3_350L, spans.getValue("ovl_quote").let { it.startMs to it.endMs })
    }

    @Test
    fun remotionSlots_splitTheClip() {
        val two = listOf(ovl("q1", OverlayObjectKind.QUOTE_BRIEF, 8), ovl("q2", OverlayObjectKind.QUOTE_BRIEF, 3))
        val s = session(CoreRenderOptions(showLowerThird = false), listOf("q1", "q2"))
        val spans = GfxLane.spans(s, two, null, 10_000L).associateBy { it.key }
        assertEquals(0L to 5_000L, spans.getValue("ovl_q1").let { it.startMs to it.endMs })
        assertEquals(5_000L to 8_000L, spans.getValue("ovl_q2").let { it.startMs to it.endMs })
        assertFalse(spans.containsKey("chyron"))
    }

    @Test
    fun lookGradeForcesFfmpeg() {
        val s = session(CoreRenderOptions(), emptyList())
        assertTrue(GfxLane.usesRemotion(s, NewsroomLook(name = "plain")))
        assertFalse(GfxLane.usesRemotion(s, NewsroomLook(name = "blurred", blurPercent = 10)))
        assertTrue(GfxLane.usesRemotion(s.copy(renderOptions = CoreRenderOptions(useRemotion = true)), NewsroomLook(name = "b", grain = true)))
    }
}
