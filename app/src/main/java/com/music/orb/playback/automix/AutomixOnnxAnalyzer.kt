package com.music.orb.playback.automix

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.music.orb.data.TrackLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device ML refinement for Automix backed directly by ONNX Runtime Android.
 *
 * ORT is a normal app dependency, so this path is intentionally typed rather
 * than reflective. Individual inference failures still fall back to the
 * deterministic DSP analyzer; a model/runtime failure must never stop playback.
 */
internal class AutomixOnnxAnalyzer(private val context: Context) : AutoCloseable {
    data class BeatResult(
        val beatsMs: List<Long>,
        val downbeatsMs: List<Long>,
        val bpm: Float,
        val confidence: Float,
    )

    data class VocalResult(
        val curve: List<Float>,
        val confidence: Float,
    )

    private val runtimeDelegate = lazy { TypedOrt(context.applicationContext) }
    private val ort by runtimeDelegate

    fun refineBeats(samples: FloatArray, sampleRate: Int, durationMs: Long): BeatResult? {
        val runtime = ort
        return runCatching {
            val spec = logFrequencySpectrogram(samples, sampleRate)
            if (spec.frames < 16) return null
            val outputs = runtime.run(
                asset = BEAT_MODEL,
                inputName = "input_spectrogram",
                values = spec.values,
                shape = longArrayOf(1, spec.frames.toLong(), BEAT_BINS.toLong()),
            )
            val beat = outputs["beat"] ?: return null
            val downbeat = outputs["downbeat"] ?: return null
            val beatCurve = beat
            val downbeatCurve = downbeat
            val frameMs = spec.hop * 1000f / spec.sampleRate
            val beats = peakPick(beatCurve, 0.30f, (180f / frameMs).roundToInt())
                .map { (it * frameMs).roundToInt().toLong().coerceAtMost(durationMs) }
            if (beats.size < 4) return null
            val downbeats = peakPick(downbeatCurve, 0.27f, (650f / frameMs).roundToInt())
                .map { (it * frameMs).roundToInt().toLong().coerceAtMost(durationMs) }
            val intervals = beats.zipWithNext { a, b -> (b - a).toFloat() }.filter { it in 250f..1200f }.sorted()
            if (intervals.isEmpty()) return null
            val median = intervals[intervals.size / 2]
            var bpm = 60_000f / median
            while (bpm < 80f) bpm *= 2f
            while (bpm > 176f) bpm /= 2f
            val peakConfidence = beats.mapNotNull { ms ->
                val i = (ms / frameMs).roundToInt().takeIf { it in beatCurve.indices }
                i?.let { beatCurve[it] }
            }.average().toFloat().coerceIn(0f, 1f)
            BeatResult(beats, downbeats, bpm, peakConfidence)
        }.onFailure { TrackLog.d("AutomixML", "Beat model fallback: ${it.message}") }.getOrNull()
    }

    /**
     * Runs the UMX-HQ magnitude model on the first and last ~22 s of the decoded
     * audio. The returned 500 ms curve is merged by confidence with the DSP
     * vocal heuristic, so a failed/weak ML pass cannot poison the transition.
     */
    fun refineVocals(samples: FloatArray, sampleRate: Int, durationMs: Long): VocalResult? {
        val runtime = ort
        if (samples.size < sampleRate * 4) return null
        return runCatching {
            val buckets = ((durationMs + AutomixAnalysis.FEATURE_BUCKET_MS - 1) / AutomixAnalysis.FEATURE_BUCKET_MS)
                .toInt().coerceAtLeast(1)
            val sum = FloatArray(buckets)
            val weight = FloatArray(buckets)
            val windowMs = VOCAL_FRAMES * VOCAL_HOP * 1000L / VOCAL_SR
            val starts = linkedSetOf(0L, (durationMs - windowMs).coerceAtLeast(0L))
            var modelConfidence = 0f
            for (startMs in starts) {
                val mono = resampleWindow(samples, sampleRate, startMs, VOCAL_SR, VOCAL_FRAMES * VOCAL_HOP + VOCAL_FFT)
                val magnitude = magnitudeSpectrogram(mono, VOCAL_FFT, VOCAL_HOP, VOCAL_FRAMES)
                val stereo = FloatArray(2 * VOCAL_BINS * VOCAL_FRAMES)
                // ONNX shape [1, 2, frequency, time]
                for (f in 0 until VOCAL_BINS) for (t in 0 until VOCAL_FRAMES) {
                    val v = magnitude[t * VOCAL_BINS + f]
                    stereo[f * VOCAL_FRAMES + t] = v
                    stereo[(VOCAL_BINS + f) * VOCAL_FRAMES + t] = v
                }
                val out = runtime.run(
                    asset = VOCAL_MODEL,
                    inputName = "mix_magnitude",
                    values = stereo,
                    shape = longArrayOf(1, 2, VOCAL_BINS.toLong(), VOCAL_FRAMES.toLong()),
                )[VOCAL_OUTPUT] ?: continue
                val target = out
                if (target.size < stereo.size) continue
                var ratioTotal = 0f
                for (t in 0 until VOCAL_FRAMES) {
                    var mixE = 1e-6
                    var vocalE = 0.0
                    // Focus on roughly 100 Hz..8 kHz, where voice evidence is useful.
                    val minBin = (100.0 * VOCAL_FFT / VOCAL_SR).roundToInt()
                    val maxBin = (8000.0 * VOCAL_FFT / VOCAL_SR).roundToInt().coerceAtMost(VOCAL_BINS - 1)
                    for (f in minBin..maxBin) {
                        val idx = f * VOCAL_FRAMES + t
                        val m = stereo[idx]
                        val v = target[idx]
                        mixE += m * m
                        vocalE += v * v
                    }
                    val ratio = sqrt((vocalE / mixE).coerceAtLeast(0.0)).toFloat().coerceIn(0f, 1f)
                    ratioTotal += ratio
                    val absoluteMs = startMs + t * VOCAL_HOP * 1000L / VOCAL_SR
                    val bucket = (absoluteMs / AutomixAnalysis.FEATURE_BUCKET_MS).toInt()
                    if (bucket in sum.indices) {
                        sum[bucket] += ratio
                        weight[bucket] += 1f
                    }
                }
                modelConfidence = max(modelConfidence, (ratioTotal / VOCAL_FRAMES).coerceIn(0.15f, 0.95f))
            }
            if (weight.none { it > 0f }) return null
            val curve = List(buckets) { i -> if (weight[i] > 0f) (sum[i] / weight[i]).coerceIn(0f, 1f) else -1f }
            VocalResult(curve, modelConfidence.coerceIn(0f, 1f))
        }.onFailure { TrackLog.d("AutomixML", "Vocal model fallback: ${it.message}") }.getOrNull()
    }

    private data class Spectrogram(val values: FloatArray, val frames: Int, val hop: Int, val sampleRate: Int)

    private fun logFrequencySpectrogram(samples: FloatArray, sampleRate: Int): Spectrogram {
        val fft = 1024
        val hop = (sampleRate * 0.020f).roundToInt().coerceAtLeast(128)
        val frames = ((samples.size - fft).coerceAtLeast(0) / hop + 1).coerceAtMost(12_000)
        val output = FloatArray(frames * BEAT_BINS)
        val window = FloatArray(fft) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (fft - 1))).toFloat() }
        val re = FloatArray(fft)
        val im = FloatArray(fft)
        val maxBin = fft / 2
        for (frame in 0 until frames) {
            val start = frame * hop
            for (i in 0 until fft) { re[i] = samples.getOrElse(start + i) { 0f } * window[i]; im[i] = 0f }
            fft(re, im)
            for (band in 0 until BEAT_BINS) {
                val x0 = band.toFloat() / BEAT_BINS
                val x1 = (band + 1f) / BEAT_BINS
                val f0 = 30.0 * exp(ln((sampleRate / 2.0) / 30.0) * x0)
                val f1 = 30.0 * exp(ln((sampleRate / 2.0) / 30.0) * x1)
                val b0 = (f0 * fft / sampleRate).roundToInt().coerceIn(1, maxBin)
                val b1 = (f1 * fft / sampleRate).roundToInt().coerceIn(b0, maxBin)
                var e = 0.0
                for (bin in b0..b1) e += re[bin] * re[bin] + im[bin] * im[bin]
                output[frame * BEAT_BINS + band] = kotlin.math.ln(1f + (e / (b1 - b0 + 1)).toFloat() * 12f)
            }
        }
        // Per-band standardization is much more stable across quiet/loud masters.
        for (band in 0 until BEAT_BINS) {
            var mean = 0f
            for (f in 0 until frames) mean += output[f * BEAT_BINS + band]
            mean /= frames.coerceAtLeast(1)
            var variance = 1e-4f
            for (f in 0 until frames) { val d = output[f * BEAT_BINS + band] - mean; variance += d * d }
            val std = sqrt(variance / frames.coerceAtLeast(1))
            for (f in 0 until frames) output[f * BEAT_BINS + band] = ((output[f * BEAT_BINS + band] - mean) / std).coerceIn(-4f, 4f)
        }
        return Spectrogram(output, frames, hop, sampleRate)
    }

    private fun magnitudeSpectrogram(samples: FloatArray, fftSize: Int, hop: Int, frames: Int): FloatArray {
        val bins = fftSize / 2 + 1
        val out = FloatArray(frames * bins)
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        val window = FloatArray(fftSize) { i -> sin(PI * (i + 0.5) / fftSize).toFloat() }
        for (frame in 0 until frames) {
            val start = frame * hop
            for (i in 0 until fftSize) { re[i] = samples.getOrElse(start + i) { 0f } * window[i]; im[i] = 0f }
            fft(re, im)
            for (bin in 0 until bins) out[frame * bins + bin] = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
        }
        return out
    }

    private fun resampleWindow(src: FloatArray, srcRate: Int, startMs: Long, dstRate: Int, count: Int): FloatArray {
        val out = FloatArray(count)
        val srcStart = startMs * srcRate / 1000.0
        val step = srcRate.toDouble() / dstRate
        for (i in out.indices) {
            val p = srcStart + i * step
            val a = p.toInt()
            val frac = (p - a).toFloat()
            val s0 = src.getOrElse(a) { 0f }
            val s1 = src.getOrElse(a + 1) { s0 }
            out[i] = s0 + (s1 - s0) * frac
        }
        return out
    }

    private fun peakPick(values: FloatArray, threshold: Float, minDistance: Int): List<Int> {
        if (values.size < 3) return emptyList()
        val peaks = ArrayList<Int>()
        var last = -minDistance
        for (i in 1 until values.lastIndex) {
            val v = values[i]
            if (v >= threshold && v >= values[i - 1] && v > values[i + 1]) {
                if (i - last >= minDistance) { peaks += i; last = i }
                else if (peaks.isNotEmpty() && v > values[peaks.last()]) { peaks[peaks.lastIndex] = i; last = i }
            }
        }
        return peaks
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = re[i]; re[i] = re[j]; re[j] = tr; val ti = im[i]; im[i] = im[j]; im[j] = ti }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenR = cos(angle).toFloat(); val wLenI = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1f; var wi = 0f
                for (k in 0 until len / 2) {
                    val uR = re[i + k]; val uI = im[i + k]
                    val vR = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vI = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = uR + vR; im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR; im[i + k + len / 2] = uI - vI
                    val nextWr = wr * wLenR - wi * wLenI
                    wi = wr * wLenI + wi * wLenR; wr = nextWr
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun close() {
        if (runtimeDelegate.isInitialized()) ort.close()
    }

    private class TypedOrt(private val context: Context) : AutoCloseable {
        private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
        private val sessions = HashMap<String, OrtSession>()
        private val lock = Any()

        fun run(
            asset: String,
            inputName: String,
            values: FloatArray,
            shape: LongArray,
        ): Map<String, FloatArray> {
            val session = sessionFor(asset, inputName)
            val expectedElements = shape.fold(1L) { acc, dim -> Math.multiplyExact(acc, dim) }
            require(expectedElements == values.size.toLong()) {
                "ONNX input $inputName for $asset expects $expectedElements values, got ${values.size}"
            }

            val bytes = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            val floats = bytes.asFloatBuffer()
            floats.put(values)
            floats.rewind()

            OnnxTensor.createTensor(env, floats, shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val outputs = LinkedHashMap<String, FloatArray>(session.outputNames.size)
                    for (name in session.outputNames) {
                        val value = result.get(name).orElse(null) as? OnnxTensor ?: continue
                        // Read contiguous primitives before Result closes. Avoid the
                        // nested arrays + millions of boxed Floats of getValue().
                        val buffer = value.floatBuffer ?: continue
                        outputs[name] = FloatArray(buffer.remaining()).also { buffer.get(it) }
                    }
                    return outputs
                }
            }
        }

        private fun sessionFor(asset: String, requiredInput: String): OrtSession = synchronized(lock) {
            sessions[asset] ?: run {
                val model = context.assets.open(asset).use { it.readBytes() }
                val created = OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    options.setInterOpNumThreads(1)
                    options.addConfigEntry("session.intra_op.allow_spinning", "0")
                    options.addConfigEntry("session.inter_op.allow_spinning", "0")
                    env.createSession(model, options)
                }
                try {
                    created.also { session ->
                        require(requiredInput in session.inputNames) {
                            "ONNX model $asset does not expose input '$requiredInput'; inputs=${session.inputNames}"
                        }
                        when (asset) {
                            BEAT_MODEL -> require(BEAT_OUTPUTS.all(session.outputNames::contains)) {
                                "ONNX model $asset outputs=${session.outputNames}; expected=$BEAT_OUTPUTS"
                            }
                            VOCAL_MODEL -> require(VOCAL_OUTPUT in session.outputNames) {
                                "ONNX model $asset outputs=${session.outputNames}; expected=$VOCAL_OUTPUT"
                            }
                        }
                        sessions[asset] = session
                    }
                } catch (error: Throwable) {
                    created.close()
                    throw error
                }
            }
        }

        override fun close() = synchronized(lock) {
            sessions.values.forEach { session ->
                runCatching { session.close() }
            }
            sessions.clear()
        }
    }

    companion object {
        private const val BEAT_MODEL = "beat_this_int8.onnx"
        private const val VOCAL_MODEL = "vocals_umxhq_int8.onnx"
        private const val VOCAL_OUTPUT = "target_magnitude"
        private val BEAT_OUTPUTS = setOf("beat", "downbeat")

        private const val BEAT_BINS = 128
        private const val VOCAL_SR = 44_100
        private const val VOCAL_FFT = 4096
        private const val VOCAL_HOP = 1024
        private const val VOCAL_FRAMES = 960
        private const val VOCAL_BINS = VOCAL_FFT / 2 + 1
    }
}
