package com.music.orb.data.scrobbling

import kotlin.math.min

/** Last.fm minimum: >30 seconds long, >=50% listened or 240s, whichever first. */
object LastfmScrobbleRules {
    fun thresholdMs(
        durationSeconds: Int,
        userMinimumSeconds: Int = 30,
        userPercent: Float = 0.5f,
        userMaximumSeconds: Int = 240,
    ): Long? {
        if (durationSeconds <= userMinimumSeconds.coerceAtLeast(30)) return null
        val required = min(durationSeconds * 500L, 240_000L)
        val userRequested = min(
            (durationSeconds * 1000L * userPercent.coerceIn(0.5f, 1f)).toLong(),
            userMaximumSeconds.coerceAtLeast(30) * 1000L,
        )
        return maxOf(required, userRequested)
    }
}
