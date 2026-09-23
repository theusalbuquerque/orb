package com.music.orb.playback.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMixPlannerTest {

    private fun analysis(
        id: String,
        bpm: Double,
        key: String = "C major",
        beatConfidence: Double = 0.85,
        vocal: Double = 0.20,
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = id,
        duration = 220.0,
        bpm = bpm,
        beatInterval = if (bpm > 0.0) 60.0 / bpm else 0.0,
        beatConfidence = beatConfidence,
        key = key,
        keyConfidence = 0.80,
        introEndTime = 12.0,
        contentEndTime = 215.0,
        outroStartTime = 205.0,
        mixInTime = 12.0,
        mixOutTime = 205.0,
        energyCurve = listOf(
            EnergySample(12.0, 0.70),
            EnergySample(205.0, 0.70),
            EnergySample(215.0, 0.70),
        ),
        vocalProbability = vocal,
    )

    @Test
    fun `a clearly worse first transition is moved out of the way`() {
        val current = analysis("current", 120.0, "C major")
        val bad = analysis("bad", 170.0, "F♯ major")
        val good = analysis("good", 122.0, "G major")
        val good2 = analysis("good2", 124.0, "D major")

        val plan = SessionMixPlanner.plan(
            current,
            listOf(
                SessionMixPlanner.Candidate("bad", bad, 0),
                SessionMixPlanner.Candidate("good", good, 1),
                SessionMixPlanner.Candidate("good2", good2, 2),
            ),
        )

        requireNotNull(plan)
        assertTrue(plan.order.first() != "bad")
        assertEquals("bad", plan.order.last())
        assertTrue(plan.improvement >= SessionMixPlanner.MIN_IMPROVEMENT)
    }

    @Test
    fun `a tiny theoretical improvement does not reshuffle the queue`() {
        val current = analysis("current", 120.0, "C major")
        val first = analysis("first", 120.0, "C major")
        val second = analysis("second", 121.0, "G major")

        val plan = SessionMixPlanner.plan(
            current,
            listOf(
                SessionMixPlanner.Candidate("first", first, 0),
                SessionMixPlanner.Candidate("second", second, 1),
            ),
        )

        assertNull(plan)
    }

    @Test
    fun `missing analysis refuses to move anything`() {
        val current = analysis("current", 120.0)
        val ready = analysis("ready", 122.0)
        val unknown = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "unknown",
        )

        val plan = SessionMixPlanner.plan(
            current,
            listOf(
                SessionMixPlanner.Candidate("ready", ready, 0),
                SessionMixPlanner.Candidate("unknown", unknown, 1),
            ),
        )

        assertNull(plan)
    }

    @Test
    fun `tempo key and transition confidence affect pair quality`() {
        val current = analysis("current", 120.0, "C major")
        val compatible = analysis("compatible", 122.0, "G major")
        val difficult = analysis("difficult", 170.0, "F♯ major", beatConfidence = 0.30, vocal = 0.90)

        assertTrue(
            SessionMixPlanner.transitionScore(current, compatible) >
                    SessionMixPlanner.transitionScore(current, difficult),
        )
    }
}
