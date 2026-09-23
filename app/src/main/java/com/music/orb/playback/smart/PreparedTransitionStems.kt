package com.music.orb.playback.smart

import java.io.File

/**
 * Real Open-Unmix stems for the incoming transition window.
 *
 * The files are ordinary 44.1 kHz stereo PCM WAVs and start at [startMs] on the
 * original track timeline. They are deliberately transition-scoped: the normal
 * playback rendition remains authoritative once the handoff is complete.
 */
data class PreparedTransitionStems(
    val trackId: String,
    val startMs: Long,
    val endMs: Long,
    val vocalsFile: File,
    val accompanimentFile: File,
    val sampleRate: Int,
) {
    fun covers(positionMs: Long): Boolean = positionMs in startMs until endMs
}
