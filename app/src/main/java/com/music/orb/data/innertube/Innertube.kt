package com.music.orb.data.innertube

import com.music.orb.data.DebugLog as Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.statement.HttpResponse
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import com.music.orb.data.model.LikeStatus
import com.music.orb.data.model.PlaylistPrivacy
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Minimal Innertube (youtubei) client.
 *
 * Two kinds of client identity, for different reasons:
 *
 *  - **WEB_REMIX** against music.youtube.com for browse/search/library. It
 *    returns the full YT Music shelf layout and honours the signed-in session.
 *
 *  - **A device client** for the `player` endpoint, chosen per call. Which
 *    ones Google answers changes without notice, so [player] takes the
 *    identity as an argument and [StreamResolver] walks a list of them rather
 *    than betting the app on any single one. See [PlayerClient].
 *
 * Authenticated account requests prefer a short-lived Google OAuth bearer
 * token. A browser cookie/SAPISIDHASH session is retained only as a compatibility
 * fallback because this is a private YouTube Music API and individual endpoints
 * can reject OAuth even when Google's public authorization succeeded.
 */
object Innertube {

    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val YT_BASE = "https://www.youtube.com/youtubei/v1"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"

    private val WEB_REMIX_VERSION: String =
        "1.${DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now(ZoneOffset.UTC))}.01.00"
    private const val WEB_REMIX_CLIENT_ID = "67"

    private const val TAG = "BitChord"

    /** Legacy browser session used only when a private Music endpoint rejects OAuth. */
    var cookie: String? = null

    /** True after Orb's Credential Manager/Supabase Google identity is established. */
    @Volatile
    var accountSignedIn: Boolean = false

    /**
     * YouTube delegated/Brand Account selected under the Google account.
     * Null means the primary/default YouTube identity.
     */
    @Volatile
    var delegatedPageId: String? = null


    /** Google account index used by the YouTube web session; normally zero. */
    @Volatile
    var authUserIndex: Int = 0

    /** Supplied by [com.music.orb.auth.OrbGoogleAuth] at process start. */
    var oauthAccessTokenProvider: (suspend () -> String?)? = null

    /** Lets the auth layer invalidate/suppress a bearer rejected by Innertube. */
    var oauthRejectedHandler: (suspend (String) -> Unit)? = null

    /** Clears a stale compatibility cookie and asks the UI to repair it. */
    var legacySessionRejectedHandler: (suspend () -> Unit)? = null

    val hasAccountSession: Boolean
        get() = accountSignedIn || cookie != null

    /**
     * Google's per-session visitor id.
     *
     * Far more load-bearing than "an id for stats". A `player` request that
     * carries no visitor id is treated as a client with no session at all, and
     * Google answers it in one of two ways: the honest one, `LOGIN_REQUIRED` /
     * "Sign in to confirm you're not a bot", or the quiet one — a perfectly
     * ordinary-looking response whose stream URLs serve a byte to anything that
     * asks and then refuse every real read with 403. The second is what
     * "it loads and then doesn't play" is made of.
     *
     * So it is fetched deliberately by [ensureVisitorData] rather than being
     * hoped for: browse responses carry one only sometimes, and a session that
     * never happened to see one would silently never play anything.
     */
    @Volatile
    private var visitorData: String? = null

    /**
     * A visitor id for this session, minting one if there isn't one yet.
     *
     * @param refresh discard the current id and take a fresh one — worth doing
     *   exactly once when a request comes back accusing us of being a bot,
     *   since an id can be burned while the session around it is fine.
     */
    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh && visitorData != null) return visitorData
        runCatching { fetchVisitorData() }
            .onFailure { Log.w(TAG, "could not mint a visitor id: ${it.message}") }
            .getOrNull()
            ?.let { visitorData = it }
        return visitorData
    }

    /**
     * The service worker bootstrap the web player loads before anything else,
     * which is where a fresh visitor id comes from without needing a page.
     * It answers with an anti-hijacking prefix and then plain nested arrays,
     * so the id is found by shape rather than by a path that would rot.
     */
    private suspend fun fetchVisitorData(): String? {
        val body = client.get("https://www.youtube.com/sw.js_data") {
            header("User-Agent", WEB_USER_AGENT)
        }.bodyAsText()
        val payload = Json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
        return findVisitorData(payload)
    }

    private fun findVisitorData(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findVisitorData(it) }
        is JsonPrimitive -> element.contentOrNull?.takeIf { VISITOR_DATA.matches(it) }
        else -> null
    }

    /** Protobuf-in-base64; always this shape, and nothing else in there is. */
    private val VISITOR_DATA = Regex("""Cg[A-Za-z0-9_%-]{40,}""")

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }

    /** See [postPlayer] — the per-request ceiling on the walk's hot path. */
    private const val PLAYER_TIMEOUT_MS = 6_000L

    private val client = HttpClient(OkHttp) {
        // Same OkHttp instance ExoPlayer streams through — see Http.
        engine { preconfigured = com.music.orb.data.Http.client }
        install(ContentNegotiation) { json(json) }
        // Without this the only bound is OkHttp's own read timeout, and the
        // failure it raises reads as "Socket timeout has expired […]
        // socket_timeout=unknown" — Ktor reporting a limit it was never told.
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = true
    }

    /**
     * Runs [block], giving transport failures another go before letting them
     * reach the caller.
     *
     * A connection reset on mobile data is weather, not information: the
     * request was fine and asking again generally answers. That matters most
     * on a shared connection pool, where a socket torn down under one request
     * — an abandoned search, a network handover — surfaces as
     * "Software caused connection abort" on whichever request picked that
     * connection up next, which had nothing to do with it.
     *
     * Only transport failures. An HTTP error status is an answer, and
     * repeating the question won't change it. Cancellation isn't caught at
     * all: [delay] throws when the coroutine is cancelled, so a search the
     * user has typed past stops here instead of retrying on behalf of a query
     * nobody is waiting for.
     */
    private suspend fun <T> withRetry(attempts: Int = 3, block: suspend () -> T): T {
        var backoff = 500L
        repeat(attempts - 1) {
            try {
                return block()
            } catch (e: HttpRequestTimeoutException) {
                // Not weather, and not worth repeating. A timeout is this app's
                // own decision that the request had long enough — so trying it
                // again cannot learn anything the first attempt didn't, and the
                // cost is multiplied rather than shared: [HttpRequestTimeoutException]
                // is an [IOException], so before this branch existed every timed-out
                // `player` call was quietly attempted three times. That turned a
                // six-second ceiling into a nineteen-second one on a walk of
                // seven clients, which is worse than the unbounded call the
                // ceiling was added to prevent. Give up on this client and let
                // the caller move to the next one.
                Log.d(TAG, "not retrying, request timed out: ${e.message}")
                throw e
            } catch (e: IOException) {
                Log.d(TAG, "retrying: ${e.message}")
            }
            delay(backoff)
            backoff *= 2
        }
        return block()
    }

    // ---- Public API ---------------------------------------------------------

    /**
     * Exact Orb v1.5.1 WEB_REMIX browse path used by album/playlist/artist detail opening.
     *
     * Intentionally bypasses the newer OAuth/anonymous-fallback routing: v1.5.1 sent one
     * ordinary WEB_REMIX request on the shared client and attached the browser cookie /
     * SAPISIDHASH only when a cookie already existed.
     */
    suspend fun browseV151(browseId: String, params: String? = null): JsonObject =
        postMusicV151("browse") {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }

    /** Exact v1.5.1 continuation request used by detail-page background fill. */
    suspend fun browseContinuationV151(token: String): JsonObject = postMusicV151(
        endpoint = "browse",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }

    suspend fun browse(
        browseId: String,
        params: String? = null,
        regionOverride: String? = null,
        selectedCountry: String? = null,
        requestTimeoutMs: Long? = null,
    ): JsonObject =
        postMusic(
            "browse",
            regionOverride = regionOverride,
            allowAnonymousFallback = true,
            requestTimeoutMs = requestTimeoutMs,
            preferLegacyCookie = true,
        ) {
            put("browseId", browseId)
            params?.let { put("params", it) }
            selectedCountry?.takeIf { it.isNotBlank() }?.let { country ->
                // Current YouTube Music charts use the sort/filter form payload
                // to select a chart country. `gl` still shapes the surrounding
                // catalogue, but does not reliably switch the chart itself.
                putJsonObject("formData") {
                    putJsonArray("selectedValues") { add(country) }
                }
            }
            // Same acknowledgement the player endpoint already sends. This lets
            // explicit releases pass through the catalogue without an interstitial.
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

    /**
     * Fast catalogue read for a detail page the listener explicitly opened.
     *
     * Artist/album/public-playlist metadata is public catalogue data. Asking
     * Google for an OAuth bearer before this request can block the entire screen
     * behind token refresh or a private WEB_REMIX auth fallback even though the
     * same browse response is available anonymously. This path intentionally
     * skips account auth and uses a short one-shot ceiling. Account-only state
     * (library/save status, private playlists) is enriched by the normal
     * authenticated path only when the anonymous answer is insufficient.
     */
    suspend fun browseFastRead(
        browseId: String,
        params: String? = null,
        requestTimeoutMs: Long = 5_000L,
    ): JsonObject = browse(
        browseId = browseId,
        params = params,
        requestTimeoutMs = requestTimeoutMs,
    )

    /**
     * Fast anonymous continuation used only after a visible detail page already
     * has content. If this fails, callers can stop paging without ever taking
     * the already-rendered rows away.
     */
    suspend fun browseContinuationFastRead(
        token: String,
        requestTimeoutMs: Long = 5_000L,
    ): JsonObject = browseContinuation(token, requestTimeoutMs)

    /**
     * The next page of a paged browse response — playlists and library feeds
     * come back roughly 100 rows at a time. YouTube Music takes the token as
     * query parameters rather than in the body, and answers with a bare
     * continuation envelope carrying the same row renderers.
     */
    suspend fun browseContinuation(
        token: String,
        requestTimeoutMs: Long? = null,
    ): JsonObject = postMusic(
        endpoint = "browse",
        // The web client passes the token in the body and the older query-string
        // form is still honoured; both are sent so either is enough.
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
        allowAnonymousFallback = true,
        requestTimeoutMs = requestTimeoutMs,
        preferLegacyCookie = true,
    ) {
        put("continuation", token)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
    }

    /**
     * Latency-sensitive browse used only by a page the listener explicitly opened.
     *
     * A half-open mobile socket can otherwise sit on the global 30 s request
     * ceiling while the detail screen shows placeholders. Give the first socket
     * five seconds; if it stalls, open one fresh request with an eight-second
     * ceiling. The outer coroutine deadline also caps OkHttp-level retries, so
     * a socket timeout can never multiply into a 20-30 second foreground wait.
     */
    suspend fun browseForeground(
        browseId: String,
        params: String? = null,
    ): JsonObject = browse(browseId, params = params)

    /** Same foreground policy for one continuation page requested by scrolling. */
    suspend fun browseContinuationForeground(token: String): JsonObject =
        browseContinuation(token)

    /** Signed-in profile: display name, email/handle and avatar. */
    suspend fun accountMenu(): JsonObject = postMusic("account/account_menu") {}


    /** Primary + Brand/secondary YouTube identities under the current Google account. */
    suspend fun accountsList(): JsonObject = postMusic("account/accounts_list") {}

    /**
     * The watch queue that YouTube Music would play after [videoId] — the
     * "RDAMVM" radio mix. Used to keep AutoPlay going past the last track.
     */
    suspend fun next(videoId: String): JsonObject = postMusic("next", allowAnonymousFallback = true) {
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("isAudioOnly", true)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
    }

    /** Timed caption transcript used as a last-resort lyrics source. */
    suspend fun transcript(videoId: String): JsonObject = postMusic("get_transcript", allowAnonymousFallback = true) {
        val id = videoId.toByteArray()
        val bytes = byteArrayOf(10, id.size.toByte()) + id
        put("params", Base64.getEncoder().encodeToString(bytes))
    }

    /**
     * Small watch-queue view of a playlist.
     *
     * The playlist detail browse response can carry ~100 rows plus header,
     * menus, suggestions and continuations. On a weak mobile connection that
     * payload is noticeably slower than the watch queue. This endpoint is used
     * only to paint the first visible media rows while the canonical browse
     * response continues in parallel; browse remains the source of truth for
     * the full playlist and continuation token.
     */
    suspend fun playlistQueue(playlistId: String): JsonObject =
        postMusic("next", allowAnonymousFallback = true, requestTimeoutMs = 6_000L) {
            put("playlistId", playlistId.removePrefix("VL"))
            put("isAudioOnly", true)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

    suspend fun search(query: String, params: String? = null): JsonObject =
        postMusic("search", allowAnonymousFallback = true) {
            put("query", query)
            params?.let { put("params", it) }
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

    /**
     * The typeahead list YouTube Music's own search box shows for a
     * half-typed query — query strings, not results.
     *
     * A different endpoint from [search] rather than a cheap mode of it, and
     * far cheaper than one: the response is a few hundred bytes of text with
     * no shelves, thumbnails or playback endpoints in it, which is what makes
     * it affordable per keystroke where a search is not.
     */
    suspend fun searchSuggestions(input: String): JsonObject =
        postMusic("music/get_search_suggestions", allowAnonymousFallback = true) {
            put("input", input)
        }

    /**
     * The `player` response for [videoId] as seen by [client] — the audio
     * formats and whatever it takes to unlock them.
     *
     * The single cheapest thing this app does to start a track: one POST,
     * answered in a few hundred milliseconds, against an endpoint that carries
     * no HTML and is not rate-shaped the way the watch page is.
     *
     * [signatureTimestamp] is required by the clients whose formats come back
     * ciphered ([PlayerClient.needsSignatureTimestamp]) and ignored by the
     * rest; it is read out of YouTube's own player JavaScript.
     *
     * @throws UnplayableException when the track is refused rather than
     *   missing — a region block, a takedown, or the client being turned away.
     *   Callers walk on to the next client on the strength of that distinction.
     */
    suspend fun player(
        videoId: String,
        client: PlayerClient,
        signatureTimestamp: Int? = null,
        authenticated: Boolean = false,
        requestTimeoutMs: Long = PLAYER_TIMEOUT_MS,
    ): JsonObject {
        val response = postPlayer(
            videoId = videoId,
            playerClient = client,
            signatureTimestamp = signatureTimestamp,
            authenticated = authenticated,
            requestTimeoutMs = requestTimeoutMs,
        )

        val status = response["playabilityStatus"]?.jsonObject
            ?.get("status")?.jsonPrimitive?.content
        if (status != null && status != "OK") {
            val reason = response["playabilityStatus"]?.jsonObject
                ?.get("reason")?.jsonPrimitive?.content
            throw UnplayableException(reason ?: status)
        }
        return response
    }

    class UnplayableException(private val reason: String) :
        IllegalStateException("Track unavailable: $reason") {

        /**
         * Whether this is Google doubting the client rather than the track
         * being unavailable. Worth a fresh visitor id and another go; a real
         * region block or takedown is not.
         */
        val looksLikeBotCheck: Boolean
            get() = reason.contains("bot", ignoreCase = true) ||
                    reason.contains("unusual traffic", ignoreCase = true) ||
                    reason.contains("sign in", ignoreCase = true)
    }

    /** The stats endpoints a player response nominates for one playback. */
    data class PlaybackTracking(val playbackUrl: String, val watchtimeUrl: String?)

    /**
     * Player response fetched *with* the session cookie, purely to read back
     * `playbackTracking` — [player] deliberately skips auth so its device
     * clients are answered at all, so it never sees this block. Null for
     * guests: there's no account history to update.
     */
    suspend fun playbackTracking(videoId: String): PlaybackTracking? {
        if (!hasAccountSession) return null
        val response = postMusic("player") {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            // Real clients always describe where playback is happening; the
            // response's tracking block is scoped to it.
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("referer", "$MUSIC_ORIGIN/watch?v=$videoId")
                }
            }
        }
        val tracking = response["playbackTracking"]?.jsonObject
        if (tracking == null) {
            val playability = response["playabilityStatus"]?.jsonObject
            Log.w(
                TAG,
                "player response has no playbackTracking for $videoId " +
                        "(status=${playability?.get("status")?.jsonPrimitive?.content}, " +
                        "reason=${playability?.get("reason")?.jsonPrimitive?.content})",
            )
            return null
        }
        val playbackUrl = tracking.trackingUrl("videostatsPlaybackUrl") ?: return null
        return PlaybackTracking(playbackUrl, tracking.trackingUrl("videostatsWatchtimeUrl"))
    }

    private fun JsonObject.trackingUrl(key: String): String? =
        this[key]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content

    /**
     * The "playback started" ping real YouTube Music clients send once a track
     * becomes audible. This is what creates the history entry the home feed
     * feeds off. [cpn] is the client-playback-nonce identifying this one play:
     * it must be the same value used for every [pingWatchtime] that follows.
     */
    suspend fun pingPlayback(baseUrl: String, cpn: String) =
        pingStats(baseUrl, cpn) { parameter("el", "detailpage") }

    /**
     * The follow-up ping reporting how much of the track was actually heard.
     * A history entry with no watchtime behind it reads as a skip, so it
     * carries little weight in recommendations — [seconds] is what makes the
     * play count. `st`/`et` are the watched segment's bounds, in seconds.
     */
    suspend fun pingWatchtime(baseUrl: String, cpn: String, seconds: Long) =
        pingStats(baseUrl, cpn) {
            parameter("st", "0")
            parameter("et", seconds.toString())
            parameter("state", "playing")
        }

    /** Shared shape of the s.youtube.com stats pings, including account auth. */
    private suspend fun pingStats(
        baseUrl: String,
        cpn: String,
        extras: HttpRequestBuilder.() -> Unit,
    ): Int {
        val oauthToken = if (accountSignedIn) oauthAccessTokenProvider?.invoke() else null

        suspend fun send(token: String?, legacyCookie: String?): HttpResponse =
            client.get(baseUrl) {
                // Inspect auth failures ourselves so OAuth can fall back to the
                // verified browser session before Ktor turns 4xx into an exception.
                expectSuccess = false
                parameter("ver", "2")
                parameter("c", "WEB_REMIX")
                parameter("cver", WEB_REMIX_VERSION)
                parameter("cpn", cpn)
                extras()
                header("X-Origin", MUSIC_ORIGIN)
                header("Origin", MUSIC_ORIGIN)
                header("Referer", "$MUSIC_ORIGIN/")
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                applyAccountHeaders(token, legacyCookie)
            }

        var response = send(oauthToken, if (oauthToken == null) cookie else null)
        if (oauthToken != null && response.status.value in AUTH_REJECTION_CODES) {
            oauthRejectedHandler?.invoke(oauthToken)
            cookie?.let { response = send(null, it) }
        }
        if (response.status.value in LEGACY_AUTH_REJECTION_CODES && cookie != null) {
            legacySessionRejectedHandler?.invoke()
        }
        return response.status.value
    }

    // ---- Writes -------------------------------------------------------------
    //
    // Everything below changes something on the account, so all of it needs
    // the account credential [postMusic] already applies. None of it needs a
    // new credential or a different client — the same WEB_REMIX identity that
    // reads the library is the one allowed to edit it.

    /** A write attempted without a session; the caller has a sign-in prompt to show. */
    class NotSignedInException : IllegalStateException("Continue with Google to do that")

    private fun requireSession() {
        if (!hasAccountSession) throw NotSignedInException()
    }

    /**
     * Thumbs up / down / neither, for [videoId].
     *
     * The response is inspected rather than discarded. Innertube answers a
     * refused write with HTTP 200 and an `error` object in the body, so the
     * status line alone will happily report a rating that never happened.
     */
    suspend fun rate(videoId: String, status: LikeStatus) {
        requireSession()
        val endpoint = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }
        val response = postMusic(endpoint) {
            putJsonObject("target") { put("videoId", videoId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the rating: ${message ?: error}")
        }
        // YouTube states what it did in the toast it would have shown. Worth
        // keeping: a rating it declines to act on still answers 200, and this
        // one line is the difference between "the call was made" and "the
        // call did something".
        Log.d(TAG, "$endpoint $videoId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Saves an album or playlist to the library, or takes it back out.
     *
     * The same endpoints [rate] uses, aimed at a playlist instead of a video:
     * YouTube has no separate "save" verb for a release — a saved album *is* a
     * liked one, which is why the Library tab's Albums and Playlists shelves and
     * the account's likes are the same list. [playlistId] is the id the page
     * itself named, not its browse id; see
     * [com.music.orb.data.model.LibraryState].
     *
     * No dislike half, unlike [rate]: nothing in YouTube Music reads a disliked
     * release, so the only two states worth expressing are saved and not.
     */
    suspend fun ratePlaylist(playlistId: String, saved: Boolean) {
        requireSession()
        val endpoint = if (saved) "like/like" else "like/removelike"
        val response = postMusic(endpoint) {
            putJsonObject("target") { put("playlistId", playlistId) }
        }
        // As in [rate]: a refusal arrives as HTTP 200 with an error in the body.
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the change: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $playlistId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Adds or removes a track from the library, using a token minted by
     * YouTube for exactly that transition — see [com.music.orb.data.model.SongMenu].
     * There is no video-id form of this call; the token *is* the request.
     */
    suspend fun sendFeedback(token: String) {
        requireSession()
        postMusic("feedback") {
            putJsonArray("feedbackTokens") { add(token) }
        }
    }

    /**
     * Creates a playlist and returns its id.
     *
     * [videoIds] seeds it in the same request, which is what "add to a new
     * playlist" is: one round trip rather than a create followed by an edit
     * that could half-succeed.
     */
    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        description: String? = null,
        videoIds: List<String> = emptyList(),
    ): String {
        requireSession()
        val response = postMusic("playlist/create") {
            put("title", title)
            put("description", description.orEmpty())
            put("privacyStatus", privacy.apiValue)
            if (videoIds.isNotEmpty()) {
                putJsonArray("videoIds") { videoIds.forEach { add(it) } }
            }
        }
        // Normally a bare top-level id; occasionally only inside the command
        // that would navigate the web client to the new page, so fall back to
        // finding it by name rather than by a path that would rot.
        return response["playlistId"]?.jsonPrimitive?.contentOrNull
            ?: findString(response, "playlistId")
            ?: error("playlist created but no id came back")
    }

    suspend fun deletePlaylist(playlistId: String) {
        requireSession()
        postMusic("playlist/delete") { put("playlistId", playlistId.removePrefix("VL")) }
    }

    /**
     * One or more edits to a playlist, applied together.
     *
     * The endpoint answers `STATUS_SUCCEEDED` rather than an HTTP error when
     * it refuses — a playlist the account merely saved rather than owns is
     * the usual reason — so the body is checked as well as the status line.
     */
    private suspend fun editPlaylist(
        playlistId: String,
        actions: JsonArrayBuilder.() -> Unit,
    ) {
        requireSession()
        val response = postMusic("browse/edit_playlist") {
            // The edit endpoint takes the raw id; `VL` is the browse prefix.
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions", actions)
        }
        val status = response["status"]?.jsonPrimitive?.contentOrNull
        if (status != null && status != "STATUS_SUCCEEDED") {
            error("YouTube Music refused the edit ($status)")
        }
    }

    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>) =
        editPlaylist(playlistId) {
            videoIds.forEach { videoId ->
                addJsonObject {
                    put("action", "ACTION_ADD_VIDEO")
                    put("addedVideoId", videoId)
                }
            }
        }

    /**
     * Removes entries from a playlist. Keyed by set-video-id as well as video
     * id: the same track added twice is two entries, and only the pair says
     * which of them to drop.
     */
    suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>) =
        editPlaylist(playlistId) {
            entries.forEach { (setVideoId, videoId) ->
                addJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("setVideoId", setVideoId)
                    put("removedVideoId", videoId)
                }
            }
        }

    suspend fun renamePlaylist(playlistId: String, title: String) =
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_SET_PLAYLIST_NAME")
                put("playlistName", title)
            }
        }

    /** A fresh client-playback-nonce, identifying one play of one track. */
    fun newCpn(): String = (1..16).map { CPN_ALPHABET.random() }.joinToString("")

    private const val CPN_ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    // ---- Request plumbing ---------------------------------------------------

    /**
     * Request plumbing copied from Orb v1.5.1 for detail-page opening. Keep this isolated
     * from the modern account/OAuth path so newer account features can coexist without
     * changing the page-open request sequence.
     */
    private suspend fun postMusicV151(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val v151WebRemixVersion = "1.20250101.01.00"
        val response = withRetry {
            client.post("$MUSIC_BASE/$endpoint") {
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                query.forEach { (key, value) -> parameter(key, value) }
                header("X-Origin", MUSIC_ORIGIN)
                header("Origin", MUSIC_ORIGIN)
                header("Referer", "$MUSIC_ORIGIN/")
                header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                header("X-YouTube-Client-Version", v151WebRemixVersion)
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                cookie?.let { c ->
                    header("Cookie", c)
                    header("X-Goog-AuthUser", "0")
                    sapisidFrom(c)?.let { header("Authorization", sapisidHash(it)) }
                }
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", v151WebRemixVersion)
                                put("hl", "en")
                                put("gl", "US")
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") { put("lockedSafetyMode", false) }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }.body<JsonObject>()
        }
        if (visitorData == null) {
            visitorData = response["responseContext"]?.jsonObject
                ?.get("visitorData")?.jsonPrimitive?.content
        }
        return response
    }

    private suspend fun postMusic(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        regionOverride: String? = null,
        allowAnonymousFallback: Boolean = false,
        requestTimeoutMs: Long? = null,
        forceAnonymous: Boolean = false,
        preferLegacyCookie: Boolean = false,
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        // Foreground catalogue reads do not need account state. Most importantly,
        // do not call the OAuth provider at all on that path: token refresh can
        // involve Google Play services/network work and must never gate the first
        // frame of an artist/album/playlist page.
        val oauthToken =
            if (
                !forceAnonymous &&
                !(preferLegacyCookie && cookie != null) &&
                accountSignedIn
            ) {
                oauthAccessTokenProvider?.invoke()
            } else {
                null
            }

        suspend fun send(token: String?, legacyCookie: String?): HttpResponse = withRetry {
            client.post("$MUSIC_BASE/$endpoint") {
                // Foreground detail navigation gets a shorter per-request
                // ceiling so a dead cellular socket can be replaced quickly.
                requestTimeoutMs?.let { timeout { requestTimeoutMillis = it } }
                // OAuth rejection is part of the compatibility decision below.
                expectSuccess = false
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                query.forEach { (key, value) -> parameter(key, value) }
                header("X-Origin", MUSIC_ORIGIN)
                header("Origin", MUSIC_ORIGIN)
                header("Referer", "$MUSIC_ORIGIN/")
                header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                header("X-YouTube-Client-Version", WEB_REMIX_VERSION)
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                applyAccountHeaders(token, legacyCookie)
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", WEB_REMIX_VERSION)
                                put("hl", "en")
                                put(
                                    "gl",
                                    regionOverride
                                        ?.takeIf { it.length == 2 && !it.equals("ZZ", ignoreCase = true) }
                                        ?: "US",
                                )
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                if (!forceAnonymous) {
                                    delegatedPageId?.takeIf { it.isNotBlank() }?.let {
                                        put("onBehalfOfUser", it)
                                    }
                                }
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }
        }

        var response = if (forceAnonymous) {
            send(null, null)
        } else {
            send(oauthToken, if (oauthToken == null) cookie else null)
        }

        if (!forceAnonymous && oauthToken != null && response.status.value in AUTH_REJECTION_CODES) {
            Log.w(TAG, "OAuth rejected by $endpoint (${response.status.value}); trying compatibility auth")
            oauthRejectedHandler?.invoke(oauthToken)
            response = when {
                cookie != null -> send(null, cookie)
                allowAnonymousFallback -> send(null, null)
                else -> response
            }
        }

        if (!forceAnonymous && response.status.value in LEGACY_AUTH_REJECTION_CODES && cookie != null) {
            legacySessionRejectedHandler?.invoke()
            // Cookie-first browse matches the fast v1.5.1 path. Only when that
            // legacy session is actually rejected do we pay for an OAuth token
            // lookup; public catalogue browse may still fall back anonymously.
            val fallbackOAuth = if (preferLegacyCookie && accountSignedIn) {
                oauthAccessTokenProvider?.invoke()
            } else {
                null
            }
            response = when {
                fallbackOAuth != null -> send(fallbackOAuth, null)
                allowAnonymousFallback -> send(null, null)
                else -> response
            }
        }
        if (response.status.value !in 200..299) {
            val details = runCatching { response.bodyAsText().take(320) }.getOrDefault("")
            error(
                "YouTube Music $endpoint failed with HTTP ${response.status.value}" +
                    details.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            )
        }

        val body = response.body<JsonObject>()
        if (visitorData == null) {
            visitorData = body["responseContext"]?.jsonObject
                ?.get("visitorData")?.jsonPrimitive?.content
        }
        return body
    }

    /**
     * Unauthenticated by default.
     *
     * The app clients [StreamResolver] walks through are answered *because*
     * they look like anonymous devices; attaching the session cookie to one
     * of those is what gets it turned away with `LOGIN_REQUIRED`. Nothing
     * about the account is needed to fetch audio through them — history is
     * credited separately, by [playbackTracking] and the stats pings, which
     * do carry the session.
     *
     * [authenticated] is the one deliberate exception: [PlayerClient.WEB_REMIX]
     * is an account-facing browser identity, so it receives the same Google
     * bearer used by account browse calls, falling back to the verified browser
     * cookie when that private endpoint refuses OAuth. A signed-out caller still
     * gets the same unauthenticated request as every device client.
     */
    private suspend fun postPlayer(
        videoId: String,
        playerClient: PlayerClient,
        signatureTimestamp: Int?,
        authenticated: Boolean = false,
        requestTimeoutMs: Long = PLAYER_TIMEOUT_MS,
    ): JsonObject {
        val oauthToken = if (authenticated && accountSignedIn) {
            oauthAccessTokenProvider?.invoke()
        } else null

        suspend fun send(token: String?, legacyCookie: String?): HttpResponse =
            client.post("${playerClient.apiBase()}/player") {
                // WEB_REMIX may reject a valid Google bearer; observe the status
                // and retry with the legacy account session when available.
                expectSuccess = false
                timeout { requestTimeoutMillis = requestTimeoutMs }
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                header("User-Agent", playerClient.userAgent)
                header("X-YouTube-Client-Name", playerClient.clientId)
                header("X-YouTube-Client-Version", playerClient.clientVersion)
                playerClient.origin?.let { header("Origin", it) }
                playerClient.referer?.let { header("Referer", it) }
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                if (authenticated) applyAccountHeaders(token, legacyCookie)
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", playerClient.clientName)
                                put("clientVersion", playerClient.clientVersion)
                                playerClient.osName?.let { put("osName", it) }
                                playerClient.osVersion?.let { put("osVersion", it) }
                                playerClient.deviceMake?.let { put("deviceMake", it) }
                                playerClient.deviceModel?.let { put("deviceModel", it) }
                                playerClient.androidSdkVersion?.let { put("androidSdkVersion", it.toInt()) }
                                put("hl", "en")
                                put("gl", "US")
                                visitorData?.let { put("visitorData", it) }
                            }
                            if (authenticated && !delegatedPageId.isNullOrBlank()) {
                                putJsonObject("user") {
                                    put("onBehalfOfUser", requireNotNull(delegatedPageId))
                                }
                            }
                        }
                        if (playerClient.needsSignatureTimestamp && signatureTimestamp != null) {
                            putJsonObject("playbackContext") {
                                putJsonObject("contentPlaybackContext") {
                                    put("signatureTimestamp", signatureTimestamp)
                                }
                            }
                        }
                        put("videoId", videoId)
                        put("contentCheckOk", true)
                        put("racyCheckOk", true)
                    },
                )
            }

        var response = send(
            oauthToken,
            if (authenticated && oauthToken == null) cookie else null,
        )
        if (
            authenticated &&
            oauthToken != null &&
            response.status.value in AUTH_REJECTION_CODES
        ) {
            oauthRejectedHandler?.invoke(oauthToken)
            cookie?.let { response = send(null, it) }
        }
        if (response.status.value in LEGACY_AUTH_REJECTION_CODES && cookie != null) {
            legacySessionRejectedHandler?.invoke()
        }
        if (response.status.value !in 200..299) {
            val details = runCatching { response.bodyAsText().take(320) }.getOrDefault("")
            error(
                "YouTube player request failed with HTTP ${response.status.value}" +
                    details.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            )
        }
        return response.body()
    }

    /** Browser-shaped clients are served from the Music host; app clients from YouTube proper. */
    private fun PlayerClient.apiBase(): String = if (usesMusicHost) MUSIC_BASE else YT_BASE

    /** First string value under [key] anywhere in [element], depth-first. */
    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> (element[key] as? JsonPrimitive)?.contentOrNull
            ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    private fun HttpRequestBuilder.applyAccountHeaders(
        oauthToken: String?,
        legacyCookie: String?,
    ) {
        when {
            !oauthToken.isNullOrBlank() -> {
                // Match the current YouTube Music OAuth shape: the bearer
                // already identifies the Google account. Browser-session
                // routing headers belong to SAPISID/cookie auth and can make
                // otherwise valid OAuth requests look contradictory. Brand
                // routing, when selected, stays in context.user.onBehalfOfUser.
                header("Authorization", "Bearer $oauthToken")
                header("X-Goog-Request-Time", (System.currentTimeMillis() / 1000L).toString())
            }
            !legacyCookie.isNullOrBlank() -> {
                header("Cookie", legacyCookie)
                header("X-Goog-AuthUser", authUserIndex.coerceAtLeast(0).toString())
                delegatedPageId?.takeIf { it.isNotBlank() }?.let { header("X-Goog-PageId", it) }
                sapisidFrom(legacyCookie)?.let { header("Authorization", sapisidHash(it)) }
            }
        }
    }

    private val AUTH_REJECTION_CODES = setOf(400, 401, 403)
    private val LEGACY_AUTH_REJECTION_CODES = setOf(401, 403)

    private fun sapisidFrom(cookieHeader: String): String? =
        cookieHeader.split("; ", ";")
            .firstOrNull { it.trim().startsWith("SAPISID=") }
            ?.substringAfter("=")

    private fun sapisidHash(sapisid: String, origin: String = MUSIC_ORIGIN): String {
        val timestamp = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$digest"
    }
}
