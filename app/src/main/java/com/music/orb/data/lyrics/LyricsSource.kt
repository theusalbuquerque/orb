package com.music.orb.data.lyrics

/**
 * Lyrics providers in their effective lookup priority (highest priority first).
 * A disabled or unavailable provider is skipped in favour of the next one.
 */
enum class LyricsSource(
    val label: String,
    val detail: String,
    /** Whether this source can return per-word/per-syllable timing. */
    val wordSynced: Boolean,
) {
    BINI_LYRICS("BiniLyrics", "Apple Music timings, matched to the recording with ISRC", true),
    LRCLIB("LRCLIB", "Reliable whole-line synchronized lyrics", false),
    BETTER_LYRICS("BetterLyrics", "Apple Music timings, word by word", true),
    BETTER_LYRICS_PORTATO("BetterLyrics Portato", "QQ Music karaoke timings through BetterLyrics", true),
    LYRICS_PLUS("LyricsPlus", "Syllable-by-syllable lyrics from community mirrors", true),
    SIMP_MUSIC("SimpMusic", "Matched to the exact playing video", true),
    MUSIXMATCH("Musixmatch", "Whole-line lyrics from the Musixmatch catalogue", false),
    UNISON("Unison", "Listener-contributed TTML, LRC and plain lyrics", true),
    MEGALOBIZ("Megalobiz", "Community-made, whole-line LRC", false),
    KUGOU("KuGou", "Whole-line lyrics with broad international coverage", false),
    YOUTUBE_TRANSCRIPT("YouTube captions", "Timed captions matched to the exact playing video", false),
}
