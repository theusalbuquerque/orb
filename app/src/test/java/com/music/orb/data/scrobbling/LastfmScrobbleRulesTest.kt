package com.music.orb.data.scrobbling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastfmScrobbleRulesTest {
    @Test fun shortTracksAreNeverScrobbled() {
        assertNull(LastfmScrobbleRules.thresholdMs(30))
        assertNull(LastfmScrobbleRules.thresholdMs(10))
    }

    @Test fun normalTracksRequireHalfDuration() {
        assertEquals(90_000L, LastfmScrobbleRules.thresholdMs(180))
        assertEquals(120_000L, LastfmScrobbleRules.thresholdMs(240))
    }

    @Test fun longTracksRequireAtLeastFourMinutes() {
        assertEquals(240_000L, LastfmScrobbleRules.thresholdMs(1000))
    }

    @Test fun userSettingsCannotScrobbleBeforeOfficialMinimum() {
        assertEquals(240_000L, LastfmScrobbleRules.thresholdMs(900, 15, 0.1f, 30))
    }
}
