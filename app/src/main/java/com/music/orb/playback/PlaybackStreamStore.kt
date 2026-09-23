package com.music.orb.playback

import android.os.SystemClock
import com.music.orb.data.sources.SourceStream
import com.music.orb.data.sources.hasPlayableHttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Last stream that was actually selected for playback for each track.
 *
 * Downloads use this as a quality hint: if the listener has already proved a
 * Hi-Q/Lossless rendition, saving the track should preserve that rendition
 * instead of launching a fresh resolver that can silently pick another tier.
 * Entries are process-local and short lived because stream URLs are signed.
 */
object PlaybackStreamStore {
    private data class Entry(val stream: SourceStream, val atMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun remember(videoId: String, stream: SourceStream) {
        if (videoId.isBlank() || !stream.hasPlayableHttpUrl()) return
        if (entries.size >= MAX_ENTRIES) entries.clear()
        entries[videoId] = Entry(stream, SystemClock.elapsedRealtime())
    }

    fun recent(videoId: String): SourceStream? {
        val entry = entries[videoId] ?: return null
        if (SystemClock.elapsedRealtime() - entry.atMs > TTL_MS) {
            entries.remove(videoId)
            return null
        }
        if (!entry.stream.hasPlayableHttpUrl()) {
            entries.remove(videoId)
            return null
        }
        return entry.stream
    }

    fun forget(videoId: String) {
        entries.remove(videoId)
    }

    private const val TTL_MS = 30L * 60L * 1000L
    private const val MAX_ENTRIES = 192
}
