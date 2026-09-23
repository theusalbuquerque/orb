package com.music.orb

import com.music.orb.playback.OverlayAudibility
import org.junit.Assert.*
import org.junit.Test

class OverlayAudibilityTest {
    @Test fun quietIntroBecomesAudibleBeforeHandoff() {
        val ride = OverlayAudibility()
        var gain = 0f
        // A is loud; B is quiet even before its fader. Test the resulting
        // signal ratio, not just the shape of a percentage curve.
        for (ms in 0..14000 step 30) {
            gain = ride.nextGain(0.11f, 1f, 0.20f, 0.06f, ms / 56000f, ms.toLong(), 30L)
        }
        assertTrue("B must be present while A still has 42 seconds left", gain * 0.06f / 0.20f >= 0.14f)
        assertTrue(gain < 0.66f)
    }

    @Test fun loudIntroIsNotNeedlesslyBoosted() {
        val ride = OverlayAudibility()
        var gain = 0f
        repeat(100) { gain = ride.nextGain(0.18f, 1f, 0.10f, 0.30f, 0.25f, 14000L, 30L) }
        assertEquals(0.18f, gain, 0.001f)
    }

    @Test fun silenceMissingAndInvalidMetersDoNotProduceGainBoost() {
        for (level in listOf(null, 0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val ride = OverlayAudibility()
            repeat(100) {
                assertEquals(0.15f, ride.nextGain(0.15f, 1f, 0.2f, level, 0.5f, 28000L, 30L), 0.001f)
            }
        }
    }

    @Test fun stemReadinessCannotHideSilentOrStalledAccompaniment() {
        assertTrue(OverlayAudibility.unusableStem(0f, 0.1f, true))
        assertTrue(OverlayAudibility.unusableStem(null, 0.1f, true))
        assertTrue(OverlayAudibility.unusableStem(0.1f, 0.1f, false))
        assertFalse(OverlayAudibility.unusableStem(0.06f, 0.1f, true))
        assertFalse(OverlayAudibility.unusableStem(0f, 0f, true)) // both tracks have real silence
    }

    @Test fun correctionIsBoundedAndResetBetweenPairs() {
        val ride = OverlayAudibility()
        var previous = 0.1f
        for (ms in 0..28000 step 30) {
            val gain = ride.nextGain(0.1f, 1f, 0.8f, 0.001f, 0.5f, ms.toLong(), 30L)
            assertTrue(gain in 0.1f..0.7001f)
            assertTrue(kotlin.math.abs(gain - previous) < 0.04f)
            previous = gain
        }
        ride.reset()
        assertEquals(0f, ride.nextGain(0f, 1f, 0.2f, 0.06f, 0f, 0L, 0L), 0f)
        assertEquals(1f, ride.nextGain(1f, 0f, 0.2f, 0.06f, 1f, 56000L, 30L), 0f)
    }
}
