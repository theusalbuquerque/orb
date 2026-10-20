package com.music.orb

import com.music.orb.data.model.Song
import com.music.orb.data.sources.TrackMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for one recording sold both on its album and as a single. */
class TrackMatcherOfflineDownloadTest {
    private val original = TrackMatcher.Target(
        title = "Underwater",
        artist = "RÜFÜS DU SOL",
        durationSec = 349,
        albumName = "SOLACE",
        releaseYear = 2018,
    )

    private fun candidate(
        album: String = "Underwater",
        title: String = "Underwater",
        artist: String = "RÜFÜS DU SOL",
        duration: String? = "5:49",
        year: Int? = null,
    ) = Song(
        videoId = "src:tidal::single",
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        albumName = album,
        releaseYear = year,
    )

    @Test fun `offline single is allowed after all identity checks`() {
        assertTrue(TrackMatcher.verifiedForSubstitution(candidate(), original, allowSingleAlbumRelease = true))
        assertTrue(TrackMatcher.verifiedForSubstitution(candidate(album = "Underwater - Single"), original, allowSingleAlbumRelease = true))
        assertTrue(TrackMatcher.verifiedForSubstitution(candidate(album = "Underwater", year = 2018), original, allowSingleAlbumRelease = true))
    }

    @Test fun `playback substitution remains release strict`() {
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(), original))
    }

    @Test fun `different release cannot disguise itself as the single`() {
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(album = "Underwater Sessions"), original, true))
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(album = "Underwater - EP"), original, true))
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(album = "Greatest Dance Hits"), original, true))
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(album = "Underwater Remixes"), original, true))
    }

    @Test fun `offline exception still rejects wrong master`() {
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(title = "Underwater (Remix)"), original, true))
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(title = "Underwater (Live)"), original, true))
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(title = "Underwater (Cover)"), original, true))
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(artist = "Another Artist"), original, true))
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(duration = "5:55"), original, true))
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(duration = null), original, true))
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(year = 2023), original, true))
        assertFalse(TrackMatcher.verifiedForSubstitution(candidate(), original.copy(durationSec = null), true))
    }
}
