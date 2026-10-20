package com.music.orb.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileReadAheadGuardTest {
    @Test fun oneCancellationPerLowBufferEpisode() {
        val policy = MobileReadAheadGuard()
        assertTrue(policy.shouldCancel(true, 7_000L, false))
        assertFalse(policy.shouldCancel(true, 6_000L, false))
        assertFalse(policy.shouldCancel(true, 12_000L, false))
        assertFalse(policy.shouldCancel(true, 15_000L, false))
        assertTrue(policy.shouldCancel(true, 7_000L, false))
    }

    @Test fun ignoresFullBufferAndResetsOnWifi() {
        val policy = MobileReadAheadGuard()
        assertFalse(policy.shouldCancel(true, 0L, true))
        assertTrue(policy.shouldCancel(true, 2_000L, false))
        assertFalse(policy.shouldCancel(false, 0L, false))
        assertTrue(policy.shouldCancel(true, 2_000L, false))
    }
}
