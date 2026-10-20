package com.music.orb.ui.player

/**
 * Decides whether the Now Playing window can display artwork and controls side
 * by side. It deliberately uses the *measured window*, not Configuration's
 * orientation or the user's YouTube Music authentication state.
 *
 * On some Android/OEM builds, screenWidthDp and orientation lag behind the
 * activity's actual resized viewport. A configuration-based check used to put
 * the portrait player on a wide display, overlapping the artwork and controls.
 */
internal fun hasWideNowPlayingViewport(widthDp: Float, heightDp: Float): Boolean =
    widthDp.isFinite() && heightDp.isFinite() &&
        widthDp >= 480f && heightDp > 0f && widthDp > heightDp * 1.08f
