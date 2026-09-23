package com.music.orb.data.lyrics

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Disposable, bounded disk cache. Timings and source preferences are part of the key. */
internal object LyricsDiskCache {
    @Volatile private var directory: File? = null
    private val mutex = Mutex()
    private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024
    private const val MAX_BYTES = 24L * 1024 * 1024
    private const val MAX_FILES = 200
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    fun init(context: Context) {
        directory = File(context.applicationContext.cacheDir, "lyrics_v1")
    }

    fun key(videoId: String, title: String, artist: String, durationMs: Long,
            album: String?, sources: Set<LyricsSource>, prioritizeSyllableSync: Boolean = false): String {
        val identity = JSONArray().put(videoId).put(title).put(artist).put(durationMs)
            .put(album ?: "").put(sources.map { it.name }.sorted().joinToString(","))
            .put(prioritizeSyllableSync)
        return MessageDigest.getInstance("SHA-256").digest(identity.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    suspend fun get(key: String): LyricsRepository.Result? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val folder = directory ?: return@withLock null
            val file = File(folder, "$key.json")
            runCatching {
                if (!file.isFile || file.length() > MAX_ENTRY_BYTES) return@runCatching null
                val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                if (System.currentTimeMillis() - json.getLong("savedAt") > TTL_MS) {
                    file.delete()
                    return@runCatching null
                }
                val source = LyricsSource.valueOf(json.getString("source"))
                val rows = json.getJSONArray("lines")
                val lines = List(rows.length()) { i ->
                    val row = rows.getJSONObject(i)
                    val words = row.getJSONArray("words")
                    LyricLine(
                        timeMs = row.getLong("time"), text = row.getString("text"),
                        words = List(words.length()) { j ->
                            val word = words.getJSONArray(j)
                            LyricWord(word.getLong(0), word.getLong(1), word.getString(2))
                        },
                        sungUntilMs = if (row.isNull("end")) null else row.getLong("end"),
                    )
                }
                if (lines.isEmpty()) null else LyricsRepository.Result(source, lines)
            }.getOrNull()
        }
    }

    suspend fun put(key: String, result: LyricsRepository.Result) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val folder = directory ?: return@withLock
            if (result.lines.isEmpty()) return@withLock
            runCatching {
                if (!folder.isDirectory && !folder.mkdirs()) return@runCatching
                val rows = JSONArray()
                result.lines.forEach { line ->
                    val words = JSONArray()
                    line.words.forEach { words.put(JSONArray().put(it.startMs).put(it.endMs).put(it.text)) }
                    rows.put(JSONObject().put("time", line.timeMs).put("text", line.text)
                        .put("words", words).put("end", line.sungUntilMs ?: JSONObject.NULL))
                }
                val bytes = JSONObject().put("savedAt", System.currentTimeMillis())
                    .put("source", result.source.name).put("lines", rows).toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_ENTRY_BYTES) return@runCatching
                val atomic = AtomicFile(File(folder, "$key.json"))
                val output = atomic.startWrite()
                try {
                    output.write(bytes)
                    atomic.finishWrite(output)
                } catch (error: Exception) {
                    atomic.failWrite(output)
                    throw error
                }
                val files = folder.listFiles()?.filter { it.extension == "json" }
                    ?.sortedByDescending { it.lastModified() }.orEmpty()
                var used = 0L
                files.forEachIndexed { index, file ->
                    used += file.length()
                    if (index >= MAX_FILES || used > MAX_BYTES) file.delete()
                }
            }
            Unit
        }
    }
}
