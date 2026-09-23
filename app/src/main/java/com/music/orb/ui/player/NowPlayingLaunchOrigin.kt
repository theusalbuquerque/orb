package com.music.orb.ui.player

import android.os.SystemClock
import androidx.compose.ui.geometry.Rect

enum class NowPlayingLaunchOriginKind {
    ARTWORK,
    MINI_PLAYER,
}

data class NowPlayingLaunchOrigin(
    val bounds: Rect,
    val kind: NowPlayingLaunchOriginKind = NowPlayingLaunchOriginKind.ARTWORK,
)

/**
 * Short-lived source geometry for the next Now Playing container transform.
 * Media rows/cards normally record their artwork bounds. MiniPlayer records
 * its complete container so the whole bar can expand into Now Playing.
 */
object NowPlayingLaunchOriginRegistry {
    @Volatile
    private var origin: NowPlayingLaunchOrigin? = null

    @Volatile
    private var recordedAtMs: Long = 0L

    fun record(rect: Rect) {
        record(rect, NowPlayingLaunchOriginKind.ARTWORK)
    }

    fun record(rect: Rect, kind: NowPlayingLaunchOriginKind) {
        if (rect.width <= 0f || rect.height <= 0f) return
        origin = NowPlayingLaunchOrigin(rect, kind)
        recordedAtMs = SystemClock.uptimeMillis()
    }

    fun consume(maxAgeMs: Long = 1_500L): NowPlayingLaunchOrigin? {
        val now = SystemClock.uptimeMillis()
        val value = origin
        origin = null
        val age = now - recordedAtMs
        recordedAtMs = 0L
        return value?.takeIf { age in 0..maxAgeMs }
    }
}
