package com.music.orb

import com.music.orb.data.model.Song
import com.music.orb.data.sources.LosslessTier
import com.music.orb.data.sources.SourceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every distinct confirmed track counts, independent of playlist position. */
class CollectionLosslessBadgeTest {
    private fun track(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)

    @Test fun threeConfirmedTracksAnywhereGetLosslessBadge() {
        val all = (1..24).map { track("orb-badge-position-$it") }
        val confirmed = listOf(all[5], all[13], all[22])
        try {
            confirmed.forEach { SourceResolver.confirmPlaybackLossless(it, LosslessTier.LOSSLESS) }
            val badge = SourceResolver.cachedCollectionPlayableBadge(
                "Position test", "Artist", "orb-badge-position-list", all,
            )
            assertTrue(badge.losslessTier.isLossless)
            assertFalse(badge.losslessTier.isHiRes)
        } finally {
            confirmed.forEach(SourceResolver::invalidateTrackLossless)
        }
    }

    @Test fun repeatedPlaylistEntryIsNotThreeDistinctSongs() {
        val a = track("orb-badge-duplicate-1")
        try {
            SourceResolver.confirmPlaybackLossless(a, LosslessTier.HI_RES_LOSSLESS)
            val badge = SourceResolver.cachedCollectionPlayableBadge(
                "Duplicates", "Artist", "orb-badge-duplicate-list", listOf(a, a, a),
            )
            assertFalse(badge.losslessTier.isLossless)
        } finally {
            SourceResolver.invalidateTrackLossless(a)
        }
    }

    @Test fun mixedTierRequiresThreeHiResForHiResWordmark() {
        val tracks = (1..3).map { track("orb-badge-mixed-$it") }
        try {
            SourceResolver.confirmPlaybackLossless(tracks[0], LosslessTier.HI_RES_LOSSLESS)
            SourceResolver.confirmPlaybackLossless(tracks[1], LosslessTier.LOSSLESS)
            SourceResolver.confirmPlaybackLossless(tracks[2], LosslessTier.LOSSLESS)
            val badge = SourceResolver.cachedCollectionPlayableBadge(
                "Mixed mastering", "Artist", "orb-badge-mixed-list", tracks,
            )
            assertEquals(LosslessTier.LOSSLESS, badge.losslessTier)
        } finally {
            tracks.forEach(SourceResolver::invalidateTrackLossless)
        }
    }
}
