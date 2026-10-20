package com.music.orb.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbLockscreenTaskRemovalPolicyTest {
    private val lockscreen = "com.music.orb.ui.lockscreen.OrbLockscreenActivity"
    private val main = "com.music.orb.MainActivity"

    @Test fun explicitLockscreenRemovalNeverStopsPlayback() {
        assertTrue(OrbLockscreenTaskRemovalPolicy.isTransientLockscreenRemoval(lockscreen, lockscreen, false))
        assertTrue(OrbLockscreenTaskRemovalPolicy.isTransientLockscreenRemoval(lockscreen, lockscreen, true))
    }

    @Test fun nullRootIntentImmediatelyAfterUnlockIsTransient() {
        assertTrue(OrbLockscreenTaskRemovalPolicy.isTransientLockscreenRemoval(null, lockscreen, true))
    }

    @Test fun mainTaskRemovalNeverGetsMisclassifiedAsLockscreenRemoval() {
        assertFalse(OrbLockscreenTaskRemovalPolicy.isTransientLockscreenRemoval(main, lockscreen, true))
        assertFalse(OrbLockscreenTaskRemovalPolicy.isTransientLockscreenRemoval(main, lockscreen, false))
    }

    @Test fun unrelatedTaskAndOldNullIntentCannotBeMistakenForLockscreen() {
        assertFalse(OrbLockscreenTaskRemovalPolicy.isTransientLockscreenRemoval(null, lockscreen, false))
        assertFalse(OrbLockscreenTaskRemovalPolicy.isTransientLockscreenRemoval("com.music.orb.SomeOtherActivity", lockscreen, true))
    }


    @Test fun unidentifiableTaskRemovalsCannotStopPlayback() {
        assertFalse(OrbLockscreenTaskRemovalPolicy.isConfirmedMainTaskRemoval(null, main))
        assertFalse(OrbLockscreenTaskRemovalPolicy.isConfirmedMainTaskRemoval("", main))
        assertFalse(OrbLockscreenTaskRemovalPolicy.isConfirmedMainTaskRemoval(lockscreen, main))
        assertFalse(OrbLockscreenTaskRemovalPolicy.isConfirmedMainTaskRemoval(
            "com.music.orb.SomeOtherActivity", main,
        ))
    }

    @Test fun explicitMainTaskRemovalRetainsUserPreferencePolicy() {
        assertTrue(OrbLockscreenTaskRemovalPolicy.isConfirmedMainTaskRemoval(main, main))
    }

    @Test fun freeAccountsStopAfterSwipingMainTaskEvenIfPlaying() {
        assertFalse(OrbLockscreenTaskRemovalPolicy.shouldPreserveMainTaskPlayback(
            premiumEntitled = false, optedIn = true, playbackOngoing = true, playbackRequested = true,
        ))
    }

    @Test fun premiumMustOptInAndMustHaveActiveAudio() {
        assertFalse(OrbLockscreenTaskRemovalPolicy.shouldPreserveMainTaskPlayback(
            premiumEntitled = true, optedIn = false, playbackOngoing = true, playbackRequested = true,
        ))
        assertFalse(OrbLockscreenTaskRemovalPolicy.shouldPreserveMainTaskPlayback(
            premiumEntitled = true, optedIn = true, playbackOngoing = false, playbackRequested = false,
        ))
    }

    @Test fun premiumOptInKeepsBothAudibleAndBufferingPlayback() {
        assertTrue(OrbLockscreenTaskRemovalPolicy.shouldPreserveMainTaskPlayback(
            premiumEntitled = true, optedIn = true, playbackOngoing = true, playbackRequested = false,
        ))
        assertTrue(OrbLockscreenTaskRemovalPolicy.shouldPreserveMainTaskPlayback(
            premiumEntitled = true, optedIn = true, playbackOngoing = false, playbackRequested = true,
        ))
    }
}
