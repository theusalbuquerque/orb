package com.music.orb.playback

/**
 * Hysteresis for speculative mobile queue prefetch. Cancels once per low-buffer
 * episode, without repeatedly stopping/restarting downloads around one threshold.
 */
internal class MobileReadAheadGuard(
    private val pauseBelowMs: Long = 8_000L,
    private val resumeAtMs: Long = 15_000L,
) {
    var suspended = false
        private set

    /** True only on the edge where queue read-ahead should be cancelled. */
    fun shouldCancel(metered: Boolean, bufferedMs: Long, fullyBuffered: Boolean): Boolean {
        if (!metered) {
            suspended = false
            return false
        }
        if (!fullyBuffered && bufferedMs.coerceAtLeast(0L) < pauseBelowMs) {
            if (suspended) return false
            suspended = true
            return true
        }
        if (fullyBuffered || bufferedMs >= resumeAtMs) suspended = false
        return false
    }
}
