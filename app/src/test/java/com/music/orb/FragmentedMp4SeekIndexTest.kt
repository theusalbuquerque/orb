package com.music.orb

import com.music.orb.download.FragmentedMp4SeekIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Small synthetic DASH-style FLAC fragments, without an sidx to begin with. */
class FragmentedMp4SeekIndexTest {
    @Test
    fun `offline fragmented MP4 becomes seekable without changing media frames`() {
        val folder = Files.createTempDirectory("orb-index-test").toFile()
        try {
            val file = folder.resolve("test.m4a")
            val ftyp = box("ftyp", "iso6".toByteArray() + ByteArray(8))
            val mdhd = fullbox("mdhd", 0, 0, ints(0, 0, 48_000, 192_000) + ByteArray(4))
            val hdlr = fullbox("hdlr", 0, 0, ints(0) + "soun".toByteArray() + ByteArray(12))
            val moov = box("moov", box("trak", box("mdia", mdhd + hdlr)))
            val fragments = (0 until 4).map { n ->
                val tfhd = fullbox("tfhd", 0, 8, ints(1, 48_000))
                val tfdt = fullbox("tfdt", 1, 0, longs(n * 96_000L))
                val trun = fullbox("trun", 0, 0, ints(2))
                box("moof", box("traf", tfhd + tfdt + trun)) + box("mdat", ByteArray(512) { n.toByte() })
            }
            val before = ftyp + moov + fragments.fold(ByteArray(0)) { all, segment -> all + segment }
            file.writeBytes(before)
            assertTrue(FragmentedMp4SeekIndex.ensureIndexed(file))
            val result = file.readBytes()
            val inserted = result.copyOfRange(ftyp.size + moov.size, ftyp.size + moov.size + 40 + 4 * 12)
            assertEquals("sidx", String(inserted.copyOfRange(4, 8), Charsets.US_ASCII))
            assertEquals(48_000, ByteBuffer.wrap(inserted, 16, 4).order(ByteOrder.BIG_ENDIAN).int)
            assertEquals(4, ByteBuffer.wrap(inserted, 38, 2).order(ByteOrder.BIG_ENDIAN).short.toInt())
            // The MP4 audio packets are byte-identical; only the segment index was added.
            assertArrayEquals(before.copyOfRange(ftyp.size + moov.size, before.size), result.copyOfRange(ftyp.size + moov.size + inserted.size, result.size))
            assertTrue(FragmentedMp4SeekIndex.ensureIndexed(file))
            assertArrayEquals(result, file.readBytes())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `invalid MP4 is left unchanged`() {
        val folder = Files.createTempDirectory("orb-invalid-index-test").toFile()
        try {
            val file = folder.resolve("invalid.m4a")
            val bytes = "not an MP4".toByteArray()
            file.writeBytes(bytes)
            assertFalse(FragmentedMp4SeekIndex.ensureIndexed(file))
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(4 * values.size)
        .order(ByteOrder.BIG_ENDIAN).also { buffer -> values.forEach(buffer::putInt) }.array()

    private fun longs(vararg values: Long): ByteArray = ByteBuffer.allocate(8 * values.size)
        .order(ByteOrder.BIG_ENDIAN).also { buffer -> values.forEach(buffer::putLong) }.array()

    private fun box(type: String, payload: ByteArray): ByteArray = ints(payload.size + 8) + type.toByteArray(Charsets.US_ASCII) + payload

    private fun fullbox(type: String, version: Int, flags: Int, body: ByteArray): ByteArray =
        box(type, ints((version shl 24) or flags) + body)
}
