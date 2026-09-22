package com.music.orb.data.social

import com.music.orb.data.model.Song

/**
 * Playback bridge retained without a remote social backend in the public tree.
 * It is deliberately side-effect free and never participates in audio timing.
 */
class SocialPlaybackReporter {
    fun onPlayerStateChanged(isPlaying: Boolean, song: Song?, durationMs: Long?, positionMs: Long) = Unit
    fun onPlaybackEnded(song: Song?, positionMs: Long, durationMs: Long?) = Unit
    fun onTrackBecameCurrent(
        song: Song?,
        previousEnded: Boolean,
        isPlaying: Boolean,
        durationMs: Long?,
        positionMs: Long,
    ) = Unit
    fun onProgress(song: Song?, positionMs: Long, durationMs: Long?) = Unit
    fun shutdown() = Unit
}
