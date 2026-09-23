package com.music.orb.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.orb.data.NerdStats
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AutomixVisualTransition
import com.music.orb.data.settings.AutomixVersion
import com.music.orb.data.settings.SmartAnalysis
import com.music.orb.data.settings.TrackAnalysisState
import com.music.orb.data.settings.TransitionWindow
import com.music.orb.playback.smart.CrossfadeMode
import com.music.orb.playback.smart.RemoteAutomixClient
import com.music.orb.playback.smart.RemoteTransitionDirective
import com.music.orb.playback.smart.PreparedTransitionStems
import com.music.orb.playback.smart.TrackAnalysis
import com.music.orb.playback.smart.TransitionTempoPoint
import com.music.orb.playback.smart.TransitionGainPoint
import com.music.orb.playback.smart.TransitionStyle
import com.music.orb.playback.smart.TransitionLoopTarget
import com.music.orb.playback.smart.TransitionTrackInfo
import com.music.orb.playback.smart.planTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * A real crossfade: two tracks audible at once, the outgoing one falling as the
 * incoming one rises, the way Spotify and Apple Music do it.
 *
 * ## Why there are two main players
 *
 * One ExoPlayer renders one queue item at a time, so at a track boundary there
 * is exactly one source and the gain it can be given is either 1 (no fade) or 0
 * (silence). The previous version of this class was a single-player volume
 * ramp, and that is precisely why it never sounded like a crossfade: it dipped
 * to silence at the join and climbed back out, leaving a hole where the blend
 * should be. Overlap needs a second decoder. There is no way around it.
 *
 * ## Which player plays what
 *
 * Two peers, not a player and a helper. Both are full ExoPlayers built the same
 * way and both can own the queue; at any instant one of them *is* the session
 * (it backs the MediaSession, holds audio focus and carries the notification)
 * and the other is idle. They swap roles at every transition.
 *
 *  - **[active]** — whichever player the session currently points at. The rest
 *    of the app only ever sees this one.
 *  - **[standby]** — the idle player. Between transitions it holds nothing. To
 *    arm a transition it is loaded with *the queue, positioned on the incoming
 *    track* at the plan's cue point, and started silently.
 *
 * The crucial word is **incoming**. An earlier version of this class put the
 * *outgoing* track on the second player: the session player jumped ahead to the
 * next song and the second player carried the old song's tail. That works, but
 * it forces a moment where both players render *the same audio*, and two
 * ExoPlayers cannot be started sample-accurately against each other. Whatever
 * they were misaligned by — measured on real transitions at 9 to 41ms — was
 * heard as the last instant of the outgoing track playing twice, at the head of
 * every single crossfade. No amount of tuning removes that; the duplication is
 * structural.
 *
 * Loading the *incoming* track on the standby removes it outright. The two
 * players never hold the same audio, so there is nothing to align, nothing to
 * hand over, and no seam to hide. The incoming track is simply already playing,
 * from exactly the right position, when its fader starts to move.
 *
 * ## The handoff
 *
 * Because both players own the queue, finishing a transition is a **role swap**
 * rather than a seek: nothing is re-buffered, nothing is re-sought, and no audio
 * is rendered twice. [onHandoff] is what performs it — the service moves the
 * MediaSession, audio focus, its listeners and its bookkeeping onto the incoming
 * player.
 *
 * It fires when the incoming deck becomes the perceptual foreground, not at its
 * first quiet note. This matters for long Instrumental Overlay transitions: B may
 * sit under A for tens of seconds while A is still clearly the song the listener
 * hears. The queue index, metadata, miniplayer and system notification therefore
 * remain on A until B's rendered gain actually takes authority. From that instant
 * [outgoing] is the idle player, still audible, being faded out — which is exactly
 * what the previous design used its tail player for, at none of the cost.
 *
 * ## Curve
 *
 * `sin`/`cos` rather than the old `sqrt`: `sin²+cos²=1` exactly, so two tracks
 * fading past each other hold constant *power* the whole way through and the
 * transition has no dip in the middle. That is the standard crossfade law, and
 * it is what makes a long crossfade sound like a blend instead of a dip.
 */
@UnstableApi
class CrossfadeController(
    private val scope: CoroutineScope,
    /** The player backing the session right now. Moves at every [onHandoff]. */
    private val active: () -> ExoPlayer,
    /** The idle player, which the next transition will load the incoming track onto. */
    private val standby: () -> ExoPlayer,
    /**
     * Moves the session onto the player that has just started the incoming
     * track: the MediaSession's player, audio focus, the service's listeners and
     * everything it books against a track change.
     *
     * Called once per transition, at the instant the incoming track becomes
     * audible. After it returns, [active] must answer `incoming` and [standby]
     * must answer `outgoing` — this class re-reads neither during a transition,
     * but everything else in the service does.
     */
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
    /**
     * Stored Automix analysis for a media item, or an empty [TrackAnalysis]
     * when there is none yet. This is the seam Phase 1's DSP analyzer plugs
     * into: until analysis finishes, a track reads as "no evidence", which
     * [planTransition] answers with the same fixed-length crossfade this
     * class always ran before Automix existed.
     */
    private val analysisFor: (MediaItem) -> TrackAnalysis = { TrackAnalysis() },
    /**
     * Queues background analysis for a media item that will soon need it.
     * Cheap to call on every tick: a track already analysed, already in
     * flight, or not yet fully cached is a no-op.
     *
     * Takes the item's duration in milliseconds, or 0 when Media3 hasn't loaded
     * that far ahead yet. The analyzer needs it to tell one rendition of a
     * recording from a differently-cut one before reusing an analysis across
     * them, and this class is the only place that already knows it.
     */
    private val requestAnalysis: (MediaItem, Long) -> Unit = { _, _ -> },
    /**
     * The low-pass and high-pass riding each side of a transition. This is what
     * makes a plan's
     * [com.music.orb.playback.smart.TransitionPlan.transitionStyle] audible
     * rather than advisory: see [rideFilters]. Defaults to
     * [TransitionFilters.None], which renders every style as the plain
     * equal-power blend this class ran before.
     */
    private val filters: TransitionFilters = TransitionFilters.None,
    /** Starts background Open-Unmix reconstruction for B's transition window. */
    private val requestIncomingStems: (MediaItem, startMs: Long, endMs: Long) -> Unit = { _, _, _ -> },
    /** Prepared real stems for B, if reconstruction completed before the mix opens. */
    private val stemsFor: (MediaItem, positionMs: Long) -> PreparedTransitionStems? = { _, _ -> null },
    /** Third transition-only deck that renders B's accompaniment stem. */
    private val stemDeck: () -> TransitionStemDeck? = { null },
    /**
     * Whether a decode and inference for a media item is running right now.
     * Only feeds the stats line — nothing about a transition waits on it.
     */
    private val analysisRunningFor: (MediaItem) -> Boolean = { false },
    /** True only when the analysis is final enough for the 2.5 remote planner. */
    private val analysisReadyForPlan: (MediaItem) -> Boolean = { true },
    /** True only when B has a validated route selected for this A→B pair. */
    private val incomingAudioReadyFor: (MediaItem) -> Boolean = { true },
) {

    private val overlayAudibility = OverlayAudibility()
    private var levelTickAtMs = 0L
    private var unusableStemSinceMs = 0L
    private var lastOverlayTraceMs = 0L

    private enum class Phase {
        /** Nothing in flight; watching for the next transition. */
        IDLE,

        /**
         * The standby player is loading the incoming track and buffering to its
         * cue point. Silent, and nothing has been committed: abandoning here
         * costs only the standby's decoder.
         */
        ARMING,

        /** Incoming track rising on one player, outgoing falling on the other. */
        FADING,

        /** Something interrupted the fade; the outgoing track is being ramped away. */
        BAILING,
    }

    private var phase = Phase.IDLE

    /**
     * The player the session was on when this transition began — the one whose
     * track is being left. Held explicitly rather than re-read through
     * [standby], because [onHandoff] moves it out from under that name halfway
     * through the fade and the ramp has to keep driving the same two players it
     * started with.
     */
    private var outgoing: ExoPlayer? = null

    /** The player carrying the track arriving. Becomes the session at [onHandoff]. */
    private var incoming: ExoPlayer? = null

    /**
     * Whether [onHandoff] has run for the transition in flight, which is what
     * decides who owns what if it has to be unwound: before it, [outgoing] is
     * the session and [incoming] is a silent scratch player; after it, they have
     * traded places.
     */
    private var handedOff = false

    /**
     * The session deck normally auto-advances through its queue. During an audible two-deck
     * overlap that would be catastrophic: A reaching its natural end could advance the outgoing
     * player into B at 0:00 while the prepared incoming deck is already part-way through B.
     * Media3 has a purpose-built guard for this: pause at the end of the current item. We remember
     * the listener's previous setting and restore it if the transition is abandoned before handoff.
     */
    private var outgoingPauseAtEndBeforeTransition = false

    /**
     * How many items the queue held when the standby was loaded with a copy of
     * it. AutoPlay appending mid-transition is explicitly allowed, so the
     * difference is reconciled onto the standby before the swap rather than
     * being allowed to lose the appended tracks — see [reconcileQueue].
     */
    private var queuedItemCount = 0

    /** Which player this class's own listener is currently attached to. */
    private var listeningTo: ExoPlayer? = null

    /** Length of the transition in flight, in ms. Fixed when it begins. */
    private var fadeMs = 0L

    /**
     * Where the fade window ends, in the session player's position ms.
     * Standard mode sets this to the track's own duration, which is what
     * [driveArming] always compared against before Automix existed; a
     * Automix plan can set it earlier, at an analyzed mix-out anchor, so
     * [driveArming] watches this field rather than re-deriving the fade point
     * from [ExoPlayer.getDuration] on every tick.
     */
    private var fadeEndMs = 0L

    /**
     * Which setting armed the fade in flight, so [driveFade] knows which one
     * being switched off mid-blend means "stop now" rather than misreading the
     * other mode's control as the fade having been turned off. Automix
     * doesn't need [AppSettings.crossfadeSeconds] to be above zero at all —
     * see [considerSmartTransition] — so treating that as still-zero as a
     * reason to cut a Automix short would end every one of them on its
     * first tick.
     */
    private var smartFadeActive = false

    /**
     * Where the incoming track is cued when the lap hands the queue over, in
     * its own timeline ms. Standard fades always leave this at 0 — a plain
     * track change starts from the top — and only a Automix plan sets it
     * to an analyzed mix-in point instead.
     */
    private var incomingCueTimeMs: Long = 0L

    /**
     * The tempo-stretch ratio applied to the incoming track for the
     * transition, stacked on top of whatever [AppSettings.playbackSpeed] the
     * listener already has set — 1.0 is a no-op. This is what actually
     * beatmatches a BEATMATCHED-tier plan: without it, the two tracks blend
     * at their own unrelated tempi and the result is a crossfade with
     * smarter timing, not a beatmatch.
     */
    private var incomingPlaybackRate: Double = 1.0

    /** Temporary tempo correction for the outgoing deck during DJ-style overlays. */
    private var outgoingPlaybackRate: Double = 1.0

    /** Explicit media-timeline start. Needed because loop/rate plans have wall time != media span. */
    private var fadeStartMs = 0L

    /** Pause-aware wall clock for INTRO_BED/INTRO_BRIDGE_FILTER, whose B playhead may loop backwards. */
    private var mixElapsedMs = 0L
    private var mixClockAtMs = 0L

    /** Optional beat-quantized loop on B's instrumental intro. */
    private var loopTarget: TransitionLoopTarget = TransitionLoopTarget.NONE
    private var loopStartMs = 0L
    private var loopEndMs = 0L
    private var loopRepeatsRemaining = 0
    private var loopRepeatsPlanned = 0
    private var loopSeekBlockedUntilMs = 0L

    /** Real stem overlay is opportunistic: missing/late separation falls back to the full B deck. */
    private var realStemWanted = false
    private var realStemActive = false
    private var realStemReleaseAligned = false

    /**
     * The style-specific half of the plan in flight — everything [rideFilters]
     * needs and nothing else. Fixed when the transition begins, because a plan
     * is recomputed every tick and a bass swap that moved to a different beat
     * halfway through the blend would be heard as the low end flapping.
     */
    private val TEMPO_RELEASE_MIN = 0.66f
    private val PHASE_LOCK_CAPTURE_MS = 1_800.0
    private val PHASE_LOCK_MAX_DELTA = 0.012
    private val PHASE_LOCK_DEADBAND_MS = 3.0
    private val PHASE_LOCK_MIN_RATE = 0.95
    private val PHASE_LOCK_MAX_RATE = 1.05
    private val PHASE_LOCK_LOG_PROGRESS = 0.08f

    private var phaseLockPeakErrorMs = 0.0
    private var phaseLockLogged = false
    private var render = Render()

    /**
     * The style fields of a [com.music.orb.playback.smart.TransitionPlan],
     * separated out so the standard (non-Smart) path can pass defaults without
     * constructing a plan it never made.
     */
    private data class Render(
        val style: TransitionStyle = TransitionStyle.EQUAL_POWER,
        val bassSwap: Boolean = false,
        val bassSwapFraction: Double = 0.7,
        val filterSweep: Double = 0.0,
        val vocalOverlap: Double = 0.0,
        val gainEnvelope: List<TransitionGainPoint> = emptyList(),
        val tempoEnvelope: List<TransitionTempoPoint> = emptyList(),
        val incomingPitchSemitones: Double = 0.0,
        /** Dynamic release point for INTRO_BED after A's last measured lead vocal. */
        val handoffFraction: Double = 0.66,
        /** True only for a server-authored Premium Automix 2.5 recipe. */
        val automix25: Boolean = false,
    )

    private var fadeStartedAt = 0L
    private var bailStartedAt = 0L
    private var armDeadline = 0L

    /**
     * When the last transition finished, from [SystemClock.elapsedRealtime], or
     * zero while none has this session.
     *
     * Read through [msSinceTransition] by callers that have to stay off the
     * session player for a moment *after* a blend as well as during one.
     */
    private var settledAt = 0L

    /**
     * Gain the outgoing track was at when the fade was interrupted, so the ramp
     * out starts from where it actually is rather than from full volume.
     */
    private var bailFromGain = 0f

    /** Dedupes the per-tick plan log down to one line per distinct verdict. */
    private var lastPlanVerdict = ""

    /** One full remote recipe per immutable A -> B pair. Local validation remains final. */
    private val remotePlanByPair = ConcurrentHashMap<String, RemoteTransitionDirective>()
    private val remotePlanAttempted = ConcurrentHashMap.newKeySet<String>()

    /**
     * Analysis itself is deduped by TrackAnalyzer, but asking it to rescan cache state four times a
     * second still burns CPU and lock traffic. Keep planning at 250 ms while probing analysis bytes
     * at a much slower cadence; a pair change bypasses the throttle immediately.
     */
    private var lastAnalysisRequestAt = 0L
    private var lastAnalysisRequestPair = ""

    /**
     * True while a transition is armed or running.
     *
     * For callers about to do something that would otherwise fight this class
     * for the session player mid-blend — [PlaybackService]'s quality upgrade is
     * the one that does, since `replaceMediaItem` tears the current source down
     * and rebuilds it. Doing that to either player mid-transition breaks the
     * blend rather than merely delaying it, so such a caller should wait for
     * this to clear rather than proceed anyway.
     */
    fun isTransitioning(): Boolean = phase != Phase.IDLE

    /**
     * How long since the last transition finished, or null while none has.
     *
     * For the same caller as [isTransitioning], which needs a little more than
     * that flag can give it. The flag clears on the tick the blend completes,
     * so a source torn down and rebuilt the moment it clears puts its break in
     * the audio a few hundred milliseconds after the incoming track finally
     * stood alone — not a broken blend, but heard as one. A caller that wants
     * the transition to have been *over* for a while, rather than merely to
     * have ended, waits this out too.
     *
     * Says nothing about a transition still in flight — it reports whatever the
     * one before it left behind — so [isTransitioning] stays the first question
     * to ask.
     */
    fun msSinceTransition(): Long? =
        settledAt.takeIf { it != 0L }?.let { SystemClock.elapsedRealtime() - it }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // The listener moving the playhead is something no half-finished
            // crossfade should survive. Nothing this class does registers here
            // any more: the handoff is a role swap, not a seek.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) bail()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                // Something replaced the queue out from under the fade — a new
                // album, a new search result — so the tail still playing is a
                // leftover of a session that no longer exists. Note that this
                // does *not* fire when AutoPlay appends to the end, since the
                // playing item doesn't change: extending the queue mid-fade is
                // harmless and shouldn't cost the listener the blend.
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> bail()
            }
        }

        override fun onPlayerError(error: PlaybackException) = bail()
    }

    /**
     * Keeps [listener] on whichever player is the session.
     *
     * It has to move rather than sit on both: arming loads a whole queue onto
     * the standby, which Media3 reports as the playlist changing, and a listener
     * attached there would read that as the queue being replaced out from under
     * the very transition it is setting up.
     */
    private fun listenTo(target: ExoPlayer) {
        if (listeningTo === target) return
        listeningTo?.removeListener(listener)
        target.addListener(listener)
        listeningTo = target
    }

    fun start() {
        listenTo(active())
        scope.launch {
            while (isActive) {
                tick()
                delay(
                    when (phase) {
                        Phase.IDLE -> IDLE_STEP_MS
                        Phase.ARMING -> ARM_STEP_MS
                        Phase.FADING -> FADE_STEP_MS
                        Phase.BAILING -> BAIL_STEP_MS
                    },
                )
            }
        }
    }


    /**
     * Analysis completion is an event, not something the 250 ms idle heartbeat should
     * have to discover. PlaybackService invokes this on Main as soon as A/B evidence
     * settles so /plan starts immediately and maximizes the remaining runway in A.
     */
    fun onAnalysisUpdated(trackId: String) {
        if (phase != Phase.IDLE) return
        val live = active()
        if (!live.isPlaying) return
        val current = live.currentMediaItem ?: return
        val nextIndex = live.nextMediaItemIndex
        val next = if (nextIndex != C.INDEX_UNSET && nextIndex in 0 until live.mediaItemCount) {
            live.getMediaItemAt(nextIndex)
        } else {
            null
        }
        if (trackId != current.mediaId && trackId != next?.mediaId) return
        considerAutoTransition()
    }

    /** Move the controller listener after PlaybackService replaces both ExoPlayers. */
    fun onPlayersRebuilt() {
        if (phase != Phase.IDLE) return
        listenTo(active())
    }

    fun release() {
        listeningTo?.removeListener(listener)
        listeningTo = null
        active().volume = 1f
        AppSettings.smartMixInProgress.value = false
        AppSettings.automixVisualTransition.value = null
        filters.open()
    }

    // ---- Entry points -------------------------------------------------------

    /**
     * A skip the listener asked for: drop any blend in flight and get out of
     * the way.
     *
     * Crossfade is deliberately a property of tracks *running out*, not of
     * being changed. Blending a manual skip means the song just left behind
     * stays audible over the one that was asked for, which reads as the app
     * ignoring the button rather than as a transition — the point of pressing
     * next is usually to stop hearing the current track.
     *
     * Called before the skip is carried out, so the outgoing track is already on
     * its way down as the new one starts, and the listener's own seek lands on a
     * player this class has finished with.
     */
    fun onSkipRequested() {
        if (phase != Phase.IDLE) bail()
    }

    /**
     * A complete queue dismissal is stronger than an ordinary skip. Tear down
     * the non-session deck immediately so a MiniPlayer swipe can never leave an
     * Automix tail audible after the visible queue has already disappeared.
     * Mark the phase as BAILING first so [finish] does not count this user abort
     * as a successfully completed smart transition.
     */
    fun onQueueCleared() {
        if (phase == Phase.IDLE) return
        phase = Phase.BAILING
        finish()
    }

    // ---- Ticker -------------------------------------------------------------

    private fun tick() {
        // A pause has to take the other player with it, or one half of the blend
        // carries on alone over a stopped one. Mirrored every tick rather than
        // handled as an event, so audio focus loss, the sleep timer and the
        // pause button all get the same treatment for free. Which player follows
        // which flips at the handoff: before it the standby shadows the session,
        // after it the outgoing tail does.
        if (phase == Phase.FADING || phase == Phase.BAILING) {
            if (handedOff) {
                // After the session swap B owns transport, so A follows it.
                outgoing?.playWhenReady = incoming?.playWhenReady ?: true
            } else {
                // During the short stability check A still owns transport. Normally B follows A so
                // a user pause never lets the scratch player run on. The one exception is the
                // temporary pause-at-end guard applied to A during an audible overlap: that pause
                // is an implementation detail, not user intent, and must never pause B at the exact
                // handoff boundary.
                val out = outgoing
                incoming?.playWhenReady = when {
                    out == null -> true
                    isInstrumentalOverlayStyle() && hasReachedNaturalEnd(out) -> true
                    else -> out.playWhenReady
                }
            }
        }

        // Every tick, not only when a transition can be planned. This used to
        // live inside [considerSmartTransition], which needs an idle phase, a
        // playing player and a known duration — none of which hold during a
        // transition or during the re-buffer after a quality upgrade. The line
        // simply froze on the previous pair, so a track that had not been
        // analysed kept showing the *departing* track's "analysed" until
        // ticking resumed.
        publishAnalysisState()

        when (phase) {
            Phase.IDLE -> considerAutoTransition()
            Phase.ARMING -> driveArming()
            Phase.FADING -> driveFade()
            Phase.BAILING -> driveBail()
        }
    }

    /** Arms a crossfade as the playing track runs out. */
    private fun considerAutoTransition() {
        val player = active()
        if (!player.isPlaying) return
        // Repeating one track would crossfade it into itself.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return
        // Nothing to transition *into*, so any analysis state left over from the
        // previous pair is stale — the last track of a queue should not still be
        // claiming both songs are measured.
        if (!player.hasNextMediaItem()) {
            AppSettings.smartTransitionWindow.value = null
            return
        }

        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return

        // Automix is its own on/off, independent of the manual crossfade
        // length: it decides its own duration from each pair of tracks (beats,
        // tempo, structure), so requiring a nonzero [AppSettings.crossfadeSeconds]
        // first would tie an automatic feature to a manual one it doesn't use.
        if (AppSettings.smartFadeEnabled.value) {
            considerSmartTransition(duration)
            return
        }

        if (configuredFadeMs() <= 0L) return
        val fade = fadeFor(duration)
        if (fade <= 0L) return

        val remaining = duration - player.currentPosition
        // Arm early: the standby has to open the incoming track and buffer to
        // its cue point, and that work has to be finished by the time the fade
        // is due rather than started then.
        if (remaining > fade + STANDARD_ARM_LEAD_MS) return

        begin(fade, startMs = (duration - fade).coerceAtLeast(0L), endMs = duration, smart = false)
    }

    /**
     * Arms a Automix transition once its plan says the playhead is close
     * enough to start arming for it.
     *
     * Reads the plan's timing (where the fade starts and how long it runs),
     * where the incoming track should be cued
     * ([com.music.orb.playback.smart.TransitionPlan.incomingCueTime]),
     * and the tempo-stretch to align it with the outgoing track
     * ([com.music.orb.playback.smart.TransitionPlan.incomingPlaybackRate])
     * — see [driveLap], which applies both at the handoff — and the style the
     * blend is rendered in
     * ([com.music.orb.playback.smart.TransitionPlan.transitionStyle]),
     * which [rideFilters] turns into a filter ride or a bass swap over the same
     * equal-power gain curve.
     */
    private fun considerSmartTransition(duration: Long) {
        val player = active()
        val currentItem = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextItem = player.getMediaItemAt(nextIndex)
        val nextDuration = nextItemDurationMs(nextIndex, nextItem)

        // An album launched in its original, non-shuffled sequence must preserve the
        // authored running order. Automix does not arm, analyze or overlap adjacent
        // album tracks in this mode; Media3 advances them normally/gaplessly.
        if (currentItem.albumSequential && !QueueShuffle.enabled.value) {
            AppSettings.smartTransitionWindow.value = null
            val verdict = "album-original-order-no-automix"
            if (verdict != lastPlanVerdict) {
                lastPlanVerdict = verdict
                Log.d(TAG, "plan ${currentItem.mediaId}->${nextItem.mediaId}: $verdict")
            }
            return
        }

        // TrackAnalyzer already refuses duplicate inference, but a 250 ms controller tick used to
        // make it re-check cache/rendition state four times a second anyway. Probe the pair at a
        // bounded cadence and bypass the throttle whenever the actual A -> B pair changes.
        val analysisPair = "${currentItem.mediaId}->${nextItem.mediaId}"
        val analysisNow = SystemClock.elapsedRealtime()
        if (analysisPair != lastAnalysisRequestPair ||
            analysisNow - lastAnalysisRequestAt >= ANALYSIS_REQUEST_INTERVAL_MS
        ) {
            lastAnalysisRequestPair = analysisPair
            lastAnalysisRequestAt = analysisNow
            requestAnalysis(currentItem, duration)
            requestAnalysis(nextItem, nextDuration)
        }

        // Only used before analysis lands, or when the evidence is too weak
        // for more than a plain fade (see [TransitionTier.PLAIN_CROSSFADE]):
        // once real analysis is available, [planTransition] sizes the overlap
        // itself from tempo and structure and ignores this entirely. Honours
        // the manual slider if the listener also set one, so the two settings
        // don't fight; falls back to a fixed length when it's at "Off".
        val fallbackSeconds = configuredFadeMs().takeIf { it > 0L }
            ?.div(1000.0)
            ?.coerceAtLeast(MIN_SMART_FALLBACK_SECONDS)
            ?: DEFAULT_SMART_FALLBACK_SECONDS

        // Resolved once and reused: [analysisFor] was being called five separate
        // times per tick below, and the answer cannot change mid-tick.
        val currentAnalysis = analysisFor(currentItem)
        val nextAnalysis = analysisFor(nextItem)
        val analysisState = AppSettings.smartAnalysis.value
        val useAutomix25 =
            AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
                AppSettings.automix25Available.value

        // Planning and transport preparation are deliberately decoupled in 2.5.
        // The server only needs musical evidence. Waiting for B's quality preflight used to
        // consume most of A's runway and made otherwise valid recipes arrive after their beat.
        // The standby player still proves the actual route/buffer before B becomes audible.
        val currentReadyForPlan = !useAutomix25 || analysisReadyForPlan(currentItem)
        val nextReadyForPlan = !useAutomix25 || analysisReadyForPlan(nextItem)

        if (useAutomix25 &&
            currentReadyForPlan && nextReadyForPlan &&
            !remotePlanByPair.containsKey(analysisPair) &&
            remotePlanAttempted.add(analysisPair)
        ) {
            scope.launch(Dispatchers.IO) {
                // Do not depend on a startup probe that may have gone stale.
                // A+B being schema-4 ready is the strongest signal that planning
                // should happen now; actively re-probe once before giving up.
                val backendReady =
                    RemoteAutomixClient.isAvailable() || RemoteAutomixClient.probe()
                if (!backendReady) {
                    delay(REMOTE_PLAN_RETRY_MS)
                    remotePlanAttempted.remove(analysisPair)
                    return@launch
                }

                val directive = RemoteAutomixClient.requestPlan(currentAnalysis, nextAnalysis)
                if (directive != null) {
                    if (remotePlanByPair.size >= REMOTE_STYLE_CACHE_LIMIT) {
                        remotePlanByPair.clear()
                        remotePlanAttempted.clear()
                        remotePlanAttempted.add(analysisPair)
                    }
                    remotePlanByPair[analysisPair] = directive
                } else {
                    delay(REMOTE_PLAN_RETRY_MS)
                    remotePlanAttempted.remove(analysisPair)
                }
            }
        }
        val remotePlan = if (useAutomix25) remotePlanByPair[analysisPair] else null

        val plan = planTransition(
            analysis = currentAnalysis,
            nextAnalysis = nextAnalysis,
            currentTrack = currentItem.toTransitionInfo(duration),
            nextTrack = nextItem.toTransitionInfo(nextDuration),
            currentTime = player.currentPosition / 1000.0,
            duration = duration / 1000.0,
            fadeSeconds = fallbackSeconds,
            mode = CrossfadeMode.SMART,
            styleHint = null,
            remoteDirective = remotePlan,
            // Automix 2.0 keeps the current local engine from this src. Only
            // the entitled 2.5 preview delegates musical-family selection to
            // the new remote planner.
            serverAuthoritative = useAutomix25,
        )
        // A blocked 2.5 plan means the recipe is still unavailable/invalid,
        // not that the musical decision is "no transition". Normal 2.5 playback
        // never reports NO_TRANSITION; album-original-order is handled above.
        val reportedStyle = if (useAutomix25 && plan.blocked) "PENDING" else plan.transitionStyle.name

        // One line per distinct verdict rather than one per 250ms tick, so the
        // log says what the planner decided for this pair without burying it.
        val verdict = "${if (useAutomix25) "2.5" else "2.0"}|${plan.reason}|$reportedStyle|fade=${plan.fadeMs}" +
            "|cue=${plan.incomingCueTime}|rates=${plan.outgoingPlaybackRate}/${plan.incomingPlaybackRate}" +
            "|loop=${plan.loopTarget}:${plan.loopStartTime}-${plan.loopEndTime}x${plan.loopRepeats}" +
            "|handoff=${"%.2f".format(plan.handoffFraction)}" +
            "|vocalOverlap=${"%.2f".format(plan.vocalOverlap)}" +
            "|blocked=${plan.blocked}|policy=${plan.policyReasons.joinToString(",")}"
        if (verdict != lastPlanVerdict) {
            lastPlanVerdict = verdict
            Log.d(
                TAG,
                "plan ${currentItem.mediaId}->${nextItem.mediaId}: $verdict " +
                    "bpm=${currentAnalysis.bpm}/${nextAnalysis.bpm} " +
                    "conf=${currentAnalysis.beatConfidence}/${nextAnalysis.beatConfidence}",
            )
        }

        // Real source separation is used only when the measured overlap says A and B would
        // actually sing over each other. A genuinely instrumental intro stays on the pristine
        // playback rendition instead of paying an unnecessary Open-Unmix reconstruction cost.
        val wantsRealStems = isInstrumentalOverlayPlan(plan.transitionStyle) &&
            plan.vocalOverlap >= REAL_STEM_VOCAL_OVERLAP_TRIGGER
        if (wantsRealStems) {
            val cueMs = (plan.incomingCueTime * 1000.0).roundToLong().coerceAtLeast(0L)
            val structuralEndMs = (maxOf(
                plan.incomingHandoffTime + REAL_STEM_HANDOFF_TAIL_SECONDS,
                plan.loopEndTime + REAL_STEM_LOOP_TAIL_SECONDS,
                plan.incomingCueTime + REAL_STEM_MIN_WINDOW_SECONDS,
            ) * 1000.0).roundToLong()
            requestIncomingStems(nextItem, cueMs, structuralEndMs)
        }

        // Detailed observation for Stats for Nerds. This does not feed back into
        // planning or rendering; it only mirrors the exact plan chosen above.
        NerdStats.beginAutomixPair(
            outgoingId = currentItem.mediaId,
            incomingId = nextItem.mediaId,
            outgoingTitle = currentItem.mediaMetadata.title?.toString().orEmpty(),
            incomingTitle = nextItem.mediaMetadata.title?.toString().orEmpty(),
        )
        NerdStats.onAutomixPlan(
            outgoingId = currentItem.mediaId,
            incomingId = nextItem.mediaId,
            style = reportedStyle,
            outgoingBpm = currentAnalysis.bpm.takeIf { it > 0.0 }?.toFloat(),
            incomingBpm = nextAnalysis.bpm.takeIf { it > 0.0 }?.toFloat(),
            outgoingKey = currentAnalysis.key.takeIf(String::isNotBlank),
            incomingKey = nextAnalysis.key.takeIf(String::isNotBlank),
            outgoingBeatConfidence = currentAnalysis.beatConfidence.takeIf { it > 0.0 }?.toFloat(),
            incomingBeatConfidence = nextAnalysis.beatConfidence.takeIf { it > 0.0 }?.toFloat(),
            candidateCount = currentAnalysis.mixOutCandidates.size + nextAnalysis.mixInCandidates.size,
            incomingRate = plan.incomingPlaybackRate.toFloat(),
            outgoingStartMs = (plan.transitionStart * 1000.0).roundToLong(),
            incomingCueMs = (plan.incomingCueTime * 1000.0).roundToLong(),
            durationMs = plan.fadeMs,
            transitionBeats = plan.transitionBeats,
            vocalOverlap = plan.vocalOverlap.toFloat(),
            blocked = plan.blocked,
            planReason = plan.reason.takeIf(String::isNotBlank),
            policyReasons = plan.policyReasons,
        )

        // Gated on *both* tracks being measured, not on the plan alone. Until
        // then the planner is still sizing the overlap from a fallback that
        // moves as evidence lands, and a marker that slides along the bar while
        // you watch it is worse than none. Cleared during the transition itself
        // by [driveLap], because from that moment these fractions describe a
        // track the session player has already left.
        //
        // Asymmetric on purpose, because the two sides are read for different
        // things and a head-only result covers one of them completely.
        //
        // Where the window *sits* comes almost entirely from the outgoing track:
        // its content end, its outro, its mix-out anchors. A provisional result
        // has none of those — [analyzeHead] drops them deliberately rather than
        // answering confidently about a track it has only seen the opening of —
        // so the plan falls back to a plain end-of-track window, and the marker
        // would sit there and then jump backwards when the whole-track pass
        // lands. That is the sliding marker this guard exists for, so the
        // outgoing side still has to be finished.
        //
        // The incoming side is the opposite case. All the planner asks of it is
        // tempo, confidence and where it is safe to cue in — which are exactly
        // the fields a head pass measures, and it measures them over the same
        // opening window the whole-track pass would. Refining will sharpen those
        // numbers but not move them, so holding the marker back for it hid a
        // window that was already correct. Since the incoming track is now
        // routinely analysed from its opening long before it plays, that was
        // most of the time the marker was missing.
        val musicallyReady =
            if (useAutomix25) {
                currentReadyForPlan && nextReadyForPlan
            } else {
                analysisState.current == TrackAnalysisState.ANALYSED &&
                    analysisState.next in MEASURED_ENOUGH_TO_ENTER_ON
            }
        val markable = !plan.blocked &&
            plan.markerVisible &&
            duration > 0L &&
            musicallyReady
        AppSettings.smartTransitionWindow.value = if (markable) {
            TransitionWindow(
                start = (plan.transitionStart * 1000.0 / duration).toFloat().coerceIn(0f, 1f),
                end = (plan.transitionEnd * 1000.0 / duration).toFloat().coerceIn(0f, 1f),
            )
        } else {
            null
        }

        if (plan.blocked) return

        // Never start Automix from a provisional fallback.  Without a complete
        // analysis of A and at least a measured head for B there is no reliable
        // beat/downbeat or vocal-safe entry point; starting anyway is what made
        // some transitions arrive off-beat or cut a still-active vocal. If B is
        // still being analysed, keep playback normal and let the next controller
        // tick reconsider as soon as evidence lands.
        if (!musicallyReady) return

        val fade = plan.fadeMs
        if (fade <= 0L) return

        val transitionStartMs = (plan.transitionStart * 1000).roundToLong()
        val remaining = transitionStartMs - player.currentPosition
        // Same arm-ahead margin as the standard path, just measured against
        // the plan's own start rather than a fixed offset from track end —
        // an analyzed mix-out anchor can place that start well before the
        // file actually ends.
        val smartArmLeadMs = if (AppSettings.meteredConnection.value == true) {
            METERED_SMART_ARM_LEAD_MS
        } else {
            SMART_ARM_LEAD_MS
        }
        if (remaining > smartArmLeadMs) return

        begin(
            fade,
            startMs = transitionStartMs,
            endMs = (plan.transitionEnd * 1000).roundToLong(),
            smart = true,
            cueTimeMs = (plan.incomingCueTime * 1000).roundToLong(),
            playbackRate = plan.incomingPlaybackRate,
            outgoingRate = plan.outgoingPlaybackRate,
            loopTarget = plan.loopTarget,
            loopStartMs = (plan.loopStartTime * 1000).roundToLong(),
            loopEndMs = (plan.loopEndTime * 1000).roundToLong(),
            loopRepeats = plan.loopRepeats,
            preferRealStems = wantsRealStems,
            renderStyle = Render(
                style = plan.transitionStyle,
                bassSwap = plan.bassSwap,
                bassSwapFraction = plan.bassSwapFraction,
                filterSweep = plan.filterSweep,
                vocalOverlap = plan.vocalOverlap,
                gainEnvelope = plan.gainEnvelope,
                tempoEnvelope = plan.tempoEnvelope,
                incomingPitchSemitones = plan.incomingPitchSemitones,
                handoffFraction = plan.handoffFraction,
                automix25 = useAutomix25,
            ),
        )
    }

    /**
     * Keeps the stats line describing the pair that is actually playing.
     *
     * Cheap enough to run unconditionally — two concurrent-map lookups and a
     * set membership test — and running it unconditionally is the point: any
     * gating reintroduces the staleness this exists to remove.
     */
    private fun publishAnalysisState() {
        val player = active()
        val currentItem = player.currentMediaItem
        val nextIndex = player.nextMediaItemIndex
        val nextItem = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
        val requireIncomingRoute = AppSettings.automixVersion.value != AutomixVersion.V2_5
        AppSettings.smartAnalysis.value = SmartAnalysis(
            current = currentItem?.let { stateOf(it, analysisFor(it)) } ?: TrackAnalysisState.WAITING,
            next = nextItem?.let {
                stateOf(it, analysisFor(it), requireIncomingAudioReady = requireIncomingRoute)
            } ?: TrackAnalysisState.WAITING,
        )
    }

    /**
     * Where one track stands, for the stats line. "Analysing" is asked for
     * first because a track can be in flight while a superseded provisional
     * result is already on record, and the work in progress is the more useful
     * thing to say about it.
     */
    private fun stateOf(
        item: MediaItem,
        analysis: TrackAnalysis,
        requireIncomingAudioReady: Boolean = false,
    ): TrackAnalysisState = when {
        analysis.isUsable && requireIncomingAudioReady && !incomingAudioReadyFor(item) ->
            TrackAnalysisState.ANALYSING
        AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
            AppSettings.automix25Available.value &&
            analysis.isUsable &&
            !analysisReadyForPlan(item) ->
            if (analysisRunningFor(item)) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSING
        analysis.isUsable ->
            if (analysisRunningFor(item)) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
        analysisRunningFor(item) -> TrackAnalysisState.ANALYSING
        analysis.status == TrackAnalysis.STATUS_READY -> TrackAnalysisState.FAILED
        else -> TrackAnalysisState.WAITING
    }

    /**
     * The next queue item's own duration.
     *
     * Media3 fills a timeline window's duration in when the item is *prepared*,
     * which for the track after this one happens a few seconds before it starts
     * playing. So for almost the whole of the current track this answered zero —
     * and zero is not a harmless "don't know" downstream. It reaches
     * [com.music.orb.playback.smart.TrackAnalyzer.request] as the next
     * track's duration, and with no duration to check a sibling copy against the
     * analyzer will only read the rendition the cache key resolves to *right
     * now*, which with source substitution on is the `#alt` entry — while the
     * copy actually on disk is the plain one its own head fetch just pulled
     * down. Nothing matches, the pass returns silently, and it does that on every
     * tick for the rest of the track. Measured: a fully cached next track sat
     * unread for three minutes and was analysed eight seconds before the fade it
     * was meant to inform, having been analysable the whole time.
     *
     * The runtime is on the item already — queued from a row that knew it, and
     * carried on the playback URI as `d=` because a cross-source match is made on
     * it (see `Song.matchQuery`). Reading it here costs nothing and is available
     * from the moment the queue is set.
     */
    private fun nextItemDurationMs(nextIndex: Int, item: MediaItem): Long {
        val timeline = active().currentTimeline
        if (!timeline.isEmpty) {
            timeline.getWindow(nextIndex, Timeline.Window()).durationMs
                .takeIf { it != C.TIME_UNSET && it > 0 }
                ?.let { return it }
        }
        return queuedDurationMs(item)
    }

    /**
     * The runtime the queue row carried, in milliseconds, or 0 when the item
     * doesn't state one — a local file, or a track queued without a duration.
     *
     * Deliberately forgiving: [Uri.getQueryParameter] throws on an opaque URI,
     * and a missing or unparsable value is simply an absent duration rather than
     * anything worth failing a tick over.
     */
    private fun queuedDurationMs(item: MediaItem): Long {
        val uri = item.localConfiguration?.uri ?: return 0L
        val seconds = runCatching { uri.getQueryParameter("d") }.getOrNull()?.toLongOrNull() ?: return 0L
        return if (seconds > 0) seconds * 1000L else 0L
    }

    /** BitChord doesn't carry album metadata on [MediaMetadata] yet, so [TransitionTrackInfo.album] stays blank. */
    private fun MediaItem.toTransitionInfo(durationMs: Long) = TransitionTrackInfo(
        id = mediaId,
        durationMs = durationMs,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
    )

    /**
     * Loads the standby player with the queue, positioned on the incoming track
     * at the plan's cue point, and leaves it buffering there silently.
     *
     * Nothing is committed here. The standby is a scratch player until
     * [startFade] runs, so a queue edit, a skip or a pause arriving during
     * arming costs nothing but the decoder it was holding.
     *
     * The cue point is reached by *starting there* rather than by seeking:
     * `setMediaItems` takes the position the item is to begin at, so the
     * incoming track opens at its analyzed mix-in point with no seek, no
     * discontinuity and no frame-rounding. Same for the beatmatch stretch, which
     * is applied before a note has been rendered rather than being switched on
     * underneath one already playing.
     */
    private fun begin(
        fade: Long,
        startMs: Long,
        endMs: Long,
        smart: Boolean,
        cueTimeMs: Long = 0L,
        playbackRate: Double = 1.0,
        outgoingRate: Double = 1.0,
        loopTarget: TransitionLoopTarget = TransitionLoopTarget.NONE,
        loopStartMs: Long = 0L,
        loopEndMs: Long = 0L,
        loopRepeats: Int = 0,
        preferRealStems: Boolean = false,
        renderStyle: Render = Render(),
    ): Boolean {
        val out = active()
        val into = standby()
        if (out === into) return false
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false

        overlayAudibility.reset()
        levelTickAtMs = 0L
        unusableStemSinceMs = 0L
        lastOverlayTraceMs = 0L
        fadeMs = fade
        fadeStartMs = startMs.coerceAtLeast(0L)
        fadeEndMs = endMs
        smartFadeActive = smart
        incomingCueTimeMs = cueTimeMs.coerceAtLeast(0L)
        incomingPlaybackRate = playbackRate.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        outgoingPlaybackRate = outgoingRate.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        this.loopTarget = loopTarget
        this.loopStartMs = loopStartMs.coerceAtLeast(0L)
        this.loopEndMs = loopEndMs.coerceAtLeast(this.loopStartMs)
        this.loopRepeatsRemaining = loopRepeats.coerceAtLeast(0)
        this.loopRepeatsPlanned = this.loopRepeatsRemaining
        loopSeekBlockedUntilMs = 0L
        realStemWanted = preferRealStems &&
            (renderStyle.style == TransitionStyle.INTRO_BED || renderStyle.style == TransitionStyle.INTRO_BRIDGE_FILTER)
        realStemActive = false
        realStemReleaseAligned = false
        stemDeck()?.reset()
        mixElapsedMs = 0L
        mixClockAtMs = 0L
        render = renderStyle
        armDeadline = SystemClock.elapsedRealtime() + ARM_TIMEOUT_MS
        handedOff = false
        outgoing = out
        incoming = into
        outgoingPauseAtEndBeforeTransition = out.pauseAtEndOfMediaItems
        // Freeze DSP ownership to these concrete A/B streams before MediaSession ownership swaps.
        // This lets Instrumental Overlay remove B's low end from its very first audible buffer.
        filters.begin()

        val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
        queuedItemCount = items.size
        if (realStemWanted) {
            tryPrepareStemDeck(items.getOrNull(nextIndex))
        }

        Log.d(
            TAG,
            "arm ${if (smart) "smart" else "standard"} fade=${fade}ms end=${endMs}ms " +
                "cue=${incomingCueTimeMs}ms rates=$outgoingPlaybackRate/$incomingPlaybackRate " +
                "start=${fadeStartMs}ms at=${out.currentPosition}ms " +
                "loop=$loopTarget:${loopStartMs}-${loopEndMs}x$loopRepeatsPlanned " +
                "style=${render.style} bassSwap=${render.bassSwap}@${render.bassSwapFraction} " +
                "sweep=${render.filterSweep} stems=$realStemWanted/${stemDeck()?.isPrepared == true}",
        )

        // Carried across so the incoming track inherits the listener's own
        // settings rather than whatever the standby was left on last time.
        into.skipSilenceEnabled = out.skipSilenceEnabled
        into.repeatMode = out.repeatMode
        into.shuffleModeEnabled = out.shuffleModeEnabled
        // The winning deck should inherit the user's normal end-of-item behaviour, not the
        // temporary guard that is applied to A once the overlap becomes audible.
        into.pauseAtEndOfMediaItems = outgoingPauseAtEndBeforeTransition
        // Stacks on top of the listener's speed control rather than replacing
        // it, so a beatmatched transition and "play everything at 1.25x" don't
        // fight each other. Undone in [finish].
        into.setPlaybackSpeed((AppSettings.playbackSpeed.value * incomingPlaybackRate).toFloat())
        into.volume = 0f
        into.setMediaItems(items, nextIndex, incomingCueTimeMs)
        // Buffers without sounding. Started for real in [startFade].
        into.playWhenReady = false
        into.prepare()

        phase = Phase.ARMING
        return true
    }

    /**
     * Waits for the standby to have the incoming track ready at its cue point,
     * and for the outgoing track to reach the fade.
     *
     * There is nothing to align here — the two players hold different songs — so
     * this is only ever waiting on a buffer.
     */
    private fun driveArming() {
        val out = outgoing ?: return bail()
        val into = incoming ?: return bail()
        if (!stillWorthFading()) return bail()
        // Paused while armed: the transition is no longer imminent, and holding
        // a prepared decoder open against a stopped player is worse than arming
        // again when playback resumes.
        if (!out.playWhenReady) return bail()

        val expired = SystemClock.elapsedRealtime() > armDeadline
        val bufferedAhead = incomingBufferedAheadMs(into)
        val requiredBuffer = requiredIncomingPrebufferMs(into)
        val decoderReady = into.playbackState == Player.STATE_READY
        val safelyBuffered = decoderReady && bufferedAhead >= requiredBuffer

        // STATE_READY only proves that Media3 can render *now*. With the global
        // fast-start load control that can be roughly half a second of audio,
        // which is nowhere near enough proof for a two-deck mix. Automix needs
        // a real runway in hand before B is ever allowed to become audible.
        if (expired && !safelyBuffered) {
            Log.w(
                TAG,
                "drop transition: incoming never prebuffered " +
                    "state=${into.playbackState} buffered=${bufferedAhead}ms " +
                    "required=${requiredBuffer}ms",
            )
            return bail()
        }

        // Move A onto the server's meeting tempo before B becomes audible for every beat-synced
        // 2.5 family. Previously this happened only for Instrumental Overlay, so DJ_BLEND often
        // had aligned metadata but two decks that were not actually converging in real playback.
        if (smartFadeActive && usesTempoBridgeStyle() && outgoingPlaybackRate != 1.0) {
            val remainingToStart = (fadeStartMs - out.currentPosition).coerceAtLeast(0L)
            val prep = (1f - remainingToStart.toFloat() / TEMPO_PREP_MS.toFloat()).coerceIn(0f, 1f)
            val eased = prep * prep * (3f - 2f * prep)
            val relative = 1.0 + (outgoingPlaybackRate - 1.0) * eased
            out.setPlaybackSpeed((AppSettings.playbackSpeed.value * relative).toFloat())
        }

        if (realStemWanted && stemDeck()?.isPrepared != true) {
            tryPrepareStemDeck(into.currentMediaItem)
        }

        // Wait for the track to reach the planner's explicit media-timeline start. This cannot be
        // reconstructed as end - fade for loop/rate overlays because their fade is wall-clock time.
        val atFadePoint = fadeStartMs <= 0L || out.currentPosition >= fadeStartMs
        if (!atFadePoint) return

        // Never start a planned beat/downbeat transition late just because B
        // happened to finish buffering after the musical entry point. Missing
        // one mix is preferable to starting it off-grid or fading into a stream
        // that is about to stall.
        if (!safelyBuffered) {
            Log.w(
                TAG,
                "drop transition at entry: incoming buffer unsafe " +
                    "state=${into.playbackState} buffered=${bufferedAhead}ms " +
                    "required=${requiredBuffer}ms",
            )
            return bail()
        }

        startFade()
    }

    private fun incomingBufferedAheadMs(player: ExoPlayer): Long =
        (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)

    private fun requiredIncomingPrebufferMs(player: ExoPlayer): Long {
        val target = when {
            smartFadeActive && AppSettings.meteredConnection.value == true -> METERED_AUTOMIX_PREBUFFER_MS
            smartFadeActive -> AUTOMIX_PREBUFFER_MS
            else -> STANDARD_PREBUFFER_MS
        }
        val remaining = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?.minus(player.currentPosition)
            ?.coerceAtLeast(0L)
        return remaining
            ?.let { minOf(target, (it - PREBUFFER_END_GUARD_MS).coerceAtLeast(0L)) }
            ?: target
    }

    /**
     * Starts B only after a real prebuffer has been accumulated.
     *
     * B is allowed to render for a very short stability window while A still
     * owns the MediaSession. If B immediately falls back to BUFFERING, the mix
     * is abandoned before metadata/audio focus/session ownership move away from
     * A. This is the last guard against the audible "B starts, then stops"
     * failure seen on cold or temporarily slow streams.
     */
    private fun startFade() {
        val out = outgoing ?: return bail()
        val into = incoming ?: return bail()

        // AutoPlay may have appended to the queue since the standby was loaded
        // with a copy of it; those tracks would otherwise be lost at the swap.
        reconcileQueue(out, into)

        // A long Instrumental Overlay can keep B audible underneath A for tens of seconds.
        // In that mode A must not auto-advance into the same B at 0:00 when it reaches its natural
        // boundary, because the prepared incoming deck is already part-way through B. Keep this
        // guard scoped to overlays: short crossfades retain Media3's normal end-of-item behaviour.
        if (isInstrumentalOverlayStyle()) {
            out.pauseAtEndOfMediaItems = true
        }

        into.volume = 0f
        // The planner uses media time. Silence skipping can eat B's quiet intro
        // after the EQ carve and move its impact ahead of A's ending.
        if (isInstrumentalOverlayStyle()) {
            into.skipSilenceEnabled = false
            out.skipSilenceEnabled = false
        }
        if (smartFadeActive && usesTempoBridgeStyle()) {
            out.setPlaybackSpeed(
                (AppSettings.playbackSpeed.value * outgoingPlaybackRate).toFloat(),
            )
            Log.d(
                TAG,
                "2.5 audible mix style=${render.style} rates=$outgoingPlaybackRate/$incomingPlaybackRate " +
                    "handoff=${render.handoffFraction} fade=${fadeMs}ms",
            )
        }
        into.playWhenReady = true
        realStemActive = if (realStemWanted) {
            val deck = stemDeck()
            val started = deck?.start() == true
            if (started) {
                Log.d(TAG, "real accompaniment stem engaged for ${into.currentMediaItem?.mediaId}")
            } else {
                Log.d(TAG, "real stems not ready at mix start; pristine B fallback")
            }
            started
        } else {
            false
        }
        fadeStartedAt = SystemClock.elapsedRealtime()
        mixElapsedMs = 0L
        mixClockAtMs = fadeStartedAt

        if (render.style == TransitionStyle.INTRO_BED ||
            render.style == TransitionStyle.INTRO_BRIDGE_FILTER ||
            render.style == TransitionStyle.RUNWAY_BLEND ||
            render.style == TransitionStyle.FOREGROUND_TAKEOVER ||
            render.style == TransitionStyle.PHRASE_TAKEOVER ||
            render.style == TransitionStyle.DJ_BLEND ||
            render.style == TransitionStyle.DJ_FILTER ||
            render.style == TransitionStyle.EQ_SWAP ||
            render.style == TransitionStyle.PHRASE_CUT ||
            render.style == TransitionStyle.CUT
        ) {
            val outgoingId = out.currentMediaItem?.mediaId.orEmpty()
            val incomingId = into.currentMediaItem?.mediaId.orEmpty()
            if (outgoingId.isNotBlank() && incomingId.isNotBlank()) {
                AppSettings.automixVisualTransition.value = AutomixVisualTransition(
                    outgoingMediaId = outgoingId,
                    incomingMediaId = incomingId,
                    progress = 0f,
                    outgoingPositionMs = out.currentPosition.coerceAtLeast(0L),
                    outgoingDurationMs = out.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L,
                )
            }
        } else {
            AppSettings.automixVisualTransition.value = null
        }

        AppSettings.smartMixInProgress.value = isRealMix()
        if (smartFadeActive) NerdStats.onAutomixMixProgress(0f)
        AppSettings.smartTransitionWindow.value = null
        phase = Phase.FADING
    }

    private fun commitHandoff(out: ExoPlayer, into: ExoPlayer) {
        if (handedOff) return
        Log.d(
            TAG,
            "handoff committed at cue=${into.currentPosition}ms out=${out.currentPosition}ms " +
                "bufferedAhead=${incomingBufferedAheadMs(into)}ms",
        )

        // Move the listener first so any event emitted by the ownership swap is
        // interpreted against the player that is about to become authoritative.
        listenTo(into)
        handedOff = true
        onHandoff(out, into)

        // The outgoing player holds the whole queue too. Once B owns the
        // session, prevent A from auto-advancing into B a second time.
        if (out.mediaItemCount > out.currentMediaItemIndex + 1) {
            out.removeMediaItems(out.currentMediaItemIndex + 1, out.mediaItemCount)
        }
    }

    private fun handoffCommitDelayMs(): Long = when {
        render.style == TransitionStyle.CUT || render.style == TransitionStyle.PHRASE_CUT ->
            minOf(140L, (fadeMs / 4).coerceAtLeast(60L))
        fadeMs <= 2_000L -> minOf(420L, (fadeMs / 4).coerceAtLeast(120L))
        else -> HANDOFF_STABILITY_MS
    }

    /**
     * Copies onto the standby anything appended to the queue while it was
     * arming.
     *
     * AutoPlay extending the queue mid-transition is explicitly allowed — it
     * doesn't change the playing item, so it has never been a reason to drop a
     * blend. Under the old design that was free, because only one player ever
     * held the queue. Now the standby is carrying a copy taken at arm time, and
     * that copy is what survives the swap, so the difference has to be carried
     * across or the appended tracks simply vanish when the roles change.
     *
     * Only a pure append is reconciled. Anything else — a queue replaced, an
     * item removed or moved — changes what the incoming track *is*, and
     * [listener] has already bailed the transition for it.
     */
    private fun reconcileQueue(out: ExoPlayer, into: ExoPlayer) {
        val appended = (queuedItemCount until out.mediaItemCount).map { out.getMediaItemAt(it) }
        if (appended.isEmpty()) return
        into.addMediaItems(appended)
        queuedItemCount = out.mediaItemCount
        Log.d(TAG, "reconciled ${appended.size} appended item(s) onto the incoming player")
    }

    /**
     * The crossfade proper.
     *
     * Driven off the *incoming* track's position rather than off a clock, so a
     * pause parks the transition where it stands and resuming picks it back up
     * — no timer to reconcile, and neither player left hanging at half volume
     * while the other waits.
     */
    /**
     * True once the incoming deck is the perceptual foreground for the current
     * renderer. This is also the boundary at which MediaSession ownership may
     * move to B, so the miniplayer and system notification follow the dominant
     * record rather than merely the record that started first.
     */
    private fun incomingOwnsForeground(progress: Float): Boolean {
        val p = progress.coerceIn(0f, 1f)
        val (incoming, outgoing) = when (render.style) {
            TransitionStyle.INTRO_BED,
            TransitionStyle.INTRO_BRIDGE_FILTER,
            TransitionStyle.RUNWAY_BLEND -> {
                introBedGainAt(p, render.gainEnvelope)
                    ?: (introBedIncomingGain(p, render.handoffFraction.toFloat()) to
                        introBedOutgoingGain(p, render.handoffFraction.toFloat()))
            }
            TransitionStyle.FOREGROUND_TAKEOVER,
            TransitionStyle.PHRASE_TAKEOVER ->
                introBedGainAt(p, render.gainEnvelope) ?: (riseGain(p) to fallGain(p))
            TransitionStyle.DJ_BLEND ->
                if (render.automix25) {
                    djBlendIncomingGain(p) to djBlendOutgoingGain(p)
                } else {
                    riseGain(p) to fallGain(p)
                }
            TransitionStyle.EQ_SWAP -> eqSwapIncomingGain(p) to eqSwapOutgoingGain(p)
            TransitionStyle.DJ_FILTER ->
                if (render.automix25) {
                    djFilterIncomingGain(p) to djFilterOutgoingGain(p)
                } else {
                    riseGain(p) to djFilterOutgoingGain(p)
                }
            TransitionStyle.CUT -> cutIncomingGain(p) to cutOutgoingGain(p)
            TransitionStyle.PHRASE_CUT ->
                phraseCutIncomingGain(p, render.handoffFraction.toFloat()) to
                    phraseCutOutgoingGain(p, render.handoffFraction.toFloat())
            else -> riseGain(p) to fallGain(p)
        }
        // A tiny hysteresis prevents ownership from flipping on an exact equality
        // one tick before B is audibly stronger. Ownership is one-way per mix.
        return incoming >= outgoing + FOREGROUND_HANDOFF_GAIN_MARGIN ||
            (p >= 0.985f && incoming >= outgoing)
    }

    private fun driveFade() {
        val out = outgoing ?: return bail()
        val player = incoming ?: return bail()
        // The incoming track gets the same say over the length as the outgoing
        // one did, so a long crossfade into a short track tightens rather than
        // swallowing it. Its duration is often still unknown when the fade
        // starts — the stream is only being opened — so this is read every tick
        // and simply narrows the span once the answer arrives. Capped only by
        // the incoming track's own length, not by [configuredFadeMs] — a Smart
        // Fade plan already sized itself independently of that setting, and
        // may be running with it at zero.
        // Measured from where the incoming track was *cued*, not from zero. A
        // Automix plan can drop it in mid-arrangement, and reading its raw
        // position as elapsed-fade would put a cue at 0:45 instantly past the
        // end of an 8-second fade — finishing the blend on its first tick and
        // landing as an abrupt cut, which is precisely the failure a cued
        // transition is supposed to avoid.
        val remainingIncoming = player.duration
            .takeIf { it != C.TIME_UNSET && it > 0L }
            ?.minus(incomingCueTimeMs)
            ?.coerceAtLeast(0L)
        val incomingCap = remainingIncoming?.div(3) ?: Long.MAX_VALUE
        // An Instrumental Overlay may deliberately loop B, so media-time remaining is not the
        // transition's wall-clock budget. Capping it to one third of B here would silently undo
        // the planner's loop/tempo choreography and finish the mix early.
        val span = if (isInstrumentalOverlayStyle()) {
            fadeMs.coerceAtLeast(1L)
        } else {
            minOf(fadeMs, incomingCap).coerceAtLeast(1L)
        }
        driveIncomingLoop(player)
        val elapsed = when {
            isInstrumentalOverlayStyle() -> advanceMixClock(out, player)
            smartFadeActive && render.automix25 -> (out.currentPosition - fadeStartMs).coerceAtLeast(0L)
            else -> (player.currentPosition - incomingCueTimeMs).coerceAtLeast(0L)
        }
        val progress = (elapsed.toFloat() / span).coerceIn(0f, 1f)
        if (smartFadeActive) NerdStats.onAutomixMixProgress(progress)

        val outgoingEnded = out.playbackState == Player.STATE_ENDED ||
            out.playbackState == Player.STATE_IDLE ||
            hasReachedNaturalEnd(out)

        // Instrumental Overlay has already committed to B as a musical layer once FADING starts.
        // If A reaches its real end, ownership must move to that exact B deck even if the network
        // put it in BUFFERING/IDLE a moment earlier. PlaybackService then repairs the route in place.
        if (outgoingEnded && !handedOff && isInstrumentalOverlayStyle()) {
            commitHandoff(out, player)
        }

        if (!handedOff) {
            // A plain short crossfade may still be dropped if B immediately proves unusable.
            // Instrumental Overlay is different: B may already have been audible underneath A for
            // tens of seconds. Destroying that deck on one transient BUFFERING/IDLE event makes A
            // later auto-open B again from 0:00, which is the exact restart heard in the field.
            // Keep A authoritative and at full gain while B refills; if A reaches its end first,
            // ownership moves to this same B deck and PlaybackService's transport recovery repairs
            // it in place without resetting its musical position.
            if (player.playbackState != Player.STATE_READY) {
                if (isInstrumentalOverlayStyle()) {
                    // Do not keep ducking/filtering A while B has no samples to contribute. Freeze
                    // the musical foreground at unity and preserve B's playhead/loader. Once B is
                    // READY again the normal envelope resumes from the same transition clock.
                    out.volume = 1f
                    player.volume = 0f
                    filters.open()
                    Log.w(
                        TAG,
                        "incoming temporarily not ready during instrumental overlay; preserving deck " +
                            "state=${player.playbackState} elapsed=${elapsed}ms " +
                            "pos=${player.currentPosition}ms bufferedAhead=${incomingBufferedAheadMs(player)}ms",
                    )
                    return
                } else {
                    Log.w(
                        TAG,
                        "incoming lost readiness before handoff: state=${player.playbackState} " +
                            "elapsed=${elapsed}ms bufferedAhead=${incomingBufferedAheadMs(player)}ms",
                    )
                    return bail()
                }
            }

            val commitDelay = handoffCommitDelayMs()
            // Keep A as the MediaSession/miniplayer/notification owner until B is
            // actually the foreground record. Instrumental Overlay intentionally
            // lets B play quietly under A for a long time; publishing B's metadata
            // at its first audible frame makes the UI disagree with what the user
            // is hearing. For every style, ownership moves only after the rendered
            // gain choreography says B has become at least as dominant as A.
            if (player.playbackState == Player.STATE_READY && elapsed >= commitDelay && incomingOwnsForeground(progress)) {
                val commitTarget = if (
                    smartFadeActive && AppSettings.meteredConnection.value == true
                ) {
                    METERED_POST_START_COMMIT_BUFFER_MS
                } else {
                    POST_START_COMMIT_BUFFER_MS
                }
                val commitBuffer = minOf(
                    commitTarget,
                    requiredIncomingPrebufferMs(player),
                )
                if (incomingBufferedAheadMs(player) < commitBuffer) {
                    Log.w(
                        TAG,
                        "incoming buffer collapsed before foreground handoff: " +
                            "bufferedAhead=${incomingBufferedAheadMs(player)}ms " +
                            "required=${commitBuffer}ms",
                    )
                    return bail()
                }
                commitHandoff(out, player)
            }
        }

        val incomingGain: Float
        val outgoingGain: Float
        when (render.style) {
            TransitionStyle.INTRO_BED,
            TransitionStyle.INTRO_BRIDGE_FILTER,
            TransitionStyle.RUNWAY_BLEND -> {
                // Reference-style intro runway: B can be audible from 0:00 while
                // A remains the foreground record. The real gain handoff begins
                // only after A's confirmed vocal release.
                val automated = introBedGainAt(progress, render.gainEnvelope)
                incomingGain = automated?.first
                    ?: introBedIncomingGain(progress, render.handoffFraction.toFloat())
                outgoingGain = automated?.second
                    ?: introBedOutgoingGain(progress, render.handoffFraction.toFloat())

                AppSettings.automixVisualTransition.value?.let { visual ->
                    if (visual.incomingMediaId == player.currentMediaItem?.mediaId) {
                        AppSettings.automixVisualTransition.value = visual.copy(
                            progress = introBedVisualProgress(
                                progress,
                                render.handoffFraction.toFloat(),
                            ),
                            outgoingPositionMs = out.currentPosition.coerceAtLeast(0L),
                            outgoingDurationMs = out.duration
                                .takeIf { it != C.TIME_UNSET && it > 0L } ?: visual.outgoingDurationMs,
                        )
                    }
                }
            }
            TransitionStyle.FOREGROUND_TAKEOVER,
            TransitionStyle.PHRASE_TAKEOVER -> {
                val automated = introBedGainAt(progress, render.gainEnvelope)
                incomingGain = automated?.first ?: riseGain(progress)
                outgoingGain = automated?.second ?: fallGain(progress)
                AppSettings.automixVisualTransition.value?.let { visual ->
                    if (visual.incomingMediaId == player.currentMediaItem?.mediaId) {
                        AppSettings.automixVisualTransition.value = visual.copy(
                            progress = progress,
                            outgoingPositionMs = out.currentPosition.coerceAtLeast(0L),
                            outgoingDurationMs = out.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                                ?: visual.outgoingDurationMs,
                        )
                    }
                }
            }
            TransitionStyle.DJ_BLEND -> {
                if (render.automix25) {
                    incomingGain = djBlendIncomingGain(progress)
                    outgoingGain = djBlendOutgoingGain(progress)
                } else {
                    incomingGain = riseGain(progress)
                    outgoingGain = fallGain(progress)
                }
            }
            TransitionStyle.EQ_SWAP -> {
                incomingGain = eqSwapIncomingGain(progress)
                outgoingGain = eqSwapOutgoingGain(progress)
            }
            TransitionStyle.DJ_FILTER -> {
                // 2.5 establishes B early under the spectral handoff; 2.0 keeps its historical
                // sine-rise renderer unchanged.
                incomingGain = if (render.automix25) djFilterIncomingGain(progress) else riseGain(progress)
                outgoingGain = djFilterOutgoingGain(progress)
            }
            TransitionStyle.CUT -> {
                incomingGain = cutIncomingGain(progress)
                outgoingGain = cutOutgoingGain(progress)
            }
            TransitionStyle.PHRASE_CUT -> {
                incomingGain = phraseCutIncomingGain(progress, render.handoffFraction.toFloat())
                outgoingGain = phraseCutOutgoingGain(progress, render.handoffFraction.toFloat())
            }
            else -> {
                incomingGain = riseGain(progress)
                outgoingGain = fallGain(progress)
            }
        }

        if (smartFadeActive && usesTempoBridgeStyle()) {
            rideTempoBridge(progress, out, player)
        }

        // A prepared standby can advance a few decoder buffers before the first
        // FADING tick.  Without an independent attack envelope, a loud master can
        // therefore appear already several dB up even though the mathematical
        // crossfade itself starts at zero.  Give every real blend (CUT excluded,
        // because its whole purpose is a sub-second phrase handoff) a short,
        // fixed soft-start.  This is multiplied into the style curve, so it never
        // changes the planned handoff timing — it only prevents B from punching
        // over A on its first audible buffers.
        val startupEnvelope = if (
            render.style == TransitionStyle.CUT ||
            render.style == TransitionStyle.PHRASE_CUT
        ) {
            1f
        } else {
            smoothStep(0f, INCOMING_SOFT_START_MS.toFloat(), elapsed.toFloat())
        }
        val plannedIncomingGain = (incomingGain * startupEnvelope).coerceIn(0f, 1f)
        val now = SystemClock.elapsedRealtime()
        val levelStep = if (levelTickAtMs == 0L) 0L else now - levelTickAtMs
        levelTickAtMs = now
        if (isInstrumentalOverlayStyle()) validateStemSignal(player, now)
        val desiredIncomingGain = if (isInstrumentalOverlayStyle()) {
            overlayAudibility.nextGain(
                planned = plannedIncomingGain,
                outgoingGain = outgoingGain,
                outgoingRms = filters.outgoingRms(),
                incomingRms = if (realStemActive) stemDeck()?.recentRms() else filters.incomingRms(),
                progress = progress,
                elapsedMs = elapsed,
                stepMs = if (out.isPlaying && player.isPlaying) levelStep else 0L,
            )
        } else plannedIncomingGain
        renderIncomingStemAware(player, desiredIncomingGain, progress)
        if (isInstrumentalOverlayStyle() && now - lastOverlayTraceMs >= 2000L) {
            lastOverlayTraceMs = now
            Log.d(TAG, "overlay render a=${out.currentPosition} b=${player.currentPosition} " +
                "playing=${out.isPlaying}/${player.isPlaying} gain=$outgoingGain/$desiredIncomingGain " +
                "rms=${filters.outgoingRms()}/${filters.incomingRms()} " +
                "stem=$realStemActive/${stemDeck()?.recentRms()} fullB=${player.volume} progress=$progress")
        }
        out.volume = outgoingGain.coerceIn(0f, 1f)
        // DSP ownership is frozen by filters.begin(), so 2.5 DJ families can shape the actual
        // overlap from B's first audible buffer. Waiting until MediaSession handoff meant a
        // DJ_FILTER sounded like an unprocessed level overlap and an EQ_SWAP happened after the
        // musical moment it was supposed to create. 2.0 keeps its historical post-handoff timing.
        val preHandoffDsp25 = render.automix25 && when (render.style) {
            TransitionStyle.DJ_BLEND,
            TransitionStyle.DJ_FILTER,
            TransitionStyle.EQ_SWAP,
            TransitionStyle.RUNWAY_BLEND,
            TransitionStyle.PHRASE_TAKEOVER -> true
            else -> false
        }
        if (handedOff || isInstrumentalOverlayStyle() || preHandoffDsp25) {
            rideFilters(progress)
        } else {
            filters.open()
        }

        // Whichever comes first: the fade running its course, the old track
        // genuinely ending, the tail failing outright, or whichever setting
        // armed this fade being switched off mid-blend. Checked against the
        // setting that actually started it — a Automix normally runs with
        // [configuredFadeMs] at zero, and reading that as "turned off" would
        // end every Automix on its first tick.
        val settingSwitchedOff = if (smartFadeActive) {
            !AppSettings.smartFadeEnabled.value
        } else {
            configuredFadeMs() <= 0L
        }
        // Non-overlay styles keep the old conservative rule: only promote a READY incoming deck.
        // Overlay styles already handled their natural-end ownership transfer above.
        if (outgoingEnded && !handedOff && player.playbackState == Player.STATE_READY) {
            commitHandoff(out, player)
        }
        val done = progress >= 1f || outgoingEnded || settingSwitchedOff
        if (done) finish()
    }

    private fun hasReachedNaturalEnd(player: ExoPlayer): Boolean {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: return false
        return player.currentPosition >= duration - OUTGOING_END_EPSILON_MS
    }

    private fun isInstrumentalOverlayPlan(style: TransitionStyle): Boolean =
        style == TransitionStyle.INTRO_BED || style == TransitionStyle.INTRO_BRIDGE_FILTER

    /** Loads a prepared accompaniment WAV on the third deck while B itself remains prebuffered. */
    private fun tryPrepareStemDeck(item: MediaItem?) {
        if (!realStemWanted || item == null) return
        val deck = stemDeck() ?: return
        if (deck.isPrepared) return
        val stems = stemsFor(item, incomingCueTimeMs) ?: return
        val speed = (AppSettings.playbackSpeed.value * incomingPlaybackRate).toFloat()
        deck.prepare(stems, incomingCueTimeMs, speed)
        Log.d(
            TAG,
            "real stems armed ${stems.trackId} ${stems.startMs}..${stems.endMs}ms " +
                "cue=${incomingCueTimeMs}ms",
        )
    }

    /** A prepared stem must also be playing and contain useful PCM. */
    private fun validateStemSignal(player: ExoPlayer, now: Long) {
        if (!realStemActive || !player.isPlaying) {
            unusableStemSinceMs = 0L
            return
        }
        val deck = stemDeck()
        val unusable = OverlayAudibility.unusableStem(
            deck?.recentRms(), filters.incomingRms(), deck?.isPlaying == true,
        )
        if (!unusable) {
            unusableStemSinceMs = 0L
        } else if (unusableStemSinceMs == 0L) {
            unusableStemSinceMs = now
        } else if (now - unusableStemSinceMs >= 750L) {
            // READY does not imply useful accompaniment. Do not keep full B
            // muted until handoff when the replacement stem is silent/stalled.
            realStemActive = false
            deck?.reset()
            unusableStemSinceMs = 0L
            Log.w(TAG, "overlay silent/stalled stem; restoring full incoming deck")
        }
    }

    /** Keeps B on the verified accompaniment until the planned return to its full rendition. */
    private fun renderIncomingStemAware(player: ExoPlayer, desiredGain: Float, progress: Float) {
        if (!realStemActive || !isInstrumentalOverlayStyle()) {
            player.volume = desiredGain
            return
        }
        val deck = stemDeck()
        if (deck == null || (!deck.isReady && !deck.isPlaying)) {
            realStemActive = false
            player.volume = desiredGain
            deck?.reset()
            Log.w(TAG, "real stem deck lost readiness; full B fallback")
            return
        }

        if (player.playWhenReady) {
            if (!deck.isPlaying) deck.start()
        } else {
            deck.pause()
        }

        val releaseStart = render.handoffFraction.toFloat().coerceIn(0.45f, 0.97f)
        val releaseWidth = if (fadeMs > 0L) {
            (REAL_STEM_RELEASE_MS.toFloat() / fadeMs.toFloat())
                .coerceIn(REAL_STEM_RELEASE_MIN_FRACTION, REAL_STEM_RELEASE_MAX_FRACTION)
        } else {
            REAL_STEM_RELEASE_MIN_FRACTION
        }
        val releaseEnd = (releaseStart + releaseWidth).coerceAtMost(1f)

        // The accompaniment WAV came from the immutable analysis rendition while the silent full B
        // deck may be Lossless/Hi-Res. They share musical time, not sample phase. Only correct a
        // meaningful drift: seeking a local stem that is already aligned can itself produce a tiny
        // discontinuity, and codec/rendition phase is intentionally handled by the very short
        // complementary release rather than by pretending the two files are sample-identical.
        if (!realStemReleaseAligned && progress >= (releaseStart - REAL_STEM_ALIGN_LEAD_FRACTION).coerceAtLeast(0f)) {
            if (!deck.isNear(player.currentPosition, REAL_STEM_ALIGNMENT_TOLERANCE_MS)) {
                deck.seekTrackPosition(player.currentPosition)
            }
            realStemReleaseAligned = true
        }

        val fullAmount = smoothStep(releaseStart, releaseEnd, progress)
        // Stem B and full B are two renderings of the same musical source. Their gains therefore
        // stay complementary instead of using the equal-power law reserved for unrelated tracks;
        // otherwise a well-aligned accompaniment would jump by ~3 dB at the middle of the release.
        val stemGain = (desiredGain * (1f - fullAmount)).coerceIn(0f, 1f)
        val fullGain = (desiredGain * fullAmount).coerceIn(0f, 1f)
        deck.setGain(stemGain)
        player.volume = fullGain

        if (fullAmount >= 0.999f) {
            deck.reset()
            realStemActive = false
            player.volume = desiredGain
            Log.d(TAG, "real stem released into full B at ${player.currentPosition}ms")
        }
    }

    private fun isInstrumentalOverlayStyle(): Boolean =
        render.style == TransitionStyle.INTRO_BED || render.style == TransitionStyle.INTRO_BRIDGE_FILTER

    /**
     * Wall-clock progress for an overlay. The incoming playhead is not a valid clock once it can
     * seek backwards through a musical loop, so advance only while both decks are genuinely ready
     * and playing. A pause or a short loop seek therefore freezes the choreography instead of
     * letting the faders run ahead of the audio.
     */
    private fun advanceMixClock(out: ExoPlayer, into: ExoPlayer): Long {
        val now = SystemClock.elapsedRealtime()
        val previous = mixClockAtMs
        mixClockAtMs = now
        if (previous <= 0L) return mixElapsedMs
        val running = out.isPlaying && into.isPlaying
        if (running) {
            mixElapsedMs += (now - previous).coerceIn(0L, MIX_CLOCK_MAX_STEP_MS)
        }
        return mixElapsedMs
    }

    /**
     * Repeats a measured low-vocal phrase of B. The loop is deliberately limited to the incoming
     * bed: at 10–20% gain a decoder seek can be hidden by A. When the real accompaniment deck is
     * active it follows the same seek, so the vocal-suppressed bed and silent authoritative B remain
     * on the same musical phrase. The third deck is reserved for that accompaniment, so this still
     * does not fake an outgoing-A loop that would require another independently controlled source.
     */
    private fun driveIncomingLoop(player: ExoPlayer) {
        if (!isInstrumentalOverlayStyle() || loopTarget != TransitionLoopTarget.INCOMING) return
        if (loopRepeatsRemaining <= 0 || loopEndMs <= loopStartMs || loopStartMs < incomingCueTimeMs) return
        val now = SystemClock.elapsedRealtime()
        if (now < loopSeekBlockedUntilMs) return
        if (player.currentPosition < loopEndMs - LOOP_TRIGGER_EARLY_MS) return

        val iteration = loopRepeatsPlanned - loopRepeatsRemaining + 1
        Log.d(
            TAG,
            "instrumental loop $iteration/$loopRepeatsPlanned " +
                "${loopStartMs}ms..${loopEndMs}ms on ${player.currentMediaItem?.mediaId}",
        )
        loopRepeatsRemaining -= 1
        loopSeekBlockedUntilMs = now + LOOP_SEEK_GUARD_MS
        player.seekTo(loopStartMs)
        if (realStemActive) stemDeck()?.seekTrackPosition(loopStartMs)
    }

    /**
     * DJ-style spectral handoff for the Instrumental Overlay. Before A releases, B has its low end
     * and part of its vocal body carved away; around the measured handoff the bass changes decks;
     * only then does B open fully. When a real accompaniment stem is active this EQ rides both
     * B representations identically, so the short stem -> full-rendition crossfade does not change
     * bass or midrange character underneath the listener.
     */
    private fun rideInstrumentalOverlayEq(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val handoff = render.handoffFraction.toFloat().coerceIn(0.45f, 0.995f)
        val swapAt = render.bassSwapFraction.toFloat().coerceIn(0.42f, 0.92f)
        val swap = smoothStep((swapAt - OVERLAY_BASS_SWAP_WIDTH).coerceAtLeast(0f),
            (swapAt + OVERLAY_BASS_SWAP_WIDTH).coerceAtMost(1f), p)
        val release = smoothStep((handoff - 0.04f).coerceAtLeast(0f), 1f, p)
        val clash = render.vocalOverlap.toFloat().coerceIn(0f, 1f)

        /*
         * INTRO_BED must sound like an instrumental entering, not like a muted deck that suddenly
         * switches on. Keep the protective carve at the start, then progressively restore B's
         * body as its intro develops. A's EQ remains almost untouched until the final ownership
         * change, so clear vocals in A still read as foreground.
         */
        val bodyOpenEnd = minOf(0.84f, (handoff - 0.08f).coerceAtLeast(0.55f))
        val bodyOpen = smoothStep(0.16f, bodyOpenEnd, p)
        val incomingLow = -OVERLAY_BED_LOW_CUT_DB * (1f - swap)
        val outgoingLow = -OVERLAY_LOW_CUT_DB * swap
        val incomingMidBed = -(OVERLAY_MID_CARVE_DB + OVERLAY_MID_CLASH_EXTRA_DB * clash)
        val progressiveMidCarve = (1f - OVERLAY_MID_BUILD_OPEN * bodyOpen).coerceIn(0.28f, 1f)
        val incomingMid = incomingMidBed * progressiveMidCarve * (1f - release)
        val incomingHigh = -OVERLAY_HIGH_CARVE_DB *
            (1f - OVERLAY_HIGH_BUILD_OPEN * bodyOpen).coerceIn(0.35f, 1f) *
            (1f - release)
        val outgoingMid = -(OVERLAY_OUTGOING_MID_DUCK_DB * clash) * release
        val outgoingHigh = -OVERLAY_OUTGOING_HIGH_DUCK_DB * release

        filters.incomingEq(incomingLow, incomingMid, incomingHigh)
        if (realStemActive) stemDeck()?.setEq(incomingLow, incomingMid, incomingHigh)
        filters.outgoingEq(outgoingLow, outgoingMid, outgoingHigh)
    }

    /** Ramps the outgoing track away rather than cutting it, so an interruption has no click in it. */
    private fun driveBail() {
        val out = outgoing
        if (out == null) {
            finish()
            return
        }
        val progress = (SystemClock.elapsedRealtime() - bailStartedAt).toFloat() / BAIL_MS
        if (progress < 1f) {
            out.volume = bailFromGain * fallGain(progress)
            return
        }
        finish()
    }

    // ---- Lifecycle of a transition -----------------------------------------

    /**
     * Abandons whatever is in flight.
     *
     * What has to be put back depends entirely on whether [startFade] got as far
     * as swapping the roles. Before the handoff the session player is untouched;
     * the standby may already be a quiet audible bed, but it has not become the
     * authoritative record, so [finish] can retire it and leave A uninterrupted.
     * After the handoff the session has
     * already moved and cannot be moved back (the incoming track is playing and
     * has been announced), so the only thing left is to take the outgoing track
     * away gracefully.
     */
    private fun bail() {
        if (phase == Phase.IDLE || phase == Phase.BAILING) return
        Log.d(TAG, "bail from $phase")
        AppSettings.smartMixInProgress.value = false
        if (!handedOff) {
            // B may already be a low bed, but A still owns the foreground. Drop
            // the standby and keep the authoritative deck uninterrupted.
            finish()
            return
        }
        // Glided open rather than snapped: the incoming track is audible here,
        // and if the bail caught a bass swap mid-handover its low end is
        // currently lifted out. Dropping a 24 dB/octave filter in one buffer is
        // the click this ramp exists to avoid.
        filters.open()
        incoming?.volume = 1f
        bailFromGain = outgoing?.volume ?: 0f
        bailStartedAt = SystemClock.elapsedRealtime()
        phase = Phase.BAILING
    }

    private fun finish() {
        val completedSmartMix = smartFadeActive && handedOff && phase == Phase.FADING
        val winner = incoming
        val incomingRateToRelease = winner?.playbackParameters?.speed?.toDouble()
            ?.div(AppSettings.playbackSpeed.value.toDouble().coerceAtLeast(0.01))
            ?: incomingPlaybackRate
        val pitchNeedsRelease = winner?.playbackParameters?.pitch?.let { abs(it - 1f) > 0.0001f } == true
        if (phase != Phase.IDLE) {
            Log.d(TAG, "finish from $phase")
            // Stamped under this guard rather than beside the assignment at the
            // bottom, because this function is idempotent and gets called with
            // nothing in flight: marking every one of those as a transition
            // just ended would keep pushing the mark forward and hold a waiting
            // caller off for as long as the calls kept coming.
            settledAt = SystemClock.elapsedRealtime()
        }
        AppSettings.smartMixInProgress.value = false
        AppSettings.automixVisualTransition.value = null
        // Unconditional and idempotent, like the speed reset below: correct
        // whether or not this transition ever filtered anything.
        filters.open()
        outgoing?.skipSilenceEnabled = AppSettings.skipSilence.value
        incoming?.skipSilenceEnabled = AppSettings.skipSilence.value
        stemDeck()?.reset()
        realStemActive = false
        realStemWanted = false
        realStemReleaseAligned = false
        phaseLockPeakErrorMs = 0.0
        phaseLockLogged = false
        render = Render()

        if (handedOff) {
            // The roles have already traded: the incoming player is the session
            // and owns the queue from here, and the outgoing one is spare.
            incoming?.let {
                it.volume = 1f
                // Keep the winning deck on its matched rate for one instant longer; if it was
                // stretched, release it smoothly after A has gone instead of snapping tempo at
                // the exact seam the listener is paying attention to.
                if (kotlin.math.abs(incomingRateToRelease - 1.0) < RATE_EPSILON && kotlin.math.abs(it.playbackParameters.pitch - 1f) < 0.0001f) {
                    it.setPlaybackParameters(PlaybackParameters(AppSettings.playbackSpeed.value, 1f))
                }
            }
            outgoing?.let(::retire)
        } else {
            // The transition never became authoritative, so the session player never moved and
            // the standby is the one to throw away. Undo the temporary end-of-item guard so A can
            // continue through the untouched queue normally if the mix was abandoned.
            outgoing?.run {
                pauseAtEndOfMediaItems = outgoingPauseAtEndBeforeTransition
                volume = 1f
                setPlaybackParameters(PlaybackParameters(AppSettings.playbackSpeed.value, 1f))
            }
            incoming?.let(::retire)
        }

        outgoing = null
        incoming = null
        handedOff = false
        outgoingPauseAtEndBeforeTransition = false
        queuedItemCount = 0
        incomingCueTimeMs = 0L
        fadeStartMs = 0L
        outgoingPlaybackRate = 1.0
        incomingPlaybackRate = 1.0
        loopTarget = TransitionLoopTarget.NONE
        loopStartMs = 0L
        loopEndMs = 0L
        loopRepeatsRemaining = 0
        loopRepeatsPlanned = 0
        loopSeekBlockedUntilMs = 0L
        realStemActive = false
        realStemWanted = false
        realStemReleaseAligned = false
        mixElapsedMs = 0L
        mixClockAtMs = 0L
        smartFadeActive = false
        phase = Phase.IDLE
        if (completedSmartMix && winner != null && (kotlin.math.abs(incomingRateToRelease - 1.0) >= RATE_EPSILON || pitchNeedsRelease)) {
            releasePlaybackRateGradually(winner, incomingRateToRelease)
        }
        if (completedSmartMix) NerdStats.onAutomixCompleted()
    }

    /** Still a next track, still playing, still switched on — by whichever setting armed this one. */
    private fun stillWorthFading(): Boolean {
        val stillOn = if (smartFadeActive) AppSettings.smartFadeEnabled.value else configuredFadeMs() > 0L
        return stillOn && (outgoing ?: active()).hasNextMediaItem()
    }

    /** Returns B to its native/user tempo after A has completely left the mix. */
    private fun releasePlaybackRateGradually(player: ExoPlayer, relativeStart: Double) {
        val mediaId = player.currentMediaItem?.mediaId ?: run {
            player.setPlaybackParameters(PlaybackParameters(AppSettings.playbackSpeed.value, 1f))
            return
        }
        val pitchStart = player.playbackParameters.pitch.toDouble()
        scope.launch {
            repeat(TEMPO_RELEASE_STEPS) { index ->
                if (player.currentMediaItem?.mediaId != mediaId) return@launch
                val x = (index + 1f) / TEMPO_RELEASE_STEPS
                val eased = x * x * (3f - 2f * x)
                val relative = relativeStart + (1.0 - relativeStart) * eased
                player.setPlaybackParameters(PlaybackParameters((AppSettings.playbackSpeed.value * relative).toFloat(), (pitchStart + (1.0 - pitchStart) * eased).toFloat()))
                delay(TEMPO_RELEASE_STEP_MS)
            }
            if (player.currentMediaItem?.mediaId == mediaId) {
                player.setPlaybackParameters(PlaybackParameters(AppSettings.playbackSpeed.value, 1f))
            }
        }
    }

    /**
     * Puts a player back in the drawer: emptied, silent no longer, and on the
     * listener's own playback rate again.
     *
     * The volume matters as much as the emptying. A player left at the gain it
     * faded out on is the next transition's *incoming* player, and it would
     * arrive already turned down — so the reset is part of retiring it, not part
     * of preparing it.
     */
    private fun retire(player: ExoPlayer) {
        player.stop()
        player.clearMediaItems()
        player.volume = 1f
        player.setPlaybackParameters(PlaybackParameters(AppSettings.playbackSpeed.value, 1f))
    }

    // ---- Numbers ------------------------------------------------------------

    private fun configuredFadeMs(): Long = AppSettings.crossfadeSeconds.value * 1000L

    /**
     * The configured length, kept off tracks too short to spend it on. A fade
     * that swallows a third of a song stops being a transition and starts being
     * the arrangement.
     */
    private fun fadeFor(duration: Long): Long {
        val configured = configuredFadeMs()
        if (duration == C.TIME_UNSET || duration <= 0L) return configured
        return minOf(configured, duration / 3).coerceAtLeast(0L)
    }

    /**
     * Renders the plan's [TransitionStyle] as filtering across the blend.
     *
     * The gain curve is the same equal-power pair for every style — this is
     * what makes them sound different from each other, and it is the whole of
     * Phase 4. Driven off the same `progress` as the gains so the two stay
     * locked: a pause parks the filter exactly where it parks the fade.
     */
    private fun rideFilters(progress: Float) {
        when (render.style) {
            TransitionStyle.DJ_FILTER -> rideFilterSweep(progress)
            TransitionStyle.DJ_BLEND,
            TransitionStyle.EQ_SWAP,
            TransitionStyle.FOREGROUND_TAKEOVER,
            TransitionStyle.PHRASE_TAKEOVER ->
                if (render.bassSwap) rideBassSwap(progress) else rideVocalSeparation(progress)
            TransitionStyle.RUNWAY_BLEND -> {
                // Long runway is an arrangement overlay, not a long equal-power fade.
                // Keep A spectrally intact until the server's release point while B's
                // low/mid body opens progressively underneath it.
                filters.incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
                filters.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
                rideInstrumentalOverlayEq(progress)
            }
            TransitionStyle.INTRO_BRIDGE_FILTER -> {
                rideIntroBridgeFilter(progress)
                rideInstrumentalOverlayEq(progress)
            }
            // The modern instrumental overlay uses the three-band DJ EQ for a real low-end
            // handoff and vocal-space carve, while leaving the broad filter fully open.
            TransitionStyle.INTRO_BED -> {
                filters.incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
                filters.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
                if (realStemActive) {
                    stemDeck()?.setFilters(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
                }
                rideInstrumentalOverlayEq(progress)
            }
            // Both cut styles are too short for a filter/EQ gesture to sound intentional.
            TransitionStyle.CUT,
            TransitionStyle.PHRASE_CUT -> filters.open()
            // GAPLESS is an album being played through, where any filtering would
            // be an edit the record didn't ask for — so it stays open whatever
            // the material does.
            TransitionStyle.GAPLESS -> filters.open()
            // EQUAL_POWER used to be defined the same way: the bottom tier,
            // reached because the evidence was too weak to justify anything more
            // opinionated, therefore don't touch the spectrum.
            //
            // That conflated two different kinds of evidence. The tier is decided
            // by tempo and beat confidence; whether both tracks are singing is
            // measured by a separate model that doesn't depend on either. A pair
            // can have useless tempo evidence — dropping it to this tier — and a
            // perfectly good vocal mask on both sides saying they collide. Every
            // one of those transitions was rendered as a plain crossfade with two
            // full vocals over each other, because the weak half of the evidence
            // was silencing the strong half.
            TransitionStyle.EQUAL_POWER -> rideVocalSeparation(progress)
        }
    }

    /**
     * The minimum intervention: pull two colliding vocals apart, and otherwise
     * leave the spectrum alone.
     *
     * Not a filter ride. [rideFilterSweep] is a *style* — a gesture chosen for a
     * pair that cannot be blended flat, driving to [FILTER_FLOOR_HZ] and taking
     * the outgoing track somewhere distant. This is damage control on a pair that
     * was going to be crossfaded plainly, and it has to stay subtle enough that a
     * listener notices the absence of the clash rather than the presence of a
     * filter. So it works the same way — complementary bands, outgoing losing its
     * top while the incoming enters with its body lifted — over a much shorter
     * distance, and only as far as the measured collision justifies.
     *
     * Zero overlap leaves both sides open, which is exactly what these styles did
     * before, so nothing changes for a pair that doesn't collide or for either
     * track lacking a vocal mask.
     */
    private fun rideVocalSeparation(progress: Float) {
        val amount = render.vocalOverlap.coerceIn(0.0, 1.0)
        if (amount <= 0.0) {
            filters.open()
            if (realStemActive) {
                stemDeck()?.setFilters(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
            }
            return
        }
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        // Both endpoints scaled by the collision, so a marginal clash is nudged
        // and a full one is properly separated, rather than everything getting
        // the same treatment at different speeds.
        val floor = glide(open, VOCAL_SEPARATION_FLOOR_HZ, amount)
        filters.outgoing(
            glide(open, floor, progress.toDouble().pow(FILTER_SWEEP_SHAPE)).toFloat(),
            TransitionFilterProcessor.OFF_HZ,
        )
        filters.incoming(
            TransitionFilterProcessor.OPEN_HZ,
            entryHighPass(progress, amount, VOCAL_SEPARATION_HIGH_PASS_HZ, ENTRY_OPEN_BY),
        )
    }

    /**
     * Pulls the outgoing track behind a closing low-pass while the incoming one
     * arrives with its body lifted out, for a pair too far apart in tempo to
     * blend flat.
     *
     * ## Why both sides are filtered
     *
     * The first version filtered only the outgoing track, and squared the
     * progress so that the sweep was spent almost entirely in the second half.
     * Both halves of that were wrong for the same reason: at the midpoint the
     * outgoing cutoff was still at 6.9kHz — wide open across the whole vocal
     * range — and the incoming track was explicitly set to no filtering at all.
     * So for the entire first half of every transition, two complete vocals
     * played over each other at comparable level, and the only thing
     * distinguishing them was gain. That is what a plain crossfade sounds like,
     * which is the one thing this is meant not to be.
     *
     * What a DJ does instead is hand the midrange over rather than double it:
     * the outgoing track starts losing its top the moment the blend begins, and
     * the incoming one enters high-passed — hats and presence only, no vocal
     * body — opening out as the outgoing track darkens. The two occupy
     * complementary bands through the middle of the blend and never compete for
     * the range a voice lives in.
     *
     * [FILTER_SWEEP_SHAPE] is what replaces the squaring: front-loaded now, so
     * the outgoing track's top is gone within the first tenth of the blend
     * rather than somewhere past the midpoint. What keeps that from gutting the
     * track being left is [FILTER_FLOOR_HZ] — the ride settles onto a 300Hz bed
     * and stays there — not restraint in the early travel, which is the part the
     * listener reads as the transition happening at all.
     */
    /**
     * Subtle carve for a long intro bed. This is intentionally far gentler than
     * DJ_FILTER: A remains recognizably full while B is quiet, but a little
     * top/body is opened up so a dense instrumental intro can be perceived at
     * 10–20% without simply turning B louder.
     */
    private fun rideIntroBridgeFilter(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val release = render.handoffFraction.coerceIn(0.55, 0.995).toFloat()
        val amount = render.filterSweep.coerceIn(0.0, 0.55)
        if (amount <= 0.0) {
            filters.open()
            return
        }

        val carve = smoothStep(0.08f, release.coerceAtLeast(0.20f), p).toDouble() * amount
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val outgoingFloor = glide(open, INTRO_BRIDGE_OUTGOING_LOW_PASS_HZ, carve)
        val incomingHigh = glide(
            TransitionFilterProcessor.OFF_HZ.toDouble(),
            INTRO_BRIDGE_INCOMING_HIGH_PASS_HZ,
            (amount * (1.0 - smoothStep(0f, release, p))).coerceIn(0.0, 1.0),
        )
        filters.outgoing(outgoingFloor.toFloat(), TransitionFilterProcessor.OFF_HZ)
        filters.incoming(TransitionFilterProcessor.OPEN_HZ, incomingHigh.toFloat())
        if (realStemActive) {
            stemDeck()?.setFilters(TransitionFilterProcessor.OPEN_HZ, incomingHigh.toFloat())
        }
    }

    private fun rideFilterSweep(progress: Float) {
        val sweep = render.filterSweep.coerceIn(0.0, 1.0)
        if (sweep <= 0.0) {
            filters.open()
            return
        }
        // Both ends scaled by [filterSweep], so a partial sweep engages less
        // sharply *and* stops short of the floor rather than crawling the same
        // distance more slowly.
        val open = TransitionFilterProcessor.OPEN_HZ.toDouble()
        val entry = glide(open, FILTER_ENTRY_HZ, sweep)
        val floor = glide(open, FILTER_FLOOR_HZ, sweep)
        // Finish the outgoing sweep before the gain cut. This leaves a short
        // fully-filtered tail at unity gain instead of fading A away together
        // with the filter; the renderer then performs a single micro-cut.
        val outgoingSweepProgress = (progress / DJ_FILTER_SWEEP_END).coerceIn(0f, 1f)
        val cutoff = glide(entry, floor, outgoingSweepProgress.toDouble().pow(FILTER_SWEEP_SHAPE))
        filters.outgoing(cutoff.toFloat(), TransitionFilterProcessor.OFF_HZ)
        filters.incoming(
            TransitionFilterProcessor.OPEN_HZ,
            entryHighPass(progress, sweep, ENTRY_HIGH_PASS_HZ, ENTRY_OPEN_BY),
        )
    }

    /**
     * Where the incoming track's high-pass sits at [progress].
     *
     * Rides from [topHz] down to nothing by [openBy] of the fade, so the track
     * is whole well before it is alone — the filter is there to keep it out of
     * the outgoing vocal's way during the overlap, not to colour the track the
     * listener is left with. [amount] scales the whole gesture, so a partial
     * sweep lifts proportionally less out.
     *
     * [ENTRY_SHAPE] is why the descent isn't linear. A geometric glide runs from
     * [TransitionFilterProcessor.OFF_HZ] to [topHz], and the bottom half of that
     * range is sub-bass nobody hears a filter in: measured, a plain ride was
     * down to 123Hz by a third of the way through, which is to say doing nothing
     * at all for two thirds of the overlap. The exponent spends the travel where
     * a voice actually is — 772Hz at a sixth of the way in, 436Hz at a third —
     * and still arrives at fully open on time.
     */
    private fun entryHighPass(progress: Float, amount: Double, topHz: Double, openBy: Double): Float {
        val remaining = (1.0 - progress / openBy).coerceIn(0.0, 1.0)
        return glide(TransitionFilterProcessor.OFF_HZ.toDouble(), topHz, amount * remaining.pow(ENTRY_SHAPE))
            .toFloat()
    }

    /**
     * Geometric interpolation between two cutoffs: [amount] 0 gives [from], 1
     * gives [to].
     *
     * Geometric rather than linear because pitch is logarithmic — a cutoff
     * moving in equal Hz steps sounds like it lurches through the bottom of its
     * range and crawls through the top.
     */
    private fun glide(from: Double, to: Double, amount: Double): Double =
        from * (to / from).pow(amount.coerceIn(0.0, 1.0))

    /**
     * Hands the low end from one track to the other, once, at the beat the
     * planner chose.
     *
     * Below [BASS_SWAP_HZ] exactly one track is present at any instant: the
     * incoming track arrives with its low end lifted out, and takes it over as
     * the outgoing track's is lifted in turn. Ramped over [BASS_SWAP_WIDTH] of
     * the fade rather than switched, because a 24 dB/octave filter appearing in
     * one buffer is a transient of its own.
     *
     * The midrange is handled far more lightly than in [rideFilterSweep] but is
     * no longer left alone, which it was. This style is chosen for pairs that
     * are beat-matched and close in tempo, so the two tracks are *meant* to
     * sound simultaneous — but "simultaneous" and "two lead vocals at once" are
     * not the same thing, and only the bass was ever being separated. So the
     * incoming track still enters with its body lifted, over a shorter window
     * and from a lower corner, and the outgoing track loses its top in the last
     * half, where it is already quiet enough that the change reads as it
     * receding rather than as an effect.
     */
    private fun rideBassSwap(progress: Float) {
        val swapAt = render.bassSwapFraction.coerceIn(0.05, 0.95)
        // 0 before the swap window, 1 after it: how much of the low end has
        // changed hands.
        val handover = ((progress - swapAt) / BASS_SWAP_WIDTH * 0.5 + 0.5).coerceIn(0.0, 1.0)
        // The incoming track's own low end is already being held out by the
        // swap, so whichever corner sits higher is the one doing the work.
        // Scaled up by however much the two are actually singing over each other.
        // A blend is chosen for pairs on a shared grid, which is the case where
        // nothing about the arrangement separates two lead vocals — they sit in
        // the same bar and the same range for the whole overlap — so the fixed
        // corner that was here handled a marginal collision and a head-on one
        // identically. At full collision the entry corner reaches
        // [BLEND_ENTRY_CLASH_HIGH_PASS_HZ] and holds longer.
        val clash = render.vocalOverlap.coerceIn(0.0, 1.0)
        val entry = maxOf(
            bassCutoff(1.0 - handover),
            entryHighPass(
                progress,
                1.0,
                glide(BLEND_ENTRY_HIGH_PASS_HZ, BLEND_ENTRY_CLASH_HIGH_PASS_HZ, clash),
                BLEND_ENTRY_OPEN_BY + (BLEND_ENTRY_CLASH_OPEN_BY - BLEND_ENTRY_OPEN_BY) * clash,
            ),
        )
        filters.incoming(TransitionFilterProcessor.OPEN_HZ, entry)
        filters.outgoing(blendExitLowPass(progress, clash), bassCutoff(handover))
    }

    /**
     * The outgoing track's low-pass through a beat-matched blend: open until
     * [BLEND_EXIT_FROM], then closing to [BLEND_EXIT_LOW_PASS_HZ] by the end.
     *
     * Deliberately shallow. Enough to take the air and the sibilance off a voice
     * that is on its way out, so it stops competing with the one arriving;
     * nowhere near the [FILTER_FLOOR_HZ] that [rideFilterSweep] drives to, which
     * would contradict the reason this style was chosen.
     *
     * [clash] both starts it earlier and takes it further, because "shallow" is
     * the right default and the wrong answer for two choruses landing together.
     */
    private fun blendExitLowPass(progress: Float, clash: Double): Float {
        val from = BLEND_EXIT_FROM + (BLEND_EXIT_CLASH_FROM - BLEND_EXIT_FROM) * clash
        val amount = ((progress - from) / (1.0 - from)).coerceIn(0.0, 1.0)
        val floor = glide(BLEND_EXIT_LOW_PASS_HZ, BLEND_EXIT_CLASH_LOW_PASS_HZ, clash)
        return glide(TransitionFilterProcessor.OPEN_HZ.toDouble(), floor, amount).toFloat()
    }

    /** [amount] 0 leaves the low end alone; 1 lifts it out entirely. */
    private fun bassCutoff(amount: Double): Float =
        glide(TransitionFilterProcessor.OFF_HZ.toDouble(), BASS_SWAP_HZ, amount).toFloat()

    /**
     * Whether the transition in flight is doing something a plain crossfade
     * could not — which is what [AppSettings.smartMixInProgress] promises the
     * listener when it lights the scrubber up.
     *
     * Any analysis-driven treatment qualifies: filtering/bass handoff, the
     * long instrumental-intro bed, the phrase cut, an incoming track cued
     * into its arrangement instead of its first frame, or a tempo stretch. The
     * case this exists to exclude is the fallback — an unanalysed pair, cued at
     * 0:00, fading equal-power — which is indistinguishable from what the app
     * did before Automix existed and would be a lie to advertise.
     */
    private fun isRealMix(): Boolean = smartFadeActive && (
        render.style == TransitionStyle.DJ_BLEND ||
            render.style == TransitionStyle.EQ_SWAP ||
            render.style == TransitionStyle.DJ_FILTER ||
            render.style == TransitionStyle.INTRO_BED ||
            render.style == TransitionStyle.INTRO_BRIDGE_FILTER ||
            render.style == TransitionStyle.RUNWAY_BLEND ||
            render.style == TransitionStyle.FOREGROUND_TAKEOVER ||
            render.style == TransitionStyle.PHRASE_TAKEOVER ||
            render.style == TransitionStyle.PHRASE_CUT ||
            render.style == TransitionStyle.CUT ||
            incomingCueTimeMs > 0L ||
            incomingPlaybackRate != 1.0
        )

    /** Cubic ease with zero slope at both ends, used only for gain choreography. */
    private fun smoothStep(from: Float, to: Float, value: Float): Float {
        if (to <= from) return if (value >= to) 1f else 0f
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Interpolates the planner's activity-aware gain automation. */
    private fun introBedGainAt(
        progress: Float,
        envelope: List<TransitionGainPoint>,
    ): Pair<Float, Float>? {
        if (envelope.size < 2) return null
        val p = progress.toDouble().coerceIn(0.0, 1.0)
        val sorted = envelope
        if (p <= sorted.first().progress) {
            val point = sorted.first()
            return point.incomingGain.toFloat() to point.outgoingGain.toFloat()
        }
        if (p >= sorted.last().progress) {
            val point = sorted.last()
            return point.incomingGain.toFloat() to point.outgoingGain.toFloat()
        }
        for (index in 1 until sorted.size) {
            val right = sorted[index]
            if (p > right.progress) continue
            val left = sorted[index - 1]
            val span = (right.progress - left.progress).coerceAtLeast(1e-6)
            val t = smoothStep(0f, 1f, ((p - left.progress) / span).toFloat()).toDouble()
            val incoming = left.incomingGain + (right.incomingGain - left.incomingGain) * t
            val outgoing = left.outgoingGain + (right.outgoingGain - left.outgoingGain) * t
            return incoming.toFloat().coerceIn(0f, 1f) to outgoing.toFloat().coerceIn(0f, 1f)
        }
        return null
    }

    /** Families whose identity depends on A and B sharing a beat grid. */
    private fun usesTempoBridgeStyle(): Boolean {
        if (!render.automix25) return false
        if (render.tempoEnvelope.isNotEmpty()) return true
        return when (render.style) {
            TransitionStyle.RUNWAY_BLEND,
            TransitionStyle.PHRASE_TAKEOVER,
            TransitionStyle.DJ_BLEND,
            TransitionStyle.DJ_FILTER,
            TransitionStyle.EQ_SWAP -> true
            else -> false
        }
    }

    /**
     * Follow the server tempo curves and bounded harmonic correction through the overlap.
     */
    private fun rideTempoBridge(progress: Float, out: ExoPlayer, into: ExoPlayer) {
        val base = AppSettings.playbackSpeed.value.toDouble().coerceAtLeast(0.01)
        val handoff = render.handoffFraction.toFloat().coerceIn(0.42f, 0.97f)
        val releaseStart = maxOf(TEMPO_RELEASE_MIN, (handoff + 0.06f).coerceAtMost(0.92f))
        val release = smoothStep(
            ((progress - releaseStart) / (1f - releaseStart)).coerceIn(0f, 1f),
        ).toDouble()

        if (!render.automix25) {
            val release = smoothStep(releaseStart, 1f, progress).toDouble()
            out.setPlaybackSpeed((base * outgoingPlaybackRate).toFloat())
            into.setPlaybackSpeed((base * (incomingPlaybackRate + (1.0 - incomingPlaybackRate) * release)).toFloat())
            return
        }
        val frame = tempoFrameAt(progress)
        val curveOutRate = (frame?.outgoingRate ?: outgoingPlaybackRate).coerceIn(0.94, 1.06)
        val curveInRate = (frame?.incomingRate ?: incomingPlaybackRate).coerceIn(0.94, 1.06)
        val outBpm = frame?.outgoingBpm ?: 0.0
        val inBpm = frame?.incomingBpm ?: 0.0

        setDeckPlayback(out, speed = base * curveOutRate, pitchSemitones = 0.0)

        val nominalIncomingRate = curveInRate + (1.0 - curveInRate) * release
        val phaseCorrection = phaseLockCorrection(
            progress = progress,
            releaseStart = releaseStart,
            baseRate = base,
            out = out,
            into = into,
            outgoingRate = curveOutRate,
            incomingRate = curveInRate,
            outgoingBpm = outBpm,
            incomingBpm = inBpm,
        )
        val correctedIncomingRate = (nominalIncomingRate * (1.0 + phaseCorrection))
            .coerceIn(PHASE_LOCK_MIN_RATE, PHASE_LOCK_MAX_RATE)
        val pitchSemitones = render.incomingPitchSemitones.coerceIn(-1.0, 1.0) * (1.0 - release)
        setDeckPlayback(
            into,
            speed = base * correctedIncomingRate,
            pitchSemitones = pitchSemitones,
        )
    }

    private fun phaseLockCorrection(
        progress: Float,
        releaseStart: Float,
        baseRate: Double,
        out: ExoPlayer,
        into: ExoPlayer,
        outgoingRate: Double,
        incomingRate: Double,
        outgoingBpm: Double,
        incomingBpm: Double,
    ): Double {
        val beatMatchedStyle = when (render.style) {
            TransitionStyle.RUNWAY_BLEND,
            TransitionStyle.PHRASE_TAKEOVER,
            TransitionStyle.DJ_BLEND,
            TransitionStyle.DJ_FILTER,
            TransitionStyle.EQ_SWAP -> true
            else -> false
        }
        if (!smartFadeActive || !beatMatchedStyle || progress >= releaseStart) return 0.0

        val transitionStartMs = fadeStartMs.coerceAtLeast(0L)
        val outMediaElapsed = (out.currentPosition - transitionStartMs).coerceAtLeast(0L).toDouble()
        val inMediaElapsed = (into.currentPosition - incomingCueTimeMs).coerceAtLeast(0L).toDouble()

        val errorMs = if (outgoingBpm > 0.0 && incomingBpm > 0.0) {
            val outBeats = outMediaElapsed * outgoingBpm / 60_000.0
            val inBeats = inMediaElapsed * incomingBpm / 60_000.0
            var cycleError = inBeats - outBeats
            cycleError -= kotlin.math.floor(cycleError + 0.5)
            val effectiveOutBpm = outgoingBpm * outgoingRate
            val effectiveInBpm = incomingBpm * incomingRate
            val meetingBpm = ((effectiveOutBpm + effectiveInBpm) * 0.5).coerceAtLeast(1.0)
            cycleError * (60_000.0 / meetingBpm)
        } else {
            val outElapsedMs = outMediaElapsed / (baseRate * outgoingRate).coerceAtLeast(0.01)
            val inElapsedMs = inMediaElapsed / (baseRate * incomingRate).coerceAtLeast(0.01)
            inElapsedMs - outElapsedMs
        }
        phaseLockPeakErrorMs = maxOf(phaseLockPeakErrorMs, abs(errorMs))

        if (!phaseLockLogged && progress >= PHASE_LOCK_LOG_PROGRESS) {
            phaseLockLogged = true
            Log.d(
                TAG,
                "phase lock initial=${"%.1f".format(errorMs)}ms " +
                    "peak=${"%.1f".format(phaseLockPeakErrorMs)}ms " +
                    "curve=${render.tempoEnvelope.isNotEmpty()} " +
                    "pitch=${"%.2f".format(render.incomingPitchSemitones)}st",
            )
        }

        if (abs(errorMs) <= PHASE_LOCK_DEADBAND_MS) return 0.0
        return (-errorMs / PHASE_LOCK_CAPTURE_MS)
            .coerceIn(-PHASE_LOCK_MAX_DELTA, PHASE_LOCK_MAX_DELTA)
    }

    private fun tempoFrameAt(progress: Float): TransitionTempoPoint? {
        val points = render.tempoEnvelope
            .filter {
                it.progress.isFinite() &&
                    it.outgoingRate.isFinite() &&
                    it.incomingRate.isFinite() &&
                    it.outgoingBpm.isFinite() &&
                    it.incomingBpm.isFinite()
            }
            .sortedBy { it.progress }
        if (points.isEmpty()) return null
        val p = progress.coerceIn(0f, 1f).toDouble()
        if (p <= points.first().progress) return points.first()
        if (p >= points.last().progress) return points.last()
        val rightIndex = points.indexOfFirst { it.progress >= p }.coerceAtLeast(1)
        val left = points[rightIndex - 1]
        val right = points[rightIndex]
        val span = (right.progress - left.progress).coerceAtLeast(1e-6)
        val amount = ((p - left.progress) / span).coerceIn(0.0, 1.0)
        fun lerp(a: Double, b: Double): Double = a + (b - a) * amount
        return TransitionTempoPoint(
            progress = p,
            outgoingRate = lerp(left.outgoingRate, right.outgoingRate),
            incomingRate = lerp(left.incomingRate, right.incomingRate),
            outgoingBpm = lerp(left.outgoingBpm, right.outgoingBpm),
            incomingBpm = lerp(left.incomingBpm, right.incomingBpm),
            confidence = lerp(left.confidence, right.confidence),
        )
    }

    private fun setDeckPlayback(
        player: ExoPlayer,
        speed: Double,
        pitchSemitones: Double,
    ) {
        val safeSpeed = speed.coerceIn(0.25, 4.0).toFloat()
        val safeSemitones = pitchSemitones.coerceIn(-1.0, 1.0)
        val pitch = 2.0.pow(safeSemitones / 12.0).toFloat()
        player.setPlaybackParameters(PlaybackParameters(safeSpeed, pitch))
    }

    private fun smoothStep(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * A DJ blend is a layered handoff, not a symmetric constant-power dissolve. A stays almost
     * full while B establishes the shared grid and low-end swap; only after the authority point
     * does A leave and B finish its rise.
     */
    private fun djBlendIncomingGain(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val handoff = render.handoffFraction.toFloat().coerceIn(0.48f, 0.84f)
        val entry = DJ_BLEND_BED_GAIN * smoothStep(0f, DJ_BLEND_ENTRY_BY, p)
        val build = smoothStep(DJ_BLEND_ENTRY_BY, handoff, p)
        val staged = entry + (DJ_BLEND_HANDOFF_GAIN - entry) * build
        val takeover = smoothStep(handoff, 1f, p)
        return (staged + (1f - staged) * takeover).coerceIn(0f, 1f)
    }

    private fun djBlendOutgoingGain(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val handoff = render.handoffFraction.toFloat().coerceIn(0.48f, 0.84f)
        val hold = 1f - DJ_BLEND_PRE_HANDOFF_DUCK * smoothStep(0f, handoff, p)
        val leave = smoothStep(handoff, 1f, p)
        return (hold * (1f - leave)).coerceIn(0f, 1f)
    }

    private fun djFilterIncomingGain(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val early = DJ_FILTER_EARLY_GAIN * smoothStep(0f, DJ_FILTER_SWEEP_END, p)
        val finish = smoothStep(DJ_FILTER_SWEEP_END, 1f, p)
        return (early + (1f - early) * finish).coerceIn(0f, 1f)
    }

    /**
     * Conservative fallback when the planner has no activity evidence. B rises
     * only to a quiet bed while A remains foreground, then opens after handoff.
     */
    private fun introBedEntryFraction(): Float {
        if (fadeMs <= 0L) return INTRO_BED_MAX_IN_FRACTION
        // A 70-80 second instrumental bed must not take 18 seconds merely to
        // become audible.  Rise over a fixed musical-feeling interval, while
        // retaining the old proportional behaviour for shorter beds.
        return (INTRO_BED_RISE_MS.toFloat() / fadeMs.toFloat()).coerceIn(
            INTRO_BED_MIN_IN_FRACTION,
            INTRO_BED_MAX_IN_FRACTION,
        )
    }

    private fun introBedIncomingGain(progress: Float, handoffFrom: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val release = handoffFrom.coerceIn(INTRO_BED_MIN_HANDOFF_FROM, INTRO_BED_MAX_HANDOFF_FROM)
        val entry = introBedEntryFraction()

        // BED: quick but soft 0 -> 10%.
        val attack = INTRO_BED_GAIN * smoothStep(0f, entry, p)

        // BUILD: do not park B at 10-20% for the entire intro. Let it grow to a clearly audible
        // instrumental layer while A remains foreground.
        val finalRiseFraction = if (fadeMs > 0L) {
            (INTRO_BED_FINAL_RISE_MS.toFloat() / fadeMs.toFloat()).coerceIn(0.04f, 0.24f)
        } else {
            0.10f
        }
        val finalRiseStart = minOf(
            release,
            (1f - finalRiseFraction).coerceIn(0.60f, 0.985f),
        )
        val buildStart = (entry * 0.80f).coerceAtMost(finalRiseStart - 0.02f)
        val build = smoothStep(buildStart, finalRiseStart, p)
        var staged = attack + (INTRO_BED_BUILD_GAIN - INTRO_BED_GAIN) * build
        staged = staged.coerceIn(0f, INTRO_BED_BUILD_GAIN)

        // IMPACT: several seconds before B's structural arrival, finish the gain rise progressively.
        val takeover = smoothStep(finalRiseStart, 1f, p)
        return (staged + (1f - staged) * takeover).coerceIn(0f, 1f)
    }

    private fun introBedOutgoingGain(progress: Float, handoffFrom: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val release = handoffFrom.coerceIn(INTRO_BED_MIN_HANDOFF_FROM, INTRO_BED_MAX_HANDOFF_FROM)
        val bedDuck = 1f - INTRO_BED_DUCK * smoothStep(0f, introBedEntryFraction(), p)

        // B may build for a long time, but A does not fade with it. Give A its own short final
        // release window so the end sounds like a handoff rather than a hard cut.
        val releaseFraction = if (fadeMs > 0L) {
            (INTRO_BED_OUTGOING_RELEASE_MS.toFloat() / fadeMs.toFloat()).coerceIn(0.02f, 0.18f)
        } else {
            0.05f
        }
        val leaveStart = minOf(
            release,
            (1f - releaseFraction).coerceIn(0.72f, 0.992f),
        )
        val leave = smoothStep(leaveStart, 1f, p)
        return (bedDuck * (1f - leave)).coerceIn(0f, 1f)
    }

    /**
     * Long, subtle A -> B artwork handoff.
     *
     * The cover is allowed to acknowledge B from the first seconds of an
     * INTRO_BED, but only as a thin top-down reveal while A remains dominant.
     * Identity, lyrics and the lower player surface stay with A much longer.
     * The reveal accelerates near the real foreground handoff and reaches 100%
     * only when B has actually completed the takeover.
     */
    private fun introBedVisualProgress(progress: Float, _handoffFrom: Float): Float {
        // The cover wipe is intentionally continuous for the *whole* mix.
        // Earlier versions revealed a thin top strip, almost parked there for
        // the bed, then rushed the rest at takeover. That read as an animation
        // freezing. The incoming playhead is already the authoritative clock,
        // so a direct monotonic mapping gives exactly what the eye expects:
        // once B appears at the top, the boundary keeps descending on every
        // controller tick and reaches the bottom only as the mix completes.
        return progress.coerceIn(0f, 1f)
    }

    /**
     * EQ_SWAP keeps both records more stable in level than a conventional
     * crossfade. The bass processors do the perceptual handoff first; gain only
     * finishes the change after that swap has landed.
     */
    private fun eqSwapIncomingGain(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val swap = render.bassSwapFraction.toFloat().coerceIn(0.20f, 0.82f)
        val pre = 0.42f * smoothStep(0f, swap, p)
        val post = smoothStep(swap, 1f, p)
        return (pre + (1f - pre) * post).coerceIn(0f, 1f)
    }

    private fun eqSwapOutgoingGain(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val swap = render.bassSwapFraction.toFloat().coerceIn(0.20f, 0.82f)
        val settle = 1f - 0.08f * smoothStep(0f, swap, p)
        val leave = smoothStep(swap, 1f, p)
        return (settle * (1f - leave)).coerceIn(0f, 1f)
    }

    /**
     * Phrase cut puts the discontinuity exactly around the planner's structural
     * anchor rather than around a hard-coded midpoint. The envelope is still
     * short enough to read as a cut, but independent decoders never hard-switch.
     */
    private fun phraseCutIncomingGain(progress: Float, handoff: Float): Float {
        val h = handoff.coerceIn(0.30f, 0.88f)
        return smoothStep((h - 0.10f).coerceAtLeast(0f), (h + 0.05f).coerceAtMost(1f), progress)
    }

    private fun phraseCutOutgoingGain(progress: Float, handoff: Float): Float {
        val h = handoff.coerceIn(0.30f, 0.88f)
        return 1f - smoothStep((h - 0.04f).coerceAtLeast(0f), (h + 0.12f).coerceAtMost(1f), progress)
    }

    /**
     * DJ_FILTER keeps A at unity gain through the filter ride and its short
     * filtered tail. The final move is a click-safe micro-cut, not a long fade.
     * The cut duration is time based so long Automix windows do not turn it back
     * into an audible fade-out.
     */
    private fun djFilterOutgoingGain(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        if (fadeMs <= 0L) return if (p < DJ_FILTER_CUT_END) 1f else 0f
        val cutFraction = (DJ_FILTER_CUT_MS.toFloat() / fadeMs.toFloat())
            .coerceIn(DJ_FILTER_MIN_CUT_FRACTION, DJ_FILTER_MAX_CUT_FRACTION)
        val cutStart = (DJ_FILTER_CUT_END - cutFraction)
            .coerceAtLeast(DJ_FILTER_SWEEP_END)
        return 1f - smoothStep(cutStart, DJ_FILTER_CUT_END, p)
    }

    /** Tight center-weighted microfade used by the remote CUT treatment. */
    private fun cutIncomingGain(progress: Float): Float =
        smoothStep(CUT_IN_FROM, CUT_IN_BY, progress.coerceIn(0f, 1f))

    private fun cutOutgoingGain(progress: Float): Float =
        1f - smoothStep(CUT_OUT_FROM, CUT_OUT_BY, progress.coerceIn(0f, 1f))

    /** Equal-power pair: [riseGain]² + [fallGain]² = 1, so the blend never dips. */
    private fun riseGain(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    private fun fallGain(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    // There is deliberately no second, equal-gain pair here any more. It existed
    // for the handoff of a track from one player to the other, where the two
    // signals were the same signal and so summed in amplitude rather than in
    // power. Nothing in this class renders the same audio twice now, so every
    // gain it applies is a gain against a genuinely different track, and
    // equal-power is right everywhere.

    private companion object {
        const val TAG = "BitChordCrossfade"

        /**
         * Used only before a pair has been analysed, or when the evidence is
         * too weak for more than a plain fade — see [considerSmartTransition].
         * Once real analysis lands, the overlap is sized from tempo and
         * structure instead and this is never read.
         */
        /** Safe fallback while musical analysis is unavailable or still arriving. */
        const val MIN_SMART_FALLBACK_SECONDS = 12.0
        const val DEFAULT_SMART_FALLBACK_SECONDS = 12.0

        // Spotify-reference gain choreography. B stays as a very quiet bed while
        // A's lead vocal remains the foreground. The real handoff point is
        // supplied by the planner from A's measured vocal release instead of a
        // fixed two-thirds marker.
        const val REAL_STEM_VOCAL_OVERLAP_TRIGGER = 0.06
        const val REAL_STEM_HANDOFF_TAIL_SECONDS = 2.0
        const val REAL_STEM_LOOP_TAIL_SECONDS = 0.5
        const val REAL_STEM_MIN_WINDOW_SECONDS = 8.0
        // The separated stem and B's authoritative playback rendition can come from different
        // codecs (analysis Opus/AAC vs Lossless). Keep their overlap deliberately short: long enough
        // to hide the timbral handoff, too short for codec phase differences to become audible as
        // flanging/echo.
        const val REAL_STEM_RELEASE_MS = 420L
        const val REAL_STEM_RELEASE_MIN_FRACTION = 0.020f
        const val REAL_STEM_RELEASE_MAX_FRACTION = 0.080f
        const val REAL_STEM_ALIGN_LEAD_FRACTION = 0.018f
        const val REAL_STEM_ALIGNMENT_TOLERANCE_MS = 250L

        const val INTRO_BED_GAIN = 0.10f
        const val INTRO_BED_DUCK = 0.015f
        const val INTRO_BED_RISE_MS = 8_000L
        const val INTRO_BED_PRE_HANDOFF_RISE_MS = 6_000L
        const val INTRO_BED_PRE_HANDOFF_GAIN = 0.20f
        // Fallback choreography mirrors the planner's BED -> BUILD -> IMPACT shape.
        const val INTRO_BED_BUILD_GAIN = 0.50f
        const val INTRO_BED_FINAL_RISE_MS = 5_000L
        const val INTRO_BED_OUTGOING_RELEASE_MS = 2_200L
        const val INCOMING_SOFT_START_MS = 1_600L
        const val TEMPO_PREP_MS = 4_000L
        const val TEMPO_RELEASE_AFTER_HANDOFF = 0.08f
        const val DJ_BLEND_ENTRY_BY = 0.18f
        const val DJ_BLEND_BED_GAIN = 0.22f
        const val DJ_BLEND_HANDOFF_GAIN = 0.72f
        const val DJ_BLEND_PRE_HANDOFF_DUCK = 0.02f
        const val DJ_FILTER_EARLY_GAIN = 0.68f
        const val RATE_EPSILON = 0.0008
        const val TEMPO_RELEASE_STEPS = 36
        const val TEMPO_RELEASE_STEP_MS = 70L
        const val MIX_CLOCK_MAX_STEP_MS = 250L
        const val LOOP_TRIGGER_EARLY_MS = 35L
        const val LOOP_SEEK_GUARD_MS = 160L

        // Instrumental-overlay DJ EQ. The arriving deck keeps its bass out until the measured
        // handoff; its midrange is carved more deeply when the vocal model reports a collision.
        // A quiet intro needs its musical body. Reserve the deep kill for A's
        // bass exit; applying it to B throughout the bed masked low-led intros.
        const val OVERLAY_BED_LOW_CUT_DB = 9f
        const val OVERLAY_LOW_CUT_DB = 24f
        const val OVERLAY_MID_CARVE_DB = 4.5f
        const val OVERLAY_MID_CLASH_EXTRA_DB = 5.5f
        const val OVERLAY_HIGH_CARVE_DB = 1.5f
        const val OVERLAY_MID_BUILD_OPEN = 0.68f
        const val OVERLAY_HIGH_BUILD_OPEN = 0.58f
        const val OVERLAY_OUTGOING_MID_DUCK_DB = 5f
        const val OVERLAY_OUTGOING_HIGH_DUCK_DB = 2.5f
        const val OVERLAY_BASS_SWAP_WIDTH = 0.075f

        // DJ_FILTER is a spectral handoff, not a gain fade. Close the filter
        // first, let that filtered tail remain at full gain briefly, then cut A.
        const val DJ_FILTER_SWEEP_END = 0.90f
        const val DJ_FILTER_CUT_END = 0.985f
        const val DJ_FILTER_CUT_MS = 90L
        const val DJ_FILTER_MIN_CUT_FRACTION = 0.006f
        const val DJ_FILTER_MAX_CUT_FRACTION = 0.05f
        const val INTRO_BED_MIN_IN_FRACTION = 0.06f
        const val INTRO_BED_MAX_IN_FRACTION = 0.24f
        const val INTRO_BED_MIN_HANDOFF_FROM = 0.55f
        const val INTRO_BED_MAX_HANDOFF_FROM = 0.985f

        // A CUT still overlaps for a fraction of a second. Concentrating both
        // gain moves around the midpoint makes it read as a phrase cut, not a fade.
        const val CUT_OUT_FROM = 0.34f
        const val CUT_OUT_BY = 0.58f
        const val CUT_IN_FROM = 0.30f
        const val CUT_IN_BY = 0.56f
        const val REMOTE_STYLE_CACHE_LIMIT = 32
        const val REMOTE_PLAN_RETRY_MS = 500L

        /** Ramp used when a fade is interrupted. */
        const val BAIL_MS = 120L

        /**
         * Head start the standby gets to open the incoming track and buffer to
         * its cue point.
         *
         * Sized for a *stream being opened*, which is the only thing arming
         * waits on now — there is no alignment to converge. Usually instant, as
         * the next track has normally been read ahead onto disk by the time it
         * matters, but a cold one has to be resolved and fetched, and a
         * transition that arrives before its incoming track is ready is one that
         * gets dropped.
         */
        // Pre-arm well before the musical entry so the standby can prove it
        // has a real audio runway, not merely Media3's fast-start 500 ms.
        const val STANDARD_ARM_LEAD_MS = 10_000L
        const val SMART_ARM_LEAD_MS = 16_000L
        // Cellular radio scheduling and handovers are burstier than Wi-Fi. Give
        // Automix more time to prove B before the musical entry instead of
        // starting a second live network dependency at the last moment.
        const val METERED_SMART_ARM_LEAD_MS = 30_000L

        // Required decoded/queued runway before B may become audible.
        const val STANDARD_PREBUFFER_MS = 6_000L
        const val AUTOMIX_PREBUFFER_MS = 10_000L
        const val METERED_AUTOMIX_PREBUFFER_MS = 18_000L
        const val PREBUFFER_END_GUARD_MS = 250L

        // After B starts silently/very quietly, keep A as the MediaSession owner
        // for a short stability proof. An immediate rebuffer can then be
        // abandoned without exposing a broken B to the user.
        const val HANDOFF_STABILITY_MS = 650L
        const val POST_START_COMMIT_BUFFER_MS = 5_000L
        const val METERED_POST_START_COMMIT_BUFFER_MS = 12_000L
        const val FOREGROUND_HANDOFF_GAIN_MARGIN = 0.015f
        const val OUTGOING_END_EPSILON_MS = 180L

        /**
         * States in which a track is measured well enough to be *entered* on.
         *
         * [TrackAnalysisState.REFINING] belongs here because the entry fields —
         * tempo, beat confidence, the cue point — are all measured over the
         * track's opening, which is precisely what a head-only pass reads. The
         * whole-track pass it is waiting on adds the *exit* half: content end,
         * outro, mix-out anchors, the energy curve. Those matter when this track
         * is later the one being left, and not at all for the transition into it.
         */
        val MEASURED_ENOUGH_TO_ENTER_ON = setOf(
            TrackAnalysisState.ANALYSED,
            TrackAnalysisState.REFINING,
        )

        /**
         * Longest a transition will wait on an incoming track that will not
         * become ready. Past this the queue is left to move on plainly, which is
         * a missed crossfade rather than a broken one.
         */
        const val ARM_TIMEOUT_MS = 20_000L

        /**
         * Where the outgoing low-pass sits the instant a filter ride begins.
         *
         * The ride used to start from [TransitionFilterProcessor.OPEN_HZ] and
         * travel down, which meant the first stretch of every transition was
         * spent crossing a range nobody can hear a filter in: a tenth of the way
         * through the fade the cutoff was still at 17.5kHz, indistinguishable
         * from no filter at all, and the ride only became audible around the
         * midpoint. Engaging here instead — above the fundamentals of everything
         * but cymbals, so what goes first is air and shimmer — is what makes the
         * gesture read as a hand landing on the filter the moment the blend
         * starts, rather than something remembered late.
         *
         * 9kHz was the first attempt at that and still read as late by ear: it
         * is above everything but cymbals, so engaging there takes the air off
         * and nothing else, and the outgoing vocal — the thing actually clashing
         * — was untouched until the sweep had travelled most of the way down.
         * 7kHz is inside the presence range, so the gesture is audible on the
         * voice itself from the first instant.
         */
        const val FILTER_ENTRY_HZ = 7_000.0

        /**
         * The bottom of a filter ride. Below a few hundred hertz a track stops
         * reading as "further away" and starts reading as "broken", which is not
         * the impression a transition should leave of the song being left.
         */
        const val FILTER_FLOOR_HZ = 300.0

        /**
         * Where the low end is considered to end. Around the fundamental of a
         * bass guitar's upper register, and the usual corner on a mixer's bass
         * kill — high enough to clear the kick and the sub, low enough to leave
         * the body of the vocal alone.
         */
        const val BASS_SWAP_HZ = 200.0

        /** Gentle spectral carve used only by INTRO_BRIDGE_FILTER. */
        const val INTRO_BRIDGE_OUTGOING_LOW_PASS_HZ = 6_200.0
        const val INTRO_BRIDGE_INCOMING_HIGH_PASS_HZ = 420.0

        /** How much of the fade the low end takes to change hands. */
        const val BASS_SWAP_WIDTH = 0.10

        /**
         * Shape of the outgoing low-pass against fade progress, between
         * [FILTER_ENTRY_HZ] and [FILTER_FLOOR_HZ].
         *
         * Was 2.0 — squared — which left the cutoff at 6.9kHz at the midpoint,
         * so the outgoing vocal went untouched through the whole first half of
         * every transition. Then 1.3, which was still back-loaded: the exponent
         * held the cutoff near its entry point through the opening of the fade,
         * which is precisely where the two vocals overlap at comparable level.
         *
         * Below 1 now, so the ride is front-loaded — steepest at the start,
         * flattening as it approaches the floor. That is the shape of the gesture
         * being imitated: a hand moves a filter knob fast and then eases it in,
         * not the reverse. The old worry that a fast cutoff takes the outgoing
         * track out prematurely is answered by [FILTER_FLOOR_HZ] rather than by
         * the exponent — the ride bottoms out at 300Hz, which is still a present
         * bed under the incoming track, not silence.
         *
         * Crosses 5kHz — about where a low-pass becomes plainly audible on a
         * full-range mix — a twentieth of the way into the fade, against a
         * quarter of the way at 1.3. Lands at 3.8kHz a tenth of the way in,
         * 2.6kHz at a fifth, 1.0kHz at the midpoint.
         */
        const val FILTER_SWEEP_SHAPE = 0.75

        /**
         * Where the incoming track's high-pass starts on a filter ride.
         *
         * Above the fundamental range of most voices and the body of a snare, so
         * what arrives first is presence and percussion — enough to hear a track
         * coming and lock onto its groove, not enough for a second lead vocal.
         *
         * 700Hz was that corner while the outgoing sweep was gentler. It no longer
         * is: the sweep engages at [FILTER_ENTRY_HZ] and is down to 4kHz a tenth
         * of the way in, so a 700Hz entry left the two tracks sharing very nearly
         * three octaves — and sharing them from 529Hz up, which is exactly where a
         * lead vocal's fundamentals sit. 1.2kHz takes about an octave off the
         * bottom of that shared band, and it is the octave the collision actually
         * happens in. What is left of the outgoing track then sits *under* the
         * arriving one rather than inside it, which is what makes the incoming
         * track read as a layer landing on top of a darkening one instead of a
         * second voice in the same space.
         */
        const val ENTRY_HIGH_PASS_HZ = 1_200.0

        /**
         * How far into the fade the incoming track is fully open again.
         *
         * Comfortably before the end: past this point the outgoing track is deep
         * into its own sweep and quiet with it, so there is nothing left to keep
         * out of the way of, and anything still filtered here would just be the
         * new track arriving wrong.
         */
        const val ENTRY_OPEN_BY = 0.6

        /**
         * Shape of the incoming high-pass's descent; see [entryHighPass].
         *
         * Below 1 so the corner lingers in the range a voice occupies instead of
         * dropping straight through it into sub-bass, where a high-pass is
         * inaudible and the clash this exists to prevent is already back.
         *
         * 0.35 rather than 0.45 for more of the same, and the effect compounds
         * across the overlap rather than being a flat offset: on a filter ride the
         * corner sits a fourteenth higher a tenth of the way in, a quarter higher
         * at three tenths, a third higher at four. So the hold is back-loaded into
         * the middle of the blend — where both tracks are near equal gain and the
         * collision is at its worst — and what gets given up in exchange is the
         * bottom of the descent, which is a few hundred hertz of sub-bass nobody
         * hears a high-pass leave. The release into the last of [ENTRY_OPEN_BY] is
         * correspondingly more of an event, which is the point: the arriving track
         * opening out is the moment the listener is meant to notice.
         */
        const val ENTRY_SHAPE = 0.35

        /**
         * How far [rideVocalSeparation] closes the outgoing track's top at a
         * full collision.
         *
         * Well above [FILTER_FLOOR_HZ]'s 300Hz, because this fires on pairs that
         * were going to be crossfaded plainly and the intent is to stop two
         * voices competing, not to send one of them into another room. 1.6kHz is
         * below the presence and sibilance a lead vocal is picked out by, and
         * above enough of its body that the track still reads as itself.
         */
        const val VOCAL_SEPARATION_FLOOR_HZ = 1_600.0

        /**
         * Where the incoming track's high-pass starts in [rideVocalSeparation].
         *
         * Lower than [ENTRY_HIGH_PASS_HZ]'s 1.2kHz, for the same reason the floor
         * is higher: on a plain crossfade the arriving track has no filter
         * gesture to explain itself with, so it has to sound like it fades in
         * normally. 700Hz clears the body of a voice while leaving its lower
         * harmonics, which is enough to stop it fighting the outgoing lead.
         *
         * Was 450Hz, which fit that description on paper and was mostly inaudible
         * in practice: a fifth of the way in it was already down to 268Hz, doing
         * nothing about a collision the vocal model had reported at full strength.
         * 700Hz is the corner a filter ride itself used to open at, so it is a
         * known-restrained one rather than a new guess — and keeping this style a
         * clear step below that one leaves the two ranked the way their tiers are.
         */
        const val VOCAL_SEPARATION_HIGH_PASS_HZ = 700.0

        /**
         * [ENTRY_HIGH_PASS_HZ]'s counterpart for a beat-matched blend: lower, and
         * briefer.
         *
         * Was 320Hz, which the bass swap almost entirely swallowed. The incoming
         * track is already high-passed at [BASS_SWAP_HZ] until the low end changes
         * hands and [rideBassSwap] takes whichever corner is higher, so a 320Hz
         * entry was only above that floor for the first sixth of the blend, and
         * only ever by a little. 520Hz gives the arriving track an entry gesture
         * that outlives the bass kill — clear of it until nearly three tenths in —
         * rather than one hiding inside it.
         */
        const val BLEND_ENTRY_HIGH_PASS_HZ = 520.0

        /** [ENTRY_OPEN_BY]'s counterpart for a beat-matched blend. */
        const val BLEND_ENTRY_OPEN_BY = 0.45

        /**
         * Where [BLEND_ENTRY_HIGH_PASS_HZ] and [BLEND_ENTRY_OPEN_BY] go at a full
         * vocal collision: a corner high enough to hold the arriving voice's body
         * out, held for most of the blend rather than a third of it.
         *
         * Still short of [ENTRY_HIGH_PASS_HZ]'s filter-ride treatment. The two
         * tracks are on a shared grid and meant to sound simultaneous; the aim is
         * to stop the two leads occupying one band, not to hide either of them.
         *
         * Tracks [BLEND_ENTRY_HIGH_PASS_HZ] upward — 620Hz to 950Hz — so how hard
         * the two are singing over each other stays the thing that separates a
         * marginal collision from a head-on one, rather than both converging on
         * whatever the bass kill was already doing.
         */
        const val BLEND_ENTRY_CLASH_HIGH_PASS_HZ = 950.0
        const val BLEND_ENTRY_CLASH_OPEN_BY = 0.7

        /** Where [BLEND_EXIT_FROM] and [BLEND_EXIT_LOW_PASS_HZ] go at a full collision. */
        const val BLEND_EXIT_CLASH_FROM = 0.12
        const val BLEND_EXIT_CLASH_LOW_PASS_HZ = 1_100.0

        /**
         * Where the outgoing track starts losing its top on a beat-matched
         * blend.
         *
         * Was 0.5, which left the outgoing track completely unfiltered for the
         * whole first half — the same "remembered late" complaint that
         * [FILTER_ENTRY_HZ] answers on a filter ride, in the one style where
         * both tracks are at their most similar and so most likely to clash.
         * Brought forward rather than to zero: a beat-matched blend is chosen
         * because the two tracks are meant to sound simultaneous, and opening
         * with the outgoing one already darkened would defeat that.
         */
        const val BLEND_EXIT_FROM = 0.3

        /**
         * Where that low-pass lands by the end of the blend. High enough that the
         * track is still plainly itself — this style is chosen for pairs meant to
         * sound simultaneous — and low enough to take the sibilance off a voice
         * that is leaving.
         */
        const val BLEND_EXIT_LOW_PASS_HZ = 2_200.0

        const val IDLE_STEP_MS = 250L
        const val ANALYSIS_REQUEST_INTERVAL_MS = 1_500L

        /**
         * Arming only waits on a buffer now — nothing is being converged — so
         * this is about how promptly the fade can start once the incoming track
         * is ready, not about a control loop's step size.
         */
        const val ARM_STEP_MS = 40L
        const val FADE_STEP_MS = 30L
        const val BAIL_STEP_MS = 15L
    }
}
