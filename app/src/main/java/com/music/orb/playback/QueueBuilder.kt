package com.music.orb.playback

import com.music.orb.data.model.Song

/**
 * Builds the station that plays on after a one-off song.
 *
 * Two problems this exists to solve:
 *
 *  - YouTube's watch queues routinely carry the same recording twice — the
 *    official audio and the music video are separate videoIds with near
 *    identical titles — so de-duping on id alone lets a track play, then play
 *    again as its video. Songs are matched on a normalised title plus lead
 *    artist instead.
 *
 *  - A radio mix leans hard on the seed's own artist. Capping how many tracks
 *    any one artist contributes keeps a station from turning into a single
 *    artist's discography.
 */
object QueueBuilder {

    /** No more than this many tracks by one artist in a single batch. */
    private const val PER_ARTIST_LIMIT = 2

    /** The artist the station was seeded on gets more room, but not the run of it. */
    private const val SEED_ARTIST_LIMIT = 4

    /**
     * The subset of [candidates] worth appending after [existing]: nothing
     * already queued, nothing repeated, at most [limit] tracks.
     */
    fun extend(existing: List<Song>, candidates: List<Song>, limit: Int): List<Song> {
        val taken = existing.toMutableList()
        val perArtist = mutableMapOf<String, Int>()
        val seedArtists = existing.lastOrNull()?.artist?.let(::artistSet).orEmpty()
        val out = mutableListOf<Song>()

        // A mix pairs all but every track with its own music-video upload —
        // the same recording under a different id, titled far enough apart
        // ("Dildaara (Stand By Me)" against "Lyrical Video: Dildara Song")
        // that no title match will catch it. The catalogue cut is the one a
        // music app wants; videos only stand in when there is nothing else.
        val songsOnly = candidates.filterNot { it.isVideo }
        val ordered = songsOnly.ifEmpty { candidates }

        for (candidate in ordered) {
            if (out.size >= limit) break
            if (taken.any { isSameRecording(it, candidate) }) continue

            val artists = artistSet(candidate.artist)
            val key = artists.minOrNull()
            if (key != null) {
                val cap = if (artists.any { it in seedArtists }) {
                    SEED_ARTIST_LIMIT
                } else {
                    PER_ARTIST_LIMIT
                }
                val played = perArtist[key] ?: 0
                if (played >= cap) continue
                perArtist[key] = played + 1
            }

            taken += candidate
            out += candidate
        }
        return out
    }

    /**
     * Selects a *station*, not just the most similar recording. Musical proximity
     * is handled later by the Automix queue sorter; this stage favours new artists
     * over another title from the seed artist or one heard repeatedly recently.
     * History is also used as a recording-level veto across refills, not only the
     * final 36 entries still present in Media3's queue.
     */
    fun extendDiverse(
        existing: List<Song>,
        candidates: List<Song>,
        history: List<Song>,
        limit: Int,
    ): List<Song> {
        if (limit <= 0) return emptyList()
        val forbidden = existing + history
        val unique = mutableListOf<Song>()
        candidates.forEach { song ->
            if (forbidden.none { isSameRecording(it, song) } &&
                unique.none { isSameRecording(it, song) }
            ) unique += song
        }

        val recentCredits = (history + existing).takeLast(24)
        val lastCredits = (history + existing).takeLast(5)
        val frequency = recentCredits.flatMap { artistSet(it.artist) }
            .groupingBy { it }.eachCount()
        val latestArtists = lastCredits.flatMap { artistSet(it.artist) }.toSet()
        val seedArtists = existing.lastOrNull()?.artist?.let(::artistSet).orEmpty()
        val selected = mutableListOf<Song>()
        val selectedArtists = mutableMapOf<String, Int>()

        while (selected.size < limit && unique.isNotEmpty()) {
            val choice = unique.withIndex().minByOrNull { (rank, song) ->
                val credits = artistSet(song.artist)
                val exposure = credits.maxOfOrNull { frequency[it] ?: 0 } ?: 0
                val alreadyChosen = credits.maxOfOrNull { selectedArtists[it] ?: 0 } ?: 0
                val seedPenalty = if (credits.any { it in seedArtists }) 30 else 0
                val recentPenalty = if (credits.any { it in latestArtists }) 20 else 0
                // Selection penalties intentionally dominate radio order: radio's top
                // matches otherwise reproduce the same two artists endlessly.
                val videoPenalty = if (song.isVideo) 500 else 0
                alreadyChosen * 1000 + exposure * 35 + seedPenalty + recentPenalty +
                    videoPenalty + rank
            }?.value ?: break
            unique.remove(choice)
            selected += choice
            artistSet(choice.artist).forEach { artist ->
                selectedArtists[artist] = (selectedArtists[artist] ?: 0) + 1
            }
        }
        return selected
    }

    /**
     * Whether two entries are the same recording. Ids differ between the audio
     * and video cuts of a track, which is exactly the case id equality misses.
     */
    fun isSameRecording(a: Song, b: Song): Boolean {
        if (a.videoId == b.videoId) return true
        val title = normalisedTitle(a.title)
        if (title.isEmpty() || title != normalisedTitle(b.title)) return false
        // One shared name is enough. The two cuts of a track get billed in
        // whatever order the upload used — "Pritam, Arijit Singh & Shilpa Rao"
        // against "Shilpa Rao, Arijit Singh, & Pritam" is one song, twice.
        val left = artistSet(a.artist)
        val right = artistSet(b.artist)
        return left.isEmpty() || right.isEmpty() || left.any { it in right }
    }

    /**
     * `Kesariya (From "Brahmastra") | Official Video` and `Kesariya` are one
     * recording as far as a queue is concerned. Remix and cover markers are
     * deliberately left in — those really are different tracks.
     */
    internal fun normalisedTitle(raw: String): String = raw.lowercase()
        .substringBefore(" | ")
        .replace(NOISE, " ")
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()

    /** The cast behind a credit, split out so billing order stops mattering. */
    internal fun artistSet(raw: String): Set<String> = raw.lowercase()
        .replace(TOPIC, " ")
        .split(",", "&", "·", "•", ";", " feat", " ft.", " ft ", " x ", " with ")
        .map { it.replace(PUNCTUATION, " ").replace(SPACES, " ").trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val NOISE = Regex(
        """\((?:official|lyric|lyrics|lyrical|audio|video|visuali[sz]er|full song|hd|4k)[^)]*\)""" +
            """|\(from[^)]*\)""" +
            """|\[[^]]*]""" +
            """|\b(?:official (?:video|audio|music video)|lyrical video|full video|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC = Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE)
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}
