package com.music.orb.ui.lockscreen

import kotlin.math.abs

/**
 * Determines whether a gesture anywhere on the lockscreen is an intentional
 * vertical unlock attempt. Both upward and downward swipes are accepted;
 * horizontal gestures, normal taps and short accidental movements are ignored.
 */
internal fun shouldRequestOrbUnlock(
    deltaX: Float,
    deltaY: Float,
    minimumDistancePx: Float,
): Boolean {
    if (!deltaX.isFinite() || !deltaY.isFinite() ||
        !minimumDistancePx.isFinite() || minimumDistancePx <= 0f
    ) return false

    return abs(deltaY) >= minimumDistancePx && abs(deltaY) > abs(deltaX) * 1.2f
}