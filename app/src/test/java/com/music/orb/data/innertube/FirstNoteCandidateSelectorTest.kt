package com.music.orb.data.innertube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirstNoteCandidateSelectorTest {
    @Test fun verifiedCarrierOverridesUnconfirmedMobileOne() {
        val selector = FirstNoteCandidateSelector<String>(allowTentative = true)
        assertNull(selector.offer("late-CDN", verified = false))
        assertEquals("proved", selector.offer("proved", verified = true))
    }

    @Test fun mobileAllowsOnlyAnUnrefusedFallbackAfterTheWave() {
        val selector = FirstNoteCandidateSelector<String>(allowTentative = true)
        assertNull(selector.offer(null, verified = false)) // HTTP 403/refused
        assertNull(selector.offer("unconfirmed-1", verified = false))
        assertNull(selector.offer("unconfirmed-2", verified = false))
        assertEquals("unconfirmed-1", selector.fallback())
    }

    @Test fun wifiDoesNotUseUnconfirmedCandidate() {
        val selector = FirstNoteCandidateSelector<String>(allowTentative = false)
        assertNull(selector.offer("unconfirmed", verified = false))
        assertNull(selector.fallback())
    }
}
