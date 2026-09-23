package com.music.orb

import com.music.orb.playback.smart.CrossfadeMode
import com.music.orb.playback.smart.EnergySample
import com.music.orb.playback.smart.TrackAnalysis
import com.music.orb.playback.smart.TransitionStyle
import com.music.orb.playback.smart.TransitionTrackInfo
import com.music.orb.playback.smart.planTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartInstrumentalOverlayPlannerTest {
    private fun curve(duration: Int, energy: (Double) -> Double): List<EnergySample> =
        (0..duration * 2).map { index ->
            val time = index * 0.5
            EnergySample(time, energy(time))
        }

    private fun outgoing(): TrackAnalysis {
        val energy = curve(200) { time -> if (time >= 190.0) 0.08 else 0.55 }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "a",
            duration = 200.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.90,
            downbeats = (0..100).map { it * 2.0 },
            phraseBoundaries = (0..25).map { it * 8.0 },
            contentEndTime = 200.0,
            mixOutTime = 190.0,
            outroStartTime = 190.0,
            energyCurve = energy,
            lowEnergyCurve = energy,
            vocalActivityMask = energy.map { point -> if (point.time < 188.0) 0.78 else 0.05 },
        )
    }

    private fun incoming(introSeconds: Double): TrackAnalysis {
        val energy = curve(200) { time -> if (time < introSeconds) 0.30 else 0.62 }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "b",
            duration = 200.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.90,
            downbeats = (0..100).map { it * 2.0 },
            phraseBoundaries = (0..25).map { it * 8.0 },
            audibleStartTime = 0.0,
            introEndTime = introSeconds,
            mixInTime = introSeconds,
            contentEndTime = 200.0,
            energyCurve = energy,
            lowEnergyCurve = energy,
            vocalActivityMask = energy.map { point -> if (point.time < introSeconds) 0.03 else 0.85 },
        )
    }

    private fun plan(now: Double, introSeconds: Double) = planTransition(
        analysis = outgoing(),
        nextAnalysis = incoming(introSeconds),
        currentTrack = TransitionTrackInfo("a", 200_000L),
        nextTrack = TransitionTrackInfo("b", 200_000L),
        currentTime = now,
        duration = 200.0,
        mode = CrossfadeMode.SMART,
        styleHint = TransitionStyle.INTRO_BED,
    )

    @Test
    fun `instrumental overlay keeps the intro instead of seeking to chorus`() {
        val plan = plan(now = 150.0, introSeconds = 30.0)

        assertEquals(TransitionStyle.INTRO_BED, plan.transitionStyle)
        assertEquals(0.0, plan.incomingCueTime, 0.001)
        assertTrue(plan.incomingHandoffTime <= 30.0)
        assertTrue(plan.transitionStart < 170.0)
    }

    @Test
    fun `elastic intro changes tempo before it sacrifices the incoming arrangement`() {
        val plan = plan(now = 165.0, introSeconds = 27.0)

        assertEquals(TransitionStyle.INTRO_BED, plan.transitionStyle)
        assertEquals(0.0, plan.incomingCueTime, 0.001)
        assertTrue(plan.incomingPlaybackRate > 1.0)
        assertTrue(plan.incomingPlaybackRate <= 1.07)
        assertTrue(plan.reason.contains("elastic-intro"))
    }

    @Test
    fun `late analysis falls back without jumping into the middle of B`() {
        val plan = plan(now = 170.0, introSeconds = 30.0)

        assertEquals(TransitionStyle.CUT, plan.transitionStyle)
        assertEquals(0.0, plan.incomingCueTime, 0.001)
        assertTrue(plan.reason.contains("intro-preserving-fallback"))
    }

    @Test
    fun `A keeps a structural tail after B becomes foreground`() {
        val plan = plan(now = 150.0, introSeconds = 30.0)
        val afterHandoff = plan.gainEnvelope.first { point ->
            point.progress >= plan.handoffFraction + 0.08
        }

        assertTrue(afterHandoff.incomingGain > 0.95)
        assertTrue(afterHandoff.outgoingGain > 0.20)
        assertTrue(afterHandoff.outgoingGain < 0.70)
    }
    @Test
    fun `quiet intro is preserved even when audible-start detector fires late`() {
        val b = incoming(56.0).copy(audibleStartTime = 30.0)
        val plan = planTransition(
            analysis = outgoing(),
            nextAnalysis = b,
            currentTrack = TransitionTrackInfo("a", 200_000L),
            nextTrack = TransitionTrackInfo("b", 200_000L),
            currentTime = 130.0,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
            styleHint = TransitionStyle.INTRO_BED,
        )

        assertEquals(TransitionStyle.INTRO_BED, plan.transitionStyle)
        assertEquals(0.0, plan.incomingCueTime, 0.001)
        assertTrue(plan.incomingHandoffTime >= 50.0)
    }

    @Test
    fun `HEATED to DANCE aligns the intro impact to A natural end`() {
        val aEnergy = curve(260) { time ->
            when {
                time in 248.0..251.5 -> 0.34
                time >= 252.0 -> 0.86
                else -> 0.72
            }
        }
        val a = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "heated",
            duration = 260.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.92,
            downbeats = (0..130).map { it * 2.0 },
            phraseBoundaries = (0..32).map { it * 8.0 },
            contentEndTime = 260.0,
            mixOutTime = 192.0,
            outroStartTime = 192.0,
            energyCurve = aEnergy,
            lowEnergyCurve = aEnergy,
            vocalActivityMask = aEnergy.map { point ->
                when {
                    point.time in 248.0..251.5 -> 0.14
                    point.time >= 252.0 -> 0.66
                    else -> 0.62
                }
            },
        )

        val bEnergy = curve(200) { time -> if (time < 56.0) 0.22 else 0.78 }
        val b = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "dance",
            duration = 200.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.92,
            downbeats = (0..100).map { it * 2.0 },
            phraseBoundaries = (0..25).map { it * 8.0 },
            audibleStartTime = 0.0,
            introEndTime = 56.0,
            mixInTime = 56.0,
            contentEndTime = 200.0,
            energyCurve = bEnergy,
            lowEnergyCurve = bEnergy,
            vocalActivityMask = bEnergy.map { point -> if (point.time < 68.0) 0.03 else 0.82 },
        )

        val plan = planTransition(
            analysis = a,
            nextAnalysis = b,
            currentTrack = TransitionTrackInfo("heated", 260_000L),
            nextTrack = TransitionTrackInfo("dance", 200_000L),
            currentTime = 180.0,
            duration = 260.0,
            mode = CrossfadeMode.SMART,
            styleHint = TransitionStyle.INTRO_BED,
        )

        assertEquals(TransitionStyle.INTRO_BED, plan.transitionStyle)
        assertEquals(0.0, plan.incomingCueTime, 0.001)
        assertEquals(56.0, plan.incomingHandoffTime, 1.0)
        assertEquals(204.0, plan.transitionStart, 1.5)
        assertEquals(260.0, plan.transitionEnd, 0.25)
        assertTrue(plan.handoffFraction > 0.97)
    }

}
