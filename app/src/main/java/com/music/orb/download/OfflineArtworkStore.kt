package com.music.orb.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.music.orb.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** Per-track app-private cover art, independent of HTTP image cache eviction. */
internal object OfflineArtworkStore {
    private fun root(context: Context) = File(context.applicationContext.filesDir, "orb_offline_art")

    private fun file(context: Context, id: String): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(root(context), "$key.jpg")
    }

    fun get(context: Context, videoId: String): String? =
        videoId.takeIf { it.isNotBlank() }?.let { id ->
            file(context, id).takeIf { it.isFile && it.length() > 0L }
                ?.let { Uri.fromFile(it).toString() }
        }

    /** Opportunistically replace old, very small covers without requiring re-downloading audio. */
    fun needsRefresh(context: Context, videoId: String): Boolean {
        if (videoId.isBlank()) return false
        val image = file(context, videoId)
        if (!image.isFile) return true
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.absolutePath, bounds)
        return bounds.outWidth < 850 || bounds.outHeight < 850
    }

    suspend fun save(context: Context, videoIds: List<String>, candidates: List<String>): String? =
        withContext(Dispatchers.IO) {
            val ids = videoIds.filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) return@withContext null
            // Real release art preferred; retain YouTube thumbnail as last resort.
            // Request full-size release artwork, not the 60–120px carousel thumbnail.
            // Consider multiple sources: the first HTTP 200 is not necessarily sharp.
            val coverClient = Http.client.newBuilder()
                .callTimeout(5L, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val sourceUrls = candidates.filter { it.startsWith("https://") }
            val urls = (sourceUrls.map { url ->
                url.replace(Regex("w\\d+-h\\d+"), "w1200-h1200")
            } + sourceUrls + ids.filter { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
                .flatMap { listOf(
                    "https://i.ytimg.com/vi/$it/maxresdefault.jpg",
                    "https://i.ytimg.com/vi/$it/sddefault.jpg",
                    "https://i.ytimg.com/vi/$it/hqdefault.jpg",
                ) }).distinct()
            var bitmap: Bitmap? = null
            for (url in urls) {
                val candidate = runCatching {
                    coverClient.newCall(Request.Builder().url(url).build()).execute().use { reply ->
                        if (!reply.isSuccessful) return@use null
                        val raw = reply.body?.bytes() ?: return@use null
                        BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    }
                }.getOrNull() ?: continue
                if (candidate.width < 300 || candidate.height < 300) {
                    candidate.recycle()
                    continue
                }
                val current = bitmap
                if (current == null || minOf(candidate.width, candidate.height) >
                    minOf(current.width, current.height)) {
                    current?.recycle()
                    bitmap = candidate
                } else candidate.recycle()
                val selected = bitmap ?: continue
                if (minOf(selected.width, selected.height) >= 1100) break
            }
            val cover = bitmap ?: return@withContext null
            val dir = root(context)
            if (!dir.isDirectory && !dir.mkdirs()) return@withContext null
            val width = cover.width
            val height = cover.height
            val factor = minOf(1f, 1200f / maxOf(width, height))
            val resized = if (factor < 1f) Bitmap.createScaledBitmap(
                cover, (width * factor).toInt().coerceAtLeast(1),
                (height * factor).toInt().coerceAtLeast(1), true,
            ) else cover
            ids.forEach { id ->
                val dest = file(context, id)
                val temp = File(dir, "${dest.name}.part")
                runCatching {
                    temp.outputStream().use { out ->
                        check(resized.compress(Bitmap.CompressFormat.JPEG, 95, out))
                    }
                    if (dest.exists()) dest.delete()
                    if (!temp.renameTo(dest)) error("Could not save offline art")
                }.onFailure { temp.delete() }
            }
            if (resized !== cover) resized.recycle()
            cover.recycle()
            get(context, ids.first())
        }

    fun delete(context: Context, videoId: String) {
        if (videoId.isNotBlank()) file(context, videoId).delete()
    }

    fun clear(context: Context) { root(context).deleteRecursively() }
}
