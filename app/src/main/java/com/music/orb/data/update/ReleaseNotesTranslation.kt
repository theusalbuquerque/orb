package com.music.orb.data.update

import com.music.orb.data.Http
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray

/** Best-effort translation for GitHub release notes shown in the update dialog. */
internal object ReleaseNotesTranslation {
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val MAX_BATCH_CHARS = 3_200
    private val markerRegex = Regex("\\uE000\\d{4}\\uE001")
    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }
    private val memory = LinkedHashMap<String, String>(8, 0.75f, true)

    suspend fun translate(text: String, targetLanguageTag: String): String? {
        val clean = text.trim()
        if (clean.isBlank()) return null
        val target = Locale.forLanguageTag(targetLanguageTag.replace('_', '-')).language
            .lowercase(Locale.ROOT)
            .ifBlank { return null }
        if (target == "en") return null

        val key = "$target\u0000$clean"
        synchronized(memory) { memory[key]?.let { return it } }

        val translated = withContext(Dispatchers.IO) {
            translateLines(clean.lines(), target)
        } ?: return null

        synchronized(memory) {
            memory[key] = translated
            while (memory.size > 8) memory.remove(memory.entries.first().key)
        }
        return translated
    }

    private fun translateLines(lines: List<String>, target: String): String? {
        if (lines.isEmpty()) return null
        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var used = 0
        for (line in lines) {
            val added = line.length + if (current.isEmpty()) 0 else 8
            if (current.isNotEmpty() && used + added > MAX_BATCH_CHARS) {
                batches += current.toList()
                current = mutableListOf()
                used = 0
            }
            current += line
            used += added
        }
        if (current.isNotEmpty()) batches += current

        val out = mutableListOf<String>()
        for (batch in batches) {
            val payload = buildString {
                batch.forEachIndexed { index, line ->
                    if (index > 0) append('\n').append(marker(index)).append('\n')
                    append(line.ifBlank { " " })
                }
            }
            val body = FormBody.Builder()
                .add("client", "dict-chrome-ex")
                .add("sl", "auto")
                .add("tl", target)
                .add("dt", "t")
                .add("q", payload)
                .build()
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("User-Agent", "Orb/1.0")
                .header("Accept", "application/json")
                .post(body)
                .build()
            val translatedBody = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val raw = response.body?.string() ?: return@use null
                    val root = JSONArray(raw)
                    val segments = root.optJSONArray(0) ?: return@use null
                    buildString {
                        for (i in 0 until segments.length()) {
                            append(segments.optJSONArray(i)?.optString(0).orEmpty())
                        }
                    }
                }
            }.getOrNull() ?: return null
            val parts = translatedBody.split(markerRegex)
            if (parts.size != batch.size) return null
            out += parts.map(String::trim)
        }
        if (out.size != lines.size) return null
        return out.joinToString("\n")
    }

    private fun marker(index: Int): String = "\uE000${index.toString().padStart(4, '0')}\uE001"
}
