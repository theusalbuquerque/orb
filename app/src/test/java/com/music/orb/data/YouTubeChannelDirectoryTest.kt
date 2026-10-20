package com.music.orb.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeChannelDirectoryTest {
    @Test fun officialChannelIdMustNotBecomePrivateDelegationId() {
        val channels = YouTubeChannelDirectory.parse(
            """{"items":[{"id":"UC123456789","snippet":{"title":"Music Account","customUrl":"@music", "thumbnails":{"high":{"url":"https://example.org/channel.jpg"}}}}]}"""
        )
        assertEquals(1, channels.size)
        assertEquals("Music Account", channels.first().name)
        assertEquals("@music", channels.first().handle)
        assertNull(channels.first().pageId)
        assertTrue(channels.first().isSelected)
        assertTrue(channels.first().fromOfficialApi)
    }

    @Test fun emptyOrInvalidChannelsAreNotInvented() {
        assertTrue(YouTubeChannelDirectory.parse("""{"items":[]}""").isEmpty())
        assertTrue(YouTubeChannelDirectory.parse("""{"items":[{"id":"bad","snippet":{"title":"Fake"}}]}""").isEmpty())
    }
}
