package com.example.graph

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseFitTest {

    @Test
    fun fitIsUnknownWithoutAreasOrText() {
        assertNull(PulseFitScorer.fit("nvidia earnings beat", emptyList()))
        assertNull(PulseFitScorer.fit("  ", listOf("AI chips")))
        assertNull(PulseFitScorer.fit("nvidia earnings", listOf(" ", "")))
    }

    @Test
    fun fitPicksTheBestMatchingArea() {
        val fit = PulseFitScorer.fit("new rust compiler release speeds up builds", listOf("hockey trades", "rust compiler"))
        assertNotNull(fit)
        assertEquals("rust compiler", fit!!.bestArea)
        assertTrue(fit.score in 1..100)
        val off = PulseFitScorer.fit("new rust compiler release speeds up builds", listOf("hockey trades"))!!
        assertTrue(off.score < fit.score)
    }

    @Test
    fun rankIsAmongSavedIdeasByFitAndUnknownWhenAlone() {
        val areas = listOf("rust compiler")
        assertNull(PulseFitScorer.rank("rust compiler speedups", emptyList(), areas))
        assertNull(PulseFitScorer.rank("rust compiler speedups", listOf("rust compiler speedups"), areas))
        assertNull(PulseFitScorer.rank("rust compiler speedups", listOf("hockey"), emptyList()))
        val rank = PulseFitScorer.rank("rust compiler speedups", listOf("hockey trade deadline", "", "baking bread"), areas)!!
        assertEquals(PulseRank(position = 1, of = 3), rank)
        val low = PulseFitScorer.rank("baking bread", listOf("rust compiler speedups"), areas)!!
        assertEquals(2, low.position)
    }

    @Test
    fun heatLabelAndCorrection() {
        assertNull(PulseFitScorer.heatLabel(null))
        assertEquals("+40%", PulseFitScorer.heatLabel(1.4))
        assertEquals("-50%", PulseFitScorer.heatLabel(0.5))
        assertEquals(
            "Jensen Huang got caught",
            PulseFitScorer.applyCorrection("jhensen wang got caught", "jhensen wang", "Jensen Huang")
        )
    }

    @Test
    fun archiveMatchFillsOnlyUnknownXHandlesWithOneExactNameMatch() {
        val result = NameDropResult(
            people = listOf(
                NameDropPerson(name = "Ada Lovelace", handles = mapOf("x" to NameDropHandle(status = "unknown"))),
                NameDropPerson(name = "Alan Turing", handles = mapOf("x" to NameDropHandle("@real_alan", "verified", "orcid"))),
                NameDropPerson(name = "Grace Hopper", handles = emptyMap())
            )
        )
        val contacts = listOf(
            GraphContactRecord("ada_l", "Ada Lovelace", "", 10, 3, 0L),
            GraphContactRecord("fake_alan", "Alan Turing", "", 10, 0, 0L),
            GraphContactRecord("gh1", "Grace Hopper", "", 1, 0, 0L),
            GraphContactRecord("gh2", "Grace Hopper", "", 1, 0, 0L)
        )
        val merged = NameDropArchiveMatcher.merge(result, contacts)
        assertEquals(NameDropHandle("@ada_l", "in_archive", "same name in your X archive (3 replies between you)"), merged.people[0].handles["x"])
        assertEquals("@real_alan", merged.people[1].handles["x"]!!.handle)
        assertNull(merged.people[2].handles["x"])
    }

    @Test
    fun pulseScoreParsesRigJsonWithUnknownHeat() {
        val raw = """
            {"text":"t","field":"note","query":"q","entities":[{"name":"jhensen wang","corrected_name":"Jensen Huang","kind":"person"}],
             "claims":[{"text":"caught cheating","about":"Jensen Huang","kind":"allegation","query":"q","status":"unsourced","sources":[],"detail":"d"}],
             "boosts_held":true,"held_reason":"Unsourced claim","heat":{"ratio":null,"sources":[{"name":"news","ok":false,"count_24h":null,"avg_7d":null,"detail":"GDELT HTTP 429"}]},
             "boosts":[{"kind":"reframe","text":"r","why":"w"}],"analysis_via":"local_mlx/m","boosts_via":"x","areas":[],"checked_at":"z"}
        """.trimIndent()
        val score = Json { ignoreUnknownKeys = true }.decodeFromString(PulseScore.serializer(), raw)
        assertTrue(score.boostsHeld)
        assertNull(score.heat.ratio)
        assertNull(score.heat.sources.single().count24h)
        assertEquals("Jensen Huang", score.entities.single().correctedName)
    }
}
