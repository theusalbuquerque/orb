package com.music.orb.data.social

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackContinuation(
    val eventId: String,
    val songId: String,
    val startedAtMs: Long,
    val deviceMs: Map<String, Long> = emptyMap(),
    val deviceTypes: Map<String, String> = emptyMap(),
    val qualifiedAtMs: Long = 0L,
) {
    val listenedMs: Long get() = deviceMs.values.sum()
    fun attributedDevice(durationMs: Long?): String {
        val winner = deviceMs.maxByOrNull { it.value } ?: return "unknown"
        if (deviceMs.size > 1 && (durationMs == null || winner.value.toDouble() <= durationMs * .5))
            return "multiple"
        return deviceTypes[winner.key] ?: "unknown"
    }
}

/** Main-thread bridge between the service reporter and Orb Switch. */
object PlaybackContinuity {
    internal var reporter: SocialPlaybackReporter? = null
    fun snapshot(songId: String): PlaybackContinuation? = reporter?.transferSnapshot(songId)
    fun adopt(value: PlaybackContinuation?) { reporter?.adoptTransfer(value) }
    fun relinquish(songId: String) { reporter?.relinquishTransfer(songId) }
}
