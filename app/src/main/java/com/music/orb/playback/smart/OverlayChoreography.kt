package com.music.orb.playback.smart

import kotlin.math.floor

/** Wall-clock automation, computed once for the exact cue, rates and loop repetitions. */
data class OverlayChoreography(
    val vocalRelease: Double,
    val vocalReleaseEnd: Double,
    val outgoingRetreat: Double,
    val outgoingExit: Double,
    val bassTransfer: Double,
    val instrumentalTail: Boolean,
    val bedLevels: List<Double>,
    val fullTrackSafety: List<Double>,
) {
    val needsStems: Boolean get() = fullTrackSafety.any { it < 0.99 }

    fun incomingGain(progress: Double): Float {
        val p = progress.coerceIn(0.0, 1.0)
        val bed = sample(bedLevels, p) * ramp(0.0, 0.04, p)
        return (bed + (1.0 - bed) * ramp(vocalRelease, vocalReleaseEnd, p)).toFloat()
    }

    fun outgoingGain(progress: Double): Float {
        val p = progress.coerceIn(0.0, 1.0)
        val tail = if (instrumentalTail) 0.28 else 0.0
        val foreground = 1.0 - (1.0 - tail) * ramp(outgoingRetreat, vocalRelease, p)
        return (foreground * (1.0 - ramp(vocalRelease, outgoingExit, p))).toFloat()
    }

    /** Missing stems must not unexpectedly expose B's voice under A's voice. */
    fun fullTrackAllowance(progress: Double): Float {
        val release = ramp(vocalRelease, vocalReleaseEnd, progress)
        return maxOf(sample(fullTrackSafety, progress), release).toFloat()
    }

    private fun sample(values: List<Double>, progress: Double): Double {
        if (values.isEmpty()) return 0.0
        val index = progress.coerceIn(0.0, 1.0) * (values.size - 1)
        val left = floor(index).toInt()
        val right = (left + 1).coerceAtMost(values.lastIndex)
        val blend = ramp(0.0, 1.0, index - left)
        return values[left] + (values[right] - values[left]) * blend
    }
}

private fun ramp(start: Double, end: Double, value: Double): Double {
    if (end <= start) return if (value >= end) 1.0 else 0.0
    val t = ((value - start) / (end - start)).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

/** Unknown/sparse vocal analysis never grants permission for an extended vocal overlap. */
private fun quietAt(analysis: TrackAnalysis, position: Double): Boolean {
    val curve = analysis.energyCurve
    val mask = analysis.vocalActivityMask
    if (curve.isEmpty() || mask.size != curve.size || position < curve.first().time ||
        position > curve.last().time) return false
    var count = 0
    var previous: Double? = null
    val found = curve.binarySearch { it.time.compareTo(position - 0.8) }
    val first = if (found >= 0) found else -found - 1
    for (i in first until curve.size) {
        val time = curve[i].time
        if (time > position + 0.8) break
        if (!mask[i].isFinite() || mask[i] > 0.25) return false
        val last = previous
        if (last != null && time - last > 0.75) return false
        previous = time
        count++
    }
    return count >= 2
}

fun planOverlayChoreography(
    outgoing: TrackAnalysis,
    incoming: TrackAnalysis,
    start: Double,
    duration: Double,
    outgoingRate: Double,
    incomingPosition: (Double) -> Double,
): OverlayChoreography {
    require(duration.isFinite() && duration > 0.0)
    val steps = 128
    val aQuiet = (0..steps).map { quietAt(outgoing, start + duration * it / steps * outgoingRate) }
    val bQuiet = (0..steps).map { quietAt(incoming, incomingPosition(duration * it / steps)) }
    // Only the final sustained quiet window can authorize A continuing underneath full B.
    // An earlier gap followed by another verse is deliberately not a vocal release.
    val lastUnsafe = aQuiet.indexOfLast { !it }
    val safeStart = (lastUnsafe + 1).toDouble() / steps
    val hasTail = safeStart <= 0.82 && (1.0 - safeStart) * duration >= 2.0
    val release = if (hasTail) maxOf(0.35, safeStart) else 0.96
    val releaseEnd = (release + (0.6 / duration).coerceIn(0.015, 0.04)).coerceAtMost(1.0)
    val retreat = (release - (1.2 / duration).coerceIn(0.02, 0.12)).coerceAtLeast(0.0)
    // Prefer a trusted phrase/downbeat in A's quiet tail for the bass exchange.
    val boundaries = (outgoing.phraseBoundaries + outgoing.downbeats)
        .filter { outgoing.beatConfidence >= 0.65 && it.isFinite() }
        .map { (it - start) / (duration * outgoingRate) }
    val bass = boundaries.filter { it >= release && it <= releaseEnd + 0.08 }
        .minOrNull() ?: release
    return OverlayChoreography(
        vocalRelease = release,
        vocalReleaseEnd = releaseEnd,
        outgoingRetreat = retreat,
        outgoingExit = if (hasTail) 1.0 else release,
        bassTransfer = bass.coerceIn(0.05, 0.98),
        instrumentalTail = hasTail,
        bedLevels = aQuiet.map { if (it) 0.24 else 0.10 },
        fullTrackSafety = aQuiet.indices.map { if (aQuiet[it] || bQuiet[it]) 1.0 else 0.0 },
    )
}
