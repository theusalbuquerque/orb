package com.music.orb.data.innertube

import android.os.SystemClock
import com.music.orb.data.TrackLog
import com.music.orb.data.Http
import com.music.orb.data.NerdStats
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.AudioQuality
import com.music.orb.data.sources.SourceStream
import com.music.orb.data.sources.StreamFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Turns a videoId into a URL ExoPlayer can actually stream.
 *
 * Three things make or break this, and the order they are attempted in matters
 * as much as the mechanics of each:
 *
 *  1. **Which endpoint asks.** The `youtubei/v1/player` POST is one small JSON
 *     round trip. The watch page — what a full extractor scrape fetches — is
 *     several hundred kilobytes of HTML and is rate-shaped: under load Google
 *     answers its headers immediately and then feeds the body out over tens of
 *     seconds, or simply stops sending and never closes. That shaping is
 *     invisible as an error and reads to a listener as endless buffering, so
 *     the scrape is kept off the hot path entirely — see [newPipeUrl], the
 *     failsafe of last resort.
 *
 *  2. **Which client asks.** Google turns identities away without notice and
 *     without pattern: the client that works today answers `LOGIN_REQUIRED`
 *     next month. So [CLIENTS] is walked rather than trusted, the one that last
 *     worked is tried first, and one that is refused for a track is stood down
 *     for that track for a while.
 *
 *  3. **Whether the URL is real.** Every googlevideo URL carries an `n`
 *     parameter which, sent as-is, gets the response throttled to a crawl or
 *     refused with 403; it has to be transformed by running YouTube's own
 *     player JavaScript, which is what NewPipe's [YoutubeJavaScriptPlayerManager]
 *     does. That can fail quietly, and a URL can be dead on arrival for reasons
 *     no amount of care predicts — so nothing is handed to the player, or
 *     cached, until a single byte has been fetched from it. See [probe].
 */
object StreamResolver {

    private const val TAG = "BitChord"

    /** Past this, an extractor fetch is worth flagging rather than just noting. */
    private const val SLOW_FETCH_MS = 2000L

    /**
     * A first-note request is allowed to move on much sooner than a background
     * quality lookup. In the reference recordings, one 6-second client timeout
     * was directly visible as 7s of silence, and two sequential timeouts as
     * roughly 14.5s.
     */
    private const val FIRST_NOTE_PLAYER_REQUEST_MS = 1_000L
    private const val FIRST_NOTE_VISITOR_WARMUP_MS = 150L
    // Every unciphered identity is cheap enough to ask in the same first-note wave. The old
    // limit of three meant one bad trio could fall into the slow full resolver even while IOS or
    // another plain-URL client would have answered immediately. They race and are cancelled as
    // soon as one usable Opus/AAC carrier arrives, so this widens reliability without serial wait.
    private const val FIRST_NOTE_CLIENT_LIMIT = 6
    private const val FIRST_NOTE_AUTH_RESCUE_MS = 1_500L

    /** See [OkHttpDownloader.execute] — the one request the extractor is not allowed to make. */
    private const val NEXT_ENDPOINT = "/youtubei/v1/next"

    /** Well-formed, empty, and over the library's fifty-character floor. */
    private const val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""

    /**
     * Player clients in the order they are worth asking, cheapest and most
     * reliable first — an order taken from what the live endpoint actually
     * answers, not from what ought to work.
     *
     * The four at the top return plain `url` fields, so a stream is one POST
     * away with no player JavaScript involved at all. [PlayerClient.ANDROID]
     * below them hands back ciphered formats, costing a download of that
     * JavaScript and a signature to solve. See each entry in [PlayerClient].
     *
     * No web client appears here. `WEB_REMIX` was the tail of this list and
     * paid for itself in neither reliability nor speed — always ciphered,
     * usually refused, and reached only on tracks that were already failing,
     * where the one thing left worth spending is time. [newPipeUrl] is the
     * last resort instead.
     *
     * The gating that decides which of these answers is applied per network,
     * not globally — an identity refused on one connection is served on
     * another — which is the whole reason this is a list and why the order is
     * only a starting guess that [clientOrder] corrects from experience.
     *
     * TVHTML5 (Cobalt v7) is first because it works on flagged IPs without
     * PO Token — the most reliable client as of July 2026.
     */
    private val CLIENTS = listOf(
        PlayerClient.TVHTML5,
        PlayerClient.ANDROID_MUSIC,
        PlayerClient.ANDROID_VR,
        PlayerClient.IOS,
        PlayerClient.ANDROID_VR_LEGACY,
        PlayerClient.IOS_RECENT,
        PlayerClient.ANDROID,
    )

    /** NewPipe needs a Downloader; reuse the app's single OkHttp client. */
    private class OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            // The `next` endpoint answers "what plays after this" — related
            // videos and the autoplay queue. `fetchPage()` asks for it
            // unconditionally while building a StreamExtractor, and this app
            // never reads the answer: it is here for [audioStreams] and gets
            // its own up-next from [Innertube.next] on a different client.
            //
            // Declining it is worth a special case because of what it costs.
            // Measured across extractions, every other request in the chain
            // lands in 110-670ms, while this one takes seven seconds or simply
            // hangs — it was the single request behind
            // `extractor fetch FAILED after 12004ms`, and since one hung call
            // fails the whole attempt, an endpoint nothing here reads was
            // deciding whether a track played at all.
            //
            // Answered with an empty-but-well-formed envelope rather than an
            // error, so nothing downstream treats it as a failed fetch; NewPipe
            // finds no related items, which is exactly as many as are wanted.
            // It has to be this padded: the library rejects any JSON body under
            // fifty characters outright with "JSON response is too short", so a
            // bare `{}` fails the whole extraction rather than quietly
            // returning nothing.
            if (NEXT_ENDPOINT in request.url()) {
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, request.url())
            }
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                    hasUserAgent = true
                }
                when {
                    values.size > 1 -> {
                        builder.removeHeader(name)
                        values.forEach { builder.addHeader(name, it) }
                    }
                    values.size == 1 -> builder.header(name, values[0])
                }
            }
            if (!hasUserAgent) {
                // Chrome, not Firefox: NewPipe's own internal fetches — the
                // player JS included — go out under whatever this default is.
                // PixelMusic-ref's equivalent downloader defaults to this same
                // Chrome UA and does not hit the "Could not parse
                // deobfuscation function" failure this app was getting on the
                // identical video and NewPipeExtractor version; a Firefox UA
                // here is the one input that differed.
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                )
            }

            // Every byte NewPipe fetches passes through here, and until this
            // line none of it was visible: an extraction that took thirty
            // seconds reported thirty seconds, with nothing to say whether that
            // was one shaped watch page, a player POST, or the player
            // JavaScript. Naming the request and its size is what makes the
            // difference between measuring the step and guessing at it.
            val requestStart = SystemClock.elapsedRealtime()
            val response = try {
                extractorClient.newCall(builder.build()).execute()
            } catch (e: Exception) {
                // The one that matters most, and the one a log written only on
                // the way out never sees: a request that times out or is torn
                // down produces no line at all, so an extraction killed by a
                // single hung call looks like an extraction that was slow for
                // no reason. Named here, then rethrown unchanged.
                TrackLog.w(
                    TAG,
                    "extractor fetch FAILED after ${SystemClock.elapsedRealtime() - requestStart}ms " +
                        "${request.httpMethod()} ${request.url()}: ${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
            val took = SystemClock.elapsedRealtime() - requestStart
            if (took > SLOW_FETCH_MS) {
                TrackLog.w(TAG, "extractor fetch ${took}ms ${request.httpMethod()} ${request.url()}")
            } else {
                TrackLog.d(TAG, "extractor fetch ${took}ms ${request.httpMethod()} ${request.url()}")
            }
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    private val init by lazy { NewPipe.init(OkHttpDownloader()) }

    /**
     * The extractor's own leash on [Http.client].
     *
     * Everything NewPipe fetches — the watch page above all — goes out through
     * here, and [Http.client] sets no `callTimeout` at all. Its 30-second read
     * timeout does not stand in for one: a read timeout is per read, so a
     * response that yields a few bytes at a time resets it forever and the call
     * never ends. That is not a hypothetical failure mode but the exact shaping
     * this file's header describes Google applying to the watch page, and it
     * was observed doing it — an extraction that simply never returned, twice
     * in a row, leaving a track buffering until ExoPlayer gave up and retried
     * into the same wall. Unbounded is the one thing this call must not be,
     * because it is the failsafe: nothing runs after it.
     *
     * It gets its **own connection pool**, and that is the point of it rather
     * than a detail. Sharing [Http.client]'s pool means the watch-page GET can
     * be handed a connection to `www.youtube.com` left over from an Innertube
     * `player` POST, and a pooled connection that the far end has quietly
     * stopped answering does not fail — it hangs, silently, until something
     * times it out. `retryOnConnectionFailure` cannot save it, because nothing
     * is failing. That is exactly the shape the measurements have: a first
     * attempt that burns the whole ceiling and a second, on a fresh connection,
     * that succeeds in 2.2 seconds. Its own pool means extraction never
     * inherits a socket some other part of the app finished with.
     *
     * Sharing the pool was never required, either. The address-family argument
     * in [Http] is about a googlevideo media fetch matching the `player`
     * request that minted its URL; this fetches HTML from `www.youtube.com`,
     * and the googlevideo URL it comes back with is fetched later through
     * [Http.client] regardless.
     *
     * The ceiling is sized against a healthy extraction — about two seconds —
     * rather than against patience. Twelve is generous enough that a merely
     * slow page still completes, and short enough that a hung one costs a few
     * seconds before [EXTRACTION_ATTEMPTS] tries again on a new connection,
     * instead of half a minute of silence.
     */
    private val extractorClient by lazy {
        Http.client.newBuilder()
            // Short, because a connection this app is *not* using is a
            // connection going stale. The observed failure is a request that
            // gets no response at all and dies on the ceiling exactly — twelve
            // thousand and one milliseconds, over and over, on three different
            // endpoints. That is not a slow server, it is a socket the far side
            // (or a NAT on the way) has silently dropped while it sat idle
            // between tracks, being handed to the next request as though it
            // were good. Half a minute of keepalive is short enough that most
            // are re-established rather than resurrected.
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            // And for the ones that go stale while held: HTTP/2 pings make the
            // client notice a dead peer itself, in seconds, instead of waiting
            // out a response that is never coming. These endpoints are all
            // HTTP/2, so this is the mechanism actually available for detecting
            // it rather than merely giving up on it.
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .callTimeout(EXTRACTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private const val PING_INTERVAL_SECONDS = 5L

    /**
     * Sized so that giving up and trying again is cheaper than waiting.
     *
     * A healthy extractor request lands in 100-700ms and the retry that follows
     * a hung one has, every time it has been watched, succeeded immediately —
     * so the ceiling is not protecting a slow-but-viable request, it is deciding
     * how long a dead connection costs. At twelve seconds one stall turned a
     * four-second start into twenty-six; at five, the same stall costs about
     * six seconds all in.
     */
    private const val EXTRACTOR_TIMEOUT_SECONDS = 5L

    /**
     * @return a directly streamable URL that has been proven to serve bytes,
     *   or throws with a reason worth showing.
     *
     * Results are held briefly — see [recent]. Resolving is the slow part of
     * starting a track, and ExoPlayer asks again for every re-open: each seek
     * outside the buffer, and each range the cache fills in.
     */
    suspend fun resolve(videoId: String): String {
        init

        val quality = AppSettings.effectiveAudioQuality
        recent[videoId]
            ?.takeIf { cached ->
                SystemClock.elapsedRealtime() - cached.at < URL_TTL_MS &&
                    validHttpStreamUrl(videoId, cached.url, "playback cache") != null &&
                    cached.matches(quality)
            }
            ?.let { cached ->
                // The resolver cache can outlive PlaybackService. Re-publish the
                // exact bitrate when a fresh cached rendition is reused so UI
                // quality proof never depends on stale session telemetry.
                NerdStats.onStreamPicked(videoId, cached.kbps)
                return cached.url
            }

        val stream = coalescedResolve(videoId)

        // The container carries no bitrate field, so this is the only place the
        // real figure is ever known.
        NerdStats.onStreamPicked(videoId, stream.kbps)
        remember(videoId, stream)
        return stream.url
    }


    /**
     * Fast first-note resolver for an unprepared/manual play.
     *
     * This asks for one ordinary playable carrier and returns as soon as that
     * carrier is proven. It does not walk the AAC 320/250 hierarchy and does
     * not invoke NewPipe on the normal first-note path. Higher quality is a
     * separate post-start job.
     */
    suspend fun resolveImmediatePlayback(videoId: String): SourceStream =
        withContext(Dispatchers.IO + TrackLog.about(videoId)) {
            // The normal first-note path does not use NewPipe. Initialising the
            // extractor here made the first manual play pay setup work for a
            // fallback that usually is never needed.
            val now = SystemClock.elapsedRealtime()
            val ceiling = AppSettings.effectiveAudioQuality.maxKbps

            recent[videoId]
                ?.takeIf { cached ->
                    now - cached.at < URL_TTL_MS &&
                        validHttpStreamUrl(videoId, cached.url, "recent cache") != null &&
                        (
                            AppSettings.effectiveAudioQuality == AudioQuality.AAC ||
                                AppSettings.effectiveAudioQuality == AudioQuality.HIGH ||
                                cached.kbps <= ceiling
                        )
                }
                ?.let { cached ->
                    NerdStats.onStreamPicked(videoId, cached.kbps)
                    return@withContext SourceStream(
                        url = cached.url,
                        format = StreamFormat(
                            codec = codecNameForMime(cached.mimeType),
                            kbps = cached.kbps,
                        ),
                        headers = PlayerClient.forStreamUrl(cached.url).mediaHeaders(),
                    )
                }

            fun pickImmediateCarrier(response: JsonObject): Audio? =
                when (AppSettings.effectiveAudioQuality) {
                    AudioQuality.AAC, AudioQuality.HIGH ->
                        pickOpus128Tier(response)
                            ?: pickAac128Tier(response)
                            ?: pickForQualityResponse(response)
                    AudioQuality.MEDIUM, AudioQuality.LOW ->
                        pickForQualityResponse(response)
                }

            val stream = timed("$videoId immediate carrier") {
                firstNoteParallelStream(
                    videoId = videoId,
                    select = ::pickImmediateCarrier,
                )
            } ?: withTimeoutOrNull(FIRST_NOTE_AUTH_RESCUE_MS) {
                // One bounded signed-in browser attempt before the expensive compatibility
                // ladder. This is especially useful on networks where anonymous device clients
                // are being bot-checked but the user's real Music session is still accepted.
                authenticatedWebRemixStream(videoId, ::pickImmediateCarrier)
            } ?: run {
                // Emergency only. The normal path above is deliberately broad enough that this
                // should be rare; keep the full resolver as the final reliability net rather than
                // turning a temporary YouTube refusal into a hard playback error.
                TrackLog.w(TAG, "all fast first-note carriers missed for $videoId; using full resolver as rescue")
                val url = resolve(videoId)
                val mime = resolvedMimeType(videoId).orEmpty()
                val kbps = resolvedBitrateKbps(videoId)
                return@withContext SourceStream(
                    url = url,
                    format = StreamFormat(codec = codecNameForMime(mime), kbps = kbps),
                    headers = PlayerClient.forStreamUrl(url).mediaHeaders(),
                )
            }

            NerdStats.onStreamPicked(videoId, stream.kbps)
            remember(videoId, stream)
            SourceStream(
                url = stream.url,
                format = StreamFormat(
                    codec = codecNameForMime(stream.mimeType),
                    kbps = stream.kbps,
                ),
                headers = PlayerClient.forStreamUrl(stream.url).mediaHeaders(),
            )
        }

    private fun codecNameForMime(mimeType: String): String? {
        val value = mimeType.lowercase()
        return when {
            "opus" in value || "webm" in value -> "opus"
            "mp4" in value || "m4a" in value || "mp4a" in value -> "aac"
            else -> null
        }
    }


    private suspend fun coalescedResolve(videoId: String): Stream {
        // computeIfAbsent, not getOrPut: getOrPut's get-then-put isn't atomic
        // on a ConcurrentHashMap, and two racing callers each starting their
        // own async before either one's put() lands is the exact race this
        // exists to close.
        val deferred = inFlight.computeIfAbsent(videoId) {
            resolverScope.async(TrackLog.about(videoId)) { resolveUncached(videoId) }
        }
        return try {
            deferred.await()
        } finally {
            inFlight.remove(videoId, deferred)
        }
    }

    private val inFlight = ConcurrentHashMap<String, Deferred<Stream>>()

    private val resolverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun resolveUncached(videoId: String): Stream {
        val resolveStart = SystemClock.elapsedRealtime()
        val stream = try {
            if (AppSettings.effectiveAudioQuality == AudioQuality.AAC) {
                resolvePreferredLossy(videoId, purpose = "playback")
            } else {
                timed("$videoId playerStream") { playerStream(videoId, ::pickForPlayback) }
                    ?: timed("$videoId authenticatedWebRemixStream") {
                        authenticatedWebRemixStream(videoId, ::pickForPlayback)
                    }
                    ?: run {
                        TrackLog.w(TAG, "every player client failed for $videoId; falling back to extraction")
                        timed("$videoId newPipeStream") { newPipeStream(videoId, ::pickForQuality) }
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            // Every strategy above either runs third-party extraction code or
            // drives YouTube's player JavaScript, so any of them can turn out to
            // have been compiled against an API this OS version does not carry.
            // That arrives as an Error, which the clause below does not catch and
            // no caller of this function catches either — ExoPlayer's loader
            // thread least of all, which is where it surfaced as a process kill
            // rather than a failed track. Converted here, at the one point every
            // strategy passes through, so the answer is the same wherever the
            // linkage failure came from.
            TrackLog.w(
                TAG,
                "resolve hit a linkage failure for $videoId after " +
                    "${SystemClock.elapsedRealtime() - resolveStart}ms: ${e.javaClass.name}: ${e.message}",
                e,
            )
            throw IOException("Stream resolution cannot run on this device: $e", e)
        } catch (e: Exception) {
            // The one path out of here that said nothing at all. A resolve that
            // throws is handed to ExoPlayer as a load error, which retries it on
            // a backoff of its own — so the symptom is a track that sits in
            // BUFFERING and walks the clients again every thirty seconds, with
            // no line anywhere naming what actually went wrong. The stack trace
            // is the point: the failure is usually several frames inside
            // NewPipe, where the message alone ("null", commonly) identifies
            // nothing.
            TrackLog.w(
                TAG,
                "resolve failed for $videoId after ${SystemClock.elapsedRealtime() - resolveStart}ms: " +
                    "${e.javaClass.name}: ${e.message}",
                e,
            )
            throw e
        }
        TrackLog.d(TAG, "TIMING $videoId total resolve: ${SystemClock.elapsedRealtime() - resolveStart}ms")
        return stream
    }

    /**
     * Resolve the listener-facing lossy ladder in a strict order:
     *
     *  1. AAC ~320 kbps
     *  2. AAC 250/256 kbps
     *  3. Opus ~128 kbps (or the best Opus offered)
     *  4. AAC ~128 kbps / remaining sub-250 AAC
     *
     * The crucial difference from the old AAC path is that a 128 kbps M4A from
     * the first client no longer wins merely because it arrived first. Player
     * responses are retained between tier passes, so after the first walk the
     * lower tiers are selected locally from the same responses rather than
     * repeating the network request. This hierarchy is intentionally playback-
     * only: audio analysis uses the first usable AAC carrier so BPM/beat-grid
     * work never waits for a 320/250 kbps hunt.
     */
    private suspend fun resolvePreferredLossy(videoId: String, purpose: String): Stream {
        val responses = mutableMapOf<PlayerClient, JsonObject>()
        val authenticatedResponses = mutableMapOf<String, JsonObject>()

        suspend fun tryTier(label: String, selector: (JsonObject) -> Audio?): Stream? {
            return timed("$videoId $purpose player $label") {
                playerStream(
                    videoId = videoId,
                    select = selector,
                    responses = responses,
                    missingFormatIsRefusal = false,
                    formatFailureIsRefusal = false,
                )
            } ?: timed("$videoId $purpose authenticated $label") {
                authenticatedWebRemixStream(
                    videoId = videoId,
                    select = selector,
                    responseCache = authenticatedResponses,
                )
            }
        }

        tryTier("AAC-320", ::pickAac320Tier)?.let { return it }
        tryTier("AAC-250", ::pickAac250Tier)?.let { return it }

        // Do not put NewPipe on the first-note path while a normal player
        // response already has a perfectly usable carrier. NewPipe is the
        // expensive escape hatch (watch-page scrape + JS work) and can take
        // tens of seconds under bot checks. Prefer the requested hierarchy from
        // the responses we already paid to fetch: AAC 320 -> AAC 250 -> Opus
        // 128 -> AAC 128. Only if all four tiers are unavailable do we invoke
        // extraction.
        tryTier("Opus-128", ::pickOpus128Tier)?.let { return it }
        tryTier("AAC-128", ::pickAac128Tier)?.let { return it }

        val extracted = timed("$videoId $purpose NewPipe hierarchy") {
            newPipePreferredLossyStream(videoId)
        }
        if (extracted != null) return extracted

        // Unknown/legacy formats are an emergency compatibility path only. They
        // are deliberately below the four requested tiers and never qualify for
        // the Hi-Quality badge.
        TrackLog.w(TAG, "$purpose quality ladder exhausted for $videoId; using emergency lossy fallback")
        playerStream(
            videoId = videoId,
            select = ::pickForQualityResponse,
            responses = responses,
            missingFormatIsRefusal = false,
        )?.let { return it }
        authenticatedWebRemixStream(
            videoId = videoId,
            select = ::pickForQualityResponse,
            responseCache = authenticatedResponses,
        )?.let { return it }
        return newPipeStream(videoId, ::pickForQuality)
    }

    private enum class LossyTier(val rank: Int) {
        EMERGENCY(0),
        AAC_128(1),
        OPUS_128(2),
        AAC_250(3),
        AAC_320(4),
    }

    private fun streamLossyTier(stream: Stream): LossyTier {
        val mime = stream.mimeType.lowercase()
        val isAac = "mp4" in mime || "m4a" in mime || "mp4a" in mime
        val isOpus = "opus" in mime || "webm" in mime
        return when {
            isAac && stream.kbps >= AAC_320_FLOOR_KBPS -> LossyTier.AAC_320
            isAac && stream.kbps >= AAC_250_FLOOR_KBPS -> LossyTier.AAC_250
            isOpus && stream.kbps >= OPUS_128_FLOOR_KBPS -> LossyTier.OPUS_128
            isAac && stream.kbps >= AAC_128_FLOOR_KBPS -> LossyTier.AAC_128
            else -> LossyTier.EMERGENCY
        }
    }

    /** Logs how long [block] took, whatever it returns — a timing probe, not a control flow change. */
    private suspend inline fun <T> timed(label: String, block: suspend () -> T): T {
        val start = SystemClock.elapsedRealtime()
        return block().also { TrackLog.d(TAG, "TIMING $label: ${SystemClock.elapsedRealtime() - start}ms") }
    }

    /**
     * Tried after [playerStream] and before extraction, and only when there is
     * a session to send: [PlayerClient.WEB_REMIX] carrying the signed-in
     * listener's own cookie.
     *
     * The anonymous walk in [playerStream] is refused on sight far more often
     * than not right now — every device client answering "sign in to confirm
     * you're not a bot" to a request that, honestly, isn't signed in. A real
     * session cookie on a browser-shaped client is the one case that isn't an
     * anonymous device pretending otherwise, which is why it is asked at all,
     * rather than left at the reputation an earlier cookie-less attempt earned
     * it — the one that got it dropped from [CLIENTS] entirely.
     *
     * It is asked *after* that walk rather than ahead of it because of what it
     * costs when it doesn't work. WEB_REMIX is a web client, so its formats
     * come back ciphered without exception, so this is the one path that has to
     * solve a signature on every single track — and a signature that cannot be
     * solved is not a cheap no. Ahead of the walk that made every track pay for
     * the most expensive failure available before anything cheaper was tried;
     * behind it, the clients that answer in one round trip get their say first
     * and this is reached only on tracks that were already failing. See
     * [jsPlayerMutex] for what the expensive failure actually was.
     *
     * Anything short of a working URL — no cookie, a refusal, a format that
     * won't unlock, a probe that fails — falls through to null rather than
     * throwing, so a bad guess here never costs more than the one round trip.
     */
    private suspend fun authenticatedWebRemixStream(
        videoId: String,
        select: (JsonObject) -> Audio?,
        responseCache: MutableMap<String, JsonObject>? = null,
    ): Stream? {
        if (!Innertube.hasAccountSession) return null
        return try {
            val cacheKey = "WEB_REMIX_AUTH"
            val response = responseCache?.get(cacheKey) ?: run {
                timed("$videoId WEB_REMIX ensureVisitorData") { Innertube.ensureVisitorData() }
                val timestamp = timed("$videoId WEB_REMIX getSignatureTimestamp") {
                    jsPlayerManager { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }
                }
                timed("$videoId WEB_REMIX player()") {
                    Innertube.player(videoId, PlayerClient.WEB_REMIX, timestamp, authenticated = true)
                }.also { responseCache?.put(cacheKey, it) }
            }
            val format = select(response) ?: return null
            val url = timed("$videoId WEB_REMIX streamUrl") {
                streamUrl(videoId, format)
                    ?.let { patchClientVersion(it, PlayerClient.WEB_REMIX.clientVersion) }
                    ?.let { validHttpStreamUrl(videoId, it, "WEB_REMIX URL") }
            } ?: return null
            if (timed("$videoId WEB_REMIX probe") { probe(url) } != Probe.OK) return null
            TrackLog.d(TAG, "resolved $videoId via authenticated WEB_REMIX @ ${format.kbps}kbps")
            Stream(url, format.kbps, format.mimeType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.d(TAG, "authenticated WEB_REMIX failed for $videoId: ${e.message}")
            null
        }
    }

    /**
     * A resolved stream: the URL, and what the format behind it turned out to
     * be. Playback only ever needs the URL; a download needs the rest of it to
     * name the file and declare its type.
     */
    class Stream(val url: String, val kbps: Int, val mimeType: String) {

        /**
         * The container these bytes are actually in, which is not always what
         * names them.
         *
         * Nothing here transcodes or remuxes — what googlevideo sends is what
         * lands on disk — so the extension has to describe the bytes rather
         * than the codec inside them. YouTube's Opus is Opus-in-WebM, and the
         * two sources disagree about how to say so: the player endpoint calls
         * it `audio/webm; codecs="opus"` and NewPipe calls it `audio/opus`.
         * Taking the latter at face value would write a WebM file named
         * `.opus`, and an `.opus` file is expected to be Ogg — which is how a
         * perfectly good download ends up refusing to open in half the players
         * on the device.
         *
         * Downloads no longer reach the WebM branch — [resolveForDownload]
         * takes MP4 or nothing — but playback still hands Opus around, and a
         * file an older build already wrote is still a `.webm` this app has to
         * be able to describe.
         */
        val downloadExtension: String
            get() = when {
                "mp4" in mimeType || "m4a" in mimeType -> "m4a"
                else -> "webm"
            }

        /** What the media store should be told this file is. */
        val downloadMimeType: String
            get() = if (downloadExtension == "m4a") "audio/mp4" else "audio/webm"
    }

    /**
     * A fresh Hi-Q-only second look for a track that is already audible.
     *
     * This deliberately bypasses [recent]: that cache may contain the Opus/AAC-128
     * fallback that won first note, and reusing it would make a background quality
     * upgrade on mobile data a no-op until the URL TTL expired. Only AAC/MP4 at
     * 256 kbps or better is accepted, so this can never turn the mobile path into
     * Lossless/Hi-Res Lossless.
     */
    suspend fun resolveHiQualityUpgrade(videoId: String): SourceStream? =
        withContext(Dispatchers.IO + TrackLog.about(videoId)) {
            init
            val responses = mutableMapOf<PlayerClient, JsonObject>()
            val authenticatedResponses = mutableMapOf<String, JsonObject>()

            fun pickHiQuality(response: JsonObject): Audio? =
                audioFormats(response)
                    .filter { it.isAac && it.kbps >= HI_QUALITY_UPGRADE_MIN_KBPS }
                    .maxByOrNull { it.kbps }

            suspend fun tryHiQuality(): Stream? =
                timed("$videoId live-upgrade Hi-Q") {
                    playerStream(
                        videoId = videoId,
                        select = { response -> pickHiQuality(response) },
                        responses = responses,
                        missingFormatIsRefusal = false,
                        formatFailureIsRefusal = false,
                    )
                } ?: timed("$videoId live-upgrade authenticated Hi-Q") {
                    authenticatedWebRemixStream(
                        videoId = videoId,
                        select = { response -> pickHiQuality(response) },
                        responseCache = authenticatedResponses,
                    )
                }

            val stream = tryHiQuality()
                ?: runCatching {
                    extractStream(videoId) { candidates ->
                        candidates
                            .filter { (kbps, audio) -> audio.isM4a && kbps >= HI_QUALITY_UPGRADE_MIN_KBPS }
                            .maxByOrNull { it.first }
                            ?.second
                    }
                }.onFailure { error ->
                    TrackLog.d(TAG, "Hi-Q second look missed for $videoId: ${error.message}")
                }.getOrNull()
                ?: return@withContext null

            if (stream.kbps < HI_QUALITY_UPGRADE_MIN_KBPS) return@withContext null
            val mime = stream.mimeType.lowercase()
            if ("mp4" !in mime && "m4a" !in mime && "mp4a" !in mime) return@withContext null

            TrackLog.d(TAG, "Hi-Q second look found $videoId @ ${stream.kbps}kbps")
            SourceStream(
                url = stream.url,
                format = StreamFormat(codec = "aac", kbps = stream.kbps),
                headers = PlayerClient.forStreamUrl(stream.url).mediaHeaders(),
            )
        }

    /**
     * As [resolve], but for a file being kept rather than a stream being heard:
     * the best AAC on offer, whatever the quality ceiling says.
     *
     * The format is not a preference here, it is the only option. Every
     * adaptive audio format YouTube offers is either AAC in MP4 or Opus (or
     * Vorbis) in WebM, and Android's media store will not mint a row in the
     * audio collection for `audio/webm` — measured on-device as
     * `IllegalArgumentException: Unsupported MIME type audio/webm` out of
     * `ContentResolver.insert`, thrown before a single byte had been fetched.
     * So every download this app offered failed, and Opus being the better
     * codec of the two never got to matter.
     *
     * Which is why there is no "best available" fallback below any more. A
     * walk that ends by taking whatever the last client offered ends by
     * taking WebM, and a WebM the store refuses is not a worse download, it
     * is no download — so running out of AAC is a failure with a sentence
     * attached rather than something to work around. It is not a common one:
     * the AAC ladder is on essentially every track, rather more reliably than
     * Opus was.
     *
     * MP4 is still demanded across *every* client before any of them is
     * allowed to give up, because a per-client walk cannot tell a client that
     * has no MP4 from a client that has been refused the track. Player
     * responses are shared between the passes, so the second costs the probes
     * again but not the round trips.
     *
     * Nothing here touches [recent]. That cache exists to keep ExoPlayer's
     * re-opens off the network, and its entries are picked under the quality
     * ceiling — seeding it with an unbudgeted URL would quietly hand a capped
     * connection the stream it was capped to avoid, and reading from it would
     * hand a download whatever bitrate playback happened to settle for.
     *
     * The whole thing is attempted twice, for the case where a client is turned
     * away with "Sign in to confirm you're not a bot": [playerStream] mints a
     * fresh visitor id and retries that one client, but `mintedFreshVisitor` is
     * scoped to a single walk, so a bot check late in the list burns the retry
     * and the new id benefits only the *next* resolve. The second walk is what
     * turns that into one download that works. It is worth knowing what it
     * cannot do: a client whose URL failed to probe was stood down by that
     * failure and is skipped on the way round again, so the second attempt is
     * the same walk minus its refusals, not a clean one. Bot checks it can fix;
     * refusals it cannot.
     *
     * Which is why extraction sits behind both. When every client is refusing
     * — the observed state, with the VR clients bot-checked and iOS minting
     * URLs that 403 — [resolve] still gets audio, because it falls through to
     * NewPipe and re-derives the URL itself. A download reaching the same wall
     * has to do the same thing or it fails while the track it is refusing to
     * save is audibly playing.
     */
    suspend fun resolveForDownload(videoId: String, targetKbps: Int? = null): Stream {
        val stream = downloadStream(videoId, targetKbps)
        // Belt and braces on the one invariant the media store enforces for us,
        // and enforces badly: everything in [downloadStream] selects for MP4,
        // and this is where a format that somehow slipped through says so in a
        // sentence rather than three frames away as an insert failure.
        check(stream.downloadExtension == "m4a") { "Can't save ${stream.mimeType} — try again" }
        return stream
    }

    private suspend fun downloadStream(
        videoId: String,
        targetKbps: Int? = null,
    ): Stream = withContext(TrackLog.about(videoId)) {
        init

        // Whether any client offered AAC at all, as distinct from whether one
        // could be turned into a working URL. Those are different failures and
        // only one of them is worth telling someone to try again about: a track
        // no client has an MP4 for will not have one in five minutes either,
        // while an MP4 that won't probe is a bad afternoon on Google's side.
        var offered = false

        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(DOWNLOAD_RETRY_MS)
            // Fresh each time. Responses are only cached once a client has
            // answered, and re-deriving a URL from a cached response produces
            // the same URL that just failed to probe — so carrying the map
            // across attempts would make every attempt after the first a
            // no-op.
            val responses = mutableMapOf<PlayerClient, JsonObject>()
            playerStream(
                videoId,
                { response -> pickAacForDownload(response, targetKbps)?.also { offered = true } },
                responses,
            )?.let { return@withContext it }
        }

        // Not "try again later" — every client being refused at once is a state
        // that lasts hours, and it is precisely the state [resolve] extracts its
        // way out of. Still asking for MP4, because this is still a download:
        // the failsafe is a different route to the bytes, not a licence to
        // fetch a container that cannot then be saved.
        TrackLog.w(TAG, "no client minted a usable MP4 URL for $videoId; extracting")
        runCatching {
            newPipeStream(videoId) { candidates ->
                val m4a = candidates.filter { it.second.isM4a }
                val selected = if (targetKbps == null) {
                    m4a.maxByOrNull { it.first }
                } else {
                    m4a.minWithOrNull(
                        compareBy<Pair<Int, AudioStream>> { abs(it.first - targetKbps) }
                            .thenByDescending { it.first },
                    )
                }
                selected?.second?.also { offered = true }
            }
        }.onSuccess { return@withContext it }
            .onFailure { TrackLog.w(TAG, "extraction found no MP4 for $videoId: ${it.message}") }

        if (offered) error("Couldn't reach a downloadable copy just now — try again")
        error("No downloadable audio for this track")
    }

    /**
     * Walks [CLIENTS] until one produces a URL that actually serves audio.
     *
     * Every step is allowed to fail without taking the attempt with it: a
     * client can be refused the track, hand back formats none of which [select]
     * accepts or none of which can be unciphered, or mint a URL that turns out
     * to be dead. Only running out of clients is a failure.
     *
     * [responses] memoises the player response per client for the caller that
     * walks twice — see [resolveForDownload]. A client that is asked again
     * inside one walk is a bug, not a cost, so the default is a fresh map.
     *
     * @return the validated stream, or null to fall through to [newPipeStream].
     */
    /**
     * First-note-only resolver that races a very small set of unciphered clients.
     *
     * The old hot path walked clients one at a time. Three 1.8 s timeouts were
     * enough to turn one tap into 5–6 seconds of silence before ExoPlayer even
     * received a URL. The first three identities in [CLIENTS] are intentionally
     * cheap, plain-URL clients, so asking them concurrently gives the same
     * reliability envelope while the latency is bounded by the slowest request
     * in one wave instead of the sum of all of them.
     *
     * No stream probe is performed here: ExoPlayer is the probe. Any client that
     * fails is recorded/stood down exactly as it is in [playerStream].
     */
    private suspend fun firstNoteParallelStream(
        videoId: String,
        select: (JsonObject) -> Audio?,
    ): Stream? {
        withTimeoutOrNull(FIRST_NOTE_VISITOR_WARMUP_MS) {
            timed("$videoId ensureVisitorData(first-note-parallel)") { Innertube.ensureVisitorData() }
        }

        val clients = clientOrder()
            .asSequence()
            .filterNot { isStoodDown(videoId, it) }
            // Keep JavaScript/signature work out of the parallel hot path.
            .filterNot { it.needsSignatureTimestamp }
            .take(FIRST_NOTE_CLIENT_LIMIT)
            .toList()
        if (clients.isEmpty()) return null

        return supervisorScope {
            val results = Channel<Pair<PlayerClient, Stream?>>(capacity = clients.size)
            val jobs = clients.map { client ->
                launch(Dispatchers.IO + TrackLog.about(videoId)) {
                    val stream = try {
                        val started = SystemClock.elapsedRealtime()
                        val response = Innertube.player(
                            videoId = videoId,
                            client = client,
                            signatureTimestamp = null,
                            requestTimeoutMs = FIRST_NOTE_PLAYER_REQUEST_MS,
                        )
                        val format = select(response)
                        if (format == null) {
                            TrackLog.d(TAG, "${client.clientName} offered no immediate format for $videoId")
                            null
                        } else {
                            val url = streamUrl(videoId, format)
                                ?.let { patchClientVersion(it, client.clientVersion) }
                                ?.let { validHttpStreamUrl(videoId, it, "${client.clientName} immediate URL") }
                            if (url == null) {
                                TrackLog.d(TAG, "${client.clientName} could not unlock immediate URL for $videoId")
                                null
                            } else {
                                val took = SystemClock.elapsedRealtime() - started
                                TrackLog.d(
                                    TAG,
                                    "FIRST_NOTE winner-candidate ${client.clientName} ${took}ms " +
                                        "@ ${format.kbps}kbps",
                                    about = videoId,
                                )
                                Stream(url, format.kbps, format.mimeType)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is Innertube.UnplayableException) {
                            if (e.looksLikeBotCheck) standDownEverywhere(client) else refused(videoId, client)
                        }
                        TrackLog.d(TAG, "FIRST_NOTE ${client.clientName} missed $videoId: ${e.message}", about = videoId)
                        null
                    }
                    results.send(client to stream)
                }
            }

            var winner: Stream? = null
            repeat(clients.size) {
                val (client, result) = results.receive()
                if (result != null && winner == null) {
                    winner = result
                    served(client)
                    preferred = client
                    TrackLog.d(TAG, "FIRST_NOTE selected ${client.clientName} for $videoId", about = videoId)
                    jobs.forEach { if (it.isActive) it.cancel() }
                    return@supervisorScope result
                }
            }
            winner
        }
    }

    private suspend fun playerStream(
        videoId: String,
        select: (JsonObject) -> Audio?,
        responses: MutableMap<PlayerClient, JsonObject> = mutableMapOf(),
        missingFormatIsRefusal: Boolean = true,
        formatFailureIsRefusal: Boolean = true,
        probeStream: Boolean = true,
        playerRequestTimeoutMs: Long? = null,
        visitorWarmupTimeoutMs: Long? = null,
        maxClients: Int? = null,
        refreshVisitorOnBotCheck: Boolean = true,
    ): Stream? {
        // A cold visitor-data request can itself block the hot path. Background
        // lookups keep the full wait; first-note playback gets only a small
        // warm-up budget and then proceeds with whatever session state exists.
        if (visitorWarmupTimeoutMs != null) {
            withTimeoutOrNull(visitorWarmupTimeoutMs) {
                timed("$videoId ensureVisitorData(first-note)") { Innertube.ensureVisitorData() }
            }
        } else {
            timed("$videoId ensureVisitorData") { Innertube.ensureVisitorData() }
        }

        var timestamp: Int? = null
        var mintedFreshVisitor = false
        val orderedClients = clientOrder().let { order ->
            maxClients?.let(order::take) ?: order
        }

        for (client in orderedClients) {
            if (isStoodDown(videoId, client)) continue
            val clientStart = SystemClock.elapsedRealtime()
            try {
                // Only fetched once, and only if a client that needs it is
                // reached — it costs a download of YouTube's player JavaScript.
                if (client.needsSignatureTimestamp && timestamp == null) {
                    timestamp = timed("$videoId getSignatureTimestamp") {
                        runCatching { jsPlayerManager { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) } }
                            .onFailure { TrackLog.w(TAG, "no signature timestamp: ${it.message}") }
                            .getOrNull()
                    } ?: continue
                }

                suspend fun playerRequest(): JsonObject =
                    if (playerRequestTimeoutMs != null) {
                        Innertube.player(
                            videoId = videoId,
                            client = client,
                            signatureTimestamp = timestamp,
                            requestTimeoutMs = playerRequestTimeoutMs,
                        )
                    } else {
                        Innertube.player(videoId, client, timestamp)
                    }

                val response = responses[client] ?: try {
                    timed("$videoId ${client.clientName} player()") { playerRequest() }
                } catch (e: Innertube.UnplayableException) {
                    // A visitor id can be burned while the session around it is
                    // fine, and the only symptom is being called a bot. Worth
                    // one fresh id and one more try, once per resolve.
                    if (!e.looksLikeBotCheck || mintedFreshVisitor || !refreshVisitorOnBotCheck) throw e
                    mintedFreshVisitor = true
                    TrackLog.d(TAG, "bot check from ${client.clientName}; minting a fresh visitor id")
                    timed("$videoId ensureVisitorData(refresh)") { Innertube.ensureVisitorData(refresh = true) }
                    timed("$videoId ${client.clientName} player() retry") { playerRequest() }
                }
                responses[client] = response

                // Answered, but with nothing this app can use. Logged because
                // the two ways that happens are worth telling apart and the
                // timings alone cannot: a client that offers no acceptable
                // format never reaches [streamUrl], so both cases look
                // identical from outside — a `player()` line and then silence.
                val format = select(response)
                if (format == null) {
                    TrackLog.d(TAG, "${client.clientName} offered no usable format for $videoId")
                    // An AAC-only pass is asking a narrower question than normal
                    // playback. "No M4A here" is not a client refusal and must
                    // not poison the same response before the Opus fallback pass.
                    if (missingFormatIsRefusal) refused(videoId, client)
                    continue
                }
                val url = timed("$videoId ${client.clientName} streamUrl") {
                    streamUrl(videoId, format)
                        ?.let { patchClientVersion(it, client.clientVersion) }
                        ?.let { validHttpStreamUrl(videoId, it, "${client.clientName} playback URL") }
                }
                if (url == null) {
                    TrackLog.d(
                        TAG,
                        "${client.clientName} offered ${format.mimeType} for $videoId but its URL could not be unlocked",
                    )
                    if (formatFailureIsRefusal) refused(videoId, client)
                    continue
                }

                if (!probeStream) {
                    // ExoPlayer is about to open this URL immediately. Probing
                    // it here duplicates that same network read and adds an
                    // avoidable round trip to the user's tap-to-audio latency.
                    TrackLog.d(
                        TAG,
                        "resolved immediate carrier $videoId via ${client.clientName} " +
                            "@ ${format.kbps}kbps without pre-probe",
                    )
                    served(client)
                    preferred = client
                    return Stream(url, format.kbps, format.mimeType)
                }

                val verdict = timed("$videoId ${client.clientName} probe") { probe(url) }
                TrackLog.d(TAG, "TIMING $videoId ${client.clientName} total: ${SystemClock.elapsedRealtime() - clientStart}ms")
                when (verdict) {
                    Probe.OK -> {
                        TrackLog.d(TAG, "resolved $videoId via ${client.clientName} @ ${format.kbps}kbps")
                        served(client)
                        preferred = client
                        return Stream(url, format.kbps, format.mimeType)
                    }
                    // The client itself is being refused this track; don't
                    // spend another round trip on it for a while.
                    Probe.REFUSED -> {
                        if (formatFailureIsRefusal) {
                            standDown(videoId, client)
                            refused(videoId, client)
                        }
                    }
                    // Nobody answered, so this says nothing about the client —
                    // deliberately not counted as a refusal, or a bad minute on
                    // the connection would stand down clients that are fine.
                    Probe.UNREACHABLE -> Unit
                }
                // Which format was rejected, not just that one was: the same
                // client can mint a good URL for one itag and a dead one for
                // another, so without the format this line cannot tell a track
                // being refused from a codec being refused.
                TrackLog.w(
                    TAG,
                    "${client.clientName} minted an unusable URL for $videoId: " +
                        "$verdict for ${format.mimeType} @ ${format.kbps}kbps",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A client turned away with "Please sign in" or "confirm you're
                // not a bot" is not being told something about this track. It is
                // being told something about this app's session, and the answer
                // will be the same for the next track and the one after that —
                // which is exactly what the logs show: the same five clients
                // refusing, every track, for a whole session, while the walk
                // asks all of them again each time.
                //
                // That is worth more than the two seconds it spends. Each pass
                // is roughly ten requests, several of them minting a fresh
                // visitor id, and churning identities at that rate is itself
                // the behaviour Google throttles — measured here as every
                // request in a resolve going four to twenty times slower for a
                // stretch, which is the difference between a track starting in
                // three seconds and in twenty. Standing a refused client down
                // for everything, not just for one video, cuts both the wait
                // and the volume that provokes the throttling.
                //
                // A phrase match is the right test only when it matches, and it
                // is one sentence of Google's wording away from not. On this
                // emulator TVHTML5 is turned away with "The page needs to be
                // reloaded." every track — the same refusal, worded so as to
                // contain none of "bot", "unusual traffic" or "sign in" — and so
                // was asked again for every track of the session. Any refusal
                // therefore also counts toward [refused], which needs no
                // vocabulary because it waits for the repetition instead.
                if (e is Innertube.UnplayableException) {
                    if (e.looksLikeBotCheck) standDownEverywhere(client) else refused(videoId, client)
                }
                TrackLog.w(TAG, "${client.clientName} failed for $videoId: ${e.message}")
            }
        }
        return null
    }

    /**
     * [CLIENTS], led by whichever one last worked.
     *
     * Google's decisions apply to the whole app for as long as they last, not
     * to one track, so the client that served the previous song is overwhelmingly
     * likely to serve this one — and starting there is what keeps the common
     * case at a single round trip.
     */
    private fun clientOrder(): List<PlayerClient> {
        val first = preferred ?: return CLIENTS
        return listOf(first) + CLIENTS.filterNot { it == first }
    }

    @Volatile
    private var preferred: PlayerClient? = null

    // ---- Format selection ---------------------------------------------------

    /** One audio entry of a player response, before its URL has been unlocked. */
    private class Audio(
        val url: String?,
        val signatureCipher: String?,
        val kbps: Int,
        val mimeType: String,
    ) {
        /**
         * YouTube's Opus is always carried in WebM and its AAC always in MP4 —
         * there is no Opus-in-MP4 on this endpoint — so the container the mime
         * type names is enough to tell the two ladders apart, and the container
         * is the thing a download actually cares about.
         */
        val isAac: Boolean get() = "mp4" in mimeType.lowercase()
        val isOpus: Boolean get() = "opus" in mimeType.lowercase()
    }

    private fun audioFormats(response: JsonObject): List<Audio> =
        response["streamingData"]?.jsonObject
            ?.get("adaptiveFormats")?.jsonArray
            ?.map { it.jsonObject }
            ?.filter { it.str("mimeType")?.startsWith("audio/") == true }
            ?.map {
                Audio(
                    url = it.str("url"),
                    signatureCipher = it.str("signatureCipher") ?: it.str("cipher"),
                    kbps = ((it.str("bitrate")?.toLongOrNull() ?: 0L) / 1000).toInt(),
                    mimeType = it.str("mimeType").orEmpty(),
                )
            }
            ?.filter { it.url != null || it.signatureCipher != null }
            .orEmpty()

    private const val AAC_320_FLOOR_KBPS = 300
    private const val AAC_250_FLOOR_KBPS = 250
    /** User-facing Hi-Q badge/upgrade threshold. */
    private const val HI_QUALITY_UPGRADE_MIN_KBPS = 256
    private const val OPUS_128_FLOOR_KBPS = 120
    private const val AAC_128_FLOOR_KBPS = 120

    private fun pickAac320Tier(response: JsonObject): Audio? =
        audioFormats(response)
            .filter { it.isAac && it.kbps >= AAC_320_FLOOR_KBPS }
            .maxByOrNull { it.kbps }

    private fun pickAac250Tier(response: JsonObject): Audio? =
        audioFormats(response)
            .filter {
                it.isAac &&
                    it.kbps >= AAC_250_FLOOR_KBPS &&
                    it.kbps < AAC_320_FLOOR_KBPS
            }
            .maxByOrNull { it.kbps }

    private fun pickOpus128Tier(response: JsonObject): Audio? =
        audioFormats(response)
            .filter { it.isOpus && it.kbps >= OPUS_128_FLOOR_KBPS }
            .maxByOrNull { it.kbps }

    private fun pickAac128Tier(response: JsonObject): Audio? =
        audioFormats(response)
            .filter {
                it.isAac &&
                    it.kbps >= AAC_128_FLOOR_KBPS &&
                    it.kbps < AAC_250_FLOOR_KBPS
            }
            .maxByOrNull { it.kbps }

    /** What playback wants for the active network quality. */
    private fun pickForPlayback(response: JsonObject): Audio? {
        if (AppSettings.effectiveAudioQuality == AudioQuality.AAC) {
            return pickAacOnly(response) ?: pickForQualityResponse(response)
        }
        return pickForQualityResponse(response)
    }

    /** AAC/M4A only. Returning null tells the caller to keep looking for AAC. */
    private fun pickAacOnly(response: JsonObject): Audio? {
        val aac = audioFormats(response).filter { it.isAac }
        val within = aac.filter { it.kbps <= AudioQuality.AAC.maxKbps }
        return within.maxByOrNull { it.kbps } ?: aac.minByOrNull { it.kbps }
    }

    private fun pickForQualityResponse(response: JsonObject): Audio? =
        pickForQuality(audioFormats(response).map { it.kbps to it })


    /** Best lossy analysis copy at or below the AAC ceiling, independent of UI settings. */


    /**
     * What a download wants: the best AAC there is, and nothing else.
     *
     * MP4 rather than the better codec because it is the only container the
     * media store will accept for the audio collection — see
     * [resolveForDownload].
     *
     * With no playback target, no ceiling is applied: a saved file is paid for
     * once and kept. When the listener has already reached a known rendition,
     * [pickAacForDownload] instead chooses the nearest AAC bitrate so offline
     * playback preserves that observed quality without copying its transport.
     */
    private fun pickAac(response: JsonObject): Audio? =
        audioFormats(response).filter { it.isAac }.maxByOrNull { it.kbps }

    /**
     * AAC/MP4 copy nearest the rendition the listener actually heard.
     *
     * The playback stream is used only as a quality target. Its URL is not
     * copied straight to disk because a stream that ExoPlayer can consume is
     * not necessarily a self-contained MP4 file suitable for MediaStore. The
     * download resolver therefore mints and probes its own MP4 URL, preserving
     * the quality tier without inheriting the playback transport.
     */
    private fun pickAacForDownload(response: JsonObject, targetKbps: Int?): Audio? {
        val candidates = audioFormats(response).filter { it.isAac }
        if (candidates.isEmpty()) return null
        if (targetKbps == null) return candidates.maxByOrNull { it.kbps }
        return candidates.minWithOrNull(
            compareBy<Audio> { abs(it.kbps - targetKbps) }
                .thenByDescending { it.kbps },
        )
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    /**
     * Highest stream at or under the ceiling set for the connection in use; if
     * everything is above it (e.g. Low on a track that only has 130kbps+), take
     * the cheapest available rather than failing.
     */
    private fun <T> pickForQuality(candidates: List<Pair<Int, T>>): T? =
        pickAtCeiling(candidates, AppSettings.effectiveAudioQuality.maxKbps)

    private fun <T> pickAtCeiling(candidates: List<Pair<Int, T>>, ceiling: Int): T? {
        if (candidates.isEmpty()) return null
        val withinBudget = candidates.filter { it.first <= ceiling }
        return (withinBudget.maxByOrNull { it.first } ?: candidates.minByOrNull { it.first })
            ?.second
    }

    // ---- Unlocking ----------------------------------------------------------

    /** The playable URL behind a format, or null if it can't be unlocked. */
    private suspend fun streamUrl(videoId: String, format: Audio): String? {
        val direct = format.url
        if (direct != null) {
            val unlocked = deobfuscate(videoId, direct)
            return validHttpStreamUrl(videoId, unlocked, "player direct URL")
        }

        val cipher = format.signatureCipher ?: return null
        val params = cipher.split("&")
            .mapNotNull { part ->
                val i = part.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                URLDecoder.decode(part.substring(0, i), "UTF-8") to
                    URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }
            .toMap()

        val base = params["url"] ?: return null
        val signature = params["s"] ?: return null
        // Which query parameter the solved signature belongs in; YouTube has
        // changed the name before, so it travels alongside rather than assumed.
        val into = params["sp"] ?: "signature"
        val solved = runCatching {
            jsPlayerManager { YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, signature) }
        }.getOrElse {
            TrackLog.w(TAG, "signature cipher failed: ${it.message}")
            return null
        }
        val separator = if ("?" in base) "&" else "?"
        val unlocked = deobfuscate(videoId, "$base$separator$into=$solved")
        return validHttpStreamUrl(videoId, unlocked, "signature-cipher URL")
    }

    /**
     * Reject anything the actual playback transport (OkHttp) cannot parse.
     * `Uri.parse("media")` succeeds, which is why the old guard let a malformed
     * player answer reach ExoPlayer and fail five times at 12 ms. Keeping this
     * at URL-minting time lets the current client fall through and another
     * YouTube client/fallback win in the same resolve instead.
     */
    private fun validHttpStreamUrl(videoId: String, url: String?, stage: String): String? {
        val candidate = url?.trim().orEmpty()
        if (candidate.toHttpUrlOrNull() != null) return candidate
        TrackLog.w(
            TAG,
            "$stage returned malformed stream URL for $videoId; ignoring " +
                candidate.substringBefore('?').take(96).ifBlank { "<blank>" },
            about = videoId,
        )
        return null
    }

    /**
     * Transform the `n` parameter when present. If deobfuscation itself fails
     * we still return the original URL — a throttled stream beats no stream,
     * and [probe] gets the final say on whether it plays at all.
     */
    private suspend fun deobfuscate(videoId: String, url: String): String {
        val needsWork = url.toHttpUrlOrNull()?.queryParameter("n")?.isNotBlank() == true
        if (!needsWork) return url
        return runCatching {
            jsPlayerManager { YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url) }
        }.getOrElse {
            TrackLog.w(TAG, "n-param deobfuscation failed: ${it.message}")
            url
        }
    }

    /**
     * Guards every call into [YoutubeJavaScriptPlayerManager].
     *
     * Its player-JS cache — the parsed code, the deobfuscation function, and
     * even a *failed* parse's exception — lives in static fields with no
     * synchronization, shared by the whole process. This app resolves more
     * than one track at once by design (a track playing while its successor
     * pre-caches — see [AudioCache][com.music.orb.playback.AudioCache]),
     * so two resolves can enter these calls together; the library was never
     * written for that, and serializing access is what keeps concurrent
     * resolves from corrupting that shared state.
     *
     * A failure here is passed straight out, and deliberately so. An earlier
     * version answered one by calling [clearAllCaches] and running the block
     * again, on the reasoning that the library caches a *failed* parse's
     * exception and replays it forever, so one bad parse would otherwise be
     * permanent for the process. Measured, that cure was far worse than the
     * disease, for two reasons that only show up on a device:
     *
     *  - The failure this app actually sees — "Could not parse deobfuscation
     *    function" against a `base.js` this NewPipe release doesn't understand
     *    — is deterministic. Re-fetching the script and parsing it again cannot
     *    end differently, so the retry only ever bought a second failure, at
     *    5s to 55s a time, holding this mutex while every concurrent resolve
     *    queued behind it.
     *  - [clearAllCaches] is all-or-nothing. It throws away the parsed player
     *    JS that the *working* paths depend on — the `n` parameter transform
     *    that [newPipeStream] runs for every format it extracts — along with
     *    the one broken function. So the price of the retry was charged twice:
     *    once here, and again on the fallback that was about to succeed, which
     *    went from 2.7s warm to 49.8s against the cache this had just emptied.
     *
     * Left alone, the cached exception makes the failure free — it is what
     * turns a broken signature solve into the ~0ms no it should always have
     * been, and what lets the walk move on to something that works. The cost
     * of not clearing is that a parse which failed for a reason that has since
     * passed stays failed until the process restarts; that is a real loss, and
     * a much smaller one than a fifty-second track.
     */
    private val jsPlayerMutex = Mutex()

    private suspend fun <T> jsPlayerManager(block: () -> T): T = jsPlayerMutex.withLock { block() }

    /**
     * Align the URL's `cver` with the client that actually asked.
     *
     * The player response fills it in from the request, but a signature or `n`
     * transform can be solved against player JavaScript of a different vintage,
     * and googlevideo answers a version it doesn't expect with a 403.
     */
    private fun patchClientVersion(url: String, clientVersion: String): String =
        if ("cver=" in url) url.replace(Regex("cver=[^&]+"), "cver=$clientVersion") else url

    // ---- Validation ---------------------------------------------------------

    private enum class Probe {
        /** Served media bytes; safe to play and to cache. */
        OK,

        /** Answered, but refused this request — the client is the problem. */
        REFUSED,

        /** Never got an answer worth interpreting; blame nothing in particular. */
        UNREACHABLE,
    }

    /**
     * Read the end of a URL before trusting it.
     *
     * This is the whole difference between a track that fails and a track that
     * fails *visibly and instantly*. A URL that 403s is indistinguishable from
     * a good one until something reads from it; hand it to ExoPlayer and the
     * failure surfaces as a track that spins and never starts.
     *
     * The range has to be as large as the real fetch will ask for, not a token
     * one. A URL minted for a session Google has reservations about serves
     * small ranges to anybody — enough to pass a small probe — and then refuses
     * the multi-megabyte ranges actual listening is made of with a 403.
     * [PROBE_RANGE_BYTES] matches the chunk size the player and read-ahead
     * fetch with, so a grudging URL fails here instead of on the playback path.
     * Sixteen kilobytes of the answer still have to actually arrive, so a
     * response that stalls after its headers is a failure too.
     *
     * The headers are the ones the media fetch will really use — see
     * [PlayerClient.forStreamUrl] — so this tests the request that matters
     * rather than a more favourable version of it.
     */
    private fun probe(url: String): Probe {
        val builder = okhttp3.Request.Builder().url(url)
            .header("Range", "bytes=0-${PROBE_RANGE_BYTES - 1}")
        PlayerClient.forStreamUrl(url).mediaHeaders().forEach { (name, value) ->
            builder.header(name, value)
        }
        return try {
            prober.newCall(builder.build()).execute().use { response ->
                when {
                    response.code in REFUSAL_CODES -> Probe.REFUSED
                    response.code !in 200..299 && response.code != 416 -> Probe.UNREACHABLE
                    // A refusal dressed as a success: an error page, or the
                    // consent/captcha interstitial, rather than audio.
                    response.header("Content-Type")?.startsWith("audio/") != true -> Probe.REFUSED
                    // Headers can arrive long before a body that never does —
                    // exactly the shaping this whole path exists to sidestep.
                    // Insisting on the bytes is the point: a trickle that
                    // yields its first byte and stalls is a failure too.
                    response.body?.source()?.request(PROBE_READ_BYTES) != true -> Probe.UNREACHABLE
                    else -> Probe.OK
                }
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "probe failed: ${e.message}")
            Probe.UNREACHABLE
        }
    }

    private val REFUSAL_CODES = setOf(403, 404, 410)

    /**
     * The probe's own client: the app's, but on a short leash.
     *
     * [Http.client]'s 30-second read timeout is right for a stream being
     * consumed as it arrives and far too patient for a yes/no question —
     * waiting it out is indistinguishable from the stall being tested for.
     * Built from the shared client, so the connection pool and DNS are the
     * same ones the real fetch will use.
     */
    private val prober by lazy {
        Http.client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private const val PROBE_TIMEOUT_SECONDS = 6L

    /**
     * How much the probe asks for, in one range.
     *
     * Has to match what the real fetch asks for ([ChunkedDataSource] and
     * [AudioCache] both fetch two-megabyte ranges), or a URL that grudges real
     * listening-sized requests — while still serving token ones — sails through
     * the probe and dies on the playback path instead.
     */
    private const val PROBE_RANGE_BYTES = 2L * 1024 * 1024

    /** How much of the answer must actually arrive, to catch a stalled body. */
    private const val PROBE_READ_BYTES = 16L * 1024

    // ---- Clients stood down -------------------------------------------------

    /**
     * Clients refused a given track, and until when.
     *
     * A refusal is rarely about the track alone — it usually means Google has
     * stopped answering that identity — but it is recorded per track because
     * that is the granularity it can be observed at. Keyed the same way it is
     * looked up, so a stale entry costs one retry rather than a lasting hole.
     */
    private val standDownUntil = ConcurrentHashMap<String, Long>()

    private const val STAND_DOWN_MS = 10 * 60 * 1000L

    private fun key(videoId: String, client: PlayerClient) =
        "$videoId|${client.clientName}@${client.clientVersion}"

    private fun standDown(videoId: String, client: PlayerClient) {
        standDownUntil[key(videoId, client)] = SystemClock.elapsedRealtime() + STAND_DOWN_MS
    }

    private fun isStoodDown(videoId: String, client: PlayerClient): Boolean =
        isStoodDown(key(videoId, client)) || isStoodDown(key(client))

    private fun isStoodDown(k: String): Boolean {
        val until = standDownUntil[k] ?: return false
        if (until > SystemClock.elapsedRealtime()) return true
        standDownUntil.remove(k)
        return false
    }

    /**
     * Which tracks each client has been refused since it last served one.
     *
     * [standDownEverywhere] is the right answer for a client that has stopped
     * being served, and reading the refusal is the hard part: Google says no in
     * whatever words it likes, and only some of them are recognisable. So this
     * does not try to read it. A client asked for one track and refused has
     * told us about that track; a client asked for three different tracks and
     * refused all three, without serving anything in between, has told us about
     * itself — whatever the wording. Videos rather than a count because one
     * track can put the same client through this twice (see [resolveForDownload],
     * which walks for AAC and then for anything), and two refusals of the same
     * track are one piece of evidence.
     */
    private val refusalsByClient = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * How much evidence is enough. Low, because the cost of being wrong is
     * bounded by [STAND_DOWN_MS] and the cost of being slow is paid on every
     * track: three tracks of the walk, then the rest of the ten minutes going
     * straight to what works.
     */
    private const val REFUSALS_BEFORE_STANDING_DOWN = 3

    /** A client answered about [videoId], and the answer was no use. */
    private fun refused(videoId: String, client: PlayerClient) {
        val refusedTracks = refusalsByClient.computeIfAbsent(key(client)) {
            ConcurrentHashMap.newKeySet<String>()
        }
        refusedTracks.add(videoId)
        if (refusedTracks.size >= REFUSALS_BEFORE_STANDING_DOWN) {
            // Cleared as it escalates, so that when the stand-down expires the
            // client is owed a fresh [REFUSALS_BEFORE_STANDING_DOWN] tracks
            // rather than being stood down again by the first one.
            refusalsByClient.remove(key(client))
            standDownEverywhere(client)
        }
    }

    /**
     * A client served a track, so what came before it was about those tracks
     * rather than about the client.
     */
    private fun served(client: PlayerClient) {
        refusalsByClient.remove(key(client))
    }

    /**
     * A client refused the session rather than the track — see [playerStream].
     *
     * Shares [standDownUntil] and its expiry with the per-track case, under a
     * key naming no video. The expiry is the whole reason this is safe to do
     * app-wide: Google's decisions here last hours but not forever, so one
     * track every [STAND_DOWN_MS] pays for a full walk and finds out whether
     * the client is being served again, while the rest go straight to what
     * works.
     */
    private fun standDownEverywhere(client: PlayerClient) {
        val k = key(client)
        if (!isStoodDown(k)) {
            TrackLog.d(TAG, "${client.clientName} is refusing this session; standing it down app-wide")
        }
        standDownUntil[k] = SystemClock.elapsedRealtime() + STAND_DOWN_MS
        // The walk starts from whichever client last worked; a client that is
        // now being skipped everywhere must not be that one.
        if (preferred == client) preferred = null
    }

    private fun key(client: PlayerClient) = "*|${client.clientName}@${client.clientVersion}"

    /**
     * A URL that [probe] cleared has been refused while actually playing.
     *
     * Everything above assumes a URL that served bytes once will keep serving
     * them, and mostly that holds. When it doesn't, nothing here would ever
     * find out: [probe] runs before playback and not again, so a client that
     * goes bad mid-session stays [preferred], and [recent] keeps handing back
     * the same dead URL for the rest of its TTL. Every following track then
     * fails the same way, and the app can only be talked out of it by being
     * restarted — which is the one symptom users actually report.
     *
     * So the refusal is fed back: forget the URL, stand the client down for
     * that track, and give up the preference so the next resolve starts from
     * the top of [CLIENTS] rather than from the client that just failed.
     *
     * Called from the playback path — see
     * [ChunkedDataSource][com.music.orb.playback.ChunkedDataSource].
     */
    /**
     * Drops only the short-lived playback URL cached for [videoId].
     *
     * Recovery uses this after a 401/403. [StreamChoice] is a different cache
     * and is cleared by PlaybackService; clearing both is intentional. A
     * prepared queue fallback may otherwise ask [resolveImmediatePlayback]
     * again and receive the same signed URL from [recent], recreating the
     * exact failure at the same byte boundary.
     */
    fun invalidatePlaybackUrl(videoId: String) {
        if (recent.remove(videoId) != null) {
            TrackLog.d(TAG, "invalidated cached playback URL for $videoId", about = videoId)
        }
    }

    fun onPlaybackRefused(url: String, responseCode: Int) {
        if (responseCode !in REFUSAL_CODES) return
        // Only googlevideo's URLs say anything about a [PlayerClient]. Anything
        // else — a module's stream URL, a downloaded file — carries no `c`
        // parameter, and [PlayerClient.forStreamUrl] answers IOS for a URL it
        // can't read rather than nothing. So without this, a Tidal URL
        // answering 404 stands down the client that mints most of YouTube's,
        // and the next YouTube track pays for a failure on a different server.
        if (url.toHttpUrlOrNull()?.host?.endsWith("googlevideo.com") != true) return
        val client = PlayerClient.forStreamUrl(url)
        // Keyed by videoId, and the fetch only knows the googlevideo URL it was
        // handed; the map is a latency cache of a few dozen entries, so finding
        // the way back costs nothing worth measuring.
        recent.entries.firstOrNull { it.value.url == url }?.key?.let { videoId ->
            recent.remove(videoId)
            standDown(videoId, client)
        }
        // Independent of that lookup on purpose: standing down the preference
        // is what breaks the loop, and it must still happen if the URL has
        // already aged out of the cache.
        if (preferred == client) {
            TrackLog.w(TAG, "${client.clientName} refused a URL it had already served; standing it down")
            preferred = null
        }
    }

    // ---- Failsafe -----------------------------------------------------------

    /**
     * NewPipe's full extractor, kept for the case where every player client has
     * been turned away — it re-derives everything itself and is updated
     * upstream when YouTube changes, so it works when nothing else does.
     *
     * Last rather than first because of what it costs: it scrapes the watch
     * page, which is the request Google shapes hardest, and a shaped response
     * can hold this call open for the better part of a minute. Worth waiting
     * out when the alternative is silence; not worth paying for every track.
     *
     * Driven through [StreamExtractor][org.schabi.newpipe.extractor.stream.StreamExtractor]
     * directly rather than through `StreamInfo.getInfo`, which is the obvious
     * call and the expensive one. `getInfo` assembles everything a video page
     * has — it drives forty-odd extractor methods, among them `getVideoStreams`
     * and `getVideoOnlyStreams`, and YouTube answers those with twenty to
     * thirty formats whose `n` parameter each has to be transformed by running
     * YouTube's player JavaScript. This app then discards every one of them and
     * keeps the audio. Fetching the page and asking only for [audioStreams]
     * pays that transform four or five times instead: measured on-device at
     * 49.8s against 2.3s for the same track over the same connection.
     */
    /**
     * One extractor pass for the playback hierarchy. The expensive watch-page /
     * player-JS work happens once; the returned candidate is the best NewPipe
     * can offer under the same global order used by the player clients.
     */
    private suspend fun newPipePreferredLossyStream(videoId: String): Stream? = try {
        extractStream(videoId) { candidates ->
            val ranked = candidates.map { (kbps, stream) ->
                Triple(kbps, stream, newPipeLossyTier(kbps, stream))
            }

            ranked.filter { it.third == LossyTier.AAC_320 }
                .maxByOrNull { it.first }?.second
                ?: ranked.filter { it.third == LossyTier.AAC_250 }
                    .maxByOrNull { it.first }?.second
                ?: ranked.filter { it.third == LossyTier.OPUS_128 }
                    .maxByOrNull { it.first }?.second
                ?: ranked.filter { it.third == LossyTier.AAC_128 }
                    .maxByOrNull { it.first }?.second
                ?: candidates.maxByOrNull { it.first }?.second
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        TrackLog.d(TAG, "NewPipe hierarchy pass missed for $videoId: ${error.message}")
        null
    }

    private fun newPipeLossyTier(kbps: Int, stream: AudioStream): LossyTier = when {
        stream.isM4a && kbps >= AAC_320_FLOOR_KBPS -> LossyTier.AAC_320
        stream.isM4a && kbps >= AAC_250_FLOOR_KBPS -> LossyTier.AAC_250
        stream.isOpus && kbps >= OPUS_128_FLOOR_KBPS -> LossyTier.OPUS_128
        stream.isM4a && kbps >= AAC_128_FLOOR_KBPS -> LossyTier.AAC_128
        else -> LossyTier.EMERGENCY
    }

    /**
     * One extractor pass that accepts M4A/AAC only. A codec miss is not retried:
     * it means this extractor response has no AAC, not that the network failed.
     */
    private suspend fun newPipeAacStream(videoId: String): Stream? = try {
        extractStream(videoId) { candidates ->
            val aac = candidates.filter { (_, stream) -> stream.isM4a }
            val within = aac.filter { (kbps, _) -> kbps <= AudioQuality.AAC.maxKbps }
            (within.maxByOrNull { it.first } ?: aac.minByOrNull { it.first })?.second
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        TrackLog.d(TAG, "NewPipe AAC pass missed for $videoId: ${error.message}")
        null
    }

    private suspend fun newPipeStream(
        videoId: String,
        select: (List<Pair<Int, AudioStream>>) -> AudioStream?,
    ): Stream {
        var failure: Exception? = null
        repeat(EXTRACTION_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(EXTRACTION_RETRY_MS * attempt)
            try {
                return extractStream(videoId, select)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                // Not a failed extraction — a method or class the extractor was
                // compiled against that this OS version does not have, which is
                // the same answer every time and so is not worth the remaining
                // attempts. It reaches here as an Error rather than an Exception,
                // and an Error let past this point does not fail the track: it
                // unwinds through ExoPlayer's loader thread, where nothing is
                // catching it, and takes the process with it. That is what a
                // NoSuchMethodError out of NewPipe's URL codec did on every
                // Android below 13 — see the note in the vendored
                // `org.schabi.newpipe.extractor.utils.Utils`. Reported as an
                // Exception so the layers above treat it as the load failure it
                // is, with the type name kept because the message alone ("No
                // static method ...") reads like nothing.
                throw IOException("Extractor cannot run on this device: $e", e)
            } catch (e: Exception) {
                // Worth another go rather than worth giving up on: the common
                // failure here is a shaped or cut-off watch page, which is a
                // fact about this minute rather than about the track, and this
                // is the last thing standing between the listener and silence.
                TrackLog.w(
                    TAG,
                    "extraction attempt ${attempt + 1} of $EXTRACTION_ATTEMPTS failed for $videoId: ${e.message}",
                )
                failure = e
            }
        }
        throw failure ?: IllegalStateException("Track unavailable: no audio streams")
    }

    /** How many times the watch page is worth asking for before giving up. */
    private const val EXTRACTION_ATTEMPTS = 3

    /** Multiplied by the attempt number, so the wait grows if the first retry doesn't take. */
    private const val EXTRACTION_RETRY_MS = 1000L

    /**
     * One extraction at a time, across the whole app.
     *
     * Extraction is not the network-bound step it looks like. Fetching the
     * watch page is the small part; the expensive part is running YouTube's
     * player JavaScript through Rhino to transform each format's `n`
     * parameter, which is CPU work on a phone against static state NewPipe
     * shares process-wide. Run concurrently it does not share out, it
     * collapses — measured on-device at 1.8s alone, 16.2s with two in flight
     * (both finishing within 35ms of each other, which is the tell), and 30.3s
     * with three. Read-ahead is what puts three or four in flight: the track
     * being waited on plus [AudioCache][com.music.orb.playback.AudioCache]'s
     * queue warm-up, every one of them an extraction now that no player client
     * is being served.
     *
     * So they are queued rather than raced. Serialised, three cost about two
     * seconds each in turn instead of thirty seconds each at once, and the one
     * a listener is actually waiting on is behind at most one other — see
     * `QUEUE_LOOKAHEAD`, kept at one for exactly this reason. The bound on how
     * long a single holder can keep the gate is [EXTRACTOR_TIMEOUT_SECONDS].
     */
    private val extractionGate = Mutex()

    private suspend fun extractStream(
        videoId: String,
        select: (List<Pair<Int, AudioStream>>) -> AudioStream?,
    ): Stream = extractionGate.withLock {
        withContext(Dispatchers.IO) {
            val waited = SystemClock.elapsedRealtime()
            val extractor = ServiceList.YouTube.getStreamExtractor(
                "https://www.youtube.com/watch?v=$videoId",
            )
            extractor.fetchPage()
            val candidates = extractor.audioStreams
                // Progressive only — DASH/HLS entries carry a manifest, not a URL.
                .filter {
                    !it.content.isNullOrBlank() &&
                        it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
                }
            val stream = select(candidates.map { it.averageBitrate to it })
                ?: error("Track unavailable: no audio streams")
            TrackLog.d(
                TAG,
                "NewPipe picked ${stream.format?.name} @ ${stream.averageBitrate}kbps " +
                    "(extraction held the gate ${SystemClock.elapsedRealtime() - waited}ms)",
            )
            // stream.content is already playable, not raw: YoutubeStreamExtractor
            // resolves the signature cipher and the `n` parameter itself while
            // building audioStreams, through the same YoutubeJavaScriptPlayerManager
            // this file also calls directly. Running it through deobfuscate() again
            // was a second, redundant trip through that same machinery on every
            // fallback — real latency (and a second chance to hit whatever's
            // currently failing it) spent solving something already solved.
            val resolvedMime = when (stream.format) {
                MediaFormat.M4A -> "audio/mp4; codecs=\"mp4a.40.2\""
                MediaFormat.WEBMA_OPUS -> "audio/webm; codecs=\"opus\""
                else -> stream.mime
            }
            val playableUrl = validHttpStreamUrl(videoId, stream.content, "NewPipe URL")
                ?: error("Extractor returned malformed audio URL")
            Stream(
                url = playableUrl,
                kbps = stream.averageBitrate,
                mimeType = resolvedMime,
            )
        }
    }

    /**
     * The container, which is all NewPipe's mime type reports.
     *
     * `MediaFormat.WEBMA_OPUS` — what YouTube's Opus arrives as — carries the
     * mime type `audio/webm`, identical to the Vorbis-in-WebM entry beside it.
     * Accurate about the bytes, and useless for telling the codec apart, which
     * is what [isM4a] is asked of the format for instead.
     */
    private val AudioStream.mime: String get() = format?.mimeType.orEmpty()

    /**
     * Whether these bytes are AAC in MP4, asked of the format rather than its
     * name.
     *
     * The name would in fact do here — `MediaFormat.M4A` reports `audio/mp4`,
     * the same thing the player endpoint calls it — but the enum is what
     * actually carries the answer, and the sibling case is a standing warning
     * against reading these mime types as codecs: the format that means Opus
     * says `audio/webm`, so a download demanding Opus by name concluded the
     * track hadn't any and fell through, while playback took the very same
     * stream and played it as Opus.
     */
    private val AudioStream.isM4a: Boolean
        get() = format == MediaFormat.M4A

    private val AudioStream.isOpus: Boolean
        get() = format == MediaFormat.WEBMA_OPUS

    // ---- Cache --------------------------------------------------------------

    /**
     * How many bytes the whole track is, or null if the URL doesn't say.
     *
     * Every progressive googlevideo URL carries the figure as `clen`. It is
     * worth reading from there because the alternative is an HTTP request that
     * reaches the end of the resource: a bounded range never reveals the total,
     * so read-ahead would have no way to know when it was finished. Resolving
     * is memoised, so asking costs nothing beyond the first time.
     */
    suspend fun contentLength(videoId: String): Long? =
        resolve(videoId).toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()

    /** Codec/container of the fresh resolved URL, for queue warm-up diagnostics. */
    fun resolvedMimeType(videoId: String): String? =
        recent[videoId]
            ?.takeIf { SystemClock.elapsedRealtime() - it.at < URL_TTL_MS }
            ?.mimeType

    /** Bitrate of the exact fresh playback rendition already proven by [resolve]. */
    fun resolvedBitrateKbps(videoId: String): Int? =
        recent[videoId]
            ?.takeIf { SystemClock.elapsedRealtime() - it.at < URL_TTL_MS }
            ?.kbps

    private class Resolved(
        val url: String,
        val at: Long,
        val mimeType: String,
        val kbps: Int,
    ) {
        val isAac: Boolean get() = "mp4" in mimeType.lowercase() || "m4a" in mimeType.lowercase()

        fun matches(quality: AudioQuality): Boolean = when (quality) {
            AudioQuality.AAC -> isAac && kbps <= AudioQuality.AAC.maxKbps
            AudioQuality.HIGH -> true
            AudioQuality.MEDIUM, AudioQuality.LOW -> kbps <= quality.maxKbps
        }
    }

    /**
     * Stream URLs already resolved, by videoId — and, since [resolve] only ever
     * stores one that has served bytes, already known good rather than merely
     * recent.
     *
     * Google issues them with several hours of validity, so the ceiling here is
     * chosen for a different reason: a URL is tied to the playback session that
     * minted it, and holding one indefinitely means a stale entry survives long
     * enough to fail a play. Twenty minutes covers a track and the seeking
     * around it while staying well inside the window where the URL is good.
     */
    private val recent = ConcurrentHashMap<String, Resolved>()


    private const val URL_TTL_MS = 20 * 60 * 1000L

    /** Enough for the queue in hand; this is a latency cache, not a store. */
    private const val MAX_REMEMBERED = 32

    /** See [resolveForDownload]: one walk to burn a stale visitor id, one to use its replacement. */
    private const val DOWNLOAD_ATTEMPTS = 2

    /** Long enough for a freshly minted visitor id to be worth anything, short enough not to be felt. */
    private const val DOWNLOAD_RETRY_MS = 500L

    private fun remember(videoId: String, stream: Stream) {
        if (validHttpStreamUrl(videoId, stream.url, "resolved cache") == null) return
        if (recent.size >= MAX_REMEMBERED) {
            val cutoff = SystemClock.elapsedRealtime() - URL_TTL_MS
            recent.entries.removeAll { it.value.at < cutoff }
            if (recent.size >= MAX_REMEMBERED) recent.clear()
        }
        recent[videoId] = Resolved(
            stream.url,
            SystemClock.elapsedRealtime(),
            stream.mimeType,
            stream.kbps,
        )
    }
}
