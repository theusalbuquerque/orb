package com.music.orb.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.orb.playback.smart.PreparedTransitionStems
import kotlin.math.abs

/**
 * Third, transition-only deck used to audition B's real Open-Unmix accompaniment stem.
 *
 * The normal incoming ExoPlayer keeps decoding the authoritative playback rendition silently in
 * parallel. Near the vocal-safe handoff the controller crossfades this accompaniment deck into
 * that full-quality rendition, so stem artifacts never survive beyond the transition window.
 */
@UnstableApi
class TransitionStemDeck(
    private val player: ExoPlayer,
    private val eq: TransitionDjEqProcessor,
    private val filter: TransitionFilterProcessor,
) {
    private var stems: PreparedTransitionStems? = null

    val isPrepared: Boolean
        get() = stems != null && player.mediaItemCount > 0

    val isReady: Boolean
        get() = isPrepared && player.playbackState == Player.STATE_READY

    val isPlaying: Boolean
        get() = isPrepared && player.isPlaying

    fun recentRms(): Float? = filter.recentRms()

    fun prepare(stems: PreparedTransitionStems, trackPositionMs: Long, playbackSpeed: Float) {
        reset()
        this.stems = stems
        val offset = (trackPositionMs - stems.startMs).coerceIn(0L, (stems.endMs - stems.startMs).coerceAtLeast(0L))
        player.volume = 0f
        player.skipSilenceEnabled = false
        player.setPlaybackSpeed(playbackSpeed)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(stems.accompanimentFile)))
        player.seekTo(offset)
        player.playWhenReady = false
        player.prepare()
    }

    fun start(): Boolean {
        if (!isReady) return false
        player.playWhenReady = true
        return true
    }

    fun setGain(value: Float) {
        if (isPrepared) player.volume = value.coerceIn(0f, 1f)
    }

    fun setPlaybackSpeed(value: Float) {
        if (isPrepared && value.isFinite() && value > 0f) player.setPlaybackSpeed(value)
    }

    fun setFilters(lowPassHz: Float, highPassHz: Float) {
        if (isPrepared) filter.setCutoffs(lowPassHz, highPassHz)
    }

    fun setEq(lowDb: Float, midDb: Float, highDb: Float) {
        if (isPrepared) eq.setGains(lowDb, midDb, highDb)
    }

    fun trackPositionMs(): Long? {
        val current = stems ?: return null
        return current.startMs + player.currentPosition.coerceAtLeast(0L)
    }

    fun seekTrackPosition(trackPositionMs: Long) {
        val current = stems ?: return
        val offset = (trackPositionMs - current.startMs)
            .coerceIn(0L, (current.endMs - current.startMs).coerceAtLeast(0L))
        player.seekTo(offset)
    }

    /** True when the stem and authoritative B deck are close enough for a transparent crossfade. */
    fun isNear(trackPositionMs: Long, toleranceMs: Long = 120L): Boolean {
        val stemPosition = trackPositionMs() ?: return false
        return abs(stemPosition - trackPositionMs) <= toleranceMs
    }

    fun pause() {
        if (isPrepared) player.playWhenReady = false
    }

    fun reset() {
        stems = null
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        player.volume = 0f
        eq.open()
        filter.open()
    }

    fun release() {
        reset()
        player.release()
    }
}
