package com.music.orb.playback.automix

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.orb.data.NerdStats
import com.music.orb.data.TrackLog
import com.music.orb.data.settings.AppSettings
import com.music.orb.playback.AudioCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runtime A -> B renderer for Orb Automix.
 *
 * Analysis and planning stay outside the player graph. This class receives a
 * completed [TransitionPlan], arms the second deck, aligns the incoming cue and
 * executes volume/EQ/rate automation. It never reorders the queue.
 */
@UnstableApi
class AutomixController(
    context: Context,
    private val scope: CoroutineScope,
    private val active: () -> ExoPlayer,
    private val standby: () -> ExoPlayer,
    private val activeEq: () -> AutomixEqProcessor,
    private val standbyEq: () -> AutomixEqProcessor,
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
) {
    private enum class Phase { IDLE, ARMING, MIXING }

    private val analysisCache = AutomixAnalysisCache(context.applicationContext)
    private val analyzer = AutomixAnalyzer(analysisCache, context.applicationContext)

    private var phase = Phase.IDLE
    private var loop: Job? = null
    private var analysisJob: Job? = null
    private var pairKey: String? = null
    private var outgoingAnalysis: AutomixAnalysis? = null
    private var incomingAnalysis: AutomixAnalysis? = null
    private var plan: TransitionPlan? = null
    private var outgoing: ExoPlayer? = null
    private var incoming: ExoPlayer? = null
    private var handedOff = false
    private var outgoingEq: AutomixEqProcessor? = null
    private var incomingEq: AutomixEqProcessor? = null
    private var lastAnalysisAttemptAt = 0L
    private var lastAppliedRelativeRate = 1f

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    fun release() {
        loop?.cancel()
        loop = null
        analysisJob?.cancel()
        analysisJob = null
        cancelTransition()
        NerdStats.automix.value = null
        analyzer.close()
    }

    fun isTransitioning(): Boolean = phase != Phase.IDLE

    fun onSkipRequested() {
        analysisJob?.cancel()
        analysisJob = null
        pairKey = null
        plan = null
        cancelTransition()
        NerdStats.automix.value = null
    }

    private fun tick() {
        if (!AppSettings.automixEnabled.value) {
            if (phase != Phase.IDLE) cancelTransition()
            NerdStats.automix.value = null
            return
        }
        when (phase) {
            Phase.IDLE -> considerPair()
            Phase.ARMING -> driveArming()
            Phase.MIXING -> driveMix()
        }
    }

    private fun considerPair() {
        val out = active()
        if ((!out.isPlaying && !out.playWhenReady) || out.repeatMode == Player.REPEAT_MODE_ONE) return
        val duration = out.duration
        if (duration == C.TIME_UNSET || duration <= MIN_TRACK_MS) return
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in 0 until out.mediaItemCount) {
            clearPair()
            return
        }
        val currentItem = out.currentMediaItem ?: return
        val nextItem = out.getMediaItemAt(nextIndex)
        val key = "${currentItem.mediaId}->${nextItem.mediaId}"
        if (pairKey != key) {
            analysisJob?.cancel()
            pairKey = key
            outgoingAnalysis = analysisCache.get(currentItem.mediaId)
            incomingAnalysis = analysisCache.get(nextItem.mediaId)
            plan = null
            lastAnalysisAttemptAt = 0L
            publishAutomixNerdStats(
                stage = when {
                    outgoingAnalysis == null -> NerdStats.AutomixStage.ANALYZING_A
                    incomingAnalysis == null -> NerdStats.AutomixStage.ANALYZING_B
                    else -> NerdStats.AutomixStage.PLANNING
                },
            )
        }

        ensureAnalysis(currentItem, nextItem)
        if (plan == null) {
            if (analysisJob?.isActive != true) {
                publishAutomixNerdStats(NerdStats.AutomixStage.PLANNING)
            }
            plan = AutomixTransitionPlanner.plan(
                outgoing = AutomixTrack(currentItem.mediaId, duration, outgoingAnalysis),
                incoming = AutomixTrack(nextItem.mediaId, 0L, incomingAnalysis),
            )
        } else if (analysisJob == null && (outgoingAnalysis != null || incomingAnalysis != null)) {
            // Re-plan as soon as fresher evidence has arrived.
            plan = AutomixTransitionPlanner.plan(
                AutomixTrack(currentItem.mediaId, duration, outgoingAnalysis),
                AutomixTrack(nextItem.mediaId, 0L, incomingAnalysis),
            )
        }

        val recipe = plan ?: return
        if (analysisJob?.isActive != true) {
            publishAutomixNerdStats(NerdStats.AutomixStage.READY, recipe)
        }
        val remaining = duration - out.currentPosition
        if (remaining > recipe.durationMs + ARM_LEAD_MS) return
        arm(out, nextIndex, recipe)
    }

    private fun ensureAnalysis(current: MediaItem, next: MediaItem) {
        if (analysisJob?.isActive == true) return
        val currentUri = current.localConfiguration?.uri
        val nextUri = next.localConfiguration?.uri
        val outgoingNeedsUpgrade = outgoingAnalysis == null ||
            (outgoingAnalysis?.completeAnalysis != true && currentUri != null && AudioCache.isFullyCached(currentUri))
        val incomingNeedsUpgrade = incomingAnalysis == null ||
            (incomingAnalysis?.completeAnalysis != true && nextUri != null && AudioCache.isFullyCached(nextUri))
        if (!outgoingNeedsUpgrade && !incomingNeedsUpgrade) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisAttemptAt < ANALYSIS_RETRY_MS) return
        lastAnalysisAttemptAt = now
        analysisJob = scope.launch {
            val owner = coroutineContext[Job]
            try {
                if (outgoingNeedsUpgrade) {
                    publishAutomixNerdStats(NerdStats.AutomixStage.ANALYZING_A)
                    currentUri?.let { uri -> outgoingAnalysis = analyzer.analyze(current.mediaId, uri) }
                    if (outgoingAnalysis != null) {
                        publishAutomixNerdStats(NerdStats.AutomixStage.A_ANALYZED)
                    }
                }
                if (incomingNeedsUpgrade) {
                    publishAutomixNerdStats(NerdStats.AutomixStage.ANALYZING_B)
                    nextUri?.let { uri -> incomingAnalysis = analyzer.analyze(next.mediaId, uri) }
                    if (incomingAnalysis != null) {
                        publishAutomixNerdStats(NerdStats.AutomixStage.B_ANALYZED)
                    }
                }
                plan = null
            } finally {
                if (analysisJob === owner) analysisJob = null
            }
        }
    }

    private fun arm(out: ExoPlayer, nextIndex: Int, recipe: TransitionPlan) {
        analysisJob?.cancel()
        analysisJob = null
        val into = standby()
        outgoing = out
        incoming = into
        outgoingEq = activeEq()
        incomingEq = standbyEq()
        handedOff = false

        val items = ArrayList<MediaItem>(out.mediaItemCount)
        for (i in 0 until out.mediaItemCount) items += out.getMediaItemAt(i)

        into.stop()
        into.clearMediaItems()
        into.volume = 0f
        incomingEq?.apply { enabled = true; neutral() }
        outgoingEq?.apply { enabled = true; neutral() }
        lastAppliedRelativeRate = recipe.incomingPlaybackRate
        into.setPlaybackSpeed(AppSettings.playbackSpeed.value * recipe.incomingPlaybackRate)
        into.setMediaItems(items, nextIndex, recipe.incomingCueMs)
        into.prepare()
        phase = Phase.ARMING
        publishAutomixNerdStats(NerdStats.AutomixStage.PREPARING_B, recipe, progress = 0f)

        TrackLog.d(
            "Automix",
            "armed ${recipe.style} ${recipe.outgoingMediaId} -> ${recipe.incomingMediaId}; " +
                "${recipe.durationMs}ms rate=${"%.4f".format(recipe.incomingPlaybackRate)} " +
                "tempo=${"%.2f".format(recipe.tempoCompatibility)} key=${"%.2f".format(recipe.harmonicCompatibility)} " +
                "structure=${"%.2f".format(recipe.structuralCompatibility)} energy=${"%.2f".format(recipe.energyCompatibility)} vocalRisk=${"%.2f".format(recipe.vocalOverlapRisk)} score=${"%.2f".format(recipe.musicalScore)} candidates=${recipe.candidateCount}",
            about = recipe.outgoingMediaId,
        )
    }

    private fun driveArming() {
        val out = outgoing ?: return cancelTransition()
        val into = incoming ?: return cancelTransition()
        val recipe = plan ?: return cancelTransition()
        if (!AppSettings.automixEnabled.value) return cancelTransition()
        if (out.currentMediaItem?.mediaId != recipe.outgoingMediaId) return cancelTransition()
        val remaining = out.duration - out.currentPosition
        if (into.playbackState == Player.STATE_READY) {
            publishAutomixNerdStats(NerdStats.AutomixStage.B_READY, recipe, progress = 0f)
        } else {
            publishAutomixNerdStats(NerdStats.AutomixStage.PREPARING_B, recipe, progress = 0f)
            return
        }
        if (remaining > recipe.durationMs) return

        into.volume = 0f
        into.playWhenReady = true
        onHandoff(out, into)
        handedOff = true
        phase = Phase.MIXING
        driveMix()
    }

    private fun driveMix() {
        val out = outgoing ?: return finishTransition()
        val into = incoming ?: return finishTransition()
        val recipe = plan ?: return finishTransition()
        if (!handedOff) return cancelTransition()
        if (!AppSettings.automixEnabled.value) return finishTransition()

        val elapsed = (into.currentPosition - recipe.incomingCueMs).coerceAtLeast(0L)
        val p = (elapsed.toFloat() / recipe.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val frame = AutomixTransitionRenderer.render(recipe, p)
        out.volume = frame.outgoing.gain.coerceIn(0f, 1f)
        into.volume = frame.incoming.gain.coerceIn(0f, 1f)
        applyDeckFrame(outgoingEq ?: return, frame.outgoing)
        applyDeckFrame(incomingEq ?: return, frame.incoming)

        // Media3's Sonic processor preserves pitch while playback speed moves.
        // The pure renderer keeps the rate locked for most of a beat-matched
        // transition, then releases it very gently near the handoff.
        lastAppliedRelativeRate = frame.incoming.playbackRate
        val desiredSpeed = AppSettings.playbackSpeed.value * lastAppliedRelativeRate
        if (kotlin.math.abs(into.playbackParameters.speed - desiredSpeed) >= RATE_UPDATE_EPSILON) {
            into.setPlaybackSpeed(desiredSpeed)
        }

        publishAutomixNerdStats(NerdStats.AutomixStage.MIXING, recipe, progress = p)
        if (p >= 1f || out.playbackState == Player.STATE_ENDED) finishTransition()
    }

    private fun applyDeckFrame(processor: AutomixEqProcessor, frame: AutomixTransitionRenderer.DeckFrame) {
        processor.targetLow = frame.low
        processor.targetMid = frame.mid
        processor.targetHigh = frame.high
        processor.targetHighPassHz = frame.highPassHz
        processor.targetLowPassHz = frame.lowPassHz
    }

    private fun finishTransition() {
        val baseSpeed = AppSettings.playbackSpeed.value
        val finishedPlan = plan
        val winner = incoming
        outgoing?.run {
            playWhenReady = false
            stop()
            clearMediaItems()
            volume = 1f
            setPlaybackSpeed(baseSpeed)
        }
        incoming?.run {
            volume = 1f
            if (finishedPlan?.incomingPlaybackRate == 1f) setPlaybackSpeed(baseSpeed)
        }
        outgoingEq?.apply { neutral(); enabled = false }
        incomingEq?.apply { neutral(); enabled = false }
        outgoingEq = null
        incomingEq = null
        outgoing = null
        incoming = null
        handedOff = false
        phase = Phase.IDLE
        if (finishedPlan != null) {
            publishAutomixNerdStats(NerdStats.AutomixStage.COMPLETED, finishedPlan, progress = 1f)
        }
        clearPair()
        if (winner != null && finishedPlan != null && kotlin.math.abs(lastAppliedRelativeRate - 1f) >= RATE_UPDATE_EPSILON) {
            restorePlaybackRateGradually(winner, lastAppliedRelativeRate)
        } else {
            winner?.setPlaybackSpeed(baseSpeed)
        }
        lastAppliedRelativeRate = 1f
    }

    private fun restorePlaybackRateGradually(player: ExoPlayer, relativeStart: Float) {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        scope.launch {
            val steps = TEMPO_RELEASE_STEPS
            repeat(steps) { i ->
                if (player.currentMediaItem?.mediaId != mediaId || !AppSettings.automixEnabled.value) return@launch
                val p = (i + 1f) / steps
                val relative = lerp(relativeStart, 1f, p * p * (3f - 2f * p))
                player.setPlaybackSpeed(AppSettings.playbackSpeed.value * relative)
                delay(TEMPO_RELEASE_STEP_MS)
            }
            if (player.currentMediaItem?.mediaId == mediaId) {
                player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
            }
        }
    }

    private fun cancelTransition() {
        val out = outgoing
        val into = incoming
        val baseSpeed = AppSettings.playbackSpeed.value
        if (handedOff) {
            out?.run { playWhenReady = false; stop(); clearMediaItems(); volume = 1f; setPlaybackSpeed(baseSpeed) }
            into?.run { volume = 1f; setPlaybackSpeed(baseSpeed) }
        } else {
            into?.run { playWhenReady = false; stop(); clearMediaItems(); volume = 1f; setPlaybackSpeed(baseSpeed) }
            out?.run { volume = 1f; setPlaybackSpeed(baseSpeed) }
        }
        outgoingEq?.apply { neutral(); enabled = false }
        incomingEq?.apply { neutral(); enabled = false }
        outgoingEq = null
        incomingEq = null
        outgoing = null
        incoming = null
        handedOff = false
        lastAppliedRelativeRate = 1f
        phase = Phase.IDLE
        clearPair()
    }

    private fun clearPair() {
        pairKey = null
        plan = null
        outgoingAnalysis = null
        incomingAnalysis = null
    }

    private fun publishAutomixNerdStats(
        stage: NerdStats.AutomixStage,
        recipe: TransitionPlan? = plan,
        progress: Float? = null,
    ) {
        val a = outgoingAnalysis
        val b = incomingAnalysis
        NerdStats.automix.value = NerdStats.AutomixSnapshot(
            stage = stage,
            outgoingAnalyzed = a != null,
            incomingAnalyzed = b != null,
            style = recipe?.style?.name,
            outgoingBpm = a?.tempoBpm,
            incomingBpm = b?.tempoBpm,
            outgoingKey = a?.camelot,
            incomingKey = b?.camelot,
            musicalScore = recipe?.musicalScore,
            tempoScore = recipe?.tempoCompatibility,
            harmonicScore = recipe?.harmonicCompatibility,
            structureScore = recipe?.structuralCompatibility,
            energyScore = recipe?.energyCompatibility,
            vocalRisk = recipe?.vocalOverlapRisk,
            candidateCount = recipe?.candidateCount ?: 0,
            incomingRate = recipe?.incomingPlaybackRate,
            outgoingStartMs = recipe?.outgoingStartMs,
            incomingCueMs = recipe?.incomingCueMs,
            durationMs = recipe?.durationMs,
            progress = progress,
        )
    }

    private fun lerp(a: Float, b: Float, p: Float): Float = a + (b - a) * p.coerceIn(0f, 1f)

    private companion object {
        const val TICK_MS = 40L
        const val ARM_LEAD_MS = 5_000L
        const val ANALYSIS_RETRY_MS = 2_000L
        const val MIN_TRACK_MS = 30_000L
        const val RATE_UPDATE_EPSILON = 0.0008f
        const val TEMPO_RELEASE_STEPS = 48
        const val TEMPO_RELEASE_STEP_MS = 75L
    }
}
