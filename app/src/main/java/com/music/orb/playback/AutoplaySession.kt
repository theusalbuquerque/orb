package com.music.orb.playback

import com.music.orb.data.model.Song
import java.util.ArrayDeque

/**
 * Session-level station memory shared by the UI and the MediaSessionService.
 * All accesses are from the main thread (Compose callbacks or service player callbacks).
 * Never treat a natural Media3 transition as a user rejection.
 */
object AutoplaySession {
    private const val HISTORY_LIMIT = 180
    private const val REJECT_LIMIT = 180
    private val heardOrQueued = ArrayDeque<Song>()
    private val rejected = ArrayDeque<Song>()
    private var activeAccountKey: String? = null

    fun ensureAccount(accountKey: String) {
        if (activeAccountKey == accountKey) return
        activeAccountKey = accountKey
        heardOrQueued.clear()
        rejected.clear()
        pausedByUser = false
        generation++
    }

    var pausedByUser: Boolean = false
        private set
    var generation: Long = 0L
        private set

    fun beginUserSelection() {
        pausedByUser = false
        generation++
        rejected.clear() // Explicitly starting a new station is a new decision.
    }

    fun pauseAfterClearingQueue() {
        pausedByUser = true
        generation++ // Invalidate an in-flight radio request before it appends.
    }

    fun reject(song: Song) {
        if (rejected.none { QueueBuilder.isSameRecording(it, song) }) {
            rejected.addLast(song)
            while (rejected.size > REJECT_LIMIT) rejected.removeFirst()
        }
        generation++ // A response already being prepared must re-check the vetoes.
    }

    fun rememberPlayed(song: Song) = remember(song)
    fun rememberSuggested(songs: List<Song>) = songs.forEach(::remember)

    private fun remember(song: Song) {
        if (heardOrQueued.none { QueueBuilder.isSameRecording(it, song) }) {
            heardOrQueued.addLast(song)
            while (heardOrQueued.size > HISTORY_LIMIT) heardOrQueued.removeFirst()
        }
    }

    fun history(): List<Song> = heardOrQueued.toList()

    fun excluded(song: Song): Boolean = rejected.any { QueueBuilder.isSameRecording(it, song) } ||
        heardOrQueued.any { QueueBuilder.isSameRecording(it, song) }
}
