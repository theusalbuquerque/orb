package com.music.orb.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.music.orb.data.Http
import com.music.orb.data.innertube.PlayerClient
import com.music.orb.data.innertube.StreamResolver
import com.music.orb.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Physical, isolated audio used by Automix analysis.
 *
 * This deliberately does not use Media3/SimpleCache.  Playback cache entries are excellent for
 * playback, but a MediaExtractor analysis needs one immutable container from byte zero to EOF.
 * A cache key can have multiple source renditions, partial spans and live readers; all of those are
 * useful playback behaviours and all of them are unnecessary failure modes for musical analysis.
 *
 * One official YouTube/Opus URL is resolved once, all byte ranges come from that same URL, and the
 * completed bytes are committed as one ordinary file.  MediaExtractor then opens a normal file
 * descriptor instead of a custom MediaDataSource over cache spans.
 */
class AnalysisAudioStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadMutex = Mutex()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val callbacks = ConcurrentHashMap<String, MutableList<(File?) -> Unit>>()

    private val directory: File by lazy {
        File(context.cacheDir, DIRECTORY).apply { mkdirs() }
    }

    fun readyFile(uri: Uri): File? {
        val videoId = uri.getQueryParameter("v") ?: return null
        return fileFor(videoId)
            .takeIf { it.isFile && it.length() >= MIN_VALID_BYTES }
            ?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    /** Opens a completed analysis copy as the random-access source MediaExtractor expects. */
    fun dataSource(file: File): MediaDataSource? = runCatching { FileMediaDataSource(file) }
        .onFailure { Log.w(TAG, "Could not open Automix analysis audio ${file.name}", it) }
        .getOrNull()

    /**
     * Ensures a complete physical analysis file exists. Repeated callers join the same download.
     * A null callback result means network/source preparation failed; it is *not* a musical failure.
     */
    fun request(uri: Uri, onReady: (File?) -> Unit) {
        val videoId = uri.getQueryParameter("v") ?: run {
            onReady(null)
            return
        }
        readyFile(uri)?.let {
            onReady(it)
            return
        }
        if (AppSettings.meteredConnection.value == null) {
            onReady(null)
            return
        }

        val waiting = callbacks.computeIfAbsent(videoId) {
            java.util.Collections.synchronizedList(mutableListOf())
        }
        synchronized(waiting) { waiting.add(onReady) }
        if (!inFlight.add(videoId)) return

        scope.launch {
            var result: File? = null
            try {
                downloadMutex.withLock {
                    result = readyFile(uri) ?: download(videoId)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Automix analysis audio download failed for $videoId", error)
            } finally {
                inFlight.remove(videoId)
                val listeners = callbacks.remove(videoId)?.let { list ->
                    synchronized(list) { list.toList() }
                }.orEmpty()
                listeners.forEach { listener -> runCatching { listener(result) } }
            }
        }
    }

    fun discard(uri: Uri): Boolean {
        val videoId = uri.getQueryParameter("v") ?: return false
        val target = fileFor(videoId)
        val partial = partialFor(videoId)
        val a = !target.exists() || target.delete()
        val b = !partial.exists() || partial.delete()
        return a && b
    }

    fun release() {
        scope.cancel()
        callbacks.clear()
        inFlight.clear()
    }

    private suspend fun download(videoId: String): File? {
        val resolvedUrl = StreamResolver.resolve(videoId)
        val headers = PlayerClient.forStreamUrl(resolvedUrl).mediaHeaders()
        val total = resolvedUrl.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: probeLength(resolvedUrl, headers)
            ?: return null
        if (total < MIN_VALID_BYTES || total > MAX_ANALYSIS_BYTES) {
            Log.w(TAG, "Automix analysis audio size refused for $videoId: $total bytes")
            return null
        }

        directory.mkdirs()
        val partial = partialFor(videoId)
        val target = fileFor(videoId)
        runCatching { partial.delete() }
        val started = SystemClock.elapsedRealtime()

        RandomAccessFile(partial, "rw").use { raf ->
            raf.setLength(total)
            var position = 0L
            while (position < total) {
                coroutineContext.ensureActive()
                val endInclusive = minOf(total - 1L, position + CHUNK_BYTES - 1L)
                val expected = endInclusive - position + 1L
                val request = Request.Builder()
                    .url(resolvedUrl)
                    .header("Range", "bytes=$position-$endInclusive")
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()

                Http.client.newCall(request).execute().use { response ->
                    if (response.code != 206 && !(response.code == 200 && position == 0L && expected == total)) {
                        error("analysis range HTTP ${response.code} at $position")
                    }
                    if (response.code == 206) {
                        val contentRange = response.header("Content-Range")
                            ?: error("analysis range missing Content-Range at $position")
                        val returnedStart = contentRange
                            .substringAfter("bytes ", "")
                            .substringBefore('-')
                            .toLongOrNull()
                        if (returnedStart != position) {
                            error("analysis range started at $returnedStart, expected $position")
                        }
                    }
                    val body = response.body
                    raf.seek(position)
                    val input = body.byteStream()
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = 0L
                    while (written < expected) {
                        coroutineContext.ensureActive()
                        val want = minOf(buffer.size.toLong(), expected - written).toInt()
                        val count = input.read(buffer, 0, want)
                        if (count < 0) break
                        raf.write(buffer, 0, count)
                        written += count
                    }
                    if (written != expected) {
                        error("analysis range short: $written of $expected at $position")
                    }
                }
                position += expected
            }
            raf.fd.sync()
        }

        if (partial.length() != total) {
            partial.delete()
            error("analysis file length ${partial.length()} != $total")
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            error("could not commit analysis file")
        }
        target.setLastModified(System.currentTimeMillis())
        prune(except = target)
        Log.d(
            TAG,
            "Automix physical analysis audio ready for $videoId: ${total / 1024}kB in " +
                "${SystemClock.elapsedRealtime() - started}ms",
        )
        return target
    }

    /**
     * Keeps the reliable-analysis fallback bounded. Results themselves live in AnalysisStore, so
     * old source audio is disposable once it has helped produce one; retaining a small recent set
     * only avoids a re-download when a process dies between download and inference.
     */
    private fun prune(except: File) {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.extension == "media" && it != except }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var retainedBytes = except.length()
        var retainedFiles = 1
        for (file in files) {
            val keep = retainedFiles < MAX_RETAINED_FILES && retainedBytes + file.length() <= MAX_RETAINED_BYTES
            if (keep) {
                retainedBytes += file.length()
                retainedFiles++
            } else {
                runCatching { file.delete() }
            }
        }
        directory.listFiles()
            ?.filter { it.isFile && it.extension == "part" }
            ?.filter { System.currentTimeMillis() - it.lastModified() > STALE_PARTIAL_MS }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun probeLength(url: String, headers: Map<String, String>): Long? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                response.header("Content-Range")
                    ?.substringAfter('/')
                    ?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
            }
        }.getOrNull()?.takeIf { it > 0L }
    }

    private fun fileFor(videoId: String): File = File(directory, "${safe(videoId)}.media")
    private fun partialFor(videoId: String): File = File(directory, "${safe(videoId)}.part")
    private fun safe(videoId: String): String = videoId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private class FileMediaDataSource(file: File) : MediaDataSource() {
        private val random = RandomAccessFile(file, "r")
        private val size = random.length()

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0L || position >= this.size) return -1
            random.seek(position)
            return random.read(buffer, offset, minOf(size.toLong(), this.size - position).toInt())
        }

        override fun getSize(): Long = size

        override fun close() = random.close()
    }

    private companion object {
        const val TAG = "OrbAutomixAudio"
        const val DIRECTORY = "automix-analysis-audio-v1"
        const val CHUNK_BYTES = 2L * 1024L * 1024L
        const val BUFFER_BYTES = 64 * 1024
        const val MIN_VALID_BYTES = 64L * 1024L
        const val MAX_ANALYSIS_BYTES = 64L * 1024L * 1024L
        const val MAX_RETAINED_FILES = 6
        const val MAX_RETAINED_BYTES = 96L * 1024L * 1024L
        const val STALE_PARTIAL_MS = 6L * 60L * 60L * 1000L
    }
}