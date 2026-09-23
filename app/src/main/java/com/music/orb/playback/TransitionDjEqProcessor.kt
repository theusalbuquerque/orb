package com.music.orb.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow

/**
 * Lightweight three-band DJ EQ used only while Automix is blending two decks.
 *
 * The existing transition filter is excellent for sweeps, but a DJ blend needs
 * a different move too: hand the low end from A to B without throwing away the
 * rest of either track.  This processor splits each channel into low, mid and
 * high bands with two gentle one-pole crossovers, applies independently glided
 * gains, and recombines them.  It is intentionally conservative -- the point is
 * separation and a clean bass swap, not mastering-grade EQ.
 */
@UnstableApi
class TransitionDjEqProcessor : BaseAudioProcessor() {

    @Volatile private var targetLowDb = 0f
    @Volatile private var targetMidDb = 0f
    @Volatile private var targetHighDb = 0f

    private var currentLow = 1f
    private var currentMid = 1f
    private var currentHigh = 1f

    private var sampleRate = 0
    private var channels = 0
    private var floatPcm = false
    private var bytesPerSample = 2
    private var lowAlpha = 0f
    private var highAlpha = 0f
    private var lowState = FloatArray(0)
    private var highLowState = FloatArray(0)

    fun setGains(lowDb: Float, midDb: Float, highDb: Float) {
        targetLowDb = lowDb.coerceIn(MIN_DB, MAX_DB)
        targetMidDb = midDb.coerceIn(MIN_DB, MAX_DB)
        targetHighDb = highDb.coerceIn(MIN_DB, MAX_DB)
    }

    fun open() = setGains(0f, 0f, 0f)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supported || inputAudioFormat.channelCount < 1) {
            Log.w(TAG, "DJ EQ inactive: encoding=${inputAudioFormat.encoding} channels=${inputAudioFormat.channelCount}")
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        floatPcm = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        bytesPerSample = if (floatPcm) 4 else 2
        lowState = FloatArray(channels)
        highLowState = FloatArray(channels)
        lowAlpha = onePoleAlpha(LOW_CROSSOVER_HZ)
        highAlpha = onePoleAlpha(HIGH_CROSSOVER_HZ)
        currentLow = dbToLinear(targetLowDb)
        currentMid = dbToLinear(targetMidDb)
        currentHigh = dbToLinear(targetHighDb)
        return inputAudioFormat
    }

    override fun onFlush() {
        lowState.fill(0f)
        highLowState.fill(0f)
        currentLow = dbToLinear(targetLowDb)
        currentMid = dbToLinear(targetMidDb)
        currentHigh = dbToLinear(targetHighDb)
    }

    override fun onReset() {
        targetLowDb = 0f
        targetMidDb = 0f
        targetHighDb = 0f
        lowState = FloatArray(0)
        highLowState = FloatArray(0)
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val bytesPerFrame = channels * bytesPerSample
        if (bytesPerFrame <= 0) return
        val frames = inputBuffer.remaining() / bytesPerFrame
        if (frames <= 0) return
        val output = replaceOutputBuffer(frames * bytesPerFrame)
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())

        val targetLow = dbToLinear(targetLowDb)
        val targetMid = dbToLinear(targetMidDb)
        val targetHigh = dbToLinear(targetHighDb)

        var remaining = frames
        while (remaining > 0) {
            val block = minOf(GLIDE_FRAMES, remaining)
            currentLow += (targetLow - currentLow) * GLIDE_RATE
            currentMid += (targetMid - currentMid) * GLIDE_RATE
            currentHigh += (targetHigh - currentHigh) * GLIDE_RATE

            repeat(block) {
                for (channel in 0 until channels) {
                    val x = readSample(inputBuffer)
                    val low = lowState[channel] + lowAlpha * (x - lowState[channel])
                    lowState[channel] = low
                    val belowHigh = highLowState[channel] + highAlpha * (x - highLowState[channel])
                    highLowState[channel] = belowHigh
                    val high = x - belowHigh
                    val mid = x - low - high
                    val y = low * currentLow + mid * currentMid + high * currentHigh
                    writeSample(output, y)
                }
            }
            remaining -= block
        }
        output.flip()
    }

    private fun onePoleAlpha(hz: Float): Float =
        (1.0 - exp(-2.0 * PI * hz / sampleRate.coerceAtLeast(1))).toFloat().coerceIn(0f, 1f)

    private fun readSample(buffer: java.nio.ByteBuffer): Float =
        if (floatPcm) buffer.float else buffer.short.toFloat()

    private fun writeSample(buffer: java.nio.ByteBuffer, value: Float) {
        if (floatPcm) {
            buffer.putFloat(value.coerceIn(-1f, 1f))
        } else {
            buffer.putShort(
                value.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort(),
            )
        }
    }

    private fun dbToLinear(db: Float): Float = 10.0.pow(db.toDouble() / 20.0).toFloat()

    companion object {
        private const val TAG = "BitChordDjEq"
        private const val LOW_CROSSOVER_HZ = 220f
        private const val HIGH_CROSSOVER_HZ = 3_800f
        private const val MIN_DB = -30f
        private const val MAX_DB = 6f
        private const val GLIDE_FRAMES = 64
        private const val GLIDE_RATE = 0.08f
    }
}
