package com.music.orb

import com.music.orb.download.orderedOfflineItems
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineCollectionOrderTest {
    @Test fun preservesAlbumTrackOrderRegardlessOfDownloadCompletionOrder() {
        val available = linkedMapOf("track-3" to "C", "track-1" to "A", "track-2" to "B")
        assertEquals(listOf("A", "B", "C"), orderedOfflineItems(listOf("track-1", "track-2", "track-3"), available))
    }

    @Test fun partialDownloadsAndRepeatedPlaylistItemsStayInTheOriginalSequence() {
        val available = mapOf("track-1" to "A", "track-3" to "C")
        assertEquals(listOf("A", "C", "A"), orderedOfflineItems(
            listOf("track-1", "track-2", "track-3", "track-1"), available))
    }
}
