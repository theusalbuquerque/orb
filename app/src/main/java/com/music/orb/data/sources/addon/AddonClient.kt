package com.music.orb.data.sources.addon

import com.music.orb.data.Http
import com.music.orb.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** HTTP client for the lightweight addon protocol used by Orb Navidrome Addon. */
class AddonClient(rawBaseUrl: String) {
    val baseUrl: String = normalizeBase(rawBaseUrl)

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manifests = SharedCalls<AddonManifest>(MANIFEST_TTL_MS, scope)
    private val searches = SharedCalls<AddonSearchResponse>(SEARCH_TTL_MS, scope)
    private val streams = SharedCalls<AddonStream>(STREAM_TTL_MS, scope)

    @Volatile private var quietUntilMs: Long = 0L

    suspend fun manifest(): Result<AddonManifest> = manifests.get(baseUrl) {
        fetch<AddonManifest>(manifestUrl(baseUrl)).mapCatching { manifest ->
            if (manifest.id.isBlank()) throw AddonException("That URL did not return an addon manifest")
            if (!manifest.isPlayable) throw AddonException("This addon does not expose a searchable catalogue")
            manifest
        }
    }

    suspend fun search(query: String): Result<List<AddonTrack>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        return searches.get(q) {
            fetch<AddonSearchResponse>(endpoint(listOf("search"), mapOf("q" to q)))
        }.map { it.tracks }
    }

    suspend fun stream(trackId: String): Result<AddonStream> = streams.get(trackId) {
        fetch(endpoint(listOf("stream", trackId)))
    }

    private fun endpoint(segments: List<String>, params: Map<String, String> = emptyMap()): String {
        val base = baseUrl.toHttpUrlOrNull() ?: throw AddonException("That is not a usable addon address")
        val builder = base.newBuilder()
        segments.forEach(builder::addPathSegment)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private suspend inline fun <reified T> fetch(url: String): Result<T> = runCatching {
        json.decodeFromString<T>(body(url))
    }.onFailure { failure ->
        if (failure is CancellationException) throw failure
        if (failure !is AddonNotFound) {
            TrackLog.w(TAG, "addon call failed ${redact(url)}: ${failure.message}")
        }
    }

    private suspend fun body(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            (quietUntilMs - System.currentTimeMillis()).takeIf { it > 0 }?.let { delay(it) }
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()
            var retryMs: Long? = null
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> return@withContext response.body?.string()?.takeIf { it.isNotBlank() }
                        ?: throw AddonException("Empty addon response")
                    response.code == 404 -> throw AddonNotFound()
                    response.code == 429 && attempt < MAX_RATE_LIMIT_RETRIES -> {
                        val retryAfter = response.header("Retry-After")?.trim()?.toDoubleOrNull()
                            ?.times(1000.0)?.toLong()
                        retryMs = (retryAfter ?: (BASE_BACKOFF_MS shl attempt))
                            .coerceIn(BASE_BACKOFF_MS, MAX_BACKOFF_MS)
                        quietUntilMs = maxOf(quietUntilMs, System.currentTimeMillis() + retryMs!!)
                    }
                    response.code == 429 -> throw AddonUnavailable("This addon is temporarily rate limiting Orb")
                    response.code >= 500 -> throw AddonUnavailable("Addon server returned HTTP ${response.code}")
                    else -> throw AddonException("Addon rejected the request (HTTP ${response.code})")
                }
            }
            val wait = retryMs ?: throw AddonException("Addon request failed")
            attempt++
            delay(wait)
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    fun clear() {
        manifests.clear()
        searches.clear()
        streams.clear()
        quietUntilMs = 0L
    }

    fun close() {
        clear()
        scope.cancel()
    }

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    }

    companion object {
        private const val TAG = "OrbAddon"
        private const val USER_AGENT = "Orb-Navidrome/1.0"
        private const val MAX_RATE_LIMIT_RETRIES = 2
        private const val BASE_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 8_000L
        private const val MANIFEST_TTL_MS = 10 * 60 * 1000L
        private const val SEARCH_TTL_MS = 10 * 60 * 1000L
        private const val STREAM_TTL_MS = 5 * 60 * 1000L

        fun normalizeBase(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.endsWith("/manifest.json", ignoreCase = true)) {
                trimmed.dropLast("/manifest.json".length).trimEnd('/')
            } else trimmed
        }

        fun manifestUrl(base: String): String = "${normalizeBase(base)}/manifest.json"

        fun redact(url: String): String =
            url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/***" } ?: "***"

    }
}
