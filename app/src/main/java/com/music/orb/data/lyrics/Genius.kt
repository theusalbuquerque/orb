package com.music.orb.data.lyrics

import com.music.orb.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Plain-text Genius fallback. It deliberately runs only after synced providers miss. */
object Genius {
    private const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    private val client by lazy {
        Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS).build()
    }

    suspend fun lyrics(title: String, artist: String): List<LyricLine>? = withContext(Dispatchers.IO) {
        val url = searchSongUrl(title, artist) ?: return@withContext null
        val html = get(url) ?: return@withContext null
        parseHtml(html)
    }

    private fun searchSongUrl(title: String, artist: String): String? {
        val query = "$artist $title".replace(Regex("\\s+"), " ").trim()
        val url = "https://genius.com/api/search/multi".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).build()
        val body = get(url.toString()) ?: return null
        return runCatching {
            val sections = lyricsJson.parseToJsonElement(body).jsonObject["response"]
                ?.jsonObject?.get("sections")?.jsonArray ?: return null
            val hits = sections.firstOrNull {
                (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "song"
            }?.jsonObject?.get("hits") as? JsonArray ?: return null
            val wantedTitle = title.lowercase(Locale.ROOT)
            val wantedArtist = artist.lowercase(Locale.ROOT)
            hits.mapNotNull { (it as? JsonObject)?.get("result") as? JsonObject }
                .maxByOrNull { result ->
                    val candidateTitle = result["title"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase(Locale.ROOT)
                    val candidateArtist = result["artist_names"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase(Locale.ROOT)
                    var score = 0
                    if (candidateTitle == wantedTitle) score += 50
                    else if (candidateTitle.contains(wantedTitle) || wantedTitle.contains(candidateTitle)) score += 20
                    if (candidateArtist == wantedArtist) score += 40
                    else if (candidateArtist.contains(wantedArtist) || wantedArtist.contains(candidateArtist)) score += 15
                    score
                }?.get("url")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    internal fun parseHtml(html: String): List<LyricLine>? {
        val containers = LYRICS_CONTAINER.findAll(html).map { it.groupValues[1] }.toList()
            .ifEmpty { LEGACY_CONTAINER.findAll(html).map { it.groupValues[1] }.toList() }
        if (containers.isEmpty()) return null
        val text = containers.joinToString("\n") { raw ->
            raw.replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""(?i)</p\s*>"""), "\n")
                .replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""<[^>]+>"""), "")
                .let(EnhancedLrc::decodeEntities)
        }
        val cleaned = text.replace('\u00A0', ' ').replace('\u200B', ' ')
            .replace(Regex("""\d*You might also like""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\d*Embed\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        val lines = cleaned.lineSequence().map(String::trim).filter(String::isNotEmpty)
            .map { LyricLine(0L, it) }.toList()
        return lines.takeIf { it.isNotEmpty() }
    }

    fun isSectionHeader(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length in 3..60
    }

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", AGENT).build()
        client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private val LYRICS_CONTAINER = Regex(
        """(?is)<div[^>]+data-lyrics-container=[\"']true[\"'][^>]*>(.*?)</div>""",
    )
    private val LEGACY_CONTAINER = Regex("""(?is)<div[^>]+class=[\"'][^\"']*lyrics[^\"']*[\"'][^>]*>(.*?)</div>""")
}
