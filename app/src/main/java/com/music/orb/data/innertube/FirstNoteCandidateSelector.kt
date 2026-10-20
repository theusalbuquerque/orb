package com.music.orb.data.innertube

/**
 * Prefer CDN-proven first-note streams. On metered networks only, keep an
 * inconclusive (timed-out, not refused) stream as a last resort rather than
 * forcing a costly extractor walk before ExoPlayer can attempt playback.
 *
 * This policy is intentionally free of Android/network APIs so the distinction
 * between a timed-out proof and an HTTP refusal can be regression-tested.
 */
internal class FirstNoteCandidateSelector<T>(private val allowTentative: Boolean) {
    private var tentative: T? = null

    fun offer(candidate: T?, verified: Boolean): T? {
        if (candidate == null) return null
        if (verified) return candidate
        if (allowTentative && tentative == null) tentative = candidate
        return null
    }

    fun fallback(): T? = tentative
}
