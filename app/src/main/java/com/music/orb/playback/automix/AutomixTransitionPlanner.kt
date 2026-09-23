package com.music.orb.playback.automix

import kotlin.math.abs
import kotlin.math.roundToLong

/** Pure A -> B decision maker. No player/network/cache access lives here. */
object AutomixTransitionPlanner {

    fun plan(outgoing: AutomixTrack, incoming: AutomixTrack): TransitionPlan {
        val a = outgoing.analysis
        val b = incoming.analysis

        // Without structural evidence we retain a deterministic conservative fallback.
        if (a == null || b == null) return fallbackPlan(outgoing, incoming)

        val harmonic = harmonicCompatibility(a, b)
        val analysisConfidence = listOf(a.tempoConfidence, b.tempoConfidence).averageOr(0.25f)
        val starts = outgoingCandidates(outgoing)
        val cues = incomingCandidates(incoming)

        val scored = ArrayList<ScoredCandidate>(starts.size * cues.size)
        for (start in starts) {
            for (cue in cues) {
                scored += scoreCandidate(outgoing, incoming, start, cue, harmonic, analysisConfidence)
            }
        }

        val winner = scored.maxWithOrNull(
            compareBy<ScoredCandidate> { it.musicalScore }
                .thenBy { it.confidence }
                .thenBy { 1f - it.vocalRisk }
                .thenBy { -abs(it.startMs - (outgoing.durationMs - 8_000L)) }
        ) ?: return fallbackPlan(outgoing, incoming)

        val phase = calculatePhaseOffset(winner.startMs, winner.cueMs, a, b, winner.rate)
        val adjustedCue = (winner.cueMs + phase).coerceIn(0L, (incoming.durationMs - 250L).coerceAtLeast(0L))

        return TransitionPlan(
            outgoingMediaId = outgoing.mediaId,
            incomingMediaId = incoming.mediaId,
            style = winner.style,
            outgoingStartMs = winner.startMs,
            outgoingEndMs = outgoing.durationMs,
            incomingCueMs = adjustedCue,
            durationMs = winner.durationMs,
            incomingPlaybackRate = winner.rate,
            phaseOffsetMs = phase,
            tempoCompatibility = winner.rhythmScore,
            harmonicCompatibility = harmonic,
            structuralCompatibility = winner.structureScore,
            vocalOverlapRisk = winner.vocalRisk,
            energyCompatibility = winner.energyScore,
            musicalScore = winner.musicalScore,
            candidateCount = scored.size,
            confidence = winner.confidence,
        )
    }

    private fun scoreCandidate(
        outgoing: AutomixTrack,
        incoming: AutomixTrack,
        start: Long,
        cue: Long,
        harmonic: Float,
        analysisConfidence: Float,
    ): ScoredCandidate {
        val a = outgoing.analysis!!
        val b = incoming.analysis!!
        val rhythm = localTempoCompatibility(a, start, b, cue)
        val rate = beatMatchRateAt(a, start, b, cue)
        val beatMatched = rate != 1f
        val structure = candidateStructuralScore(a, start, b, cue)
        val energy = energyCompatibility(a, start, b, cue)
        val vocalRisk = vocalOverlapRisk(a, start, b, cue, outgoing.durationMs - start, rate)
        val energyDelta = incomingEnergyLift(a, start, b, cue)

        val style = when {
            beatMatched && rhythm >= 0.80f && harmonic >= 0.60f && structure >= 0.40f &&
                vocalRisk <= 0.64f && analysisConfidence >= 0.46f -> TransitionStyle.BLEND
            rhythm >= 0.54f && energyDelta > 0.10f && isDropOrBuildCue(b, cue) -> TransitionStyle.RISE
            rhythm >= 0.38f || structure >= 0.52f -> TransitionStyle.FADE
            else -> TransitionStyle.CUT
        }

        val duration = (outgoing.durationMs - start).coerceAtLeast(750L)
        val durationFitness = durationFitness(duration, idealDurationMs(a, start, b, cue, harmonic, style), style)
        val styleFitness = styleFitness(style, rhythm, harmonic, structure, energy, vocalRisk, energyDelta)

        // Global musical score. Vocal collision receives an explicit negative weight because
        // avoiding simultaneous lead vocals is more important than squeezing out a tiny BPM gain.
        val weighted = when (style) {
            TransitionStyle.BLEND -> 0.25f * rhythm + 0.18f * harmonic + 0.18f * structure +
                0.14f * energy + 0.10f * durationFitness + 0.15f * (1f - vocalRisk)
            TransitionStyle.RISE -> 0.19f * rhythm + 0.10f * harmonic + 0.21f * structure +
                0.24f * energy + 0.11f * durationFitness + 0.15f * (1f - vocalRisk)
            TransitionStyle.FADE -> 0.14f * rhythm + 0.13f * harmonic + 0.22f * structure +
                0.18f * energy + 0.14f * durationFitness + 0.19f * (1f - vocalRisk)
            TransitionStyle.CUT -> 0.07f * rhythm + 0.08f * harmonic + 0.29f * structure +
                0.17f * energy + 0.17f * durationFitness + 0.22f * (1f - vocalRisk)
        }
        val score = (weighted * (0.78f + 0.22f * styleFitness)).coerceIn(0f, 1f)
        val confidence = (
            0.30f * analysisConfidence +
                0.20f * rhythm +
                0.18f * structure +
                0.12f * energy +
                0.20f * (1f - vocalRisk)
            ).coerceIn(0f, 1f)

        return ScoredCandidate(
            startMs = start,
            cueMs = cue,
            durationMs = duration,
            style = style,
            rate = rate,
            rhythmScore = rhythm,
            structureScore = structure,
            energyScore = energy,
            vocalRisk = vocalRisk,
            musicalScore = score,
            confidence = confidence,
        )
    }

    private fun outgoingCandidates(track: AutomixTrack): List<Long> {
        val a = track.analysis ?: return listOf((track.durationMs - 6_000L).coerceAtLeast(0L))
        val duration = track.durationMs
        val preferredWindowStart = (duration - MAX_OUTGOING_SEARCH_MS).coerceAtLeast(0L)
        val raw = buildList {
            addAll(a.outroCandidatesMs)
            addAll(a.phrases.flatMap { listOf(it.startMs, it.endMs) })
            addAll(a.sections.flatMap { listOf(it.startMs, it.endMs) })
            addAll(a.barsMs.takeLast(MAX_GRID_CANDIDATES))
            // Keep several deterministic duration targets even when structural detection is sparse.
            for (ms in longArrayOf(4_000L, 6_000L, 8_000L, 12_000L, 16_000L)) add(duration - ms)
        }
        return raw.asSequence()
            .filter { it in preferredWindowStart until duration }
            .filter { duration - it in MIN_TRANSITION_MS..MAX_TRANSITION_MS }
            .map { alignToNearestGrid(it, a) }
            .distinct()
            .sorted()
            .toList()
            .takeLast(MAX_SIDE_CANDIDATES)
            .ifEmpty { listOf((duration - 6_000L).coerceAtLeast(0L)) }
    }

    private fun incomingCandidates(track: AutomixTrack): List<Long> {
        val b = track.analysis ?: return listOf(0L)
        val raw = buildList {
            addAll(b.introCandidatesMs)
            addAll(b.dropCandidatesMs.filter { it <= MAX_INCOMING_SEARCH_MS })
            addAll(b.phrases.take(MAX_STRUCTURE_CANDIDATES).flatMap { listOf(it.startMs, it.endMs) })
            addAll(b.sections.take(MAX_STRUCTURE_CANDIDATES).map { it.startMs })
            addAll(b.barsMs.filter { it <= MAX_INCOMING_SEARCH_MS }.take(MAX_GRID_CANDIDATES))
            add(b.firstBeatMs.coerceAtLeast(0L))
            add(0L)
        }
        return raw.asSequence()
            .filter { it in 0L..minOf(track.durationMs.coerceAtLeast(0L), MAX_INCOMING_SEARCH_MS) }
            .map { alignToNearestGrid(it, b).coerceAtLeast(0L) }
            .distinct()
            .sorted()
            .take(MAX_SIDE_CANDIDATES)
            .toList()
            .ifEmpty { listOf(0L) }
    }

    private fun candidateStructuralScore(a: AutomixAnalysis, start: Long, b: AutomixAnalysis, cue: Long): Float {
        var score = 0.28f
        if (nearAny(start, a.outroCandidatesMs, 1_000L)) score += 0.20f
        if (nearBoundary(start, a.phrases, 850L)) score += 0.14f
        if (nearSectionBoundary(start, a.sections, 850L)) score += 0.08f
        if (nearAny(cue, b.introCandidatesMs, 1_000L)) score += 0.14f
        if (nearBoundary(cue, b.phrases, 850L)) score += 0.09f
        if (nearAny(cue, b.dropCandidatesMs, 1_000L)) score += 0.07f
        if (a.completeAnalysis) score += 0.03f
        if (b.completeAnalysis) score += 0.03f
        return score.coerceIn(0f, 1f)
    }

    private fun energyCompatibility(a: AutomixAnalysis, start: Long, b: AutomixAnalysis, cue: Long): Float {
        val a0 = a.energyAt(start)
        val a1 = a.energyAt(start + 4_000L)
        val b0 = b.energyAt(cue)
        val b1 = b.energyAt(cue + 4_000L)
        val boundaryMatch = 1f - abs(a0 - b0)
        val directionA = a1 - a0
        val directionB = b1 - b0
        val trajectory = (1f - abs(directionA - directionB)).coerceIn(0f, 1f)
        // A rising incoming track is desirable when A is decaying, not a mismatch.
        val complementary = if (directionA < -0.04f && directionB > 0.04f) 1f else trajectory
        return (0.62f * boundaryMatch + 0.38f * complementary).coerceIn(0f, 1f)
    }

    private fun incomingEnergyLift(a: AutomixAnalysis, start: Long, b: AutomixAnalysis, cue: Long): Float {
        val outgoing = (a.energyAt(start) + a.energyAt(start + 3_000L)) * 0.5f
        val incoming = (b.energyAt(cue + 2_000L) + b.energyAt(cue + 5_000L)) * 0.5f
        return incoming - outgoing
    }

    private fun vocalOverlapRisk(
        a: AutomixAnalysis,
        aStart: Long,
        b: AutomixAnalysis,
        bCue: Long,
        durationMs: Long,
        incomingRate: Float,
    ): Float {
        val samples = 7
        var productSum = 0f
        var peak = 0f
        for (i in 0 until samples) {
            val f = i.toFloat() / (samples - 1).coerceAtLeast(1)
            val aPos = aStart + (durationMs * f).roundToLong()
            val bPos = bCue + ((durationMs * f) * incomingRate).roundToLong()
            val product = a.vocalProbabilityAt(aPos) * b.vocalProbabilityAt(bPos)
            productSum += product
            if (product > peak) peak = product
        }
        val average = productSum / samples
        return (0.66f * average + 0.34f * peak).coerceIn(0f, 1f)
    }

    private fun localTempoCompatibility(a: AutomixAnalysis, aPos: Long, b: AutomixAnalysis, bPos: Long): Float {
        var x = tempoAt(a, aPos) ?: return 0.45f
        var y = tempoAt(b, bPos) ?: return 0.45f
        while (x / y > 1.5f) y *= 2f
        while (y / x > 1.5f) y /= 2f
        val ratio = abs(x - y) / maxOf(x, y)
        return (1f - ratio / 0.12f).coerceIn(0f, 1f)
    }

    private fun beatMatchRateAt(a: AutomixAnalysis, aPos: Long, b: AutomixAnalysis, bPos: Long): Float {
        if (!a.usableTempo || !b.usableTempo) return 1f
        var target = tempoAt(a, aPos) ?: a.tempoBpm
        var source = tempoAt(b, bPos) ?: b.tempoBpm
        while (source / target > 1.5f) source /= 2f
        while (target / source > 1.5f) source *= 2f
        val rate = target / source
        return if (abs(rate - 1f) <= MAX_RATE_DEVIATION && minOf(a.tempoConfidence, b.tempoConfidence) >= 0.45f) {
            rate.coerceIn(1f - MAX_RATE_DEVIATION, 1f + MAX_RATE_DEVIATION)
        } else 1f
    }

    private fun tempoAt(analysis: AutomixAnalysis, positionMs: Long): Float? {
        analysis.sections.firstOrNull { positionMs in it.startMs until it.endMs }
            ?.tempoBpm?.takeIf { it in 50f..220f }?.let { return it }
        if (analysis.tempoCurveBpm.isNotEmpty()) {
            val total = analysis.analyzedDurationMs.coerceAtLeast(1L)
            val index = ((positionMs.toDouble() / total) * analysis.tempoCurveBpm.size)
                .toInt().coerceIn(0, analysis.tempoCurveBpm.lastIndex)
            analysis.tempoCurveBpm[index].takeIf { it in 50f..220f }?.let { return it }
        }
        return analysis.tempoBpm.takeIf { it in 50f..220f }
    }

    private fun isDropOrBuildCue(b: AutomixAnalysis, cue: Long): Boolean {
        if (nearAny(cue, b.dropCandidatesMs, 1_250L)) return true
        val before = b.energyAt((cue - 3_000L).coerceAtLeast(0L))
        val after = b.energyAt(cue + 3_000L)
        return after - before >= 0.12f
    }

    private fun idealDurationMs(
        a: AutomixAnalysis,
        aPos: Long,
        b: AutomixAnalysis,
        bPos: Long,
        harmonic: Float,
        style: TransitionStyle,
    ): Long {
        val beats = when (style) {
            TransitionStyle.BLEND -> if (harmonic >= 0.80f) 32 else 16
            TransitionStyle.RISE -> 16
            TransitionStyle.FADE -> 12
            TransitionStyle.CUT -> 4
        }
        val bpm = tempoAt(a, aPos) ?: tempoAt(b, bPos) ?: 120f
        return (beats * 60_000f / bpm).roundToLong().coerceIn(
            if (style == TransitionStyle.CUT) 1_200L else 3_500L,
            if (style == TransitionStyle.BLEND) 18_000L else 12_000L,
        )
    }

    private fun durationFitness(duration: Long, ideal: Long, style: TransitionStyle): Float {
        val scale = when (style) {
            TransitionStyle.BLEND -> 8_000f
            TransitionStyle.RISE -> 5_500f
            TransitionStyle.FADE -> 4_500f
            TransitionStyle.CUT -> 2_200f
        }
        return (1f - abs(duration - ideal) / scale).coerceIn(0f, 1f)
    }

    private fun styleFitness(
        style: TransitionStyle,
        rhythm: Float,
        harmonic: Float,
        structure: Float,
        energy: Float,
        vocalRisk: Float,
        energyDelta: Float,
    ): Float = when (style) {
        TransitionStyle.BLEND -> (rhythm + harmonic + structure + (1f - vocalRisk)) / 4f
        TransitionStyle.RISE -> (rhythm + structure + energy + energyDelta.coerceIn(0f, 1f) + (1f - vocalRisk)) / 5f
        TransitionStyle.FADE -> (structure + energy + (1f - vocalRisk)) / 3f
        TransitionStyle.CUT -> (structure + (1f - vocalRisk) + (1f - rhythm)) / 3f
    }.coerceIn(0f, 1f)

    private fun fallbackPlan(outgoing: AutomixTrack, incoming: AutomixTrack): TransitionPlan {
        val duration = minOf(6_000L, outgoing.durationMs.coerceAtLeast(1_200L))
        val start = (outgoing.durationMs - duration).coerceAtLeast(0L)
        return TransitionPlan(
            outgoingMediaId = outgoing.mediaId,
            incomingMediaId = incoming.mediaId,
            style = TransitionStyle.FADE,
            outgoingStartMs = start,
            outgoingEndMs = outgoing.durationMs,
            incomingCueMs = 0L,
            durationMs = outgoing.durationMs - start,
            incomingPlaybackRate = 1f,
            phaseOffsetMs = 0L,
            tempoCompatibility = 0.45f,
            harmonicCompatibility = harmonicCompatibility(outgoing.analysis, incoming.analysis),
            structuralCompatibility = 0.35f,
            vocalOverlapRisk = 0.5f,
            energyCompatibility = 0.5f,
            musicalScore = 0.42f,
            candidateCount = 1,
            confidence = 0.35f,
        )
    }

    private fun alignToNearestGrid(position: Long, a: AutomixAnalysis): Long {
        if (!a.usableTempo) return position
        val grid = if (a.tatumsMs.size >= 2) a.beatIntervalMs / 2f else a.beatIntervalMs
        if (grid <= 0f) return position
        val origin = a.firstBeatMs.toFloat()
        val index = ((position - origin) / grid).roundToLong()
        return (origin + index * grid).roundToLong().coerceAtLeast(0L)
    }

    private fun calculatePhaseOffset(
        outgoingStart: Long,
        incomingCue: Long,
        a: AutomixAnalysis?,
        b: AutomixAnalysis?,
        rate: Float,
    ): Long {
        if (a?.usableTempo != true || b?.usableTempo != true) return 0L
        val grid = if (a.tatumsMs.size >= 2) a.beatIntervalMs / 2f else a.beatIntervalMs
        if (grid <= 0f) return 0L
        val aPhase = modulo(outgoingStart - a.firstBeatMs, grid)
        val incomingGrid = (if (b.tatumsMs.size >= 2) b.beatIntervalMs / 2f else b.beatIntervalMs) / rate
        val bPhase = modulo(incomingCue - b.firstBeatMs, incomingGrid)
        var delta = (aPhase - bPhase).roundToLong()
        val half = (grid / 2f).roundToLong()
        val full = grid.roundToLong().coerceAtLeast(1L)
        while (delta > half) delta -= full
        while (delta < -half) delta += full
        return delta.coerceIn(-180L, 180L)
    }

    private fun nearAny(value: Long, candidates: List<Long>, tolerance: Long): Boolean =
        candidates.any { abs(it - value) <= tolerance }

    private fun nearBoundary(value: Long, phrases: List<AutomixPhrase>, tolerance: Long): Boolean =
        phrases.any { abs(it.startMs - value) <= tolerance || abs(it.endMs - value) <= tolerance }

    private fun nearSectionBoundary(value: Long, sections: List<AutomixSection>, tolerance: Long): Boolean =
        sections.any { abs(it.startMs - value) <= tolerance || abs(it.endMs - value) <= tolerance }

    private fun modulo(value: Long, modulus: Float): Float {
        if (modulus <= 0f) return 0f
        val m = modulus.toLong().coerceAtLeast(1L)
        return ((value % m + m) % m).toFloat()
    }

    private fun Iterable<Float>.averageOr(default: Float): Float {
        var sum = 0f
        var n = 0
        for (v in this) { sum += v; n++ }
        return if (n == 0) default else sum / n
    }

    private data class ScoredCandidate(
        val startMs: Long,
        val cueMs: Long,
        val durationMs: Long,
        val style: TransitionStyle,
        val rate: Float,
        val rhythmScore: Float,
        val structureScore: Float,
        val energyScore: Float,
        val vocalRisk: Float,
        val musicalScore: Float,
        val confidence: Float,
    )

    private const val MAX_RATE_DEVIATION = 0.04f
    private const val MIN_TRANSITION_MS = 1_200L
    private const val MAX_TRANSITION_MS = 18_000L
    private const val MAX_OUTGOING_SEARCH_MS = 65_000L
    private const val MAX_INCOMING_SEARCH_MS = 60_000L
    private const val MAX_SIDE_CANDIDATES = 16
    private const val MAX_GRID_CANDIDATES = 16
    private const val MAX_STRUCTURE_CANDIDATES = 8
}
