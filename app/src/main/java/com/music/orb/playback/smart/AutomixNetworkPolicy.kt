package com.music.orb.playback.smart

/** Unknown connectivity stays conservative; Wi-Fi alone enables the faster profile. */
internal object AutomixNetworkPolicy {
    fun cacheGraceMs(wifi: Boolean?): Long = if (wifi == true) 2_000L else 12_000L
    fun chunkBytes(wifi: Boolean?): Long = if (wifi == true) 2L * 1024 * 1024 else 128L * 1024
    fun downloadPauseMs(wifi: Boolean?, bytes: Long, elapsedMs: Long): Long =
        if (wifi == true) 0L else (bytes * 1000L / (128L * 1024) - elapsedMs).coerceAtLeast(0L)
}
