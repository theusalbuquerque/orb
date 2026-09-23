package com.music.orb

import com.music.orb.playback.automix.AutomixTransitionRenderer
import com.music.orb.playback.automix.TransitionPlan
import com.music.orb.playback.automix.TransitionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixTransitionRendererTest {
    private fun plan(style: TransitionStyle, rate: Float = 0.984f) = TransitionPlan(
        outgoingMediaId = "a",
        incomingMediaId = "b",
        style = style,
        outgoingStartMs = 100_000L,
        outgoingEndMs = 112_000L,
        incomingCueMs = 8_000L,
        durationMs = 12_000L,
        incomingPlaybackRate = rate,
        phaseOffsetMs = 0L,
        tempoCompatibility = 0.9f,
        harmonicCompatibility = 0.9f,
        confidence = 0.9f,
    )

    @Test
    fun `blend swaps bass and preserves equal-power style overlap`() {
        val early = AutomixTransitionRenderer.render(plan(TransitionStyle.BLEND), 0.15f)
        val late = AutomixTransitionRenderer.render(plan(TransitionStyle.BLEND), 0.85f)
        assertTrue(early.outgoing.low > early.incoming.low)
        assertTrue(late.outgoing.low < late.incoming.low)
        assertTrue(early.outgoing.gain > early.incoming.gain)
        assertTrue(late.outgoing.gain < late.incoming.gain)
    }

    @Test
    fun `rise performs a real outgoing high pass sweep and opens incoming low pass`() {
        val early = AutomixTransitionRenderer.render(plan(TransitionStyle.RISE), 0.15f)
        val late = AutomixTransitionRenderer.render(plan(TransitionStyle.RISE), 0.85f)
        assertTrue(late.outgoing.highPassHz > early.outgoing.highPassHz)
        assertTrue(late.incoming.lowPassHz > early.incoming.lowPassHz)
        assertTrue(late.incoming.low > early.incoming.low)
    }

    @Test
    fun `cut is a short handoff rather than equal power blend`() {
        val before = AutomixTransitionRenderer.render(plan(TransitionStyle.CUT, 1f), 0.20f)
        val after = AutomixTransitionRenderer.render(plan(TransitionStyle.CUT, 1f), 0.70f)
        assertTrue(before.outgoing.gain > 0.95f)
        assertTrue(before.incoming.gain < 0.05f)
        assertTrue(after.outgoing.gain < 0.10f)
        assertTrue(after.incoming.gain > 0.90f)
    }

    @Test
    fun `tempo release converges to user speed by end of transition`() {
        val start = AutomixTransitionRenderer.render(plan(TransitionStyle.BLEND, 0.984f), 0.5f)
        val end = AutomixTransitionRenderer.render(plan(TransitionStyle.BLEND, 0.984f), 1f)
        assertEquals(0.984f, start.incoming.playbackRate, 0.0001f)
        assertEquals(1f, end.incoming.playbackRate, 0.0001f)
    }
}
