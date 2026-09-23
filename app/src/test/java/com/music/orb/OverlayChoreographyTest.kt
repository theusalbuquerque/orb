package com.music.orb

import com.music.orb.playback.smart.EnergySample
import com.music.orb.playback.smart.TrackAnalysis
import com.music.orb.playback.smart.planOverlayChoreography
import org.junit.Assert.*
import org.junit.Test

class OverlayChoreographyTest {
    private fun analysis(vocal: (Double) -> Double) = TrackAnalysis(
        energyCurve = (0..400).map { EnergySample(it * 0.1, 0.5) },
        vocalActivityMask = (0..400).map { vocal(it * 0.1) },
    )

    @Test fun finalInstrumentalTailCanContinueUnderB() {
        val plan = planOverlayChoreography(
            analysis { if (it < 10.0) 1.0 else 0.0 }, analysis { 1.0 },
            0.0, 20.0, 1.0, { it },
        )
        assertTrue(plan.instrumentalTail)
        assertTrue(plan.vocalRelease < 0.7)
        assertTrue(plan.outgoingExit > plan.vocalReleaseEnd)
        assertTrue(plan.outgoingGain(plan.vocalReleaseEnd) > 0f)
        assertEquals(0f, plan.outgoingGain(1.0), 0.0001f)
    }

    @Test fun pauseBetweenVersesDoesNotReleaseB() {
        val plan = planOverlayChoreography(
            analysis { if (it in 5.0..9.0) 0.0 else 1.0 }, analysis { 1.0 },
            0.0, 20.0, 1.0, { it },
        )
        assertFalse(plan.instrumentalTail)
        assertTrue(plan.vocalRelease > 0.9)
        assertEquals(0f, plan.fullTrackAllowance(0.65), 0.0001f)
        assertEquals(0f, plan.outgoingGain(plan.vocalRelease), 0.0001f)
    }

    @Test fun missingEvidenceUsesProtectedHandoff() {
        val plan = planOverlayChoreography(
            TrackAnalysis(), TrackAnalysis(), 0.0, 20.0, 1.0, { it },
        )
        assertFalse(plan.instrumentalTail)
        assertTrue(plan.needsStems)
        assertEquals(0f, plan.fullTrackAllowance(0.5), 0.0001f)
        assertEquals(1f, plan.incomingGain(1.0), 0.0001f)
        assertEquals(1f, plan.fullTrackAllowance(1.0), 0.0001f)
    }

    @Test fun naturalInstrumentalBNeedsNoSeparation() {
        val plan = planOverlayChoreography(
            analysis { 1.0 }, analysis { 0.0 }, 0.0, 20.0, 1.0, { it },
        )
        assertFalse(plan.needsStems)
        assertEquals(1f, plan.fullTrackAllowance(0.4), 0.0001f)
    }

    @Test fun safetyUsesLoopedMediaPositionAndOutgoingRate() {
        val b = analysis { if (it < 5.0) 0.0 else 1.0 }
        val loop = planOverlayChoreography(
            analysis { 1.0 }, b, 0.0, 20.0, 1.1, { it % 4.0 },
        )
        val straight = planOverlayChoreography(
            analysis { 1.0 }, b, 0.0, 20.0, 1.1, { it },
        )
        assertEquals(1f, loop.fullTrackAllowance(0.5), 0.0001f)
        assertEquals(0f, straight.fullTrackAllowance(0.5), 0.0001f)
        for (i in 0..1000) {
            val p = i / 1000.0
            assertTrue(loop.incomingGain(p) in 0f..1f)
            assertTrue(loop.outgoingGain(p) in 0f..1f)
            assertTrue(loop.fullTrackAllowance(p) in 0f..1f)
        }
    }
}
