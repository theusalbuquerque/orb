package com.music.orb.download

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Add a real segment index to a locally saved, fragmented MP4 (for example the
 * FLAC-in-MP4 renditions delivered over DASH). Merely concatenating the DASH
 * initialization and media fragments produces a file that can play linearly,
 * but Media3's FragmentedMp4Extractor has no seek map without a `sidx` atom.
 *
 * This works on the complete OFFLINE file. It reads only atom headers and
 * decode-time metadata; audio samples are copied verbatim, with no conversion,
 * loss of bitrate or change to the FLAC frames. The rewrite goes to a sibling
 * temp file and replaces the original only after it has been fully flushed.
 */
internal object FragmentedMp4SeekIndex {
    private data class Atom(val start: Long, val size: Long, val header: Int, val type: String) {
        val end: Long get() = start + size
        val content: Long get() = start + header
    }
    private data class Fragment(val start: Long, val decodeTime: Long, val duration: Long?)

    /** Returns true if the file is already indexed or has just been indexed. */
    fun ensureIndexed(file: File): Boolean {
        if (!file.isFile || !(file.name.endsWith(".m4a", ignoreCase = true) || file.name.endsWith(".m4a.part", ignoreCase = true))) return false
        val plan = RandomAccessFile(file, "r").use { raf ->
            val atoms = atoms(raf, 0L, raf.length())
            if (atoms.any { it.type == "sidx" }) return true
            val firstMoof = atoms.indexOfFirst { it.type == "moof" }
            if (firstMoof < 0) {
                // Ordinary non-fragmented MP4 is indexed by its moov sample table.
                // It must not be downgraded merely because it lacks a DASH sidx.
                return atoms.any { it.type == "moov" } && atoms.any { it.type == "mdat" }
            }
            val moov = atoms.firstOrNull { it.type == "moov" } ?: return false
            val timescale = mediaTimescale(raf, moov) ?: return false
            if (timescale <= 0L || timescale > 0xFFFF_FFFFL) return false
            val moofs = atoms.drop(firstMoof).filter { it.type == "moof" }
            if (moofs.isEmpty() || moofs.size > 0xFFFF) return false
            val fragments = moofs.map { moof ->
                val traf = atoms(raf, moof.content, moof.end).firstOrNull { it.type == "traf" }
                    ?: return false
                val children = atoms(raf, traf.content, traf.end)
                val tfdt = children.firstOrNull { it.type == "tfdt" } ?: return false
                val decodeTime = decodeTime(raf, tfdt) ?: return false
                Fragment(moof.start, decodeTime, fragmentDuration(raf, children))
            }
            if (fragments.first().start < moov.end) return false
            val references = fragments.indices.map { index ->
                val fragment = fragments[index]
                val end = fragments.getOrNull(index + 1)?.start ?: raf.length()
                val size = end - fragment.start
                val duration = fragment.duration
                    ?: fragments.getOrNull(index + 1)?.let { it.decodeTime - fragment.decodeTime }
                    ?: fragments.getOrNull(index - 1)?.let { fragment.decodeTime - it.decodeTime }
                    ?: return false
                if (size !in 1L..0x7FFF_FFFFL || duration !in 1L..0xFFFF_FFFFL) return false
                size to duration
            }
            val sidx = buildSidx(timescale, fragments.first().decodeTime, references)
            val start = fragments.first().start
            if (start > raf.length()) return false
            start to sidx
        }
        val (insertAt, sidx) = plan
        val temporary = File(file.parentFile, "${file.name}.seekindex.tmp")
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(temporary).use { output ->
                    copyExactly(input, output, insertAt)
                    output.write(sidx)
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            if (temporary.length() != file.length() + sidx.size) return false
            // Atomic replacement prevents a process interruption from leaving
            // a half-indexed track that looks like a completed download.
            try {
                Files.move(
                    temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return true
        } finally {
            temporary.delete()
        }
    }

    private fun copyExactly(input: FileInputStream, output: FileOutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) error("Incomplete fragmented MP4")
            output.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun atoms(file: RandomAccessFile, start: Long, end: Long): List<Atom> {
        val result = ArrayList<Atom>()
        var position = start
        while (position + 8 <= end && result.size < 200_000) {
            file.seek(position)
            val size32 = file.readInt().toLong() and 0xFFFF_FFFFL
            val nameBytes = ByteArray(4)
            file.readFully(nameBytes)
            val name = String(nameBytes, Charsets.ISO_8859_1)
            val header = if (size32 == 1L) 16 else 8
            val size = when (size32) {
                0L -> end - position
                1L -> if (position + 16 <= end) file.readLong() else break
                else -> size32
            }
            if (size < header || size > end - position) break
            result.add(Atom(position, size, header, name))
            position += size
        }
        return result
    }

    private fun mediaTimescale(file: RandomAccessFile, moov: Atom): Long? {
        for (trak in atoms(file, moov.content, moov.end).filter { it.type == "trak" }) {
            val mdia = atoms(file, trak.content, trak.end).firstOrNull { it.type == "mdia" }
                ?: continue
            val mdhd = atoms(file, mdia.content, mdia.end).firstOrNull { it.type == "mdhd" }
                ?: continue
            val handler = atoms(file, mdia.content, mdia.end).firstOrNull { it.type == "hdlr" }
            if (handler != null) {
                file.seek(handler.content + 8) // fullbox(4) + pre_defined(4)
                val handlerType = ByteArray(4)
                file.readFully(handlerType)
                if (String(handlerType, Charsets.ISO_8859_1) != "soun") continue
            }
            file.seek(mdhd.content)
            val version = file.readUnsignedByte()
            val timescaleAt = mdhd.content + if (version == 1) 20 else 12
            if (timescaleAt + 4 > mdhd.end) continue
            file.seek(timescaleAt)
            return file.readInt().toLong() and 0xFFFF_FFFFL
        }
        return null
    }

    private fun decodeTime(file: RandomAccessFile, tfdt: Atom): Long? {
        if (tfdt.size < 16) return null
        file.seek(tfdt.content)
        val version = file.readUnsignedByte()
        file.skipBytes(3)
        return if (version == 1) {
            if (tfdt.content + 12 > tfdt.end) null else file.readLong().takeIf { it >= 0 }
        } else {
            file.readInt().toLong() and 0xFFFF_FFFFL
        }
    }

    private fun fragmentDuration(file: RandomAccessFile, trafChildren: List<Atom>): Long? {
        val tfhd = trafChildren.firstOrNull { it.type == "tfhd" }
        val defaultDuration = tfhd?.let {
            file.seek(it.content)
            file.readUnsignedByte()
            val flags = (file.readUnsignedByte() shl 16) or
                (file.readUnsignedByte() shl 8) or file.readUnsignedByte()
            file.skipBytes(4) // track_ID
            if ((flags and 0x000001) != 0) file.skipBytes(8)
            if ((flags and 0x000002) != 0) file.skipBytes(4)
            if ((flags and 0x000008) != 0 && file.filePointer + 4 <= it.end) {
                file.readInt().toLong() and 0xFFFF_FFFFL
            } else null
        }
        var sum = 0L
        var samples = 0L
        for (trun in trafChildren.filter { it.type == "trun" }) {
            file.seek(trun.content)
            file.readUnsignedByte()
            val flags = (file.readUnsignedByte() shl 16) or
                (file.readUnsignedByte() shl 8) or file.readUnsignedByte()
            val count = file.readInt().toLong() and 0xFFFF_FFFFL
            if (count > 1_000_000L) return null
            if ((flags and 0x000001) != 0) file.skipBytes(4)
            if ((flags and 0x000004) != 0) file.skipBytes(4)
            if ((flags and 0x000100) != 0) {
                repeat(count.toInt()) {
                    if (file.filePointer + 4 > trun.end) return null
                    sum += file.readInt().toLong() and 0xFFFF_FFFFL
                    if ((flags and 0x000200) != 0) file.skipBytes(4)
                    if ((flags and 0x000400) != 0) file.skipBytes(4)
                    if ((flags and 0x000800) != 0) file.skipBytes(4)
                }
            } else {
                val duration = defaultDuration ?: return null
                sum += count * duration
            }
            samples += count
        }
        return sum.takeIf { samples > 0 && it > 0 }
    }

    internal fun buildSidx(
        timescale: Long,
        earliestTime: Long,
        references: List<Pair<Long, Long>>,
    ): ByteArray {
        require(timescale in 1..0xFFFF_FFFFL)
        require(earliestTime >= 0L)
        require(references.size in 1..0xFFFF)
        val buffer = ByteBuffer.allocate(40 + 12 * references.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(buffer.capacity())
        buffer.put("sidx".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0x01000000) // version 1
        buffer.putInt(1) // reference_ID: audio stream
        buffer.putInt(timescale.toInt())
        buffer.putLong(earliestTime)
        buffer.putLong(0L) // first_offset: first moof follows immediately
        buffer.putShort(0)
        buffer.putShort(references.size.toShort())
        for ((size, duration) in references) {
            require(size in 1..0x7FFF_FFFFL && duration in 1..0xFFFF_FFFFL)
            buffer.putInt(size.toInt())
            buffer.putInt(duration.toInt())
            buffer.putInt(0x90000000.toInt()) // starts_with_SAP, SAP type 1
        }
        return buffer.array()
    }
}
