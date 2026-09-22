package com.music.orb.playback

import android.os.SystemClock
import com.music.orb.data.sources.SourceStream
import com.music.orb.data.sources.hasPlayableHttpUrl
import java.util.concurrent.ConcurrentHashMap

object PlaybackStreamStore {
    private data class Entry(val stream: SourceStream, val atMs: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun remember(videoId: String, stream: SourceStream) {
        if (videoId.isBlank() || !stream.hasPlayableHttpUrl()) return
        if (entries.size >= 192) entries.clear()
        entries[videoId] = Entry(stream, SystemClock.elapsedRealtime())
    }

    fun recent(videoId: String): SourceStream? {
        val entry = entries[videoId] ?: return null
        if (SystemClock.elapsedRealtime() - entry.atMs > TTL_MS || !entry.stream.hasPlayableHttpUrl()) {
            entries.remove(videoId)
            return null
        }
        return entry.stream
    }

    fun forget(videoId: String) { entries.remove(videoId) }
    fun clear() { entries.clear() }

    private const val TTL_MS = 30L * 60L * 1000L
}
