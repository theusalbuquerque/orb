package com.music.orb

import com.music.orb.data.model.Song
import com.music.orb.playback.isOrderedAlbumQueue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumPlaybackIntentTest {
    private fun song(
        id: String,
        release: String = "album:solace",
        explicitlyAlbum: Boolean = true,
        playlist: String? = null,
    ) = Song(
        videoId = id,
        title = id,
        artist = "RÜFÜS DU SOL",
        thumbnailUrl = null,
        albumId = release,
        preserveAlbumRelease = explicitlyAlbum,
        sourcePlaylistId = playlist,
    )

    @Test fun albumPlayInOriginalOrderDisablesAutomix() {
        assertTrue(listOf(song("a"), song("b"), song("c")).isOrderedAlbumQueue())
    }

    @Test fun playlistContainingAnAlbumStillAllowsAutomix() {
        assertFalse(listOf(song("a", explicitlyAlbum = false, playlist = "PL123"),
            song("b", explicitlyAlbum = false, playlist = "PL123")).isOrderedAlbumQueue())
    }

    @Test fun mixedReleaseAndUnmarkedQueuesNeverCountAsAlbumPlay() {
        assertFalse(listOf(song("a"), song("b", release = "other")).isOrderedAlbumQueue())
        assertFalse(listOf(song("a"), song("b", explicitlyAlbum = false)).isOrderedAlbumQueue())
        assertFalse(listOf(song("a")).isOrderedAlbumQueue())
    }
}
