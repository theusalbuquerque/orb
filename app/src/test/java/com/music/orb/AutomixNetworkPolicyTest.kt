package com.music.orb

import com.music.orb.playback.smart.AutomixNetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixNetworkPolicyTest {
    @Test fun unknownNetworkUsesCellularBudget() {
        assertEquals(AutomixNetworkPolicy.chunkBytes(false), AutomixNetworkPolicy.chunkBytes(null))
        assertEquals(AutomixNetworkPolicy.cacheGraceMs(false), AutomixNetworkPolicy.cacheGraceMs(null))
    }
    @Test fun wifiStartsSoonerAndDoesNotThrottle() {
        assertTrue(AutomixNetworkPolicy.cacheGraceMs(true) < AutomixNetworkPolicy.cacheGraceMs(false))
        assertEquals(0L, AutomixNetworkPolicy.downloadPauseMs(true, 131072L, 10L))
    }
    @Test fun cellularYieldsOnlyUnusedTransferBudget() {
        assertEquals(900L, AutomixNetworkPolicy.downloadPauseMs(false, 131072L, 100L))
        assertEquals(0L, AutomixNetworkPolicy.downloadPauseMs(false, 131072L, 1500L))
    }
}
