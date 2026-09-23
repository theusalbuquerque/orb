package com.music.orb.data.social

import com.music.orb.data.model.Song
import com.music.orb.data.settings.PlaylistListeningStore
import com.music.orb.data.settings.RecentPlaybackStore
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bridges Media3's lifecycle to the tiny social event model stored online.
 *
 * It deliberately does not upload progress every few seconds. `now_playing`
 * gets a heartbeat roughly once per minute; `listening_activity` receives one
 * row when real forward playback reaches 30%. No audio bytes are ever uploaded.
 */
class SocialPlaybackReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentSong: Song? = null
    private var currentStartedAtMs: Long = 0L
    private var currentDurationMs: Long? = null
    private var lastPositionMs: Long = 0L
    private var lastHeartbeatAtMs: Long = 0L
    private var listenedMs: Long = 0L
    private var lastObservedPositionMs: Long = 0L
    private var lastProgressElapsedMs: Long = 0L
    private var qualifiedPlaybackRecorded = false
    @Volatile private var activityRowRecorded = false
    @Volatile private var socialFeedThresholdPublished = false
    private var socialFeedPersistJob: Job? = null
    private val activityWriteMutex = Mutex()
    private var currentlyPlaying = false

    fun onPlayerStateChanged(
        isPlaying: Boolean,
        song: Song?,
        durationMs: Long?,
        positionMs: Long,
    ) {
        if (song != null && !sameSong(currentSong, song)) {
            currentSong = song
            currentStartedAtMs = System.currentTimeMillis()
            lastHeartbeatAtMs = 0L
            resetThreshold(positionMs, isPlaying)
        }
        if (song != null) {
            currentDurationMs = durationMs ?: currentDurationMs
            lastPositionMs = positionMs.coerceAtLeast(0L)
        }

        currentlyPlaying = isPlaying
        if (isPlaying && song != null) {
            if (lastProgressElapsedMs == 0L) lastProgressElapsedMs = SystemClock.elapsedRealtime()
            publish(song, force = true)
        } else {
            lastProgressElapsedMs = 0L
            clearLiveRow()
        }
    }

    fun onTrackBecameCurrent(
        song: Song?,
        previousEnded: Boolean,
        isPlaying: Boolean,
        durationMs: Long?,
        positionMs: Long,
    ) {
        val previousSong = currentSong
        val previousDuration = currentDurationMs
        val newPosition = positionMs.coerceAtLeast(0L)

        // Media3 can re-announce the same item after a pause/resume, source
        // re-prepare or transport recovery. That is still the same listening
        // session: finalizing + resetting here would turn one play into two in
        // Stats. Treat it as a continuation while the playhead has not jumped
        // materially backwards. An explicit restart/replay of the same song is
        // still a new session because its position resets beyond this tolerance.
        val continuingSamePlayback =
            previousSong != null &&
                song != null &&
                sameSong(previousSong, song) &&
                !previousEnded &&
                newPosition + SAME_TRACK_CONTINUATION_REWIND_MS >= lastPositionMs
        if (continuingSamePlayback) {
            currentDurationMs = durationMs ?: currentDurationMs
            lastPositionMs = newPosition
            lastObservedPositionMs = newPosition
            lastProgressElapsedMs = if (isPlaying) SystemClock.elapsedRealtime() else 0L
            currentlyPlaying = isPlaying
            if (isPlaying) {
                publish(song, force = true)
            } else {
                clearLiveRow()
            }
            return
        }

        if (previousSong != null) {
            finalizeQualifiedPlayback(
                song = previousSong,
                durationMs = previousDuration,
                previousEnded = previousEnded,
            )
        }

        currentSong = song
        currentStartedAtMs = System.currentTimeMillis()
        currentDurationMs = durationMs
        lastPositionMs = positionMs.coerceAtLeast(0L)
        lastHeartbeatAtMs = 0L
        resetThreshold(positionMs, isPlaying)

        if (isPlaying && song != null) {
            publish(song, force = true)
        } else {
            clearLiveRow()
        }
    }

    fun onProgress(
        song: Song?,
        positionMs: Long,
        durationMs: Long?,
    ) {
        if (song == null) return
        if (!sameSong(currentSong, song)) {
            currentSong = song
            currentStartedAtMs = System.currentTimeMillis() - positionMs.coerceAtLeast(0L)
            lastHeartbeatAtMs = 0L
            resetThreshold(positionMs, true)
        }
        val position = positionMs.coerceAtLeast(0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        val positionDelta = position - lastObservedPositionMs
        val elapsedDelta = if (lastProgressElapsedMs > 0L) {
            (nowElapsed - lastProgressElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
        // PlaybackService calls this only while the player is actually
        // running. Requiring forward progress and capping by wall-clock time
        // prevents a seek straight to the end from becoming a fake listen.
        if (positionDelta > 0L && elapsedDelta > 0L && positionDelta <= MAX_PROGRESS_DELTA_MS) {
            listenedMs += minOf(positionDelta, elapsedDelta + PROGRESS_JITTER_MS)
        }
        lastObservedPositionMs = position
        lastProgressElapsedMs = nowElapsed
        lastPositionMs = position
        currentDurationMs = durationMs ?: currentDurationMs
        if (!socialFeedThresholdPublished && listenedMs >= SOCIAL_FEED_THRESHOLD_MS) {
            recordSocialFeedPlayback(song)
        }
        if (!qualifiedPlaybackRecorded && hasReachedListenThreshold()) {
            recordQualifiedPlayback(song)
        }
        publish(song, force = false)
    }

    fun onPlaybackEnded(
        song: Song?,
        positionMs: Long,
        durationMs: Long?,
    ) {
        val tracked = currentSong
        if (tracked != null && (song == null || tracked.videoId == song.videoId)) {
            finalizeQualifiedPlayback(
                song = tracked,
                durationMs = durationMs ?: currentDurationMs,
                previousEnded = true,
            )
        }
        currentSong = null
        currentStartedAtMs = 0L
        currentDurationMs = null
        lastPositionMs = 0L
        lastHeartbeatAtMs = 0L
        resetThreshold(0L, false)
        clearLiveRow()
    }

    private fun resetThreshold(positionMs: Long, isPlaying: Boolean) {
        listenedMs = 0L
        lastObservedPositionMs = positionMs.coerceAtLeast(0L)
        lastProgressElapsedMs = if (isPlaying) SystemClock.elapsedRealtime() else 0L
        qualifiedPlaybackRecorded = false
        activityRowRecorded = false
        socialFeedThresholdPublished = false
        socialFeedPersistJob?.cancel()
        socialFeedPersistJob = null
        currentlyPlaying = isPlaying
    }

    private fun hasReachedListenThreshold(): Boolean {
        val duration = currentDurationMs?.takeIf { it > 0L } ?: return false
        return listenedMs.toDouble() / duration.toDouble() >= MIN_LISTEN_FRACTION
    }

    private fun finalizeQualifiedPlayback(
        song: Song,
        durationMs: Long?,
        previousEnded: Boolean,
    ) {
        val duration = durationMs?.takeIf { it > 0L }
        currentDurationMs = duration ?: currentDurationMs

        // A natural completion is trustworthy even when the five-second
        // sampler did not observe the final slice of a very short track.
        if (previousEnded && duration != null) {
            listenedMs = maxOf(listenedMs, duration)
        }

        val finishedAtMs = System.currentTimeMillis()
        if (activityRowRecorded) {
            updateQualifiedPlayback(
                song = song,
                finishedAtMs = finishedAtMs,
                playedMs = listenedMs,
            )
        }

        if (!hasReachedListenThreshold()) return
        if (!qualifiedPlaybackRecorded) {
            recordQualifiedPlayback(song)
        } else if (!activityRowRecorded) {
            // Defensive fallback for a remote row that could not be created earlier.
            persistQualifiedPlayback(song, finishedAtMs, listenedMs)
        }
    }

    /**
     * One row represents one playback session. The social feed may create it at
     * 30 seconds; Recents/Stats only consume it after their independent 30%
     * qualification. Replaying the same song creates a new row because
     * [currentStartedAtMs] changes for every session.
     */
    private fun recordQualifiedPlayback(song: Song) {
        if (qualifiedPlaybackRecorded) return
        qualifiedPlaybackRecorded = true
        val qualifiedAtMs = System.currentTimeMillis()
        RecentPlaybackStore.record(
            song = song,
            playedAtMs = qualifiedAtMs,
            startedAtMs = currentStartedAtMs,
        )
        if (activityRowRecorded) {
            updateQualifiedPlayback(
                song = song,
                finishedAtMs = qualifiedAtMs,
                playedMs = listenedMs,
            )
        } else {
            persistQualifiedPlayback(
                song = song,
                finishedAtMs = qualifiedAtMs,
                playedMs = listenedMs,
            )
        }
    }

    /**
     * The social feed should not wait for 30% of a long track. At 30 seconds we
     * persist the same session row early; Stats continues to ignore it until its
     * own 30% threshold is reached because every Stats aggregation calls
     * reachedListenThreshold().
     */
    private fun recordSocialFeedPlayback(song: Song) {
        if (socialFeedThresholdPublished || socialFeedPersistJob?.isActive == true) return
        val finishedAtMs = System.currentTimeMillis()
        val startedAtMs = currentStartedAtMs.takeIf { it > 0L } ?: finishedAtMs
        val durationMs = currentDurationMs
        val playedAtThreshold = listenedMs.coerceAtLeast(SOCIAL_FEED_THRESHOLD_MS)
        socialFeedPersistJob = scope.launch {
            val persisted = ensureActivityRowPersisted(
                song = song,
                startedAtMs = startedAtMs,
                durationMs = durationMs,
                finishedAtMs = finishedAtMs,
                playedMs = playedAtThreshold,
            )
            if (!persisted) return@launch

            // Mark the threshold only after Supabase has actually committed the
            // row. If a transient write fails, the next 5-second progress sample
            // retries instead of permanently believing the social event exists.
            if (currentStartedAtMs == startedAtMs && sameSong(currentSong, song)) {
                socialFeedThresholdPublished = true
            }

            // Commit ordering matters: the listening_activity row is durable first,
            // then now_playing is touched. Followers can therefore use either the
            // Postgres change itself or this heartbeat as a push signal without a
            // race where they query before the feed row exists.
            runCatching {
                SocialRepository.publishNowPlaying(
                    song = song,
                    startedAtMs = startedAtMs,
                    heartbeatAtMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private suspend fun ensureActivityRowPersisted(
        song: Song,
        startedAtMs: Long,
        durationMs: Long?,
        finishedAtMs: Long,
        playedMs: Long,
    ): Boolean = activityWriteMutex.withLock {
        val isCurrentSession = currentStartedAtMs == startedAtMs && sameSong(currentSong, song)
        if (activityRowRecorded && isCurrentSession) {
            return@withLock runCatching {
                SocialRepository.updateListeningActivity(
                    song = song,
                    startedAtMs = startedAtMs,
                    finishedAtMs = finishedAtMs,
                    durationMs = durationMs,
                    playedMs = playedMs.coerceAtLeast(0L),
                )
            }.isSuccess
        }

        val write = runCatching {
            SocialRepository.recordListeningActivity(
                song = song,
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                durationMs = durationMs,
                playedMs = playedMs.coerceAtLeast(0L),
            )
        }
        val legacyStatsSchema = write.getOrNull() == false
        if (write.isSuccess) {
            if (isCurrentSession) activityRowRecorded = true
            if (legacyStatsSchema) {
                PlaylistListeningStore.record(
                    playlistId = song.sourcePlaylistId,
                    title = song.sourcePlaylistTitle,
                    artworkUrl = song.sourcePlaylistArtworkUrl,
                    listenedMs = playedMs.coerceAtLeast(0L),
                    finishedAtMs = finishedAtMs,
                )
            }
            true
        } else {
            false
        }
    }

    private fun sameSong(left: Song?, right: Song): Boolean =
        left?.let { (it.localUri ?: it.videoId) == (right.localUri ?: right.videoId) } == true

    fun shutdown() {
        // The service scope is about to disappear. Use a detached tiny cleanup
        // request so a stale "listening now" row is not left behind.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { SocialRepository.clearNowPlaying() }
        }
        scope.cancel()
    }

    private fun publish(song: Song, force: Boolean) {
        // Do not consume the heartbeat window while no social account exists;
        // if the listener connects their Orb account mid-song, the next 5-second
        // progress sample should publish immediately rather than wait a minute.
        if (SocialRepository.currentUserId() == null) {
            lastHeartbeatAtMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastHeartbeatAtMs < HEARTBEAT_INTERVAL_MS) return
        lastHeartbeatAtMs = now
        val started = currentStartedAtMs.takeIf { it > 0L } ?: now
        scope.launch {
            runCatching {
                SocialRepository.publishNowPlaying(
                    song = song,
                    startedAtMs = started,
                    heartbeatAtMs = now,
                )
            }
        }
    }

    private fun clearLiveRow() {
        scope.launch {
            runCatching { SocialRepository.clearNowPlaying() }
        }
    }

    private fun persistQualifiedPlayback(
        song: Song,
        finishedAtMs: Long,
        playedMs: Long,
    ) {
        val startedAtMs = currentStartedAtMs.takeIf { it > 0L } ?: finishedAtMs
        val durationMs = currentDurationMs
        scope.launch {
            ensureActivityRowPersisted(
                song = song,
                startedAtMs = startedAtMs,
                durationMs = durationMs,
                finishedAtMs = finishedAtMs,
                playedMs = playedMs,
            )
        }
    }

    private fun updateQualifiedPlayback(
        song: Song,
        finishedAtMs: Long,
        playedMs: Long,
    ) {
        val startedAtMs = currentStartedAtMs.takeIf { it > 0L } ?: finishedAtMs
        val durationMs = currentDurationMs
        scope.launch {
            ensureActivityRowPersisted(
                song = song,
                startedAtMs = startedAtMs,
                durationMs = durationMs,
                finishedAtMs = finishedAtMs,
                playedMs = playedMs,
            )
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 60_000L
        const val MIN_LISTEN_FRACTION = 0.30
        const val SOCIAL_FEED_THRESHOLD_MS = 30_000L
        const val MAX_PROGRESS_DELTA_MS = 20_000L
        const val PROGRESS_JITTER_MS = 500L
        /** Same-track callbacks inside this rewind window are transport continuity, not replay. */
        const val SAME_TRACK_CONTINUATION_REWIND_MS = 3_000L
    }
}
