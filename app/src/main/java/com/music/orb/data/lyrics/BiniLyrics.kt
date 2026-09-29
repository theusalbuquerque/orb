package com.music.orb.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Apple Music TTML matched to a recording; exposes ISRC for downstream matching. */
object BiniLyrics {
    private const val BASE = "https://lyrics-api.binimum.org/"

    data class Match(val isrc: String?, val lines: List<LyricLine>)

    internal suspend fun identify(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        isrc: String? = null,
    ): Hit? = withContext(Dispatchers.IO) {
        val url = BASE.toHttpUrl().newBuilder()
            .apply {
                if (!isrc.isNullOrBlank()) {
                    addQueryParameter("isrc", isrc)
                } else {
                    addQueryParameter("track", title)
                    addQueryParameter("artist", artist)
                    if (!album.isNullOrBlank()) addQueryParameter("album", album)
                    val seconds = durationMs / 1000
                    if (seconds > 0) addQueryParameter("duration", seconds.toString())
                }
            }
            .build()
        val body = lyricsGet(url.toString()) ?: return@withContext null
        val response = runCatching { lyricsJson.decodeFromString<Response>(body) }.getOrNull()
            ?: return@withContext null
        response.results?.maxByOrNull { hit -> matchScore(hit, title, artist, durationMs, album) }
    }

    internal fun matchScore(hit: Hit, title: String, artist: String, durationMs: Long, album: String?): Int {
        fun norm(value: String?): String =
            value.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        fun similarity(a: String?, b: String?): Int {
            val left = norm(a); val right = norm(b)
            if (left.isBlank() || right.isBlank()) return 0
            if (left == right) return 35
            if (left.contains(right) || right.contains(left)) return 22
            return 0
        }
        val durationScore = hit.duration?.let { seconds ->
            val delta = kotlin.math.abs(seconds * 1000L - durationMs)
            when { delta <= 1_500L -> 30; delta <= 3_000L -> 20; delta <= 5_000L -> 10; delta <= 10_000L -> 3; else -> 0 }
        } ?: 0
        return similarity(hit.trackName, title) +
            similarity(hit.artistName, artist) +
            if (!album.isNullOrBlank()) similarity(hit.albumName, album) else 0 +
            durationScore
    }

    internal suspend fun lyricsFor(hit: Hit): Match? = withContext(Dispatchers.IO) {
        val document = hit.lyricsUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        val ttml = lyricsGet(document) ?: return@withContext null
        val lines = TtmlLyrics.parse(ttml).takeIf { it.isNotEmpty() } ?: return@withContext null
        Match(hit.isrc?.takeIf { it.isNotBlank() }, lines)
    }

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        isrc: String? = null,
    ): Match? = identify(title, artist, durationMs, album, isrc)?.let { lyricsFor(it) }

    @Serializable
    internal data class Response(
        val total: Int? = null,
        val source: String? = null,
        val results: List<Hit>? = null,
    )

    @Serializable
    internal data class Hit(
        @SerialName("track_name") val trackName: String? = null,
        @SerialName("artist_name") val artistName: String? = null,
        @SerialName("album_name") val albumName: String? = null,
        val duration: Int? = null,
        val isrc: String? = null,
        @SerialName("timing_type") val timingType: String? = null,
        val lyricsUrl: String? = null,
    )
}
