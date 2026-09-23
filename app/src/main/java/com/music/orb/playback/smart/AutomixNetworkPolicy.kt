package com.music.orb.playback.smart

/** Unknown connectivity stays conservative; Wi-Fi alone enables the faster profile. */
internal object AutomixNetworkPolicy {
    // Reliable analysis uses its own immutable carrier, so on Wi-Fi there is no
    // benefit in waiting for the playback cache to fill first. Start immediately.
    fun cacheGraceMs(wifi: Boolean?): Long = if (wifi == true) 0L else 12_000L

    // Fewer HTTP range round-trips on Wi-Fi; cellular keeps the deliberately
    // throttled small-block profile.
    fun chunkBytes(wifi: Boolean?): Long = if (wifi == true) 8L * 1024 * 1024 else 128L * 1024
    fun downloadPauseMs(wifi: Boolean?, bytes: Long, elapsedMs: Long): Long =
        if (wifi == true) 0L else (bytes * 1000L / (128L * 1024) - elapsedMs).coerceAtLeast(0L)
}
