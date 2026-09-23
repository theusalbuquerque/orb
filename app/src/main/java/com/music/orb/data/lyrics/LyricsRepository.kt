package com.music.orb.data.lyrics

import com.music.orb.data.settings.AppSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/** Multi-provider lyrics resolver. */
object LyricsRepository {
    data class Result(val source: LyricsSource, val lines: List<LyricLine>)

    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
        prioritizeSyllableSync: Boolean = AppSettings.prioritizeSyllableSync.value,
        isrc: String? = null,
    ): Result? {
        if (sources.isEmpty()) return null
        val key = LyricsDiskCache.key(
            videoId, title, artist, durationMs, album, sources, prioritizeSyllableSync,
        )
        LyricsDiskCache.get(key)?.takeIf { it.source in sources }?.let { return it }
        val result = fetchLyrics(
            videoId, title, artist, durationMs, album, sources,
            prioritizeSyllableSync, isrc,
        )
        if (result != null) LyricsDiskCache.put(key, result)
        return result
    }

    private suspend fun fetchLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sources: Set<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        isrc: String?,
    ): Result? = coroutineScope {
        val sequence = LyricsSource.entries.filter { it in sources }
        val searchTitle = title.trim()
        val searchArtist = artist.trim()

        val known = isrc?.takeIf(String::isNotBlank) ?: synchronized(isrcs) { isrcs[videoId] }
        val hit = if (known == null && LyricsSource.BINI_LYRICS in sequence) {
            withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
                runCatching {
                    BiniLyrics.identify(searchTitle, searchArtist, durationMs, album)
                }.getOrNull()
            }
        } else null
        hit?.isrc?.takeIf(String::isNotBlank)?.let { rememberIsrc(videoId, it) }
        val recording = known ?: hit?.isrc?.takeIf(String::isNotBlank)

        val racing: List<Pair<LyricsSource, Deferred<List<LyricLine>?>>> = sequence.map { source ->
            val start = if (source == LyricsSource.GENIUS) CoroutineStart.LAZY else CoroutineStart.DEFAULT
            source to async(Dispatchers.IO, start = start) {
                fetch(source, videoId, searchTitle, searchArtist, durationMs, album, recording, hit)
            }
        }

        try {
            var fallback: Result? = null
            for ((source, job) in racing) {
                if (fallback != null && source == LyricsSource.GENIUS) continue
                val lines = try {
                    job.await()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }?.takeIf { found -> found.any { it.text.isNotBlank() } }
                    ?: continue

                val result = Result(source, lines)
                if (lines.any { it.isWordSynced }) return@coroutineScope result
                if (!prioritizeSyllableSync && lines.any { it.timeMs > 0L }) {
                    return@coroutineScope result
                }
                if (fallback == null) fallback = result
            }
            fallback
        } finally {
            racing.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetch(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
        biniHit: BiniLyrics.Hit?,
    ): List<LyricLine>? = when (source) {
        LyricsSource.BINI_LYRICS ->
            (biniHit?.let { BiniLyrics.lyricsFor(it) }
                ?: BiniLyrics.lyrics(title, artist, durationMs, album, isrc))
                ?.also { match -> match.isrc?.let { rememberIsrc(videoId, it) } }
                ?.lines
        LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)
        LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs, album)
        LyricsSource.PAXSENIX -> PaxSenix.lyrics(title, artist, durationMs, album)
        LyricsSource.PAXSENIX_SPOTIFY -> PaxSenix.spotifyLyrics(title, artist, durationMs)
        LyricsSource.PAXSENIX_MUSIXMATCH -> PaxSenix.musixmatchLyrics(title, artist, durationMs)
        LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc)
        LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)
        LyricsSource.UNISON -> Unison.lyrics(title, artist, durationMs, album)
        LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(videoId)
        LyricsSource.YOUTUBE_MUSIC -> YouTubeMusicLyrics.lyrics(videoId)
        LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(title, artist)
        LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)
        LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)
        LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)
        LyricsSource.GENIUS -> Genius.lyrics(title, artist)
    }

    private const val IDENTIFY_TIMEOUT_MS = 2_500L
    private const val REMEMBERED_ISRCS = 100
    private val isrcs: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(REMEMBERED_ISRCS, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > REMEMBERED_ISRCS
        },
    )

    private fun rememberIsrc(videoId: String, isrc: String) {
        if (videoId.isNotBlank() && isrc.isNotBlank()) isrcs[videoId] = isrc
    }
}
