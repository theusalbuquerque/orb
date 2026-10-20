package com.music.orb

import com.music.orb.ui.lockscreen.shouldRequestOrbUnlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbLockscreenGesturesTest {
    @Test fun swipeUpFromAnyPositionRequestsUnlock() {
        assertTrue(shouldRequestOrbUnlock(4f, -70f, 56f))
    }

    @Test fun swipeDownFromAnyPositionRequestsUnlock() {
        assertTrue(shouldRequestOrbUnlock(-3f, 70f, 56f))
    }

    @Test fun tapsAndSmallMovementsDoNotUnlock() {
        assertFalse(shouldRequestOrbUnlock(0f, 0f, 56f))
        assertFalse(shouldRequestOrbUnlock(4f, -40f, 56f))
    }

    @Test fun horizontalSwipesDoNotUnlock() {
        assertFalse(shouldRequestOrbUnlock(120f, 50f, 56f))
        assertFalse(shouldRequestOrbUnlock(100f, 70f, 56f))
    }

    @Test fun invalidDistancesDoNotUnlock() {
        assertFalse(shouldRequestOrbUnlock(Float.NaN, 80f, 56f))
        assertFalse(shouldRequestOrbUnlock(0f, 80f, 0f))
    }
}