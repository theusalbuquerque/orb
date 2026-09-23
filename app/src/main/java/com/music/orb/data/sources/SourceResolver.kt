package com.music.orb.data.sources

import android.net.Uri
import com.music.orb.data.TrackLog
import com.music.orb.data.innertube.StreamResolver
import com.music.orb.data.model.Song
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AudioQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-source stream policy used by playback.
 *
 * Playback decisions intentionally follow BitChord v1.5: the active network
 * ceiling is answered once, HIGH asks every source for its best rendition,
 * sources ranked above YouTube race rather than queue behind one another, and
 * an in-flight stream is replaced only by a lossless copy or a materially
 * higher bitrate rendition.
 *
 * The badge/preflight methods at the bottom are compatibility adapters for the
 * Orb UI. They never influence source ordering, stream selection or upgrades.
 */
object SourceResolver {
    private const val TAG = "BitChord"
    private const val MATCH_CANDIDATES = 15
    private const val STREAM_ATTEMPTS = 3
    private const val SAME_RECORDING_SEC = 3
    private const val LIVE_UPGRADE_DURATION_SEC = 2
    private const val UPGRADE_MIN_GAIN_KBPS = 96
    // AAC 250 -> AAC 320 is a real step in Orb's quality ladder even though
    // the nominal delta is only ~70 kbps. Keep the stricter 96 kbps floor for
    // generic "Best" upgrades, but allow the explicit AAC ceiling to climb
    // through that final rung.
    private const val AAC_UPGRADE_MIN_GAIN_KBPS = 48
    private const val YOUTUBE_BEST_AAC_KBPS = 256
    private const val COLLECTION_BADGE_PROBE_TRACKS = 3
    private const val COLLECTION_LOSSLESS_PROBE_TRACKS = 3
    // Collection-level quality only appears after more than three tracks
    // have independently confirmed the tier during real playback/probing.
    private const val COLLECTION_BADGE_MIN_CONFIRMED_TRACKS = 4
    private const val FIRST_NOTE_PINNED_SOURCE_MS = 900L
    private const val FIRST_NOTE_YOUTUBE_QUERY_LIMIT = 2
    private const val FIRST_NOTE_YOUTUBE_CANDIDATES = 6

    // ---------------------------------------------------------------------
    // BitChord v1.5 playback policy
    // ---------------------------------------------------------------------

    fun requestForNow(): StreamRequest {
        // Lossless is a separate per-network ceiling. HIGH/AAC is still a
        // lossy first-note request and must never be interpreted as Lossless;
        // doing that put the optional source lookup directly on the Play hot
        // path and was the main reason a tap could sit silent for ~20 seconds.
        if (AppSettings.effectiveMaximumAudioQuality) return StreamRequest.Lossless

        return when (val ceiling = AppSettings.effectiveAudioQuality) {
            AudioQuality.AAC -> StreamRequest.Aac(ceiling.maxKbps)
            AudioQuality.HIGH -> StreamRequest.Best
            AudioQuality.MEDIUM, AudioQuality.LOW -> StreamRequest.Capped(ceiling.maxKbps)
        }
    }

    suspend fun resolve(uri: Uri): SourceStream? {
        val configId = uri.getQueryParameter("s") ?: return null
        val trackId = uri.getQueryParameter("t") ?: return null
        return resolve(configId, trackId, targetIn(uri))
    }

    /**
     * First-note path for a catalogue/source-backed queue item.
     *
     * The normal [resolve] is intentionally quality-oriented: it can ask a pinned Lossless source,
     * search fallbacks and compare candidates before returning. That is the correct policy once
     * audio is already playing, but it is the wrong thing to put between a Play tap and the first
     * sample. A TIDAL/source item could therefore sit on “audio info pending” for several seconds
     * even though YouTube had an Opus carrier capable of starting immediately.
     *
     * Race two *cheap* answers instead:
     *  - the pinned source gets a small chance to serve the exact track directly;
     *  - YouTube Music searches the metadata and opens its fastest Opus/AAC carrier.
     *
     * Whichever proves usable first starts playback. [QualityUpgrade] asks the full-quality
     * question again after the track is audible, so choosing Opus here is latency policy, not a
     * permanent downgrade.
     */
    suspend fun resolveImmediate(uri: Uri, budgetMs: Long = 1_800L): SourceStream? = coroutineScope {
        val configId = uri.getQueryParameter("s") ?: return@coroutineScope null
        val trackId = uri.getQueryParameter("t") ?: return@coroutineScope null
        val target = targetIn(uri)
        val active = SourceRegistry.active()
        val pinned = SourceRegistry.instance(configId)
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE }
        val jobs = mutableListOf<Pair<String, Deferred<SourceStream?>>>()

        if (pinned != null) {
            jobs += "pinned" to async {
                withTimeoutOrNull(FIRST_NOTE_PINNED_SOURCE_MS.coerceAtMost(budgetMs)) {
                    attempt(pinned) { pinned.stream(trackId, requestForNow()) }
                        ?.takeIf { validateStream(pinned, trackId, it) }
                }
            }
        }

        if (youtube != null && target.title.isNotBlank()) {
            jobs += "youtube" to async {
                withTimeoutOrNull(budgetMs) {
                    immediateYouTubeFallback(youtube, target)
                }
            }
        }

        try {
            while (jobs.isNotEmpty()) {
                val (label, stream) = select<Pair<String, SourceStream?>> {
                    jobs.forEach { (name, deferred) ->
                        deferred.onAwait { name to it }
                    }
                }
                jobs.removeAll { it.first == label }
                if (stream != null) {
                    TrackLog.d(
                        TAG,
                        "first-note source race: '${target.title}' started from $label at ${stream.format.summary}",
                    )
                    return@coroutineScope stream
                }
            }
            null
        } finally {
            jobs.forEach { (_, deferred) -> deferred.cancel() }
        }
    }

    private suspend fun immediateYouTubeFallback(
        youtube: MusicSource,
        target: TrackMatcher.Target,
    ): SourceStream? {
        for (query in TrackMatcher.queries(target).take(FIRST_NOTE_YOUTUBE_QUERY_LIMIT)) {
            val candidates = attempt(youtube) {
                youtube.search(query, limit = FIRST_NOTE_YOUTUBE_CANDIDATES, waitForAll = false)
            } ?: continue
            val ranked = TrackMatcher.ranked(candidates, target)
            val match = if (target.durationSec != null) {
                // Fast must not mean wrong recording. When runtime is known, only an answer inside
                // the same-recording tolerance is allowed to become the emergency carrier.
                ranked.firstOrNull { song -> TrackMatcher.withinSeconds(song, target, SAME_RECORDING_SEC) }
            } else {
                ranked.firstOrNull()
            }
            if (match == null) continue
            val id = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
            val carrier = runCatching { StreamResolver.resolveImmediatePlayback(id) }.getOrNull() ?: continue
            return carrier.copy(durationSec = TrackMatcher.secondsOf(match.durationText))
        }
        return null
    }

    fun targetIn(uri: Uri) = TrackMatcher.Target(
        title = uri.getQueryParameter("n").orEmpty(),
        artist = uri.getQueryParameter("a").orEmpty(),
        durationSec = uri.getQueryParameter("d")?.toIntOrNull(),
        isExplicit = uri.getQueryParameter("e")?.let { it == "1" },
        albumName = uri.getQueryParameter("al"),
        releaseYear = uri.getQueryParameter("y")?.toIntOrNull(),
        sourceVideoId = uri.getQueryParameter("sv"),
    )

    suspend fun resolve(
        configId: String,
        trackId: String,
        target: TrackMatcher.Target,
    ): SourceStream? {
        val request = requestForNow()
        val pinned = SourceRegistry.instance(configId)
        val active = SourceRegistry.active()

        if (request is StreamRequest.Lossless && pinned?.kind?.canServeLossless != true) {
            for (source in rankedAbove(configId, active)) {
                if (!source.kind.canServeLossless) continue
                val upgraded = matchAndStream(source, target, request) ?: continue
                if (upgraded.format.isLossless != true) continue
                TrackLog.d(TAG, "lossless upgrade: '${target.title}' served by ${source.displayName}")
                return upgraded
            }
        }

        if (pinned != null) {
            attempt(pinned) { pinned.stream(trackId, request) }
                ?.takeIf { validateStream(pinned, trackId, it) }
                ?.let { return it }
        }

        val fallback = bestAcross(
            sources = active.filterNot { it.configId == configId },
            target = target,
            request = request,
        ) ?: return null
        TrackLog.d(TAG, "fallback: '${target.title}' served by ${fallback.first.displayName}")
        return fallback.second
    }

    suspend fun substituteForYouTube(target: TrackMatcher.Target): SourceStream? {
        if (target.title.isBlank()) return null
        val active = SourceRegistry.active()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val result = bestAcross(rankedAbove(youtube.configId, active), target, requestForNow()) ?: return null
        TrackLog.d(
            TAG,
            "substituted: '${target.title}' served by ${result.first.displayName} over YouTube at " +
                result.second.format.summary + if (result.second.belowRequest) " (below request)" else "",
        )
        return result.second
    }

    /** Speculative v1.5 warm-up: only sources explicitly marked cheap to probe. */
    suspend fun prefetchSubstitute(target: TrackMatcher.Target): SourceStream? {
        if (target.title.isBlank()) return null
        val active = SourceRegistry.active()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val quick = rankedAbove(youtube.configId, active).filter { it.kind.worthPrefetching }
        if (quick.isEmpty()) return null
        val result = bestAcross(quick, target, requestForNow()) ?: return null
        TrackLog.d(TAG, "warmed: '${target.title}' from ${result.first.displayName} at ${result.second.format.summary}")
        return result.second
    }

    suspend fun upgradeFor(
        target: TrackMatcher.Target,
        playing: StreamFormat? = null,
    ): SourceStream? {
        if (target.title.isBlank() || target.durationSec == null) return null
        val active = SourceRegistry.active()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE } ?: return null
        val request = requestForNow()
        val result = bestAcross(
            sources = rankedAbove(youtube.configId, active),
            target = target,
            request = request,
            waitForAll = true,
            strictLength = true,
        ) { _, stream ->
            // Maximum on Wi-Fi is a two-stage ladder: exhaust the real
            // Lossless/Hi-Res candidates first, and only then let
            // QualityUpgrade fall back to verified Hi-Quality AAC. A source
            // that answers a Lossless request with AAC is useful as a
            // fallback, but it must never end the Lossless phase early.
            (request !is StreamRequest.Lossless || stream.format.isLossless == true) &&
                worthSwapping(stream.format, playing)
        } ?: return null
        TrackLog.d(TAG, "upgrade found: '${target.title}' at ${result.second.format.summary} from ${result.first.displayName}")
        return result.second
    }

    /**
     * Best candidate for the immediate queue successor while the current track is already audible.
     *
     * This is deliberately different from first-note resolution. B has time to look for quality
     * while A is playing, so Maximum spends the first phase only on genuine Lossless/Hi-Res
     * candidates and falls back to verified Hi-Q AAC only if that phase produces nothing.
     * On lossy ceilings, Hi-Q is the preferred prepared carrier. The caller still resolves a
     * cheap official fallback in parallel, so a miss here can never delay the handoff.
     */
    suspend fun queuedBestCandidate(song: Song, budgetMs: Long): SourceStream? {
        if (song.localUri != null || budgetMs <= 0L) return null
        val target = TrackMatcher.targetOf(song)
        if (target.title.isBlank()) return null

        return when (requestForNow()) {
            StreamRequest.Lossless -> {
                // Give the real lossless catalogues most of the decision window, but keep a
                // guaranteed tail for Hi-Q. A hung/slow source must not consume the entire
                // successor-preparation budget and strand B on the emergency carrier.
                val losslessBudget = (budgetMs * 2L / 3L).coerceIn(4_000L, 20_000L)
                val active = SourceRegistry.active()
                val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE }
                val sources = youtube?.let { rankedAbove(it.configId, active) } ?: active
                val lossless = withTimeoutOrNull(losslessBudget) {
                    bestAcross(
                        sources = sources,
                        target = target,
                        request = StreamRequest.Lossless,
                        // Queue preparation is latency-sensitive: the first *real* lossless
                        // answer is already above every lossy fallback. bestAcross still folds
                        // answers completed in the same turn, so an immediately available
                        // Hi-Res copy wins over ordinary Lossless.
                        waitForAll = false,
                        strictLength = target.durationSec != null,
                    ) { _, stream -> stream.format.isLossless == true }?.second
                }
                if (lossless != null) {
                    lossless
                } else {
                    val hiqBudget = (budgetMs - losslessBudget).coerceAtLeast(4_000L)
                    withTimeoutOrNull(hiqBudget) {
                        StreamResolver.resolveHiQualityUpgrade(song.videoId)
                    }
                }
            }

            is StreamRequest.Aac, StreamRequest.Best -> withTimeoutOrNull(budgetMs) {
                coroutineScope {
                    // YouTube's fresh Hi-Q lookup and higher-ranked lossy sources can run
                    // together because A is already playing. Keep whichever proves better.
                    val youtubeHiq = async {
                        StreamResolver.resolveHiQualityUpgrade(song.videoId)
                    }
                    val sourceHiq = async {
                        if (target.durationSec == null) null
                        else upgradeFor(
                            target = target,
                            playing = StreamFormat(codec = "opus", kbps = 128),
                        )
                    }
                    val a = youtubeHiq.await()
                    val b = sourceHiq.await()
                    when {
                        a == null -> b
                        b == null -> a
                        isBetter(b.format, a.format) -> b
                        else -> a
                    }
                }
            }

            is StreamRequest.Capped -> null
        }
    }

    /** Download compatibility: choose only a cross-source copy worth keeping over YouTube. */
    suspend fun forDownload(target: TrackMatcher.Target): SourceStream? = coroutineScope {
        if (target.title.isBlank()) return@coroutineScope null
        val active = SourceRegistry.active()
        val youtube = active.firstOrNull { it.kind == SourceKind.YOUTUBE }
        val sources = youtube?.let { rankedAbove(it.configId, active) } ?: active
        if (sources.isEmpty()) return@coroutineScope null

        val request = requestForNow()
        val result = bestAcross(
            sources = sources,
            target = target,
            request = request,
            waitForAll = true,
            strictLength = target.durationSec != null,
        ) ?: return@coroutineScope null

        val stream = result.second
        when {
            stream.format.isLossless == true -> stream
            (stream.format.kbps ?: 0) > YOUTUBE_BEST_AAC_KBPS -> stream
            else -> null
        }
    }

    fun worthSwapping(candidate: StreamFormat, playing: StreamFormat?): Boolean {
        val request = requestForNow()
        // A module is allowed to return something better than the requested
        // lossy tier. On mobile data that must NOT accidentally promote the
        // track to FLAC: Lossless/Hi-Res is a Wi-Fi Maximum-only ceiling.
        if (candidate.isLossless == true) return request is StreamRequest.Lossless
        val current = playing?.kbps ?: return false
        val offered = candidate.kbps ?: return false
        val minimumGain = when (request) {
            is StreamRequest.Aac, StreamRequest.Best -> AAC_UPGRADE_MIN_GAIN_KBPS
            else -> UPGRADE_MIN_GAIN_KBPS
        }
        return offered - current >= minimumGain
    }

    /**
     * Whether the current network ceiling still leaves a worthwhile live
     * upgrade above [playing].
     *
     * This is intentionally broader than Lossless. Orb's normal hierarchy is
     * AAC 320 -> AAC 250 -> Opus 128 -> AAC 128, so a song that started quickly
     * on Opus must still be allowed to climb to AAC while it is playing.
     */
    fun requestCouldImprove(playing: StreamFormat?): Boolean {
        if (playing?.isLossless == true) return false
        val current = playing?.kbps
        return when (val request = requestForNow()) {
            // Lossless still requires a real source above YouTube.
            StreamRequest.Lossless -> canSubstituteForYouTube()
            // AAC/Best can improve by re-asking YouTube itself for a Hi-Q AAC
            // rendition, so external-source availability must not gate this.
            is StreamRequest.Aac ->
                current == null || request.maxKbps - current >= AAC_UPGRADE_MIN_GAIN_KBPS
            StreamRequest.Best ->
                current == null || 320 - current >= AAC_UPGRADE_MIN_GAIN_KBPS
            is StreamRequest.Capped ->
                canSubstituteForYouTube() && current != null &&
                    request.maxKbps - current >= UPGRADE_MIN_GAIN_KBPS
        }
    }

    /** Cheap settings-only gate used before the decoder has reported bitrate. */
    fun requestAllowsLiveUpgrade(): Boolean = when (requestForNow()) {
        StreamRequest.Lossless -> canSubstituteForYouTube()
        // The normal AAC hierarchy may retry YouTube itself in the background,
        // including on mobile data. No external module is required for that.
        StreamRequest.Best,
        is StreamRequest.Aac -> true
        is StreamRequest.Capped -> false
    }

    /**
     * Whether a prepared/live stream has actually reached the quality ceiling
     * for the current network. The old queue code treated every `hiq` route as
     * final, which froze AAC 250 as if it were AAC 320 and prevented the normal
     * live second-look from climbing the last rung.
     */
    fun isAtRequestedQualityCeiling(format: StreamFormat): Boolean = when (val request = requestForNow()) {
        StreamRequest.Lossless -> format.isLossless == true
        is StreamRequest.Aac -> format.isLossless != true && (format.kbps ?: 0) >= request.maxKbps
        StreamRequest.Best -> format.isLossless != true && (format.kbps ?: 0) >= 320
        is StreamRequest.Capped -> format.isLossless != true && (format.kbps ?: 0) >= request.maxKbps
    }

    /** Whether an already validated queue candidate still respects the current network ceiling. */
    fun preparedQueueCandidateAllowed(format: StreamFormat): Boolean = when (requestForNow()) {
        StreamRequest.Lossless -> true // Lossless is preferred; verified Hi-Q is its allowed fallback.
        StreamRequest.Best, is StreamRequest.Aac -> format.isLossless != true
        is StreamRequest.Capped -> false
    }

    fun sameRecordingAs(candidateSec: Int?, playingSec: Int?): Boolean =
        candidateSec != null && playingSec != null && kotlin.math.abs(candidateSec - playingSec) <= LIVE_UPGRADE_DURATION_SEC

    fun canSubstituteForYouTube(): Boolean {
        val active = SourceRegistry.active()
        val youtubeIndex = active.indexOfFirst { it.kind == SourceKind.YOUTUBE }
        if (youtubeIndex <= 0) return false
        val request = requestForNow()
        return active.take(youtubeIndex).any { source ->
            request is StreamRequest.Lossless ||
                source.kind !in setOf(SourceKind.ADDON, SourceKind.TIDAL)
        }
    }

    private fun rankedAbove(configId: String, active: List<MusicSource>): List<MusicSource> {
        val index = active.indexOfFirst { it.configId == configId }
        return if (index < 0) active else active.take(index)
    }

    private suspend fun bestAcross(
        sources: List<MusicSource>,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
        accept: (MusicSource, SourceStream) -> Boolean = { _, _ -> true },
    ): Pair<MusicSource, SourceStream>? = coroutineScope {
        // Orb Navidrome exposes original files only. Keep HTTP addons off the
        // lossy hot path so a raw FLAC cannot bypass AAC/Opus data ceilings.
        val eligibleSources = if (request is StreamRequest.Lossless) {
            sources
        } else {
            // Native hifi-api and private file addons are Lossless-only. Keep
            // both off the AAC/Opus first-note path.
            sources.filterNot { it.kind in setOf(SourceKind.ADDON, SourceKind.TIDAL) }
        }
        if (eligibleSources.isEmpty()) return@coroutineScope null
        val jobs = eligibleSources.map { source ->
            source to async {
                matchAndStream(source, target, request, waitForAll, strictLength)
            }
        }.toMutableList()

        var best: Pair<MusicSource, SourceStream>? = null
        try {
            while (jobs.isNotEmpty()) {
                val (source, stream) = select<Pair<MusicSource, SourceStream?>> {
                    jobs.forEach { (source, deferred) ->
                        deferred.onAwait { source to it }
                    }
                }
                jobs.removeAll { it.first.configId == source.configId }
                if (stream == null || !accept(source, stream)) continue
                best = chooseBetter(best, source to stream)

                // Fold answers that completed in the same scheduling turn so a
                // higher-quality already-finished stream is not lost to list order.
                val completed = jobs.filter { it.second.isCompleted }
                for ((other, deferred) in completed) {
                    jobs.removeAll { it.first.configId == other.configId }
                    val candidate = deferred.await()
                    if (candidate != null && accept(other, candidate)) {
                        best = chooseBetter(best, other to candidate)
                    }
                }
                // Live/first-note races may return as soon as a usable
                // answer wins. Background upgrades and downloads pass
                // waitForAll=true because they are explicitly choosing the
                // best available rendition, so keep collecting every source
                // instead of cancelling a slower Lossless/Hi-Res answer.
                if (best != null && !waitForAll) break
            }
            best
        } finally {
            jobs.forEach { (_, deferred) -> deferred.cancel() }
        }
    }

    private fun chooseBetter(
        current: Pair<MusicSource, SourceStream>?,
        candidate: Pair<MusicSource, SourceStream>,
    ): Pair<MusicSource, SourceStream> {
        if (current == null) return candidate
        return if (isBetter(candidate.second.format, current.second.format)) candidate else current
    }

    private fun isBetter(candidate: StreamFormat, current: StreamFormat): Boolean {
        if (candidate.isLossless != current.isLossless) return candidate.isLossless == true
        if (candidate.isLossless == true && current.isLossless == true) {
            if (candidate.losslessTier != current.losslessTier) {
                return candidate.losslessTier.ordinal > current.losslessTier.ordinal
            }
            if ((candidate.bitDepth ?: 0) != (current.bitDepth ?: 0)) {
                return (candidate.bitDepth ?: 0) > (current.bitDepth ?: 0)
            }
            if ((candidate.sampleRateHz ?: 0) != (current.sampleRateHz ?: 0)) {
                return (candidate.sampleRateHz ?: 0) > (current.sampleRateHz ?: 0)
            }
        }
        return (candidate.kbps ?: 0) > (current.kbps ?: 0)
    }

    private suspend fun matchAndStream(
        source: MusicSource,
        target: TrackMatcher.Target,
        request: StreamRequest,
        waitForAll: Boolean = false,
        strictLength: Boolean = false,
    ): SourceStream? {
        for (query in TrackMatcher.queries(target)) {
            val candidates = attempt(source) {
                source.search(query, limit = MATCH_CANDIDATES, waitForAll = waitForAll)
            } ?: continue
            var matches = TrackMatcher.ranked(candidates, target)
            // A YouTube catalogue track is the authoritative recording. An
            // external source is allowed to replace it only when the candidate
            // can be verified as the same release, not merely a same-title,
            // same-duration result. This is the guard that prevents a cover or
            // tribute recording from playing underneath the official metadata.
            if (target.sourceVideoId != null && source.kind != SourceKind.YOUTUBE) {
                matches = matches.filter { TrackMatcher.verifiedForSubstitution(it, target) }
            }
            if (strictLength && target.durationSec != null) {
                matches = matches.filter { TrackMatcher.withinSeconds(it, target, LIVE_UPGRADE_DURATION_SEC) }
            }
            if (matches.isEmpty()) continue
            streamBest(source, matches, target, request)?.let { return it }
        }
        return null
    }

    internal fun preferred(
        matches: List<Song>,
        target: TrackMatcher.Target,
        wantsLossless: Boolean,
    ): List<Song> {
        val sameLength = matches.filter { TrackMatcher.withinSeconds(it, target, SAME_RECORDING_SEC) }
        val eligible = sameLength.ifEmpty { matches }
        if (!wantsLossless) return eligible
        return eligible.sortedByDescending { it.sourceQuality == ModuleSource.LOSSLESS }
    }

    private suspend fun streamBest(
        source: MusicSource,
        matches: List<Song>,
        target: TrackMatcher.Target,
        request: StreamRequest,
    ): SourceStream? {
        val wantsLossless = request is StreamRequest.Lossless
        val ordered = preferred(matches, target, wantsLossless)
        var settleFor: SourceStream? = null
        for (match in ordered.take(STREAM_ATTEMPTS)) {
            val trackId = SourceRegistry.parseTrackKey(match.videoId)?.second ?: match.videoId
            val opened = attempt(source) { source.stream(trackId, request) } ?: continue
            if (!validateStream(source, trackId, opened)) continue
            val stream = opened.copy(durationSec = TrackMatcher.secondsOf(match.durationText))
            val served = stream.format
            if (!wantsLossless || served.isLossless == true || served.statesNothingLossy) return stream
            settleFor = betterOf(settleFor, stream.copy(belowRequest = true))
        }
        return settleFor
    }

    /**
     * Reject malformed catalogue answers before they can be cached or handed
     * to Media3. `Uri.parse()` is intentionally permissive, whereas the actual
     * transport is OkHttp; using [SourceStream.hasPlayableHttpUrl] here keeps
     * those two layers from disagreeing at playback time.
     */
    private fun validateStream(source: MusicSource, trackId: String, stream: SourceStream): Boolean {
        if (stream.hasPlayableHttpUrl()) return true
        TrackLog.w(
            TAG,
            "${source.displayName} returned invalid stream URL for $trackId; " +
                "discarding ${stream.url.streamOriginForLog()}",
        )
        return false
    }

    private fun betterOf(current: SourceStream?, candidate: SourceStream): SourceStream {
        if (current == null) return candidate
        return if (isBetter(candidate.format, current.format)) candidate else current
    }

    private val StreamFormat.statesNothingLossy: Boolean
        get() = isLossless == null && kbps == null

    private suspend fun <T> attempt(source: MusicSource, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "${source.displayName} failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    // ---------------------------------------------------------------------
    // Orb UI compatibility. These are observations only: none can pin or
    // reorder a playback source, so the v1.5 engine above stays authoritative.
    // ---------------------------------------------------------------------

    data class PlayableBadgeQuality(
        val resolved: Boolean = false,
        val losslessTier: LosslessTier = LosslessTier.NONE,
        val hiQuality: Boolean = false,
    )

    data class PlaylistBadgeAvailability(
        val isExplicit: Boolean,
        val isLossless: Boolean,
        val isHiResLossless: Boolean,
        val losslessKnown: Boolean,
    )

    private val confirmedTiers = ConcurrentHashMap<String, LosslessTier>()
    private val releaseExplicit = ConcurrentHashMap<String, Boolean>()
    private val playlistBadgeCache = ConcurrentHashMap<String, PlaylistBadgeAvailability>()
    private val collectionBadgeCache = ConcurrentHashMap<String, PlayableBadgeQuality>()
    // The exact card subtitle is not a stable collection identity: Home, Search,
    // artist shelves and Detail can describe the same release differently.
    // Keep aliases by browse id and a conservative release identity so a tier
    // proven on one surface is immediately visible on every other surface.
    private val collectionBadgeByBrowseId = ConcurrentHashMap<String, PlayableBadgeQuality>()
    private val collectionBadgeByReleaseIdentity = ConcurrentHashMap<String, PlayableBadgeQuality>()
    private val _badgeCacheRevision = MutableStateFlow(0L)
    val badgeCacheRevision = _badgeCacheRevision.asStateFlow()

    fun losslessFeatureEnabled(): Boolean =
        requestForNow() is StreamRequest.Lossless && SourceRegistry.active().any { it.kind.canServeLossless }

    fun cachedTrackLosslessTier(song: Song): LosslessTier? = confirmedTiers[song.videoId]
    fun cachedTrackLossless(song: Song): Boolean? = cachedTrackLosslessTier(song)?.isLossless

    private fun qualifiesForHiQuality(mimeType: String?, kbps: Int?): Boolean {
        val rate = kbps ?: return false
        if (rate < 256) return false
        val mime = mimeType?.lowercase() ?: return false
        return mime == "audio/aac" || mime.endsWith("/aac") || mime.contains("mp4a") || "m4a" in mime || "mp4" in mime
    }

    fun cachedTrackPlayableBadge(song: Song): PlayableBadgeQuality {
        val tier = confirmedTiers[song.videoId] ?: LosslessTier.NONE
        val remoteTrack = SourceRegistry.parseTrackKey(song.videoId) == null
        val mime = if (remoteTrack) StreamResolver.resolvedMimeType(song.videoId) else null
        val kbps = if (remoteTrack) StreamResolver.resolvedBitrateKbps(song.videoId) else null
        val known = tier.isLossless || kbps != null
        return PlayableBadgeQuality(
            resolved = known,
            losslessTier = tier,
            hiQuality = tier == LosslessTier.NONE && qualifiesForHiQuality(mime, kbps),
        )
    }

    suspend fun prepareTrackPlayableBadge(song: Song, allowLosslessProbe: Boolean = true): PlayableBadgeQuality {
        val isYouTubeTrack = SourceRegistry.parseTrackKey(song.videoId) == null
        if (isYouTubeTrack) {
            // Prove the ordinary AAC/MP4 tier independently of whether the
            // collection detail page has ever been opened. This makes Home and
            // other collection surfaces self-sufficient instead of relying on
            // DetailScreen to populate the quality cache first.
            runCatching { StreamResolver.resolve(song.videoId) }
        }

        val lossy = cachedTrackPlayableBadge(song)
        if (!allowLosslessProbe || !isYouTubeTrack || !losslessFeatureEnabled()) return lossy

        // Collection badges are allowed to do a background source preflight.
        // A successful stream lookup is enough to state what this collection
        // can actually be upgraded to, but it does not write into the
        // playback-confirmed per-track cache used by the Now Playing badge.
        val candidate = runCatching {
            substituteForYouTube(TrackMatcher.targetOf(song))
        }.getOrNull()

        val tier = candidate?.format?.losslessTier ?: LosslessTier.NONE
        return if (tier.isLossless) {
            PlayableBadgeQuality(
                resolved = true,
                losslessTier = tier,
                hiQuality = false,
            )
        } else {
            lossy
        }
    }

    private fun collectionKey(title: String, subtitle: String, browseId: String?) =
        listOf(browseId.orEmpty(), title.trim().lowercase(), subtitle.trim().lowercase()).joinToString("|")

    private fun normalizedCollectionBrowseId(browseId: String?): String? = browseId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        // YouTube sometimes exposes playlist browse ids with or without the VL
        // wrapper depending on the surface; they are the same collection.
        ?.removePrefix("VL")

    private fun releaseIdentityAliases(title: String, subtitle: String): List<String> {
        val normalizedTitle = normalizeCollectionToken(title).takeIf { it.isNotBlank() } ?: return emptyList()
        val parts = subtitle.split("•", "·")
            .map(String::trim)
            .filter(String::isNotBlank)
        val year = parts.firstOrNull { it.matches(Regex("(?:19|20)\\d{2}")) }.orEmpty()
        val credit = parts.firstOrNull { part ->
            val token = normalizeCollectionToken(part)
            token.isNotBlank() &&
                !part.matches(Regex("(?:19|20)\\d{2}")) &&
                !token.matches(Regex("(?:album|álbum|single|ep|playlist|lista de reprodução|lista de reproduccion|lista de reproducción)")) &&
                !token.matches(Regex("\\d+\\s+(?:songs?|musicas?|músicas?|canciones?)"))
        }?.let(::normalizeCollectionToken).orEmpty()

        // Different surfaces often omit the year while retaining the artist/owner
        // credit. Store both the most specific identity and that stable credit
        // alias so a quality grade verified on a card survives into Detail (and
        // vice versa) even when their subtitle formatting is not identical.
        return buildList {
            if (credit.isNotBlank() && year.isNotBlank()) {
                add("$normalizedTitle|$year|$credit")
            }
            if (credit.isNotBlank()) {
                add("$normalizedTitle|credit:$credit")
            } else if (year.isNotBlank()) {
                add("$normalizedTitle|year:$year")
            }
        }.distinct()
    }

    private fun normalizeCollectionToken(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun PlayableBadgeQuality.strength(): Int = when {
        losslessTier.isHiRes -> 4
        losslessTier.isLossless -> 3
        hiQuality -> 2
        resolved -> 1
        else -> 0
    }

    private fun strongestBadge(vararg values: PlayableBadgeQuality?): PlayableBadgeQuality =
        values.filterNotNull().maxByOrNull { it.strength() } ?: PlayableBadgeQuality()

    private fun cacheCollectionPlayableBadgeAliases(
        title: String,
        subtitle: String,
        browseId: String?,
        value: PlayableBadgeQuality,
    ) {
        val exactKey = collectionKey(title, subtitle, browseId)
        collectionBadgeCache.compute(exactKey) { _, old -> strongestBadge(old, value) }
        normalizedCollectionBrowseId(browseId)?.let { id ->
            collectionBadgeByBrowseId.compute(id) { _, old -> strongestBadge(old, value) }
        }
        releaseIdentityAliases(title, subtitle).forEach { identity ->
            collectionBadgeByReleaseIdentity.compute(identity) { _, old -> strongestBadge(old, value) }
        }
    }

    fun cachedCollectionPlayableBadge(
        title: String,
        subtitle: String,
        browseId: String?,
        songs: List<Song> = emptyList(),
    ): PlayableBadgeQuality {
        val key = collectionKey(title, subtitle, browseId)
        val cached = strongestBadge(
            collectionBadgeCache[key],
            normalizedCollectionBrowseId(browseId)?.let(collectionBadgeByBrowseId::get),
            *releaseIdentityAliases(title, subtitle)
                .mapNotNull(collectionBadgeByReleaseIdentity::get)
                .toTypedArray(),
        )
        if (songs.isEmpty()) return cached

        // Playback can confirm more tracks after an earlier collection probe.
        // Re-evaluate the currently known rows before trusting a stale cached
        // "no badge" result, otherwise a collection could stay unbadged even
        // after its fourth track has been verified.
        val observed = collectionQualityFrom(songs.map(::cachedTrackPlayableBadge))
        if (observed.losslessTier.isLossless || observed.hiQuality) {
            return strongestBadge(cached, observed)
        }
        return strongestBadge(cached, observed)
    }

    suspend fun prepareCollectionPlayableBadge(
        title: String,
        subtitle: String,
        browseId: String?,
        songs: List<Song>,
        allowLosslessProbe: Boolean = true,
    ): PlayableBadgeQuality {
        if (songs.isEmpty()) {
            return cachedCollectionPlayableBadge(title, subtitle, browseId, songs)
        }

        // A collection should not need to be opened manually before its badges
        // become visible. Probe a small representative set concurrently: albums
        // normally share one mastering tier. Only the first four perform the
        // more expensive cross-source Lossless lookup; all sampled tracks still
        // prove their ordinary AAC/MP4 tier.
        val probeSongs = songs.take(COLLECTION_BADGE_PROBE_TRACKS)
        val facts = coroutineScope {
            probeSongs.mapIndexed { index, song ->
                async {
                    prepareTrackPlayableBadge(
                        song,
                        allowLosslessProbe = allowLosslessProbe && index < COLLECTION_LOSSLESS_PROBE_TRACKS,
                    )
                }
            }.awaitAll()
        }

        val result = collectionQualityFrom(facts)
        cacheCollectionPlayableBadgeAliases(title, subtitle, browseId, result)
        bumpBadges()
        return cachedCollectionPlayableBadge(title, subtitle, browseId, songs)
    }

    /**
     * Collection quality is deliberately stricter than a track badge.
     *
     * One lucky Lossless/Hi-Q result must not label a whole album or playlist.
     * Orb exposes a collection badge only after at least three independently
     * resolved tracks confirm that tier or better. Hi-Res requires three
     * Hi-Res confirmations; mixed Hi-Res/Lossless falls back to ordinary
     * Lossless, and a mixed Lossless/Hi-Q sample falls back to Hi-Q.
     */
    private fun collectionQualityFrom(facts: List<PlayableBadgeQuality>): PlayableBadgeQuality {
        val confirmed = facts.filter { it.resolved }
        if (confirmed.size < COLLECTION_BADGE_MIN_CONFIRMED_TRACKS) {
            return PlayableBadgeQuality()
        }

        val hiResCount = confirmed.count { it.losslessTier.isHiRes }
        val losslessCount = confirmed.count { it.losslessTier.isLossless }
        // Lossless is also at least Hi-Q quality. If all four confirmed
        // tracks are Hi-Q-or-better but not all three are Lossless, the
        // collection safely falls back to the Hi-Q badge instead of showing
        // nothing for a mixed high-quality mastering.
        val hiQualityOrBetterCount = confirmed.count { it.losslessTier.isLossless || it.hiQuality }

        return when {
            hiResCount >= COLLECTION_BADGE_MIN_CONFIRMED_TRACKS -> PlayableBadgeQuality(
                resolved = true,
                losslessTier = LosslessTier.HI_RES_LOSSLESS,
            )
            losslessCount >= COLLECTION_BADGE_MIN_CONFIRMED_TRACKS -> PlayableBadgeQuality(
                resolved = true,
                losslessTier = LosslessTier.LOSSLESS,
            )
            hiQualityOrBetterCount >= COLLECTION_BADGE_MIN_CONFIRMED_TRACKS -> PlayableBadgeQuality(
                resolved = true,
                hiQuality = true,
            )
            // Four tracks were checked, but they did not confirm one common
            // quality tier. Keep the collection unbadged rather than guessing.
            else -> PlayableBadgeQuality(resolved = true)
        }
    }

    fun cachedPlaylistBadges(browseId: String): PlaylistBadgeAvailability? = playlistBadgeCache[browseId]

    suspend fun playlistBadges(
        browseId: String,
        explicitHint: Boolean = false,
        knownSongs: List<Song> = emptyList(),
        force: Boolean = false,
        priority: Boolean = false,
    ): PlaylistBadgeAvailability {
        if (!force) {
            playlistBadgeCache[browseId]?.let { cached ->
                // An early feed card may only know the playlist-level explicit
                // hint. Once its tracks arrive, refine that placeholder instead
                // of treating the empty prewarm result as final.
                if (knownSongs.isEmpty() || cached.losslessKnown) return cached
            }
        }
        val tiers = knownSongs.mapNotNull(::cachedTrackLosslessTier)
        val hiResCount = tiers.count { it.isHiRes }
        val losslessCount = tiers.count { it.isLossless }
        val best = when {
            hiResCount >= COLLECTION_BADGE_MIN_CONFIRMED_TRACKS -> LosslessTier.HI_RES_LOSSLESS
            losslessCount >= COLLECTION_BADGE_MIN_CONFIRMED_TRACKS -> LosslessTier.LOSSLESS
            else -> LosslessTier.NONE
        }
        val value = PlaylistBadgeAvailability(
            isExplicit = explicitHint || knownSongs.any { it.isExplicit },
            isLossless = best.isLossless,
            isHiResLossless = best.isHiRes,
            losslessKnown = tiers.size >= COLLECTION_BADGE_MIN_CONFIRMED_TRACKS,
        )
        playlistBadgeCache[browseId] = value
        bumpBadges()
        return value
    }

    fun invalidatePlaylistBadges(browseId: String) {
        playlistBadgeCache.remove(browseId)
        bumpBadges()
    }

    fun cachedReleaseLosslessTier(title: String, subtitle: String = "", browseId: String? = null): LosslessTier? =
        cachedCollectionPlayableBadge(title, subtitle, browseId).losslessTier.takeIf { it.isLossless }

    fun cachedReleaseLossless(title: String, subtitle: String = "", browseId: String? = null): Boolean? =
        cachedReleaseLosslessTier(title, subtitle, browseId)?.isLossless

    fun cachedReleaseExplicit(
        title: String,
        subtitle: String = "",
        browseId: String? = null,
    ): Boolean? {
        val exact = releaseExplicit[collectionKey(title, subtitle, browseId)]
        val byId = normalizedCollectionBrowseId(browseId)?.let { id ->
            releaseExplicit[collectionKey("", "", id)]
        }
        return when {
            exact == true || byId == true -> true
            else -> null
        }
    }

    /**
     * Explicit is a positive badge cache, not a clean-content cache. A feed row
     * that simply omits the Explicit badge has not proven the release clean and
     * must never suppress a later positive validation from detail/track data.
     */
    fun cacheReleaseExplicit(
        title: String,
        subtitle: String = "",
        browseId: String? = null,
        explicit: Boolean,
    ) {
        if (!explicit) return
        releaseExplicit[collectionKey(title, subtitle, browseId)] = true
        normalizedCollectionBrowseId(browseId)?.let { id ->
            releaseExplicit[collectionKey("", "", id)] = true
        }
        bumpBadges()
    }

    suspend fun prepareReleaseLossless(
        title: String,
        subtitle: String = "",
        songs: List<Song>,
        force: Boolean = false,
        browseId: String? = null,
    ): Boolean = prepareCollectionPlayableBadge(title, subtitle, browseId, songs, allowLosslessProbe = false)
        .losslessTier.isLossless

    suspend fun releaseHasLossless(
        title: String,
        subtitle: String = "",
        browseId: String? = null,
        force: Boolean = false,
    ): Boolean = cachedReleaseLossless(title, subtitle, browseId) == true

    /** Legacy Orb preflight API: deliberately does no source lookup. */
    suspend fun prepareLossless(song: Song, resolveStream: Boolean = false, force: Boolean = false): Boolean =
        cachedTrackLosslessTier(song)?.isLossless == true

    fun hasPreparedLossless(target: TrackMatcher.Target): Boolean = false
    fun hasPreparedLosslessStream(song: Song): Boolean = false
    fun preparedLosslessIsDash(song: Song): Boolean = false
    fun forgetPreparedLossless(target: TrackMatcher.Target) = Unit

    fun confirmPlaybackLossless(song: Song, tier: LosslessTier) {
        if (!tier.isLossless) return
        confirmedTiers[song.videoId] = tier
        bumpBadges()
    }

    fun invalidateTrackLossless(song: Song) {
        confirmedTiers.remove(song.videoId)
        bumpBadges()
    }

    private fun bumpBadges() {
        _badgeCacheRevision.value = _badgeCacheRevision.value + 1L
    }
}
