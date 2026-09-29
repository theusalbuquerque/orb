package com.music.orb.data.lyrics

import com.music.orb.data.settings.AppSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
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

        val biniHit = if (LyricsSource.BINI_LYRICS in sequence) {
            withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
                runCatching { BiniLyrics.identify(searchTitle, searchArtist, durationMs, album, known) }.getOrNull()
            }
        } else null

        val racing = sequence.map { source ->
            val start = if (source == LyricsSource.GENIUS) CoroutineStart.LAZY else CoroutineStart.DEFAULT
            source to async(Dispatchers.IO, start = start) {
                fetchCandidate(source, videoId, searchTitle, searchArtist, durationMs, album, known, biniHit)
            }
        }

        try {
            val candidates = racing.mapNotNull { (source, job) ->
                val payload = try {
                    job.await()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null
                val lines = payload.lines.takeIf { found -> found.any { it.text.isNotBlank() } }
                    ?: return@mapNotNull null
                Candidate(source, lines, payload.identityScore)
            }
            if (candidates.isEmpty()) return@coroutineScope null
            val winner = candidates.maxByOrNull { candidateScore(it, candidates, prioritizeSyllableSync) }
                ?: return@coroutineScope null
            if (winner.source == LyricsSource.BINI_LYRICS) {
                biniHit?.isrc?.takeIf { it.isNotBlank() }?.let { rememberIsrc(videoId, it) }
            }
            Result(winner.source, winner.lines)
        } finally {
            racing.forEach { it.second.cancel() }
        }
    }

    private data class Candidate(
        val source: LyricsSource,
        val lines: List<LyricLine>,
        val identityScore: Int,
    )

    private fun candidateScore(candidate: Candidate, all: List<Candidate>, prioritizeSyllableSync: Boolean): Int {
        val sourceBase = when (candidate.source) {
            LyricsSource.BINI_LYRICS -> 95
            LyricsSource.BETTER_LYRICS -> 74
            LyricsSource.BETTER_LYRICS_PORTATO -> 72
            LyricsSource.PAXSENIX -> 70
            LyricsSource.PAXSENIX_SPOTIFY -> 68
            LyricsSource.PAXSENIX_MUSIXMATCH -> 76
            LyricsSource.LYRICS_PLUS -> 70
            LyricsSource.SIMP_MUSIC -> 120
            LyricsSource.UNISON -> 64
            LyricsSource.YOUTUBE_TRANSCRIPT -> 82
            LyricsSource.MEGALOBIZ -> 58
            LyricsSource.KUGOU -> 60
            LyricsSource.LRCLIB -> 66
            LyricsSource.MUSIXMATCH -> 68
            LyricsSource.GENIUS -> 45
        }
        val timingBonus = when {
            candidate.lines.any { it.isWordSynced } -> 10
            candidate.lines.any { it.timeMs > 0L } -> 4
            else -> 0
        }
        val preference = if (prioritizeSyllableSync && !candidate.lines.any { it.isWordSynced }) -8 else 0
        val consensus = all.asSequence().filter { it !== candidate }
            .map { lyricSimilarity(candidate.lines, it.lines) }
            .maxOrNull()?.let { (it * 24.0).toInt() } ?: 0
        return sourceBase + candidate.identityScore + timingBonus + preference + consensus
    }

    private fun lyricSimilarity(a: List<LyricLine>, b: List<LyricLine>): Double {
        fun tokens(lines: List<LyricLine>): Set<String> =
            lines.asSequence()
                .flatMap { it.text.lowercase(Locale.ROOT).split(Regex("\\W+")).asSequence() }
                .map(String::trim).filter { it.length >= 3 }.take(500).toSet()
        val left = tokens(a); val right = tokens(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size.toDouble()
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
    ): CandidatePayload? = when (source) {
        LyricsSource.BINI_LYRICS -> {
            val match = biniHit?.let { BiniLyrics.lyricsFor(it) }
                ?: BiniLyrics.lyrics(title, artist, durationMs, album, isrc)
                ?: return null
            CandidatePayload(match.lines, biniHit?.let { BiniLyrics.matchScore(it, title, artist, durationMs, album) } ?: 0)
        }
        LyricsSource.BETTER_LYRICS -> BetterLyrics.lyrics(title, artist, durationMs, album)?.let { CandidatePayload(it, 0) }
        LyricsSource.BETTER_LYRICS_PORTATO -> BetterLyrics.portato(title, artist, durationMs, album)?.let { CandidatePayload(it, 0) }
        LyricsSource.PAXSENIX -> PaxSenix.lyrics(title, artist, durationMs, album)?.let { CandidatePayload(it, 0) }
        LyricsSource.PAXSENIX_SPOTIFY -> PaxSenix.spotifyLyrics(title, artist, durationMs)?.let { CandidatePayload(it, 0) }
        LyricsSource.PAXSENIX_MUSIXMATCH -> PaxSenix.musixmatchLyrics(title, artist, durationMs)?.let { CandidatePayload(it, 0) }
        LyricsSource.LYRICS_PLUS -> LyricsPlus.lyrics(title, artist, durationMs, album, isrc)?.let { CandidatePayload(it, 0) }
        LyricsSource.SIMP_MUSIC -> SimpMusicLyrics.lyrics(videoId, durationMs)?.let { CandidatePayload(it, 0) }
        LyricsSource.UNISON -> Unison.lyrics(title, artist, durationMs, album)?.let { CandidatePayload(it, 0) }
        LyricsSource.YOUTUBE_TRANSCRIPT -> YouTubeTranscriptLyrics.lyrics(videoId)?.let { CandidatePayload(it, 0) }
        LyricsSource.MEGALOBIZ -> Megalobiz.lyrics(title, artist)?.let { CandidatePayload(it, 0) }
        LyricsSource.KUGOU -> KuGou.lyrics(title, artist, durationMs, album)?.let { CandidatePayload(it, 0) }
        LyricsSource.LRCLIB -> LrcLib.lyrics(title, artist, durationMs)?.let { CandidatePayload(it, 0) }
        LyricsSource.MUSIXMATCH -> Musixmatch.lyrics(title, artist, durationMs)?.let { CandidatePayload(it, 0) }
        LyricsSource.GENIUS -> Genius.lyrics(title, artist)?.let { CandidatePayload(it, 0) }
    }

    private data class CandidatePayload(val lines: List<LyricLine>, val identityScore: Int)

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
