package com.music.orb.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guard against regressions that make an independent FLAC search finish immediately. */
class TidalDownloadManifestRoutingTest {
    private fun invokeString(name: String, input: String): String? {
        val method = TidalService::class.java.getDeclaredMethod(name, String::class.java)
        method.isAccessible = true
        return method.invoke(TidalService, input) as String?
    }

    @Test fun `catalogue 403 never cools down manifests`() {
        val host = "https://hifi-api-workers.orbmusic.workers.dev"
        assertNotEquals(
            invokeString("circuitKey", "$host/search/?s=example"),
            invokeString("circuitKey", "$host/trackManifests/?id=42&usage=DOWNLOAD"),
        )
    }

    @Test fun `manifest download playback and legacy routes have distinct cooldowns`() {
        val host = "https://hifi-api-workers.orbmusic.workers.dev"
        val download = invokeString("circuitKey", "$host/trackManifests/?id=42&usage=DOWNLOAD")
        val playback = invokeString("circuitKey", "$host/trackManifests/?id=42&usage=PLAYBACK")
        val legacy = invokeString("circuitKey", "$host/track/?id=42&quality=LOSSLESS")
        assertNotEquals(download, playback)
        assertNotEquals(playback, legacy)
        assertEquals(download, invokeString("circuitKey", "$host/trackManifests/?id=99&usage=DOWNLOAD"))
    }

    @Test fun `expired signed MPD never blacklists an unrelated MPD`() {
        val host = "https://manifest.example.org"
        assertNotEquals(
            invokeString("circuitKey", "$host/1/manifests/signedA.mpd?token=secret"),
            invokeString("circuitKey", "$host/1/manifests/signedB.mpd?token=secret"),
        )
    }

    @Test fun `reads trackManifests nested attributes URI`() {
        val body = """{"version":"2.7","data":{"data":{"attributes":{"uri":"https://example.org/song.mpd?token=abc"}}}}"""
        assertEquals("https://example.org/song.mpd?token=abc", invokeString("parseTrackManifestUri", body))
    }

    @Test fun `invalid manifest response cannot become a stream`() {
        assertNull(invokeString("parseTrackManifestUri", """{"data":{"status":"denied"}}"""))
    }
}
