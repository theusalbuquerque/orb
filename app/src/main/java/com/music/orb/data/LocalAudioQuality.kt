package com.music.orb.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.music.orb.data.model.Song
import com.music.orb.data.sources.StreamFormat
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Native quality metadata for audio that already lives on the device.
 *
 * This deliberately reads the *file/container*, not the decoder's working PCM
 * format. Android is allowed to decode a perfectly ordinary 16-bit FLAC into
 * 32-bit float internally; treating that working precision as source bit depth
 * is what made CD-quality local files appear as Hi-Res Lossless.
 */
object LocalAudioQuality {

    private const val HEADER_BYTES = 64 * 1024

    private val positive = ConcurrentHashMap<String, StreamFormat>()
    private val negative = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** A re-download may replace the exact same filename with a new codec. */
    fun invalidate(uri: String?) {
        val value = uri?.takeIf { it.isNotBlank() } ?: return
        val key = "u:$value"
        positive.remove(key)
        negative.remove(key)
    }

    fun cached(song: Song): StreamFormat? = cached(song.localUri, song.localPath)

    fun cached(uri: String?, path: String?): StreamFormat? {
        val key = key(uri, path) ?: return null
        return positive[key]
    }

    /**
     * Inspect once and cache for the process. A null answer is cached too so a
     * malformed/unsupported file is not reopened every five-second player tick.
     */
    fun inspect(context: Context, song: Song): StreamFormat? =
        inspect(context, song.localUri, song.localPath)

    fun inspect(context: Context, uriString: String?, localPath: String?): StreamFormat? {
        val cacheKey = key(uriString, localPath) ?: return null
        positive[cacheKey]?.let { return it }
        if (negative.contains(cacheKey)) return null

        val detected = runCatching {
            detectFromHeader(context, uriString, localPath)
                ?: detectWithExtractor(context, uriString, localPath)
        }.getOrNull()

        if (detected != null) positive[cacheKey] = detected else negative += cacheKey
        return detected
    }

    private fun key(uri: String?, path: String?): String? = when {
        !uri.isNullOrBlank() -> "u:$uri"
        !path.isNullOrBlank() -> "p:$path"
        else -> null
    }

    /** Exact parsers for the two local lossless containers Orb already accepts most often. */
    private fun detectFromHeader(
        context: Context,
        uriString: String?,
        localPath: String?,
    ): StreamFormat? {
        val bytes = open(context, uriString, localPath)?.use { input ->
            val out = ByteArray(HEADER_BYTES)
            var count = 0
            while (count < out.size) {
                val read = input.read(out, count, out.size - count)
                if (read <= 0) break
                count += read
            }
            out.copyOf(count)
        } ?: return null

        return parseFlac(bytes) ?: parseWave(bytes) ?: parseFragmentedMp4Flac(bytes)
    }

    /**
     * FLAC STREAMINFO is authoritative for sample rate and bits per sample.
     * Layout: 4-byte `fLaC`, a 4-byte metadata header, then the 34-byte
     * STREAMINFO block. The 20-bit rate and 5-bit depth share bytes 10..13.
     */
    internal fun parseFlac(bytes: ByteArray): StreamFormat? {
        if (bytes.size < 42 || String(bytes, 0, 4, Charsets.US_ASCII) != "fLaC") return null
        val blockType = bytes[4].toInt() and 0x7f
        val length = ((bytes[5].toInt() and 0xff) shl 16) or
            ((bytes[6].toInt() and 0xff) shl 8) or
            (bytes[7].toInt() and 0xff)
        if (blockType != 0 || length < 34 || bytes.size < 8 + length) return null

        return parseStreamInfo(bytes, 8)
    }

    /**
     * Read the FLACSpecificBox in an MP4 initialization segment. For DASH the
     * file starts with ftyp and contains the fLaC sample entry with a dfLa box.
     * Its payload has four version/flags bytes followed by native FLAC metadata
     * blocks; do not treat the Android decoder's PCM format as source depth.
     *
     * Some muxers prefix their STREAMINFO metadata with the fLaC marker and
     * others expose the raw 34-byte STREAMINFO without a metadata-block header.
     * These cases are supported without guessing the bit depth from a bitrate.
     */
    internal fun parseFragmentedMp4Flac(bytes: ByteArray): StreamFormat? {
        if (bytes.size < 48 || String(bytes, 4, 4, Charsets.US_ASCII) != "ftyp") return null
        val marker = "dfLa".toByteArray(Charsets.US_ASCII)
        for (offset in 4 until bytes.size - marker.size) {
            if (!marker.indices.all { index -> bytes[offset + index] == marker[index] }) continue
            // ISO BMFF box size precedes type; dfLa is a FullBox (version/flags).
            if (offset < 4) continue
            val boxSize = bigEndianInt(bytes, offset - 4)
            if (boxSize < 12 || boxSize.toLong() > bytes.size.toLong() - (offset - 4)) continue
            val payload = offset + 8
            val end = offset - 4 + boxSize
            if (payload >= end) continue
            // Native FLAC marker (if present), then a complete metadata block.
            if (payload + 4 <= end && String(bytes, payload, 4, Charsets.US_ASCII) == "fLaC") {
                parseFlac(bytes.copyOfRange(payload, end))?.let { return it }
            }
            // Standard FLACSpecificBox: STREAMINFO metadata block header + 34 bytes.
            if (payload + 38 <= end) {
                val wrapped = ByteArray(42)
                "fLaC".toByteArray(Charsets.US_ASCII).copyInto(wrapped)
                bytes.copyInto(wrapped, 4, payload, payload + 38)
                parseFlac(wrapped)?.let { return it }
            }
            // Some decoders expose the STREAMINFO block directly (34 bytes).
            if (end - payload == 34) {
                parseStreamInfo(bytes, payload)?.let { return it }
            }
        }
        return null
    }

    /** FLAC STREAMINFO: sample rate (20 bits), channels (3), depth (5). */
    private fun parseStreamInfo(bytes: ByteArray, start: Int): StreamFormat? {
        if (start < 0 || start + 18 > bytes.size) return null
        val a = bytes[start + 10].toInt() and 0xff
        val b = bytes[start + 11].toInt() and 0xff
        val c = bytes[start + 12].toInt() and 0xff
        val d = bytes[start + 13].toInt() and 0xff
        val sampleRate = (a shl 12) or (b shl 4) or (c ushr 4)
        val bitDepth = (((c and 1) shl 4) or (d ushr 4)) + 1
        // Reject junk or shifted offsets rather than advertising invented Hi-Res.
        if (sampleRate !in 8_000..384_000 || bitDepth !in 4..32) return null
        return StreamFormat(codec = "flac", sampleRateHz = sampleRate, bitDepth = bitDepth)
    }

    private fun bigEndianInt(bytes: ByteArray, start: Int): Int {
        if (start < 0 || start + 4 > bytes.size) return -1
        return ((bytes[start].toInt() and 0xff) shl 24) or
            ((bytes[start + 1].toInt() and 0xff) shl 16) or
            ((bytes[start + 2].toInt() and 0xff) shl 8) or
            (bytes[start + 3].toInt() and 0xff)
    }

    /** Parses PCM/float WAV and WAVE_FORMAT_EXTENSIBLE `fmt ` metadata. */
    internal fun parseWave(bytes: ByteArray): StreamFormat? {
        if (bytes.size < 12) return null
        val riff = String(bytes, 0, 4, Charsets.US_ASCII)
        val wave = String(bytes, 8, 4, Charsets.US_ASCII)
        if ((riff != "RIFF" && riff != "RF64") || wave != "WAVE") return null

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = littleEndianInt(bytes, offset + 4)
            if (size < 0) return null
            val data = offset + 8
            if (id == "fmt " && size >= 16 && data + 16 <= bytes.size) {
                val formatTag = littleEndianShort(bytes, data)
                val sampleRate = littleEndianInt(bytes, data + 4)
                val containerBits = littleEndianShort(bytes, data + 14)
                // WAVE_FORMAT_EXTENSIBLE can store (for example) valid 24-bit
                // samples in a 32-bit container. The valid-bits field, not the
                // container width, is the native source depth in that case.
                val validBits = if (
                    formatTag == 0xfffe &&
                    size >= 20 &&
                    data + 20 <= bytes.size
                ) {
                    littleEndianShort(bytes, data + 18).takeIf { it > 0 }
                } else {
                    null
                }
                return StreamFormat(
                    codec = "wav",
                    sampleRateHz = sampleRate.takeIf { it > 0 },
                    bitDepth = (validBits ?: containerBits).takeIf { it > 0 },
                )
            }
            val padded = size + (size and 1)
            if (padded <= 0) break
            offset = data + padded
        }
        return null
    }

    /**
     * Fallback for ALAC and containers whose native metadata Android exposes
     * through MediaExtractor. Literal vendor/common keys are used for bit depth
     * so this remains source-compatible even where the SDK lacks a named key.
     */
    private fun detectWithExtractor(
        context: Context,
        uriString: String?,
        localPath: String?,
    ): StreamFormat? {
        val extractor = MediaExtractor()
        try {
            when {
                !uriString.isNullOrBlank() -> extractor.setDataSource(context, Uri.parse(uriString), null)
                !localPath.isNullOrBlank() -> extractor.setDataSource(localPath)
                else -> return null
            }

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val codec = codecFrom(mime, uriString, localPath) ?: continue
                val sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
                if (codec == "flac") {
                    val csd = runCatching { format.getByteBuffer("csd-0")?.duplicate() }.getOrNull()
                    if (csd != null && csd.remaining() in 34..(HEADER_BYTES)) {
                        val raw = ByteArray(csd.remaining())
                        csd.get(raw)
                        val native = parseFlac(raw)
                            ?: if (raw.size >= 38) {
                                val wrapped = ByteArray(raw.size + 4)
                                "fLaC".toByteArray(Charsets.US_ASCII).copyInto(wrapped)
                                raw.copyInto(wrapped, 4)
                                parseFlac(wrapped)
                            } else null
                            ?: if (raw.size == 34) parseStreamInfo(raw, 0) else null
                        if (native != null) return native
                    }
                }
                val bits = sequenceOf("bits-per-sample", "bit-width", "bitsPerSample")
                    .mapNotNull { key -> format.intOrNull(key) }
                    .firstOrNull { it > 0 }
                return StreamFormat(
                    codec = codec,
                    sampleRateHz = sampleRate?.takeIf { it > 0 },
                    bitDepth = bits,
                )
            }
        } finally {
            extractor.release()
        }
        return null
    }

    private fun codecFrom(mime: String, uri: String?, path: String?): String? {
        val lower = mime.lowercase()
        return when {
            "flac" in lower -> "flac"
            "alac" in lower -> "alac"
            lower == "audio/raw" || "wav" in lower -> "wav"
            else -> extension(uri, path)?.takeIf {
                it in setOf("flac", "wav", "aiff", "aif", "alac", "ape", "wv", "dsf", "dff")
            }
        }
    }

    private fun extension(uri: String?, path: String?): String? {
        val value = path ?: uri ?: return null
        return value.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        runCatching { if (containsKey(key)) getInteger(key) else null }.getOrNull()

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > bytes.size) return -1
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return -1
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt() and 0xffff
    }

    private fun open(context: Context, uriString: String?, localPath: String?): InputStream? {
        if (!uriString.isNullOrBlank()) {
            val uri = Uri.parse(uriString)
            runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()?.let { return it }
            if (uri.scheme == "file") {
                uri.path?.let { path -> runCatching { FileInputStream(File(path)) }.getOrNull()?.let { return it } }
            }
        }
        if (!localPath.isNullOrBlank()) {
            runCatching { FileInputStream(File(localPath)) }.getOrNull()?.let { return it }
        }
        return null
    }
}
