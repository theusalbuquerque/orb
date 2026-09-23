package com.music.orb.playback.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure DSP automation model for one A -> B transition.
 *
 * It contains no ExoPlayer or Android state. The runtime controller samples this
 * envelope and applies it to the two deck graphs. Keeping it pure makes the
 * audible behaviour deterministic and unit-testable.
 */
object AutomixTransitionRenderer {

    data class DeckFrame(
        val gain: Float,
        val low: Float = 1f,
        val mid: Float = 1f,
        val high: Float = 1f,
        /** 0 disables the high-pass stage. */
        val highPassHz: Float = 0f,
        /** >= 20 kHz effectively disables the low-pass stage. */
        val lowPassHz: Float = 20_000f,
        /** Relative to the user's base playback speed. */
        val playbackRate: Float = 1f,
    )

    data class Frame(
        val outgoing: DeckFrame,
        val incoming: DeckFrame,
    )

    fun render(plan: TransitionPlan, progress: Float): Frame {
        val p = progress.coerceIn(0f, 1f)
        return when (plan.style) {
            TransitionStyle.BLEND -> blend(plan, p)
            TransitionStyle.RISE -> rise(plan, p)
            TransitionStyle.FADE -> fade(plan, p)
            TransitionStyle.CUT -> cut(plan, p)
        }
    }

    private fun blend(plan: TransitionPlan, p: Float): Frame {
        // Equal-power gain keeps perceived loudness stable while the low-end is
        // handed from A to B around the middle of the phrase.
        val angle = p * (PI / 2.0)
        val bassSwap = smoothstep(0.28f, 0.72f, p)
        val rate = releaseTempo(plan.incomingPlaybackRate, p, releaseFrom = 0.90f)
        return Frame(
            outgoing = DeckFrame(
                gain = cos(angle).toFloat(),
                low = lerp(1f, 0.10f, bassSwap),
                mid = lerp(1f, 0.76f, smoothstep(0.55f, 1f, p)),
                high = 1f,
            ),
            incoming = DeckFrame(
                gain = sin(angle).toFloat(),
                low = lerp(0.10f, 1f, bassSwap),
                mid = lerp(0.82f, 1f, smoothstep(0f, 0.65f, p)),
                high = 1f,
                playbackRate = rate,
            ),
        )
    }

    private fun rise(plan: TransitionPlan, p: Float): Frame {
        // A keeps authority during the build. Its bass is progressively removed
        // and a real high-pass sweep creates headroom before B's mapped drop.
        val build = smoothstep(0.05f, 0.82f, p)
        val release = smoothstep(0.72f, 1f, p)
        val incomingOpen = smoothstep(0.42f, 0.90f, p)
        val outGain = (1f - release.pow(1.45f)).coerceIn(0f, 1f)
        val inGain = smoothstep(0.18f, 0.88f, p).pow(0.72f)
        val rate = releaseTempo(plan.incomingPlaybackRate, p, releaseFrom = 0.94f)
        return Frame(
            outgoing = DeckFrame(
                gain = outGain,
                low = lerp(1f, 0.06f, build),
                mid = lerp(1f, 0.72f, build),
                high = lerp(1f, 1.08f, build),
                highPassHz = lerp(28f, 1_350f, build),
            ),
            incoming = DeckFrame(
                gain = inGain,
                low = lerp(0.08f, 1f, incomingOpen),
                mid = lerp(0.70f, 1f, incomingOpen),
                high = lerp(0.84f, 1f, incomingOpen),
                // B begins slightly veiled and opens as its drop takes over.
                lowPassHz = lerp(5_200f, 20_000f, incomingOpen),
                playbackRate = rate,
            ),
        )
    }

    private fun fade(plan: TransitionPlan, p: Float): Frame {
        // Smooth logarithmic-ish overlap, intentionally without beat/EQ tricks.
        // This is the musical fallback for material that should not be forced
        // into a DJ-style blend.
        val out = (1f - p).pow(0.72f)
        val into = p.pow(0.72f)
        return Frame(
            outgoing = DeckFrame(gain = out),
            incoming = DeckFrame(
                gain = into,
                playbackRate = releaseTempo(plan.incomingPlaybackRate, p, releaseFrom = 0.70f),
            ),
        )
    }

    private fun cut(plan: TransitionPlan, p: Float): Frame {
        // Phrase-boundary handoff: A stays present, then exits quickly while B
        // arrives almost immediately. A small bass separation prevents a clicky
        // double-kick without making the transition sound like a long blend.
        val outDrop = smoothstep(0.35f, 0.62f, p)
        val inRise = smoothstep(0.28f, 0.55f, p)
        return Frame(
            outgoing = DeckFrame(
                gain = 1f - outDrop,
                low = lerp(1f, 0.45f, outDrop),
                highPassHz = lerp(0f, 180f, outDrop),
            ),
            incoming = DeckFrame(
                gain = inRise,
                low = lerp(0.55f, 1f, inRise),
                playbackRate = releaseTempo(plan.incomingPlaybackRate, p, releaseFrom = 0.58f),
            ),
        )
    }

    private fun releaseTempo(start: Float, progress: Float, releaseFrom: Float): Float {
        if (start == 1f || progress <= releaseFrom) return start
        val t = smoothstep(releaseFrom, 1f, progress)
        return lerp(start, 1f, t)
    }

    private fun lerp(a: Float, b: Float, p: Float): Float = a + (b - a) * p.coerceIn(0f, 1f)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
