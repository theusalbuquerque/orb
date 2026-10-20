package com.music.orb.data.scrobbling

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.music.orb.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Counts audible, unpaused listening time; seek/quality upgrades never add time. */
class ScrobbleManager(
    private val context: Context,
    private val scope: CoroutineScope,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 240,
) {
    var useNowPlaying: Boolean = true
    var useScrobbling: Boolean = true
    private var song: Song? = null
    private var startedAt: Long = 0L
    private var durationSeconds: Int = 0
    private var heardMs: Long = 0L
    private var runningFrom: Long = 0L
    private var sent: Boolean = false
    private var timer: Job? = null

    fun destroy() = onSongStop()

    fun onSongStart(track: Song?, durationMs: Long? = null) {
        onSongStop()
        if (track == null || track.artist.isBlank() || track.title.isBlank()) return
        song = track
        startedAt = System.currentTimeMillis() / 1000L
        durationSeconds = (durationMs?.takeIf { it > 0 }?.div(1000L)?.toInt()
            ?: track.durationText?.let(::parseDurationSeconds) ?: 0)
        runningFrom = SystemClock.elapsedRealtime()
        if (useNowPlaying) sendNowPlaying(track)
        scheduleTimer()
    }

    fun onSongResume(track: Song) {
        if (song?.videoId != track.videoId) {
            onSongStart(track)
            return
        }
        if (runningFrom != 0L) return // Avoid starting two timers on repeated state notifications.
        runningFrom = SystemClock.elapsedRealtime()
        if (useNowPlaying) sendNowPlaying(track)
        scheduleTimer()
    }

    fun onSongPause() {
        if (runningFrom != 0L) {
            heardMs += (SystemClock.elapsedRealtime() - runningFrom).coerceAtLeast(0L)
            runningFrom = 0L
        }
        timer?.cancel()
        timer = null
    }

    fun onSongStop() {
        onSongPause()
        song = null
        startedAt = 0L
        durationSeconds = 0
        heardMs = 0L
        sent = false
    }

    fun onPlayerStateChanged(isPlaying: Boolean, track: Song?, durationMs: Long? = null) {
        if (track == null) {
            onSongStop()
            return
        }
        if (!isPlaying) {
            onSongPause()
            return
        }
        if (song?.videoId != track.videoId) {
            onSongStart(track, durationMs)
        } else {
            if (durationSeconds <= 0 && durationMs != null && durationMs > 0) {
                durationSeconds = (durationMs / 1000L).toInt()
            }
            onSongResume(track)
        }
    }

    /** The first decoder frame may arrive before Media3 knows its final duration. */
    fun onDurationAvailable(durationMs: Long) {
        if (song == null || durationMs <= 0 || durationSeconds > 0) return
        durationSeconds = (durationMs / 1000L).toInt()
        if (runningFrom != 0L) scheduleTimer()
    }

    private fun scheduleTimer() {
        timer?.cancel()
        if (!useScrobbling || sent) return
        val needed = LastfmScrobbleRules.thresholdMs(
            durationSeconds = durationSeconds,
            userMinimumSeconds = minSongDuration,
            userPercent = scrobbleDelayPercent,
            userMaximumSeconds = scrobbleDelaySeconds,
        ) ?: return
        val remaining = (needed - heardMs).coerceAtLeast(0L)
        timer = scope.launch {
            delay(remaining)
            val track = song ?: return@launch
            if (runningFrom == 0L || sent) return@launch
            sent = true
            val completed = LastFM.Scrobble(
                artist = track.artist,
                track = track.title,
                album = track.albumName,
                timestamp = startedAt,
                duration = durationSeconds,
            )
            withContext(NonCancellable + Dispatchers.IO) {
                LastfmScrobbleQueue.enqueue(context, completed)
            }
            Log.d("OrbLastfm", "Queued scrobble for delivery")
        }
    }

    private fun sendNowPlaying(track: Song) {
        scope.launch {
            try {
                LastFM.updateNowPlaying(track.artist, track.title, track.albumName, durationSeconds)
            } catch (e: Exception) {
                Log.w("OrbLastfm", "Now Playing update skipped", e)
            }
        }
    }

    private fun parseDurationSeconds(raw: String): Int {
        val chunks = raw.trim().split(':').map { it.toIntOrNull() ?: return 0 }
        if (chunks.size !in 2..3) return 0
        return chunks.fold(0) { total, part -> total * 60 + part }
    }
}
