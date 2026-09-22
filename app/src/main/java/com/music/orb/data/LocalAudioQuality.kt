package com.music.orb.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.music.orb.data.model.Song
import com.music.orb.data.sources.StreamFormat
import java.util.concurrent.ConcurrentHashMap

object LocalAudioQuality {
    private val cache = ConcurrentHashMap<String, StreamFormat>()

    fun cached(song: Song): StreamFormat? = key(song)?.let(cache::get)

    fun inspect(context: Context, song: Song): StreamFormat? {
        val key = key(song) ?: return null
        cache[key]?.let { return it }
        val extractor = MediaExtractor()
        return try {
            when {
                !song.localUri.isNullOrBlank() -> extractor.setDataSource(context, Uri.parse(song.localUri), null)
                !song.localPath.isNullOrBlank() -> extractor.setDataSource(song.localPath)
                else -> return null
            }
            var found: StreamFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val codec = when {
                    "flac" in mime -> "flac"
                    "alac" in mime -> "alac"
                    "raw" in mime || "wav" in mime -> "wav"
                    else -> null
                }
                val sampleRate = runCatching {
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else null
                }.getOrNull()
                val bitDepth = sequenceOf("bits-per-sample", "bit-width", "bitsPerSample")
                    .mapNotNull { name ->
                        runCatching { if (format.containsKey(name)) format.getInteger(name) else null }.getOrNull()
                    }
                    .firstOrNull { it > 0 }
                found = StreamFormat(codec = codec, sampleRateHz = sampleRate, bitDepth = bitDepth)
                break
            }
            found?.also { cache[key] = it }
        } finally {
            extractor.release()
        }
    }

    private fun key(song: Song): String? =
        song.localUri?.let { "u:$it" } ?: song.localPath?.let { "p:$it" }
}
