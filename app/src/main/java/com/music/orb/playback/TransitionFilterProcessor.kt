package com.music.orb.playback

import android.util.Log
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan
import kotlin.math.sqrt

/**
 * The filter a track rides through a Automix transition: a low-pass that can
 * close over the outgoing track, and a high-pass that can lift the low end out
 * of one side of a blend.
 *
 * ## Why this exists
 *
 * [CrossfadeController] renders every transition as an equal-power gain blend,
 * and a gain blend is the one move that cannot fix the two things that actually
 * make a mix sound amateur:
 *
 *  - **Two basslines at once.** Below roughly 200 Hz a mix has very little room;
 *    two kick drums and two bass parts occupying it simultaneously read as mud
 *    and eat headroom, however carefully the gains are matched. Every DJ mixer
 *    ever built has a bass kill for exactly this, and the fix is the same here:
 *    the low end belongs to exactly one track at a time, and it changes hands
 *    once, on a beat the planner picked
 *    ([com.music.orb.playback.smart.TransitionPlan.bassSwapFraction]).
 *  - **Two unrelated tempi at once.** When the tracks are too far apart to
 *    beat-match, their transients simply collide. Closing a low-pass over the
 *    outgoing track pulls it behind the incoming one instead of leaving them to
 *    fight, which is why a filtered handoff is the standard move for a tempo
 *    change.
 *
 * ## The filter
 *
 * A topology-preserving (trapezoidal-integrator) state-variable filter, two
 * second-order sections cascaded to a 24 dB/octave Butterworth response. Chosen
 * over the more familiar Chamberlin SVF because the trapezoidal form is stable
 * at every cutoff up to Nyquist, while Chamberlin's is only well behaved below
 * about a sixth of the sample rate — a low-pass parked wide open at 20 kHz sits
 * far outside that, so the naive form would have to be special-cased at exactly
 * the setting it spends most of its time at.
 *
 * `tan` is evaluated once per sub-block rather than per sample, and the whole
 * thing degenerates to a buffer copy when both cutoffs are parked, so a
 * transition that asks for no filtering costs nothing.
 *
 * ## Gliding
 *
 * Cutoffs are targets, not values. [CrossfadeController] re-aims them once per
 * fade tick (every 30 ms), and stepping a filter in 30 ms jumps is audible as
 * zipper noise, so the real cutoff chases its target geometrically across
 * [GLIDE_FRAMES]-sample sub-blocks. Geometric because cutoff is perceived
 * logarithmically: a linear glide down from 20 kHz would spend nearly all of
 * itself inaudible and then lurch through the last octave.
 */
@UnstableApi
class TransitionFilterProcessor : BaseAudioProcessor() {

    @Volatile
    private var targetLowPassHz: Float = OPEN_HZ

    @Volatile
    private var targetHighPassHz: Float = OFF_HZ

    private data class Level(val rms: Float, val atMs: Long)
    @Volatile private var level: Level? = null
    private var meanSquare = 0.0

    /** Decoder output after EQ/filter, before the player fader; never proof of device output. */
    fun recentRms(): Float? = level?.takeIf {
        SystemClock.elapsedRealtime() - it.atMs in 0L..1500L
    }?.rms

    private fun measure(buffer: java.nio.ByteBuffer, frames: Int) {
        val samples = buffer.duplicate().order(ByteOrder.nativeOrder())
        var sum = 0.0
        var count = 0
        // Sample every fourth frame, all channels. Do not consume/modify audio bytes.
        val stride = bytesPerSample * channelCount * 4
        var frame = samples.position()
        while (frame + bytesPerSample * channelCount <= samples.limit()) {
            repeat(channelCount) { channel ->
                val offset = frame + channel * bytesPerSample
                val value = if (encoding == C.ENCODING_PCM_FLOAT) samples.getFloat(offset).toDouble()
                    else samples.getShort(offset).toDouble() / 32768.0
                if (value.isFinite()) sum += value * value
                count++
            }
            frame += stride
        }
        if (count == 0) return
        val weight = 1.0 - exp(-frames.toDouble() / sampleRate.coerceAtLeast(1) / 0.18)
        meanSquare += (sum / count - meanSquare) * weight
        level = Level(sqrt(meanSquare.coerceAtLeast(0.0)).toFloat(), SystemClock.elapsedRealtime())
    }

    private var channelCount = 0
    private var sampleRate = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0

    private var currentLowPassHz = OPEN_HZ
    private var currentHighPassHz = OFF_HZ

    /** Two integrator states per second-order section, per channel. */
    private var lowState = FloatArray(0)
    private var highState = FloatArray(0)

    private val lowA1 = FloatArray(STAGES)
    private val lowA2 = FloatArray(STAGES)
    private val lowA3 = FloatArray(STAGES)
    private val highA1 = FloatArray(STAGES)
    private val highA2 = FloatArray(STAGES)
    private val highA3 = FloatArray(STAGES)
    private val highK = FloatArray(STAGES)

    /**
     * Aims the filter. [lowPassHz] at or above [OPEN_HZ] and [highPassHz] at or
     * below [OFF_HZ] mean "not filtering", which is the state this returns to
     * between transitions.
     */
    fun setCutoffs(lowPassHz: Float, highPassHz: Float) {
        targetLowPassHz = lowPassHz.coerceIn(MIN_HZ, OPEN_HZ)
        targetHighPassHz = highPassHz.coerceIn(OFF_HZ, MAX_HIGH_PASS_HZ)
    }

    /** Parks both filters. Glided, not snapped — see the class doc. */
    fun open() = setCutoffs(OPEN_HZ, OFF_HZ)

    /**
     * Supports both 16-bit and float PCM, and bows out with
     * [AudioProcessor.AudioFormat.NOT_SET] rather than throwing for the same
     * reason it does: `DefaultAudioSink` configures every processor in its chain
     * whether or not the effect is switched on, and a throw from any of them
     * kills the renderer outright. NOT_SET means "inactive for this format" and
     * the chain routes around this processor.
     *
     * Logged rather than silent, because the failure mode of a filter that
     * quietly declines to run is a Phase 4 transition that sounds exactly like a
     * Phase 3 one, with nothing anywhere saying why.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT) ||
            inputAudioFormat.channelCount < 1
        ) {
            Log.w(
                TAG,
                "Transition filtering inactive: encoding=${inputAudioFormat.encoding} " +
                        "channels=${inputAudioFormat.channelCount} is not supported PCM",
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        encoding = inputAudioFormat.encoding
        bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        lowState = FloatArray(channelCount * STAGES * 2)
        highState = FloatArray(channelCount * STAGES * 2)
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        return inputAudioFormat
    }

    override fun onFlush() {
        level = null
        meanSquare = 0.0
        lowState.fill(0f)
        highState.fill(0f)
        // Snapped, not glided: a flush means a seek or a fresh source, so there
        // is no continuous signal for a glide to be continuous with.
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
    }

    override fun onReset() {
        level = null
        meanSquare = 0.0
        targetLowPassHz = OPEN_HZ
        targetHighPassHz = OFF_HZ
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        lowState = FloatArray(0)
        highState = FloatArray(0)
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val bytesPerFrame = bytesPerSample * channelCount
        if (bytesPerFrame == 0) return
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)

        val targetLow = targetLowPassHz
        val targetHigh = targetHighPassHz
        // Parked at both ends *and* already settled there: nothing to do but
        // hand the buffer straight through. The "already settled" half matters
        // — a transition that has just finished is still gliding back open, and
        // cutting the filter out from under that glide is the click it exists
        // to avoid.
        val parked = targetLow >= OPEN_HZ && targetHigh <= OFF_HZ &&
                currentLowPassHz >= OPEN_HZ - SETTLED_HZ && currentHighPassHz <= OFF_HZ + SETTLED_HZ
        if (parked) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            measure(outputBuffer, frameCount)
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        var remaining = frameCount
        while (remaining > 0) {
            val block = min(remaining, GLIDE_FRAMES)
            currentLowPassHz = glide(currentLowPassHz, targetLow)
            currentHighPassHz = glide(currentHighPassHz, targetHigh)
            val lowOn = currentLowPassHz < OPEN_HZ - SETTLED_HZ
            val highOn = currentHighPassHz > OFF_HZ + SETTLED_HZ
            if (lowOn) updateLowCoefficients()
            if (highOn) updateHighCoefficients()

            repeat(block) {
                for (channel in 0 until channelCount) {
                    var sample = readSample(inputBuffer)
                    if (lowOn) sample = lowPass(channel, sample)
                    if (highOn) sample = highPass(channel, sample)
                    writeSample(outputBuffer, sample)
                }
            }
            remaining -= block
        }
        outputBuffer.flip()
        measure(outputBuffer, frameCount)
    }

    // ---- Filter ------------------------------------------------------------

    private fun glide(current: Float, target: Float): Float {
        val from = ln(current.coerceAtLeast(MIN_HZ))
        val to = ln(target.coerceAtLeast(MIN_HZ))
        return exp(from + (to - from) * GLIDE_RATE)
    }

    /** Highest cutoff the bilinear transform can still represent without warping to infinity. */
    private fun usableCutoff(hz: Float): Float =
        hz.coerceIn(MIN_HZ, sampleRate * MAX_CUTOFF_FRACTION)

    private fun updateLowCoefficients() {
        val g = tan(Math.PI * usableCutoff(currentLowPassHz) / sampleRate).toFloat()
        for (stage in 0 until STAGES) {
            val k = 1f / BUTTERWORTH_Q[stage]
            val a1 = 1f / (1f + g * (g + k))
            lowA1[stage] = a1
            lowA2[stage] = g * a1
            lowA3[stage] = g * (g * a1)
        }
    }

    private fun updateHighCoefficients() {
        val g = tan(Math.PI * usableCutoff(currentHighPassHz) / sampleRate).toFloat()
        for (stage in 0 until STAGES) {
            val k = 1f / BUTTERWORTH_Q[stage]
            val a1 = 1f / (1f + g * (g + k))
            highA1[stage] = a1
            highA2[stage] = g * a1
            highA3[stage] = g * (g * a1)
            highK[stage] = k
        }
    }

    private fun lowPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = lowState[i]
            val ic2 = lowState[i + 1]
            val v3 = value - ic2
            val v1 = lowA1[stage] * ic1 + lowA2[stage] * v3
            val v2 = ic2 + lowA2[stage] * ic1 + lowA3[stage] * v3
            lowState[i] = 2f * v1 - ic1
            lowState[i + 1] = 2f * v2 - ic2
            value = v2
        }
        return value
    }

    private fun highPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = highState[i]
            val ic2 = highState[i + 1]
            val v3 = value - ic2
            val v1 = highA1[stage] * ic1 + highA2[stage] * v3
            val v2 = ic2 + highA2[stage] * ic1 + highA3[stage] * v3
            highState[i] = 2f * v1 - ic1
            highState[i + 1] = 2f * v2 - ic2
            value -= highK[stage] * v1 + v2
        }
        return value
    }

    private fun clampToShort(value: Float): Short =
        value.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()

    private fun readSample(buffer: java.nio.ByteBuffer): Float =
        if (encoding == C.ENCODING_PCM_FLOAT) buffer.float else buffer.short.toFloat()

    private fun writeSample(buffer: java.nio.ByteBuffer, value: Float) {
        if (encoding == C.ENCODING_PCM_FLOAT) {
            buffer.putFloat(value.coerceIn(-1f, 1f))
        } else {
            buffer.putShort(clampToShort(value))
        }
    }

    companion object {
        private const val TAG = "BitChordTransitionFilter"

        /** A low-pass at or above this is doing nothing audible, so it counts as off. */
        const val OPEN_HZ = 20_000f

        /** A high-pass at or below this is doing nothing audible, so it counts as off. */
        const val OFF_HZ = 20f

        /** Nothing musical wants the low end lifted above this, and a typo shouldn't be able to. */
        const val MAX_HIGH_PASS_HZ = 2_000f

        private const val MIN_HZ = 10f
        /** Two cascaded second-order sections: 24 dB/octave, the usual DJ-filter slope. */
        private const val STAGES = 2

        /** Section Qs for a maximally flat (Butterworth) fourth-order response. */
        private val BUTTERWORTH_Q = floatArrayOf(0.54120f, 1.30656f)

        /** Frames between coefficient updates. ~1.5 ms at 44.1 kHz. */
        private const val GLIDE_FRAMES = 64

        /** Per-sub-block glide fraction. ~30 ms time constant, just under one fade tick. */
        private const val GLIDE_RATE = 0.05f

        /** How close to a parked value counts as parked, so a glide terminates. */
        private const val SETTLED_HZ = 1f

        /** Keeps `tan` away from its pole at Nyquist. */
        private const val MAX_CUTOFF_FRACTION = 0.45f
    }
}

/**
 * The two filters a transition rides: one over the track arriving, one over the
 * track leaving.
 *
 * An interface rather than the processors themselves so [CrossfadeController]
 * stays testable without an audio sink, and so it never has to know that
 * "incoming" and "outgoing" are two different ExoPlayers whose roles swap at the
 * lap.
 */
interface TransitionFilters {
    /**
     * Freezes which concrete processors belong to the incoming and outgoing
     * tracks for this audible overlap. The MediaSession may swap owners halfway
     * through the blend, but the filters must keep following the same two audio
     * streams rather than swapping with the UI/session roles.
     */
    fun begin() = Unit

    /** The track fading up, regardless of which player currently owns the session. */
    fun incoming(lowPassHz: Float, highPassHz: Float)

    /** The track fading out, regardless of which player currently owns the session. */
    fun outgoing(lowPassHz: Float, highPassHz: Float)

    /**
     * Three-band DJ EQ automation for the arriving deck. Default no-op keeps
     * older/test bindings source-compatible.
     */
    fun incomingEq(lowDb: Float, midDb: Float, highDb: Float) = Unit

    /** Three-band DJ EQ automation for the departing deck. */
    fun outgoingEq(lowDb: Float, midDb: Float, highDb: Float) = Unit

    fun incomingRms(): Float? = null
    fun outgoingRms(): Float? = null

    /** Parks filters and EQ on both decks. Called however a transition ended. */
    fun open() {
        incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        incomingEq(0f, 0f, 0f)
        outgoingEq(0f, 0f, 0f)
    }

    /** For callers with no audio sink to filter — tests, and the default wiring. */
    object None : TransitionFilters {
        override fun incoming(lowPassHz: Float, highPassHz: Float) = Unit
        override fun outgoing(lowPassHz: Float, highPassHz: Float) = Unit
    }
}
