package com.music.orb.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PremiumLaunchVisibilityTest {
    private val before = LocalDateTime.of(2026, 11, 1, 7, 59, 59)
    private val released = LocalDateTime.of(2026, 11, 1, 8, 0, 0)

    @Test fun controlsAreNotPublicBeforeEightAMLocal() {
        assertFalse(PremiumLaunch.isPublicLaunchTime(before))
        assertFalse(PremiumLaunch.isLockedAfterLaunch(false, false, before))
    }

    @Test fun freeAccountSeesLockedControlsAtEightAMLocal() {
        assertTrue(PremiumLaunch.isPublicLaunchTime(released))
        assertTrue(PremiumLaunch.isLockedAfterLaunch(false, false, released))
    }

    @Test fun invitedAccountSeesOfferBeforeLaunchButIsNotTheOwner() {
        assertTrue(PremiumLaunch.isEarlyOfferAccount("theusalboficial@gmail.com"))
        assertTrue(PremiumLaunch.isEarlyOfferAccount("  THEUSALBOFICIAL@GMAIL.COM "))
        assertTrue(PremiumLaunch.isLaunchTime("theusalboficial@gmail.com", before))
        assertFalse(PremiumLaunch.isDeveloper("theusalboficial@gmail.com"))
    }

    @Test fun earlyOfferIsNotGrantedToOtherFreeAccounts() {
        assertFalse(PremiumLaunch.isEarlyOfferAccount("other@example.com"))
        assertFalse(PremiumLaunch.isEarlyOfferAccount(null))
        assertFalse(PremiumLaunch.isLaunchTime("other@example.com", before))
        assertTrue(PremiumLaunch.isLaunchTime("other@example.com", released))
    }

    @Test fun originalOwnerStillHasEarlyAccess() {
        assertTrue(PremiumLaunch.isDeveloper("theusalbuquerque98@gmail.com"))
        assertFalse(PremiumLaunch.isEarlyOfferAccount("theusalbuquerque98@gmail.com"))
        assertTrue(PremiumLaunch.isLaunchTime("theusalbuquerque98@gmail.com", before))
    }

    @Test fun premiumAndOwnerNeverHaveLockedControls() {
        assertFalse(PremiumLaunch.isLockedAfterLaunch(true, false, released))
        assertFalse(PremiumLaunch.isLockedAfterLaunch(false, true, released))
    }
}
