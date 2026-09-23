package com.music.orb.playback.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp

/**
 * Per-deck DSP stage used only while Automix is active.
 *
 * Besides the three broad EQ bands, v3 adds independently automated high-pass
 * and low-pass stages. This lets RISE perform an actual filter sweep instead of
 * faking one by changing band gains. All parameters are smoothed to avoid
 * zipper noise.
 */
@UnstableApi
class AutomixEqProcessor : BaseAudioProcessor() {
    @Volatile var enabled: Boolean = false
    @Volatile var targetLow: Float = 1f
    @Volatile var targetMid: Float = 1f
    @Volatile var targetHigh: Float = 1f
    @Volatile var targetHighPassHz: Float = 0f
    @Volatile var targetLowPassHz: Float = 20_000f

    private var lowGain = 1f
    private var midGain = 1f
    private var highGain = 1f
    private var lowAlpha = 0f
    private var highAlpha = 0f
    private var smooth = 0.001f
    private var sampleRate = 48_000f
    private val lowState = FloatArray(2)
    private val highLpState = FloatArray(2)
    private val highPassLowState = FloatArray(2)
    private val lowPassState = FloatArray(2)

    fun neutral() {
        targetLow = 1f
        targetMid = 1f
        targetHigh = 1f
        targetHighPassHz = 0f
        targetLowPassHz = 20_000f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate.toFloat()
        lowAlpha = onePoleAlpha(220f, sampleRate)
        highAlpha = onePoleAlpha(4_200f, sampleRate)
        smooth = (1f - exp(-1f / (sampleRate * 0.020f))).coerceIn(0.0001f, 0.05f)
        return inputAudioFormat
    }

    override fun onFlush() {
        lowState.fill(0f)
        highLpState.fill(0f)
        highPassLowState.fill(0f)
        lowPassState.fill(0f)
        lowGain = targetLow
        midGain = targetMid
        highGain = targetHigh
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val bytesPerFrame = channels * 2
        val frames = inputBuffer.remaining() / bytesPerFrame
        if (frames <= 0) return
        val output = replaceOutputBuffer(frames * bytesPerFrame)
        if (!enabled) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())

        // Controller updates targets at ~25 Hz. Resolve coefficients once per
        // PCM buffer, then the audio states themselves stay continuous sample to
        // sample. This is cheap enough for two simultaneous decks.
        val highPassEnabled = targetHighPassHz >= 25f
        val lowPassEnabled = targetLowPassHz <= 18_500f
        val hpAlpha = onePoleAlpha(targetHighPassHz.coerceIn(20f, 5_000f), sampleRate)
        val lpAlpha = onePoleAlpha(targetLowPassHz.coerceIn(800f, 20_000f), sampleRate)

        repeat(frames) {
            lowGain += (targetLow - lowGain) * smooth
            midGain += (targetMid - midGain) * smooth
            highGain += (targetHigh - highGain) * smooth
            repeat(channels) { ch ->
                val x = inputBuffer.short / 32768f
                lowState[ch] += lowAlpha * (x - lowState[ch])
                highLpState[ch] += highAlpha * (x - highLpState[ch])
                val low = lowState[ch]
                val high = x - highLpState[ch]
                val mid = x - low - high
                var y = low * lowGain + mid * midGain + high * highGain

                if (highPassEnabled) {
                    highPassLowState[ch] += hpAlpha * (y - highPassLowState[ch])
                    y -= highPassLowState[ch]
                } else {
                    // Keep state warm so enabling the filter doesn't introduce
                    // a discontinuity on the first sample.
                    highPassLowState[ch] += 0.02f * (y - highPassLowState[ch])
                }

                if (lowPassEnabled) {
                    lowPassState[ch] += lpAlpha * (y - lowPassState[ch])
                    y = lowPassState[ch]
                } else {
                    lowPassState[ch] = y
                }

                output.putShort((y.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        output.flip()
    }

    private fun onePoleAlpha(cutoff: Float, sampleRate: Float): Float =
        (1f - exp((-2f * PI.toFloat() * cutoff) / sampleRate)).coerceIn(0f, 1f)
}
