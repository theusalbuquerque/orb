package com.music.orb

import com.music.orb.ui.player.hasWideNowPlayingViewport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingViewportTest {
    @Test fun landscapePhoneDoesNotDependOnAuthOrAndroidOrientationFlag() {
        // Geometry is measured by Compose; Android may still report portrait.
        assertTrue(hasWideNowPlayingViewport(widthDp = 600f, heightDp = 288f))
        assertTrue(hasWideNowPlayingViewport(widthDp = 486f, heightDp = 324f))
    }

    @Test fun landscapeTabletAndFoldableWindowsKeepTwoPanePlayer() {
        assertTrue(hasWideNowPlayingViewport(widthDp = 1024f, heightDp = 600f))
        assertTrue(hasWideNowPlayingViewport(widthDp = 700f, heightDp = 520f))
    }

    @Test fun portraitAndNearlySquareWindowsKeepPortraitLayout() {
        assertFalse(hasWideNowPlayingViewport(widthDp = 400f, heightDp = 840f))
        assertFalse(hasWideNowPlayingViewport(widthDp = 650f, heightDp = 620f))
        assertFalse(hasWideNowPlayingViewport(widthDp = 800f, heightDp = 1280f))
        assertFalse(hasWideNowPlayingViewport(widthDp = 1024f, heightDp = 1366f))
        assertFalse(hasWideNowPlayingViewport(widthDp = 450f, heightDp = 260f))
        assertFalse(hasWideNowPlayingViewport(widthDp = 0f, heightDp = 0f))
        assertFalse(hasWideNowPlayingViewport(widthDp = Float.NaN, heightDp = 620f))
    }
}
