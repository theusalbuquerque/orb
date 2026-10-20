package com.music.orb.data.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSyncPolicyTest {
    @Test fun mobileGuestFollowsFiveSecondsBehindHost() {
        assertEquals(15_000L, RoomSyncPolicy.targetPosition(20_000L, true))
        assertEquals(0L, RoomSyncPolicy.targetPosition(4_999L, true))
        assertEquals(0L, RoomSyncPolicy.targetPosition(0L, true))
    }
    @Test fun wifiUsesHostTimelineWithoutExtraDelay() {
        assertEquals(20_000L, RoomSyncPolicy.targetPosition(20_000L, false))
    }
    @Test fun previousTrackIsHeldDuringFirstFiveSecondsOnMobile() {
        assertTrue(RoomSyncPolicy.holdPreviousTrack(2, 3, 4_999L, true, true))
        assertFalse(RoomSyncPolicy.holdPreviousTrack(2, 3, 5_000L, true, true))
        assertFalse(RoomSyncPolicy.holdPreviousTrack(2, 3, 2_000L, false, true))
        assertFalse(RoomSyncPolicy.holdPreviousTrack(2, 4, 2_000L, true, true))
        assertFalse(RoomSyncPolicy.holdPreviousTrack(2, 3, 2_000L, true, false))
    }
    @Test fun holdNonzeroCueHandoffForFiveSeconds() {
        assertTrue(RoomSyncPolicy.holdObservedHandoff(0, 0, 1, 0L, true, true))
        assertTrue(RoomSyncPolicy.holdObservedHandoff(0, 0, 1, 4_999L, true, true))
        assertFalse(RoomSyncPolicy.holdObservedHandoff(0, 0, 1, 5_000L, true, true))
        assertFalse(RoomSyncPolicy.holdObservedHandoff(1, 0, 1, 1_000L, true, true))
    }
    @Test fun neverSeekDuringMixingOrBuffering() {
        assertFalse(RoomSyncPolicy.correctSameTrack(10_000L, true, false, true, 60_000L))
        assertFalse(RoomSyncPolicy.correctSameTrack(10_000L, true, true, false, 60_000L))
    }
    @Test fun cooldownAndThresholdPreventSeekStorms() {
        assertFalse(RoomSyncPolicy.correctSameTrack(2_500L, true, false, false, 30_000L))
        assertFalse(RoomSyncPolicy.correctSameTrack(10_000L, true, false, false, 7_999L))
        assertTrue(RoomSyncPolicy.correctSameTrack(-4_000L, true, false, false, 8_000L))
        assertFalse(RoomSyncPolicy.correctSameTrack(1_200L, false, false, false, 10_000L))
        assertTrue(RoomSyncPolicy.correctSameTrack(1_500L, false, false, false, 3_000L))
    }
    @Test fun repeatedMobileFailuresBackOff() {
        assertEquals(2_500L, RoomSyncPolicy.cycleInterval(true, 0))
        assertEquals(2_500L, RoomSyncPolicy.cycleInterval(true, 2))
        assertEquals(5_000L, RoomSyncPolicy.cycleInterval(true, 3))
        assertEquals(8_000L, RoomSyncPolicy.cycleInterval(true, 5))
        assertEquals(1_200L, RoomSyncPolicy.cycleInterval(false, 0))
    }
}
