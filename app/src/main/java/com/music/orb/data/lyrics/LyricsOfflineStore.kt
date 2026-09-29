package com.music.orb.data.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Durable lyrics stored alongside Premium offline downloads. */
internal object LyricsOfflineStore {
    private const val VERSION = 1

    private fun directory(context: Context): File =
        File(context.applicationContext.filesDir, "offline_lyrics")

    private fun key(videoId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    suspend fun put(context: Context, videoId: String, result: LyricsRepository.Result) =
        withContext(Dispatchers.IO) {
            if (videoId.isBlank() || result.lines.isEmpty()) return@withContext
            runCatching {
                val folder = directory(context)
                if (!folder.isDirectory && !folder.mkdirs()) return@runCatching
                val rows = JSONArray()
                result.lines.forEach { line ->
                    val words = JSONArray()
                    line.words.forEach { word ->
                        words.put(JSONArray().put(word.startMs).put(word.endMs).put(word.text))
                    }
                    rows.put(
                        JSONObject()
                            .put("time", line.timeMs)
                            .put("text", line.text)
                            .put("words", words)
                            .put("end", line.sungUntilMs ?: JSONObject.NULL)
                    )
                }
                JSONObject()
                    .put("version", VERSION)
                    .put("videoId", videoId)
                    .put("source", result.source.name)
                    .put("savedAt", System.currentTimeMillis())
                    .put("lines", rows)
                    .let { File(folder, "${key(videoId)}.json").writeText(it.toString(), Charsets.UTF_8) }
            }
        }

    suspend fun get(context: Context, videoId: String): LyricsRepository.Result? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(directory(context), "${key(videoId)}.json")
                if (!file.isFile) return@runCatching null
                val json = JSONObject(file.readText(Charsets.UTF_8))
                if (json.optInt("version", 0) != VERSION ||
                    json.optString("videoId") != videoId) return@runCatching null
                val source = LyricsSource.valueOf(json.getString("source"))
                val rows = json.getJSONArray("lines")
                val lines = List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    val words = row.getJSONArray("words")
                    LyricLine(
                        timeMs = row.getLong("time"),
                        text = row.getString("text"),
                        words = List(words.length()) { wordIndex ->
                            val word = words.getJSONArray(wordIndex)
                            LyricWord(
                                startMs = word.getLong(0),
                                endMs = word.getLong(1),
                                text = word.getString(2),
                            )
                        },
                        sungUntilMs = if (row.isNull("end")) null else row.getLong("end"),
                    )
                }
                lines.takeIf { it.isNotEmpty() }?.let {
                    LyricsRepository.Result(source = source, lines = it)
                }
            }.getOrNull()
        }
}
