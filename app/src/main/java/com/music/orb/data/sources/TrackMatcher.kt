package com.music.orb.data.sources

import com.music.orb.data.model.Song
import kotlin.math.abs
import java.text.Normalizer

/**
 * Decides whether one catalogue's track is the same recording as another's,
 * and what to ask that catalogue for in the first place.
 *
 * Split out of [SourceResolver] because it is the only part of the source
 * layer that is a judgement rather than plumbing, and because both of its
 * failure modes are silent:
 *
 *  - **Too loose** and the wrong recording plays under the right title — a
 *    cover, a remix, an hour-long loop — with nothing on screen to say so.
 *  - **Too strict** and the module the user configured is quietly never used,
 *    which is what "Paniyon Sa (From "Satyamev Jayate")" by Atif Aslam did
 *    against a catalogue holding the same audio as "Paniyon Sa" by
 *    Atif Aslam, Tulsi Kumar.
 *
 * The way out of both is to be explicit about *what part of a title carries
 * identity*. Services disagree constantly about the packaging — the film a
 * song is from, the "Official Audio" tag, which of the credited singers make
 * it into the title — and agree about the recording underneath. So the title
 * is taken apart into three pieces:
 *
 *  - [TitleParts.words], the title proper. Must agree exactly.
 *  - [TitleParts.versions], the words that mean *a different take* — remix,
 *    live, acoustic. Must agree exactly, in both directions: "Song (Live)"
 *    is not "Song", and neither is the other way round.
 *  - [TitleParts.context], everything else thrown away with the brackets.
 *    Never a veto, only a tie-break, because one service writing the film
 *    name in the title is not a disagreement about the audio.
 *
 * Artist and duration are then checked against that: a shared credit is
 * required when both sides name one, and a runtime far from the one asked for
 * rules a candidate out however well its title reads.
 */
object TrackMatcher {

    /** The recording being looked for, as much of it as the queue knows. */
    data class Target(
        val title: String,
        val artist: String = "",
        /** Runtime in whole seconds; null when the queue row never carried one. */
        val durationSec: Int? = null,
        /** Explicit and clean masters are not interchangeable recordings. */
        val isExplicit: Boolean? = null,
        /** Release title claimed by the catalogue row, when known. */
        val albumName: String? = null,
        /** Original release year, when a trustworthy catalogue states it. */
        val releaseYear: Int? = null,
        /** YouTube id used only to enrich missing identity metadata in background. */
        val sourceVideoId: String? = null,
    )

    fun targetOf(song: Song) = Target(
        title = song.title,
        artist = song.artist,
        durationSec = secondsOf(song.durationText),
        isExplicit = song.isExplicit,
        albumName = song.albumName,
        releaseYear = song.releaseYear,
        sourceVideoId = song.videoId.takeIf { SourceRegistry.parseTrackKey(it) == null },
    )

    // ── Asking ──────────────────────────────────────────────────────────────

    /**
     * What to put to a source's search box, best query first.
     *
     * The raw title is deliberately *not* one of them. A YouTube title carries
     * the packaging — `Paniyon Sa (From "Satyamev Jayate")` — and handing that
     * verbatim to a catalogue that lists the track as `Paniyon Sa` is asking it
     * to match on words it has never stored. Most search backends score that as
     * a poor hit or no hit at all, and the track was then written off as
     * missing before any of the matching below ever ran.
     *
     * Two queries, not one: the second drops the artist, for the catalogues
     * that credit a track to the composer or the film rather than the singer
     * and would otherwise score every result down.
     */
    fun queries(target: Target): List<String> {
        val title = searchableTitle(target.title, target.artist)
        if (title.isBlank()) return emptyList()
        val artist = primaryArtist(target.artist)
        val album = target.albumName?.trim().orEmpty()
        val normal = buildList {
            // Album-aware lookup first. Search engines are allowed to return
            // covers and alternate releases; giving them the requested release
            // helps discovery before identity checks even begin.
            if (artist.isNotBlank() && album.isNotBlank()) add("$title $artist $album")
            if (artist.isNotBlank()) add("$title $artist")
            add(title)
        }.distinct()

        // Some catalogues expose clean and explicit as separate rows with the
        // same title. Ask for the explicit edition first when that is what was
        // selected, then fall back to the ordinary query and let score() enforce
        // the badge identity. This improves discovery without ever substituting
        // a clean master for an explicit one.
        if (target.isExplicit != true) return normal
        val hinted = if (artist.isBlank()) {
            listOf("$title explicit")
        } else {
            listOf("$title $artist explicit", "$title explicit")
        }
        return (hinted + normal).distinct()
    }

    /** The title with the packaging taken off, version markers kept. */
    internal fun searchableTitle(title: String, artist: String = ""): String =
        parseTitle(title, artist).let { (it.words + it.versions).joinToString(" ") }

    /** The first credited artist — who a catalogue is most likely to file the track under. */
    internal fun primaryArtist(artist: String): String =
        artist.lowercase().split(ARTIST_SEPARATORS).firstOrNull()?.trim().orEmpty()

    // ── Judging ─────────────────────────────────────────────────────────────

    /**
     * The best of [candidates] that is genuinely [target], or null if none is.
     *
     * Best, not first. A search for a track routinely answers with the single,
     * the album cut, a sped-up edit and a karaoke version, in whatever order
     * the backend felt like — and taking the first acceptable one means the
     * ranking of somebody else's search engine decides which copy plays.
     * Scoring them all and taking the top lets the runtime and the fuller
     * artist credit break that tie instead.
     */
    fun best(candidates: List<Song>, target: Target): Song? = ranked(candidates, target).firstOrNull()

    /**
     * Every candidate that really is [target], most confident first.
     *
     * The whole list rather than the winner, because identity is not the only
     * question worth asking of it: two catalogues can both genuinely hold a
     * recording and offer it at different qualities, and that choice belongs
     * to [SourceResolver], which knows what was asked for. Confidence orders
     * what it is given; it does not get to spend it.
     */
    fun ranked(candidates: List<Song>, target: Target): List<Song> =
        candidates
            .mapNotNull { candidate -> score(candidate, target)?.let { candidate to it } }
            .sortedByDescending { it.second }
            .map { it.first }

    /**
     * How confident this is the same recording, or null when it is not one.
     *
     * Null is the common answer and the safe one: it costs a source its turn,
     * and the next source — ultimately YouTube, which by definition has the
     * track — still plays what the user asked for.
     */
    fun score(candidate: Song, target: Target): Int? {
        val wanted = parseTitle(target.title, target.artist)
        val got = parseTitle(candidate.title, candidate.artist)
        if (wanted.core.isEmpty() || got.core.isEmpty()) return null
        if (wanted.core != got.core) return null
        // Explicit and clean versions can have identical title, artist and
        // duration. Once the catalogue told us which one was selected, that
        // distinction is a hard identity check just like remix/live markers.
        if (target.isExplicit != null && candidate.sourceExplicitKnown && candidate.isExplicit != target.isExplicit) return null
        // Direction matters both ways round: asking for the album cut must not
        // land on the live take, and asking for the live take must not land on
        // the album cut.
        //
        // Some catalogues (notably TIDAL) may hide the take descriptor outside
        // the track title. TIDAL has a dedicated `version` field (folded into
        // candidate.title by TidalSource), and some releases still expose it
        // only in the album/single title (e.g. "Escapism. (Sped Up)"). A plain
        // target must never match one of those rows merely because its visible
        // track title is still "Escapism.".
        val gotVersions = got.versions + candidateAlbumVersions(candidate)
        if (wanted.versions != gotVersions) return null

        val duration = durationScore(target.durationSec, secondsOf(candidate.durationText))
            ?: return null

        // A cover with the same title and nearly the same runtime is still a
        // cover. Runtime is corroboration, never permission to waive a named
        // artist. Every artist explicitly present in the requested credit —
        // including featured artists — must be represented by the candidate.
        val artist = artistScore(target.artist, candidate.artist) ?: return null

        // Release metadata is recording identity too. We only veto on fields
        // both catalogues actually state; SourceResolver enriches both sides
        // before a lossless upgrade so album/year are normally available there.
        val release = releaseScore(target, candidate) ?: return null

        return BASE + artist + duration + release + contextScore(wanted, got)
    }


    /**
     * Version markers hidden in the release title rather than the track title.
     *
     * This is deliberately a veto, not a scoring hint. Playing YouTube's
     * official/album recording is always preferable to substituting a lossless
     * remix under the official metadata. Neutral labels such as "Album
     * Version", "Radio Edit" and "Original Mix" are already removed by
     * [classify], so only genuinely different takes survive here.
     */
    private fun candidateAlbumVersions(candidate: Song): Set<String> {
        val album = candidate.albumName?.trim().orEmpty()
        if (album.isBlank()) return emptySet()
        return parseTitle(album, candidate.artist).versions
    }

    /**
     * Whether [candidate] states a runtime, and one within [seconds] of
     * [target]'s.
     *
     * Both halves are requirements. A candidate that doesn't say how long it
     * is fails this — for the callers that ask, an unstated runtime is not a
     * near miss, it is a candidate that cannot be checked, and the whole
     * reason to ask is that the check is the last thing standing between a
     * listener and the wrong recording.
     */
    fun withinSeconds(candidate: Song, target: Target, seconds: Int): Boolean {
        val wanted = target.durationSec ?: return false
        val got = secondsOf(candidate.durationText) ?: return false
        return abs(wanted - got) <= seconds
    }

    /**
     * Final cross-source identity check used before Lossless is allowed to
     * replace YouTube. Unlike the broad catalogue scorer, this requires the
     * release itself to be stated. When both catalogues know the year it must
     * agree; an omitted TIDAL year is unknown metadata, not a different release.
     */
    internal fun verifiedForLossless(candidate: Song, target: Target): Boolean {
        if (score(candidate, target) == null) return false
        if (target.artist.isBlank() || candidate.artist.isBlank()) return false
        val wantedAlbum = target.albumName?.takeIf { it.isNotBlank() } ?: return false
        val gotAlbum = candidate.albumName?.takeIf { it.isNotBlank() } ?: return false
        if (releaseCore(wantedAlbum) != releaseCore(gotAlbum)) return false
        if (target.releaseYear != null && candidate.releaseYear != null && candidate.releaseYear != target.releaseYear) return false
        if (!withinSeconds(candidate, target, LOSSLESS_IDENTITY_DURATION_SEC)) return false
        return true
    }


    /**
     * Strict identity gate for replacing an authoritative YouTube Music track
     * with bytes from another catalogue.
     *
     * General search matching intentionally tolerates incomplete metadata so a
     * user can still find obscure recordings. Playback substitution cannot be
     * that permissive: the UI keeps the original song title/artwork while only
     * the audio bytes change, so a false positive sounds exactly like Orb is
     * presenting a cover as the official recording.
     *
     * When the original release is known, the external candidate must state a
     * compatible release too. A common deluxe/expanded reissue is accepted as
     * the same release family; an unrelated tribute/cover compilation is not.
     */
    internal fun verifiedForSubstitution(candidate: Song, target: Target): Boolean {
        // Do the title/artist/version/duration check first without release veto;
        // release-family compatibility is handled below so a legitimate deluxe
        // reissue can still satisfy an original-album request.
        if (score(candidate, target.copy(albumName = null, releaseYear = null)) == null) return false
        if (target.artist.isBlank() || candidate.artist.isBlank()) return false

        if (target.durationSec != null && !withinSeconds(candidate, target, LOSSLESS_IDENTITY_DURATION_SEC)) {
            return false
        }

        if (looksLikeCover(candidate.title, candidate.albumName) &&
            !looksLikeCover(target.title, target.albumName)
        ) {
            return false
        }

        val wantedAlbum = target.albumName?.takeIf { it.isNotBlank() }
        if (wantedAlbum != null) {
            val gotAlbum = candidate.albumName?.takeIf { it.isNotBlank() } ?: return false
            if (!sameReleaseFamily(wantedAlbum, gotAlbum)) return false
        }

        if (target.releaseYear != null && candidate.releaseYear != null &&
            candidate.releaseYear != target.releaseYear
        ) {
            return false
        }
        return true
    }

    /**
     * Kept for the callers that only want a yes or no — the YouTube seed
     * lookup behind AutoPlay, and the tests.
     */
    fun matches(candidate: Song, title: String, artist: String, durationSec: Int? = null): Boolean =
        score(candidate, Target(title, artist, durationSec)) != null

    // ── Title ───────────────────────────────────────────────────────────────

    /**
     * A title split into the part that is the recording's identity and the
     * parts that are the listing's.
     */
    internal data class TitleParts(
        /** The title proper, lowercased, one entry per word. */
        val words: List<String>,
        /** [words] with everything but letters and digits removed — what identity is compared on. */
        val core: String,
        /** Markers that mean a different take of the same song: `remix`, `live`, `acoustic`. */
        val versions: Set<String>,
        /** Words dropped with the packaging. A hint for scoring, never a veto. */
        val context: Set<String>,
    )

    internal fun parseTitle(raw: String, artist: String = ""): TitleParts {
        val versions = sortedSetOf<String>()
        val context = mutableSetOf<String>()
        var text = raw.lowercase().replace("&", " and ")

        // Bracketed asides, innermost first: "(From "Satyamev Jayate")",
        // "[Official Audio]", "(Live at Wembley)".
        repeat(BRACKET_PASSES) {
            if (!BRACKETED.containsMatchIn(text)) return@repeat
            text = BRACKETED.replace(text) { match ->
                classify(match.groupValues[1], versions, context)
                " "
            }
        }
        // An unbalanced bracket — a title truncated mid-aside — takes the rest
        // of the line with it rather than leaving half an aside in the core.
        text.indexOfFirst { it == '(' || it == '[' }.takeIf { it >= 0 }?.let { open ->
            classify(text.substring(open), versions, context)
            text = text.substring(0, open)
        }

        // Dash- and pipe-separated tails: "Paniyon Sa - Satyamev Jayate",
        // "Song | Official Video". The head is normally the title, but the
        // "Artist - Title" upload convention inverts that, so a head that is
        // just the artist's name hands over to the tail instead of eating it.
        repeat(DASH_PASSES) {
            val dash = DASH.find(text) ?: return@repeat
            val head = text.substring(0, dash.range.first)
            val tail = text.substring(dash.range.last + 1)
            text = if (isArtistName(head, artist)) {
                classify(head, versions, context)
                tail
            } else {
                classify(tail, versions, context)
                head
            }
        }

        // A feat. credit belongs to the artist field wherever a catalogue
        // chooses to print it.
        text = text.replace(FEATURING, " ")

        var words = text.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() && it !in JOINING_WORDS }
        // "Paniyon Sa Full Song", "Tum Hi Ho Audio" — an upload's trailing
        // label, printed without brackets to hang it on. Never stripped down
        // to nothing: a track really called "Song" keeps its name.
        while (words.size > 1 && words.last() in TRAILING_NOISE) {
            words = words.dropLast(1)
        }

        return TitleParts(
            words = words,
            core = words.joinToString(""),
            versions = versions,
            context = context,
        )
    }

    /**
     * Files one dropped segment under [versions] or [context].
     *
     * A segment naming a take — `Remix`, `Live at Wembley`, `Slowed + Reverb` —
     * is identity and is kept. Everything else is packaging: the film, the
     * label, `Official Video`, the remaster note. The phrases in
     * [NEUTRAL_SEGMENTS] are the exceptions that read like takes and aren't:
     * `Album Version` and `Radio Edit` describe the ordinary release, and
     * treating them as versions would stop a module ever matching the plain
     * listing of the same track.
     */
    private fun classify(
        segment: String,
        versions: MutableSet<String>,
        context: MutableSet<String>,
    ) {
        val words = segment.split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        if (words.joinToString("") in NEUTRAL_SEGMENTS) return
        val marks = words.filter { it in VERSION_WORDS }
        if (marks.isNotEmpty()) {
            versions += marks
            return
        }
        context += words.filter { it.length > 2 && it !in NOISE_WORDS }
    }

    /** Whether [text] is nothing but (part of) [artist] — the "Artist - Title" upload shape. */
    private fun isArtistName(text: String, artist: String): Boolean {
        if (artist.isBlank()) return false
        val words = text.split(WORD_SPLIT).map { it.replace(NON_ALNUM, "") }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val credited = artist.lowercase().split(WORD_SPLIT)
            .map { it.replace(NON_ALNUM, "") }
            .filter { it.isNotEmpty() }
            .toSet()
        return words.all { it in credited }
    }

    // ── Artist ──────────────────────────────────────────────────────────────

    /**
     * Points for the credit agreeing, or null when it disagrees.
     *
     * The disagreement that matters is a cover: same title, different singer.
     * The agreement that has to survive is a *partial* credit, because which
     * of a duet's singers reaches the title is a formatting choice — YouTube's
     * "Atif Aslam" and a module's "Atif Aslam, Tulsi Kumar" are one recording
     * with two spellings of its credit, and refusing that pairing is what kept
     * the module out of the way of the very tracks it held.
     *
     * A side with no credit at all scores zero rather than failing: there is
     * nothing to disagree with, and the title has already had to match exactly.
     */
    private fun artistScore(wanted: String, got: String): Int? {
        val want = artistNames(wanted)
        val have = artistNames(got)
        if (want.isEmpty()) return 0
        if (have.isEmpty()) return null

        // Shared-main-artist alone is not enough. If YouTube says
        // "Mark Ronson feat. Bruno Mars", a TIDAL row naming only Mark Ronson
        // is incomplete identity; likewise a cover artist cannot pass merely
        // because the title and duration happen to align.
        val allRequestedCreditsPresent = want.all { requested ->
            have.any { candidate -> sameArtist(requested, candidate) }
        }
        if (!allRequestedCreditsPresent) return null

        return if (want.size == have.size && have.all { candidate ->
                want.any { requested -> sameArtist(requested, candidate) }
            }) {
            ARTIST_EXACT
        } else {
            ARTIST_REQUESTED_COMPLETE
        }
    }

    /**
     * The credited artists, each as its own list of words.
     *
     * Words rather than one run-together string, so that containment is
     * checked on whole names: "Queen" is inside "Queensrÿche" as text and is
     * not one of its artists, while "Atif Aslam" is genuinely one of
     * "Atif Aslam, Tulsi Kumar". Single letters go — an initialled
     * "A. R. Rahman" and a plain "AR Rahman" are the same person.
     */
    internal fun artistNames(value: String): Set<List<String>> = value
        .lowercase()
        .split(ARTIST_SEPARATORS)
        .map { name ->
            name.split(WORD_SPLIT)
                .map { it.replace(NON_ALNUM, "") }
                .filter { it.length > 1 }
        }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun sameArtist(a: List<String>, b: List<String>) = runOf(a, b) || runOf(b, a)

    /** Whether [outer] contains [inner] as a run of whole words. */
    private fun runOf(outer: List<String>, inner: List<String>): Boolean {
        if (inner.isEmpty() || inner.size > outer.size) return false
        return (0..outer.size - inner.size).any { at ->
            outer.subList(at, at + inner.size) == inner
        }
    }

    // ── Release identity ───────────────────────────────────────────────────

    /**
     * Album/single and year agreement. Different releases can carry the same
     * title, artists and runtime — and covers routinely do — so a stated
     * mismatch is a hard veto. Missing metadata is neutral here; lossless
     * playback asks SourceResolver to enrich it before the final decision.
     */
    private fun releaseScore(target: Target, candidate: Song): Int? {
        var score = 0
        val wantedAlbum = target.albumName?.takeIf { it.isNotBlank() }
        val gotAlbum = candidate.albumName?.takeIf { it.isNotBlank() }
        if (wantedAlbum != null && gotAlbum != null) {
            if (releaseCore(wantedAlbum) != releaseCore(gotAlbum)) return null
            score += ALBUM_EXACT
        }

        val wantedYear = target.releaseYear
        val gotYear = candidate.releaseYear
        if (wantedYear != null && gotYear != null) {
            if (wantedYear != gotYear) return null
            score += YEAR_EXACT
        }
        return score
    }

    /** Case/punctuation/diacritic-insensitive release identity; edition words stay. */
    private fun releaseCore(value: String): String = Normalizer
        .normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .replace(RELEASE_NON_ALNUM, "")

    /**
     * Release-family identity used only by the strict playback-substitution
     * gate. It treats common deluxe/expanded/remastered packages as the same
     * underlying album while keeping genuinely different releases separate.
     */
    private fun sameReleaseFamily(first: String, second: String): Boolean {
        val a = releaseFamilyCore(first)
        val b = releaseFamilyCore(second)
        return a.isNotBlank() && a == b
    }

    private fun releaseFamilyCore(value: String): String {
        val normalized = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        if (normalized.isBlank()) return ""
        val words = normalized.split(Regex("\\s+"))
        val editionAt = words.indexOfFirst { it in RELEASE_EDITION_MARKERS }
        val base = if (editionAt >= 0) words.take(editionAt) else words
        return base.dropLastWhile { it in RELEASE_TRAILING_ARTICLES }.joinToString("")
    }

    private fun looksLikeCover(title: String, album: String?): Boolean {
        val haystack = "$title ${album.orEmpty()}".lowercase()
        return COVER_MARKERS.any { it.containsMatchIn(haystack) }
    }

    // ── Duration ────────────────────────────────────────────────────────────

    /**
     * Points for the runtimes agreeing, or null when they are too far apart to
     * be the same recording.
     *
     * The strongest signal available, and the one that catches what titles
     * cannot: the ten-minute loop, the album-side upload, the snippet. Only
     * consulted when both sides state a runtime — most module rows do, and a
     * queue row usually does.
     */
    private fun durationScore(wanted: Int?, got: Int?): Int? {
        if (wanted == null || got == null) return 0
        val drift = abs(wanted - got)
        return when {
            drift > DURATION_LIMIT_SEC -> null
            drift <= DURATION_TIGHT_SEC -> DURATION_TIGHT
            else -> DURATION_LOOSE
        }
    }

    /** "3:45" or "1:02:03" as whole seconds; null for anything else. */
    internal fun secondsOf(text: String?): Int? {
        val parts = text?.trim()?.split(':')?.takeIf { it.size in 2..3 } ?: return null
        val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
        return numbers.fold(0) { total, part -> total * 60 + part }.takeIf { it > 0 }
    }

    // ── Context ─────────────────────────────────────────────────────────────

    /** A nudge when both listings mention the same film or album in their asides. */
    private fun contextScore(wanted: TitleParts, got: TitleParts): Int =
        if (wanted.context.any { it in got.context }) CONTEXT_SHARED else 0

    // ── Weights ─────────────────────────────────────────────────────────────

    /** Everything that reaches scoring has already matched on title and version. */
    private const val BASE = 100
    private const val ARTIST_EXACT = 30
    private const val ARTIST_REQUESTED_COMPLETE = 18
    private const val ALBUM_EXACT = 35
    private const val YEAR_EXACT = 20
    private const val DURATION_TIGHT = 40
    private const val DURATION_LOOSE = 15
    private const val CONTEXT_SHARED = 20

    /** Within this many seconds is the same master, allowing for trimmed silence. */
    private const val DURATION_TIGHT_SEC = 3

    /**
     * Past this, two tracks sharing a title are not sharing a recording.
     * Wide enough for a fade or an intro a service trims differently, narrow
     * enough to rule out an extended cut or a full-album upload.
     */
    private const val DURATION_LIMIT_SEC = 30
    private const val LOSSLESS_IDENTITY_DURATION_SEC = 3

    private const val BRACKET_PASSES = 3
    private const val DASH_PASSES = 3

    private val BRACKETED = Regex("""[(\[]([^()\[\]]*)[)\]]""")
    private val DASH = Regex("""\s+[-–—|]+\s+""")
    private val FEATURING = Regex("""\b(feat|ft|featuring|with)\b.*""")
    private val WORD_SPLIT = Regex("""[\s.·]+""")
    private val NON_ALNUM = Regex("""[^a-z0-9]""")
    private val DIACRITICS = Regex("""\p{M}+""")
    private val RELEASE_NON_ALNUM = Regex("""[^a-z0-9]""")
    private val ARTIST_SEPARATORS =
        Regex("""\s*(?:[,&/;·|]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""")

    private val RELEASE_EDITION_MARKERS = setOf(
        "deluxe", "expanded", "anniversary", "edition", "complete", "special",
        "bonus", "legacy", "collector", "collectors", "platinum", "remaster", "remastered",
    )
    private val RELEASE_TRAILING_ARTICLES = setOf("the", "a", "an")
    private val COVER_MARKERS = listOf(
        Regex("\\bcover\\b"),
        Regex("\\btribute\\b"),
        Regex("\\bkaraoke\\b"),
        Regex("\\bsound[ -]?alike\\b"),
        Regex("\\bin the style of\\b"),
        Regex("\\bmade famous by\\b"),
        Regex("\\bbacking track\\b"),
    )

    /**
     * What makes a listing a different recording rather than a different
     * listing of the same one. A title carrying one of these on one side only
     * is refused outright.
     */
    private val VERSION_WORDS = setOf(
        "remix", "remixes", "rmx", "refix", "flip", "bootleg", "mashup", "medley",
        "live", "concert", "unplugged", "acoustic", "instrumental", "karaoke",
        // A stem is not the song. "Vocals Only", "Acapella", "Backing Track"
        // and friends carry the right title and the right artist and are not
        // remotely the recording anybody asked for.
        "vocals", "vocal", "acapella", "acappella", "backing", "stems", "stem",
        // "cover" is intentionally not a global veto. Covers remain valid
        // catalogue recordings when the user actually selected that artist/
        // release; artist + album + year decide whether it is the requested one.
        "demo", "reprise", "remake", "rework", "extended", "edit",
        "version", "mix", "dub", "vip", "session", "sessions",
        "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "symphonic",
        "part", "pt", "chapter",
    )

    /**
     * Asides that read like a version and describe the ordinary release. The
     * exception list to [VERSION_WORDS] — without it, "Song (Album Version)"
     * and "Song" would be two different recordings.
     */
    private val NEUTRAL_SEGMENTS = setOf(
        "albumversion", "originalversion", "originalmix", "singleversion",
        "radioversion", "radioedit", "stereoversion", "monoversion",
        "studioversion", "fullversion", "standardversion", "explicitversion",
        "deluxeversion", "originaltrack",
    )

    /** Packaging words, worth nothing as a tie-break because everything has them. */
    private val NOISE_WORDS = setOf(
        "official", "video", "audio", "lyrics", "lyric", "lyrical", "visualizer",
        "song", "songs", "full", "music", "the", "and", "from", "feat", "ft",
        "featuring", "with", "new", "latest", "free", "download", "remaster",
        "remastered", "explicit", "clean", "bonus", "track", "deluxe", "original",
        "album", "single", "hd", "hq", "4k", "mp3",
    )

    /** Trailing labels an upload hangs on a title with no brackets to hold them. */
    private val TRAILING_NOISE = setOf(
        "song", "songs", "video", "audio", "lyrics", "lyric", "lyrical",
        "official", "full", "hd", "hq", "4k", "mp3", "ost", "soundtrack",
    )

    /** Dropped from the core so that "Jack and Jill" and "Jack & Jill" are one title. */
    private val JOINING_WORDS = setOf("and")
}
