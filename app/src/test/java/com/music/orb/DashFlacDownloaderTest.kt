package com.music.orb

import com.music.orb.data.LocalAudioQuality
import com.music.orb.download.DashFlacDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DashFlacDownloaderTest {
    @Test
    fun `DASH Maximum selects native 24-bit FLAC and every media segment`() {
        val manifest = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet contentType="audio"><Representation id="FLAC,44100,16" codecs="flac"><SegmentTemplate initialization="cd/0.mp4" media="cd/${'$'}Number${'$'}.mp4"><SegmentTimeline><S d="44100" r="2"/></SegmentTimeline></SegmentTemplate></Representation><Representation id="FLAC,96000,24" codecs="flac"><SegmentTemplate initialization="https://cdn.example/0.mp4?token=t&amp;x=1" media="https://cdn.example/${'$'}Number${'$'}.mp4?token=t&amp;x=1"><SegmentTimeline><S d="96000" r="2"/><S d="48000"/></SegmentTimeline></SegmentTemplate></Representation></AdaptationSet></Period></MPD>"""
        val plan = DashFlacDownloader.plan("https://cdn.example/track.mpd", manifest)
        assertNotNull(plan)
        assertEquals(96000, plan!!.sampleRateHz)
        assertEquals(24, plan.bitDepth)
        assertEquals(4, plan.segments.size)
        assertEquals("https://cdn.example/0.mp4?token=t&x=1", plan.initialization)
        assertEquals("https://cdn.example/4.mp4?token=t&x=1", plan.segments.last())
    }

    @Test
    fun `AAC DASH cannot pass the Lossless downloader`() {
        val manifest = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet contentType="audio"><Representation id="AAC" codecs="mp4a.40.2"><SegmentTemplate initialization="0.mp4" media="${'$'}Number${'$'}.mp4"><SegmentTimeline><S d="48000" r="2"/></SegmentTimeline></SegmentTemplate></Representation></AdaptationSet></Period></MPD>"""
        assertNull(DashFlacDownloader.plan("https://cdn.example/track.mpd", manifest))
    }

    @Test
    fun `fragmented MP4 FLAC keeps original source bit depth and sample rate`() {
        val rate = 96_000
        val depth = 24
        val metadata = ByteArray(38)
        metadata[3] = 34
        metadata[14] = (rate ushr 12).toByte()
        metadata[15] = (rate ushr 4).toByte()
        metadata[16] = (((rate and 15) shl 4) or ((depth - 1) ushr 4)).toByte()
        metadata[17] = (((depth - 1) and 15) shl 4).toByte()
        val mp4 = ByteArray(24) + "dfLa".toByteArray() + ByteArray(4) + metadata
        "ftyp".toByteArray().copyInto(mp4, 4)
        // A real ISO BMFF dfLa box has a big-endian size immediately before its type.
        mp4[23] = 50
        val format = LocalAudioQuality.parseFragmentedMp4Flac(mp4)
        assertEquals("flac", format?.codec)
        assertEquals(depth, format?.bitDepth)
        assertEquals(rate, format?.sampleRateHz)
    }
    @Test
    fun `fragmented MP4 FLAC accepts a 34-byte raw STREAMINFO within dfLa`() {
        val rate = 44_100
        val depth = 16
        val raw = ByteArray(34)
        raw[10] = (rate ushr 12).toByte()
        raw[11] = (rate ushr 4).toByte()
        raw[12] = (((rate and 15) shl 4) or ((depth - 1) ushr 4)).toByte()
        raw[13] = (((depth - 1) and 15) shl 4).toByte()
        val mp4 = ByteArray(24) + "dfLa".toByteArray() + ByteArray(4) + raw
        mp4[23] = 46
        "ftyp".toByteArray().copyInto(mp4, 4)
        val format = LocalAudioQuality.parseFragmentedMp4Flac(mp4)
        assertEquals("flac", format?.codec)
        assertEquals(44_100, format?.sampleRateHz)
        assertEquals(16, format?.bitDepth)
    }

    @Test
    fun `fragmented MP4 parser rejects a fake dfLa marker without an MP4 box`() {
        val invalid = ByteArray(24) + "dfLa".toByteArray() + ByteArray(4) + ByteArray(38)
        "ftyp".toByteArray().copyInto(invalid, 4)
        assertNull(LocalAudioQuality.parseFragmentedMp4Flac(invalid))
    }

}
