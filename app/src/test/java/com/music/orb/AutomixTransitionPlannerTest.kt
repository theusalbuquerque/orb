package com.music.orb

import com.music.orb.playback.automix.AutomixAnalysis
import com.music.orb.playback.automix.AutomixTrack
import com.music.orb.playback.automix.AutomixTransitionPlanner
import com.music.orb.playback.automix.TransitionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixTransitionPlannerTest {
    private fun analysis(
        id: String,
        bpm: Float,
        key: Int,
        minor: Boolean,
        confidence: Float = 0.9f,
        energy: Float = 0.7f,
    ) = AutomixAnalysis(
        mediaId = id,
        analyzedAtMs = 0L,
        analyzedDurationMs = 30_000L,
        tempoBpm = bpm,
        tempoConfidence = confidence,
        firstBeatMs = 100L,
        beatIntervalMs = 60_000f / bpm,
        beatsMs = emptyList(),
        barsMs = emptyList(),
        keyPitchClass = key,
        minorMode = minor,
        keyConfidence = confidence,
        loudnessCurve = List(16) { energy },
        energyCurve = List(16) { energy },
        introCandidatesMs = listOf(100L, 2_000L),
    )

    @Test
    fun `close tempo and compatible key create a beat matched blend`() {
        val plan = AutomixTransitionPlanner.plan(
            AutomixTrack("a", 180_000L, analysis("a", 124f, 9, true)),
            AutomixTrack("b", 180_000L, analysis("b", 126f, 4, true)),
        )
        assertEquals(TransitionStyle.BLEND, plan.style)
        assertTrue(plan.incomingPlaybackRate in 0.96f..1.04f)
        assertTrue(plan.incomingPlaybackRate != 1f)
    }

    @Test
    fun `large tempo mismatch refuses time stretching`() {
        val plan = AutomixTransitionPlanner.plan(
            AutomixTrack("a", 180_000L, analysis("a", 82f, 0, false)),
            AutomixTrack("b", 180_000L, analysis("b", 128f, 6, true)),
        )
        assertEquals(1f, plan.incomingPlaybackRate)
        assertTrue(plan.style != TransitionStyle.BLEND)
    }

    @Test
    fun `planner never moves queue tracks and only describes the supplied pair`() {
        val plan = AutomixTransitionPlanner.plan(
            AutomixTrack("fixed-a", 240_000L, null),
            AutomixTrack("fixed-b", 220_000L, null),
        )
        assertEquals("fixed-a", plan.outgoingMediaId)
        assertEquals("fixed-b", plan.incomingMediaId)
    }

    @Test
    fun `candidate matrix prefers low vocal structural boundaries`() {
        val outgoingBase = analysis("a", 124f, 9, true)
        val incomingBase = analysis("b", 126f, 9, true)
        val outgoing = outgoingBase.copy(
            analyzedDurationMs = 180_000L,
            completeAnalysis = true,
            vocalProbabilityCurve = List(360) { index -> if (index >= 320) 0.05f else 0.85f },
            energyCurve = List(360) { index -> if (index >= 320) 0.45f else 0.68f },
            outroCandidatesMs = listOf(160_000L, 168_000L, 172_000L),
            phrases = listOf(
                com.music.orb.playback.automix.AutomixPhrase(0L, 16_000L, 0.7f, 0.8f),
                com.music.orb.playback.automix.AutomixPhrase(160_000L, 176_000L, 0.45f, 0.05f),
            ),
        )
        val incoming = incomingBase.copy(
            analyzedDurationMs = 180_000L,
            completeAnalysis = true,
            vocalProbabilityCurve = List(360) { index -> if (index < 40) 0.05f else 0.8f },
            energyCurve = List(360) { index -> if (index < 24) 0.48f else 0.72f },
            introCandidatesMs = listOf(0L, 8_000L),
            dropCandidatesMs = listOf(12_000L),
        )
        val plan = AutomixTransitionPlanner.plan(
            AutomixTrack("a", 180_000L, outgoing),
            AutomixTrack("b", 180_000L, incoming),
        )
        assertTrue(plan.candidateCount > 1)
        assertTrue(plan.vocalOverlapRisk < 0.20f)
        assertTrue(plan.musicalScore > 0.60f)
        assertTrue(plan.outgoingStartMs >= 160_000L)
    }

    @Test
    fun `global score exposes energy and musical quality diagnostics`() {
        val plan = AutomixTransitionPlanner.plan(
            AutomixTrack("a", 180_000L, analysis("a", 120f, 0, false)),
            AutomixTrack("b", 180_000L, analysis("b", 121f, 7, false)),
        )
        assertTrue(plan.energyCompatibility in 0f..1f)
        assertTrue(plan.musicalScore in 0f..1f)
        assertTrue(plan.candidateCount >= 1)
    }

}
