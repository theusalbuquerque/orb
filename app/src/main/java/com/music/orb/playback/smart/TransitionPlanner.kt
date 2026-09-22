/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), merging its
 * TransitionPlanner.kt and WsolaPlanner.kt into one file.
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.orb.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Turns stored analysis into a concrete transition plan for one pair of
 * tracks.
 *
 * Nothing here touches PCM; the planner decides *where* a transition happens
 * and *how* ambitious it is. [CrossfadeController] is what executes a plan: it
 * reads the timing fields ([TransitionPlan.transitionStart], [TransitionPlan.fadeSeconds]),
 * cues the incoming track to [TransitionPlan.incomingCueTime] instead of 0,
 * stretches it by [TransitionPlan.incomingPlaybackRate] to align tempo, and
 * renders [TransitionPlan.transitionStyle] locally — a closing low-pass over
 * the outgoing track for [TransitionStyle.DJ_FILTER], a low-end handover at
 * [TransitionPlan.bassSwapFraction] for [TransitionStyle.DJ_BLEND]/EQ_SWAP, a
 * long arrangement runway for [TransitionStyle.INTRO_BED]/INTRO_BRIDGE_FILTER,
 * or a structural micro-handoff for CUT/PHRASE_CUT. In Automix server-authoritative mode the
 * server chooses the family, timing and gain/DSP choreography; the device retains only hard
 * transport/timeline safety rails and never substitutes a different musical style.
 */

/** Which crossfade behaviour the listener asked for. */
enum class CrossfadeMode { STANDARD, SMART }

/**
 * The minimal facts about a queue item the planner needs, independent of
 * Media3's `MediaItem` — kept separate so this file stays pure and testable
 * without constructing one.
 */
data class TransitionTrackInfo(
    val id: String,
    val durationMs: Long,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
)

/**
 * Four bars. Overlaps are counted in beats because that is what the ear
 * hears; the seconds values are rails for tempi where four bars would be
 * absurd, not the primary control. Eight to sixteen beats is the range the
 * automatic-DJ literature reports for stable dance material, and less for
 * dense pop.
 */
private const val AUTO_TRANSITION_MAX_BEATS = 16.0
private const val AUTO_MIN_SECONDS = 4.0
private const val AUTO_FAST_TRACK_MIN_SECONDS = 6.0
private const val AUTO_TRANSITION_MAX_SECONDS = 12.0
private const val AUTO_FALLBACK_SECONDS = 12.0

/** Below this a track would spend too much of itself transitioning to be worth planning. */
private const val MIN_SMART_DURATION_SECONDS = 45.0

/** Leave enough incoming material after a calibrated handoff to avoid landing in its outro. */
private const val MIN_INCOMING_CLEARANCE_SECONDS = 5.0

private val KEY_INDEX = mapOf(
    "C" to 0, "C♯" to 1, "D♭" to 1, "D" to 2, "D♯" to 3, "E♭" to 3,
    "E" to 4, "F" to 5, "F♯" to 6, "G♭" to 6, "G" to 7, "G♯" to 8,
    "A♭" to 8, "A" to 9, "A♯" to 10, "B♭" to 10, "B" to 11,
)

/** Anything matching this is spoken or already a performance; mixing it is never wanted. */
private val BLOCKED_TEXT = Regex(
    """\b(podcast|episode|audiobook|live|concert|performance)\b""",
    RegexOption.IGNORE_CASE,
)

/** How the renderer should execute a planned transition. */
enum class TransitionStyle {
    /** A constant-power fade, unfiltered. The only style the bottom tier permits. */
    EQUAL_POWER,

    /** Album siblings played through: a near-instant handoff, not a mix. */
    GAPLESS,

    /** Beat-aligned blend with a bass swap, for matching or near-matching tempi. */
    DJ_BLEND,

    /** Filtered handoff for tempi too far apart to blend flat. */
    DJ_FILTER,

    /**
     * Long Apple/Spotify-style runway: B starts from its opening well before the
     * handoff, remains subordinate under A, and its structural impact is aimed at
     * A's measured release. B never restarts when foreground ownership changes.
     */
    RUNWAY_BLEND,

    /**
     * B takes foreground on a phrase boundary before A's file necessarily reaches
     * its natural end. A is allowed a short musical tail after its foreground has
     * genuinely released; this is an overlap/takeover, not a hard CUT.
     */
    PHRASE_TAKEOVER,

    /**
     * Spotify-like long intro bed: B's verified instrumental intro is allowed to
     * arrive quietly under A — preferably from 0:00 / first safe audible audio —
     * while A keeps authority. B's major structural arrival/drop is timed toward
     * A's natural ending; it never grants permission to cut A's active vocal. When
     * both beat grids are trustworthy, the two decks may meet at a conservative
     * temporary tempo and B may loop a measured low-vocal phrase to preserve the
     * musical handoff instead of cutting either arrangement short.
     */
    INTRO_BED,

    /**
     * Phrase-boundary micro-handoff. Used when a long overlap would create a
     * vocal/tempo collision; it sounds like a clean cut, but keeps a sub-second
     * de-click envelope so two independent decoders never hard-switch samples.
     */
    CUT,

    /**
     * A phrase-aware cut that deliberately cues B at a strong structural entry
     * (drop / phrase downbeat) and aligns that event with a vocal-safe boundary
     * in A. Unlike CUT, it may skip B's intro on purpose.
     */
    PHRASE_CUT,

    /**
     * Beat-matched blend whose defining gesture is a low-end exchange. The
     * renderer keeps the records relatively stable in level while the bass/kick
     * region changes hands on a measured beat.
     */
    EQ_SWAP,

    /**
     * Long intro bed with a subtle complementary spectral carve. Used when B
     * has a useful long runway but A/B are dense enough that a fully-open bed
     * would mask or clutter the foreground.
     */
    INTRO_BRIDGE_FILTER,

    /**
     * B takes perceptual foreground quickly while A remains audible as a musical tail. This is
     * useful when A has genuinely opened a lane and B has an assertive entrance; it is vetoed
     * whenever A is still vocally/structurally protected.
     */
    FOREGROUND_TAKEOVER,
}


/** Optional beat-quantized loop used only while an Automix transition is in flight. */
enum class TransitionLoopTarget {
    NONE,
    INCOMING,
}

/**
 * One automation point for the long Instrumental Overlay gain ride. Progress is
 * normalized wall-clock transition progress (0..1); gains are linear deck
 * gains. The renderer interpolates these points, so the planner can react to
 * the measured activity of A and B instead of relying on one fixed 10/20/100
 * recipe for every song pair.
 */
data class TransitionGainPoint(
    val progress: Double,
    val incomingGain: Double,
    val outgoingGain: Double,
)

/**
 * The planned transition for one pair of tracks, in outgoing-track timeline
 * seconds.
 *
 * A plan is produced on every tick; [shouldStart] is what says the playhead
 * has actually reached it. [markerVisible] is separate because a future UI
 * may want to draw the upcoming transition before it begins. When [blocked]
 * is true nothing should happen at all and [reason] says why.
 */
data class TransitionPlan(
    val shouldStart: Boolean = false,
    val markerVisible: Boolean = false,
    val blocked: Boolean = false,
    val reason: String = "",
    val transitionStart: Double = 0.0,
    val transitionEnd: Double = 0.0,
    val fadeSeconds: Double = 0.0,
    val transitionStyle: TransitionStyle = TransitionStyle.EQUAL_POWER,
    /** Where in the incoming track playback should be cued to when the transition opens. */
    val incomingCueTime: Double = 0.0,
    /** Where the incoming track's arrangement lands, on its own timeline. */
    val incomingHandoffTime: Double = 0.0,
    /** Temporary tempo correction for A. 1.0 keeps the outgoing deck untouched. */
    val outgoingPlaybackRate: Double = 1.0,
    /** Temporary tempo correction for B. Pitch remains locked by Media3/Sonic. */
    val incomingPlaybackRate: Double = 1.0,
    /** Optional loop in B's low-vocal intro, expressed in B's own timeline. */
    val loopTarget: TransitionLoopTarget = TransitionLoopTarget.NONE,
    val loopStartTime: Double = 0.0,
    val loopEndTime: Double = 0.0,
    val loopRepeats: Int = 0,
    val handoffStartSeconds: Double = 0.0,
    val handoffDuration: Double = 0.0,
    val pickupSeconds: Double = 0.0,
    val transitionBeats: Int = 0,
    val bassSwap: Boolean = false,
    val handoffFraction: Double = HANDOFF_FRACTION,
    val bedPosition: Double = BED_POSITION,
    val bassSwapFraction: Double = 0.7,
    val filterSweep: Double = 0.0,
    /** Activity-aware deck automation; empty means use the renderer's conservative fallback. */
    val gainEnvelope: List<TransitionGainPoint> = emptyList(),
    /**
     * How strongly the two tracks are expected to be singing over each other
     * through this overlap, 0..1; see [vocalOverlapAmount].
     *
     * Separate from [filterSweep] because they answer to different things.
     * [filterSweep] is a property of the *style* — a filter ride is what an
     * unmatched pair gets instead of a beat-matched blend — and a blend
     * deliberately asks for none of it. This is a property of the *material*, and
     * it applies whatever the style: two tempo-matched vocals sitting on the same
     * grid is the case a blend handles worst, precisely because nothing about the
     * arrangement is going to separate them.
     *
     * Zero whenever either track lacks a vocal mask, which leaves every style
     * rendering exactly as it did before this existed.
     */
    val vocalOverlap: Double = 0.0,
    /**
     * The tempi the overlap is built on, which are **not** the analyses' raw
     * BPMs: the incoming one has been folded into the outgoing one's octave.
     * Zero when the plan is not beat-matched.
     */
    val outgoingBpm: Double = 0.0,
    val incomingBpm: Double = 0.0,
    /** Why the policy landed where it did, when it declined to be more ambitious. */
    val policyReasons: List<String> = emptyList(),
) {
    /** Convenience for the engine, which schedules in milliseconds. */
    val fadeMs: Long get() = (fadeSeconds * 1000).roundToLong()
}

private fun blocked(reason: String, transitionStart: Double = 0.0, transitionEnd: Double = 0.0) =
    TransitionPlan(
        blocked = true,
        reason = reason,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
    )

private fun trackDurationSeconds(track: TransitionTrackInfo?): Double =
    if (track == null || track.durationMs <= 0) 0.0 else track.durationMs / 1000.0

private fun itemText(track: TransitionTrackInfo?): String =
    if (track == null) "" else listOf(track.title, track.artist, track.album)
        .filter { it.isNotBlank() }
        .joinToString(" ")

/**
 * Gapless is for an album being played through, not for any two songs that
 * happen to share an album. A playlist, a manual queue or a shuffle that
 * lands two album siblings back to back is a mix, and gets mixed; the caller
 * decides which of those it is via `albumSequential` and says so explicitly.
 */
private fun sameAlbum(left: TransitionTrackInfo?, right: TransitionTrackInfo?): Boolean {
    if (left == null || right == null) return false
    if (left.albumId.isNotBlank() && left.albumId == right.albumId) return true
    return left.album.isNotBlank() && left.album == right.album && left.artist == right.artist
}

/** Folds [nextBpm] into the same octave as [currentBpm] and returns the ratio between them. */
private fun normalizedTempoRatio(currentBpm: Double, nextBpm: Double): Double {
    if (currentBpm <= 0 || nextBpm <= 0) return 1.0
    var ratio = nextBpm / currentBpm
    while (ratio > 1.5) ratio /= 2
    while (ratio < 0.67) ratio *= 2
    return ratio
}

private fun splitKey(key: String): Pair<Int?, String?> {
    val parts = key.trim().split(' ')
    return KEY_INDEX[parts.firstOrNull()] to parts.getOrNull(1)
}

private fun keyDistance(left: String, right: String): Int? {
    val (leftIndex, leftMode) = splitKey(left)
    val (rightIndex, rightMode) = splitKey(right)
    if (leftIndex == null || rightIndex == null) return null
    val pitchDistance = min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12)
    return pitchDistance + if (leftMode != null && rightMode != null && leftMode != rightMode) 1 else 0
}

private fun harmonicallyCompatible(left: String, right: String): Boolean {
    val (leftIndex, leftMode) = splitKey(left)
    val (rightIndex, rightMode) = splitKey(right)
    if (leftIndex == null || rightIndex == null) return false
    val distance = min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12)
    if (leftMode != null && rightMode != null && leftMode != rightMode) return distance <= 1
    // A fifth is as close as a second here: it is the move every DJ makes.
    return distance <= 2 || distance == 5
}

/** A key the analyzer was not confident about is no key at all. */
private fun trustedKey(analysis: TrackAnalysis): String =
    if (analysis.key.isBlank() || analysis.keyConfidence < 0.25) "" else analysis.key

/**
 * Pair score used only to order AutoPlay's future tail. It does not choose a transition style.
 * The actual A -> B recipe is still planned later by Automix 2.0/2.5.
 */
internal fun automixQueueCompatibility(outgoing: TrackAnalysis, incoming: TrackAnalysis): Double {
    if (!outgoing.isUsable || !incoming.isUsable) return Double.NEGATIVE_INFINITY

    val ratio = normalizedTempoRatio(outgoing.bpm, incoming.bpm)
    val tempo = (1.0 - (abs(1.0 - ratio) / 0.12)).coerceIn(0.0, 1.0)
    val beat = min(outgoing.beatConfidence, incoming.beatConfidence).coerceIn(0.0, 1.0)

    val leftKey = trustedKey(outgoing)
    val rightKey = trustedKey(incoming)
    val harmonic = when {
        leftKey.isBlank() || rightKey.isBlank() -> 0.45
        leftKey == rightKey -> 1.0
        harmonicallyCompatible(leftKey, rightKey) -> 0.86
        else -> 0.18
    }

    val aEnd = outgoing.contentEndTime.takeIf { it > 0.0 } ?: outgoing.duration
    val aStart = max(0.0, aEnd - 10.0)
    val bStart = incoming.audibleStartTime ?: 0.0
    val bEnd = min(
        incoming.contentEndTime.takeIf { it > bStart } ?: incoming.duration,
        bStart + 10.0,
    )
    val aEnergy = meanCurveEnergy(outgoing.energyCurve, aStart, aEnd)
    val bEnergy = meanCurveEnergy(incoming.energyCurve, bStart, bEnd)
    val energy = if (aEnergy != null && bEnergy != null) {
        (1.0 - abs(aEnergy - bEnergy)).coerceIn(0.0, 1.0)
    } else {
        0.50
    }

    val structure = (
        (if (outgoing.downbeats.isNotEmpty()) 0.25 else 0.0) +
            (if (incoming.downbeats.isNotEmpty()) 0.25 else 0.0) +
            (if (outgoing.phraseBoundaries.isNotEmpty()) 0.25 else 0.0) +
            (if (incoming.phraseBoundaries.isNotEmpty()) 0.25 else 0.0)
        ).coerceIn(0.0, 1.0)

    return (0.38 * tempo + 0.26 * harmonic + 0.20 * beat + 0.10 * energy + 0.06 * structure)
        .coerceIn(0.0, 1.0)
}

private fun nearestTimedValue(
    values: List<Double>,
    target: Double,
    tolerance: Double = Double.POSITIVE_INFINITY,
    minimum: Double = 0.0,
): Double? = values
    .filter { it.isFinite() && it >= minimum && abs(it - target) <= tolerance }
    .minByOrNull { abs(it - target) }

private fun timedValueNearOrBefore(
    values: List<Double>,
    target: Double,
    tolerance: Double = Double.POSITIVE_INFINITY,
    minimum: Double = 0.0,
): Double? = values
    .filter { it.isFinite() && it >= minimum && it <= target && target - it <= tolerance }
    .maxOrNull()

/**
 * Snaps a transition start onto the outgoing track's grid: a phrase boundary
 * if one is near, a downbeat otherwise, and the raw target when neither is.
 */
private fun alignedTransitionStart(
    analysis: TrackAnalysis,
    target: Double,
    end: Double,
    preferEarlier: Boolean,
    minimum: Double,
): Double {
    val interval = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val phraseTolerance = max(1.0, interval * 4)
    val downbeatTolerance = max(0.75, interval * 2)
    val phrase = if (preferEarlier) {
        timedValueNearOrBefore(analysis.phraseBoundaries, target, phraseTolerance, minimum)
    } else {
        nearestTimedValue(analysis.phraseBoundaries, target, phraseTolerance, minimum)
    }
    val downbeat = if (preferEarlier) {
        timedValueNearOrBefore(analysis.downbeats, target, downbeatTolerance, minimum)
    } else {
        nearestTimedValue(analysis.downbeats, target, downbeatTolerance, minimum)
    }
    return clamp(phrase ?: downbeat ?: target, minimum, end)
}

/**
 * Where the incoming track's arrangement arrives: the point the outgoing
 * track should be gone by.
 */
internal fun incomingCuePoint(analysis: TrackAnalysis): Double {
    rankMixInCandidates(analysis).firstOrNull()?.let { return it.time }

    val interval = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val downbeats = analysis.downbeats

    val analyzedMixIn = analysis.mixInTime
    if (analyzedMixIn.isFinite() && analyzedMixIn > 0) {
        return nearestTimedValue(downbeats, analyzedMixIn, max(0.5, interval * 2)) ?: analyzedMixIn
    }

    val pickup = max(
        0.0,
        analysis.introEndTime.orZero().takeIf { it != 0.0 }
            ?: (analysis.audibleStartTime ?: analysis.pickupTime).orZero().takeIf { it != 0.0 }
            ?: analysis.firstBeat.orZero(),
    )
    val duration = analysis.duration.orZero().takeIf { it != 0.0 } ?: 300.0
    if (pickup > 0 && pickup < duration - 10) {
        downbeats.firstOrNull { it >= pickup }?.let { return it }
    }
    val phrases = analysis.phraseBoundaries
    if (phrases.size > 1 && phrases[1] > 4) return phrases[1]
    if (downbeats.size >= 8) return downbeats[min(8, downbeats.size - 1)].orZero()
    return pickup
}

/** Where the incoming track first makes sound, so the fade is not cued into its lead-in silence. */
private fun incomingStartPoint(analysis: TrackAnalysis): Double =
    listOfNotNull(analysis.audibleStartTime, analysis.pickupTime, analysis.firstBeat)
        .firstOrNull { it.isFinite() && it >= 0 } ?: 0.0

// ---------------------------------------------------------------------------
// WSOLA-style beat-matched phrase-switch plan (ported from WsolaPlanner.kt)
// ---------------------------------------------------------------------------

// The fade is bounded in beats because overlap length is musical: bounding it
// in seconds makes a faster track get a longer mix, which is backwards. Four
// bars is the ceiling and one bar the floor, the latter for tracks whose
// intro cannot cover more.
private const val MIN_FADE_BEATS = 4
private const val MAX_FADE_BEATS = 16

// A ceiling on the whole overlap regardless of how long the incoming intro is.
private const val MAX_OVERLAP_SECONDS = 16.0

/**
 * Moving both decks by the same musical amount preserves the beat grid and
 * overlap length while putting the incoming arrangement inside the blend
 * instead of making it the finish line. Applied only to a content-end exit on
 * the outgoing side; a real structural/energy exit has already supplied the
 * earlier anchor.
 */
internal const val ARRANGEMENT_OVERLAP_BEATS = 8

/**
 * One continuous equal-power fade across the whole overlap. 0.5/0.5 is the
 * plain symmetric crossfade, which is exactly the sin/cos pair
 * [com.music.orb.playback.CrossfadeController] rides — so at these values
 * the renderer already honours them, and anything else would need a two-segment
 * gain curve it does not have.
 */
const val HANDOFF_FRACTION = 0.5
const val BED_POSITION = 0.5

/** The prior for where the low end hands over, on a pairing with no useful structural change. */
private const val DEFAULT_BASS_SWAP_FRACTION = 0.7

/** Analysis may move the swap later than the prior, but never so late the outgoing low end survives almost to silence. */
private const val MAX_BASS_SWAP_FRACTION = 0.85

/** A normalized low-band step smaller than this is too weak to move the swap away from its prior. */
private const val MIN_BASS_STRUCTURE_SCORE = 0.25

/** Capped in absolute seconds too, so a long overlap does not scale the hold up with it. */
private const val BASS_SWAP_MAX_SECONDS = 6.0

/**
 * How far the outgoing track's low-pass sweep travels by the end of the
 * overlap, as a fraction of a full ride. 1.0 is the whole way down to
 * [com.music.orb.playback.CrossfadeController.FILTER_FLOOR_HZ].
 */
const val FILTER_SWEEP = 1.0

/** The outgoing track must have this much audio before the overlap and the incoming this much after it. */
private const val MIN_CLEARANCE_SECONDS = 5.0

private fun averageLowEnergy(curve: List<EnergySample>, from: Double, until: Double): Double? {
    if (until <= from) return null
    var index = curve.binarySearchBy(from) { it.time }.let { if (it >= 0) it else -it - 1 }
    var sum = 0.0
    var count = 0
    while (index < curve.size && curve[index].time < until) {
        val point = curve[index++]
        if (point.time.isFinite() && point.energy.isFinite() && point.energy >= 0) {
            sum += point.energy
            count++
        }
    }
    return if (count > 0) sum / count else null
}

private fun lowEnergyReference(curve: List<EnergySample>): Double? {
    val energies = curve.map { it.energy }.filter { it.isFinite() && it >= 0 }.sorted()
    if (energies.isEmpty()) return null
    val upperDecile = energies[(energies.lastIndex * 0.9).toInt()]
    val reference = max(upperDecile, (energies.lastOrNull() ?: 0.0) * 0.25)
    return reference.takeIf { it > 1e-9 }
}

private fun lowEnergyResolution(curve: List<EnergySample>): Double {
    val gaps = curve.zipWithNext { left, right -> right.time - left.time }
        .filter { it.isFinite() && it > 0 }
        .sorted()
    return gaps.getOrNull(gaps.size / 2) ?: 0.0
}

/** Change in low-band energy across one beat either side of [at], normalized per track. */
private fun lowEnergyChange(
    curve: List<EnergySample>,
    reference: Double?,
    at: Double,
    windowSeconds: Double,
): Double? {
    if (curve.isEmpty() || reference == null || windowSeconds <= 0) return null
    val before = averageLowEnergy(curve, at - windowSeconds, at) ?: return null
    val after = averageLowEnergy(curve, at, at + windowSeconds) ?: return null
    return (after / reference).coerceIn(0.0, 1.5) -
        (before / reference).coerceIn(0.0, 1.5)
}

/** Chooses one shared-grid beat for the low-end handoff. */
private fun bassSwapFractionFor(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionStart: Double,
    incomingCueTime: Double,
    outgoingBeatSeconds: Double,
    incomingBeatSeconds: Double,
    overlapSeconds: Double,
    overlapBeats: Int,
): Double {
    if (overlapSeconds <= 0) return DEFAULT_BASS_SWAP_FRACTION

    val latestFraction = min(MAX_BASS_SWAP_FRACTION, BASS_SWAP_MAX_SECONDS / overlapSeconds)
        .coerceIn(0.0, 1.0)
    val prior = min(DEFAULT_BASS_SWAP_FRACTION, latestFraction)
    if (overlapBeats < 2) return prior

    val earliestFraction = min(HANDOFF_FRACTION, latestFraction)
    val earliestBeat = ceil(earliestFraction * overlapBeats - 1e-9).toInt()
        .coerceIn(1, overlapBeats - 1)
    val latestBeat = floor(latestFraction * overlapBeats + 1e-9).toInt()
        .coerceIn(earliestBeat, overlapBeats - 1)
    val candidates = (earliestBeat..latestBeat).toList()
    val fallbackBeat = candidates.minWithOrNull(
        compareBy<Int> { abs(it.toDouble() / overlapBeats - prior) }
            .thenBy { if (it % 4 == 0) 0 else 1 },
    ) ?: return prior

    data class BassCandidate(val beat: Int, val score: Double)

    val outgoingReference = lowEnergyReference(analysis.lowEnergyCurve)
    val incomingReference = lowEnergyReference(nextAnalysis.lowEnergyCurve)
    val outgoingWindow = max(
        outgoingBeatSeconds,
        lowEnergyResolution(analysis.lowEnergyCurve) * 1.1,
    )
    val incomingWindow = max(
        incomingBeatSeconds,
        lowEnergyResolution(nextAnalysis.lowEnergyCurve) * 1.1,
    )
    val strongest = candidates.mapNotNull { beat ->
        val outgoingAt = transitionStart + beat * outgoingBeatSeconds
        val incomingAt = incomingCueTime + beat * incomingBeatSeconds
        val incomingChange = lowEnergyChange(
            nextAnalysis.lowEnergyCurve,
            incomingReference,
            incomingAt,
            incomingWindow,
        )
        val outgoingChange = lowEnergyChange(
            analysis.lowEnergyCurve,
            outgoingReference,
            outgoingAt,
            outgoingWindow,
        )
        if (incomingChange == null && outgoingChange == null) return@mapNotNull null
        BassCandidate(beat, (incomingChange ?: 0.0) - (outgoingChange ?: 0.0))
    }.maxWithOrNull(
        compareBy<BassCandidate> { it.score }
            .thenBy { if (it.beat % 4 == 0) 1 else 0 }
            .thenBy { -abs(it.beat.toDouble() / overlapBeats - prior) },
    )

    val chosenBeat = strongest?.takeIf { it.score >= MIN_BASS_STRUCTURE_SCORE }?.beat
        ?: fallbackBeat
    return chosenBeat.toDouble() / overlapBeats
}

/**
 * How vocal the planned overlap is on both sides at once, measured over the
 * windows the plan actually blends.
 *
 * The two windows are not the same length in wall-clock terms whenever the
 * incoming track is being stretched: [incomingPlaybackRate] above 1 means it
 * covers proportionally more of its own timeline in the same number of seconds,
 * so the incoming window is scaled by it rather than copied from the outgoing
 * one. Getting that wrong would measure a window the listener never hears.
 *
 * Answers zero for a degenerate span and for any track without a mask, so every
 * caller can set this unconditionally.
 */
private fun plannedVocalOverlap(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionStart: Double,
    transitionEnd: Double,
    incomingCueTime: Double,
    incomingPlaybackRate: Double,
): Double {
    val outgoingSpan = transitionEnd - transitionStart
    if (outgoingSpan <= 0.0 || !outgoingSpan.isFinite()) return 0.0
    val rate = incomingPlaybackRate.takeIf { it.isFinite() && it > 0 } ?: 1.0
    return simultaneousVocalFraction(
        outgoing = analysis,
        incoming = nextAnalysis,
        outStart = transitionStart,
        outEnd = transitionEnd,
        inStart = incomingCueTime,
        rate = rate,
    ) ?: 0.0
}

private fun nearestAtOrBefore(values: List<Double>, target: Double): Double? =
    values.filter { it.isFinite() && it >= 0 && it <= target }.maxOrNull()

/**
 * The outcome of planning one beat-matched transition.
 *
 * [Refused] is a routing decision, not an error: the caller falls back to the
 * adaptive overlap below, which degrades further on its own.
 */
sealed interface WsolaPlanResult {
    data class Refused(val reason: String) : WsolaPlanResult

    /** All times are seconds on each track's own media timeline. */
    data class Planned(
        val tier: TransitionTier,
        val beatConfidence: Double,
        val mixOutType: String,
        val vocalClash: Boolean,
        val transitionStart: Double,
        val transitionEnd: Double,
        val overlapSeconds: Double,
        val beats: Int,
        val fadeBeats: Int,
        val handoffFraction: Double,
        val bedPosition: Double,
        val bassSwapFraction: Double,
        val filterSweep: Double,
        val outgoingBpm: Double,
        val incomingBpm: Double,
        val stretchRatio: Double,
        val incomingCueTime: Double,
        val incomingDropTime: Double,
        val incomingHandoffTime: Double,
        val incomingResumeTime: Double,
    ) : WsolaPlanResult
}

/** Where the incoming track takes over: the best-ranked mix-in candidate, snapped to a downbeat. */
fun incomingMixInPoint(analysis: TrackAnalysis): Double? {
    val beatSeconds = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val tolerance = max(0.5, beatSeconds * 2)
    val target = listOfNotNull(rankMixInCandidates(analysis).firstOrNull()?.time, analysis.mixInTime)
        .firstOrNull { it.isFinite() && it > 0 }
        ?: return null
    return nearestValue(analysis.downbeats, target, tolerance) ?: target
}

/** Where the incoming track first makes sound. */
fun incomingAudibleStart(analysis: TrackAnalysis): Double = audibleStartOf(analysis)

/** Plans one beat-matched transition between [analysis] and [nextAnalysis]. */
fun planWsolaTransition(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    duration: Double = 0.0,
    nextDuration: Double = 0.0,
): WsolaPlanResult {
    val policy = assessTransitionTier(analysis, nextAnalysis)
    if (policy.tier != TransitionTier.BEATMATCHED) {
        return WsolaPlanResult.Refused(policy.reasons.firstOrNull() ?: "policy")
    }

    val outgoingBpm = analysis.bpm.orZero()
    val incomingBpm = alignTempoOctave(outgoingBpm, nextAnalysis.bpm.orZero())
    val stretchRatio = outgoingBpm / incomingBpm

    val outgoingLength = max(duration.orZero(), analysis.duration.orZero())
    val incomingLength = max(nextDuration.orZero(), nextAnalysis.duration.orZero())
    if (outgoingLength <= 0 || incomingLength <= 0) return WsolaPlanResult.Refused("missing-duration")

    val incomingBeatSeconds = 60 / incomingBpm
    val outgoingBeatSeconds = 60 / outgoingBpm

    val incomingDropTime = incomingMixInPoint(nextAnalysis)
    if (incomingDropTime == null || !incomingDropTime.isFinite() || incomingDropTime < 0) {
        return WsolaPlanResult.Refused("incoming-mix-in")
    }

    val contentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: outgoingLength
    val mixOutAnchor = resolveMixOutAnchor(analysis, contentEnd = contentEnd, duration = outgoingLength)
    val unshiftedOverlapEnd = min(outgoingLength, mixOutAnchor.time)
    val outgoingArrangementOverlap =
        if (mixOutAnchor.type == "content_end") {
            min(ARRANGEMENT_OVERLAP_BEATS * outgoingBeatSeconds, MAX_DISCARDED_MUSIC_SECONDS)
        } else {
            0.0
        }
    val overlapEndTarget = max(MIN_CLEARANCE_SECONDS, unshiftedOverlapEnd - outgoingArrangementOverlap)

    val audibleStart = incomingAudibleStart(nextAnalysis)
    val availableFadeBeats = max(0.0, incomingDropTime - audibleStart) / incomingBeatSeconds
    val cappedByOverlap = floor(floor(MAX_OVERLAP_SECONDS / incomingBeatSeconds) / 4).toInt() * 4
    if (cappedByOverlap < MIN_FADE_BEATS) return WsolaPlanResult.Refused("overlap-too-long")
    var fadeBeats = minOf(
        MAX_FADE_BEATS,
        cappedByOverlap,
        floor(availableFadeBeats / 4).toInt() * 4,
    )
    if (fadeBeats < MIN_FADE_BEATS) fadeBeats = MIN_FADE_BEATS

    fun clashOver(beats: Int): Boolean {
        val outStart = overlapEndTarget - beats * outgoingBeatSeconds
        val inStart = max(audibleStart, incomingDropTime - beats * incomingBeatSeconds)
        val outVocal = vocalActivityBetween(analysis, outStart, overlapEndTarget)
        val inVocal = vocalActivityBetween(nextAnalysis, inStart, incomingDropTime)

        // Instant-by-instant first, because it is the question actually being
        // asked. The mean-based test below only fires when *both* windows average
        // vocal across their whole length, which a real clash routinely does not:
        // an incoming track that starts singing a few seconds into the overlap
        // averages clear and still puts its opening line under the outgoing
        // vocal. This catches that, and it is what shrinks the overlap until the
        // two voices stop landing together.
        val simultaneous = simultaneousVocalFraction(
            outgoing = analysis,
            incoming = nextAnalysis,
            outStart = outStart,
            outEnd = overlapEndTarget,
            inStart = inStart,
            rate = if (outgoingBeatSeconds > 0) incomingBeatSeconds / outgoingBeatSeconds else 1.0,
        )
        if (simultaneous != null && simultaneous > VOCAL_CLASH_TOLERANCE) return true

        if (isVocalClash(outVocal, inVocal)) return true

        if (beats > 8 && outVocal != null && outVocal >= VOCAL_ACTIVE_THRESHOLD) {
            val deepVocal = vocalActivityBetween(analysis, outStart, overlapEndTarget - 8 * outgoingBeatSeconds)
            if (deepVocal != null && deepVocal >= VOCAL_ACTIVE_THRESHOLD) {
                return true
            }
        }
        return false
    }
    var fadeVocalClash = clashOver(fadeBeats)
    while (fadeVocalClash && fadeBeats > MIN_FADE_BEATS) {
        fadeBeats -= 4
        fadeVocalClash = clashOver(fadeBeats)
    }

    val coverableBeats = floor(max(0.0, incomingDropTime - audibleStart) / incomingBeatSeconds).toInt()
    val overlapBeats = min(fadeBeats, coverableBeats)
    if (overlapBeats < 1) return WsolaPlanResult.Refused("incoming-no-intro")

    val outgoingOverlapSeconds = overlapBeats * outgoingBeatSeconds
    val overlapSeconds = overlapBeats * incomingBeatSeconds

    val requestedIncomingHandoff =
        incomingDropTime + ARRANGEMENT_OVERLAP_BEATS * incomingBeatSeconds
    val maxIncomingHandoff = incomingLength - MIN_CLEARANCE_SECONDS
    if (maxIncomingHandoff < incomingDropTime) return WsolaPlanResult.Refused("incoming-too-short")
    val incomingHandoffTime = min(requestedIncomingHandoff, maxIncomingHandoff)
    val incomingCueTime = incomingHandoffTime - overlapSeconds
    if (incomingCueTime < audibleStart - 0.05) return WsolaPlanResult.Refused("incoming-no-runway")

    val startTarget = overlapEndTarget - outgoingOverlapSeconds
    val transitionStart = nearestAtOrBefore(analysis.downbeats, startTarget) ?: startTarget
    if (transitionStart < MIN_CLEARANCE_SECONDS) return WsolaPlanResult.Refused("outgoing-too-short")
    val transitionEnd = transitionStart + outgoingOverlapSeconds
    if (transitionEnd > outgoingLength + 0.05) return WsolaPlanResult.Refused("outgoing-overlap-overruns")

    val incomingResumeTime = incomingCueTime + overlapSeconds
    if (incomingResumeTime + MIN_CLEARANCE_SECONDS > incomingLength) {
        return WsolaPlanResult.Refused("incoming-too-short")
    }

    return WsolaPlanResult.Planned(
        tier = policy.tier,
        beatConfidence = policy.beatConfidence,
        mixOutType = mixOutAnchor.type,
        vocalClash = fadeVocalClash,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
        overlapSeconds = overlapSeconds,
        beats = overlapBeats,
        fadeBeats = overlapBeats,
        handoffFraction = HANDOFF_FRACTION,
        bedPosition = BED_POSITION,
        bassSwapFraction = bassSwapFractionFor(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            transitionStart = transitionStart,
            incomingCueTime = incomingCueTime,
            outgoingBeatSeconds = outgoingBeatSeconds,
            incomingBeatSeconds = incomingBeatSeconds,
            overlapSeconds = overlapSeconds,
            overlapBeats = overlapBeats,
        ),
        filterSweep = FILTER_SWEEP,
        outgoingBpm = outgoingBpm,
        incomingBpm = incomingBpm,
        stretchRatio = stretchRatio,
        incomingCueTime = incomingCueTime,
        incomingDropTime = incomingDropTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingResumeTime = incomingResumeTime,
    )
}

/**
 * The most ambitious move available: run the incoming track's instrumental
 * intro underneath the outgoing one and close on its drop. A refusal is a
 * routing decision, not an error: the caller falls back to the adaptive
 * overlap below, which degrades further on its own.
 */
private fun phraseSwitch(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
): TransitionPlan? {
    if (!harmonicallyCompatible(trustedKey(analysis), trustedKey(nextAnalysis))) return null

    val planned = planWsolaTransition(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        duration = length,
        nextDuration = nextLength,
    ) as? WsolaPlanResult.Planned ?: return null

    val overlap = planned.transitionEnd - planned.transitionStart
    return TransitionPlan(
        markerVisible = true,
        transitionStart = planned.transitionStart,
        transitionEnd = planned.transitionEnd,
        fadeSeconds = overlap,
        handoffStartSeconds = 0.0,
        handoffDuration = overlap,
        incomingCueTime = planned.incomingCueTime,
        incomingHandoffTime = planned.incomingHandoffTime,
        incomingPlaybackRate = (planned.stretchRatio * 10000).roundToInt() / 10000.0,
        pickupSeconds = incomingAudibleStart(nextAnalysis),
        transitionBeats = planned.beats,
        bassSwap = true,
        handoffFraction = planned.handoffFraction,
        bedPosition = planned.bedPosition,
        bassSwapFraction = planned.bassSwapFraction,
        // Deliberately not `planned.filterSweep`. A phrase switch is the one
        // case where both decks are genuinely on the same grid, and the move
        // there is to hand the low end over on a beat, not to hide the outgoing
        // track behind a filter — filtering a blend this well aligned would
        // throw away the reason it was worth aligning. The renderer reads a
        // nonzero sweep as "ride the filter instead", so this says zero.
        filterSweep = 0.0,
        // The separation this style *does* need, and the one it cannot get from
        // alignment. Two tracks on a shared grid are the worst case for
        // overlapping voices precisely because nothing about the arrangement
        // pulls them apart — they sit in the same bar, in the same range, for the
        // whole blend. The renderer uses this to deepen the entry high-pass and
        // the exit low-pass without turning the blend into a filter ride.
        vocalOverlap = plannedVocalOverlap(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            transitionStart = planned.transitionStart,
            transitionEnd = planned.transitionEnd,
            incomingCueTime = planned.incomingCueTime,
            incomingPlaybackRate = planned.stretchRatio,
        ),
        outgoingBpm = planned.outgoingBpm,
        incomingBpm = planned.incomingBpm,
        transitionStyle = TransitionStyle.DJ_BLEND,
    )
}

/**
 * Hard safety ceiling for the long Spotify-like intro treatment.
 *
 * This is deliberately much longer than a conventional crossfade.  A quiet
 * instrumental intro is not the handoff itself: it can sit underneath A for
 * close to a minute while A's lead vocal is still the foreground.  The old
 * 40-second cap forced long intros (HEATED -> DANCE is the concrete example)
 * to jump into B around 0:45-0:55 even when a clean bed existed near the start.
 */
private const val OVERLAY_MIN_BEAT_CONFIDENCE = 0.45
private const val OVERLAY_STRONG_BEAT_CONFIDENCE = 0.65
private const val OVERLAY_MAX_DECK_RATE_DEVIATION_HIGH = 0.04
private const val OVERLAY_MAX_DECK_RATE_DEVIATION_MEDIUM = 0.0325
private const val OVERLAY_MAX_DECK_RATE_DEVIATION_LOW = 0.025
private const val OVERLAY_TARGET_BEATS = 32.0
private const val OVERLAY_TARGET_MIN_SECONDS = 12.0
private const val OVERLAY_TARGET_MAX_SECONDS = 32.0
private const val OVERLAY_MAX_LOOP_REPEATS = 3
private const val OVERLAY_LOOP_MAX_VOCAL = 0.18
private const val OVERLAY_LOOP_MIN_SECONDS = 1.8
private const val OVERLAY_LOOP_MAX_SECONDS = 12.0
private const val OVERLAY_LOOP_END_GUARD_SECONDS = 1.0
private const val OVERLAY_LOOP_MIN_ENERGY_STABILITY = 0.62
private val OVERLAY_LOOP_BAR_CHOICES = intArrayOf(4, 2, 1)
private const val OVERLAY_VOCAL_SAMPLES = 17
private const val OVERLAY_GAIN_SAMPLES = 49
// INTRO_BED is a real three-stage entrance, not a cut with a quiet pre-roll.
// B is allowed to build audibly while A remains foreground, then only the
// final ownership exchange is compressed near A's natural release.
private const val OVERLAY_BUILD_TARGET_MIN = 0.26
private const val OVERLAY_BUILD_TARGET_MAX = 0.54
private const val OVERLAY_IMPACT_PREP_MIN = 0.46
private const val OVERLAY_IMPACT_PREP_MAX = 0.70
private const val OVERLAY_INCOMING_FINAL_RISE_SECONDS = 5.0
private const val OVERLAY_OUTGOING_FINAL_RELEASE_SECONDS = 2.2
private const val ACTIVITY_PROTECTED_THRESHOLD = 0.68
private const val ACTIVITY_REBOUND_THRESHOLD = 0.66
private const val ACTIVITY_RELEASE_THRESHOLD = 0.54
private const val ACTIVITY_SAFE_SEARCH_BARS = 4.0
private const val ACTIVITY_SAFE_SEARCH_MIN_SECONDS = 10.0

private const val INTRO_BED_MAX_SECONDS = 90.0
private const val INTRO_BED_MIN_SECONDS = 10.0
private const val INTRO_BED_MAX_VOCAL = 0.42
private const val INTRO_BED_ENTRY_PROBE_SECONDS = 4.0
private const val INTRO_BED_ENTRY_ENERGY_WINDOW_SECONDS = 2.0
private const val INTRO_BED_MIN_NORMALIZED_ENERGY = 0.035
private const val INTRO_BED_EARLIEST_SAFE_VOCAL = 0.24
private const val INTRO_BED_EARLIEST_SAFE_PROBE_SECONDS = 6.0
private const val INTRO_BED_EARLIEST_SAFE_SILENCE_GRACE_SECONDS = 1.25
private const val INTRO_BED_HEAD_CUE_MAX_SECONDS = 8.0
private const val INTRO_BED_HEAD_MAX_VOCAL = 0.30
private const val INTRO_BED_HEAD_MAX_ACTIVITY = 0.82
private const val INTRO_BED_OUTGOING_VOCAL_THRESHOLD = 0.40
private const val INTRO_BED_OUTGOING_VOCAL_TAIL_THRESHOLD = 0.20
private const val INTRO_BED_OUTGOING_VOCAL_RETURN_THRESHOLD = 0.28
private const val INTRO_BED_OUTGOING_VOCAL_CONFIRM_SAMPLES = 2
private const val INTRO_BED_MIN_HANDOFF_SECONDS = 1.50
private const val INTRO_BED_EARLIEST_HANDOFF_FRACTION = 0.55
private const val INTRO_BED_LATEST_HANDOFF_FRACTION = 0.995

// INTRO_BED has two independent landmarks on B: the earliest safe bed cue and
// the later structural arrival (usually the first real drop).  Treating the
// first detected vocal as both landmarks is what made DANCE start around 0:38
// even though its useful instrumental head begins near 0:06 and its important
// drop is much later.
private const val INTRO_BED_DROP_MIN_AFTER_CUE_SECONDS = 12.0
private const val INTRO_BED_DROP_PRE_WINDOW_SECONDS = 6.0
private const val INTRO_BED_DROP_POST_WINDOW_SECONDS = 4.0
private const val INTRO_BED_DROP_MIN_POST_ENERGY = 0.30
private const val INTRO_BED_DROP_MIN_ENERGY_LIFT = 0.10
private const val INTRO_BED_DROP_MIN_LOW_LIFT = 0.12
private const val INTRO_BED_DROP_MIN_SCORE = 0.19
private const val INTRO_BED_DROP_STRUCTURAL_TOLERANCE_SECONDS = 1.75
/** A cut is still a tiny overlap so decoder boundaries cannot click. */
private const val CUT_MIN_SECONDS = 0.35
private const val CUT_MAX_SECONDS = 0.90

/** Phrase-cut keeps a little more runway so the selected downbeat can breathe. */
private const val PHRASE_CUT_MIN_SECONDS = 0.45
private const val PHRASE_CUT_MAX_SECONDS = 1.15
private const val PHRASE_CUT_MIN_ENTRY_SCORE = 0.42

/**
 * First sustained vocal onset on the analysis grid. Three consecutive samples
 * are required so one consonant/model spike cannot terminate an instrumental
 * intro. Returns null when the analyzer supplied no usable vocal mask.
 */
private fun firstSustainedVocalTime(analysis: TrackAnalysis): Double? {
    val mask = analysis.vocalActivityMask
    val curve = analysis.energyCurve
    if (mask.isEmpty() || mask.size != curve.size || curve.size < 3) return null
    var streak = 0
    for (index in mask.indices) {
        val time = curve[index].time
        val vocal = mask[index]
        if (time.isFinite() && vocal.isFinite() && vocal >= VOCAL_ACTIVE_THRESHOLD) {
            streak += 1
            if (streak >= 3) return curve[index - 2].time.takeIf { it.isFinite() && it >= 0.0 }
        } else {
            streak = 0
        }
    }
    return null
}

/**
 * Earliest musically useful point of B's measured instrumental runway.
 *
 * The previous INTRO_BED backed up from B's first vocal by the desired overlap,
 * which routinely discarded the first half of a long intro.  For the Spotify
 * reference that is exactly the wrong priority: the instrumental head is useful
 * material and may sit under A's lead vocal for a long time.  Prefer the first
 * downbeat/phrase boundary that is already audible and still measurably low-vocal;
 * fall back to the analyzer's audible start when no structural marker is present.
 */
private fun instrumentalBedCueTime(analysis: TrackAnalysis, vocalEntry: Double): Double? {
    val audibleStart = audibleStartOf(analysis)
    if (!vocalEntry.isFinite() || vocalEntry < INTRO_BED_MIN_SECONDS) return null

    /*
     * A thresholded audibleStart is not the beginning of the musical arrangement. Very soft pads,
     * filtered drums and ambience are valuable Automix runway. Probe the real file head first and
     * keep it whenever it is low-vocal and not already a dense foreground section.
     */
    val headEnd = min(vocalEntry, INTRO_BED_HEAD_CUE_MAX_SECONDS)
    if (headEnd >= 1.0) {
        val headVocal = vocalActivityBetween(analysis, 0.0, headEnd)
        val headActivity = musicalActivityBetween(analysis, 0.0, headEnd)
        if (
            (headVocal == null || headVocal <= INTRO_BED_HEAD_MAX_VOCAL) &&
            (headActivity == null || headActivity <= INTRO_BED_HEAD_MAX_ACTIVITY)
        ) return 0.0
    }

    if (!audibleStart.isFinite() || vocalEntry - audibleStart < INTRO_BED_MIN_SECONDS) return null
    val latestUseful = vocalEntry - INTRO_BED_ENTRY_PROBE_SECONDS
    if (latestUseful <= audibleStart) return null

    val earliestCandidate =
        if (audibleStart <= INTRO_BED_EARLIEST_SAFE_SILENCE_GRACE_SECONDS) 0.0 else audibleStart
    val earliestProbeStart = earliestCandidate.coerceAtLeast(0.0)
    val earliestProbeEnd = min(vocalEntry, earliestProbeStart + INTRO_BED_EARLIEST_SAFE_PROBE_SECONDS)
    val earliestVocal = vocalActivityBetween(analysis, earliestProbeStart, earliestProbeEnd)
    if (
        earliestProbeEnd - earliestProbeStart >= 1.0 &&
        (earliestVocal == null || earliestVocal <= INTRO_BED_EARLIEST_SAFE_VOCAL)
    ) return earliestCandidate

    val curve = analysis.energyCurve
    if (curve.isNotEmpty()) {
        for (index in curve.indices) {
            val candidate = curve[index].time
            if (!candidate.isFinite() || candidate < audibleStart || candidate > latestUseful) continue
            if (!curve[index].energy.isFinite() || curve[index].energy < INTRO_BED_MIN_NORMALIZED_ENERGY) continue
            val energyEnd = min(vocalEntry, candidate + INTRO_BED_ENTRY_ENERGY_WINDOW_SECONDS)
            val meanEnergy = meanCurveEnergy(curve, candidate, energyEnd) ?: continue
            if (meanEnergy < INTRO_BED_MIN_NORMALIZED_ENERGY) continue
            val vocalEnd = min(vocalEntry, candidate + INTRO_BED_ENTRY_PROBE_SECONDS)
            val vocal = vocalActivityBetween(analysis, candidate, vocalEnd)
            if (vocal == null || vocal <= INTRO_BED_MAX_VOCAL) return candidate.coerceAtLeast(0.0)
        }
    }

    val structural = (analysis.downbeats + analysis.phraseBoundaries + analysis.mixInCandidates.map { it.time })
        .asSequence()
        .filter { it.isFinite() && it >= audibleStart - 0.05 && it <= latestUseful }
        .sorted()
        .firstOrNull { candidate ->
            val finish = min(vocalEntry, candidate + INTRO_BED_ENTRY_PROBE_SECONDS)
            val vocal = vocalActivityBetween(analysis, candidate, finish)
            vocal == null || vocal <= INTRO_BED_MAX_VOCAL
        }
    return (structural ?: audibleStart).coerceAtLeast(0.0)
}

/** Mean normalized energy in one timeline window. */
private fun meanCurveEnergy(curve: List<EnergySample>, from: Double, until: Double): Double? {
    if (curve.isEmpty() || until <= from) return null
    var index = curve.binarySearchBy(from) { it.time }.let { if (it >= 0) it else -it - 1 }
    var sum = 0.0
    var count = 0
    while (index < curve.size && curve[index].time < until) {
        val point = curve[index++]
        if (point.time.isFinite() && point.energy.isFinite() && point.energy >= 0.0) {
            sum += point.energy
            count += 1
        }
    }
    return if (count > 0) sum / count else null
}

/**
 * Finds B's first *major sustained structural arrival* after its quiet bed has
 * begun.  In pop/dance material this is usually the drop: energy rises, the low
 * band arrives and that new level survives for several seconds.
 *
 * This is intentionally independent from vocal detection.  An ad-lib, sample or
 * early sung phrase may appear before the drop and is useful information for
 * overlap safety, but it must not cause the planner to throw away 30 seconds of
 * instrumental runway.  The concrete regression is HEATED -> DANCE: the first
 * sustained-vocal heuristic pointed near 0:38 while the musically important
 * arrival is around 0:56.
 */
private fun structuralDropTime(
    analysis: TrackAnalysis,
    bedCue: Double,
    trackLength: Double,
): Double? {
    val energy = analysis.energyCurve
    if (energy.size < 6) return null

    val earliest = bedCue + INTRO_BED_DROP_MIN_AFTER_CUE_SECONDS
    val contentEnd = listOf(analysis.contentEndTime, trackLength)
        .filter { it.isFinite() && it > 0.0 }
        .minOrNull()
        ?: trackLength
    val latest = min(contentEnd - MIN_INCOMING_CLEARANCE_SECONDS, bedCue + INTRO_BED_MAX_SECONDS)
    if (!latest.isFinite() || latest <= earliest + 1.0) return null

    data class Drop(val time: Double, val score: Double)
    val candidates = mutableListOf<Drop>()

    for (point in energy) {
        val time = point.time
        if (!time.isFinite() || time < earliest || time > latest) continue
        // Do not back-date an impact merely because a future rise falls inside the post window.
        // The candidate frame itself must already belong to the new, audible arrangement level.
        if (!point.energy.isFinite() || point.energy < INTRO_BED_DROP_MIN_POST_ENERGY) continue

        // Leave a small gap immediately before the candidate so the onset frame
        // itself does not contaminate the baseline.  Then demand that the higher
        // level persists after it; one isolated transient is not a drop.
        val preEnergy = meanCurveEnergy(
            energy,
            time - INTRO_BED_DROP_PRE_WINDOW_SECONDS,
            time - 0.50,
        ) ?: continue
        val postEnergy = meanCurveEnergy(
            energy,
            time + 0.25,
            time + INTRO_BED_DROP_POST_WINDOW_SECONDS,
        ) ?: continue
        val energyLift = postEnergy - preEnergy

        val preLow = meanCurveEnergy(
            analysis.lowEnergyCurve,
            time - INTRO_BED_DROP_PRE_WINDOW_SECONDS,
            time - 0.50,
        )
        val postLow = meanCurveEnergy(
            analysis.lowEnergyCurve,
            time + 0.25,
            time + INTRO_BED_DROP_POST_WINDOW_SECONDS,
        )
        val lowLift = if (preLow != null && postLow != null) postLow - preLow else 0.0
        val hasLowEvidence = preLow != null && postLow != null

        // Either the full-band envelope or the bass band must make a meaningful
        // sustained step.  Requiring both would miss sparse drops; accepting
        // neither would simply rediscover the first loud phrase.
        val structuralLift = energyLift >= INTRO_BED_DROP_MIN_ENERGY_LIFT ||
            (hasLowEvidence && lowLift >= INTRO_BED_DROP_MIN_LOW_LIFT)
        if (!structuralLift || postEnergy < INTRO_BED_DROP_MIN_POST_ENERGY) continue

        // Bass arrival carries the most perceptual weight for this style.  A
        // downbeat/phrase near the step adds confidence, but never creates a drop
        // by itself — the measured envelope remains authoritative.
        val structuralNear = (analysis.downbeats + analysis.phraseBoundaries)
            .any { it.isFinite() && abs(it - time) <= INTRO_BED_DROP_STRUCTURAL_TOLERANCE_SECONDS }
        val score =
            0.52 * energyLift.coerceAtLeast(0.0) +
            0.72 * lowLift.coerceAtLeast(0.0) +
            0.12 * (postEnergy - INTRO_BED_DROP_MIN_POST_ENERGY).coerceAtLeast(0.0) +
            if (structuralNear) 0.06 else 0.0

        if (score >= INTRO_BED_DROP_MIN_SCORE) candidates += Drop(time, score)
    }

    if (candidates.isEmpty()) return null
    val bestScore = candidates.maxOf { it.score }
    // We want B's first major arrangement arrival, not its loudest later moment. Keep early
    // candidates only when they are a substantial fraction of the strongest measured step so a
    // tiny pickup does not beat the real drop, then choose the earliest qualifying event.
    val majorFloor = max(INTRO_BED_DROP_MIN_SCORE, bestScore * 0.72)
    val raw = candidates.asSequence()
        .filter { it.score >= majorFloor }
        .minByOrNull { it.time }
        ?.time
        ?: candidates.maxByOrNull { it.score }?.time
        ?: return null
    // Once an actual energy/bass step has been proven, snap only a little to a
    // musical boundary.  This keeps a detected 0:56 drop on its downbeat rather
    // than on whichever 500 ms analysis frame crossed the threshold first.
    return nearestTimedValue(
        values = analysis.phraseBoundaries + analysis.downbeats,
        target = raw,
        tolerance = INTRO_BED_DROP_STRUCTURAL_TOLERANCE_SECONDS,
        minimum = earliest,
    ) ?: raw
}

/**
 * Last confidently active outgoing-vocal sample in the planned overlap.
 *
 * INTRO_BED treats A's vocal as the foreground: B may already be playing under
 * it, but A should not start its real fade merely because B became audible.  The
 * fade is released only after the last measured lead-vocal activity. The full remaining
 * transition window is checked so a breath/word gap cannot masquerade as the end of the phrase.
 */
private fun outgoingVocalReleaseTime(
    analysis: TrackAnalysis,
    transitionStart: Double,
    transitionEnd: Double,
): Double? {
    val mask = analysis.vocalActivityMask
    val curve = analysis.energyCurve
    if (mask.isEmpty() || mask.size != curve.size) return null

    /*
     * First prove that this really is foreground singing. After that, do not
     * decide "the vocal ended" from one quiet classifier frame. A release is
     * accepted only when no meaningful vocal returns within roughly one bar.
     * This mirrors what the ear hears: a breath/pause between words belongs to
     * the same phrase and cannot authorize B's drop.
     */
    var firstForegroundIndex = -1
    var strongStreak = 0
    for (index in mask.indices) {
        val time = curve[index].time
        val vocal = mask[index]
        if (!time.isFinite() || !vocal.isFinite() || time < transitionStart || time > transitionEnd) continue
        if (vocal >= INTRO_BED_OUTGOING_VOCAL_THRESHOLD) {
            strongStreak += 1
            if (strongStreak >= INTRO_BED_OUTGOING_VOCAL_CONFIRM_SAMPLES) {
                firstForegroundIndex = index - INTRO_BED_OUTGOING_VOCAL_CONFIRM_SAMPLES + 1
                break
            }
        } else {
            strongStreak = 0
        }
    }
    if (firstForegroundIndex < 0) return null

    fun meaningfulAt(index: Int): Boolean {
        if (index !in mask.indices) return false
        val time = curve[index].time
        val vocal = mask[index]
        if (!time.isFinite() || !vocal.isFinite() || time < transitionStart || time > transitionEnd) return false
        if (vocal >= INTRO_BED_OUTGOING_VOCAL_RETURN_THRESHOLD) return true
        if (vocal < INTRO_BED_OUTGOING_VOCAL_TAIL_THRESHOLD) return false
        // A soft syllable/tail needs nearby corroboration so one noisy frame
        // cannot keep A alive to the file's end.
        val left = mask.getOrNull(index - 1) ?: 0.0
        val right = mask.getOrNull(index + 1) ?: 0.0
        return left >= INTRO_BED_OUTGOING_VOCAL_TAIL_THRESHOLD ||
            right >= INTRO_BED_OUTGOING_VOCAL_TAIL_THRESHOLD
    }

    var lastActive = curve[firstForegroundIndex].time
    var index = firstForegroundIndex
    while (index < mask.size) {
        val time = curve[index].time
        if (!time.isFinite() || time > transitionEnd) break
        if (time < transitionStart) {
            index += 1
            continue
        }

        if (meaningfulAt(index)) {
            lastActive = time
            index += 1
            continue
        }

        // Candidate release. If the vocal returns anywhere later in the final transition
        // window, this was only a breath/break inside the same ending.
        val lookEnd = transitionEnd
        var returnedAt = -1
        var probe = index + 1
        while (probe < mask.size) {
            val probeTime = curve[probe].time
            if (!probeTime.isFinite() || probeTime > lookEnd) break
            if (meaningfulAt(probe)) {
                returnedAt = probe
                break
            }
            probe += 1
        }
        if (returnedAt >= 0) {
            index = returnedAt
            continue
        }

        return lastActive.coerceAtMost(transitionEnd)
    }

    return lastActive.coerceAtMost(transitionEnd)
}

/**
 * Chooses the first musical boundary at or after [target].
 *
 * There is deliberately no fixed post-vocal delay here. A vocal that resolves
 * just before a downbeat can hand the room over immediately; another phrase may
 * need almost a bar. The grid decides. Phrase boundaries and downbeats are both
 * valid, and the earliest measured one wins.
 */
private fun musicalBoundaryAtOrAfter(
    analysis: TrackAnalysis,
    target: Double,
    ceiling: Double,
): Double {
    if (!target.isFinite()) return target
    val beat = analysis.beatInterval.orZero().takeIf { it > 0.0 }
        ?: analysis.bpm.orZero().takeIf { it > 0.0 }?.let { 60.0 / it }
        ?: return target.coerceAtMost(ceiling)

    // Search at most one musical bar. If the analyzer did not publish a usable
    // boundary in that span, keeping the measured release is safer than adding
    // an arbitrary number of seconds.
    val searchEnd = min(ceiling, target + beat * 4.0)
    if (searchEnd <= target) return target.coerceAtMost(ceiling)

    val phrase = analysis.phraseBoundaries
        .asSequence()
        .filter { it.isFinite() && it >= target - 0.01 && it <= searchEnd + 0.01 }
        .minOrNull()
    val downbeat = analysis.downbeats
        .asSequence()
        .filter { it.isFinite() && it >= target - 0.01 && it <= searchEnd + 0.01 }
        .minOrNull()

    return listOfNotNull(phrase, downbeat).minOrNull()
        ?.coerceIn(target, ceiling)
        ?: target.coerceAtMost(ceiling)
}

/**
 * A general Automix exit guard for analyzed transitions.
 *
 * When the nominated mix-out still sits inside a foreground vocal, move the
 * handoff to the measured vocal release and then onto the next musical boundary.
 * This is intentionally grid-based rather than a fixed delay, so it can react to
 * the actual phrase and tempo of each song.
 */
private fun vocalSafeMixAnchor(
    analysis: TrackAnalysis,
    preferred: Double,
    finalAnchor: Double,
): Double {
    if (!preferred.isFinite() || finalAnchor <= preferred + 0.05) return preferred
    val beat = analysis.beatInterval.orZero().takeIf { it > 0.0 }
        ?: analysis.bpm.orZero().takeIf { it > 0.0 }?.let { 60.0 / it }
        ?: 0.5

    /*
     * Do not gate this on "is there vocal exactly at preferred?". preferred can
     * land in a breath between words; that was the regression that allowed B's
     * drop to cut A halfway through the next word. Inspect the whole final
     * phrase region and only keep preferred when no confirmed vocal survives it.
     */
    val probeStart = max(0.0, preferred - beat * 4.0)
    val release = outgoingVocalReleaseTime(
        analysis = analysis,
        transitionStart = probeStart,
        transitionEnd = finalAnchor,
    ) ?: return preferred

    if (release <= preferred + 0.05) return preferred
    return musicalBoundaryAtOrAfter(
        analysis = analysis,
        target = release,
        ceiling = finalAnchor,
    ).coerceAtLeast(preferred)
}

/**
 * Refuses to hand off in the middle of a dense musical section even when no
 * foreground vocal is present. A chorus/drop with loud instrumentation is just
 * as structurally wrong a place to cut as a sung phrase. When activity around
 * the preferred point is high, scan forward on real phrase/downbeat boundaries
 * for the first measured release; if none exists soon enough, preserve A to its
 * natural content end.
 */
private fun activitySafeMixAnchor(
    analysis: TrackAnalysis,
    preferred: Double,
    finalAnchor: Double,
): Double {
    if (!preferred.isFinite() || finalAnchor <= preferred + 0.05) return preferred
    val beat = analysis.beatInterval.orZero().takeIf { it > 0.0 }
        ?: analysis.bpm.orZero().takeIf { it > 0.0 }?.let { 60.0 / it }
        ?: 0.5

    fun activityAt(time: Double): Double? = musicalActivityBetween(
        analysis,
        max(0.0, time - beat * 2.0),
        min(finalAnchor, time + beat * 2.0),
    )

    fun hasProtectedRebound(after: Double): Boolean {
        val step = max(1.5, beat * 2.0)
        var probe = max(after + step, preferred + step)
        while (probe < finalAnchor - 0.15) {
            val local = musicalActivityBetween(
                analysis,
                max(after, probe - step * 0.55),
                min(finalAnchor, probe + step * 0.55),
            )
            if (local != null && local >= ACTIVITY_REBOUND_THRESHOLD) return true
            probe += step
        }
        return false
    }

    val current = activityAt(preferred) ?: return preferred
    if (current < ACTIVITY_PROTECTED_THRESHOLD && !hasProtectedRebound(preferred)) return preferred

    val searchEnd = min(
        finalAnchor,
        preferred + max(ACTIVITY_SAFE_SEARCH_MIN_SECONDS, beat * 4.0 * ACTIVITY_SAFE_SEARCH_BARS),
    )
    val candidates = (analysis.phraseBoundaries + analysis.downbeats)
        .asSequence()
        .filter { it.isFinite() && it > preferred + beat * 0.5 && it <= searchEnd + 0.01 }
        .distinct()
        .sorted()

    for (candidate in candidates) {
        val before = musicalActivityBetween(
            analysis,
            max(0.0, candidate - beat * 2.0),
            candidate,
        )
        val after = musicalActivityBetween(
            analysis,
            candidate,
            min(finalAnchor, candidate + beat * 2.0),
        )
        val settled = listOfNotNull(before, after).maxOrNull() ?: continue
        if (settled <= ACTIVITY_RELEASE_THRESHOLD && !hasProtectedRebound(candidate + beat * 0.5)) {
            return candidate.coerceAtMost(finalAnchor)
        }
    }
    return finalAnchor
}

/**
 * Builds the deliberately non-DJ transition seen in the Spotify reference:
 * B starts as a quiet instrumental bed while A remains dominant. Its major
 * arrangement arrival is aligned toward A's natural end, while A's gain handoff
 * is independently held until the outgoing vocal is proven released. The server may request the style,
 * but this local proof is authoritative; without a measured low-vocal runway
 * the request is refused and the normal planner continues.
 */

private data class OverlayTempoPlan(
    val outgoingRate: Double,
    val incomingRate: Double,
    val mixBpm: Double,
)

/**
 * A DJ rarely asks one deck to absorb the full tempo difference when both can move a little.
 * For the long instrumental overlay we therefore meet in the middle, but only inside a very
 * conservative transparent window. Larger differences keep both decks at native tempo and rely
 * on phrase/arrangement timing instead of forcing an audible time stretch.
 */
private fun overlayTempoPlan(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
): OverlayTempoPlan {
    val outgoingBpm = analysis.bpm.orZero()
    val incomingBpm = alignTempoOctave(outgoingBpm, nextAnalysis.bpm.orZero())
    if (outgoingBpm <= 0.0 || incomingBpm <= 0.0) return OverlayTempoPlan(1.0, 1.0, 0.0)
    val outgoingConfidence = analysis.beatConfidence.coerceIn(0.0, 1.0)
    val incomingConfidence = nextAnalysis.beatConfidence.coerceIn(0.0, 1.0)
    val floorConfidence = min(outgoingConfidence, incomingConfidence)
    val peakConfidence = max(outgoingConfidence, incomingConfidence)
    if (floorConfidence < OVERLAY_MIN_BEAT_CONFIDENCE ||
        peakConfidence < OVERLAY_STRONG_BEAT_CONFIDENCE
    ) return OverlayTempoPlan(1.0, 1.0, 0.0)

    val mixBpm = sqrt(outgoingBpm * incomingBpm)
    val outgoingRate = mixBpm / outgoingBpm
    val incomingRate = mixBpm / incomingBpm
    val maxDeviation = max(abs(outgoingRate - 1.0), abs(incomingRate - 1.0))
    val allowedDeviation = when {
        floorConfidence >= 0.78 -> OVERLAY_MAX_DECK_RATE_DEVIATION_HIGH
        floorConfidence >= 0.62 -> OVERLAY_MAX_DECK_RATE_DEVIATION_MEDIUM
        else -> OVERLAY_MAX_DECK_RATE_DEVIATION_LOW
    }
    return if (maxDeviation <= allowedDeviation) {
        OverlayTempoPlan(
            outgoingRate = (outgoingRate * 10_000.0).roundToInt() / 10_000.0,
            incomingRate = (incomingRate * 10_000.0).roundToInt() / 10_000.0,
            mixBpm = mixBpm,
        )
    } else {
        OverlayTempoPlan(1.0, 1.0, 0.0)
    }
}

private data class OverlayLoopWindow(
    val start: Double,
    val end: Double,
    val score: Double,
) {
    val duration: Double get() = end - start
}

/**
 * Finds a genuinely loopable instrumental phrase in B's intro. We require a vocal mask here:
 * guessing that an unmeasured region is instrumental is exactly how a loop ends up repeating a
 * word or cutting a vocal pickup. Boundaries are quantized to measured downbeats and scored for
 * low vocal activity plus stable energy, which makes a repeated groove much less obvious than a
 * loop across a fill/build.
 */
private fun incomingOverlayLoopWindow(
    analysis: TrackAnalysis,
    bedCue: Double,
    handoff: Double,
): OverlayLoopWindow? {
    val beat = analysis.beatInterval.orZero().takeIf { it > 0.0 }
        ?: analysis.bpm.orZero().takeIf { it > 0.0 }?.let { 60.0 / it }
        ?: return null
    if (analysis.vocalActivityMask.isEmpty() ||
        analysis.vocalActivityMask.size != analysis.energyCurve.size
    ) return null

    val latestEnd = handoff - max(beat * 2.0, OVERLAY_LOOP_END_GUARD_SECONDS)
    if (latestEnd <= bedCue + OVERLAY_LOOP_MIN_SECONDS) return null
    val starts = (analysis.downbeats + analysis.phraseBoundaries)
        .asSequence()
        .filter { it.isFinite() && it >= bedCue - 0.05 && it <= latestEnd - OVERLAY_LOOP_MIN_SECONDS }
        .distinct()
        .sorted()
        .toList()
    if (starts.isEmpty()) return null

    var best: OverlayLoopWindow? = null
    for (start in starts) {
        for (bars in OVERLAY_LOOP_BAR_CHOICES) {
            val targetEnd = start + bars * 4.0 * beat
            if (targetEnd > latestEnd + beat * 0.35) continue
            val end = nearestTimedValue(
                analysis.downbeats,
                targetEnd,
                tolerance = max(0.18, beat * 0.35),
                minimum = start + OVERLAY_LOOP_MIN_SECONDS,
            ) ?: continue
            val duration = end - start
            if (duration !in OVERLAY_LOOP_MIN_SECONDS..OVERLAY_LOOP_MAX_SECONDS) continue

            val vocal = vocalActivityBetween(analysis, start, end) ?: continue
            if (vocal > OVERLAY_LOOP_MAX_VOCAL) continue
            val middle = start + duration / 2.0
            val leftEnergy = meanCurveEnergy(analysis.energyCurve, start, middle) ?: continue
            val rightEnergy = meanCurveEnergy(analysis.energyCurve, middle, end) ?: continue
            val reference = max(max(leftEnergy, rightEnergy), 0.05)
            val stability = (1.0 - abs(leftEnergy - rightEnergy) / reference).coerceIn(0.0, 1.0)
            if (stability < OVERLAY_LOOP_MIN_ENERGY_STABILITY) continue

            // Prefer a four-bar groove, then low vocals and stable energy. A loop beginning near
            // the front of the bed is slightly preferred because it leaves the natural build/drop
            // untouched for the eventual handoff.
            val barPreference = when (bars) {
                4 -> 1.0
                2 -> 0.88
                else -> 0.72
            }
            val earlyPreference = (1.0 - ((start - bedCue) / max(1.0, handoff - bedCue)))
                .coerceIn(0.0, 1.0)
            val score = 0.42 * (1.0 - vocal.coerceIn(0.0, 1.0)) +
                0.33 * stability + 0.17 * barPreference + 0.08 * earlyPreference
            if (best == null || score > best.score) best = OverlayLoopWindow(start, end, score)
        }
    }
    return best
}

/** Maps elapsed wall time onto B's media timeline while its intro loop is active. */
private fun loopedIncomingPosition(
    cue: Double,
    elapsedWallSeconds: Double,
    incomingRate: Double,
    loop: OverlayLoopWindow?,
    repeats: Int,
): Double {
    val travelled = elapsedWallSeconds.coerceAtLeast(0.0) * incomingRate.coerceAtLeast(0.01)
    if (loop == null || repeats <= 0 || loop.start < cue || loop.duration <= 0.0) return cue + travelled
    val toLoopEnd = loop.end - cue
    if (travelled <= toLoopEnd) return cue + travelled
    val afterFirstPass = travelled - toLoopEnd
    val repeatedSpan = loop.duration * repeats
    if (afterFirstPass < repeatedSpan) {
        val within = afterFirstPass % loop.duration
        return loop.start + within
    }
    return loop.end + (afterFirstPass - repeatedSpan)
}

private fun smoothUnit(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

/**
 * Builds a DJ-style gain ride for Instrumental Overlay from the exact material
 * the two decks will play. Before the foreground handoff, B is automatically
 * held lower underneath a dense/vocal A and can breathe higher under sparse
 * instrumental gaps. A is never faded simply because the mix has started; it
 * only leaves after the planner's activity/vocal-safe handoff.
 */
private fun overlayGainEnvelope(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionStart: Double,
    wallDuration: Double,
    handoffFraction: Double,
    outgoingRate: Double,
    incomingCue: Double,
    incomingRate: Double,
    loop: OverlayLoopWindow?,
    repeats: Int,
): List<TransitionGainPoint> {
    if (wallDuration <= 0.0) return emptyList()

    /*
     * Three distinct phases:
     *
     *  BED    - B becomes audible without challenging A.
     *  BUILD  - B follows the energy of its own intro and progressively gains body.
     *  IMPACT - B is already present before the structural impact; only ownership changes at end.
     *
     * The old curve pinned B around 8-20% until [handoffFraction]. With a protected A that fraction
     * can be 0.995, which turned "Entrada Instrumental" into an audible 20% -> 100% jump during the
     * final few hundred milliseconds. The release landmark still protects A's foreground, but it
     * no longer prevents B's instrumental arrangement from developing naturally underneath it.
     */
    val release = handoffFraction.coerceIn(0.50, 0.995)
    val incomingFinalRiseStart = min(
        release,
        (1.0 - OVERLAY_INCOMING_FINAL_RISE_SECONDS / wallDuration).coerceIn(0.60, 0.985),
    )
    val outgoingReleaseStart = min(
        release,
        (1.0 - OVERLAY_OUTGOING_FINAL_RELEASE_SECONDS / wallDuration).coerceIn(0.72, 0.992),
    )
    val points = ArrayList<TransitionGainPoint>(OVERLAY_GAIN_SAMPLES)

    repeat(OVERLAY_GAIN_SAMPLES) { index ->
        val progress = index.toDouble() / (OVERLAY_GAIN_SAMPLES - 1).coerceAtLeast(1)
        val wall = wallDuration * progress
        val aPos = transitionStart + wall * outgoingRate
        val bPos = loopedIncomingPosition(incomingCue, wall, incomingRate, loop, repeats)
        val probe = max(0.45, min(1.5, wallDuration / 28.0))

        val activityA = musicalActivityBetween(
            analysis,
            max(0.0, aPos - probe),
            aPos + probe,
        ) ?: 0.50
        val activityB = musicalActivityBetween(
            nextAnalysis,
            max(0.0, bPos - probe),
            bPos + probe,
        ) ?: 0.45
        val vocalA = vocalActivityBetween(
            analysis,
            max(0.0, aPos - probe),
            aPos + probe,
        ) ?: 0.45

        val densityA = max(activityA, 0.90 * vocalA).coerceIn(0.0, 1.0)
        val attackSpan = min(0.18, max(0.055, 8.0 / wallDuration))
        val attack = smoothUnit(progress / attackSpan)

        // BED: roughly 7-18%. A dense/vocal A keeps B smaller; a lighter A lets the first
        // instrumental details become perceptible sooner.
        val bedTarget = (
            0.09 +
                0.075 * (1.0 - densityA) +
                0.035 * activityB
            ).coerceIn(0.07, 0.18)

        // BUILD: B is no longer frozen at "background noise" level. Its own arrangement/energy
        // drives a gradual rise while A remains the perceptual foreground.
        val buildTarget = (
            0.31 +
                0.22 * activityB -
                0.11 * densityA
            ).coerceIn(OVERLAY_BUILD_TARGET_MIN, OVERLAY_BUILD_TARGET_MAX)
        val buildStart = attackSpan * 0.78
        val buildEnd = max(buildStart + 0.08, incomingFinalRiseStart)
        val build = smoothUnit((progress - buildStart) / (buildEnd - buildStart).coerceAtLeast(0.01))
        var incoming = (bedTarget + (buildTarget - bedTarget) * build) * attack

        // IMPACT PREP: during the last few seconds of B's intro it is allowed to become a
        // substantial layer (about 46-70%) without fading A. EQ/bass carve does the separation.
        val impactWindow = min(0.16, 5.0 / wallDuration).coerceAtLeast(0.025)
        val impactStart = (incomingFinalRiseStart - impactWindow).coerceAtLeast(buildStart)
        val impactPrep = smoothUnit(
            (progress - impactStart) / (incomingFinalRiseStart - impactStart).coerceAtLeast(0.01),
        )
        val impactTarget = (
            0.52 +
                0.20 * activityB -
                0.10 * vocalA
            ).coerceIn(OVERLAY_IMPACT_PREP_MIN, OVERLAY_IMPACT_PREP_MAX)
        incoming += (impactTarget - incoming) * impactPrep

        // Final incoming rise starts several seconds before the impact, so there is no 20->100%
        // cliff. A remains essentially full until its much shorter final release window.
        val incomingTakeover = smoothUnit(
            (progress - incomingFinalRiseStart) /
                (1.0 - incomingFinalRiseStart).coerceAtLeast(0.005),
        )
        incoming += (1.0 - incoming) * incomingTakeover

        // Preserve A. Only a tiny density-aware duck is allowed during the bed/build. The actual
        // A fade starts close to the natural end and is independent from B's earlier build.
        val bedDuck = (0.018 * (1.0 - densityA) * attack).coerceIn(0.0, 0.018)
        val outgoingTakeover = smoothUnit(
            (progress - outgoingReleaseStart) /
                (1.0 - outgoingReleaseStart).coerceAtLeast(0.004),
        )
        val outgoing = ((1.0 - bedDuck) * (1.0 - outgoingTakeover)).coerceIn(0.0, 1.0)

        points += TransitionGainPoint(
            progress = progress,
            incomingGain = incoming.coerceIn(0.0, 1.0),
            outgoingGain = outgoing,
        )
    }

    // Exact endpoints prevent interpolation/decoder noise from producing a click or a lingering deck.
    if (points.isNotEmpty()) {
        points[0] = points[0].copy(incomingGain = 0.0, outgoingGain = 1.0)
        points[points.lastIndex] = TransitionGainPoint(1.0, 1.0, 0.0)
    }
    return points
}

/** Vocal-collision score for the exact rate/loop choreography used by the overlay. */
private fun overlayVocalOverlap(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    transitionStart: Double,
    wallDuration: Double,
    outgoingRate: Double,
    incomingCue: Double,
    incomingRate: Double,
    loop: OverlayLoopWindow?,
    repeats: Int,
): Double {
    if (wallDuration <= 0.0) return 0.0
    var sum = 0.0
    var peak = 0.0
    var measured = 0
    repeat(OVERLAY_VOCAL_SAMPLES) { index ->
        val fraction = index.toDouble() / (OVERLAY_VOCAL_SAMPLES - 1).coerceAtLeast(1)
        val wall = wallDuration * fraction
        val aPos = transitionStart + wall * outgoingRate
        val bPos = loopedIncomingPosition(incomingCue, wall, incomingRate, loop, repeats)
        val aVocal = vocalActivityBetween(analysis, max(0.0, aPos - 0.25), aPos + 0.25)
        val bVocal = vocalActivityBetween(nextAnalysis, max(0.0, bPos - 0.25), bPos + 0.25)
        if (aVocal == null || bVocal == null) return@repeat
        val collision = min(aVocal, bVocal).coerceIn(0.0, 1.0)
        sum += collision
        peak = max(peak, collision)
        measured += 1
    }
    if (measured == 0) return 0.0
    return (0.65 * (sum / measured) + 0.35 * peak).coerceIn(0.0, 1.0)
}

private fun introBedTransition(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    playbackTime: Double,
    mixAnchor: Double,
    finalMixAnchor: Double,
    nextLength: Double,
    style: TransitionStyle = TransitionStyle.INTRO_BED,
): TransitionPlan? {
    if (mixAnchor <= INTRO_BED_MIN_SECONDS || nextLength <= 0.0) return null
    if (style != TransitionStyle.INTRO_BED && style != TransitionStyle.INTRO_BRIDGE_FILTER) return null

    val audibleStart = audibleStartOf(nextAnalysis)
    val firstVocal = firstSustainedVocalTime(nextAnalysis)
        ?: listOf(nextAnalysis.mixInTime, nextAnalysis.introEndTime)
            .filter { it.isFinite() && it > audibleStart }
            .maxOrNull()
        ?: return null

    val bedCue = instrumentalBedCueTime(nextAnalysis, firstVocal) ?: return null
    val openingProbeStart = max(bedCue, audibleStart)
    val openingProbeEnd = min(
        firstVocal,
        openingProbeStart + max(INTRO_BED_ENTRY_PROBE_SECONDS, 8.0),
    )
    val openingVocal = vocalActivityBetween(nextAnalysis, openingProbeStart, openingProbeEnd)
    if (openingVocal != null && openingVocal > INTRO_BED_MAX_VOCAL) return null

    val structuralArrival = structuralDropTime(nextAnalysis, bedCue, nextLength)
    val annotatedIntroEnd = nextAnalysis.introEndTime.takeIf {
        it.isFinite() &&
            it - bedCue >= INTRO_BED_DROP_MIN_AFTER_CUE_SECONDS &&
            it < nextLength - MIN_INCOMING_CLEARANCE_SECONDS
    }
    // The bed ends at B's first major arrangement impact. An earlier intro-end/drop beats a later
    // first sustained vocal; otherwise a 0:56 impact + 1:08 vocal incorrectly starts A at 3:12.
    val incomingHandoff = listOfNotNull(structuralArrival, annotatedIntroEnd, firstVocal)
        .filter { it - bedCue >= INTRO_BED_MIN_SECONDS }
        .minOrNull()
        ?: firstVocal
    val fullIncomingRunway = incomingHandoff - bedCue
    if (fullIncomingRunway < INTRO_BED_MIN_SECONDS) return null

    val tempo = overlayTempoPlan(analysis, nextAnalysis)
    val outgoingRate = tempo.outgoingRate.coerceAtLeast(0.01)
    val incomingRate = tempo.incomingRate.coerceAtLeast(0.01)
    val naturalWallRunway = fullIncomingRunway / incomingRate

    val contentEnd = finalMixAnchor.coerceAtLeast(mixAnchor)
    if (contentEnd <= INTRO_BED_MIN_SECONDS) return null
    val effectiveMixAnchor = min(
        mixAnchor,
        (contentEnd - min(naturalWallRunway, INTRO_BED_MAX_SECONDS) * outgoingRate)
            .coerceAtLeast(INTRO_BED_MIN_SECONDS),
    )

    // A remains authoritative until its measured foreground vocal really releases. A short breath
    // is not enough: outgoingVocalReleaseTime already confirms that the vocal does not immediately
    // return, then we quantize the release to the next phrase/downbeat.
    val lateVocalRelease = outgoingVocalReleaseTime(
        analysis = analysis,
        transitionStart = max(0.0, effectiveMixAnchor - 8.0),
        transitionEnd = contentEnd,
    )?.takeIf { it > effectiveMixAnchor - 0.05 }

    val vocalSafeTarget = if (lateVocalRelease != null) {
        musicalBoundaryAtOrAfter(
            analysis = analysis,
            target = lateVocalRelease.coerceAtLeast(effectiveMixAnchor),
            ceiling = contentEnd,
        ).coerceIn(effectiveMixAnchor, contentEnd)
    } else {
        musicalBoundaryAtOrAfter(
            analysis = analysis,
            target = effectiveMixAnchor,
            ceiling = contentEnd,
        ).coerceIn(effectiveMixAnchor, contentEnd)
    }
    val activitySafeTarget = activitySafeMixAnchor(
        analysis = analysis,
        preferred = vocalSafeTarget,
        finalAnchor = contentEnd,
    ).coerceIn(effectiveMixAnchor, contentEnd)

    // Prefer A's natural content end. If B's unedited intro cannot physically fit, fall back to the
    // safest measured A boundary rather than chopping a lead phrase to make the trick happen.
    val naturalEndFeasible = contentEnd - naturalWallRunway * outgoingRate >= MIN_CLEARANCE_SECONDS
    val arrivalTarget = if (naturalEndFeasible) contentEnd else max(activitySafeTarget, effectiveMixAnchor)
        .coerceIn(effectiveMixAnchor, contentEnd)

    val loop = incomingOverlayLoopWindow(nextAnalysis, bedCue, incomingHandoff)
    val mixBpm = tempo.mixBpm.takeIf { it > 0.0 }
        ?: analysis.bpm.orZero().takeIf { it > 0.0 }
        ?: nextAnalysis.bpm.orZero().takeIf { it > 0.0 }
        ?: 120.0
    val targetOverlayWall = clamp(
        OVERLAY_TARGET_BEATS * 60.0 / mixBpm,
        OVERLAY_TARGET_MIN_SECONDS,
        OVERLAY_TARGET_MAX_SECONDS,
    )
    val earliestStart = max(MIN_CLEARANCE_SECONDS, playbackTime.coerceAtLeast(0.0))
    val availableWall = ((arrivalTarget - earliestStart) / outgoingRate).coerceAtLeast(0.0)

    var loopRepeats = 0
    if (loop != null && availableWall > naturalWallRunway + 0.25) {
        val loopWall = loop.duration / incomingRate
        if (loopWall > 0.0) {
            val desiredWall = max(naturalWallRunway, targetOverlayWall)
                .coerceAtMost(min(INTRO_BED_MAX_SECONDS, availableWall))
            val wanted = ceil((desiredWall - naturalWallRunway).coerceAtLeast(0.0) / loopWall).toInt()
            val fits = floor((availableWall - naturalWallRunway).coerceAtLeast(0.0) / loopWall).toInt()
            loopRepeats = minOf(wanted, fits, OVERLAY_MAX_LOOP_REPEATS).coerceAtLeast(0)
        }
    }

    var selectedLoop = loop?.takeIf { loopRepeats > 0 }
    var actualWall = naturalWallRunway +
        (selectedLoop?.duration?.times(loopRepeats)?.div(incomingRate) ?: 0.0)
    var transitionStart = arrivalTarget - actualWall * outgoingRate
    var incomingCue = bedCue

    // Analysis can arrive after the mathematically ideal start. In that case first drop loop
    // repetitions, then (only if still necessary) trim the front of B's intro by the unavoidable
    // amount. Never move A's release earlier just to keep a fancy overlay alive.
    while (loopRepeats > 0 && transitionStart < playbackTime - 0.05) {
        loopRepeats -= 1
        if (loopRepeats == 0) selectedLoop = null
        actualWall = naturalWallRunway +
            (selectedLoop?.duration?.times(loopRepeats)?.div(incomingRate) ?: 0.0)
        transitionStart = arrivalTarget - actualWall * outgoingRate
    }
    if (transitionStart < playbackTime - 0.05 || transitionStart < MIN_CLEARANCE_SECONDS) {
        selectedLoop = null
        loopRepeats = 0
        transitionStart = max(playbackTime, MIN_CLEARANCE_SECONDS)
            .coerceAtMost(arrivalTarget - INTRO_BED_MIN_SECONDS * outgoingRate)
        actualWall = (arrivalTarget - transitionStart) / outgoingRate
        if (actualWall < INTRO_BED_MIN_SECONDS) return null
        val incomingRunway = actualWall * incomingRate
        incomingCue = (incomingHandoff - incomingRunway)
            .coerceAtLeast(bedCue)
            .coerceAtMost(incomingHandoff - INTRO_BED_MIN_SECONDS * incomingRate)
    }

    if (actualWall < INTRO_BED_MIN_SECONDS || actualWall > INTRO_BED_MAX_SECONDS + 0.25) return null
    if (incomingCue < 0.0 || incomingCue >= incomingHandoff) return null

    val releaseTarget = max(transitionStart, min(activitySafeTarget, arrivalTarget))
    val outgoingMediaSpan = arrivalTarget - transitionStart
    if (outgoingMediaSpan <= 0.0) return null
    val rawHandoffFraction = ((releaseTarget - transitionStart) / outgoingMediaSpan)
        .takeIf { it.isFinite() } ?: HANDOFF_FRACTION
    val latestHandoffFraction = if (
        lateVocalRelease != null || activitySafeTarget >= arrivalTarget - 0.05
    ) {
        INTRO_BED_LATEST_HANDOFF_FRACTION
    } else {
        (1.0 - INTRO_BED_MIN_HANDOFF_SECONDS / actualWall).coerceIn(
            INTRO_BED_EARLIEST_HANDOFF_FRACTION,
            INTRO_BED_LATEST_HANDOFF_FRACTION,
        )
    }
    val handoffFraction = rawHandoffFraction.coerceIn(
        INTRO_BED_EARLIEST_HANDOFF_FRACTION,
        latestHandoffFraction,
    )

    val overlapVocals = overlayVocalOverlap(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        transitionStart = transitionStart,
        wallDuration = actualWall,
        outgoingRate = outgoingRate,
        incomingCue = incomingCue,
        incomingRate = incomingRate,
        loop = selectedLoop,
        repeats = loopRepeats,
    )
    val gainEnvelope = overlayGainEnvelope(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        transitionStart = transitionStart,
        wallDuration = actualWall,
        handoffFraction = handoffFraction,
        outgoingRate = outgoingRate,
        incomingCue = incomingCue,
        incomingRate = incomingRate,
        loop = selectedLoop,
        repeats = loopRepeats,
    )
    val started = playbackTime >= transitionStart
    val tempoTag = if (tempo.mixBpm > 0.0) "-dual-tempo" else ""
    val loopTag = if (loopRepeats > 0) "-loop${loopRepeats}" else ""
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,        transitionStart = transitionStart,
        transitionEnd = arrivalTarget,
        // For this style fadeSeconds is deliberately wall-clock duration. The controller drives
        // it from a pause-aware mix clock because B may seek backwards through an intro loop.
        fadeSeconds = actualWall,
        handoffDuration = actualWall,
        incomingCueTime = incomingCue,
        incomingHandoffTime = incomingHandoff,
        outgoingPlaybackRate = outgoingRate,
        incomingPlaybackRate = incomingRate,
        loopTarget = if (selectedLoop != null) TransitionLoopTarget.INCOMING else TransitionLoopTarget.NONE,
        loopStartTime = selectedLoop?.start ?: 0.0,
        loopEndTime = selectedLoop?.end ?: 0.0,
        loopRepeats = loopRepeats,
        pickupSeconds = audibleStart,
        transitionBeats = (actualWall * mixBpm / 60.0).roundToInt().coerceAtLeast(0),
        // The renderer performs a true low-end handoff with the three-band DJ EQ. Keeping the
        // filter sweep separate preserves the subtle bridge variant without stacking two gestures.
        bassSwap = true,
        bassSwapFraction = handoffFraction.coerceIn(0.45, 0.90),
        handoffFraction = handoffFraction,
        filterSweep = if (style == TransitionStyle.INTRO_BRIDGE_FILTER) 0.38 else 0.0,
        gainEnvelope = gainEnvelope,
        vocalOverlap = overlapVocals,
        outgoingBpm = analysis.bpm.orZero(),
        incomingBpm = alignTempoOctave(analysis.bpm.orZero(), nextAnalysis.bpm.orZero()),
        transitionStyle = style,
        reason = when {
            started -> "instrumental-overlay$tempoTag$loopTag"
            else -> "before-instrumental-overlay$tempoTag$loopTag"
        },
    )
}

private fun cutTransition(
    nextAnalysis: TrackAnalysis,
    playbackTime: Double,
    mixAnchor: Double,
    outgoingBpm: Double,
): TransitionPlan? {
    if (mixAnchor <= CUT_MIN_SECONDS) return null
    val beat = if (outgoingBpm.isFinite() && outgoingBpm in 40.0..220.0) 60.0 / outgoingBpm else 0.5
    val overlap = clamp(beat, CUT_MIN_SECONDS, CUT_MAX_SECONDS)
    val transitionStart = (mixAnchor - overlap).coerceAtLeast(0.0)
    val actual = mixAnchor - transitionStart
    if (actual <= 0.0) return null
    val cue = audibleStartOf(nextAnalysis)
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = mixAnchor,
        fadeSeconds = actual,
        handoffDuration = actual,
        incomingCueTime = cue,
        incomingPlaybackRate = 1.0,
        pickupSeconds = cue,
        bassSwap = false,
        filterSweep = 0.0,
        vocalOverlap = 0.0,
        transitionStyle = TransitionStyle.CUT,
        reason = if (started) "remote-cut" else "before-remote-cut-window",
    )
}


/**
 * Structural phrase cut. B is deliberately cued into a strong phrase/drop and
 * that event is aligned with a vocal-safe boundary in A. This is separate from
 * CUT: CUT is a generic short escape hatch, PHRASE_CUT is an intentional
 * phrase-to-phrase edit.
 */
private fun phraseCutTransition(
    nextAnalysis: TrackAnalysis,
    playbackTime: Double,
    safeAnchor: Double,
    finalAnchor: Double,
    outgoingBpm: Double,
): TransitionPlan? {
    if (safeAnchor <= PHRASE_CUT_MIN_SECONDS) return null

    val strongEntry = rankMixInCandidates(nextAnalysis)
        .firstOrNull { it.score >= PHRASE_CUT_MIN_ENTRY_SCORE }
        ?.time
        ?: incomingMixInPoint(nextAnalysis)
        ?: incomingCuePoint(nextAnalysis)
    if (!strongEntry.isFinite() || strongEntry < 0.0) return null

    val beat = if (outgoingBpm.isFinite() && outgoingBpm in 40.0..220.0) {
        60.0 / outgoingBpm
    } else {
        0.5
    }
    val overlap = clamp(beat * 1.5, PHRASE_CUT_MIN_SECONDS, PHRASE_CUT_MAX_SECONDS)
    val before = overlap * 0.58
    val after = overlap - before
    val transitionStart = (safeAnchor - before).coerceAtLeast(0.0)
    val transitionEnd = min(finalAnchor, safeAnchor + after).coerceAtLeast(safeAnchor)
    val actual = transitionEnd - transitionStart
    if (actual <= 0.10) return null

    val anchorOffset = safeAnchor - transitionStart
    val cue = (strongEntry - anchorOffset)
        .coerceAtLeast(audibleStartOf(nextAnalysis))
        .coerceAtLeast(0.0)
    val handoffFraction = (anchorOffset / actual).coerceIn(0.35, 0.85)
    val started = playbackTime >= transitionStart

    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
        fadeSeconds = actual,
        handoffDuration = actual,
        incomingCueTime = cue,
        incomingHandoffTime = strongEntry,
        incomingPlaybackRate = 1.0,
        pickupSeconds = audibleStartOf(nextAnalysis),
        bassSwap = false,
        handoffFraction = handoffFraction,
        filterSweep = 0.0,
        vocalOverlap = 0.0,
        transitionStyle = TransitionStyle.PHRASE_CUT,
        reason = if (started) "remote-phrase-cut" else "before-remote-phrase-cut-window",
    )
}

private data class Overlap(
    val overlap: Double,
    val transitionBeats: Int,
    val incomingPlaybackRate: Double,
)

/** How long a mix should run when the tracks are related but not phrase-switchable. */
private fun adaptiveOverlap(analysis: TrackAnalysis, nextAnalysis: TrackAnalysis): Overlap {
    val currentBpm = analysis.bpm.orZero()
    val nextBpm = nextAnalysis.bpm.orZero()
    if (currentBpm <= 0 || nextBpm <= 0) {
        return Overlap(AUTO_FALLBACK_SECONDS, 0, 1.0)
    }

    val ratio = normalizedTempoRatio(currentBpm, nextBpm)
    val distance = keyDistance(trustedKey(analysis), trustedKey(nextAnalysis))
    val vocalConflict = analysis.vocalProbability >= 0.62 && nextAnalysis.vocalProbability >= 0.62
    val transitionBeats =
        if (!vocalConflict && (abs(1 - ratio) > 0.07 || (distance != null && distance > 4))) 16 else 8
    val beatSeconds = 60 / currentBpm
    val minimumOverlap = if (currentBpm >= 140) AUTO_FAST_TRACK_MIN_SECONDS else AUTO_MIN_SECONDS

    return Overlap(
        overlap = clamp(transitionBeats * beatSeconds, minimumOverlap, AUTO_TRANSITION_MAX_SECONDS),
        transitionBeats = transitionBeats,
        incomingPlaybackRate = if (ratio in 0.9..1.1) {
            (clamp(1 / ratio, 0.9, 1.1) * 10000).roundToInt() / 10000.0
        } else {
            1.0
        },
    )
}


private const val REMOTE_DIRECTIVE_MIN_CONFIDENCE = 0.56
private const val REMOTE_IMPACT_HANDOFF_TOLERANCE_SECONDS = 3.0
private const val REMOTE_INTRO_TIMING_TOLERANCE_SECONDS = 3.0
private const val REMOTE_STYLE_PRIOR = 0.075
private const val ADAPTIVE_SPECIAL_MARGIN = 0.035

private fun foregroundProtectedToEnd(analysis: TrackAnalysis, finalAnchor: Double): Boolean {
    if (finalAnchor <= 0.0) return false
    val start = max(0.0, finalAnchor - 12.0)
    val activity = musicalActivityBetween(analysis, start, finalAnchor) ?: return false
    val vocal = vocalActivityBetween(analysis, start, finalAnchor) ?: 0.0
    return (activity >= 0.74 && vocal >= 0.48) || (activity >= 0.90 && vocal >= 0.28)
}

private fun foregroundTakeoverTransition(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    playbackTime: Double,
    finalAnchor: Double,
): TransitionPlan? {
    if (finalAnchor <= 6.0 || foregroundProtectedToEnd(analysis, finalAnchor)) return null
    val cue = audibleStartOf(nextAnalysis).coerceAtLeast(0.0)
    val openingEnd = min(nextAnalysis.duration.takeIf { it > 0.0 } ?: cue + 8.0, cue + 8.0)
    val incomingActivity = musicalActivityBetween(nextAnalysis, cue, openingEnd) ?: 0.5
    val incomingVocal = vocalActivityBetween(nextAnalysis, cue, openingEnd) ?: nextAnalysis.vocalProbability
    val outgoingTailActivity = musicalActivityBetween(analysis, max(0.0, finalAnchor - 10.0), finalAnchor) ?: 0.5
    val outgoingTailVocal = vocalActivityBetween(analysis, max(0.0, finalAnchor - 10.0), finalAnchor) ?: analysis.vocalProbability

    // If A is still both dense and vocal, B is not allowed to steal foreground early.
    if (outgoingTailVocal >= 0.62 && outgoingTailActivity >= 0.82) return null
    val assertiveness = (0.68 * incomingActivity + 0.32 * incomingVocal).coerceIn(0.0, 1.0)
    if (assertiveness < 0.54) return null

    val bpm = analysis.bpm.takeIf { it in 40.0..220.0 } ?: 120.0
    val span = clamp(24.0 * 60.0 / bpm, 6.0, 20.0).coerceAtMost(finalAnchor)
    val transitionStart = max(0.0, finalAnchor - span)
    val releaseNeed = (0.62 * outgoingTailActivity + 0.38 * outgoingTailVocal).coerceIn(0.0, 1.0)
    val handoff = (0.20 + 0.16 * releaseNeed).coerceIn(0.16, 0.42)
    val started = playbackTime >= transitionStart
    val gain = listOf(
        TransitionGainPoint(0.0, 0.0, 1.0),
        TransitionGainPoint(0.12, 0.48, 1.0),
        TransitionGainPoint(handoff, 0.88, 0.90),
        TransitionGainPoint(min(0.70, handoff + 0.30), 1.0, 0.42),
        TransitionGainPoint(1.0, 1.0, 0.0),
    ).sortedBy { it.progress }

    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = finalAnchor,
        fadeSeconds = finalAnchor - transitionStart,
        handoffDuration = finalAnchor - transitionStart,
        incomingCueTime = cue,
        incomingHandoffTime = nextAnalysis.mixInTime.takeIf { it.isFinite() && it >= cue } ?: cue,
        outgoingPlaybackRate = 1.0,
        incomingPlaybackRate = 1.0,
        pickupSeconds = cue,
        bassSwap = analysis.lowEnergyCurve.isNotEmpty() && nextAnalysis.lowEnergyCurve.isNotEmpty(),
        bassSwapFraction = 0.46,
        handoffFraction = handoff,
        filterSweep = 0.0,
        gainEnvelope = gain,
        vocalOverlap = min(outgoingTailVocal, incomingVocal).coerceIn(0.0, 1.0),
        outgoingBpm = analysis.bpm.orZero(),
        incomingBpm = nextAnalysis.bpm.orZero(),
        transitionStyle = TransitionStyle.FOREGROUND_TAKEOVER,
        reason = if (started) "foreground-takeover" else "before-foreground-takeover",
    )
}

private data class AdaptiveSpecialCandidate(val plan: TransitionPlan, val score: Double)

private fun chooseAdaptiveSpecialTransition(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    playbackTime: Double,
    mixAnchor: Double,
    safeMixAnchor: Double,
    finalMixAnchor: Double,
    nextLength: Double,
    styleHint: TransitionStyle?,
): TransitionPlan? {
    val tailStart = max(0.0, finalMixAnchor - 10.0)
    val aTailActivity = musicalActivityBetween(analysis, tailStart, finalMixAnchor) ?: 0.5
    val aTailVocal = vocalActivityBetween(analysis, tailStart, finalMixAnchor) ?: analysis.vocalProbability
    val bStart = audibleStartOf(nextAnalysis)
    val bOpenEnd = min(nextLength.takeIf { it > 0.0 } ?: bStart + 8.0, bStart + 8.0)
    val bOpenActivity = musicalActivityBetween(nextAnalysis, bStart, bOpenEnd) ?: 0.5
    val bOpenVocal = vocalActivityBetween(nextAnalysis, bStart, bOpenEnd) ?: nextAnalysis.vocalProbability
    val tempoRatio = normalizedTempoRatio(analysis.bpm.orZero(), nextAnalysis.bpm.orZero())
    val tempoCompatibility = (1.0 - abs(tempoRatio - 1.0) / 0.14).coerceIn(0.0, 1.0)
    val beatConfidence = min(analysis.beatConfidence.coerceIn(0.0, 1.0), nextAnalysis.beatConfidence.coerceIn(0.0, 1.0))
    val protected = foregroundProtectedToEnd(analysis, finalMixAnchor)
    val candidates = mutableListOf<AdaptiveSpecialCandidate>()

    val introStyle = if (aTailActivity >= 0.78 && bOpenActivity >= 0.62) {
        TransitionStyle.INTRO_BRIDGE_FILTER
    } else {
        TransitionStyle.INTRO_BED
    }
    introBedTransition(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        playbackTime = playbackTime,
        mixAnchor = mixAnchor,
        finalMixAnchor = finalMixAnchor,
        nextLength = nextLength,
        style = introStyle,
    )?.let { plan ->
        val runway = (plan.incomingHandoffTime - plan.incomingCueTime).coerceAtLeast(0.0)
        val longIntro = ((runway - 6.0) / 42.0).coerceIn(0.0, 1.0)
        var score = 0.46 + 0.22 * longIntro + 0.13 * (1.0 - bOpenVocal) + 0.08 * aTailActivity + 0.06 * tempoCompatibility
        if (protected) score += 0.06
        if (styleHint == plan.transitionStyle || (styleHint == TransitionStyle.INTRO_BED && plan.transitionStyle == TransitionStyle.INTRO_BRIDGE_FILTER)) {
            score += REMOTE_STYLE_PRIOR
        }
        candidates += AdaptiveSpecialCandidate(plan, score)
    }

    foregroundTakeoverTransition(analysis, nextAnalysis, playbackTime, finalMixAnchor)?.let { plan ->
        val outgoingRelease = (1.0 - (0.62 * aTailActivity + 0.38 * aTailVocal)).coerceIn(0.0, 1.0)
        val incomingAssert = (0.68 * bOpenActivity + 0.32 * bOpenVocal).coerceIn(0.0, 1.0)
        val introRunway = (nextAnalysis.introEndTime - bStart).coerceAtLeast(0.0)
        val shortIntro = (1.0 - introRunway / 18.0).coerceIn(0.0, 1.0)
        var score = 0.32 + 0.24 * outgoingRelease + 0.22 * incomingAssert + 0.15 * shortIntro + 0.05 * tempoCompatibility
        if (outgoingRelease >= 0.60 && incomingAssert >= 0.65 && shortIntro >= 0.55) score += 0.15
        if (styleHint == TransitionStyle.FOREGROUND_TAKEOVER) score += REMOTE_STYLE_PRIOR
        candidates += AdaptiveSpecialCandidate(plan, score)
    }

    phraseCutTransition(nextAnalysis, playbackTime, safeMixAnchor, finalMixAnchor, analysis.bpm.orZero())?.let { plan ->
        val skipped = (plan.incomingCueTime - bStart).coerceAtLeast(0.0)
        val longIntroPenalty = ((skipped - 8.0) / 24.0).coerceIn(0.0, 1.0)
        val vocalClash = min(aTailVocal, bOpenVocal)
        var score = 0.24 + 0.28 * (0.55 * vocalClash + 0.45 * (1.0 - tempoCompatibility)) + 0.12 * bOpenActivity - 0.40 * longIntroPenalty
        if (styleHint == TransitionStyle.PHRASE_CUT) score += REMOTE_STYLE_PRIOR
        if (skipped <= 8.0) candidates += AdaptiveSpecialCandidate(plan, score)
    }

    if (protected) {
        cutTransition(nextAnalysis, playbackTime, finalMixAnchor, analysis.bpm.orZero())?.let { plan ->
            var score = 0.48 + 0.16 * bOpenVocal + 0.08 * bOpenActivity
            if (styleHint == TransitionStyle.CUT) score += REMOTE_STYLE_PRIOR
            candidates += AdaptiveSpecialCandidate(plan.copy(reason = if (plan.shouldStart) "protected-clean-handoff" else "before-protected-clean-handoff"), score)
        }
    }

    if (candidates.isEmpty()) return null

    // A protected ending is a hard musical-integrity rail, not a scoring preference. If B can
    // safely sit underneath it, use that intro. Otherwise preserve A to its natural end and use
    // the short clean handoff. A symmetric blend must never win merely because its grid score is high.
    if (protected) {
        candidates
            .filter { it.plan.transitionStyle == TransitionStyle.INTRO_BED || it.plan.transitionStyle == TransitionStyle.INTRO_BRIDGE_FILTER }
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= 0.50 }
            ?.let { return it.plan }
        candidates.firstOrNull { it.plan.transitionStyle == TransitionStyle.CUT }?.let { return it.plan }
    }

    val winner = candidates.maxByOrNull { it.score } ?: return null
    val vocalClash = min(aTailVocal, bOpenVocal)
    val ordinaryScore = 0.34 + 0.27 * tempoCompatibility + 0.13 * beatConfidence + 0.15 * (1.0 - vocalClash)
    return winner.plan.takeIf { winner.score >= max(0.50, ordinaryScore + ADAPTIVE_SPECIAL_MARGIN) }
}

private fun validatedRemoteDirective(
    remote: RemoteTransitionDirective,
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    playbackTime: Double,
    finalMixAnchor: Double,
    nextLength: Double,
): TransitionPlan? {
    // v5/2.5 contract: style/timing/choreography are musical decisions owned by the server. The phone
    // only refuses values that cannot be executed safely on the current media timelines; it does
    // not reinterpret foreground/vocal/intro evidence and it never swaps the chosen family.
    if (!remote.serverAuthoritative) return null
    if (remote.blocked) {
        return blocked(
            reason = "server-authoritative-2.5-no-transition",
            transitionStart = finalMixAnchor,
            transitionEnd = finalMixAnchor,
        )
    }
    val start = remote.transitionStart
    val end = remote.transitionEnd
    if (!start.isFinite() || !end.isFinite() || end <= start + 0.10) return null
    if (start < 0.0 || end > finalMixAnchor + 0.75) return null

    val span = end - start
    val cue = remote.incomingCueTime
    val handoff = remote.incomingHandoffTime
    if (!cue.isFinite() || !handoff.isFinite() || cue < 0.0 || handoff < cue) return null
    if (nextLength > 0.0 && (cue >= nextLength - 0.25 || handoff > nextLength + 0.75)) return null
    if (remote.incomingPlaybackRate !in 0.90..1.10 || remote.outgoingPlaybackRate !in 0.90..1.10) return null

    val started = playbackTime >= start
    val vocalOverlap = plannedVocalOverlap(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        transitionStart = start,
        transitionEnd = end,
        incomingCueTime = cue,
        incomingPlaybackRate = remote.incomingPlaybackRate,
    )
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = start,
        transitionEnd = end,
        fadeSeconds = span,
        handoffDuration = span,
        incomingCueTime = cue,
        incomingHandoffTime = handoff,
        outgoingPlaybackRate = remote.outgoingPlaybackRate,
        incomingPlaybackRate = remote.incomingPlaybackRate,
        pickupSeconds = audibleStartOf(nextAnalysis),
        bassSwap = remote.bassSwap,
        bassSwapFraction = remote.bassSwapFraction,
        handoffFraction = remote.handoffFraction,
        filterSweep = remote.filterSweep,
        gainEnvelope = remote.gainEnvelope,
        vocalOverlap = vocalOverlap,
        outgoingBpm = analysis.bpm.orZero(),
        incomingBpm = nextAnalysis.bpm.orZero(),
        transitionStyle = remote.style,
        reason = if (started) "server-authoritative-v6-${remote.reason}" else "before-server-authoritative-v6-${remote.reason}",
    )
}

private fun standardTransition(
    length: Double,
    playbackTime: Double,
    fadeSeconds: Double,
    minFadeSeconds: Double,
    reason: String = "standard",
): TransitionPlan {
    val fade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    val transitionStart = max(0.0, length - fade)
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = length,
        fadeSeconds = fade,
        transitionStyle = TransitionStyle.EQUAL_POWER,
        reason = if (started) reason else "before-$reason-window",
    )
}

/** A stale analysis paired with the wrong track is worse than no analysis at all. */
private fun analysisReadyForTrack(analysis: TrackAnalysis, track: TransitionTrackInfo?): Boolean {
    if (analysis.status.isBlank()) return true
    if (analysis.status != TrackAnalysis.STATUS_READY) return false
    val trackId = track?.id
    return analysis.trackId.isBlank() || trackId.isNullOrBlank() || analysis.trackId == trackId
}

/**
 * Plans the transition out of [currentTrack] and into [nextTrack].
 *
 * Called on every playback tick; the returned plan describes the transition
 * whether or not it has started yet.
 *
 * @param albumSequential true only when this is an album genuinely being
 *   played through in order, which is the sole case that earns a gapless
 *   handoff instead of a mix.
 * @param currentTime the outgoing track's playhead, in seconds.
 */
fun planTransition(
    analysis: TrackAnalysis = TrackAnalysis(),
    nextAnalysis: TrackAnalysis = TrackAnalysis(),
    currentTrack: TransitionTrackInfo? = null,
    nextTrack: TransitionTrackInfo? = null,
    currentTime: Double = 0.0,
    duration: Double = 0.0,
    fadeSeconds: Double = 6.0,
    minFadeSeconds: Double = 1.0,
    mode: CrossfadeMode = CrossfadeMode.STANDARD,
    albumSequential: Boolean = false,
    /** Legacy/test-only local hint. Production Automix passes null in server-authoritative mode. */
    styleHint: TransitionStyle? = null,
    /** Server-authored recipe. In [serverAuthoritative] mode no local musical style may replace it. */
    remoteDirective: RemoteTransitionDirective? = null,
    /** When true, local code is transport fallback only; all Automix style selection belongs to server. */
    serverAuthoritative: Boolean = false,
): TransitionPlan {
    val length = max(duration.orZero(), trackDurationSeconds(currentTrack))
    val playbackTime = max(0.0, currentTime.orZero())
    if (length <= 0) return blocked("no-duration")

    val standardFade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    if (mode != CrossfadeMode.SMART) {
        return standardTransition(length, playbackTime, standardFade, minFadeSeconds)
    }

    if (length < MIN_SMART_DURATION_SECONDS) {
        return blocked("short-duration-guard", transitionStart = length, transitionEnd = length)
    }

    val analyzedContentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: length
    val finalMixAnchor = if (analyzedContentEnd > 0 && analyzedContentEnd <= length) {
        analyzedContentEnd
    } else {
        length
    }
    val mixOutAnchor = resolveMixOutAnchor(analysis, contentEnd = finalMixAnchor, duration = length)
    val hasInteriorMixOut = mixOutAnchor.time < finalMixAnchor - 1

    if (albumSequential && sameAlbum(currentTrack, nextTrack) && !hasInteriorMixOut) {
        val transitionStart = max(0.0, length - 0.45)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = length,
            fadeSeconds = 0.12,
            transitionStyle = TransitionStyle.GAPLESS,
            reason = if (started) "same-album-gapless" else "before-gapless-window",
        )
    }

    if (BLOCKED_TEXT.containsMatchIn("${itemText(currentTrack)} ${itemText(nextTrack)}")) {
        return blocked("blocked-speech-or-live")
    }

    if (!analysisReadyForTrack(analysis, currentTrack) ||
        !analysisReadyForTrack(nextAnalysis, nextTrack)
    ) {
        if (serverAuthoritative) {
            // Automix 2.5 waits for real musical evidence. Missing/stale analysis
            // means normal playback, never an equal-power Crossfade disguised as 2.5.
            return blocked(
                "automix-2.5-waiting-analysis",
                transitionStart = finalMixAnchor,
                transitionEnd = finalMixAnchor,
            )
        }
        // Automix 2.0 preserves its historical fallback behaviour.
        return standardTransition(
            length,
            playbackTime,
            standardFade,
            minFadeSeconds,
            "smart-analysis-fallback",
        )
    }

    val preferredMixAnchor = min(length, mixOutAnchor.time)
    val mixAnchor =
        if (playbackTime >= preferredMixAnchor - 0.05 && preferredMixAnchor < finalMixAnchor - 1) {
            finalMixAnchor
        } else {
            preferredMixAnchor
        }

    // For ordinary Automix styles, never let a nominated exit land squarely
    // inside a measured foreground vocal.  INTRO_BED has its own longer vocal
    // hold below, so it still receives the original anchor.
    val safeMixAnchor = activitySafeMixAnchor(
        analysis = analysis,
        preferred = vocalSafeMixAnchor(
            analysis = analysis,
            preferred = mixAnchor,
            finalAnchor = finalMixAnchor,
        ),
        finalAnchor = finalMixAnchor,
    )

    val nextLength = max(nextAnalysis.duration.orZero(), trackDurationSeconds(nextTrack))

    // The server owns the musical choice. Local validation below is deliberately limited to
    // executable timeline/rate bounds; rejecting a malformed directive never authorizes the
    // device to pick INTRO_BED/DJ_BLEND/FOREGROUND/etc. on its own.
    remoteDirective?.let { directive ->
        validatedRemoteDirective(
            remote = directive,
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            playbackTime = playbackTime,
            finalMixAnchor = finalMixAnchor,
            nextLength = nextLength,
        )?.let { return it.copy(policyReasons = assessTransitionTier(analysis, nextAnalysis).reasons) }

        if (serverAuthoritative) {
            return blocked(
                "automix-2.5-server-plan-invalid",
                transitionStart = finalMixAnchor,
                transitionEnd = finalMixAnchor,
            )
        }
    }

    if (serverAuthoritative) {
        // Automix 2.5 never disguises a generic Crossfade as an intelligent
        // transition. If the Premium planner is late/offline, let A finish and
        // advance normally; a later tick may still receive a valid recipe.
        return blocked(
            "automix-2.5-awaiting-server-plan",
            transitionStart = finalMixAnchor,
            transitionEnd = finalMixAnchor,
        )
    }

    chooseAdaptiveSpecialTransition(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        playbackTime = playbackTime,
        mixAnchor = mixAnchor,
        safeMixAnchor = safeMixAnchor,
        finalMixAnchor = finalMixAnchor,
        nextLength = nextLength,
        styleHint = styleHint,
    )?.let { return it.copy(policyReasons = assessTransitionTier(analysis, nextAnalysis).reasons) }

    val policy = assessTransitionTier(analysis, nextAnalysis)
    if (policy.tier == TransitionTier.PLAIN_CROSSFADE) {
        val transitionStart = max(0.0, safeMixAnchor - standardFade)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = safeMixAnchor,
            fadeSeconds = safeMixAnchor - transitionStart,
            transitionStyle = TransitionStyle.EQUAL_POWER,
            incomingCueTime = incomingStartPoint(nextAnalysis),
            policyReasons = policy.reasons,
            reason = if (started) "smart-plain-crossfade" else "before-plain-crossfade-window",
        )
    }

    phraseSwitch(analysis, nextAnalysis, length, nextLength)
        ?.takeIf { playbackTime < it.transitionEnd }
        ?.let { plan ->
            val started = playbackTime >= plan.transitionStart
            return plan.copy(
                shouldStart = started,
                policyReasons = policy.reasons,
                reason = if (started) "smart-phrase-switch" else "before-phrase-switch",
            )
        }

    val (overlap, transitionBeats, incomingPlaybackRate) = adaptiveOverlap(analysis, nextAnalysis)
    val currentBpm = analysis.bpm.orZero()
    val nextBpm = nextAnalysis.bpm.orZero()
    val handoffBpm = if (currentBpm > 0) currentBpm else nextBpm
    val sameBeatBlend = currentBpm > 0 && nextBpm > 0 &&
        abs(1 - normalizedTempoRatio(currentBpm, nextBpm)) <= 0.05 &&
        (analysis.beatConfidence.orZero() >= 0.2 || nextAnalysis.beatConfidence.orZero() >= 0.2)
    val outgoingArrangementOverlap =
        if (sameBeatBlend && mixOutAnchor.type == "content_end") {
            min(ARRANGEMENT_OVERLAP_BEATS * 60 / currentBpm, MAX_DISCARDED_MUSIC_SECONDS)
        } else {
            0.0
        }
    val mixEnd = max(0.0, safeMixAnchor - outgoingArrangementOverlap)
    val maximumOverlap = minOf(
        if (handoffBpm > 0) (AUTO_TRANSITION_MAX_BEATS * 60) / handoffBpm else AUTO_TRANSITION_MAX_SECONDS,
        AUTO_TRANSITION_MAX_SECONDS,
        mixEnd * 0.4,
        if (nextLength > 0) nextLength * 0.4 else AUTO_TRANSITION_MAX_SECONDS,
    )
    val handoffBeats = if (sameBeatBlend) 8 else 4
    val beatSeconds = if (handoffBpm > 0) 60 / handoffBpm else 0.5
    val handoffSeconds = if (handoffBpm > 0) {
        clamp((handoffBeats * 60) / handoffBpm, 2.0, if (sameBeatBlend) 6.0 else 5.0)
    } else {
        4.0
    }
    val analyzedPickup = nextAnalysis.audibleStartTime ?: nextAnalysis.pickupTime
    val pickupSeconds = if (analyzedPickup != null && analyzedPickup.isFinite() && analyzedPickup >= 0) {
        analyzedPickup
    } else {
        0.0
    }
    val incomingDropTime = incomingCuePoint(nextAnalysis)
    val alignedIncomingBpm = alignTempoOctave(currentBpm, nextBpm)
    val requestedIncomingHandoff =
        if (sameBeatBlend && alignedIncomingBpm > 0) {
            incomingDropTime + ARRANGEMENT_OVERLAP_BEATS * 60 / alignedIncomingBpm
        } else {
            incomingDropTime
        }
    val maxIncomingHandoff = nextLength - MIN_INCOMING_CLEARANCE_SECONDS
    val incomingHandoffTime =
        if (maxIncomingHandoff >= incomingDropTime) {
            min(requestedIncomingHandoff, maxIncomingHandoff)
        } else {
            incomingDropTime
        }
    val rawIncomingCueTime = incomingStartPoint(nextAnalysis)
    val analyzedIncomingHandoff = nextAnalysis.mixInTime
    val hasIncomingPreroll = analyzedIncomingHandoff.isFinite() &&
        analyzedIncomingHandoff > rawIncomingCueTime + 0.5
    val incomingCueTime = if (hasIncomingPreroll) rawIncomingCueTime else incomingHandoffTime
    val introPreroll = max(
        0.0,
        (if (hasIncomingPreroll) incomingHandoffTime - incomingCueTime else 0.0) /
            max(0.8, incomingPlaybackRate),
    )

    val finalIncomingCueTime: Double
    val transitionStart: Double

    if (sameBeatBlend && beatSeconds > 0) {
        val introDropTime = incomingHandoffTime / max(0.8, incomingPlaybackRate)
        val totalOverlap = clamp(introDropTime, min(12.0, maximumOverlap), maximumOverlap)
        val targetStart = max(0.0, mixEnd - totalOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = alignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = true,
            minimum = earliestTransitionStart,
        )
        finalIncomingCueTime =
            max(0.0, incomingHandoffTime - (mixEnd - transitionStart) * incomingPlaybackRate)
    } else {
        val desiredOverlap = max(overlap, introPreroll + handoffSeconds * 0.42)
        val actualOverlap = clamp(desiredOverlap, min(handoffSeconds, maximumOverlap), maximumOverlap)
        val targetStart = max(0.0, mixEnd - actualOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = alignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = desiredOverlap > overlap + 0.5,
            minimum = earliestTransitionStart,
        )
        finalIncomingCueTime = if (hasIncomingPreroll) {
            max(0.0, incomingHandoffTime - (mixEnd - transitionStart) * incomingPlaybackRate)
        } else {
            incomingCueTime
        }
    }

    val alignedOverlap = mixEnd - transitionStart
    val hasBassContent = analysis.lowEnergyCurve.isNotEmpty() || nextAnalysis.lowEnergyCurve.isNotEmpty()
    // A server hint can choose a less ambitious treatment, but it cannot manufacture a shared
    // beat grid. DJ_BLEND is therefore accepted only when the local evidence already proves the
    // pair safe to beat-match; otherwise it is downgraded to DJ_FILTER.
    val plannedStyle = when (styleHint) {
        TransitionStyle.DJ_BLEND ->
            if (sameBeatBlend) TransitionStyle.DJ_BLEND else TransitionStyle.DJ_FILTER
        TransitionStyle.EQ_SWAP ->
            if (sameBeatBlend &&
                analysis.lowEnergyCurve.isNotEmpty() &&
                nextAnalysis.lowEnergyCurve.isNotEmpty()
            ) TransitionStyle.EQ_SWAP
            else if (sameBeatBlend) TransitionStyle.DJ_BLEND
            else TransitionStyle.DJ_FILTER
        TransitionStyle.DJ_FILTER -> TransitionStyle.DJ_FILTER
        // Dedicated styles are consumed above. Reaching here means their local
        // safety proof refused them, so degrade to an ordinary analyzed plan.
        TransitionStyle.INTRO_BED,
        TransitionStyle.INTRO_BRIDGE_FILTER,
        TransitionStyle.RUNWAY_BLEND,
        TransitionStyle.FOREGROUND_TAKEOVER,
        TransitionStyle.PHRASE_TAKEOVER,
        TransitionStyle.PHRASE_CUT,
        TransitionStyle.CUT ->
            if (sameBeatBlend) TransitionStyle.DJ_BLEND else TransitionStyle.DJ_FILTER
        else -> if (sameBeatBlend) TransitionStyle.DJ_BLEND else TransitionStyle.DJ_FILTER
    }
    val safeIncomingPlaybackRate =
        if (plannedStyle == TransitionStyle.DJ_BLEND || plannedStyle == TransitionStyle.EQ_SWAP) {
            incomingPlaybackRate
        } else {
            1.0
        }
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = mixEnd,
        fadeSeconds = alignedOverlap,
        handoffStartSeconds = 0.0,
        handoffDuration = alignedOverlap,
        incomingCueTime = finalIncomingCueTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingPlaybackRate = safeIncomingPlaybackRate,
        pickupSeconds = pickupSeconds,
        transitionBeats = transitionBeats,
        bassSwap = plannedStyle == TransitionStyle.DJ_BLEND ||
            plannedStyle == TransitionStyle.EQ_SWAP ||
            hasBassContent,
        transitionStyle = plannedStyle,
        // The two styles are alternatives, not a scale: a matched pair hands the
        // low end over on a beat and otherwise stays open, while an unmatched
        // pair has no shared grid to hand anything over on and instead pulls the
        // outgoing track behind a closing low-pass. Left at zero on the blend
        // branch so the renderer doesn't do both at once.
        filterSweep = if (
            plannedStyle == TransitionStyle.DJ_BLEND ||
            plannedStyle == TransitionStyle.EQ_SWAP
        ) 0.0 else FILTER_SWEEP,
        vocalOverlap = plannedVocalOverlap(
            analysis = analysis,
            nextAnalysis = nextAnalysis,
            transitionStart = transitionStart,
            transitionEnd = mixEnd,
            incomingCueTime = finalIncomingCueTime,
            incomingPlaybackRate = safeIncomingPlaybackRate,
        ),
        policyReasons = policy.reasons,
        reason = if (started) {
            if (styleHint != null) "remote-style-${plannedStyle.name.lowercase()}" else "smart-duration"
        } else {
            if (styleHint != null) "before-remote-style-${plannedStyle.name.lowercase()}" else "before-smart-duration"
        },
    )
}