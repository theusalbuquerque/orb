package com.music.orb.data.lyrics

/**
 * Databases Orb may query for lyrics. Declaration order is the default
 * priority used by [LyricsRepository].
 */
enum class LyricsSource(
    val label: String,
    val detail: String,
    /** Whether this source can return per-word/per-syllable timing. */
    val wordSynced: Boolean,
) {
    BINI_LYRICS(
        label = "BiniLyrics",
        detail = "Apple Music timings, matched to the recording with ISRC",
        wordSynced = true,
    ),
    BETTER_LYRICS(
        label = "BetterLyrics",
        detail = "Apple Music timings, word by word",
        wordSynced = true,
    ),
    BETTER_LYRICS_PORTATO(
        label = "BetterLyrics Portato",
        detail = "QQ Music karaoke timings through BetterLyrics",
        wordSynced = true,
    ),
    PAXSENIX(
        label = "PaxSenix",
        detail = "Apple Music timings through the keyless provider",
        wordSynced = true,
    ),
    PAXSENIX_SPOTIFY(
        label = "PaxSenix: Spotify",
        detail = "Spotify lyrics through PaxSenix; API key required",
        wordSynced = false,
    ),
    PAXSENIX_MUSIXMATCH(
        label = "PaxSenix: Musixmatch",
        detail = "Musixmatch timings through PaxSenix; API key required",
        wordSynced = true,
    ),
    LYRICS_PLUS(
        label = "LyricsPlus",
        detail = "Syllable-by-syllable lyrics from community mirrors",
        wordSynced = true,
    ),
    SIMP_MUSIC(
        label = "SimpMusic",
        detail = "Matched to the exact playing video",
        wordSynced = true,
    ),
    UNISON(
        label = "Unison",
        detail = "Listener-contributed TTML, LRC and plain lyrics",
        wordSynced = true,
    ),
    YOUTUBE_TRANSCRIPT(
        label = "YouTube captions",
        detail = "Timed captions matched to the exact playing video",
        wordSynced = false,
    ),
    YOUTUBE_MUSIC(
        label = "YouTube Music",
        detail = "Plain lyrics from the playing video's Lyrics tab",
        wordSynced = false,
    ),
    MEGALOBIZ(
        label = "Megalobiz",
        detail = "Community-made, whole-line LRC",
        wordSynced = false,
    ),
    KUGOU(
        label = "KuGou",
        detail = "Whole-line lyrics with broad international coverage",
        wordSynced = false,
    ),
    LRCLIB(
        label = "LRCLIB",
        detail = "Reliable whole-line synchronized lyrics",
        wordSynced = false,
    ),
    MUSIXMATCH(
        label = "Musixmatch",
        detail = "Whole-line lyrics from the Musixmatch catalogue",
        wordSynced = false,
    ),
    GENIUS(
        label = "Genius",
        detail = "Plain-text fallback with a very large catalogue",
        wordSynced = false,
    ),
}
