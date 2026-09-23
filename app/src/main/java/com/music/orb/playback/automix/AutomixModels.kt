package com.music.orb.playback.automix

import kotlin.math.abs

/** A coarse musical section derived from Orb's own local DSP analysis. */
data class AutomixSection(
    val startMs: Long,
    val endMs: Long,
    val energy: Float,
    val tempoBpm: Float,
    val tempoConfidence: Float,
    val vocalProbability: Float,
    val isDrop: Boolean = false,
)

/** Phrase-sized structural region. Phrases are aligned to bars whenever possible. */
data class AutomixPhrase(
    val startMs: Long,
    val endMs: Long,
    val energy: Float,
    val vocalProbability: Float,
)

/** Immutable musical map produced from Orb's own decoded audio. */
data class AutomixAnalysis(
    val mediaId: String,
    val version: Int = CURRENT_VERSION,
    val analyzedAtMs: Long,
    val analyzedDurationMs: Long,
    /** True only when analysis could read the complete cached rendition. */
    val completeAnalysis: Boolean = false,
    val tempoBpm: Float,
    val tempoConfidence: Float,
    /** Confidence contributed by the optional Beat This ONNX refinement. */
    val beatModelConfidence: Float = 0f,
    val firstBeatMs: Long,
    val beatIntervalMs: Float,
    val beatsMs: List<Long>,
    val barsMs: List<Long>,
    /** Two tatums per beat in v2. This provides a finer phase grid than beats alone. */
    val tatumsMs: List<Long> = emptyList(),
    /** Local tempo sampled through the analyzed audio rather than one global BPM only. */
    val tempoCurveBpm: List<Float> = emptyList(),
    val keyPitchClass: Int?,
    val minorMode: Boolean?,
    val keyConfidence: Float,
    val loudnessCurve: List<Float>,
    val energyCurve: List<Float>,
    /** 0..1 vocal-likelihood curve. v2 uses an on-device spectral heuristic, not cloud metadata. */
    val vocalProbabilityCurve: List<Float> = emptyList(),
    /** Confidence contributed by the optional UMX-HQ vocal magnitude model. */
    val vocalModelConfidence: Float = 0f,
    val sections: List<AutomixSection> = emptyList(),
    val phrases: List<AutomixPhrase> = emptyList(),
    val introCandidatesMs: List<Long>,
    val outroCandidatesMs: List<Long> = emptyList(),
    val dropCandidatesMs: List<Long> = emptyList(),
) {
    val mlAnalysisUsed: Boolean get() = beatModelConfidence > 0f || vocalModelConfidence > 0f

    val usableTempo: Boolean
        get() = tempoBpm in 50f..220f && beatIntervalMs > 0f && tempoConfidence >= 0.20f

    val camelot: String?
        get() {
            val pc = keyPitchClass ?: return null
            val minor = minorMode ?: return null
            // Camelot wheel mapping: pitch class C=0 ... B=11.
            val number = if (minor) {
                intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)[pc]
            } else {
                intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)[pc]
            }
            return "$number${if (minor) 'A' else 'B'}"
        }

    fun vocalProbabilityAt(positionMs: Long): Float {
        if (vocalProbabilityCurve.isEmpty()) return 0.35f
        val index = (positionMs / FEATURE_BUCKET_MS).toInt().coerceIn(0, vocalProbabilityCurve.lastIndex)
        return vocalProbabilityCurve[index]
    }

    fun energyAt(positionMs: Long): Float {
        if (energyCurve.isEmpty()) return 0.5f
        val index = (positionMs / FEATURE_BUCKET_MS).toInt().coerceIn(0, energyCurve.lastIndex)
        return energyCurve[index]
    }

    companion object {
        const val CURRENT_VERSION = 3
        const val FEATURE_BUCKET_MS = 500L
    }
}

data class AutomixTrack(
    val mediaId: String,
    val durationMs: Long,
    val analysis: AutomixAnalysis?,
)

enum class TransitionStyle {
    /** Beat-aligned, long equal-power/EQ blend. */
    BLEND,
    /** Energy-building transition that thins the outgoing low end before handoff. */
    RISE,
    /** Musical overlap without tempo manipulation. */
    FADE,
    /** Short phrase-boundary handoff for incompatible material. */
    CUT,
}

data class TransitionPlan(
    val outgoingMediaId: String,
    val incomingMediaId: String,
    val style: TransitionStyle,
    /** Position in A where B becomes audible. */
    val outgoingStartMs: Long,
    /** End of the active overlap. */
    val outgoingEndMs: Long,
    /** Position inside B that is cued before playback starts. */
    val incomingCueMs: Long,
    val durationMs: Long,
    /** Temporary rate applied to B during the overlap; pitch is preserved by Media3/Sonic. */
    val incomingPlaybackRate: Float,
    /** Offset used to align B's beat/tatum phase to A's grid. */
    val phaseOffsetMs: Long,
    val tempoCompatibility: Float,
    val harmonicCompatibility: Float,
    val structuralCompatibility: Float = 0.5f,
    val vocalOverlapRisk: Float = 0.5f,
    /** Energy continuity/complementarity at the selected A -> B points. */
    val energyCompatibility: Float = 0.5f,
    /** Weighted musical score of the winning candidate pair. */
    val musicalScore: Float = 0.5f,
    /** Number of A x B candidate pairs evaluated for this decision. */
    val candidateCount: Int = 1,
    val confidence: Float,
) {
    val beatMatched: Boolean get() = incomingPlaybackRate != 1f
}

internal fun harmonicCompatibility(a: AutomixAnalysis?, b: AutomixAnalysis?): Float {
    val aKey = a?.keyPitchClass ?: return 0.5f
    val bKey = b?.keyPitchClass ?: return 0.5f
    val aMinor = a.minorMode ?: return 0.5f
    val bMinor = b.minorMode ?: return 0.5f
    val semitones = minOf(abs(aKey - bKey), 12 - abs(aKey - bKey))
    return when {
        aKey == bKey && aMinor == bMinor -> 1f
        aKey == bKey -> 0.90f
        semitones == 5 || semitones == 7 -> if (aMinor == bMinor) 0.90f else 0.78f
        semitones <= 2 -> if (aMinor == bMinor) 0.72f else 0.62f
        else -> 0.35f
    }
}
