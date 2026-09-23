package com.music.orb.data.lyrics

import com.music.orb.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Public Apple lyrics plus optional authenticated Spotify and Musixmatch routes. */
object PaxSenix {
    private const val API = "https://api.paxsenix.org"
    private const val PUBLIC_PROXY = "https://lyrics.paxsenix.org"
    private const val APPLE_SEARCH = "https://amp-api.music.apple.com/v1/catalog/us/search"
    private val tokenMutex = Mutex()
    private val cachedAppleToken = AtomicReference<String?>(null)

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val id = searchPublicAppleTrackId(title, artist, durationMs) ?: return@withContext null
        val url = "$PUBLIC_PROXY/apple-music/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("id", id)
            .addQueryParameter("ttml", "true")
            .build()
        lyricsGet(url.toString())?.let(ProviderLyrics::parse)
    }

    suspend fun spotifyLyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val key = AppSettings.paxSenixApiKey.value.takeIf { it.isNotBlank() } ?: return@withContext null
            val search = "$API/spotify/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", "$title $artist").build()
            val id = lyricsGetBearer(search.toString(), key)?.let { raw -> bestCandidateId(raw, title, artist, durationMs) }
            val direct = id?.let {
                val url = "$API/lyrics/spotify".toHttpUrl().newBuilder().addQueryParameter("id", it).build()
                lyricsGetBearer(url.toString(), key)?.let(ProviderLyrics::parse)
            }
            direct ?: genericAuthenticatedLyrics(title, artist)
        }

    suspend fun musixmatchLyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val key = AppSettings.paxSenixApiKey.value.takeIf { it.isNotBlank() } ?: return@withContext null
            val url = "$API/lyrics/musixmatch".toHttpUrl().newBuilder()
                .addQueryParameter("t", title)
                .addQueryParameter("a", artist)
                .addQueryParameter("d", (durationMs / 1000).toString())
                .build()
            lyricsGetBearer(url.toString(), key)?.let(ProviderLyrics::parse)
                ?: genericAuthenticatedLyrics(title, artist)
        }

    private fun genericAuthenticatedLyrics(title: String, artist: String): List<LyricLine>? {
        val key = AppSettings.paxSenixApiKey.value.takeIf { it.isNotBlank() } ?: return null
        val url = "$API/lyrics/lrcget".toHttpUrl().newBuilder()
            .addQueryParameter("q", "$title $artist").build()
        return lyricsGetBearer(url.toString(), key)?.let(ProviderLyrics::parse)
    }

    private suspend fun searchPublicAppleTrackId(title: String, artist: String, durationMs: Long): String? {
        val token = appleToken() ?: return null
        val url = APPLE_SEARCH.toHttpUrl().newBuilder()
            .addQueryParameter("term", "$title $artist")
            .addQueryParameter("types", "songs")
            .addQueryParameter("limit", "10")
            .addQueryParameter("l", "en-US")
            .build()
        val raw = lyricsGetBearer(url.toString(), token) ?: return null
        return bestCandidateId(raw, title, artist, durationMs)
    }

    private suspend fun appleToken(): String? = cachedAppleToken.get() ?: tokenMutex.withLock {
        cachedAppleToken.get() ?: scrapeAppleToken()?.also(cachedAppleToken::set)
    }

    private fun scrapeAppleToken(): String? {
        val page = lyricsGet("https://music.apple.com/us/new") ?: return null
        val scriptPath = APPLE_INDEX_SCRIPT.find(page)?.value ?: return null
        val script = lyricsGet("https://music.apple.com$scriptPath") ?: return null
        return APPLE_TOKEN.find(script)?.value
    }

    private fun bestCandidateId(raw: String, wantedTitle: String, wantedArtist: String, wantedDuration: Long): String? {
        val root = runCatching { lyricsJson.parseToJsonElement(raw) }.getOrNull() ?: return null
        val candidates = buildList { root.collectCandidates(this) }
        return candidates.map { it to it.score(wantedTitle, wantedArtist, wantedDuration) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= 10 }
            ?.first?.id
    }

    private fun JsonElement.collectCandidates(into: MutableList<Candidate>) {
        when (this) {
            is JsonArray -> forEach { it.collectCandidates(into) }
            is JsonObject -> {
                toCandidate()?.let(into::add)
                values.forEach { it.collectCandidates(into) }
            }
            else -> Unit
        }
    }

    private fun JsonObject.toCandidate(): Candidate? {
        val details = this["attributes"] as? JsonObject ?: this
        val id = firstString(listOf("id", "trackId", "track_id", "realId"))
            ?: details.firstString(listOf("id", "trackId", "track_id", "realId")) ?: return null
        val title = details.firstString(listOf("name", "title", "trackName", "track_name")) ?: return null
        val artist = details.firstString(listOf("artistName", "artist_name")) ?: details.artistNames().orEmpty()
        val duration = details.firstLong(listOf("durationInMillis", "durationMs", "duration_ms", "duration"))
            .toDurationMs()
        return Candidate(id, title, artist, duration)
    }

    private fun JsonObject.artistNames(): String? = when (val artists = this["artists"] ?: this["artist"]) {
        is JsonPrimitive -> artists.contentOrNull
        is JsonObject -> artists.firstString(listOf("name", "artistName", "title"))
        is JsonArray -> artists.mapNotNull {
            when (it) {
                is JsonPrimitive -> it.contentOrNull
                is JsonObject -> it.firstString(listOf("name", "artistName", "title"))
                else -> null
            }
        }.joinToString(", ").takeIf(String::isNotEmpty)
        else -> null
    }

    private fun JsonObject.firstString(keys: List<String>): String? =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }

    private fun JsonObject.firstLong(keys: List<String>): Long? =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.longOrNull }

    private fun Long?.toDurationMs(): Long = when {
        this == null || this <= 0 -> 0
        this < 10_000 -> this * 1000
        else -> this
    }

    private fun Candidate.score(wantedTitle: String, wantedArtist: String, wantedDuration: Long): Int {
        var score = textScore(title, wantedTitle, 20, 10) + textScore(artist, wantedArtist, 15, 5)
        if (wantedDuration > 0 && durationMs > 0) score += when {
            abs(durationMs - wantedDuration) < 3_000 -> 10
            abs(durationMs - wantedDuration) < 10_000 -> 5
            else -> 0
        }
        return score
    }

    private fun textScore(candidate: String, wanted: String, exact: Int, partial: Int): Int = when {
        candidate.isBlank() || wanted.isBlank() -> 0
        candidate.equals(wanted, ignoreCase = true) -> exact
        candidate.contains(wanted, ignoreCase = true) || wanted.contains(candidate, ignoreCase = true) -> partial
        else -> 0
    }

    private data class Candidate(val id: String, val title: String, val artist: String, val durationMs: Long)

    private val APPLE_INDEX_SCRIPT = Regex("""/assets/index~[^\"]+\.js""")
    private val APPLE_TOKEN = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
}
