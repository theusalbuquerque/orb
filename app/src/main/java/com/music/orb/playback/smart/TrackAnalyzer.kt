/*
 * Modeled on Orchard's own TrackAnalyzer (https://github.com/SFG5453/Orchard).
 * Phase 1 was the DSP-only pass (native/analyzer/audio_analysis.cpp); Phase 2
 * adds the Beat This! ONNX model (see [BeatTracker]) and Phase 3 the
 * open-unmix vocal mask (see [VocalTracker]), both over the head and tail of
 * the track, which is the only part a transition ever reads.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.orb.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.orb.data.NerdStats
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AutomixVersion
import com.music.orb.playback.AudioCache
import com.music.orb.playback.SmallLruMap
import com.music.orb.playback.SmallLruSet
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Produces [TrackAnalysis] for tracks that are about to be mixed, and hands it
 * to [TransitionPlanner].
 *
 * [analysisFor] is called from the crossfade watcher every tick, so it never
 * blocks or computes: it returns what is already known, and an unanalysed
 * track simply reads as no evidence, which the policy ladder answers with a
 * plain fade.
 */
@UnstableApi
class TrackAnalyzer(private val context: Context, private val cache: AudioCache) {

    private val tracker = BeatTracker(context)
    private val vocals = VocalTracker(context)
    private val stemSeparator = VocalTracker(context)

    /**
     * Last-resort, immutable Opus copy for the immediate A -> B pair. Playback cache spans are the
     * fast path, but a partial WebM is not guaranteed to be seekable by MediaExtractor. This store
     * gives the analyzer one ordinary file without tying the mix to whichever Lossless rendition
     * eventually wins playback source selection.
     */
    private val reliableAudio = AnalysisAudioStore(context)

    /** Real vocal/accompaniment windows prepared only for the immediate incoming Automix deck. */
    private val stemResults = ConcurrentHashMap<String, PreparedTransitionStems>()
    private val stemRunning = ConcurrentHashMap.newKeySet<String>()
    private val stemDirectory by lazy {
        File(context.cacheDir, "automix-stems-v1").apply { mkdirs() }
    }

    /**
     * The full curves are the expensive part of an analysis, so only the recent working set stays
     * resident. Older results remain in [AnalysisStore] and are restored on demand.
     */
    private val results = SmallLruMap<String, TrackAnalysis>(RESULTS_RAM_ENTRIES) { trackId, _ ->
        onResultEvicted(trackId)
    }

    @Volatile
    private var onAnalysisUpdated: ((String) -> Unit)? = null

    /** Called whenever stored or freshly decoded evidence for a track changes. */
    fun setOnAnalysisUpdated(listener: ((String) -> Unit)?) {
        onAnalysisUpdated = listener
    }

    private fun notifyAnalysisUpdated(trackId: String) {
        runCatching { onAnalysisUpdated?.invoke(trackId) }
    }
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** Physical downloads already joined, so the one-second planner heartbeat adds one callback. */
    private val reliablePending = ConcurrentHashMap.newKeySet<String>()
    private val reliableEligibleAt = SmallLruMap<String, Long>(TRACK_AUX_ENTRIES)
    private val reliableRetryAt = SmallLruMap<String, Long>(TRACK_AUX_ENTRIES)
    private val reliableFailures = SmallLruMap<String, Int>(TRACK_AUX_ENTRIES)

    /** Tracks whose result came from [analyzeHead] and is waiting to be superseded. */
    private val provisional = SmallLruSet<String>(TRACK_AUX_ENTRIES)

    /**
     * Cached prefix size, in bytes, at the last head attempt on each
     * *rendition*. See [headWorthTrying].
     *
     * Keyed by rendition rather than by track because the growth guard is
     * asking "have I already decoded roughly this much of this copy", and a
     * track can move between copies: a first attempt on a half-cached lossless
     * rendition recorded six megabytes, and a much lighter Opus head arriving
     * afterwards — the one that would actually have produced a result — was then
     * refused for being smaller than a number belonging to a different file.
     */
    private val headAttempts = SmallLruMap<String, Long>(RENDITION_AUX_ENTRIES)

    /**
     * Track-and-rendition pairs that have already reported waiting for a head,
     * so the tick doesn't spam. Not keyed by track alone: a track that gains a
     * second copy of itself is in a genuinely new situation, and the first
     * version of this hid exactly that.
     */
    private val headSkipLogged = SmallLruSet<String>(RENDITION_AUX_ENTRIES)

    /**
     * How many times each track's whole-track pass has been refused for
     * decoding short. Counted rather than flagged so a rendition still filling
     * in its holes gets a few more chances, while one that is genuinely
     * truncated stops being re-decoded on every tick.
     */
    private val shortDecodes = SmallLruMap<String, Int>(TRACK_AUX_ENTRIES)

    /** Tracks already looked for on disk this session; see [restoreOnce]. */
    private val restoreAttempted = SmallLruSet<String>(TRACK_AUX_ENTRIES)

    /** Results that survive the process, so a track is measured once and stays measured. */
    private val store = AnalysisStore(context)

    /**
     * Cache keys of renditions that decoded short despite the cache calling them
     * complete. Keyed by rendition rather than by track, because the point is to
     * send the next attempt at the *same* track to a different copy of it.
     */
    private val badRenditions = SmallLruSet<String>(RENDITION_AUX_ENTRIES)

    /**
     * Cache keys already put through the whole-track pass, whatever came of it.
     *
     * What reopens a track that was written off: a copy of it nobody has read
     * yet. Without this the write-off is final for the session, and a rendition
     * that arrives seconds later — a quality upgrade, or the head fetch the
     * analyzer itself asked for — is never looked at. Measured, a track was
     * given up on at 22:09:24 and its `#hifi` copy finished downloading at
     * 22:09:41.
     */
    private val triedRenditions = SmallLruSet<String>(RENDITION_AUX_ENTRIES)

    /**
     * How many times each rendition has been thrown off disk for being
     * undecodable, so a clean slate stays a one-off.
     *
     * The refetch that follows a discard is not guaranteed to be any better —
     * a source can serve the same broken file twice — and without a bound the
     * two halves feed each other: refuse, delete, refetch, refuse, delete, on a
     * 250ms tick, spending the listener's data in a loop. One clean-slate retry
     * is enough for the case this exists for, which is an entry spliced from two
     * different encodings and unrecoverable only because nothing would ever
     * overwrite it.
     */
    private val discarded = SmallLruMap<String, Int>(RENDITION_AUX_ENTRIES)

    /** Disk lookups that have actually completed, whether or not they found a result. */
    private val restoreCompleted = SmallLruSet<String>(TRACK_AUX_ENTRIES)

    private fun discardsOf(key: String): Int = discarded[key] ?: 0

    /**
     * An evicted analysis can always be restored from disk, so discard only the per-track guards
     * that would otherwise falsely say the result is still resident. Active downloads/jobs keep
     * their own membership in [running]/[reliablePending] and are deliberately not disturbed.
     */
    private fun onResultEvicted(trackId: String) {
        // Eviction callbacks run after the LRU lock is released. If another worker restored the
        // same track in that tiny window, its fresh state wins and must not be cleared here.
        if (results.containsKey(trackId)) return
        provisional.remove(trackId)
        shortDecodes.remove(trackId)
        restoreAttempted.remove(trackId)
        restoreCompleted.remove(trackId)
        reliableEligibleAt.remove(trackId)
        reliableRetryAt.remove(trackId)
        reliableFailures.remove(trackId)
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            // DSP is background work. UI animation and the playback/audio threads always win
            // scheduler time when the device is busy.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            runnable.run()
        }, "orb-smart-analysis").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * Tiny network/cache probes must never sit behind PCM decode / ONNX work on the
     * single analysis worker. They can eliminate that work entirely on a server cache hit.
     */
    private val cacheLookupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            runnable.run()
        }, "orb-automix-cache-lookup").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val remoteCacheLookupPending = ConcurrentHashMap.newKeySet<String>()
    private val remoteCacheChecked = ConcurrentHashMap.newKeySet<String>()

    /**
     * What is known about [trackId] right now: never a computation, never a
     * block. Returns an empty analysis for anything not yet finished, which
     * [assessTransitionTier] reads as no evidence rather than as a failure.
     */
    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    /**
     * Seeds a previously computed/cached analysis without decoding this future queue item.
     * AutoPlay uses this to rank candidate order by tempo/key/beat while preserving the
     * A -> B -> C rule: no new DSP pass for C is started before B becomes current.
     */
    fun acceptCachedAnalysis(analysis: TrackAnalysis) {
        val trackId = analysis.trackId
        if (trackId.isBlank() || !analysis.isUsable) return
        val current = results[trackId]
        if (current?.isUsable == true && current.beatConfidence >= analysis.beatConfidence) return
        results[trackId] = analysis
        store.save(trackId, analysis)
        notifyAnalysisUpdated(trackId)
    }

    /**
     * Head-only tempo/key/beat preview for AutoPlay ordering. It never starts
     * the full reliable C/D analysis, so the live Automix A -> B -> C contract
     * stays intact.
     */
    @Synchronized
    fun requestQueuePreview(trackId: String, uri: Uri, durationSeconds: Double) {
        if (trackId.isBlank()) return
        restoreOnce(trackId)
        if (results[trackId]?.isUsable == true) return

        // AutoPlay ordering is allowed a lightweight head preview in 2.5, but it
        // never competes with the authoritative A/B pipeline. Full reliable
        // analysis remains strictly A -> B -> C; this preview extracts only the
        // rhythm/key evidence needed to order future radio suggestions.
        if (running.isNotEmpty() || reliablePending.isNotEmpty()) return
        cache.requestAnalysisHead(uri)
        val rendition = headWorthTrying(trackId, uri, durationSeconds) ?: return
        if (!running.add(trackId)) return

        executor.execute {
            try {
                if (results[trackId]?.isUsable == true) return@execute
                analyzeHead(trackId, uri, durationSeconds, rendition)?.let { preview ->
                    provisional.add(trackId)
                    results[trackId] = preview
                    notifyAnalysisUpdated(trackId)
                    Log.d(
                        TAG,
                        "AutoPlay preview ready for " + trackId + ": bpm=" + preview.bpm +
                            " key=" + preview.key + " beat=" + preview.beatConfidence,
                    )
                }
            } catch (error: Throwable) {
                Log.d(TAG, "AutoPlay preview unavailable for " + trackId + ": " + error.message)
            } finally {
                running.remove(trackId)
                if (running.isEmpty()) {
                    tracker.release()
                    vocals.release()
                }
            }
        }
    }

    /**
     * Looks [trackId] up on disk, once, off the playback thread.
     *
     * Deliberately not folded into [analysisFor], which is called several times
     * per tick from the playback thread and must never touch the filesystem.
     * The result lands in [results] a tick or two later, which is immaterial
     * against the seconds a real analysis takes — and against the alternative,
     * which is not having it at all.
     */
    private fun restoreOnce(trackId: String) {
        if (results.containsKey(trackId)) return
        // The set doubles as the once-guard: add() is true only for the first
        // caller, so a track with nothing stored is looked for once per session
        // rather than on every tick.
        if (!restoreAttempted.add(trackId)) return
        executor.execute {
            try {
                val stored = store.load(trackId) ?: return@execute
                Log.d(TAG, "Restored analysis for $trackId: bpm=${stored.bpm} conf=${stored.beatConfidence}")
                NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.STORED)
                if (results.putIfAbsent(trackId, stored) == null) {
                    notifyAnalysisUpdated(trackId)
                }
            } finally {
                restoreCompleted.add(trackId)
                // Even a cache miss is new information: the session planner can
                // now stop waiting for disk and start warming/analyzing audio.
                notifyAnalysisUpdated(trackId)
            }
        }
    }

    /**
     * Primes a queue candidate from persisted analysis without asking the audio
     * cache to fetch any bytes. Automix can therefore inspect upcoming
     * tracks ahead cheaply and only warm audio for the first few unknown ones.
     */
    fun restoreStored(trackId: String) {
        if (trackId.isBlank()) return
        restoreOnce(trackId)
    }

    /**
     * Whether the persisted lookup has finished. Used to avoid fetching an
     * analysis head while a perfectly good stored result may still be seconds
     * away in the analyzer's single-threaded restore queue.
     */
    fun storedLookupFinished(trackId: String): Boolean =
        results.containsKey(trackId) || trackId in restoreCompleted

    /** True once [trackId] has a result, including a failure. Nothing more will arrive. */
    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    /**
     * True only when the current track has finished its full analysis pass.
     *
     * A complete result is required on every network. The service also waits for worker cleanup.
     */
    fun isFullyAnalysed(trackId: String): Boolean {
        val analysis = results[trackId] ?: return false
        if (analysis.status != TrackAnalysis.STATUS_READY || trackId in provisional) return false

        // Automix 2.5 must not release B from the A -> B gate merely because an older
        // persisted/local analysis says READY. While the curve-aware backend is available,
        // A is complete only after the remote schema carrying onset/energy/chroma/tempo
        // curves has landed. Otherwise B can begin its download/upload/inference while A is
        // still waiting for exactly the evidence the 2.5 planner needs.
        val curveAware25 =
            AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
                AppSettings.automix25Available.value

        // Backend reachability is transient; analysis completeness is not.
        // Never relabel a legacy/schema-3 result as "complete" merely because
        // the health probe is temporarily false — /plan still requires schema 6.
        return !curveAware25 || analysis.analysisSchema >= REMOTE_CURVE_SCHEMA
    }

    /**
     * Whether the published evidence is usable, including a provisional result.
     * This is not permission to start the next track's analysis.
     */
    fun hasUsableAnalysis(trackId: String): Boolean = results[trackId]?.isUsable == true

    /**
     * Planning readiness is intentionally weaker than full analysis for the incoming track.
     * A may only plan from final schema-4 evidence, but B can become plan-ready from a
     * trustworthy opening analysis (BPM/beat/key/mix-in) while its full-track refinement continues.
     */
    fun isReadyForPlan(trackId: String, incoming: Boolean): Boolean {
        val analysis = results[trackId] ?: return false
        if (!analysis.isUsable) return false
        return if (incoming) {
            hasIncomingHeadEvidence(analysis)
        } else {
            hasOutgoingTailEvidence(analysis)
        }
    }

    /**
     * True while a decode and inference for [trackId] is actually in flight.
     *
     * Distinct from "not analysed": a track waiting on bytes and a track being
     * worked on right now are the same absence of a result, and the difference
     * is the difference between something being wrong and something simply
     * taking the several seconds it takes.
     */
    fun isAnalysing(trackId: String): Boolean = trackId in running || trackId in reliablePending

    /**
     * Queues [trackId] (playing at [uri]) for analysis if it is not already
     * done or in flight. Cheap to call repeatedly; callers re-request as
     * caching progresses.
     *
     * Runs in up to two passes, because waiting for a full cache is what kept
     * the *incoming* track of every transition unanalysed. A track only
     * finishes downloading once it is already playing, so the whole-track pass
     * lands in time to describe a track's own mix-out and never in time to
     * describe its entry — which is the half the listener hears at the moment
     * of the blend.
     *
     * So a track with enough of a head on disk gets [analyzeHead] first: beat
     * grid only, over the opening window, which is all the incoming side is
     * read for. That result is provisional and is replaced by the whole-track
     * [analyze] as soon as the remaining bytes arrive.
     */
    @Synchronized
    fun request(trackId: String, uri: Uri, durationSeconds: Double) {
        if (trackId.isBlank()) return
        if (running.isNotEmpty() || reliablePending.isNotEmpty()) return

        // Any complete rendition of this recording will do, not just the one the
        // player happens to be on: see [chooseRendition]. Waiting on the live
        // URI is what made analysis arrive after the transition that needed it.
        // Queued before the cache is even consulted, so a track measured in an
        // earlier session short-circuits the whole path rather than being
        // re-earned from audio the cache may since have evicted.
        restoreOnce(trackId)

        // Complete *and* not already written off. A copy that decoded to
        // nothing is not a copy worth routing to: counting it as "fully cached"
        // sent this down the whole-track path on every tick with nothing left
        // for that path to read, and each of those empty passes was then
        // counted as a failed decode.
        val complete = cache.renditionsOf(uri).filter { it.isComplete && it.key !in badRenditions }
        val usableComplete = complete.isNotEmpty()

        val recorded = results[trackId]
        // Two things are worth superseding, and nothing else is. A provisional
        // head result, because replacing it with the whole-track pass is the
        // entire point of it — and a recorded failure, but only once a copy of
        // the track nobody has read yet turns up. Re-deciding a failure against
        // the same renditions that produced it would just spend the decode
        // again for the same answer.
        val untried = complete.any { it.key !in triedRenditions }
        val supersedable = when {
            recorded == null -> false
            trackId in provisional -> usableComplete
            else -> !recorded.isUsable && untried
        }
        if (recorded != null && !supersedable) {
            // One exception to returning empty-handed. A provisional result is a
            // placeholder, not an answer — it says the opening decoded, not that
            // the track is measured — and the thing that supersedes it is bytes.
            // Without this nudge the byte escalation stops at whatever the first
            // successful head happened to cost, nothing else ever asks for the
            // rest, and a queued track reaches its own transition carrying an
            // entry-only estimate: no content end, no mix-out anchor, no vocal
            // mask, which is most of what the outgoing half of a blend reads.
            if (trackId in provisional && !usableComplete) {
                NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.CACHE_WARMING)
                cache.requestAnalysisHead(uri)
            }
            return
        }
        // The strike count belongs to the attempt that was given up on, not to
        // the track for the rest of the session; a reopened track starts level.
        if (recorded != null && !recorded.isUsable) shortDecodes.remove(trackId)
        // One head attempt per track: a partial container that will not parse
        // now is unlikely to parse ten ticks later, and retrying a decode every
        // 250ms would cost more than the analysis it is trying to bring
        // forward.
        val headRendition = if (usableComplete) null else headWorthTrying(trackId, uri, durationSeconds)
        if (!usableComplete && headRendition == null) {
            // Nothing on disk worth decoding, so ask for something. Every other
            // writer either fetches this track's opening too late to matter or
            // never fetches it at all — see [AudioCache.requestAnalysisHead],
            // which is a no-op after the first call and for anything that isn't
            // a YouTube-backed track.
            NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.CACHE_WARMING)
            cache.requestAnalysisHead(uri)
            return
        }
        if (!running.add(trackId)) return

        executor.execute {
            try {
                // [restoreOnce] queues onto this same single-threaded executor,
                // so a stored result for this track has landed by now if there
                // was one — but the decision to get here was taken a tick
                // earlier, when it had not. Without this check a track measured
                // in an earlier session is restored and then immediately spends
                // seven seconds recomputing the identical numbers. A provisional
                // result is exempt: superseding one is the whole point of it.
                val landed = results[trackId]
                if (landed != null && landed.isUsable && trackId !in provisional) return@execute
                if (usableComplete) {
                    NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.CACHE_FULL)
                    val outcome = analyze(trackId, uri, durationSeconds)
                    val whole = outcome.analysis
                    if (whole != null) {
                        results[trackId] = whole
                        notifyAnalysisUpdated(trackId)
                        provisional.remove(trackId)
                        shortDecodes.remove(trackId)
                        // Only the whole-track pass is persisted. A head result
                        // is missing everything past its window — the outro, the
                        // mix-out anchor, the energy curve — and storing one
                        // would freeze a deliberately partial answer in place of
                        // the complete one that supersedes it minutes later.
                        store.save(trackId, whole)
                        restoreAttempted.add(trackId)
                    } else if (outcome.decodedShort &&
                        shortDecodes.update(trackId) { (it ?: 0) + 1 } >= MAX_SHORT_DECODE_ATTEMPTS
                    ) {
                        // Bounded, so a container that is genuinely truncated
                        // isn't re-decoded on every tick for the rest of the
                        // session. Any provisional head result already published
                        // stays: a partial analysis beats an empty one.
                        Log.w(TAG, "Giving up on $trackId after $MAX_SHORT_DECODE_ATTEMPTS short decodes")
                        if (trackId !in provisional) {
                            results[trackId] = TrackAnalysis(
                                status = TrackAnalysis.STATUS_READY,
                                trackId = trackId,
                                duration = durationSeconds,
                            )
                            notifyAnalysisUpdated(trackId)
                        }
                    }
                } else {
                    // Marked before it is published, so a reader on the playback
                    // thread can never see a provisional result that is not
                    // flagged as one.
                    NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.CACHE_HEAD)
                    analyzeHead(trackId, uri, durationSeconds, headRendition!!)?.let { head ->
                        provisional.add(trackId)
                        results[trackId] = head
                        notifyAnalysisUpdated(trackId)
                    }
                }
            } catch (error: Throwable) {
                // Throwable, not Exception: decode leans on MediaCodec, and an
                // OOM or a codec-level Error uncaught on a pool thread that is
                // nobody's parent takes the whole app down for work whose
                // entire failure mode is meant to be "this track goes
                // unanalysed".
                Log.w(TAG, "Analysis of $trackId failed", error)
                // A failed head pass records nothing: the whole-track pass reads
                // a different, complete file and deserves its own attempt.
                // [headWorthTrying] has already made sure the head is not tried
                // twice, so this cannot spin.
                if (usableComplete) {
                    NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.FAILED)
                    // Recorded as ready-but-empty so a track that cannot be
                    // analysed is not retried on every tick for the rest of the
                    // session.
                    results[trackId] = TrackAnalysis(
                        status = TrackAnalysis.STATUS_READY,
                        trackId = trackId,
                        duration = durationSeconds,
                    )
                    notifyAnalysisUpdated(trackId)
                    provisional.remove(trackId)
                }
            } finally {
                running.remove(trackId)
                // The session holds the model's arena and a parsed ONNX graph in native heap for
                // as long as it is open, which a backgrounded music player cannot justify between
                // transitions. Released the moment nothing is in flight; reloading costs under a
                // second against an analysis that already takes several.
                if (running.isEmpty()) {
                    tracker.release()
                    vocals.release()
                }
            }
        }
    }

    /**
     * Downloads the immutable analysis carrier without starting DSP/inference.
     *
     * Used for B while A is being analysed on Wi-Fi. This preserves the strict
     * A -> B analysis order, but removes B's network download from the critical
     * path: once A finishes, B can upload/analyse immediately from a ready file.
     */
    fun prewarmReliableAudio(uri: Uri) {
        if (AppSettings.wifiConnection.value != true) return
        if (uri.getQueryParameter("v").isNullOrBlank()) return
        // Resolve URL/headers/length only. Schema-6 analysis itself will fetch
        // just the head/tail byte ranges it needs when B becomes eligible.
        reliableAudio.prewarmSeekable(uri)
    }

    /**
     * Guarantees a seekable analysis source for the immediate transition pair.
     *
     * [request] remains the zero-duplication fast path and may finish from persisted analysis or
     * playback's cache. If that has only produced a head result (or no result), this method joins a
     * single bounded download of YouTube's Opus fallback and runs the complete structural pass from
     * one immutable file. The resulting timeline is later mapped onto the Lossless/Hi-Res playback
     * rendition when safe, so source-quality discovery and musical analysis can run
     * independently without forcing the mix to play Opus.
     */
    @Synchronized
    fun requestReliable(trackId: String, uri: Uri, durationSeconds: Double) {
        if (trackId.isBlank()) return
        if (running.any { it != trackId } || reliablePending.isNotEmpty()) return

        // Always let persisted evidence win without touching the network or decoding again.
        restoreOnce(trackId)

        val hasAnalysisCarrier = !uri.getQueryParameter("v").isNullOrBlank()
        val remote25 =
            hasAnalysisCarrier &&
                AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
                AppSettings.automix25Available.value &&
                RemoteAutomixClient.isAvailable()

        // Ask Render first. A warm schema-4 cache hit is dramatically cheaper than
        // downloading an entire Opus carrier only to have /analyze answer "cached".
        if (remote25 && trackId !in remoteCacheChecked) {
            if (remoteCacheLookupPending.add(trackId)) {
                cacheLookupExecutor.execute {
                    try {
                        val cached = RemoteAutomixClient.cachedAnalysisForQueue(trackId)
                        remoteCacheChecked.add(trackId)
                        if (cached?.isUsable == true &&
                            cached.analysisSchema >= REMOTE_CURVE_SCHEMA
                        ) {
                            acceptCachedAnalysis(cached)
                            NerdStats.onAutomixAnalysisSource(
                                trackId,
                                NerdStats.AutomixAnalysisSource.REMOTE,
                            )
                            Log.d(TAG, "Remote cache hit for $trackId; skipped analysis audio download")
                        }
                    } finally {
                        remoteCacheLookupPending.remove(trackId)
                        // A miss continues the normal reliable path immediately;
                        // a hit returns at the recorded-analysis guard below.
                        requestReliable(trackId, uri, durationSeconds)
                    }
                }
            }
            return
        }

        // Remote analysis needs the immutable YouTube/Opus analysis rendition. Local files and
        // source-only items keep the existing local analyzer because there is no separate
        // analysis carrier to upload. Before the backend health probe succeeds, local behaviour
        // is also preserved exactly — this makes deployment of the server half non-breaking.
        if (!hasAnalysisCarrier ||
            !RemoteAutomixClient.isAvailable() ||
            AppSettings.meteredConnection.value == true
        ) {
            // Reuse local evidence first on cellular. A provisional result never
            // unlocks B; the complete pass below must finish before advancing.
            request(trackId, uri, durationSeconds)
        }
        if (!hasAnalysisCarrier) return

        val recorded = results[trackId]
        val curveAware25 =
            AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
                AppSettings.automix25Available.value &&
                RemoteAutomixClient.isAvailable()
        if (recorded?.isUsable == true && trackId !in provisional &&
            (!curveAware25 || recorded.analysisSchema >= REMOTE_CURVE_SCHEMA)
        ) return
        if (trackId in running) return
        val now = SystemClock.elapsedRealtime()
        val eligibleAt = reliableEligibleAt.putIfAbsent(trackId, now + AutomixNetworkPolicy.cacheGraceMs(AppSettings.wifiConnection.value))
            ?: (now + AutomixNetworkPolicy.cacheGraceMs(AppSettings.wifiConnection.value))
        if (now < eligibleAt) return
        if ((reliableRetryAt[trackId] ?: 0L) > now) return
        if (!reliablePending.add(trackId)) return

        NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.RELIABLE_DOWNLOAD)
        reliableAudio.requestSeekable(uri) { seekable ->
            synchronized(this@TrackAnalyzer) {
                if (seekable == null) {
                    NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.FAILED)
                    deferReliableRetry(trackId)
                    reliablePending.remove(trackId)
                    return@requestSeekable
                }
                reliableFailures.remove(trackId)
                reliableRetryAt.remove(trackId)
                try {
                    scheduleSeekableAnalysis(trackId, uri, durationSeconds, seekable)
                } finally {
                    reliablePending.remove(trackId)
                }
            }
        }
    }

    /**
     * Fast authoritative pass for the queued/incoming track.
     *
     * Unlike requestQueuePreview(), this does not depend on playback having
     * already cached B. It opens the immutable HTTP-range carrier immediately,
     * measures B.HEAD, and publishes schema-6 evidence for /plan.
     */
    @Synchronized
    fun requestIncomingHead(trackId: String, uri: Uri, durationSeconds: Double) {
        if (trackId.isBlank()) return
        restoreOnce(trackId)

        val recorded = results[trackId]
        if (recorded?.isUsable == true &&
            recorded.analysisSchema >= REMOTE_CURVE_SCHEMA &&
            hasIncomingHeadEvidence(recorded)
        ) return

        if (trackId in running || reliablePending.isNotEmpty()) return
        if (!reliablePending.add(trackId)) return

        reliableAudio.requestSeekable(uri) { seekable ->
            synchronized(this@TrackAnalyzer) {
                if (seekable == null) {
                    reliablePending.remove(trackId)
                    deferReliableRetry(trackId)
                    return@requestSeekable
                }
                reliableFailures.remove(trackId)
                reliableRetryAt.remove(trackId)
                try {
                    scheduleIncomingHeadAnalysis(trackId, uri, durationSeconds, seekable)
                } finally {
                    reliablePending.remove(trackId)
                }
            }
        }
    }

    private fun scheduleIncomingHeadAnalysis(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        source: AnalysisAudioStore.SeekableSource,
    ) {
        if (!running.add(trackId)) return
        executor.execute {
            val started = SystemClock.elapsedRealtime()
            try {
                val analysis = analyzeHeadSource(
                    trackId = trackId,
                    durationSeconds = durationSeconds,
                    openSource = { source.open() },
                )
                if (analysis?.isUsable == true) {
                    results[trackId] = analysis
                    provisional.remove(trackId)
                    store.save(trackId, analysis)
                    restoreAttempted.add(trackId)
                    notifyAnalysisUpdated(trackId)
                    Log.d(
                        TAG,
                        "HEAD-ready $trackId in ${SystemClock.elapsedRealtime() - started}ms " +
                            "bpm=${analysis.bpm} conf=${analysis.beatConfidence} " +
                            "planReady=${hasIncomingHeadEvidence(analysis)} " +
                            "energyPoints=${analysis.energyCurve.size}",
                    )
                } else {
                    deferReliableRetry(trackId)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "HEAD analysis of $trackId failed", error)
                deferReliableRetry(trackId)
            } finally {
                reliableAudio.releaseSeekable(uri)
                running.remove(trackId)
                if (running.isEmpty()) {
                    tracker.release()
                    vocals.release()
                }
            }
        }
    }

    private fun analyzeHeadSource(
        trackId: String,
        durationSeconds: Double,
        openSource: () -> MediaDataSource?,
    ): TrackAnalysis? {
        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0.0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0.0) return null

        val window = minOf(LOCAL_TRANSITION_WINDOW_SECONDS, effectiveDuration)
        val head = region(
            openSource = openSource,
            startSeconds = 0.0,
            endSeconds = window,
            features = null,
            deriveFeatures = true,
        ) ?: return null
        val features = head.features ?: return null
        val grid = head.grid

        val energy = features.energyCurve
        val low = features.lowEnergyCurve
        val mid = features.midEnergyCurve
        val high = features.highEnergyCurve
        val brightness = energy.mapIndexed { index, point ->
            val lo = low.getOrNull(index)?.energy ?: 0.0
            val mi = mid.getOrNull(index)?.energy ?: point.energy
            val hi = high.getOrNull(index)?.energy ?: 0.0
            val total = lo + mi + hi
            EnergySample(point.time, if (total > 1e-9) (hi + 0.35 * mi) / total else 0.0)
        }
        var previous = 0.0
        val onset = energy.map { point ->
            val value = max(0.0, point.energy - previous)
            previous = point.energy
            EnergySample(point.time, value)
        }
        val vocal = (head.vocalMask?.toList() ?: features.vocalActivityMask)
            .take(features.energyCurve.size)

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            analysisSchema = LOCAL_METADATA_SCHEMA,
            duration = effectiveDuration,
            bpm = grid?.bpm ?: features.bpm,
            beatInterval = grid?.beatInterval ?: features.beatInterval,
            beatConfidence = grid?.beatConfidence ?: features.beatConfidence,
            beats = grid?.beats.orEmpty(),
            tempoCurve = grid?.let {
                listOf(TempoSample(head.actualStart + head.seconds * 0.5, it.bpm, it.beatConfidence))
            }.orEmpty(),
            downbeats = grid?.downbeats ?: features.downbeats,
            phraseBoundaries = features.phraseBoundaries,
            firstBeat = grid?.firstBeat ?: features.firstBeat,
            key = features.key,
            keyConfidence = features.keyConfidence,
            headKey = features.key,
            headKeyConfidence = features.keyConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
            mixInTime = features.mixInTime,
            mixInCandidates = features.mixInCandidates,
            energyCurve = energy,
            lowEnergyCurve = low,
            midEnergyCurve = mid,
            highEnergyCurve = high,
            brightnessCurve = brightness,
            onsetCurve = onset,
            chromaCurve = if (features.chroma.size == 12) {
                listOf(ChromaSample(head.actualStart + head.seconds * 0.5, features.chroma))
            } else {
                emptyList()
            },
            vocalActivityMask = if (vocal.size == energy.size) {
                vocal
            } else {
                List(energy.size) { index -> vocal.getOrElse(index) { NEUTRAL_VOCAL } }
            },
            vocalProbability = features.vocalProbability,
        )
    }

    private fun hasIncomingHeadEvidence(analysis: TrackAnalysis): Boolean =
        analysis.analysisSchema >= REMOTE_CURVE_SCHEMA &&
            analysis.bpm in 40.0..220.0 &&
            analysis.energyCurve.any { point ->
                point.time.isFinite() &&
                    point.energy.isFinite() &&
                    point.time <= LOCAL_TRANSITION_WINDOW_SECONDS + 2.0
            }

    private fun hasOutgoingTailEvidence(analysis: TrackAnalysis): Boolean {
        val duration = analysis.duration.takeIf { it.isFinite() && it > 0.0 } ?: return false
        val tailFloor = (duration - LOCAL_TRANSITION_WINDOW_SECONDS - 2.0).coerceAtLeast(0.0)
        return analysis.analysisSchema >= REMOTE_CURVE_SCHEMA &&
            analysis.bpm in 40.0..220.0 &&
            analysis.energyCurve.any { point ->
                point.time.isFinite() &&
                    point.energy.isFinite() &&
                    point.time >= tailFloor
            }
    }

    /**
     * Fast authoritative pass for the currently playing/outgoing track.
     *
     * A -> B planning needs A.TAIL, not A.HEAD. This avoids paying for a second
     * 60 s region before B is even allowed to start its cheap head preview.
     */
    @Synchronized
    fun requestOutgoingTail(trackId: String, uri: Uri, durationSeconds: Double) {
        if (trackId.isBlank()) return
        restoreOnce(trackId)

        val recorded = results[trackId]
        if (recorded?.isUsable == true &&
            trackId !in provisional &&
            hasOutgoingTailEvidence(recorded)
        ) return

        if (trackId in running || reliablePending.isNotEmpty()) return
        if (!reliablePending.add(trackId)) return

        reliableAudio.requestSeekable(uri) { seekable ->
            synchronized(this@TrackAnalyzer) {
                if (seekable == null) {
                    reliablePending.remove(trackId)
                    deferReliableRetry(trackId)
                    return@requestSeekable
                }
                reliableFailures.remove(trackId)
                reliableRetryAt.remove(trackId)
                try {
                    scheduleOutgoingTailAnalysis(trackId, uri, durationSeconds, seekable)
                } finally {
                    reliablePending.remove(trackId)
                }
            }
        }
    }

    private fun scheduleOutgoingTailAnalysis(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        source: AnalysisAudioStore.SeekableSource,
    ) {
        if (!running.add(trackId)) return
        executor.execute {
            val started = SystemClock.elapsedRealtime()
            try {
                val analysis = analyzeTailSource(
                    trackId = trackId,
                    durationSeconds = durationSeconds,
                    openSource = { source.open() },
                )
                if (analysis?.isUsable == true) {
                    results[trackId] = analysis
                    provisional.remove(trackId)
                    store.save(trackId, analysis)
                    restoreAttempted.add(trackId)
                    notifyAnalysisUpdated(trackId)
                    Log.d(
                        TAG,
                        "TAIL-ready $trackId in ${SystemClock.elapsedRealtime() - started}ms " +
                            "bpm=${analysis.bpm} conf=${analysis.beatConfidence} " +
                            "planReady=${hasOutgoingTailEvidence(analysis)} " +
                            "energyPoints=${analysis.energyCurve.size}",
                    )
                } else {
                    deferReliableRetry(trackId)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "TAIL analysis of $trackId failed", error)
                deferReliableRetry(trackId)
            } finally {
                reliableAudio.releaseSeekable(uri)
                running.remove(trackId)
                if (running.isEmpty()) {
                    tracker.release()
                    vocals.release()
                }
            }
        }
    }

    private fun analyzeTailSource(
        trackId: String,
        durationSeconds: Double,
        openSource: () -> MediaDataSource?,
    ): TrackAnalysis? {
        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0.0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0.0) return null

        val window = minOf(LOCAL_TRANSITION_WINDOW_SECONDS, effectiveDuration)
        val tailStart = max(0.0, effectiveDuration - window)
        val tail = region(
            openSource = openSource,
            startSeconds = tailStart,
            endSeconds = effectiveDuration,
            features = null,
            deriveFeatures = true,
        ) ?: return null
        val features = tail.features ?: return null
        val grid = tail.grid

        fun shift(value: Double): Double =
            if (value.isFinite() && value > 0.0) value + tail.actualStart else value
        fun shiftEnergy(values: List<EnergySample>): List<EnergySample> =
            values.map { EnergySample(it.time + tail.actualStart, it.energy) }
        fun shiftCandidates(values: List<MixCandidate>): List<MixCandidate> =
            values.map { it.copy(time = it.time + tail.actualStart) }

        val energy = shiftEnergy(features.energyCurve)
        val low = shiftEnergy(features.lowEnergyCurve)
        val mid = shiftEnergy(features.midEnergyCurve)
        val high = shiftEnergy(features.highEnergyCurve)
        val brightness = energy.mapIndexed { index, point ->
            val lo = low.getOrNull(index)?.energy ?: 0.0
            val mi = mid.getOrNull(index)?.energy ?: point.energy
            val hi = high.getOrNull(index)?.energy ?: 0.0
            val total = lo + mi + hi
            EnergySample(point.time, if (total > 1e-9) (hi + 0.35 * mi) / total else 0.0)
        }
        var previous = 0.0
        val onset = energy.map { point ->
            val value = max(0.0, point.energy - previous)
            previous = point.energy
            EnergySample(point.time, value)
        }
        val vocal = (tail.vocalMask?.toList() ?: features.vocalActivityMask)
            .take(features.energyCurve.size)

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            analysisSchema = LOCAL_METADATA_SCHEMA,
            duration = effectiveDuration,
            bpm = grid?.bpm ?: features.bpm,
            beatInterval = grid?.beatInterval ?: features.beatInterval,
            beatConfidence = grid?.beatConfidence ?: features.beatConfidence,
            beats = grid?.beats.orEmpty(),
            tempoCurve = grid?.let {
                listOf(
                    TempoSample(
                        tail.actualStart + tail.seconds * 0.5,
                        it.bpm,
                        it.beatConfidence,
                    ),
                )
            }.orEmpty(),
            downbeats = grid?.downbeats ?: features.downbeats.map { it + tail.actualStart },
            phraseBoundaries = features.phraseBoundaries.map { it + tail.actualStart },
            firstBeat = grid?.firstBeat ?: shift(features.firstBeat),
            key = features.key,
            keyConfidence = features.keyConfidence,
            tailKey = features.key,
            tailKeyConfidence = features.keyConfidence,
            contentEndTime = shift(features.contentEndTime).takeIf { it > 0.0 } ?: effectiveDuration,
            outroStartTime = shift(features.outroStartTime),
            mixOutTime = shift(features.mixOutTime),
            mixOutCandidates = shiftCandidates(features.mixOutCandidates),
            energyCurve = energy,
            lowEnergyCurve = low,
            midEnergyCurve = mid,
            highEnergyCurve = high,
            brightnessCurve = brightness,
            onsetCurve = onset,
            chromaCurve = if (features.chroma.size == 12) {
                listOf(
                    ChromaSample(
                        tail.actualStart + tail.seconds * 0.5,
                        features.chroma,
                    ),
                )
            } else {
                emptyList()
            },
            vocalActivityMask = if (vocal.size == energy.size) {
                vocal
            } else {
                List(energy.size) { index -> vocal.getOrElse(index) { NEUTRAL_VOCAL } }
            },
            vocalProbability = features.vocalProbability,
        )
    }

    /**
     * Starts a best-effort real stem separation for B's transition window.
     *
     * It reuses the same immutable analysis carrier already used by Automix. The expensive work
     * runs on the analyzer's single worker after structural analysis, so stem preparation cannot
     * race the beat/vocal pass or create a second concurrent ONNX burst on memory-constrained
     * devices. Callers poll [transitionStemsFor]; no playback thread ever blocks here.
     */
    @Synchronized
    fun requestTransitionStems(
        trackId: String,
        uri: Uri,
        startSeconds: Double,
        endSeconds: Double,
    ) {
        if (trackId.isBlank() || uri.getQueryParameter("v").isNullOrBlank()) return
        if (running.isNotEmpty() || reliablePending.isNotEmpty() || !isFullyAnalysed(trackId)) return
        if (!startSeconds.isFinite() || !endSeconds.isFinite() || endSeconds <= startSeconds) return

        val startMs = (startSeconds.coerceAtLeast(0.0) * 1000.0).toLong()
        val maxEnd = startSeconds + STEM_TOTAL_MAX_SECONDS
        val endMs = (minOf(endSeconds, maxEnd) * 1000.0).toLong()
        if (endMs - startMs < STEM_MIN_MS) return
        val key = stemKey(trackId, startMs, endMs)
        stemResults[key]?.let { ready ->
            if (ready.vocalsFile.isFile && ready.accompanimentFile.isFile) return
            stemResults.remove(key)
        }
        if (!stemRunning.add(key)) return

        reliableAudio.request(uri) { file ->
            if (file == null) {
                stemRunning.remove(key)
                return@request
            }
            executor.execute {
                try {
                    prepareTransitionStems(trackId, file, startMs, endMs)?.let { ready ->
                        // One current window per track is enough; planner refinements should replace rather
                        // than accumulate multi-megabyte WAV pairs.
                        stemResults.entries
                            .filter { it.value.trackId == trackId && it.key != key }
                            .forEach { entry ->
                                stemResults.remove(entry.key)?.deleteFiles()
                            }
                        stemResults[key] = ready
                        pruneStemCache(keepKey = key)
                        Log.d(
                            TAG,
                            "Real stems ready for $trackId ${ready.startMs}..${ready.endMs}ms " +
                                "(${ready.accompanimentFile.length() / 1024}kB accompaniment)",
                        )
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Stem preparation failed for $trackId", error)
                } finally {
                    stemRunning.remove(key)
                    // Stem inference owns a separate session from the analysis mask. Release it between
                    // transitions instead of retaining another ORT arena for the life of the service.
                    stemSeparator.release()
                }
            }
        }
    }

    /** Returns a prepared window covering [positionMs], preferring the widest match. */
    fun transitionStemsFor(trackId: String, positionMs: Long): PreparedTransitionStems? =
        stemResults.values
            .asSequence()
            .filter { it.trackId == trackId && it.covers(positionMs) }
            .maxByOrNull { it.endMs - it.startMs }
            ?.takeIf { it.vocalsFile.isFile && it.accompanimentFile.isFile }

    private data class StemChunk(
        val vocalsLeft: FloatArray,
        val vocalsRight: FloatArray,
        val accompanimentLeft: FloatArray,
        val accompanimentRight: FloatArray,
        val sampleRate: Int,
    ) {
        val frames: Int get() = minOf(
            vocalsLeft.size,
            vocalsRight.size,
            accompanimentLeft.size,
            accompanimentRight.size,
        )
    }

    private fun prepareTransitionStems(
        trackId: String,
        file: File,
        requestedStartMs: Long,
        requestedEndMs: Long,
    ): PreparedTransitionStems? {
        if (requestedEndMs - requestedStartMs < STEM_MIN_MS) return null
        stemDirectory.mkdirs()
        val base = safeStemName(trackId) + "_${requestedStartMs}_${requestedEndMs}"
        val vocalsFile = File(stemDirectory, "${base}_vocals.wav")
        val accompanimentFile = File(stemDirectory, "${base}_instrumental.wav")

        var vocalSink: StereoPcm16WavSink? = null
        var accompanimentSink: StereoPcm16WavSink? = null
        var pending: StemChunk? = null
        var cursorMs = requestedStartMs
        var totalFrames = 0L
        var outputRate = 0
        var completed = false

        try {
            while (cursorMs < requestedEndMs) {
                val chunkEndMs = minOf(
                    requestedEndMs,
                    cursorMs + (STEM_CHUNK_USEFUL_SECONDS * 1000.0).toLong(),
                )
                val chunk = separateStemChunk(file, cursorMs, chunkEndMs) ?: return null
                if (chunk.frames <= 0) return null
                if (outputRate == 0) {
                    outputRate = chunk.sampleRate
                    vocalSink = StereoPcm16WavSink(vocalsFile, outputRate)
                    accompanimentSink = StereoPcm16WavSink(accompanimentFile, outputRate)
                } else if (chunk.sampleRate != outputRate) {
                    return null
                }
                // Local non-null references make the invariant explicit to Kotlin: after the first
                // successfully separated chunk, both WAV sinks must exist for every append below.
                val vocalOut = vocalSink ?: return null
                val accompanimentOut = accompanimentSink ?: return null

                val overlapFramesTarget = (STEM_CHUNK_OVERLAP_SECONDS * outputRate).toInt()
                val isLast = chunkEndMs >= requestedEndMs
                val previous = pending
                if (previous == null) {
                    if (isLast) {
                        vocalOut.append(chunk.vocalsLeft, chunk.vocalsRight, 0, chunk.frames)
                        accompanimentOut.append(
                            chunk.accompanimentLeft, chunk.accompanimentRight, 0, chunk.frames,
                        )
                        totalFrames += chunk.frames
                    } else {
                        val tailFrames = overlapFramesTarget.coerceAtMost(chunk.frames / 3).coerceAtLeast(1)
                        val bodyEnd = chunk.frames - tailFrames
                        vocalOut.append(chunk.vocalsLeft, chunk.vocalsRight, 0, bodyEnd)
                        accompanimentOut.append(
                            chunk.accompanimentLeft, chunk.accompanimentRight, 0, bodyEnd,
                        )
                        totalFrames += bodyEnd
                        pending = chunk.tail(tailFrames)
                    }
                } else {
                    val overlapFrames = minOf(previous.frames, chunk.frames, overlapFramesTarget)
                    if (overlapFrames <= 0) return null
                    val mixed = crossfadeStemChunks(previous, chunk, overlapFrames)
                    vocalOut.append(mixed.vocalsLeft, mixed.vocalsRight, 0, mixed.frames)
                    accompanimentOut.append(
                        mixed.accompanimentLeft, mixed.accompanimentRight, 0, mixed.frames,
                    )
                    totalFrames += mixed.frames

                    if (isLast) {
                        vocalOut.append(chunk.vocalsLeft, chunk.vocalsRight, overlapFrames, chunk.frames)
                        accompanimentOut.append(
                            chunk.accompanimentLeft, chunk.accompanimentRight, overlapFrames, chunk.frames,
                        )
                        totalFrames += chunk.frames - overlapFrames
                        pending = null
                    } else {
                        val tailFrames = overlapFramesTarget
                            .coerceAtMost((chunk.frames - overlapFrames).coerceAtLeast(1) / 2)
                            .coerceAtLeast(1)
                        val bodyEnd = chunk.frames - tailFrames
                        if (bodyEnd > overlapFrames) {
                            vocalOut.append(chunk.vocalsLeft, chunk.vocalsRight, overlapFrames, bodyEnd)
                            accompanimentOut.append(
                                chunk.accompanimentLeft, chunk.accompanimentRight, overlapFrames, bodyEnd,
                            )
                            totalFrames += bodyEnd - overlapFrames
                        }
                        pending = chunk.tail(tailFrames)
                    }
                }

                if (isLast) break
                cursorMs = (chunkEndMs - (STEM_CHUNK_OVERLAP_SECONDS * 1000.0).toLong())
                    .coerceAtLeast(cursorMs + 1L)
            }

            pending?.let { tail ->
                vocalSink?.append(tail.vocalsLeft, tail.vocalsRight, 0, tail.frames)
                accompanimentSink?.append(
                    tail.accompanimentLeft, tail.accompanimentRight, 0, tail.frames,
                )
                totalFrames += tail.frames
            }
            completed = outputRate > 0 && totalFrames > 0L
        } catch (error: Throwable) {
            Log.w(TAG, "Could not stitch stem window for $trackId", error)
            return null
        } finally {
            runCatching { vocalSink?.close() }
            runCatching { accompanimentSink?.close() }
            if (!completed) {
                // A failed inference/stitch must never leave a partial RIFF that a later lookup can
                // mistake for a usable stem or let abandoned transition windows fill the cache.
                runCatching { vocalsFile.delete() }
                runCatching { accompanimentFile.delete() }
            }
        }

        if (!completed || !vocalsFile.isFile || !accompanimentFile.isFile) {
            runCatching { vocalsFile.delete() }
            runCatching { accompanimentFile.delete() }
            return null
        }
        val realEndMs = requestedStartMs + (totalFrames * 1000.0 / outputRate).toLong()
        return PreparedTransitionStems(
            trackId = trackId,
            startMs = requestedStartMs,
            endMs = realEndMs,
            vocalsFile = vocalsFile,
            accompanimentFile = accompanimentFile,
            sampleRate = outputRate,
        )
    }

    /** Separates one Open-Unmix-sized useful region, with extra context removed after iSTFT. */
    private fun separateStemChunk(file: File, requestedStartMs: Long, requestedEndMs: Long): StemChunk? {
        val requestedStartSeconds = requestedStartMs / 1000.0
        val requestedEndSeconds = requestedEndMs / 1000.0
        val paddedStartSeconds = (requestedStartSeconds - STEM_EDGE_PAD_SECONDS).coerceAtLeast(0.0)
        val paddedEndSeconds = requestedEndSeconds + STEM_EDGE_PAD_SECONDS
        val decoded = reliableAudio.dataSource(file)?.use { source ->
            AudioDecoder.decodeRegionStereo(source, paddedStartSeconds, paddedEndSeconds)
        } ?: return null
        val pcm = decoded.first
        val actualStart = decoded.second
        if (pcm.sampleRate <= 0.0 || pcm.left.isEmpty() || pcm.left.size != pcm.right.size) return null

        val padTrimFrames = ((paddedStartSeconds - actualStart) * pcm.sampleRate)
            .toInt().coerceAtLeast(0).coerceAtMost(pcm.left.size)
        // MediaExtractor may land a little after the requested decode point. Track the actual first
        // retained sample instead of assuming the requested padded start was reached exactly; this
        // keeps the generated stem on B's musical timeline even across codec/keyframe differences.
        val retainedStartSeconds = actualStart + padTrimFrames / pcm.sampleRate
        val paddedWantedFrames = ((paddedEndSeconds - retainedStartSeconds) * pcm.sampleRate)
            .toInt().coerceAtLeast(1)
        val paddedEndFrame = (padTrimFrames + paddedWantedFrames).coerceAtMost(pcm.left.size)
        if (paddedEndFrame <= padTrimFrames) return null

        val paddedLeft = pcm.left.copyOfRange(padTrimFrames, paddedEndFrame)
        val paddedRight = pcm.right.copyOfRange(padTrimFrames, paddedEndFrame)
        val separated = stemSeparator.separate(paddedLeft, paddedRight, pcm.sampleRate) ?: return null

        val outputRate = separated.sampleRate
        val usefulStartFrame = ((requestedStartSeconds - retainedStartSeconds) * outputRate)
            .toInt().coerceAtLeast(0)
        val usefulWantedFrames = ((requestedEndSeconds - requestedStartSeconds) * outputRate)
            .toInt().coerceAtLeast(1)
        val separatedFrames = minOf(
            separated.vocalsLeft.size,
            separated.vocalsRight.size,
            separated.accompanimentLeft.size,
            separated.accompanimentRight.size,
        )
        val usefulEndFrame = (usefulStartFrame + usefulWantedFrames).coerceAtMost(separatedFrames)
        if (usefulEndFrame <= usefulStartFrame) return null

        return StemChunk(
            vocalsLeft = separated.vocalsLeft.copyOfRange(usefulStartFrame, usefulEndFrame),
            vocalsRight = separated.vocalsRight.copyOfRange(usefulStartFrame, usefulEndFrame),
            accompanimentLeft = separated.accompanimentLeft.copyOfRange(usefulStartFrame, usefulEndFrame),
            accompanimentRight = separated.accompanimentRight.copyOfRange(usefulStartFrame, usefulEndFrame),
            sampleRate = outputRate.toInt(),
        )
    }

    private fun StemChunk.tail(frames: Int): StemChunk {
        val count = frames.coerceIn(1, this.frames)
        val start = this.frames - count
        return StemChunk(
            vocalsLeft.copyOfRange(start, this.frames),
            vocalsRight.copyOfRange(start, this.frames),
            accompanimentLeft.copyOfRange(start, this.frames),
            accompanimentRight.copyOfRange(start, this.frames),
            sampleRate,
        )
    }

    /** Complementary-power-shaped stitch for two estimates of the *same* musical samples. */
    private fun crossfadeStemChunks(previousTail: StemChunk, current: StemChunk, frames: Int): StemChunk {
        val count = minOf(frames, previousTail.frames, current.frames)
        fun mix(previous: FloatArray, next: FloatArray): FloatArray = FloatArray(count) { index ->
            val progress = (index + 1).toDouble() / (count + 1).toDouble()
            val theta = progress * kotlin.math.PI / 2.0
            // cos² + sin² = 1. These windows describe the same source material, so the gains must
            // sum to one; a conventional equal-power cos/sin fade would add ~3 dB at the midpoint.
            val a = (kotlin.math.cos(theta) * kotlin.math.cos(theta)).toFloat()
            val b = (kotlin.math.sin(theta) * kotlin.math.sin(theta)).toFloat()
            (previous[previous.size - count + index] * a + next[index] * b).coerceIn(-1f, 1f)
        }
        return StemChunk(
            vocalsLeft = mix(previousTail.vocalsLeft, current.vocalsLeft),
            vocalsRight = mix(previousTail.vocalsRight, current.vocalsRight),
            accompanimentLeft = mix(previousTail.accompanimentLeft, current.accompanimentLeft),
            accompanimentRight = mix(previousTail.accompanimentRight, current.accompanimentRight),
            sampleRate = current.sampleRate,
        )
    }

    private class StereoPcm16WavSink(file: File, private val sampleRate: Int) : AutoCloseable {
        private val output = java.io.RandomAccessFile(file, "rw")
        private var framesWritten = 0L
        private var closed = false

        init {
            output.setLength(0L)
            writeHeader(dataBytes = 0L)
        }

        fun append(left: FloatArray, right: FloatArray, start: Int, endExclusive: Int) {
            check(!closed)
            val from = start.coerceAtLeast(0)
            val until = minOf(endExclusive, left.size, right.size).coerceAtLeast(from)
            val count = until - from
            if (count <= 0) return
            val bytes = ByteArray(count * 4)
            var offset = 0
            for (index in from until until) {
                val l = (left[index].coerceIn(-1f, 1f) * 32767f).toInt()
                val r = (right[index].coerceIn(-1f, 1f) * 32767f).toInt()
                bytes[offset++] = (l and 0xff).toByte()
                bytes[offset++] = ((l ushr 8) and 0xff).toByte()
                bytes[offset++] = (r and 0xff).toByte()
                bytes[offset++] = ((r ushr 8) and 0xff).toByte()
            }
            output.seek(output.length())
            output.write(bytes)
            framesWritten += count
        }

        private fun writeHeader(dataBytes: Long) {
            output.seek(0L)
            fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
            fun le16(value: Int) {
                output.write(value and 0xff)
                output.write((value ushr 8) and 0xff)
            }
            fun le32(value: Long) {
                output.write((value and 0xff).toInt())
                output.write(((value ushr 8) and 0xff).toInt())
                output.write(((value ushr 16) and 0xff).toInt())
                output.write(((value ushr 24) and 0xff).toInt())
            }
            ascii("RIFF")
            le32(36L + dataBytes)
            ascii("WAVEfmt ")
            le32(16L)
            le16(1)
            le16(2)
            le32(sampleRate.toLong())
            le32((sampleRate * 4L))
            le16(4)
            le16(16)
            ascii("data")
            le32(dataBytes)
        }

        override fun close() {
            if (closed) return
            closed = true
            val dataBytes = framesWritten * 4L
            writeHeader(dataBytes)
            output.fd.sync()
            output.close()
        }
    }

    private fun pruneStemCache(keepKey: String) {
        val entries = stemResults.entries
            .sortedByDescending { entry ->
                maxOf(entry.value.vocalsFile.lastModified(), entry.value.accompanimentFile.lastModified())
            }
        var bytes = 0L
        var kept = 0
        for (entry in entries) {
            val size = entry.value.vocalsFile.length() + entry.value.accompanimentFile.length()
            val mustKeep = entry.key == keepKey
            val canKeep = mustKeep || (kept < MAX_STEM_WINDOWS && bytes + size <= MAX_STEM_BYTES)
            if (canKeep) {
                kept += 1
                bytes += size
            } else {
                stemResults.remove(entry.key)?.deleteFiles()
            }
        }
    }

    private fun PreparedTransitionStems.deleteFiles() {
        runCatching { vocalsFile.delete() }
        runCatching { accompanimentFile.delete() }
    }

    private fun stemKey(trackId: String, startMs: Long, endMs: Long): String =
        "$trackId:${startMs / STEM_KEY_QUANTUM_MS}:${endMs / STEM_KEY_QUANTUM_MS}"

    private fun safeStemName(trackId: String): String =
        trackId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun scheduleSeekableAnalysis(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        source: AnalysisAudioStore.SeekableSource,
    ) {
        val recorded = results[trackId]
        val curveAware25 =
            AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
                AppSettings.automix25Available.value &&
                RemoteAutomixClient.isAvailable()
        if (recorded?.isUsable == true && trackId !in provisional &&
            (!curveAware25 || recorded.analysisSchema >= REMOTE_CURVE_SCHEMA)
        ) return
        if (!running.add(trackId)) return

        executor.execute {
            try {
                val landed = results[trackId]
                val needsCurveRemote =
                    AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
                        AppSettings.automix25Available.value &&
                        RemoteAutomixClient.isAvailable()
                if (landed?.isUsable == true && trackId !in provisional &&
                    (!needsCurveRemote || landed.analysisSchema >= REMOTE_CURVE_SCHEMA)
                ) return@execute

                val outcome = analyzeSource(
                    trackId = trackId,
                    durationSeconds = durationSeconds,
                    sourceLabel = source.label,
                    openSource = { source.open() },
                    onDecodedShort = {},
                )
                val complete = outcome.analysis
                if (complete != null && complete.isUsable) {
                    results[trackId] = complete
                    provisional.remove(trackId)
                    shortDecodes.remove(trackId)
                    store.save(trackId, complete)
                    restoreAttempted.add(trackId)
                    notifyAnalysisUpdated(trackId)
                    Log.d(
                        TAG,
                        "Seekable schema-6 analysis ready for $trackId: bpm=${complete.bpm} " +
                            "conf=${complete.beatConfidence}",
                    )
                } else {
                    NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.FAILED)
                    deferReliableRetry(trackId)
                    if (trackId !in provisional && results[trackId]?.isUsable != true) {
                        results.remove(trackId)
                        notifyAnalysisUpdated(trackId)
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Seekable analysis of $trackId failed", error)
                NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.FAILED)
                deferReliableRetry(trackId)
            } finally {
                reliableAudio.releaseSeekable(uri)
                running.remove(trackId)
                if (running.isEmpty()) {
                    tracker.release()
                    vocals.release()
                }
            }
        }
    }

    private fun scheduleReliableAnalysis(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        file: File,
    ) {
        val recorded = results[trackId]
        val curveAware25 =
            AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
                AppSettings.automix25Available.value &&
                RemoteAutomixClient.isAvailable()
        if (recorded?.isUsable == true && trackId !in provisional &&
            (!curveAware25 || recorded.analysisSchema >= REMOTE_CURVE_SCHEMA)
        ) return
        // A cache pass already in flight wins. The physical file remains on disk and the next
        // planning heartbeat will schedule it immediately if that pass did not finish the job.
        if (!running.add(trackId)) return

        executor.execute {
            try {
                val landed = results[trackId]
                val needsCurveRemote =
                    AppSettings.automixVersion.value == AutomixVersion.V2_5 &&
                        AppSettings.automix25Available.value &&
                        RemoteAutomixClient.isAvailable()
                if (landed?.isUsable == true && trackId !in provisional &&
                    (!needsCurveRemote || landed.analysisSchema >= REMOTE_CURVE_SCHEMA)
                ) return@execute

                // Schema 6 keeps acoustic analysis on-device. Beat This! + Orb DSP produce
                // the planner metadata directly; Render receives no audio from this path.

                NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.RELIABLE_FILE)
                val outcome = analyzeFile(trackId, file, durationSeconds)
                val complete = outcome.analysis
                if (complete != null && complete.isUsable) {
                    results[trackId] = complete
                    provisional.remove(trackId)
                    shortDecodes.remove(trackId)
                    store.save(trackId, complete)
                    restoreAttempted.add(trackId)
                    notifyAnalysisUpdated(trackId)
                    Log.d(
                        TAG,
                        "Reliable analysis ready for $trackId: bpm=${complete.bpm} " +
                            "conf=${complete.beatConfidence}",
                    )
                } else {
                    // A committed file that still decodes short/carries no tempo is not useful on
                    // the next heartbeat. Remove it once, then retry with exponential backoff. Keep
                    // any provisional head evidence already published instead of replacing it with
                    // an empty failure.
                    NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.FAILED)
                    reliableAudio.discard(uri)
                    deferReliableRetry(trackId)
                    if (trackId !in provisional && results[trackId]?.isUsable != true) {
                        results.remove(trackId)
                        notifyAnalysisUpdated(trackId)
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Reliable analysis of $trackId failed", error)
                NerdStats.onAutomixAnalysisSource(trackId, NerdStats.AutomixAnalysisSource.FAILED)
                reliableAudio.discard(uri)
                deferReliableRetry(trackId)
            } finally {
                running.remove(trackId)
                if (running.isEmpty()) {
                    tracker.release()
                    vocals.release()
                }
            }
        }
    }

    private fun deferReliableRetry(trackId: String) {
        val failures = reliableFailures.update(trackId) { (it ?: 0) + 1 }
        val delayMs = (RELIABLE_RETRY_BASE_MS shl (failures - 1).coerceIn(0, 5))
            .coerceAtMost(RELIABLE_RETRY_MAX_MS)
        reliableRetryAt[trackId] = SystemClock.elapsedRealtime() + delayMs
        Log.d(TAG, "Reliable analysis for $trackId will retry in ${delayMs / 1000}s")
    }

    /**
     * Whether enough of [uri]'s head is on disk to be worth a decode, claiming
     * the attempt if so.
     *
     * The byte threshold is derived from the rendition's own average bitrate
     * where the duration is known, because "30 seconds of audio" is a wildly
     * different number of bytes at 96 kbps and at lossless.
     * [HEAD_BYTES_MARGIN] covers the container header and the fact that a
     * track's opening is rarely at its own average bitrate.
     *
     * Where the duration isn't known — which is the common case, since callers
     * request analysis before anything has read the container — the estimate is
     * unavailable and [MIN_HEAD_BYTES] stands in. That is about 30 s of a
     * typical stream but only a few seconds of lossless, so a single attempt
     * gated on it would be spent on too little audio for exactly the tracks
     * that carry the most bytes per second.
     *
     * Hence retrying on growth rather than attempting once: an attempt is
     * allowed again only when the cached prefix has [HEAD_RETRY_GROWTH]-fold
     * grown since the last one. A track therefore gets a handful of tries
     * spread across its download instead of either one try or one per tick.
     */
    private fun headWorthTrying(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        // Across every rendition of the recording, not just the one the player
        // happens to be on. The same track can be part-downloaded under a
        // sibling cache key, and the live URI's own copy is frequently the one
        // holding nothing — a track that reported six megabytes cached twenty
        // minutes earlier reported zero here, because the question was being
        // asked of the wrong copy of it.
        val candidate = cache.renditionsOf(uri)
            .filter { it.cachedPrefix > 0L && it.key !in badRenditions }
            // The growth guard, applied as a filter rather than to the winner.
            // Applied afterwards it did not skip a copy, it ended the search: the
            // single best candidate was chosen, refused for not having grown, and
            // the second-best — frequently the one that would have worked — was
            // never reached. A track therefore got exactly one head attempt ever,
            // against whichever copy of it happened to rank highest at the time.
            .filter { rendition ->
                val previous = headAttempts[rendition.key] ?: return@filter true
                rendition.cachedPrefix >= previous * HEAD_RETRY_GROWTH
            }
            // Most *audio*, not most bytes: renditions differ in bitrate, so the
            // largest prefix is not necessarily the longest playable head.
            .maxByOrNull { headSecondsOf(it, durationSeconds) }
            ?: return null

        val prefix = candidate.cachedPrefix
        val total = candidate.contentLength
        val needed = if (durationSeconds.isFinite() && durationSeconds > MIN_HEAD_SECONDS && total > 0) {
            val bytesPerSecond = total / durationSeconds
            // Sized to [MIN_HEAD_SECONDS] — the shortest decode [analyzeHead]
            // will accept — not to the model's full window. Gating on the full
            // window meant demanding two and a half times the input the analysis
            // would actually settle for: a lossless rendition needs nine
            // megabytes on disk for thirty seconds of audio, and a track sitting
            // at six was refused outright despite holding twice what was needed
            // to produce a result. Whatever *is* cached still gets decoded — the
            // read simply runs out — so a larger prefix is used when there is
            // one, and [HEAD_RETRY_GROWTH] comes back for a better look as the
            // rest arrives.
            (MIN_HEAD_SECONDS * bytesPerSecond * HEAD_BYTES_MARGIN).toLong()
                .coerceAtLeast(MIN_HEAD_BYTES)
                .coerceAtMost(total)
        } else {
            MIN_HEAD_BYTES
        }
        if (prefix < needed) {
            // Once per track, not per tick: a head pass that never fires is
            // invisible otherwise, which is exactly how the first version of
            // this shipped doing nothing at all.
            if (headSkipLogged.add("$trackId@${candidate.key}")) {
                Log.d(
                    TAG,
                    "Head pass for $trackId waiting: ${prefix / 1024}kB cached of " +
                        "${needed / 1024}kB needed (rendition ${candidate.key})",
                )
            }
            return null
        }

        headAttempts[candidate.key] = prefix
        return candidate
    }

    /**
     * Roughly how many seconds of audio a rendition's cached prefix holds.
     *
     * The ranking this feeds used to be `cachedPrefix / contentLength`, which
     * answers zero whenever the length is unknown — and the length is unknown
     * for precisely the entry that matters most, the head
     * [AudioCache.requestAnalysisHead] just fetched, because a bounded request
     * gets a bounded answer. A megabyte of freshly downloaded opening therefore
     * scored below a sibling holding eight unusable kilobytes, and the analyzer
     * spent its one attempt on the wrong copy.
     *
     * [ASSUMED_BYTES_PER_SECOND] stands in where the length still isn't known.
     * It only has to be the right order of magnitude: this decides which copy to
     * read first, not whether the result is trusted.
     */
    private fun headSecondsOf(rendition: AudioCache.Rendition, durationSeconds: Double): Double =
        if (rendition.contentLength > 0 && durationSeconds.isFinite() && durationSeconds > 0) {
            rendition.cachedPrefix * durationSeconds / rendition.contentLength
        } else {
            rendition.cachedPrefix / ASSUMED_BYTES_PER_SECOND
        }

    /**
     * The opening window only: a beat grid, and nothing that would need the rest
     * of the file.
     *
     * Runs [TrackFeatures] over the head, but copies across only the fields
     * that describe an *entry*: where the file starts making sound, the pickup,
     * the end of the intro, and the mix-in candidates. Those are all measured
     * within the opening seconds, so a head-only pass measures them exactly as
     * a whole-track pass would.
     *
     * Everything that describes the rest of the track is dropped on the floor —
     * content end, outro, mix-out anchors, the energy curve. Over a 30 s head
     * that pass does not fail, it answers confidently about a track that is
     * mostly missing, and the planner has no way to tell the difference. Left at
     * their defaults they read as "no evidence": [contentEndTime] falls back to
     * the real duration and the mix-out list ranks as empty.
     *
     * The energy curve is dropped for the same reason even though it is
     * genuinely measured here: the policy indexes the vocal mask against it and
     * counts audible seconds from it, and a curve that stops at 30 s would have
     * this track's *outgoing* half scored against a window it does not cover.
     * A vocal mask therefore cannot come from this pass either, and waits for
     * the whole-track one.
     */
    private fun analyzeHead(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        rendition: AudioCache.Rendition,
    ): TrackAnalysis? {
        fun openSource(): MediaDataSource? = cache.renditionDataSource(uri, rendition)

        // Same guard the whole-track pass applies, for the same reason: a
        // sibling rendition can be a different cut, and a beat grid borrowed
        // across that would put every anchor seconds out. Skipped for the
        // player's own copy, which is the track by definition. A header that
        // will not parse yet is not held against the rendition — more bytes may
        // well fix it — but a length that genuinely disagrees is.
        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        if (expected != null && rendition.key != cache.cacheKeyOf(uri)) {
            val length = openSource()?.use(AudioDecoder::containerDurationSeconds)
            if (length == null || length <= 0) {
                // Logged rather than returned quietly. This is the likeliest way
                // for a head pass to do nothing — a partial container the
                // extractor will not read a duration out of — and while it was
                // silent the whole path looked like it had never run.
                Log.d(TAG, "Head rendition ${rendition.key} for $trackId has no readable duration yet")
                return null
            }
            if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Head rendition ${rendition.key} rejected for $trackId: " +
                        "${"%.1f".format(length)}s against ${"%.1f".format(expected)}s expected",
                )
                badRenditions.add(rendition.key)
                return null
            }
        }

        val window = BeatTracker.WINDOW_SECONDS
        val head = region(::openSource, 0.0, window, features = null, deriveFeatures = true)
            ?: run {
                Log.d(TAG, "Head pass for $trackId could not decode rendition ${rendition.key}")
                return null
            }
        // What was decoded, not what was asked for: the source stops where the
        // cache does. A tempo read off a few seconds is not a weaker measurement
        // than one read off thirty, it is a different and much more credulous
        // one, and the planner cannot see the difference — so it is refused here
        // and the next attempt gets more of the file.
        if (head.seconds < MIN_HEAD_SECONDS) {
            Log.d(TAG, "Head pass for $trackId decoded only ${"%.1f".format(head.seconds)}s; too short")
            return null
        }
        val grid = head.grid
        val entry = head.features
        if (grid == null && entry == null) {
            Log.d(TAG, "Head pass for $trackId produced nothing usable")
            return null
        }

        Log.d(
            TAG,
            "Analysed head of $trackId: bpm=${grid?.bpm ?: entry?.bpm} " +
                "conf=${grid?.beatConfidence ?: entry?.beatConfidence} " +
                "audibleStart=${entry?.audibleStartTime} pickup=${entry?.pickupTime} " +
                "introEnd=${entry?.introEndTime} mixInCandidates=${entry?.mixInCandidates?.size ?: 0} " +
                "over ${"%.1f".format(head.seconds)}s",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            analysisSchema = LOCAL_METADATA_SCHEMA,
            duration = durationSeconds,
            bpm = grid?.bpm ?: entry?.bpm ?: 0.0,
            beatInterval = grid?.beatInterval ?: entry?.beatInterval ?: 0.0,
            beatConfidence = grid?.beatConfidence ?: entry?.beatConfidence ?: 0.0,
            downbeats = grid?.downbeats ?: entry?.downbeats.orEmpty(),
            firstBeat = grid?.firstBeat ?: entry?.firstBeat ?: 0.0,
            key = entry?.key.orEmpty(),
            keyConfidence = entry?.keyConfidence ?: 0.0,
            audibleStartTime = entry?.audibleStartTime,
            pickupTime = entry?.pickupTime,
            introEndTime = entry?.introEndTime ?: 0.0,
            mixInTime = entry?.mixInTime ?: 0.0,
            mixInCandidates = entry?.mixInCandidates.orEmpty(),
        )
    }

    /**
     * Picks which rendition of [uri]'s recording to analyse: the lightest one
     * that is both complete and the same cut as the track being played.
     *
     * A recording can be on disk two or three times over — the Opus stream
     * YouTube served, a substituted source's copy, a later quality upgrade — and
     * they hold the same music, so an analysis of any of them describes all of
     * them. Analysing the smallest is not merely cheaper: it is the one that
     * finished downloading first, and a lossless upgrade can take most of a
     * track's play time to arrive. Waiting for it is why analysis was landing
     * seconds *after* the transition it was meant to inform.
     *
     * The duration check is what makes the sharing safe. A `#alt` rendition
     * comes from an entirely different source and may be a different cut —
     * a radio edit, a version with a longer intro — and a beat grid borrowed
     * across that difference would put every downbeat and both mix anchors
     * seconds out. Comparing container durations catches exactly that, and
     * costs a header parse per candidate.
     */
    private fun chooseRendition(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        val complete = cache.renditionsOf(uri)
            .filter { it.isComplete && it.key !in badRenditions }
        if (complete.isEmpty()) return null

        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        // Without a length to check a sibling against, sharing would be a guess,
        // so only the rendition actually being played can be trusted.
        if (expected == null) {
            val own = cache.cacheKeyOf(uri)
            complete.firstOrNull { it.key == own }?.let { return it }
            // Nothing to cross-check against — but one copy is not ambiguous
            // either, and refusing it outright is a dead end rather than a
            // safeguard. [cacheKeyOf] answers with whichever rendition the key
            // factory resolves to *now*, which with substitution on is the `#alt`
            // entry; the copy actually on disk is routinely the plain one, so
            // this asked for a rendition that did not exist and returned null on
            // every tick, silently, for as long as the track stayed queued.
            //
            // The risk the duration check exists to catch is borrowing a beat
            // grid across two different cuts of a song. That needs two copies to
            // choose wrongly between. With exactly one there is no choice being
            // made, and the worst case degrades from "never analysed" to "a grid
            // measured off the only audio we have".
            return complete.singleOrNull()?.also {
                Log.d(
                    TAG,
                    "Analysing $trackId from its only cached rendition ${it.key}; " +
                        "no duration to check it against",
                )
            }
        }

        for (candidate in complete) {
            val length = cache.renditionDataSource(uri, candidate)
                .use(AudioDecoder::containerDurationSeconds) ?: continue
            if (length <= 0) continue
            if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Rendition ${candidate.key} rejected for $trackId: " +
                        "${"%.1f".format(length)}s against ${"%.1f".format(expected)}s expected",
                )
                continue
            }
            if (candidate.key != cache.cacheKeyOf(uri)) {
                Log.d(
                    TAG,
                    "Analysing $trackId from lighter rendition ${candidate.key} " +
                        "(${candidate.contentLength / 1024}kB)",
                )
            }
            return candidate
        }
        return null
    }

    /**
     * What a whole-track pass came back with.
     *
     * [decodedShort] is the difference between "this copy is broken, strike it"
     * and "there was no copy to read", which the caller counts very differently:
     * three strikes writes a track off for the session. Conflating the two spent
     * all three in 922ms on a track whose only complete copy had just been
     * rejected — the following two attempts decoded nothing because there was
     * nothing left to decode, and were counted as though they had tried.
     */
    private class WholeTrack(val analysis: TrackAnalysis?, val decodedShort: Boolean = false)

    /**
     * What Pass 1 came back with.
     *
     * [decodedShort] has to survive the return rather than collapsing into a null [features]: it is
     * the same distinction [WholeTrack.decodedShort] draws, between a broken copy and no copy, and
     * only one of the two is a strike.
     */
    private class Structural(
        val features: TrackFeatures.Features?,
        val decodedShort: Boolean = false,
    )

    /**
     * Decodes the whole track and reduces it to DSP features.
     *
     * A method rather than a block in [analyze] for a reason that is about memory, not tidiness —
     * see the call site. Everything it decodes is dead by the time it returns, and returning is
     * what makes that true of the heap as well as of the program.
     */
    private fun structure(
        trackId: String,
        sourceLabel: String,
        openSource: () -> MediaDataSource?,
        effectiveDuration: Double,
        onDecodedShort: () -> Unit,
    ): Structural {
        val structRate = TrackFeatures.sampleRate
        val decoded = openSource()?.use { AudioDecoder.decodeRegion(it, 0.0, effectiveDuration) }
            ?: return Structural(null)
        val (pcm, _) = decoded

        // A decode that stops early is indistinguishable, downstream, from a
        // track that simply goes quiet: [TrackFeatures] is handed the
        // container's full duration alongside a short buffer, reads the
        // difference as trailing silence, and puts the mix-out anchor where the
        // bytes ran out. Nothing about the result looks wrong — it is a complete
        // analysis with a plausible contentEnd — and the audible symptom is the
        // track being faded out minutes early. Refused outright rather than
        // published, because a missing analysis degrades to a plain crossfade
        // while a confidently wrong one does not degrade at all.
        val decodedSeconds = if (pcm.sampleRate > 0) pcm.samples.size / pcm.sampleRate else 0.0
        if (decodedSeconds < effectiveDuration * MIN_DECODED_FRACTION) {
            Log.w(
                TAG,
                "Analysis of $trackId refused: $sourceLabel decoded " +
                    "${"%.1f".format(decodedSeconds)}s of a ${"%.1f".format(effectiveDuration)}s " +
                    "container — cached with holes?",
            )
            // Remembered, so the retry reaches for a *different* rendition. This
            // is the whole reason the lightest one is only a preference: a
            // rendition the cache index calls complete can still decode short if
            // it was written badly, and without this the retries would pick the
            // same broken copy three times over and give up on a track whose
            // heavier rendition would have analysed perfectly well.
            onDecodedShort()
            return Structural(null, decodedShort = true)
        }

        val samples = if (abs(pcm.sampleRate - structRate) > 1.0) {
            TrackFeatures.resample(pcm.samples, pcm.sampleRate, structRate) ?: return Structural(null)
        } else {
            pcm.samples
        }

        return Structural(TrackFeatures.analyze(samples, effectiveDuration))
    }

    /**
     * The whole-track pass. A null [WholeTrack.analysis] means "not now, try
     * again"; see the short-decode guard below, which is the one condition that
     * produces a confident-looking analysis that is wrong by minutes rather than
     * merely absent, and the only one that counts as a strike.
     */
    private fun analyze(trackId: String, uri: Uri, durationSeconds: Double): WholeTrack {
        val rendition = chooseRendition(trackId, uri, durationSeconds) ?: return WholeTrack(null)
        // Recorded before the outcome is known, because what this gates is
        // whether a *written-off* track is worth reopening, and the answer is
        // only ever "yes" for a copy that has not been read yet. Recording it on
        // success alone would leave a failure looking permanently reopenable and
        // re-decode the same file on every tick.
        triedRenditions.add(rendition.key)
        fun openSource(): MediaDataSource? = cache.renditionDataSource(uri, rendition)

        return analyzeSource(
            trackId = trackId,
            durationSeconds = durationSeconds,
            sourceLabel = "rendition ${rendition.key}",
            openSource = ::openSource,
            onDecodedShort = {
                badRenditions.add(rendition.key)
                // A complete cache entry that decodes short will never improve. Remove the sibling
                // rendition once so the clean fallback can refill it; never delete the live stream.
                if (rendition.isComplete && discardsOf(rendition.key) < MAX_RENDITION_DISCARDS &&
                    cache.discardBadRendition(uri, rendition.key)
                ) {
                    discarded.update(rendition.key) { (it ?: 0) + 1 }
                    badRenditions.remove(rendition.key)
                    triedRenditions.remove(rendition.key)
                    shortDecodes.remove(trackId)
                }
            },
        )
    }

    private fun analyzeFile(trackId: String, file: File, durationSeconds: Double): WholeTrack {
        fun openSource(): MediaDataSource? = reliableAudio.dataSource(file)
        // The proxy may be a radio/album cut a few seconds away from queue metadata. Analyse its
        // real timeline and only map it onto the
        // playback rendition; pretending the metadata duration is exact creates false anchors.
        val containerDuration = openSource()?.use(AudioDecoder::containerDurationSeconds)
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: durationSeconds
        return analyzeSource(
            trackId = trackId,
            durationSeconds = containerDuration,
            sourceLabel = "analysis file ${file.name}",
            openSource = ::openSource,
            onDecodedShort = {},
        )
    }

    private fun analyzeSource(
        trackId: String,
        durationSeconds: Double,
        sourceLabel: String,
        openSource: () -> MediaDataSource?,
        onDecodedShort: () -> Unit,
    ): WholeTrack {
        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: cached media has no duration")
            return WholeTrack(empty(trackId, 0.0))
        }

        // Schema 6 analyzes only the transition-relevant windows. The middle of the
        // track is not decoded or inspected for A -> B planning.
        val window = minOf(LOCAL_TRANSITION_WINDOW_SECONDS, effectiveDuration)
        val tailStart = max(0.0, effectiveDuration - window)
        val head = region(openSource, 0.0, window, features = null, deriveFeatures = true)
            ?: return WholeTrack(empty(trackId, effectiveDuration))
        val tail = if (tailStart > window / 2.0) {
            region(openSource, tailStart, effectiveDuration, features = null, deriveFeatures = true)
        } else null

        val headFeatures = head.features ?: return WholeTrack(empty(trackId, effectiveDuration))
        val tailFeatures = tail?.features ?: headFeatures
        val headGrid = head.grid
        val tailGrid = tail?.grid
        val leading = tailGrid ?: headGrid

        fun shiftTime(value: Double, offset: Double): Double =
            if (value.isFinite() && value > 0.0) value + offset else value
        fun shiftEnergy(values: List<EnergySample>, offset: Double): List<EnergySample> =
            values.map { EnergySample(it.time + offset, it.energy) }
        fun shiftCandidates(values: List<MixCandidate>, offset: Double): List<MixCandidate> =
            values.map { it.copy(time = it.time + offset) }
        fun mergeEnergy(headValues: List<EnergySample>, tailValues: List<EnergySample>): List<EnergySample> {
            if (tail == null) return headValues
            return (headValues + shiftEnergy(tailValues, tail.actualStart))
                .distinctBy { Math.round(it.time * 1000.0) }
                .sortedBy { it.time }
        }

        val energy = mergeEnergy(headFeatures.energyCurve, tailFeatures.energyCurve)
        val low = mergeEnergy(headFeatures.lowEnergyCurve, tailFeatures.lowEnergyCurve)
        val mid = mergeEnergy(headFeatures.midEnergyCurve, tailFeatures.midEnergyCurve)
        val high = mergeEnergy(headFeatures.highEnergyCurve, tailFeatures.highEnergyCurve)
        val brightness = energy.mapIndexed { index, point ->
            val lo = low.getOrNull(index)?.energy ?: 0.0
            val mi = mid.getOrNull(index)?.energy ?: point.energy
            val hi = high.getOrNull(index)?.energy ?: 0.0
            val total = lo + mi + hi
            EnergySample(point.time, if (total > 1e-9) (hi + 0.35 * mi) / total else 0.0)
        }
        var previousEnergy = 0.0
        val onset = energy.map { point ->
            val value = max(0.0, point.energy - previousEnergy)
            previousEnergy = point.energy
            EnergySample(point.time, value)
        }

        val beats = (headGrid?.beats.orEmpty() + tailGrid?.beats.orEmpty())
            .distinctBy { Math.round(it * 1000.0) }
            .sorted()
        val downbeats = (headGrid?.downbeats.orEmpty() + tailGrid?.downbeats.orEmpty())
            .distinctBy { Math.round(it * 1000.0) }
            .sorted()
            .ifEmpty {
                val shiftedTail = tailFeatures.downbeats.map { it + (tail?.actualStart ?: 0.0) }
                (headFeatures.downbeats + shiftedTail).sorted()
            }
        val tempoCurve = buildList {
            headGrid?.let { add(TempoSample(head.actualStart + head.seconds * 0.5, it.bpm, it.beatConfidence)) }
            tailGrid?.let { add(TempoSample((tail?.actualStart ?: 0.0) + (tail?.seconds ?: 0.0) * 0.5, it.bpm, it.beatConfidence)) }
        }.sortedBy { it.time }

        val headKey = headFeatures.key
        val tailKey = tailFeatures.key
        val headKeyConfidence = headFeatures.keyConfidence
        val tailKeyConfidence = tailFeatures.keyConfidence
        val globalKey = if (tailKeyConfidence > headKeyConfidence) tailKey else headKey
        val globalKeyConfidence = max(headKeyConfidence, tailKeyConfidence)
        val chroma = buildList {
            if (headFeatures.chroma.size == 12) add(ChromaSample(head.actualStart + head.seconds * 0.5, headFeatures.chroma))
            if (tail != null && tailFeatures.chroma.size == 12) add(ChromaSample(tail.actualStart + tail.seconds * 0.5, tailFeatures.chroma))
        }

        val contentEnd = if (tail != null) {
            shiftTime(tailFeatures.contentEndTime, tail.actualStart).coerceAtMost(effectiveDuration)
        } else headFeatures.contentEndTime.takeIf { it > 0.0 } ?: effectiveDuration
        val outroStart = if (tail != null) shiftTime(tailFeatures.outroStartTime, tail.actualStart) else headFeatures.outroStartTime
        val mixOut = if (tail != null) shiftTime(tailFeatures.mixOutTime, tail.actualStart) else headFeatures.mixOutTime
        val mixOutCandidates = if (tail != null) shiftCandidates(tailFeatures.mixOutCandidates, tail.actualStart) else headFeatures.mixOutCandidates
        val phrases = (headFeatures.phraseBoundaries +
            if (tail != null) tailFeatures.phraseBoundaries.map { it + tail.actualStart } else emptyList())
            .distinctBy { Math.round(it * 1000.0) }
            .sorted()

        val vocalMask = buildList {
            addAll((head.vocalMask?.toList() ?: headFeatures.vocalActivityMask).take(headFeatures.energyCurve.size))
            if (tail != null) {
                addAll((tail.vocalMask?.toList() ?: tailFeatures.vocalActivityMask).take(tailFeatures.energyCurve.size))
            }
        }
        val alignedVocalMask = if (vocalMask.size == energy.size) vocalMask else
            List(energy.size) { index -> vocalMask.getOrElse(index) { NEUTRAL_VOCAL } }

        Log.d(TAG, "Analysed schema-6 windows for $trackId: head=${head.seconds}s tail=${tail?.seconds ?: 0.0}s")

        return WholeTrack(
            TrackAnalysis(
                status = TrackAnalysis.STATUS_READY,
                trackId = trackId,
                analysisSchema = LOCAL_METADATA_SCHEMA,
                duration = effectiveDuration,
                bpm = leading?.bpm ?: headFeatures.bpm,
                beatInterval = leading?.beatInterval ?: headFeatures.beatInterval,
                beatConfidence = leading?.beatConfidence ?: headFeatures.beatConfidence,
                beats = beats,
                tempoCurve = tempoCurve,
                downbeats = downbeats,
                phraseBoundaries = phrases,
                firstBeat = headGrid?.firstBeat ?: headFeatures.firstBeat,
                key = globalKey,
                keyConfidence = globalKeyConfidence,
                headKey = headKey,
                headKeyConfidence = headKeyConfidence,
                tailKey = tailKey,
                tailKeyConfidence = tailKeyConfidence,
                audibleStartTime = headFeatures.audibleStartTime,
                pickupTime = headFeatures.pickupTime,
                introEndTime = headFeatures.introEndTime,
                contentEndTime = contentEnd,
                outroStartTime = outroStart,
                mixInTime = headFeatures.mixInTime,
                mixOutTime = mixOut,
                mixInCandidates = headFeatures.mixInCandidates,
                mixOutCandidates = mixOutCandidates,
                energyCurve = energy,
                lowEnergyCurve = low,
                midEnergyCurve = mid,
                highEnergyCurve = high,
                brightnessCurve = brightness,
                onsetCurve = onset,
                chromaCurve = chroma,
                vocalActivityMask = alignedVocalMask,
                vocalProbability = max(headFeatures.vocalProbability, tailFeatures.vocalProbability),
            ),
        )
    }
    /**
     * Everything a decoded region contributes, once its audio has been let go
     * of. [seconds] is what was actually decoded, which for a partially cached
     * file is not what was asked for.
     */
    private class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
        val seconds: Double,
        val actualStart: Double,
        /** Only populated when the caller asked for it; see [region]'s `deriveFeatures`. */
        val features: TrackFeatures.Features? = null,
    )

    /**
     * Decodes one stereo region and runs both models over it, returning only their results.
     *
     * The point of the function boundary is the audio: the stereo buffer, its mono mix and the
     * resampled copies are all local, so they become collectible the moment this returns rather
     * than staying live until the whole analysis finishes. A 30 s stereo region is several
     * megabytes before either model's own working set is counted.
     *
     * Null, or a null field, means "no model evidence for this window" — a codec that will not
     * configure, a region too short, a missing model — which [analyze] already falls back on.
     *
     * The extractor seeks to a sync sample at or before what was asked for, so the region's real
     * start (not [startSeconds]) is what its beat times must be stated against.
     */
    private fun region(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        features: TrackFeatures.Features?,
        deriveFeatures: Boolean = false,
    ): Region? {
        val decoded = openSource()?.use { AudioDecoder.decodeRegionStereo(it, startSeconds, endSeconds) }
            ?: run {
                // The two ways this comes back empty mean opposite things and
                // were reported identically, which cost a round of guessing:
                // this one is the extractor refusing the container outright, so
                // the bytes are wrong or not enough of them are there to parse.
                Log.d(TAG, "Region [$startSeconds, $endSeconds) would not open")
                return null
            }
        val (stereo, actualStart) = decoded
        if (stereo.left.size < stereo.sampleRate) {
            // And this one is a container that parsed fine and yielded under a
            // second of audio — a decode that started and ran out, not one that
            // never started.
            Log.d(TAG, "Region [$startSeconds, $endSeconds) decoded ${stereo.left.size} frames; too few")
            return null
        }

        val seconds = stereo.left.size / stereo.sampleRate
        // In a frame of its own so the full-rate mono downmix is released before either model runs.
        // It is 23 MB for this window at 48 kHz — the same size as each of the two channels it
        // averages — and it is read exactly twice, to make the resampled model input and the DSP
        // one. As a local it would nonetheless stay reachable through `tracker.track` and
        // `vocalMask` below, which is where the analysis allocates most heavily and where the
        // process was dying. Same reasoning [derived] already had, one level further out.
        val inputs = regionInputs(
            stereo = stereo,
            seconds = seconds,
            deriveFeatures = deriveFeatures,
            preferTailModelWindow = startSeconds > 0.5,
        )

        val maskFeatures = features ?: inputs.derived
        return Region(
            grid = inputs.forModel?.let {
                tracker.track(
                    it,
                    offsetSeconds = actualStart + inputs.modelOffsetSeconds,
                )
            },
            // The native DSP mask already covers the complete 60 s transition
            // region. Open-Unmix is a refinement and must not hold READY_FOR_PLAN
            // hostage; real stem/vocal inference can run after the recipe exists.
            vocalMask = maskFeatures?.vocalActivityMask?.toDoubleArray(),
            seconds = seconds,
            actualStart = actualStart,
            features = inputs.derived,
        )
    }

    /** A region's model input, and its DSP features when the caller asked for them. */
    private class RegionInputs(
        val forModel: FloatArray?,
        val modelOffsetSeconds: Double,
        val derived: TrackFeatures.Features?,
    )

    /**
     * Reduces a decoded region to the buffers the models and the DSP actually read.
     *
     * Both come off one full-rate mono downmix, which is why they are made together rather than on
     * demand: that downmix is the largest single allocation in the analysis, and returning is the
     * only way to be rid of it before the models run.
     */
    private fun regionInputs(
        stereo: AudioDecoder.StereoPcm,
        seconds: Double,
        deriveFeatures: Boolean,
        preferTailModelWindow: Boolean,
    ): RegionInputs {
        val mono = FloatArray(stereo.left.size) { index -> (stereo.left[index] + stereo.right[index]) * 0.5f }
        // Beat This!'s efficient window is one 1500-frame inference (~30 s).
        // Running a 60 s region through the model doubles inference work. Keep
        // TrackFeatures on the full region, but feed Beat This! only the transition-
        // relevant side: opening for B/head, ending for A/tail.
        val beatWindowSeconds = minOf(seconds, BeatTracker.WINDOW_SECONDS)
        val beatWindowSamples = (beatWindowSeconds * stereo.sampleRate)
            .toInt()
            .coerceIn(1, mono.size)
        val beatStartSample = if (preferTailModelWindow) {
            (mono.size - beatWindowSamples).coerceAtLeast(0)
        } else {
            0
        }
        val beatMono = if (beatStartSample == 0 && beatWindowSamples == mono.size) {
            mono
        } else {
            mono.copyOfRange(beatStartSample, beatStartSample + beatWindowSamples)
        }
        val forModel = if (abs(stereo.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
            MelSpectrogram.resample(beatMono, stereo.sampleRate, MelSpectrogram.sampleRate)
        } else {
            beatMono
        }
        val modelOffsetSeconds = beatStartSample / stereo.sampleRate

        // Derived here rather than by the caller so the mono buffer is still
        // live: handing it back would keep several megabytes reachable for the
        // rest of the analysis, which is the one thing this function exists to
        // avoid.
        val derived = if (deriveFeatures) {
            val forFeatures = if (abs(stereo.sampleRate - TrackFeatures.sampleRate) > 1.0) {
                TrackFeatures.resample(mono, stereo.sampleRate, TrackFeatures.sampleRate)
            } else {
                mono
            }
            forFeatures?.let { TrackFeatures.analyze(it, seconds) }
        } else {
            null
        }

        return RegionInputs(forModel, modelOffsetSeconds, derived)
    }

    /**
     * Optional refinement path for a transition-window vocal curve. The critical schema-6 pass
     *
     * uses the native DSP mask directly; Open-Unmix can later overwrite the
     * transition-critical probes it actually measured: the opening of an incoming track, the real
     * ending of an outgoing track, and one late-intro probe when B exposes a long runway. This
     * keeps the expensive model bounded while avoiding the old failure mode where every unmeasured
     * point became a neutral 0.5 and the final 60 s of A were effectively invisible to vocal logic.
     */
    private fun vocalMask(
        stereo: AudioDecoder.StereoPcm,
        features: TrackFeatures.Features,
        actualStart: Double,
        featureOffsetSeconds: Double = 0.0,
        preferTailCoverage: Boolean = false,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty() || !VocalSpectrogram.available) return null

        // Two frames of margin absorb the ±1 sample a rate conversion can land on.
        val maxSeconds =
            (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val maxSamples = (maxSeconds * stereo.sampleRate).toInt().coerceAtMost(stereo.left.size)
        if (maxSamples <= 0) return null

        // Keep the DSP estimate everywhere the model does not run. The model is a refinement,
        // not a reason to throw away already-measured vocal evidence from the rest of the window.
        val mask = DoubleArray(curve.size) { index ->
            features.vocalActivityMask.getOrNull(index)
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0.0, 1.0)
                ?: NEUTRAL_VOCAL
        }
        var measuredAny = false
        val usedStarts = ArrayList<Int>(2)

        fun runProbe(rawStartSample: Int) {
            val startSample = rawStartSample
                .coerceIn(0, (stereo.left.size - maxSamples).coerceAtLeast(0))
            if (usedStarts.any { abs(it - startSample) < maxSamples / 3 }) return
            usedStarts += startSample

            val endSample = minOf(stereo.left.size, startSample + maxSamples)
            if (endSample - startSample < stereo.sampleRate.toInt().coerceAtLeast(1)) return

            val left = if (startSample == 0 && endSample == stereo.left.size) {
                stereo.left
            } else {
                stereo.left.copyOfRange(startSample, endSample)
            }
            val right = if (startSample == 0 && endSample == stereo.right.size) {
                stereo.right
            } else {
                stereo.right.copyOfRange(startSample, endSample)
            }
            val values = vocals.track(left, right, stereo.sampleRate) ?: return
            val probeStartSeconds = startSample / stereo.sampleRate

            for (index in curve.indices) {
                val pointOnRegion =
                    curve[index].time + featureOffsetSeconds - actualStart
                val frame =
                    ((pointOnRegion - probeStartSeconds) * VocalSpectrogram.frameRate).toInt()
                if (frame in values.indices) {
                    mask[index] = values[frame].toDouble().coerceIn(0.0, 1.0)
                    measuredAny = true
                }
            }
        }

        if (preferTailCoverage) {
            // A's release is decided at the end of the tail, not at the beginning of a 90 s
            // tail window. Measure the final model-sized slice so the last vocal/reverb phrase is
            // what the planner sees.
            runProbe(stereo.left.size - maxSamples)
        } else {
            // Always classify the start of B.
            runProbe(0)

            // Long instrumental intros (for example an impact around 40-60 s) need one more
            // high-confidence look. Aim the second probe around the strongest structural entry
            // instead of chunking the whole 90 s window and multiplying inference cost.
            val lateIntroTarget = buildList {
                if (features.introEndTime.isFinite() && features.introEndTime > 0.0) {
                    add(features.introEndTime)
                }
                if (features.mixInTime.isFinite() && features.mixInTime > 0.0) {
                    add(features.mixInTime)
                }
                features.mixInCandidates
                    .maxByOrNull { it.score }
                    ?.time
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?.let { add(it) }
            }.maxOrNull()

            if (lateIntroTarget != null && lateIntroTarget > maxSeconds * 0.85) {
                val regionSeconds = stereo.left.size / stereo.sampleRate
                val desiredStartSeconds =
                    (lateIntroTarget - maxSeconds * 0.72)
                        .coerceIn(0.0, max(0.0, regionSeconds - maxSeconds))
                runProbe((desiredStartSeconds * stereo.sampleRate).toInt())
            }
        }

        return mask.takeIf { measuredAny }
    }

    /**
     * Overlays the head and tail masks onto one full-length curve, or null when neither ran —
     * which the caller answers by keeping the DSP heuristic rather than reporting a mask of
     * nothing but [NEUTRAL_VOCAL].
     */
    private fun mergeMasks(size: Int, head: DoubleArray?, tail: DoubleArray?): List<Double>? {
        if (size <= 0 || (head == null && tail == null)) return null
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        for (source in listOfNotNull(head, tail)) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    /** Recorded ready-but-empty so a track that cannot be decoded is not retried every tick. */
    private fun empty(trackId: String, durationSeconds: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    fun release() {
        executor.shutdownNow()
        cacheLookupExecutor.shutdownNow()
        remoteCacheLookupPending.clear()
        remoteCacheChecked.clear()
        results.clear()
        reliableAudio.release()
        stemResults.values.forEach { it.deleteFiles() }
        stemResults.clear()
        stemRunning.clear()
        stemSeparator.release()
        reliablePending.clear()
        reliableEligibleAt.clear()
        reliableRetryAt.clear()
        reliableFailures.clear()
        headAttempts.clear()
        headSkipLogged.clear()
        provisional.clear()
        restoreAttempted.clear()
        restoreCompleted.clear()
        shortDecodes.clear()
        badRenditions.clear()
        triedRenditions.clear()
        discarded.clear()
        tracker.release()
        vocals.release()
    }

    private companion object {
        /** Server analysis schema required by curve-aware Automix 2.5 mix-v6. */
        const val REMOTE_CURVE_SCHEMA = 6
        const val LOCAL_METADATA_SCHEMA = 6
        const val LOCAL_TRANSITION_WINDOW_SECONDS = 60.0
        const val TAG = "BitChordTrackAnalyzer"

        /**
         * What an unmeasured instant reads as. Below the policy's VOCAL_ACTIVE_THRESHOLD by
         * design, so absence of measurement is never mistaken for absence of a vocal, or for the
         * presence of one.
         */
        const val NEUTRAL_VOCAL = 0.5

        /** Full curve-heavy analyses kept in Java RAM; older entries live in AnalysisStore. */
        const val RESULTS_RAM_ENTRIES = 12

        /** Lightweight per-track guards get more room than the full analyses but stay bounded. */
        const val TRACK_AUX_ENTRIES = 48

        /** Rendition keys can outnumber tracks (AAC/Opus/Lossless), so give them a wider cap. */
        const val RENDITION_AUX_ENTRIES = 96

        /** Network/source errors are retried, but never from the one-second planning heartbeat. */
        const val RELIABLE_RETRY_BASE_MS = 15_000L
        const val RELIABLE_RETRY_MAX_MS = 5L * 60L * 1000L
        const val RELIABLE_CACHE_GRACE_MS = 0L

        /**
         * How much more than the average-bitrate estimate of the opening window
         * to insist on before decoding it. Covers the container header and the
         * fact that a track's opening is rarely at its own average bitrate.
         */
        const val HEAD_BYTES_MARGIN = 1.35

        /**
         * Floor under the computed threshold, and the whole requirement when the
         * duration is unknown. Roughly fifteen seconds at 128 kbps — a little
         * over [MIN_HEAD_SECONDS], so it guarantees a parsable container and a
         * usable decode without quietly reinstating the thirty-second demand the
         * bitrate estimate was just lowered away from.
         */
        const val MIN_HEAD_BYTES = 256L * 1024L

        /**
         * How much of the container's stated duration must actually decode
         * before the whole-track pass is trusted. Not 1.0: a decoder legitimately
         * comes up a frame or two short of the container's rounding, and
         * refusing over that would refuse everything.
         */
        const val MIN_DECODED_FRACTION = 0.95

        /** Refusals before a track is written off as truncated rather than still filling in. */
        const val MAX_SHORT_DECODE_ATTEMPTS = 3

        /**
         * Clean-slate retries per rendition. One: a discard is worth doing when
         * the bytes on disk are unrecoverable and nothing would otherwise
         * overwrite them, and worth doing exactly once, because a second identical
         * answer means the source is serving that file rather than the cache
         * having mangled it.
         */
        const val MAX_RENDITION_DISCARDS = 1

        /**
         * How far two renditions' container durations may differ and still count
         * as the same cut. Generous enough for codec padding and the player's own
         * rounding, tight enough that a different edit of the same song — where a
         * borrowed beat grid would be useless — is rejected.
         */
        const val RENDITION_DURATION_TOLERANCE = 1.0

        /**
         * Stand-in bitrate for a rendition whose real length isn't recorded yet,
         * used only to rank copies against each other in [headSecondsOf]. About
         * 160kbps, the middle of what the streams in play here run at.
         */
        const val ASSUMED_BYTES_PER_SECOND = 20_000.0

        /**
         * The least decoded audio a head-only tempo estimate is allowed to rest
         * on. Twelve seconds is around 24 beats at 120 bpm — enough for the
         * grid's own confidence measure to mean something.
         */
        const val MIN_HEAD_SECONDS = 12.0

        /**
         * How much more of the file has to be cached before the head is worth
         * decoding again. Doubling bounds the attempts to a handful over a whole
         * download while still catching up quickly on a high-bitrate rendition
         * whose first attempt covered only a few seconds.
         */
        const val HEAD_RETRY_GROWTH = 2

        /** Open-Unmix is fixed-width; long overlays are stitched from bounded windows. */
        const val STEM_CHUNK_USEFUL_SECONDS = 18.0
        const val STEM_CHUNK_OVERLAP_SECONDS = 0.25
        const val STEM_EDGE_PAD_SECONDS = 0.40
        const val STEM_TOTAL_MAX_SECONDS = 90.0
        const val STEM_MIN_MS = 2_000L
        const val STEM_KEY_QUANTUM_MS = 250L
        const val MAX_STEM_WINDOWS = 3
        const val MAX_STEM_BYTES = 32L * 1024L * 1024L
    }
}