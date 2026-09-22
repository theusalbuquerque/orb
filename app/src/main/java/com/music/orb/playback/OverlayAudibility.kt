package com.music.orb.playback

import kotlin.math.exp
import kotlin.math.max

/** Post-DSP level correction for an intro bed, independent of metadata/session ownership. */
internal class OverlayAudibility {
    private var gain = 0f

    fun reset() { gain = 0f }

    fun nextGain(
        planned: Float,
        outgoingGain: Float,
        outgoingRms: Float?,
        incomingRms: Float?,
        progress: Float,
        elapsedMs: Long,
        stepMs: Long,
    ): Float {
        val p = progress.coerceIn(0f, 1f)
        var target = planned.coerceIn(0f, 1f)
        if (outgoingRms != null && incomingRms != null &&
            outgoingRms.isFinite() && incomingRms.isFinite() &&
            outgoingRms > SIGNAL_FLOOR && incomingRms > SIGNAL_FLOOR
        ) {
            // A 10% fader on a quiet intro is not a 10% audible bed. Compare the
            // actual post-EQ PCM levels, with a bounded correction and soft attack.
            val attack = (elapsedMs / 2500f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
            val ratio = (0.14f + 0.24f * p * p) * attack
            val needed = outgoingRms * outgoingGain * ratio / incomingRms
            val ceiling = max(target, 0.60f + 0.20f * p)
            target = max(target, needed.coerceAtMost(ceiling))
        }
        // No boost from silence/missing observations, no abrupt gain jumps when
        // a meter becomes available, and no change to the planner's A envelope.
        val seconds = stepMs.coerceIn(0L, 250L) / 1000f
        val timeConstant = if (target > gain) 0.55f else 0.25f
        gain += (target - gain) * (1f - exp(-seconds / timeConstant))
        return max(planned.coerceIn(0f, 1f), gain.coerceIn(0f, 1f))
    }

    companion object {
        const val SIGNAL_FLOOR = 0.0002f

        fun unusableStem(stemRms: Float?, fullRms: Float?, playing: Boolean): Boolean =
            !playing || (fullRms != null && fullRms.isFinite() && fullRms > 0.002f &&
                (stemRms == null || !stemRms.isFinite() || stemRms < max(SIGNAL_FLOOR, fullRms * 0.04f)))
    }
}