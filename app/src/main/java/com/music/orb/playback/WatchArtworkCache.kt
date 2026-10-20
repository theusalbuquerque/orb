package com.music.orb.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.music.orb.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Small album-art snapshots for system media metadata (including Wear OS).
 *
 * The watch normally relies on the phone's MediaSession, not the audio API on Render.
 * Persisting a compact JPEG on the phone and embedding it in MediaMetadata avoids
 * a fragile second CDN request when mobile signal dips. The ordinary artworkUri
 * stays attached to the track so clients that prefer a larger cover still work.
 */
internal class WatchArtworkCache(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "watch_artwork_v1")
    private val client = Http.client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun cached(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        readCached(uri)
    }

    suspend fun load(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        readCached(uri)?.let { return@withContext it }
        val image = try {
            val original = when (uri.scheme?.lowercase()) {
                "https", "http" -> {
                    val request = Request.Builder().url(uri.toString()).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        val body = response.body ?: return@withContext null
                        val declared = body.contentLength()
                        if (declared > MAX_INPUT_BYTES) return@withContext null
                        body.byteStream().use { stream -> readBounded(stream, MAX_INPUT_BYTES) }
                    }
                }
                "content", "file" -> appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    readBounded(stream, MAX_INPUT_BYTES)
                }
                else -> null
            } ?: return@withContext null
            compress(original)
        } catch (_: Exception) {
            // Radio handoffs/offline moments are normal, not an image failure to report to UI.
            null
        } ?: return@withContext null

        runCatching {
            directory.mkdirs()
            val destination = diskFile(uri)
            val pending = File(directory, destination.name + ".pending")
            pending.writeBytes(image)
            if (!pending.renameTo(destination)) {
                destination.writeBytes(image)
                pending.delete()
            }
            trim()
        }
        image
    }

    private fun readCached(uri: Uri): ByteArray? = runCatching {
        val file = diskFile(uri)
        if (!file.isFile || file.length() !in 1..MAX_ARTWORK_BYTES.toLong()) return@runCatching null
        file.readBytes().takeIf { BitmapFactory.decodeByteArray(it, 0, it.size) != null }?.also {
            file.setLastModified(System.currentTimeMillis())
        }
    }.getOrNull()

    private fun diskFile(uri: Uri): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.jpg")
    }

    private fun compress(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 384 || bounds.outHeight / sample > 384) sample *= 2
        val source = BitmapFactory.decodeByteArray(
            raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val resized = if (source.width > COVER_PX || source.height > COVER_PX) {
            val ratio = minOf(COVER_PX.toFloat() / source.width, COVER_PX.toFloat() / source.height)
            Bitmap.createScaledBitmap(
                source, (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1), true,
            )
        } else source
        return try {
            ByteArrayOutputStream().use { out ->
                resized.compress(Bitmap.CompressFormat.JPEG, 76, out)
                if (out.size() > MAX_ARTWORK_BYTES) {
                    out.reset()
                    resized.compress(Bitmap.CompressFormat.JPEG, 56, out)
                }
                out.toByteArray().takeIf { it.size in 1..MAX_ARTWORK_BYTES }
            }
        } finally {
            if (resized !== source) resized.recycle()
            source.recycle()
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8_192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (output.size() + n > limit) return null
            output.write(chunk, 0, n)
        }
        return output.toByteArray()
    }

    private fun trim() {
        val files = directory.listFiles { file -> file.isFile && file.extension == "jpg" }
            ?.sortedByDescending(File::lastModified) ?: return
        var total = 0L
        files.forEachIndexed { index, file ->
            total += file.length()
            if (index >= MAX_COVERS || total > MAX_CACHE_BYTES) file.delete()
        }
    }

    private companion object {
        const val COVER_PX = 256
        const val MAX_INPUT_BYTES = 1_500_000
        const val MAX_ARTWORK_BYTES = 90_000
        const val MAX_CACHE_BYTES = 8_000_000L
        const val MAX_COVERS = 80
    }
}
