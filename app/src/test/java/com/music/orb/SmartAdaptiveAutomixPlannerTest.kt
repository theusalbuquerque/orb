package com.music.orb

import com.music.orb.playback.smart.CrossfadeMode
import com.music.orb.playback.smart.EnergySample
import com.music.orb.playback.smart.RemoteTransitionDirective
import com.music.orb.playback.smart.TrackAnalysis
import com.music.orb.playback.smart.TransitionStyle
import com.music.orb.playback.smart.TransitionTrackInfo
import com.music.orb.playback.smart.planTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAdaptiveAutomixPlannerTest {
    private fun curve(duration: Int, block: (Double) -> Double): List<EnergySample> =
        (0..duration * 2).map { index ->
            val time = index * 0.5
            EnergySample(time, block(time))
        }

    private fun heatedLike(): TrackAnalysis {
        val energy = curve(260) { time ->
            when {
                time in 248.0..251.5 -> 0.34 // a breath / small pocket, not the end
                time >= 252.0 -> 0.86        // final phrase returns strongly
                else -> 0.72
            }
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "heated",
            duration = 260.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.92,
            downbeats = (0..130).map { it * 2.0 },
            phraseBoundaries = (0..32).map { it * 8.0 },
            contentEndTime = 260.0,
            mixOutTime = 192.0, // regression anchor: 3:12 must NOT dictate the transition
            outroStartTime = 192.0,
            energyCurve = energy,
            lowEnergyCurve = energy,
            vocalActivityMask = energy.map { point ->
                when {
                    point.time in 248.0..251.5 -> 0.14
                    point.time >= 252.0 -> 0.66
                    else -> 0.62
                }
            },
        )
    }

    private fun danceLike(): TrackAnalysis {
        val energy = curve(200) { time -> if (time < 56.0) 0.22 else 0.78 }
        return TrackAnalysis(
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
            mixInTime = 68.0, // first-vocal-like landmark occurs after the actual intro impact
            contentEndTime = 200.0,
            energyCurve = energy,
            lowEnergyCurve = energy,
            vocalActivityMask = energy.map { point -> if (point.time < 68.0) 0.03 else 0.82 },
        )
    }

    @Test
    fun `HEATED to DANCE preserves A and aligns DANCE impact to natural end`() {
        val plan = planTransition(
            analysis = heatedLike(),
            nextAnalysis = danceLike(),
            currentTrack = TransitionTrackInfo("heated", 260_000L),
            nextTrack = TransitionTrackInfo("dance", 200_000L),
            currentTime = 180.0,
            duration = 260.0,
            mode = CrossfadeMode.SMART,
        )

        assertEquals(TransitionStyle.INTRO_BED, plan.transitionStyle)
        assertEquals(0.0, plan.incomingCueTime, 0.001)
        assertEquals(56.0, plan.incomingHandoffTime, 1.0)
        assertEquals(204.0, plan.transitionStart, 1.5) // 4:20 - 0:56 = 3:24
        assertEquals(260.0, plan.transitionEnd, 0.25)
        assertTrue(plan.handoffFraction > 0.97)
        assertTrue(plan.transitionStart > 192.0)

        // INTRO_BED must be an audible progressive entrance, not a quiet pre-roll followed by a cut.
        assertTrue(plan.gainEnvelope.isNotEmpty())
        val build = plan.gainEnvelope.minByOrNull { kotlin.math.abs(it.progress - 0.75) }!!
        val impactPrep = plan.gainEnvelope.minByOrNull { kotlin.math.abs(it.progress - 0.90) }!!
        assertTrue(build.incomingGain >= 0.24)
        assertTrue(impactPrep.incomingGain >= 0.38)
        assertTrue(impactPrep.outgoingGain >= 0.90)
        assertEquals(1.0, plan.gainEnvelope.last().incomingGain, 0.0001)
        assertEquals(0.0, plan.gainEnvelope.last().outgoingGain, 0.0001)
    }

    @Test
    fun `bad remote first-vocal recipe is rejected and locally rebuilt`() {
        val badRemote = RemoteTransitionDirective(
            style = TransitionStyle.INTRO_BED,
            confidence = 0.95,
            reason = "first-vocal-regression",
            transitionStart = 192.0,
            transitionEnd = 260.0,
            incomingCueTime = 0.0,
            incomingHandoffTime = 68.0,
            outgoingPlaybackRate = 1.0,
            incomingPlaybackRate = 1.0,
            handoffFraction = 0.95,
            bassSwap = true,
            bassSwapFraction = 0.90,
            filterSweep = 0.0,
            gainEnvelope = emptyList(),
            outgoingReleaseTime = 248.0,
            incomingImpactTime = 56.0,
            planner = "regression",
        )
        val plan = planTransition(
            analysis = heatedLike(),
            nextAnalysis = danceLike(),
            currentTrack = TransitionTrackInfo("heated", 260_000L),
            nextTrack = TransitionTrackInfo("dance", 200_000L),
            currentTime = 180.0,
            duration = 260.0,
            mode = CrossfadeMode.SMART,
            styleHint = badRemote.style,
            remoteDirective = badRemote,
        )

        assertFalse(plan.reason.contains("remote-v4"))
        assertEquals(204.0, plan.transitionStart, 1.5)
        assertEquals(56.0, plan.incomingHandoffTime, 1.0)
    }
}
