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

        return parseFlac(bytes) ?: parseWave(bytes)
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

        val o = 8
        val b10 = bytes[o + 10].toInt() and 0xff
        val b11 = bytes[o + 11].toInt() and 0xff
        val b12 = bytes[o + 12].toInt() and 0xff
        val b13 = bytes[o + 13].toInt() and 0xff
        val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
        val bitsPerSample = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1

        return StreamFormat(
            codec = "flac",
            sampleRateHz = sampleRate.takeIf { it > 0 },
            bitDepth = bitsPerSample.takeIf { it > 0 },
        )
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
