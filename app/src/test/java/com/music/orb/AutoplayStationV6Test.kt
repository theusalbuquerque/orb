package com.music.orb

import com.music.orb.data.model.Song
import com.music.orb.playback.AutoplaySession
import com.music.orb.playback.QueueBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayStationV6Test {
    private fun song(id: String, artist: String) =
        Song(videoId = id, title = "Track $id", artist = artist, thumbnailUrl = null)

    @Test fun favoursThreeDifferentArtistsInsteadOfRepeatingTheSeed() {
        val current = song("cur", "Same Artist")
        val previous = listOf(song("x", "Same Artist"), song("y", "Same Artist"))
        val options = listOf(
            song("a", "Same Artist"), song("b", "Same Artist"),
            song("c", "Another Artist"), song("d", "Third Artist"),
            song("e", "Fourth Artist"),
        )
        val selected = QueueBuilder.extendDiverse(listOf(current), options, previous, 3)
        assertEquals(3, selected.size)
        assertEquals(3, selected.map { it.artist }.toSet().size)
        assertFalse(selected.any { it.artist == current.artist })
    }

    @Test fun neverSuggestsAlreadyQueuedOrPlayedRecordingUnderDifferentVideoId() {
        val already = Song("original", "Example (Official Audio)", "Artist", null)
        val alias = Song("video", "Example", "Artist", null)
        val selected = QueueBuilder.extendDiverse(
            existing = listOf(already),
            candidates = listOf(alias, song("fresh", "New Artist")),
            history = emptyList(),
            limit = 3,
        )
        assertEquals(listOf("fresh"), selected.map { it.videoId })
    }

    @Test fun manualRemovalForbidsAlternativeRecordingAndInvalidatesRequest() {
        AutoplaySession.beginUserSelection()
        val target = song("removed-v6", "Artist V6")
        val oldVersion = AutoplaySession.generation
        AutoplaySession.reject(target)
        assertTrue(AutoplaySession.generation > oldVersion)
        assertTrue(AutoplaySession.excluded(target.copy(videoId = "another-upload-v6")))
    }

    @Test fun clearingQueueSuspendsAutoplayUntilExplicitNewSelection() {
        AutoplaySession.pauseAfterClearingQueue()
        assertTrue(AutoplaySession.pausedByUser)
        AutoplaySession.beginUserSelection()
        assertFalse(AutoplaySession.pausedByUser)
    }
}
