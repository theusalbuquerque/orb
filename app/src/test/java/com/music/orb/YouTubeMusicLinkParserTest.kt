package com.music.orb

import com.music.orb.playback.YouTubeMusicLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicLinkParserTest {
    @Test fun recognizesSharedTrackLinks() {
        assertEquals("dQw4w9WgXcQ", YouTubeMusicLinkParser.videoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc"))
        assertEquals("dQw4w9WgXcQ", YouTubeMusicLinkParser.videoId("http://music.youtube.com/watch?si=z&v=dQw4w9WgXcQ"))
    }

    @Test fun rejectsUnrelatedAndMaliciousLinks() {
        for (value in listOf(
            "https://evil.example/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/playlist?list=PLfoo",
            "https://music.youtube.com/watch?v=bad",
            "javascript:alert(1)",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ%26unsafe=1",
        )) assertNull(value, YouTubeMusicLinkParser.videoId(value))
    }
}
