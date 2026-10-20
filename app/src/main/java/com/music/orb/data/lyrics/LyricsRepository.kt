package com.music.orb.data.lyrics

import com.music.orb.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
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

    /** Offline downloads must store real timestamps; plain lyrics are never an acceptable substitute. */
    suspend fun synchronizedLyrics(
        videoId: String, title: String, artist: String, durationMs: Long,
        album: String? = null,
    ): Result? {
        if (durationMs <= 0L) return null
        // Providers run concurrently, as in ordinary lyrics lookup, under one
        // 12-second download budget. Never serialize ten separate network timeouts.
        return withTimeoutOrNull(12_000L) {
            supervisorScope {
                val jobs = LyricsSource.entries.filter { it in AppSettings.lyricsSources.value }
                    .map { source ->
                        source to async(Dispatchers.IO) {
                            try {
                                fetchCandidate(source, videoId, title.trim(), artist.trim(),
                                    durationMs, album, null, null)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) { null }
                        }
                    }
                try {
                    for ((source, job) in jobs) {
                        val candidate = job.await() ?: continue
                        if (candidate.lines.count { line -> line.text.isNotBlank() &&
                                (line.timeMs > 0L || line.words.any { word -> word.startMs > 0L }) } >= 2) {
                            return@supervisorScope Result(source, candidate.lines)
                        }
                    }
                    null
                } finally { jobs.forEach { it.second.cancel() } }
            }
        }
    }

    /**
     * Fetch enabled sources concurrently to keep fallback latency low, but decide
     * the winner in [LyricsSource] priority order (not completion time or score).
     * A failed/empty provider transparently falls through to the next.
     *
     * The optional user-controlled syllable-sync preference can override the
     * default order *only* to favour word-synced lyrics: it still uses the first
     * suitable provider in the configured order.
     */
    private suspend fun fetchLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        sources: Set<LyricsSource>,
        prioritizeSyllableSync: Boolean,
        isrc: String?,
    ): Result? = supervisorScope {
        val sequence = LyricsSource.entries.filter { it in sources }
        val searchTitle = title.trim()
        val searchArtist = artist.trim()
        val known = isrc?.takeIf(String::isNotBlank) ?: synchronized(isrcs) { isrcs[videoId] }

        // Identify once, instead of redundantly contacting the Bini provider.
        val biniHit = if (LyricsSource.BINI_LYRICS in sequence) {
            withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
                try {
                    BiniLyrics.identify(searchTitle, searchArtist, durationMs, album, known)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        } else null

        val racing = sequence.map { source ->
            source to async(Dispatchers.IO) {
                try {
                    fetchCandidate(source, videoId, searchTitle, searchArtist, durationMs, album, known, biniHit)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }

        try {
            var firstAvailable: Result? = null
            for ((source, job) in racing) {
                val payload = try {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) { job.await() }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: continue
                if (payload.lines.none { it.text.isNotBlank() }) continue

                val result = Result(source, payload.lines)
                if (firstAvailable == null) firstAvailable = result
                if (!prioritizeSyllableSync || payload.lines.any { it.isWordSynced }) {
                    if (source == LyricsSource.BINI_LYRICS) {
                        biniHit?.isrc?.takeIf(String::isNotBlank)?.let { rememberIsrc(videoId, it) }
                    }
                    return@supervisorScope result
                }
            }
            firstAvailable
        } finally {
            racing.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetchCandidate(
        source: LyricsSource,
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
        isrc: String?,
        biniHit: BiniLyrics.Hit?,
    ): CandidatePayload? {
        return when (source) {
        LyricsSource.BINI_LYRICS -> {
            val match = biniHit?.let { BiniLyrics.lyricsFor(it) }
                ?: BiniLyrics.lyrics(title, artist, durationMs, album, isrc)
                ?: return null
            CandidatePayload(match.lines)
        }
        LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)?.let { CandidatePayload(it) }
        LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs, album)?.let { CandidatePayload(it) }
        LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc)?.let { CandidatePayload(it) }
        LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)?.let { CandidatePayload(it) }
        LyricsSource.UNISON -> Unison.lyrics(title, artist, durationMs, album)?.let { CandidatePayload(it) }
        LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(videoId)?.let { CandidatePayload(it) }
        LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(title, artist)?.let { CandidatePayload(it) }
        LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)?.let { CandidatePayload(it) }
        LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)?.let { CandidatePayload(it) }
        LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)?.let { CandidatePayload(it) }
        }
    }

    private data class CandidatePayload(val lines: List<LyricLine>)

    private const val IDENTIFY_TIMEOUT_MS = 2_500L
    private const val SOURCE_TIMEOUT_MS = 7_000L
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
