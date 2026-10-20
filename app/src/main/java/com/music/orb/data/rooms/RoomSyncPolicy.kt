package com.music.orb.data.rooms

import kotlin.math.abs

/** Playback-only synchronization rules. Pure functions keep mobile drift decisions testable. */
internal object RoomSyncPolicy {
    /** A mobile guest follows a stable, deliberately delayed host timeline. */
    const val MOBILE_GUEST_DELAY_MS = 5_000L
    const val MOBILE_POLL_INTERVAL_MS = 2_500L
    const val WIFI_POLL_INTERVAL_MS = 1_200L
    const val MOBILE_SEEK_THRESHOLD_MS = 2_500L
    const val WIFI_SEEK_THRESHOLD_MS = 1_200L
    const val MOBILE_SEEK_COOLDOWN_MS = 8_000L
    const val WIFI_SEEK_COOLDOWN_MS = 3_000L

    fun targetPosition(hostExpectedMs: Long, metered: Boolean): Long =
        (hostExpectedMs - if (metered) MOBILE_GUEST_DELAY_MS else 0L).coerceAtLeast(0L)

    /** The guest is still hearing A while the host begins the first five seconds of B. */
    fun holdPreviousTrack(
        guestIndex: Int,
        hostIndex: Int,
        hostExpectedMs: Long,
        metered: Boolean,
        playing: Boolean,
    ): Boolean = metered && playing && hostIndex > 0 &&
        guestIndex == hostIndex - 1 && hostExpectedMs < MOBILE_GUEST_DELAY_MS

    /** When Automix hands off B at a nonzero cue, its position alone cannot
     * tell us how recently the host switched tracks. Use observed handoff time. */
    fun holdObservedHandoff(
        guestIndex: Int,
        previousHostIndex: Int,
        hostIndex: Int,
        elapsedSinceObservedMs: Long,
        metered: Boolean,
        playing: Boolean,
    ): Boolean = metered && playing && previousHostIndex >= 0 &&
        guestIndex == previousHostIndex && hostIndex != previousHostIndex &&
        elapsedSinceObservedMs < MOBILE_GUEST_DELAY_MS

    fun correctSameTrack(
        driftMs: Long,
        metered: Boolean,
        buffering: Boolean,
        mixing: Boolean,
        elapsedSinceSeekMs: Long,
    ): Boolean {
        if (buffering || mixing) return false
        val threshold = if (metered) MOBILE_SEEK_THRESHOLD_MS else WIFI_SEEK_THRESHOLD_MS
        val cooldown = if (metered) MOBILE_SEEK_COOLDOWN_MS else WIFI_SEEK_COOLDOWN_MS
        return abs(driftMs) > threshold && elapsedSinceSeekMs >= cooldown
    }

    /** Mobile signal drops do not cause bursts of retries against the room RPC. */
    fun cycleInterval(metered: Boolean, consecutiveFailures: Int): Long {
        val base = if (metered) MOBILE_POLL_INTERVAL_MS else WIFI_POLL_INTERVAL_MS
        return when {
            consecutiveFailures >= 5 -> 8_000L
            consecutiveFailures >= 3 -> 5_000L
            else -> base
        }
    }
}
