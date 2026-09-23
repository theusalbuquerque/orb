package com.music.orb.data.lyrics

import android.content.Context
import android.util.LruCache
import com.music.orb.data.Http
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response

/** On-demand lyric translation which preserves the source lyric clock. */
object LyricsTranslation {
    sealed interface Result {
        data class Translated(
            val lines: List<LyricLine>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : Result
        data class SameLanguage(val language: String) : Result
        data object Unavailable : Result
    }

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val CACHE_VERSION = 1
    private const val CACHE_DIRECTORY = "lyrics_translation_v1"
    private const val MAX_CACHE_BYTES = 2L * 1024L * 1024L
    private const val MAX_BATCH_CHARS = 3_500
    private const val MAX_PARALLEL_REQUESTS = 2
    private val markerRegex = Regex("\\uE000\\d{4}\\uE001")
    private val json = Json { ignoreUnknownKeys = true }
    private val diskMutex = Mutex()
    private val memory = LruCache<String, CachedTranslation>(12)
    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    private data class CachedTranslation(
        val version: Int = CACHE_VERSION,
        val sourceLanguage: String,
        val targetLanguage: String,
        val texts: List<String>,
    )

    private data class Slot(val lineIndex: Int, val text: String, val sectionHeader: Boolean)
    private data class Batch(val slots: List<Slot>, val payload: String)
    private data class BatchAnswer(
        val translations: List<String>,
        val sourceLanguage: String,
        val sourceWeight: Int,
    )

    suspend fun translate(
        context: Context,
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): Result {
        val target = targetLanguageTag.trim()
        if (target.isBlank() || lines.isEmpty()) return Result.Unavailable
        val slots = flatten(lines)
        if (slots.isEmpty()) return Result.Unavailable

        val key = cacheKey(trackId, target, slots)
        val cached = memory.get(key) ?: readCache(context, key)?.also { memory.put(key, it) }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return if (sameLanguage(cached.sourceLanguage, target)) {
                Result.SameLanguage(cached.sourceLanguage)
            } else {
                Result.Translated(rebuild(lines, slots, cached.texts), cached.sourceLanguage, true)
            }
        }

        val answers = coroutineScope {
            batches(slots).chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return Result.Unavailable
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key.orEmpty()
        if (source.isBlank()) return Result.Unavailable

        val translated = complete.flatMap { it.translations }
        if (translated.size != slots.size) return Result.Unavailable
        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = target,
            texts = translated,
        )
        memory.put(key, entry)
        writeCache(context, key, entry)

        return if (sameLanguage(source, target)) {
            Result.SameLanguage(source)
        } else {
            Result.Translated(rebuild(lines, slots, translated), source, false)
        }
    }

    /**
     * Best-effort detection of the source lyric language so UI can decide
     * whether translating would be redundant before the user taps the button.
     * Returns `true` when the lyrics are already in [targetLanguageTag],
     * `false` when they are not, and `null` when the language could not be
     * determined quickly.
     */
    suspend fun lyricsAlreadyInLanguage(
        context: Context,
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): Boolean? {
        val target = targetLanguageTag.trim()
        if (target.isBlank() || lines.isEmpty()) return null
        val slots = flatten(lines)
        if (slots.isEmpty()) return null

        val key = cacheKey(trackId, target, slots)
        val cached = memory.get(key) ?: readCache(context, key)?.also { memory.put(key, it) }
        if (cached != null && cached.version == CACHE_VERSION) {
            return sameLanguage(cached.sourceLanguage, target)
        }

        val probe = batches(slots).firstOrNull() ?: return null
        val answer = requestBatch(probe, target) ?: return null
        if (answer.sourceLanguage.isBlank()) return null
        return sameLanguage(answer.sourceLanguage, target)
    }

    private fun flatten(lines: List<LyricLine>): List<Slot> = buildList {
        lines.forEachIndexed { index, line ->
            if (line.text.isBlank()) return@forEachIndexed
            val header = line.text.trim().let { it.startsWith("[") && it.endsWith("]") && it.length in 3..60 }
            val text = if (header) line.text.trim().removePrefix("[").removeSuffix("]").trim() else line.text
            add(Slot(index, text, header))
        }
    }

    private fun batches(slots: List<Slot>): List<Batch> {
        val result = mutableListOf<Batch>()
        var current = mutableListOf<Slot>()
        var length = 0
        fun flush() {
            if (current.isEmpty()) return
            result += Batch(current.toList(), payload(current))
            current = mutableListOf()
            length = 0
        }
        slots.forEach { slot ->
            val added = slot.text.length + if (current.isEmpty()) 0 else 8
            if (current.isNotEmpty() && length + added > MAX_BATCH_CHARS) flush()
            current += slot
            length += added
        }
        flush()
        return result
    }

    private fun payload(slots: List<Slot>): String = buildString {
        slots.forEachIndexed { index, slot ->
            if (index > 0) append('\n').append(marker(index)).append('\n')
            append(slot.text)
        }
    }

    private fun marker(index: Int): String = "\uE000${index.toString().padStart(4, '0')}\uE001"

    private suspend fun requestBatch(batch: Batch, target: String): BatchAnswer? {
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", "auto")
            .add("tl", target)
            .add("dt", "t")
            .add("q", batch.payload)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", "Orb/1.0")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).awaitBody()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            val translatedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = translatedBody.split(markerRegex).map(String::trim)
            if (parts.size != batch.slots.size || parts.any(String::isBlank)) return@runCatching null
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }

    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = if (it.isSuccessful) it.body?.string() else null
                    if (!continuation.isActive) return
                    if (text != null) continuation.resume(text)
                    else continuation.resumeWithException(IOException("Translation HTTP ${it.code}"))
                }
            }
        })
    }

    private fun rebuild(
        original: List<LyricLine>,
        slots: List<Slot>,
        translated: List<String>,
    ): List<LyricLine> {
        val byLine = slots.zip(translated).associate { it.first.lineIndex to (it.first to it.second) }
        return original.mapIndexed { index, line ->
            val entry = byLine[index] ?: return@mapIndexed line
            val (slot, raw) = entry
            val text = if (slot.sectionHeader) "[$raw]" else raw
            line.copy(
                text = text,
                words = if (line.words.isEmpty()) emptyList() else {
                    listOf(LyricWord(line.words.first().startMs, line.words.last().endMs, text))
                },
                timingSource = line.takeIf { it.isWordSynced },
            )
        }
    }

    private fun canonicalLanguage(tag: String): String {
        val language = Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
        return when (language) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> language
        }
    }

    private fun sameLanguage(first: String, second: String): Boolean =
        canonicalLanguage(first) == canonicalLanguage(second)

    private fun cacheKey(trackId: String, target: String, slots: List<Slot>): String {
        val source = buildString {
            append(trackId).append('\u0000').append(target)
            slots.forEach { append('\u0000').append(it.text) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private suspend fun readCache(context: Context, key: String): CachedTranslation? =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val file = File(File(context.cacheDir, CACHE_DIRECTORY), "$key.json.gz")
                if (!file.isFile) return@withLock null
                runCatching {
                    val value = GZIPInputStream(FileInputStream(file)).bufferedReader().use {
                        json.decodeFromString<CachedTranslation>(it.readText())
                    }
                    file.setLastModified(System.currentTimeMillis())
                    value.takeIf { it.version == CACHE_VERSION }
                }.getOrNull()
            }
        }

    private suspend fun writeCache(context: Context, key: String, value: CachedTranslation) =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val directory = File(context.cacheDir, CACHE_DIRECTORY)
                if (!directory.exists() && !directory.mkdirs()) return@withLock
                val destination = File(directory, "$key.json.gz")
                val temporary = File(directory, "$key.tmp")
                runCatching {
                    GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use {
                        it.write(json.encodeToString(value))
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    trimCache(directory)
                }.onFailure { temporary.delete() }
            }
        }

    private fun trimCache(directory: File) {
        val files = directory.listFiles { file -> file.extension == "gz" }
            ?.sortedByDescending(File::lastModified).orEmpty()
        var used = 0L
        files.forEach { file ->
            used += file.length()
            if (used > MAX_CACHE_BYTES) file.delete()
        }
    }
}
