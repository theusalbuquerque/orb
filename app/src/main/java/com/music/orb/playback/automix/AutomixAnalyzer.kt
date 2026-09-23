package com.music.orb.playback.automix

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import com.music.orb.data.TrackLog
import com.music.orb.playback.AudioCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executors
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orb-owned, local audio-analysis pipeline.
 *
 * This intentionally consumes decoded cached audio rather than Spotify Audio
 * Analysis metadata. The first revision focuses on the information the renderer
 * can already use safely: tempo/beat grid, downbeat-ish bar grid, loudness,
 * energy and key/mode. Structural/vocal models can be layered onto the same
 * immutable analysis object later without touching PlaybackService.
 */
class AutomixAnalyzer(
    private val cache: AutomixAnalysisCache,
    context: android.content.Context,
) {
    private val ml = AutomixOnnxAnalyzer(context.applicationContext)

    // A cancelled native inference may still be finishing. One worker prevents
    // its replacement from decoding another track concurrently, and closing
    // sessions is queued behind inference instead of racing it on the UI thread.
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "Orb-Automix-Analysis").apply { isDaemon = true }
    }
    private val dispatcher = worker.asCoroutineDispatcher()

    fun close() {
        if (!worker.isShutdown) {
            worker.execute { ml.close() }
            dispatcher.close()
        }
    }

    suspend fun analyze(mediaId: String, uri: Uri): AutomixAnalysis? = withContext(dispatcher) {
        ensureActive()
        val cached = cache.get(mediaId)
        if (cached != null && (cached.completeAnalysis || !AudioCache.isFullyCached(uri))) {
            return@withContext cached
        }

        val startedAt = android.os.SystemClock.elapsedRealtime()
        val decoded = decodeAvailable(uri) { ensureActive() } ?: return@withContext cached
        if (decoded.samples.size < decoded.sampleRate * 4) return@withContext null

        ensureActive()
        val dspRhythm = estimateTempo(decoded)
        ensureActive()
        val mlBeat = ml.refineBeats(decoded.samples, decoded.sampleRate, decoded.durationMs)
        val rhythm = if (mlBeat != null && mlBeat.confidence >= 0.28f) {
            val interval = 60_000f / mlBeat.bpm.coerceAtLeast(1f)
            Rhythm(mlBeat.bpm, max(dspRhythm.confidence, mlBeat.confidence), mlBeat.beatsMs.firstOrNull() ?: dspRhythm.firstBeatMs, interval)
        } else dspRhythm
        ensureActive()
        val curves = buildCurves(decoded)
        ensureActive()
        val key = estimateKey(decoded)
        val beats = mlBeat?.beatsMs?.takeIf { mlBeat.confidence >= 0.28f && it.size >= 4 } ?: buildBeatGrid(rhythm, decoded.durationMs)
        val bars = mlBeat?.downbeatsMs?.takeIf { mlBeat.confidence >= 0.28f && it.size >= 2 } ?: beats.filterIndexed { index, _ -> index % 4 == 0 }
        val tatums = buildTatums(beats)
        ensureActive()
        val tempoCurve = buildLocalTempoCurve(decoded, rhythm)
        ensureActive()
        val dspVocalCurve = buildVocalProbabilityCurve(decoded)
        ensureActive()
        val mlVocal = if (decoded.complete) ml.refineVocals(decoded.samples, decoded.sampleRate, decoded.durationMs) else null
        val vocalCurve = mergeVocalCurves(dspVocalCurve, mlVocal)
        ensureActive()
        val sections = buildSections(decoded.durationMs, bars, curves.energy, vocalCurve, tempoCurve, rhythm)
        ensureActive()
        val phrases = buildPhrases(decoded.durationMs, bars, sections, curves.energy, vocalCurve)
        val introCandidates = buildIntroCandidates(beats, curves.energy)
        val outroCandidates = buildOutroCandidates(decoded, bars, curves.energy, vocalCurve)
        val dropCandidates = sections.filter { it.isDrop }.map { it.startMs }

        ensureActive()
        AutomixAnalysis(
            mediaId = mediaId,
            analyzedAtMs = System.currentTimeMillis(),
            analyzedDurationMs = decoded.durationMs,
            completeAnalysis = decoded.complete,
            tempoBpm = rhythm.bpm,
            tempoConfidence = rhythm.confidence,
            beatModelConfidence = mlBeat?.confidence ?: 0f,
            firstBeatMs = rhythm.firstBeatMs,
            beatIntervalMs = rhythm.intervalMs,
            beatsMs = beats,
            barsMs = bars,
            tatumsMs = tatums,
            tempoCurveBpm = tempoCurve,
            keyPitchClass = key?.pitchClass,
            minorMode = key?.minor,
            keyConfidence = key?.confidence ?: 0f,
            loudnessCurve = curves.loudness,
            energyCurve = curves.energy,
            vocalProbabilityCurve = vocalCurve,
            vocalModelConfidence = mlVocal?.confidence ?: 0f,
            sections = sections,
            phrases = phrases,
            introCandidatesMs = introCandidates,
            outroCandidatesMs = outroCandidates,
            dropCandidatesMs = dropCandidates,
        ).also { analysis ->
            cache.put(analysis)
            TrackLog.d("AutomixPerf", "analysis completed in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms; " +
                "samples=${decoded.samples.size}; complete=${decoded.complete}; " +
                "javaHeapKiB=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024}; " +
                "nativeHeapKiB=${android.os.Debug.getNativeHeapAllocatedSize() / 1024}")
        }
    }

    private data class DecodedAudio(val sampleRate: Int, val samples: FloatArray, val complete: Boolean) {
        val durationMs: Long get() = samples.size * 1000L / sampleRate.coerceAtLeast(1)
    }

    private data class Rhythm(val bpm: Float, val confidence: Float, val firstBeatMs: Long, val intervalMs: Float)
    private data class Curves(val loudness: List<Float>, val energy: List<Float>)
    private data class KeyGuess(val pitchClass: Int, val minor: Boolean, val confidence: Float)

    private fun decodeAvailable(uri: Uri, checkActive: () -> Unit): DecodedAudio? {
        val fullSource = AudioCache.mediaDataSource(uri)
        val source = fullSource ?: AudioCache.headMediaDataSource(uri) ?: return null
        val complete = fullSource != null
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val output = FloatCollector(MAX_ANALYSIS_SAMPLES)
            val info = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            var sampleRate = inputFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = inputFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = C.ENCODING_PCM_16BIT
            var downsampleStride = (sampleRate / ANALYSIS_SAMPLE_RATE).coerceAtLeast(1)
            var sourceFrameIndex = 0L

            while (!sawOutputEnd && output.size < MAX_ANALYSIS_SAMPLES) {
                checkActive()
                if (!sawInputEnd) {
                    val index = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: continue
                        val size = runCatching { extractor.readSampleData(buffer, 0) }.getOrDefault(-1)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(index, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = format.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, C.ENCODING_PCM_16BIT)
                        downsampleStride = (sampleRate / ANALYSIS_SAMPLE_RATE).coerceAtLeast(1)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        decoder.getOutputBuffer(outIndex)?.let { buffer ->
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            sourceFrameIndex = appendPcmAsMono(buffer, pcmEncoding, channels, downsampleStride, sourceFrameIndex, output)
                        }
                        sawOutputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                }
            }

            if (output.size == 0) return null
            val analysisRate = (sampleRate / downsampleStride).coerceAtLeast(1)
            return DecodedAudio(analysisRate, output.toArray(), complete && sawOutputEnd)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            TrackLog.d("Automix", "analysis decode unavailable: ${t.message}")
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
            runCatching { source.close() }
        }
    }

    private fun appendPcmAsMono(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
        stride: Int,
        initialFrameIndex: Long,
        output: FloatCollector,
    ): Long {
        if (channels <= 0) return initialFrameIndex
        buffer.order(ByteOrder.nativeOrder())
        var frameIndex = initialFrameIndex
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> {
                while (buffer.remaining() >= 4 * channels && output.size < MAX_ANALYSIS_SAMPLES) {
                    var sum = 0f
                    repeat(channels) { sum += buffer.float.coerceIn(-1f, 1f) }
                    if (frameIndex % stride == 0L) output.add(sum / channels)
                    frameIndex++
                }
            }
            C.ENCODING_PCM_16BIT -> {
                while (buffer.remaining() >= 2 * channels && output.size < MAX_ANALYSIS_SAMPLES) {
                    var sum = 0f
                    repeat(channels) { sum += buffer.short / 32768f }
                    if (frameIndex % stride == 0L) output.add(sum / channels)
                    frameIndex++
                }
            }
            else -> Unit
        }
        return frameIndex
    }

    private fun estimateTempo(audio: DecodedAudio): Rhythm {
        val hop = 512
        val window = 1024
        if (audio.samples.size < window * 4) return Rhythm(120f, 0f, 0L, 500f)
        val frameCount = 1 + (audio.samples.size - window) / hop
        val energy = FloatArray(frameCount)
        for (frame in 0 until frameCount) {
            val start = frame * hop
            var sum = 0.0
            var i = 0
            while (i < window) {
                val s = audio.samples[start + i]
                sum += s * s
                i++
            }
            energy[frame] = sqrt((sum / window).toFloat())
        }
        val onset = FloatArray(frameCount)
        for (i in 1 until frameCount) onset[i] = max(0f, energy[i] - energy[i - 1])
        val mean = onset.average().toFloat()
        for (i in onset.indices) onset[i] = max(0f, onset[i] - mean * 0.55f)

        val fps = audio.sampleRate.toFloat() / hop
        var bestLag = 0
        var best = 0.0
        var second = 0.0
        val minLag = (fps * 60f / MAX_BPM).roundToInt().coerceAtLeast(1)
        val maxLag = (fps * 60f / MIN_BPM).roundToInt().coerceAtMost(onset.size / 2)
        for (lag in minLag..maxLag) {
            var score = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in lag until onset.size) {
                val a = onset[i]
                val b = onset[i - lag]
                score += a * b
                normA += a * a
                normB += b * b
            }
            val normalized = if (normA > 0 && normB > 0) score / sqrt(normA * normB) else 0.0
            if (normalized > best) {
                second = best
                best = normalized
                bestLag = lag
            } else if (normalized > second) second = normalized
        }
        if (bestLag <= 0) return Rhythm(120f, 0f, 0L, 500f)
        var bpm = fps * 60f / bestLag
        while (bpm < 80f) bpm *= 2f
        while (bpm > 176f) bpm /= 2f
        val effectiveLag = (fps * 60f / bpm).coerceAtLeast(1f)

        // Select the strongest onset phase modulo one beat.
        val phaseBins = effectiveLag.roundToInt().coerceAtLeast(1)
        val phaseScores = FloatArray(phaseBins)
        onset.forEachIndexed { index, value -> phaseScores[index % phaseBins] += value }
        val phase = phaseScores.indices.maxByOrNull { phaseScores[it] } ?: 0
        val firstBeatMs = (phase / fps * 1000f).roundToInt().toLong()
        val confidence = (best * 0.75 + (best - second).coerceAtLeast(0.0) * 1.5).toFloat().coerceIn(0f, 1f)
        return Rhythm(bpm, confidence, firstBeatMs, 60_000f / bpm)
    }

    private fun buildBeatGrid(rhythm: Rhythm, durationMs: Long): List<Long> {
        if (rhythm.intervalMs <= 0f) return emptyList()
        val result = ArrayList<Long>()
        var beat = rhythm.firstBeatMs.toFloat()
        while (beat < durationMs && result.size < 512) {
            if (beat >= 0) result += beat.roundToInt().toLong()
            beat += rhythm.intervalMs
        }
        return result
    }

    private fun buildCurves(audio: DecodedAudio): Curves {
        val bucket = (audio.sampleRate / 2).coerceAtLeast(1) // 500 ms
        val loudness = ArrayList<Float>()
        var index = 0
        while (index < audio.samples.size) {
            val end = minOf(audio.samples.size, index + bucket)
            var sum = 0.0
            var peak = 0f
            for (i in index until end) {
                val x = audio.samples[i]
                sum += x * x
                peak = max(peak, abs(x))
            }
            val rms = sqrt((sum / (end - index).coerceAtLeast(1)).toFloat())
            // dBFS-like curve mapped to 0..1; silence remains near zero.
            val db = 20f * (ln(max(rms, 1e-6f)) / ln(10f))
            val mapped = ((db + 60f) / 60f).coerceIn(0f, 1f)
            loudness += mapped
            index = end
        }
        val maxL = loudness.maxOrNull()?.coerceAtLeast(0.001f) ?: 1f
        val energy = loudness.map { (it / maxL).coerceIn(0f, 1f) }
        return Curves(loudness, energy)
    }

    private fun buildIntroCandidates(beats: List<Long>, energy: List<Float>): List<Long> {
        if (beats.isEmpty()) return listOf(0L)
        val result = linkedSetOf<Long>()
        result += beats.first()
        beats.getOrNull(4)?.let(result::add)
        beats.getOrNull(8)?.let(result::add)
        // If the intro begins almost silent, favor the first bar whose 500ms
        // bucket has meaningful energy instead of mixing dead air.
        val firstEnergeticBucket = energy.indexOfFirst { it >= 0.18f }
        if (firstEnergeticBucket >= 0) {
            val target = firstEnergeticBucket * 500L
            beats.minByOrNull { abs(it - target) }?.let(result::add)
        }
        return result.sorted()
    }


    private fun buildTatums(beats: List<Long>): List<Long> {
        if (beats.size < 2) return beats
        val result = ArrayList<Long>(beats.size * 2)
        for (i in 0 until beats.lastIndex) {
            val a = beats[i]
            val b = beats[i + 1]
            result += a
            result += a + (b - a) / 2L
        }
        result += beats.last()
        return result
    }

    private fun buildLocalTempoCurve(audio: DecodedAudio, global: Rhythm): List<Float> {
        val windowSamples = audio.sampleRate * LOCAL_TEMPO_WINDOW_SECONDS
        val hopSamples = audio.sampleRate * LOCAL_TEMPO_HOP_SECONDS
        if (audio.samples.size < windowSamples || global.bpm !in MIN_BPM..MAX_BPM) return listOf(global.bpm)
        val result = ArrayList<Float>()
        var start = 0
        while (start + windowSamples <= audio.samples.size) {
            result += estimateTempoAround(audio.samples, start, windowSamples, audio.sampleRate, global.bpm)
            start += hopSamples
        }
        return result.ifEmpty { listOf(global.bpm) }
    }

    private fun estimateTempoAround(samples: FloatArray, start: Int, length: Int, sampleRate: Int, seedBpm: Float): Float {
        val hop = 512
        val frames = ((length - hop) / hop).coerceAtLeast(4)
        val onset = FloatArray(frames)
        var previous = 0f
        for (frame in 0 until frames) {
            val base = start + frame * hop
            val end = minOf(samples.size, base + hop)
            var sum = 0.0
            for (i in base until end) sum += samples[i] * samples[i]
            val rms = sqrt((sum / (end - base).coerceAtLeast(1)).toFloat())
            onset[frame] = max(0f, rms - previous)
            previous = rms
        }
        val fps = sampleRate.toFloat() / hop
        val minBpm = (seedBpm * 0.82f).coerceAtLeast(MIN_BPM)
        val maxBpm = (seedBpm * 1.18f).coerceAtMost(MAX_BPM)
        val minLag = (fps * 60f / maxBpm).roundToInt().coerceAtLeast(1)
        val maxLag = (fps * 60f / minBpm).roundToInt().coerceAtMost(onset.lastIndex)
        var bestLag = 0
        var best = -1.0
        for (lag in minLag..maxLag) {
            var score = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in lag until onset.size) {
                val a = onset[i]
                val b = onset[i - lag]
                score += a * b
                normA += a * a
                normB += b * b
            }
            val normalized = if (normA > 0.0 && normB > 0.0) score / sqrt(normA * normB) else 0.0
            if (normalized > best) { best = normalized; bestLag = lag }
        }
        return if (bestLag > 0) (fps * 60f / bestLag).coerceIn(MIN_BPM, MAX_BPM) else seedBpm
    }

    private fun mergeVocalCurves(heuristic: List<Float>, mlResult: AutomixOnnxAnalyzer.VocalResult?): List<Float> {
        if (mlResult == null || heuristic.isEmpty()) return heuristic
        val mlCurve = mlResult.curve
        val mlWeight = (0.45f + mlResult.confidence * 0.35f).coerceIn(0.45f, 0.78f)
        return List(max(heuristic.size, mlCurve.size)) { i ->
            val h = heuristic.getOrElse(i) { 0.35f }
            val m = mlCurve.getOrElse(i) { -1f }
            if (m < 0f) h else (h * (1f - mlWeight) + m * mlWeight).coerceIn(0f, 1f)
        }
    }

    private fun buildVocalProbabilityCurve(audio: DecodedAudio): List<Float> {
        val bucket = (audio.sampleRate / 2).coerceAtLeast(1)
        val result = ArrayList<Float>()
        var start = 0
        while (start < audio.samples.size) {
            val end = minOf(audio.samples.size, start + bucket)
            val n = (end - start).coerceAtLeast(1)
            var rmsSum = 0.0
            var zc = 0
            var previous = audio.samples[start]
            for (i in start until end) {
                val x = audio.samples[i]
                rmsSum += x * x
                if ((x >= 0f) != (previous >= 0f)) zc++
                previous = x
            }
            val rms = sqrt((rmsSum / n).toFloat())
            val zcr = zc.toFloat() / n
            val mid = bandPower(audio.samples, start, end, audio.sampleRate, VOCAL_FREQS)
            val low = bandPower(audio.samples, start, end, audio.sampleRate, LOW_FREQS)
            val high = bandPower(audio.samples, start, end, audio.sampleRate, HIGH_FREQS)
            val spectralRatio = (mid / (mid + low + high + 1e-9)).toFloat().coerceIn(0f, 1f)
            val voicedZcr = (1f - (kotlin.math.abs(zcr - 0.08f) / 0.10f)).coerceIn(0f, 1f)
            val activity = (rms * 14f).coerceIn(0f, 1f)
            val probability = (spectralRatio * 0.62f + voicedZcr * 0.23f + activity * 0.15f).coerceIn(0f, 1f)
            result += probability
            start = end
        }
        return smoothCurve(result)
    }

    private fun bandPower(samples: FloatArray, start: Int, end: Int, sampleRate: Int, frequencies: DoubleArray): Double {
        if (end - start < 32) return 0.0
        var total = 0.0
        for (frequency in frequencies) {
            val omega = 2.0 * PI * frequency / sampleRate
            val coeff = 2.0 * cos(omega)
            var s1 = 0.0
            var s2 = 0.0
            var i = start
            while (i < end) {
                val s0 = samples[i] + coeff * s1 - s2
                s2 = s1
                s1 = s0
                i += 2
            }
            total += s1 * s1 + s2 * s2 - coeff * s1 * s2
        }
        return total / frequencies.size.coerceAtLeast(1)
    }

    private fun smoothCurve(input: List<Float>): List<Float> {
        if (input.size < 3) return input
        return input.indices.map { i ->
            var sum = 0f
            var n = 0
            for (j in maxOf(0, i - 1)..minOf(input.lastIndex, i + 1)) { sum += input[j]; n++ }
            sum / n.coerceAtLeast(1)
        }
    }

    private fun buildSections(
        durationMs: Long,
        bars: List<Long>,
        energy: List<Float>,
        vocal: List<Float>,
        tempoCurve: List<Float>,
        global: Rhythm,
    ): List<AutomixSection> {
        if (durationMs <= 0L) return emptyList()
        val boundaries = linkedSetOf(0L)
        var lastBoundary = 0L
        for (i in 2 until energy.size) {
            val before = (energy[i - 2] + energy[i - 1]) / 2f
            val after = energy[i]
            val at = i * AutomixAnalysis.FEATURE_BUCKET_MS
            if (kotlin.math.abs(after - before) >= SECTION_ENERGY_DELTA && at - lastBoundary >= MIN_SECTION_MS) {
                val aligned = nearestBar(at, bars)
                if (aligned - lastBoundary >= MIN_SECTION_MS) {
                    boundaries += aligned
                    lastBoundary = aligned
                }
            }
        }
        boundaries += durationMs
        val sorted = boundaries.toList().distinct().sorted()
        val sections = ArrayList<AutomixSection>()
        for (i in 0 until sorted.lastIndex) {
            val start = sorted[i]
            val end = sorted[i + 1]
            if (end <= start) continue
            val e = averageCurve(energy, start, end, 0.5f)
            val v = averageCurve(vocal, start, end, 0.35f)
            val tempoIndex = (start / (LOCAL_TEMPO_HOP_SECONDS * 1000L)).toInt().coerceIn(0, tempoCurve.lastIndex.coerceAtLeast(0))
            val localTempo = tempoCurve.getOrNull(tempoIndex) ?: global.bpm
            val previousEnergy = sections.lastOrNull()?.energy ?: e
            val isDrop = e >= DROP_MIN_ENERGY && e - previousEnergy >= DROP_ENERGY_JUMP
            sections += AutomixSection(start, end, e, localTempo, global.confidence, v, isDrop)
        }
        return sections
    }

    private fun buildPhrases(
        durationMs: Long,
        bars: List<Long>,
        sections: List<AutomixSection>,
        energy: List<Float>,
        vocal: List<Float>,
    ): List<AutomixPhrase> {
        val boundaries = linkedSetOf<Long>()
        boundaries += 0L
        bars.filterIndexed { index, _ -> index % PHRASE_BARS == 0 }.forEach(boundaries::add)
        sections.forEach { boundaries += it.startMs }
        boundaries += durationMs
        val sorted = boundaries.filter { it in 0L..durationMs }.distinct().sorted()
        return (0 until sorted.lastIndex).mapNotNull { i ->
            val start = sorted[i]
            val end = sorted[i + 1]
            if (end - start < 1_000L) null else AutomixPhrase(
                startMs = start,
                endMs = end,
                energy = averageCurve(energy, start, end, 0.5f),
                vocalProbability = averageCurve(vocal, start, end, 0.35f),
            )
        }
    }

    private fun buildOutroCandidates(
        audio: DecodedAudio,
        bars: List<Long>,
        energy: List<Float>,
        vocal: List<Float>,
    ): List<Long> {
        if (!audio.complete || bars.isEmpty()) return emptyList()
        val duration = audio.durationMs
        val result = linkedSetOf<Long>()
        listOf(32, 16, 8, 4).forEach { beatsBack ->
            val barsBack = (beatsBack / 4).coerceAtLeast(1)
            bars.getOrNull((bars.size - barsBack).coerceAtLeast(0))?.let { if (it < duration) result += it }
        }
        val tailStart = (duration - 30_000L).coerceAtLeast(0L)
        val firstLowEnergy = ((tailStart / AutomixAnalysis.FEATURE_BUCKET_MS).toInt()..energy.lastIndex).firstOrNull { i ->
            energy[i] <= 0.38f && vocal.getOrElse(i) { 0.35f } <= 0.55f
        }
        if (firstLowEnergy != null) result += nearestBar(firstLowEnergy * AutomixAnalysis.FEATURE_BUCKET_MS, bars)
        return result.filter { it in 0L until duration }.sorted()
    }

    private fun nearestBar(positionMs: Long, bars: List<Long>): Long =
        bars.minByOrNull { kotlin.math.abs(it - positionMs) } ?: positionMs

    private fun averageCurve(curve: List<Float>, startMs: Long, endMs: Long, default: Float): Float {
        if (curve.isEmpty()) return default
        val from = (startMs / AutomixAnalysis.FEATURE_BUCKET_MS).toInt().coerceIn(0, curve.lastIndex)
        val to = ((endMs - 1L).coerceAtLeast(startMs) / AutomixAnalysis.FEATURE_BUCKET_MS).toInt().coerceIn(from, curve.lastIndex)
        var sum = 0f
        var n = 0
        for (i in from..to) { sum += curve[i]; n++ }
        return if (n > 0) sum / n else default
    }

    private fun estimateKey(audio: DecodedAudio): KeyGuess? {
        val stride = (audio.sampleRate / KEY_SAMPLE_RATE).coerceAtLeast(1)
        val maxInput = minOf(audio.samples.size, audio.sampleRate * KEY_ANALYSIS_SECONDS)
        if (maxInput < audio.sampleRate * 4) return null
        val decimated = FloatArray((maxInput + stride - 1) / stride)
        var out = 0
        var i = 0
        while (i < maxInput) {
            decimated[out++] = audio.samples[i]
            i += stride
        }
        val rate = audio.sampleRate.toFloat() / stride
        val chroma = DoubleArray(12)
        for (pc in 0 until 12) {
            var power = 0.0
            for (octave in 3..5) {
                val midi = 12 * (octave + 1) + pc
                val freq = 440.0 * 2.0.pow((midi - 69) / 12.0)
                power += goertzel(decimated, rate, freq)
            }
            chroma[pc] = power
        }
        val total = chroma.sum()
        if (total <= 0.0) return null
        for (pc in chroma.indices) chroma[pc] /= total

        var bestScore = Double.NEGATIVE_INFINITY
        var secondScore = Double.NEGATIVE_INFINITY
        var bestPc = 0
        var bestMinor = false
        for (root in 0 until 12) {
            val major = profileScore(chroma, MAJOR_PROFILE, root)
            val minor = profileScore(chroma, MINOR_PROFILE, root)
            listOf(major to false, minor to true).forEach { (score, isMinor) ->
                if (score > bestScore) {
                    secondScore = bestScore
                    bestScore = score
                    bestPc = root
                    bestMinor = isMinor
                } else if (score > secondScore) secondScore = score
            }
        }
        val margin = if (bestScore.isFinite() && secondScore.isFinite()) (bestScore - secondScore) else 0.0
        return KeyGuess(bestPc, bestMinor, (0.35 + margin * 3.5).toFloat().coerceIn(0f, 1f))
    }

    private fun goertzel(samples: FloatArray, sampleRate: Float, frequency: Double): Double {
        val omega = 2.0 * PI * frequency / sampleRate
        val coeff = 2.0 * cos(omega)
        var s0: Double
        var s1 = 0.0
        var s2 = 0.0
        // Sparse Hann window reduces leakage without allocating another buffer.
        for (i in samples.indices step 2) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / max(1, samples.lastIndex))
            s0 = samples[i] * w + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun profileScore(chroma: DoubleArray, profile: DoubleArray, root: Int): Double {
        var sum = 0.0
        for (i in 0 until 12) sum += chroma[(root + i) % 12] * profile[i]
        return sum
    }

    private fun MediaFormat.getIntegerOr(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private class FloatCollector(private val capacity: Int) {
        private var data = FloatArray(minOf(capacity, ANALYSIS_SAMPLE_RATE * 16))
        var size: Int = 0
            private set
        fun add(value: Float) {
            if (size >= capacity) return
            if (size == data.size) data = data.copyOf(minOf(capacity, data.size * 2))
            data[size++] = value
        }
        fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
    }

    private companion object {
        const val CODEC_TIMEOUT_US = 8_000L
        const val ANALYSIS_SAMPLE_RATE = 11_025
        const val MAX_ANALYSIS_SECONDS = 240
        const val MAX_ANALYSIS_SAMPLES = ANALYSIS_SAMPLE_RATE * MAX_ANALYSIS_SECONDS
        const val MIN_BPM = 60f
        const val MAX_BPM = 200f
        const val KEY_SAMPLE_RATE = 11_025
        const val KEY_ANALYSIS_SECONDS = 24
        const val LOCAL_TEMPO_WINDOW_SECONDS = 8
        const val LOCAL_TEMPO_HOP_SECONDS = 4
        const val PHRASE_BARS = 8
        const val MIN_SECTION_MS = 6_000L
        const val SECTION_ENERGY_DELTA = 0.18f
        const val DROP_MIN_ENERGY = 0.62f
        const val DROP_ENERGY_JUMP = 0.16f

        val VOCAL_FREQS = doubleArrayOf(250.0, 400.0, 700.0, 1_100.0, 1_700.0, 2_500.0, 3_400.0)
        val LOW_FREQS = doubleArrayOf(70.0, 110.0, 160.0)
        val HIGH_FREQS = doubleArrayOf(4_500.0, 5_000.0)

        val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    }
}
