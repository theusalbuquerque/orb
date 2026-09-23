package com.music.orb

import com.music.orb.playback.smart.CrossfadeMode
import com.music.orb.playback.smart.EnergySample
import com.music.orb.playback.smart.TrackAnalysis
import com.music.orb.playback.smart.RemoteTransitionDirective
import com.music.orb.playback.smart.TransitionGainPoint
import com.music.orb.playback.smart.TransitionStyle
import com.music.orb.playback.smart.TransitionTrackInfo
import com.music.orb.playback.smart.planTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveAutomixStrategyTest {
    private fun curve(duration: Int, energy: (Double) -> Double): List<EnergySample> =
        (0..duration * 2).map { index ->
            val time = index * 0.5
            EnergySample(time, energy(time))
        }

    private fun outgoing(strongToEnd: Boolean): TrackAnalysis {
        val energy = curve(180) { time ->
            when {
                strongToEnd && time >= 155.0 -> 0.90
                time < 160.0 -> 0.70
                else -> 0.20
            }
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "a",
            duration = 180.0,
            bpm = 124.0,
            beatInterval = 60.0 / 124.0,
            beatConfidence = 0.88,
            downbeats = (0..100).map { it * 4.0 * 60.0 / 124.0 },
            phraseBoundaries = (0..30).map { it * 8.0 },
            contentEndTime = 180.0,
            mixOutTime = 166.0,
            outroStartTime = 164.0,
            energyCurve = energy,
            lowEnergyCurve = energy,
            vocalActivityMask = energy.map { point ->
                when {
                    strongToEnd && point.time >= 155.0 -> 0.82
                    point.time < 160.0 -> 0.68
                    else -> 0.08
                }
            },
        )
    }

    private fun assertiveIncoming(): TrackAnalysis {
        val energy = curve(180) { if (it < 20.0) 0.82 else 0.65 }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "b",
            duration = 180.0,
            bpm = 126.0,
            beatInterval = 60.0 / 126.0,
            beatConfidence = 0.88,
            downbeats = (0..100).map { it * 4.0 * 60.0 / 126.0 },
            phraseBoundaries = (0..30).map { it * 8.0 },
            audibleStartTime = 0.0,
            introEndTime = 2.0,
            mixInTime = 2.0,
            contentEndTime = 180.0,
            energyCurve = energy,
            lowEnergyCurve = energy,
            vocalActivityMask = energy.map { point -> if (point.time < 2.0) 0.15 else 0.72 },
        )
    }

    private fun instrumentalIncoming(introSeconds: Double = 18.0): TrackAnalysis {
        val energy = curve(180) { time -> if (time < introSeconds) 0.28 else 0.75 }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "bed",
            duration = 180.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.90,
            downbeats = (0..90).map { it * 2.0 },
            phraseBoundaries = (0..22).map { it * 8.0 },
            audibleStartTime = 0.0,
            introEndTime = introSeconds,
            mixInTime = introSeconds,
            contentEndTime = 180.0,
            energyCurve = energy,
            lowEnergyCurve = energy,
            vocalActivityMask = energy.map { point -> if (point.time < introSeconds) 0.03 else 0.85 },
        )
    }

    private fun plan(
        a: TrackAnalysis,
        b: TrackAnalysis,
        hint: TransitionStyle? = null,
    ) = planTransition(
        analysis = a,
        nextAnalysis = b,
        currentTrack = TransitionTrackInfo(a.trackId, 180_000L),
        nextTrack = TransitionTrackInfo(b.trackId, 180_000L),
        currentTime = 145.0,
        duration = 180.0,
        mode = CrossfadeMode.SMART,
        styleHint = hint,
    )

    @Test
    fun `assertive B can take foreground while releasable A becomes the tail`() {
        val plan = plan(outgoing(strongToEnd = false), assertiveIncoming())

        assertEquals(TransitionStyle.FOREGROUND_TAKEOVER, plan.transitionStyle)
        assertEquals(0.0, plan.incomingCueTime, 0.001)
        assertTrue(plan.handoffFraction < 0.40)
        val afterHandoff = plan.gainEnvelope.first { it.progress >= plan.handoffFraction + 0.15 }
        assertTrue(afterHandoff.incomingGain > afterHandoff.outgoingGain)
        assertTrue(afterHandoff.outgoingGain > 0.0)
    }

    @Test
    fun `strong vocal A is preserved to its natural end when B opens assertively`() {
        val plan = plan(outgoing(strongToEnd = true), assertiveIncoming())

        assertEquals(TransitionStyle.CUT, plan.transitionStyle)
        assertEquals(0.0, plan.incomingCueTime, 0.001)
        assertTrue(plan.transitionStart >= 179.0)
        assertTrue(plan.reason.contains("foreground-protected"))
    }

    @Test
    fun `strong vocal A may still host a compatible instrumental intro`() {
        val strong = outgoing(strongToEnd = true).copy(
            bpm = 120.0,
            beatInterval = 0.5,
            downbeats = (0..90).map { it * 2.0 },
        )
        val plan = plan(strong, instrumentalIncoming())

        assertEquals(TransitionStyle.INTRO_BED, plan.transitionStyle)
        assertEquals(0.0, plan.incomingCueTime, 0.001)
        assertTrue(plan.handoffFraction > 0.90)
    }

    @Test
    fun `remote style is only a prior and cannot force the wrong choreography`() {
        val plan = plan(
            outgoing(strongToEnd = false),
            assertiveIncoming(),
            hint = TransitionStyle.INTRO_BED,
        )

        assertEquals(TransitionStyle.FOREGROUND_TAKEOVER, plan.transitionStyle)
    }
    @Test
    fun `valid v3 remote directive becomes the authoritative recipe`() {
        val a = outgoing(strongToEnd = true).copy(bpm = 120.0, beatInterval = 0.5)
        val b = instrumentalIncoming(introSeconds = 45.0)
        val remote = RemoteTransitionDirective(
            style = TransitionStyle.INTRO_BED,
            confidence = 0.92,
            reason = "server-intro-underlay",
            transitionStart = 135.0,
            transitionEnd = 180.0,
            incomingCueTime = 0.0,
            incomingHandoffTime = 45.0,
            outgoingPlaybackRate = 1.0,
            incomingPlaybackRate = 1.0,
            handoffFraction = 0.96,
            bassSwap = true,
            bassSwapFraction = 0.96,
            filterSweep = 0.0,
            gainEnvelope = listOf(
                TransitionGainPoint(0.0, 0.0, 1.0),
                TransitionGainPoint(0.90, 0.18, 1.0),
                TransitionGainPoint(1.0, 1.0, 0.0),
            ),
            protectedOutgoing = true,
            planner = "orb-adaptive-dj-v3",
        )

        val plan = planTransition(
            analysis = a,
            nextAnalysis = b,
            currentTrack = TransitionTrackInfo(a.trackId, 180_000L),
            nextTrack = TransitionTrackInfo(b.trackId, 180_000L),
            currentTime = 120.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            remoteDirective = remote,
        )

        assertEquals(TransitionStyle.INTRO_BED, plan.transitionStyle)
        assertEquals(135.0, plan.transitionStart, 0.001)
        assertEquals(45.0, plan.incomingHandoffTime, 0.001)
        assertTrue(plan.reason.contains("remote-v4"))
    }

    @Test
    fun `low confidence remote directive falls back to adaptive local planner`() {
        val a = outgoing(strongToEnd = false)
        val b = assertiveIncoming()
        val remote = RemoteTransitionDirective(
            style = TransitionStyle.INTRO_BED,
            confidence = 0.20,
            reason = "weak-server-plan",
            transitionStart = 130.0,
            transitionEnd = 180.0,
            incomingCueTime = 0.0,
            incomingHandoffTime = 30.0,
            outgoingPlaybackRate = 1.0,
            incomingPlaybackRate = 1.0,
            handoffFraction = 0.90,
            bassSwap = false,
            bassSwapFraction = 0.70,
            filterSweep = 0.0,
            gainEnvelope = emptyList(),
        )

        val plan = planTransition(
            analysis = a,
            nextAnalysis = b,
            currentTrack = TransitionTrackInfo(a.trackId, 180_000L),
            nextTrack = TransitionTrackInfo(b.trackId, 180_000L),
            currentTime = 145.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            remoteDirective = remote,
        )

        assertEquals(TransitionStyle.FOREGROUND_TAKEOVER, plan.transitionStyle)
        assertTrue(!plan.reason.contains("remote-v4"))
    }

    @Test
    fun `remote intro plan cannot skip a soft musical head or release A on a breath`() {
        val baseA = outgoing(strongToEnd = true).copy(
            bpm = 120.0,
            beatInterval = 0.5,
            duration = 180.0,
            contentEndTime = 180.0,
        )
        val aEnergy = curve(180) { time ->
            when {
                time in 166.0..169.0 -> 0.30 // brief pocket / breath
                time >= 169.5 -> 0.88        // final phrase returns strongly
                else -> 0.72
            }
        }
        val a = baseA.copy(
            energyCurve = aEnergy,
            lowEnergyCurve = aEnergy,
            vocalActivityMask = aEnergy.map { point ->
                when {
                    point.time in 166.0..169.0 -> 0.12
                    point.time >= 169.5 -> 0.82
                    else -> 0.68
                }
            },
        )
        val b = instrumentalIncoming(introSeconds = 56.0).copy(audibleStartTime = 30.0)
        val remote = RemoteTransitionDirective(
            style = TransitionStyle.INTRO_BED,
            confidence = 0.96,
            reason = "stale-v2-like-plan",
            transitionStart = 150.0,
            transitionEnd = 180.0,
            incomingCueTime = 30.0,
            incomingHandoffTime = 56.0,
            outgoingPlaybackRate = 1.0,
            incomingPlaybackRate = 1.0,
            handoffFraction = 0.60,
            bassSwap = true,
            bassSwapFraction = 0.70,
            filterSweep = 0.0,
            gainEnvelope = emptyList(),
            protectedOutgoing = false,
            outgoingReleaseTime = 168.0,
            planner = "orb-adaptive-dj-v2",
        )

        val plan = planTransition(
            analysis = a,
            nextAnalysis = b,
            currentTrack = TransitionTrackInfo("a", 180_000L),
            nextTrack = TransitionTrackInfo("b", 180_000L),
            currentTime = 118.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            remoteDirective = remote,
        )

        assertEquals(TransitionStyle.INTRO_BED, plan.transitionStyle)
        assertEquals(0.0, plan.incomingCueTime, 0.001)
        assertTrue(!plan.reason.contains("remote-v4"))
        assertTrue(plan.handoffFraction > 0.90)
    }

    @Test
    fun `remote intro plan cannot ignore an earlier reported impact`() {
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
        val badRemote = RemoteTransitionDirective(
            style = TransitionStyle.INTRO_BED,
            confidence = 0.97,
            reason = "server-v3-first-vocal-bug",
            transitionStart = 192.0,
            transitionEnd = 260.0,
            incomingCueTime = 0.0,
            incomingHandoffTime = 68.0,
            outgoingPlaybackRate = 1.0,
            incomingPlaybackRate = 1.0,
            handoffFraction = 0.82,
            bassSwap = true,
            bassSwapFraction = 0.86,
            filterSweep = 0.0,
            gainEnvelope = emptyList(),
            protectedOutgoing = false,
            outgoingReleaseTime = 248.0,
            incomingImpactTime = 56.0,
            planner = "orb-adaptive-dj-v3",
        )

        val plan = planTransition(
            analysis = a,
            nextAnalysis = b,
            currentTrack = TransitionTrackInfo("heated", 260_000L),
            nextTrack = TransitionTrackInfo("dance", 200_000L),
            currentTime = 180.0,
            duration = 260.0,
            mode = CrossfadeMode.SMART,
            remoteDirective = badRemote,
        )

        assertEquals(TransitionStyle.INTRO_BED, plan.transitionStyle)
        assertTrue(!plan.reason.contains("remote-v4"))
        assertEquals(56.0, plan.incomingHandoffTime, 1.0)
        assertEquals(204.0, plan.transitionStart, 1.5)
    }

}
