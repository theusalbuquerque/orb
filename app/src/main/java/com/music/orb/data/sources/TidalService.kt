package com.music.orb.data.sources

import android.util.Base64
import com.music.orb.data.TrackLog
import com.music.orb.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * TIDAL helper backed by hifi-api compatible instances.
 *
 * Endpoint priority:
 * 1. Orb's private Cloudflare Worker
 * 2. Public hifi-api compatible instances
 * 3. Local PC hifi-api
 * 4. null, allowing SourceResolver to fall back to YouTube/Opus
 *
 * Playback quality priority inside each endpoint:
 * 1. FLAC_HIRES
 * 2. FLAC
 * 3. legacy HI_RES_LOSSLESS / LOSSLESS compatibility paths
 *
 * No TIDAL credential is stored in the Android app. Authentication remains
 * server-side in the Worker / hifi-api instance.
 */
object TidalService {

    private const val TAG = "TidalService"
    private const val USER_AGENT = "Mozilla/5.0 (compatible; OrbPlay/1.0)"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val SEARCH_CACHE_TTL_MS = 30 * 60_000L
    private const val PLAYBACK_QUEUE_TIMEOUT_MS = 30_000L

    private const val PRIMARY_INSTANCE =
        "https://hifi-api-workers.orbmusic.workers.dev"

    const val LOCAL_INSTANCE = "http://192.168.3.90:8000"

    val bundledInstances: List<String> = listOf(
        PRIMARY_INSTANCE,
        "https://api.monochrome.tf",
        "https://monochrome-api.samidy.com",
        "https://hifi.geeked.wtf",
        LOCAL_INSTANCE,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    data class Track(
        val id: Long,
        val title: String,
        /** TIDAL keeps remix/edit descriptors in a separate field from title. */
        val version: String? = null,
        val durationSec: Int?,
        val artists: List<String>,
        val album: String?,
        val albumId: Long? = null,
        /** Real release year, from releaseDate — never streamStartDate. */
        val releaseYear: Int? = null,
        val audioQuality: String?,
        /** Quality tags exposed by newer hifi-api search payloads. */
        val mediaTags: Set<String> = emptySet(),
        /** TIDAL's explicit flag when the hifi-api instance exposes it. */
        val isExplicit: Boolean? = null,
    ) {
        val losslessTier: LosslessTier
            get() = TidalService.losslessTierOf(audioQuality, mediaTags)
    }

    data class AlbumMetadata(
        val title: String?,
        val releaseYear: Int?,
    )

    private val albumMetadataCache = ConcurrentHashMap<Long, AlbumMetadata>()

    private data class SearchCacheEntry(
        val tracks: List<Track>,
        val cachedAtMs: Long,
    )

    /** Short-lived raw search cache; verified quality knowledge is persisted separately. */
    private val searchCache = ConcurrentHashMap<String, SearchCacheEntry>()

    /** Per-host circuit breaker so one bad public mirror cannot be hammered by every badge. */
    private val hostCooldownUntil = ConcurrentHashMap<String, Long>()
    private val hostFailures = ConcurrentHashMap<String, Int>()

    /** hifi-api permits one in-flight playback request per playback credential. */
    private val playbackGate = Semaphore(1)

    data class Stream(
        val url: String,
        val codec: String = "flac",
        val sampleRateHz: Int? = null,
        val bitDepth: Int? = null,
        val audioQuality: String? = null,
        val isDash: Boolean = false,
        /** Native lossless evidence checked by Orb, not just a quality claim. */
        val verifiedLossless: Boolean = false,
    )

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        // Prefer the private Orb Worker. If it is temporarily unavailable,
        // public compatible instances and the local PC remain valid fallbacks.
        for (base in orderedInstances()) {
            if (requestText("$base/") != null) {
                TrackLog.d(TAG, "health ok via $base")
                return@withContext true
            }

            if (requestText("$base/search/?s=test&limit=1") != null) {
                TrackLog.d(TAG, "health ok via search fallback on $base")
                return@withContext true
            }
        }

        false
    }

    suspend fun search(query: String, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        if (!AppSettings.losslessAudio.value || query.isBlank()) return@withContext emptyList()

        val cacheKey = query.trim().lowercase().replace(Regex("\\s+"), " ")
        val now = System.currentTimeMillis()
        searchCache[cacheKey]?.takeIf { now - it.cachedAtMs <= SEARCH_CACHE_TTL_MS }?.let { cached ->
            return@withContext cached.tracks.take(limit)
        }

        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())

        for (base in orderedInstances()) {
            val body = requestText("$base/search/?s=$encoded") ?: continue
            val tracks = parseSearch(body)

            if (tracks.isNotEmpty()) {
                searchCache[cacheKey] = SearchCacheEntry(tracks, now)
                TrackLog.d(TAG, "search '$query' -> ${tracks.size.coerceAtMost(limit)} hits via $base")
                return@withContext tracks.take(limit)
            }
        }

        emptyList()
    }

    /**
     * Canonical album metadata for identity verification.
     *
     * Search responses from older hifi-api builds do not always include the
     * album releaseDate. This slower album lookup is therefore used only by
     * Orb's background/upgrade matcher, never to hold up the first note.
     */
    suspend fun albumMetadata(albumId: Long): AlbumMetadata? = withContext(Dispatchers.IO) {
        if (!AppSettings.losslessAudio.value) return@withContext null
        albumMetadataCache[albumId]?.let { return@withContext it }
        for (base in orderedInstances()) {
            val body = requestText("$base/album/?id=$albumId&limit=1") ?: continue
            val metadata = parseAlbumMetadata(body) ?: continue
            albumMetadataCache[albumId] = metadata
            return@withContext metadata
        }
        null
    }

    /**
     * Tries the highest real lossless quality first.
     *
     * TIDAL can downgrade a HI_RES_LOSSLESS request to HIGH/AAC. Such a
     * response is rejected here instead of being mislabeled as lossless.
     */
    suspend fun bestLosslessStream(trackId: Long): Stream? = withContext(Dispatchers.IO) {
        if (!AppSettings.losslessAudio.value) return@withContext null
        playbackGate.withPermit {
            // Ask for both tiers in one playback request. Besides saving a network
            // round trip, this is required by current hifi-api instances, which
            // serialize playback work per credential and otherwise return 202.
            trackManifestStream(trackId)?.let { stream ->
                TrackLog.d(
                    TAG,
                    "best stream $trackId -> ${stream.audioQuality} " +
                            "${stream.bitDepth ?: "?"}-bit/${stream.sampleRateHz ?: "?"}Hz",
                )
                return@withPermit stream
            }

            // Compatibility fallback for older/local hifi-api builds.
            streamExact(trackId, "HI_RES_LOSSLESS")?.let { return@withPermit it }
            streamExact(trackId, "LOSSLESS")?.let { return@withPermit it }

            TrackLog.d(TAG, "no qualifying TIDAL lossless stream for $trackId")
            null
        }
    }

    /**
     * Resolves a TIDAL stream through hifi-api 2.10's /trackManifests endpoint.
     *
     * uriScheme=HTTPS is important: the API returns a signed .mpd URL that
     * Media3 can open directly. This avoids inventing a local /dash route and
     * avoids feeding the inline base64 MPD from the legacy /track endpoint to
     * an HTTP-only data source.
     */
    private suspend fun trackManifestStream(trackId: Long): Stream? {
        for (base in orderedInstances()) {
            val body = requestText(
                "$base/trackManifests/?" +
                        "id=$trackId" +
                        "&formats=FLAC_HIRES" +
                        "&formats=FLAC" +
                        "&adaptive=true" +
                        "&manifestType=MPEG_DASH" +
                        "&uriScheme=HTTPS" +
                        "&usage=PLAYBACK",
            ) ?: continue

            val manifestUrl = parseTrackManifestUri(body) ?: continue

            // Read the MPD once for metadata/validation. Media3 will open the
            // same signed URL for playback afterwards.
            val mpd = requestText(
                manifestUrl,
                accept = "application/dash+xml, application/xml, text/xml, */*",
            ) ?: continue
            if (!mpd.contains("<MPD", ignoreCase = true)) {
                TrackLog.d(TAG, "trackManifests returned a non-MPD document for $trackId")
                continue
            }

            // The combined manifest can contain several FLAC representations.
            // Read every one and retain the native maximum instead of trusting
            // the first XML attribute, which is commonly the 16/44.1 variant.
            val representations = Regex(
                """id\s*=\s*["']FLAC,(\d+),(\d+)["']""",
                RegexOption.IGNORE_CASE,
            ).findAll(mpd).mapNotNull { match ->
                val rate = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                val depth = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
                rate to depth
            }.toList()
            val best = representations.maxWithOrNull(
                compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first },
            )
            val resolvedRate = best?.first ?: Regex(
                """audioSamplingRate\s*=\s*["'](\d+)["']""",
                RegexOption.IGNORE_CASE,
            ).findAll(mpd).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.maxOrNull()
            val declaredBitDepth = best?.second ?: Regex(
                """(?:bitDepth|bitsPerSample)\s*=\s*["'](\d+)["']""",
                RegexOption.IGNORE_CASE,
            ).findAll(mpd).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.maxOrNull()
            val hiResLabel = Regex(
                """(?:<Label[^>]*>\s*)?(?:FLAC_HIRES|HI[_-]?RES(?:_LOSSLESS)?)(?:\s*</Label>)?""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(mpd)
            // This is stronger than the catalogue saying "LOSSLESS": Orb read
            // the actual playback MPD and found a FLAC representation in it.
            // Keep that evidence so the UI can still classify the track during
            // the rare window where Media3 has not published sampleMimeType.
            val manifestSaysFlac = representations.isNotEmpty() ||
                mpd.contains("FLAC", ignoreCase = true)

            return Stream(
                url = manifestUrl,
                codec = "flac",
                sampleRateHz = resolvedRate,
                bitDepth = declaredBitDepth,
                audioQuality = if (
                    hiResLabel || (declaredBitDepth ?: 0) > 16 || (resolvedRate ?: 0) > 48_000
                ) {
                    "HI_RES_LOSSLESS"
                } else {
                    "LOSSLESS"
                },
                isDash = true,
                verifiedLossless = manifestSaysFlac,
            )
        }

        return null
    }

    private fun parseTrackManifestUri(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject

        val outerData = root["data"] as? JsonObject
        val innerData = outerData?.get("data") as? JsonObject
        val attributes = innerData?.get("attributes") as? JsonObject

        sequenceOf(
            attributes?.string("uri"),
            innerData?.string("uri"),
            outerData?.string("uri"),
            root.string("uri"),
            root.string("manifestUrl"),
        )
            .filterNotNull()
            .firstOrNull()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }.getOrElse {
        TrackLog.d(TAG, "trackManifests parse failed: ${it.message}")
        null
    }

    /**
     * Specific-quality entry point kept for any existing callers.
     */
    suspend fun stream(trackId: Long, quality: String = "HI_RES_LOSSLESS"): Stream? =
        withContext(Dispatchers.IO) {
            if (!AppSettings.losslessAudio.value) null else playbackGate.withPermit {
                streamExact(trackId, quality)
            }
        }

    private suspend fun streamExact(trackId: Long, quality: String): Stream? {
        for (base in orderedInstances()) {
            val encodedQuality = URLEncoder.encode(quality, Charsets.UTF_8.name())
            val body = requestText(
                "$base/track/?id=$trackId&quality=$encodedQuality",
            ) ?: continue

            val stream = parseStream(
                body = body,
                trackId = trackId,
                requestedQuality = quality,
            )

            if (stream != null) {
                val verified = if (!stream.isDash && stream.codec.equals("flac", ignoreCase = true)) {
                    probeFlacHeader(stream.url)
                } else {
                    stream.verifiedLossless
                }
                TrackLog.d(
                    TAG,
                    "stream $trackId requested=$quality returned=${stream.audioQuality} " +
                            "dash=${stream.isDash} verifiedLossless=$verified via $base",
                )
                return stream.copy(verifiedLossless = verified)
            }
        }

        return null
    }

    /**
     * Byte-level verification for legacy direct FLAC URLs. A low compressed
     * bitrate (160 kbps, for example) is irrelevant: if the file header is
     * FLAC, it is lossless. Read only the first 64 bytes and never reject the
     * stream merely because a host refuses Range; the decoder remains the final
     * authority in that case.
     */
    private fun probeFlacHeader(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        return runCatching {
            val connection = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-63")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) return@runCatching false
                val bytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(64)
                    val read = input.read(buffer)
                    if (read <= 0) ByteArray(0) else buffer.copyOf(read)
                }
                bytes.indices.any { i ->
                    i + 3 < bytes.size &&
                        bytes[i] == 'f'.code.toByte() &&
                        bytes[i + 1] == 'L'.code.toByte() &&
                        bytes[i + 2] == 'a'.code.toByte() &&
                        bytes[i + 3] == 'C'.code.toByte()
                }
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun orderedInstances(): List<String> =
        bundledInstances
            .map { it.trimEnd('/') }
            .distinct()

    private data class HttpResult(
        val code: Int,
        val body: String,
        val location: String?,
        val retryAfterMs: Long?,
    )

    private suspend fun requestText(
        url: String,
        accept: String = "application/json",
    ): String? {
        // Master kill-switch: no health check, catalogue search, album lookup or
        // manifest request is allowed to reach TIDAL while Lossless is off.
        if (!AppSettings.losslessAudio.value) return null

        var currentUrl = url
        var statusUrl: String? = null
        var cancelUrl: String? = null
        val deadline = System.currentTimeMillis() + PLAYBACK_QUEUE_TIMEOUT_MS

        while (true) {
            val result = requestRaw(currentUrl, "GET", accept) ?: return null
            if (result.code in 200..299 && result.code != HttpURLConnection.HTTP_ACCEPTED) {
                markHostHealthy(currentUrl)
                return result.body
            }
            if (result.code != HttpURLConnection.HTTP_ACCEPTED) {
                registerHttpFailure(currentUrl, result.code, result.retryAfterMs)
                return null
            }

            // Current hifi-api queues playback work and returns a status URL.
            // 202 is progress, not a broken host and must never trip the mirror
            // circuit breaker.
            val pending = parsePendingRequest(result.body)
            statusUrl = resolveUrl(currentUrl, pending?.first ?: result.location ?: statusUrl)
            cancelUrl = resolveUrl(currentUrl, pending?.second ?: cancelUrl)
            if (statusUrl == null || System.currentTimeMillis() >= deadline) {
                cancelUrl?.let { requestRaw(it, "DELETE", "application/json") }
                TrackLog.d(TAG, "playback queue timed out on ${URL(currentUrl).host}")
                return null
            }
            delay((result.retryAfterMs ?: 750L).coerceIn(250L, 3_000L))
            currentUrl = statusUrl
        }
    }

    private fun requestRaw(url: String, method: String, accept: String): HttpResult? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        val host = parsed.host.lowercase()
        if ((hostCooldownUntil[host] ?: 0L) > System.currentTimeMillis()) return null
        return runCatching {
            val connection = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..399) connection.inputStream else connection.errorStream
                HttpResult(
                    code = code,
                    body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(),
                    location = connection.getHeaderField("Location"),
                    retryAfterMs = retryAfterMs(connection.getHeaderField("Retry-After")),
                )
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            val failures = (hostFailures[host] ?: 0) + 1
            hostFailures[host] = failures
            val cooldown = (10_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(2 * 60_000L)
            hostCooldownUntil[host] = System.currentTimeMillis() + cooldown
            TrackLog.d(TAG, "request failed on $host: ${it.message}; cooling down ${cooldown / 1000}s")
            null
        }
    }

    private fun parsePendingRequest(body: String): Pair<String?, String?>? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        root.string("statusUrl") to root.string("cancelUrl")
    }.getOrNull()

    private fun resolveUrl(base: String, value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching { URL(URL(base), value).toString() }.getOrNull()
    }

    private fun markHostHealthy(url: String) {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return
        hostFailures.remove(host)
        hostCooldownUntil.remove(host)
    }

    private fun registerHttpFailure(url: String, code: Int, serverRetryMs: Long?) {
        val host = runCatching { URL(url).host.lowercase() }.getOrNull() ?: return
        if (code in setOf(400, 404, 405, 422)) {
            // Unsupported route/parameter is not a dead mirror. In particular,
            // older hifi-api builds may reject combined manifests but still
            // support the legacy /track endpoint on the same host.
            TrackLog.d(TAG, "HTTP $code from $host; trying compatible endpoint")
            return
        }
        val failures = (hostFailures[host] ?: 0) + 1
        hostFailures[host] = failures
        val cooldown = when (code) {
            429 -> serverRetryMs ?: 120_000L
            401, 403 -> 15 * 60_000L
            in 500..599 -> (15_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(5 * 60_000L)
            else -> 30_000L
        }
        hostCooldownUntil[host] = System.currentTimeMillis() + cooldown
        TrackLog.d(TAG, "HTTP $code from $host; cooling down ${cooldown / 1000}s")
    }

    private fun retryAfterMs(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        return seconds.coerceIn(1L, 3600L) * 1000L
    }

    private fun parseSearch(body: String): List<Track> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject ?: return@runCatching emptyList()

        val directItems = data["items"] as? JsonArray
        val nestedItems = data["tracks"]
            ?.let { it as? JsonObject }
            ?.get("items") as? JsonArray

        val items =
            directItems?.takeIf { it.isNotEmpty() }
                ?: nestedItems
                ?: JsonArray(emptyList())

        items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.long("id") ?: return@mapNotNull null
            val title = item.string("title").orEmpty()

            if (title.isBlank()) return@mapNotNull null

            val artists = buildList {
                val artistArray = item["artists"] as? JsonArray

                artistArray?.forEach { artistElement ->
                    val name = (artistElement as? JsonObject)?.string("name")
                    if (!name.isNullOrBlank()) add(name)
                }

                if (isEmpty()) {
                    val name = (item["artist"] as? JsonObject)?.string("name")
                    if (!name.isNullOrBlank()) add(name)
                }
            }.distinct()

            val albumObject = item["album"] as? JsonObject
            val mediaTags = ((item["mediaMetadata"] as? JsonObject)?.get("tags") as? JsonArray)
                ?.mapNotNull { tag -> tag.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
                ?.toSet()
                .orEmpty()
            Track(
                id = id,
                title = title,
                version = item.string("version")?.trim()?.takeIf { it.isNotEmpty() },
                durationSec = item.int("duration"),
                artists = artists,
                album = albumObject?.string("title"),
                albumId = albumObject?.long("id"),
                releaseYear = releaseYearOf(
                    albumObject?.string("releaseDate") ?: item.string("releaseDate"),
                ),
                audioQuality = item.string("audioQuality"),
                mediaTags = mediaTags,
                isExplicit = item.boolean("explicit") ?: item.boolean("isExplicit"),
            )
        }
    }.getOrElse {
        TrackLog.d(TAG, "search parse failed: ${it.message}")
        emptyList()
    }

    private fun parseAlbumMetadata(body: String): AlbumMetadata? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"] as? JsonObject ?: return@runCatching null
        AlbumMetadata(
            title = data.string("title"),
            releaseYear = releaseYearOf(data.string("releaseDate")),
        )
    }.getOrElse {
        TrackLog.d(TAG, "album metadata parse failed: ${it.message}")
        null
    }

    /** Only a real releaseDate is accepted as release identity. */
    private fun releaseYearOf(value: String?): Int? = value
        ?.trim()
        ?.take(4)
        ?.toIntOrNull()
        ?.takeIf { it in 1900..2100 }

    private fun parseStream(
        body: String,
        trackId: Long,
        requestedQuality: String,
    ): Stream? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject ?: return@runCatching null

        val returnedQuality = data.string("audioQuality")?.uppercase()
        val requested = requestedQuality.uppercase()

        val accepted = when (requested) {
            "HI_RES_LOSSLESS" -> returnedQuality == "HI_RES_LOSSLESS"
            "LOSSLESS" -> returnedQuality == "LOSSLESS" || returnedQuality == "HI_RES_LOSSLESS"
            else -> returnedQuality == requested
        }

        if (!accepted) {
            TrackLog.d(
                TAG,
                "rejecting TIDAL stream $trackId: requested=$requested " +
                        "but upstream returned=$returnedQuality",
            )
            return@runCatching null
        }

        val manifest = data.string("manifest") ?: return@runCatching null
        val manifestMime = data.string("manifestMimeType")
        val sampleRate = data.int("sampleRate")?.let { if (it < 1000) it * 1000 else it }
        val bitDepth = data.int("bitDepth")

        if (manifestMime?.contains("dash", ignoreCase = true) == true) {
            // Stock hifi-api 2.10 returns this MPD inline as base64 and does
            // not expose the synthetic /dash/<id>.mpd route previously assumed
            // here. /trackManifests (tried before this legacy path) is the
            // supported way to obtain an HTTPS MPD URI.
            TrackLog.d(
                TAG,
                "legacy /track returned inline DASH for $trackId; " +
                        "use /trackManifests instead",
            )
            return@runCatching null
        }

        val decoded = String(
            Base64.decode(manifest, Base64.DEFAULT),
            Charsets.UTF_8,
        )

        if (decoded.trimStart().startsWith("<")) {
            TrackLog.d(TAG, "unexpected inline XML manifest for $trackId")
            return@runCatching null
        }

        val manifestJson = json.parseToJsonElement(decoded).jsonObject
        val mimeType = manifestJson.string("mimeType")

        val url = (manifestJson["urls"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.startsWith("http") }
            ?: return@runCatching null

        val codec = when {
            mimeType?.contains("flac", ignoreCase = true) == true -> "flac"
            else -> url
                .substringBefore('?')
                .substringAfterLast('.', "")
                .lowercase()
                .ifBlank { "unknown" }
        }

        if (codec != "flac") {
            TrackLog.d(
                TAG,
                "rejecting non-FLAC direct stream $trackId: codec=$codec quality=$returnedQuality",
            )
            return@runCatching null
        }

        Stream(
            url = url,
            codec = "flac",
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            audioQuality = returnedQuality,
            isDash = false,
        )
    }.getOrElse {
        TrackLog.d(TAG, "stream parse failed: ${it.message}")
        null
    }


    private fun losslessTierOf(audioQuality: String?, tags: Set<String>): LosslessTier {
        val values = buildList {
            audioQuality?.let { add(it) }
            addAll(tags)
        }.map { value -> value.uppercase().replace('-', '_').replace(' ', '_') }
        return when {
            values.any { value ->
                value.contains("FLAC_HIRES") || value.contains("HI_RES_LOSSLESS") ||
                        value.contains("HIRES_LOSSLESS") || value == "HIRES"
            } -> LosslessTier.HI_RES_LOSSLESS
            values.any { value -> value.contains("LOSSLESS") || value == "FLAC" } ->
                LosslessTier.LOSSLESS
            else -> LosslessTier.NONE
        }
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.contentOrNull?.let { value ->
            when (value.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
        }
}
