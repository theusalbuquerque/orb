package com.music.orb.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Short, on-demand artist biographies sourced from Wikipedia. */
object WikipediaArtistInfoRepository {
    data class ArtistInfo(
        val extract: String,
        val pageTitle: String,
        val language: String,
    )

    private val cache = ConcurrentHashMap<String, ArtistInfo>()

    suspend fun summary(artistName: String): ArtistInfo? = withContext(Dispatchers.IO) {
        val clean = artistName.trim()
        if (clean.isBlank()) return@withContext null

        val locale = Locale.getDefault()
        val preferred = wikipediaLanguage(locale)
        val displayKey = "$preferred|${clean.lowercase(Locale.ROOT)}"
        cache[displayKey]?.let { return@withContext it }

        // Always ask the user's Wikipedia first. If that edition does not have
        // a trustworthy artist page, use another edition only as source text and
        // translate the biography back into the user's language before it reaches
        // the UI. This avoids silently falling back to English on e.g. pt-BR/es.
        val preferredInfo = runCatching { fetchValidated(clean, preferred) }.getOrNull()
        if (preferredInfo != null) {
            cache[displayKey] = preferredInfo
            return@withContext preferredInfo
        }

        val fallbackLanguages = listOf("en", "pt", "es")
            .filter { it != preferred }
            .distinct()
        val fallback = fallbackLanguages.firstNotNullOfOrNull { language ->
            val sourceKey = "$language|${clean.lowercase(Locale.ROOT)}"
            cache[sourceKey] ?: runCatching { fetchValidated(clean, language) }
                .getOrNull()
                ?.also { cache[sourceKey] = it }
        } ?: return@withContext null

        val translated = runCatching {
            translateExtract(
                text = fallback.extract,
                sourceLanguage = fallback.language,
                targetLanguage = translationLanguage(locale),
            )
        }.getOrNull()

        val localized = if (!translated.isNullOrBlank()) {
            fallback.copy(
                extract = briefExtract(translated),
                language = preferred,
            )
        } else {
            // Network translation is a best-effort enhancement. Never turn a
            // valid Wikipedia biography into an error just because translation
            // itself is temporarily unavailable.
            fallback
        }
        cache[displayKey] = localized
        localized
    }

    /**
     * Prefer an exact Wikipedia title/redirect before doing a full-text search.
     *
     * The previous implementation searched for `"artist" musician singer band`
     * and accepted the first hit. That can select a songwriter/producer whose
     * biography merely *mentions* the requested star (for example a Madonna
     * collaborator) instead of Madonna herself. Exact title resolution plus
     * music-domain validation makes that impossible for ordinary artist pages;
     * the broader search is only a fallback for genuinely ambiguous names.
     */
    private fun fetchValidated(artistName: String, language: String): ArtistInfo? {
        fetchExactTitle(artistName, language)?.let { return it }
        return fetchSearchCandidates(artistName, language)
    }

    private fun fetchExactTitle(artistName: String, language: String): ArtistInfo? {
        val encoded = encode(artistName)
        val url = "https://$language.wikipedia.org/w/api.php" +
            "?action=query&titles=$encoded&redirects=1" +
            "&prop=extracts%7Cpageprops&exintro=1&explaintext=1" +
            "&format=json&formatversion=2"
        val page = requestPages(url, language).firstOrNull() ?: return null
        if (!isValidMusicPage(page, language)) return null
        return page.toArtistInfo(artistName, language)
    }

    private fun fetchSearchCandidates(artistName: String, language: String): ArtistInfo? {
        val contextTerms = when (language) {
            "pt" -> "(músico OR cantor OR cantora OR banda OR rapper OR compositor)"
            "es" -> "(músico OR cantante OR banda OR rapero OR compositor)"
            else -> "(musician OR singer OR band OR rapper OR songwriter)"
        }
        val query = "\"$artistName\" $contextTerms"
        val encoded = encode(query)
        val url = "https://$language.wikipedia.org/w/api.php" +
            "?action=query&generator=search&gsrsearch=$encoded&gsrnamespace=0&gsrlimit=8" +
            "&prop=extracts%7Cpageprops&exintro=1&explaintext=1&redirects=1" +
            "&format=json&formatversion=2"

        return requestPages(url, language)
            .asSequence()
            .filter { isValidMusicPage(it, language) }
            .mapNotNull { page ->
                val score = candidateScore(page, artistName)
                if (score >= MIN_SEARCH_SCORE) score to page else null
            }
            .maxByOrNull { it.first }
            ?.second
            ?.toArtistInfo(artistName, language)
    }

    private fun requestPages(url: String, language: String): List<JSONObject> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Orb/1.0 Android (Wikipedia artist information)")
            .header("Accept-Language", language)
            .build()

        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val pages = JSONObject(body)
                .optJSONObject("query")
                ?.optJSONArray("pages")
                ?: return emptyList()
            return buildList(pages.length()) {
                for (index in 0 until pages.length()) {
                    pages.optJSONObject(index)?.let(::add)
                }
            }
        }
    }

    private fun JSONObject.toArtistInfo(fallbackName: String, language: String): ArtistInfo? {
        val extract = optString("extract").trim()
        if (extract.isBlank()) return null
        return ArtistInfo(
            extract = briefExtract(extract),
            pageTitle = optString("title", fallbackName).ifBlank { fallbackName },
            language = language,
        )
    }

    private fun isValidMusicPage(page: JSONObject, language: String): Boolean {
        if (page.optBoolean("missing", false)) return false
        if (page.optJSONObject("pageprops")?.has("disambiguation") == true) return false
        val title = page.optString("title")
        val extract = page.optString("extract").trim()
        if (extract.isBlank()) return false
        return looksMusicRelated(title, extract, language)
    }

    /**
     * A fallback search result must identify the requested artist in its title
     * (or right at the beginning of the biography), not merely mention them in
     * a collaborator list. This is the guard that rejects pages such as Klas
     * Åhlund for a request for Madonna.
     */
    private fun candidateScore(page: JSONObject, artistName: String): Int {
        val target = normalizeWords(artistName)
        val title = normalizeWords(page.optString("title"))
        val extractStart = normalizeWords(page.optString("extract").take(300))
        if (target.isBlank() || title.isBlank()) return 0

        val titleIsArtist = title == target || title.startsWith("$target ")
        val extractIntroducesArtist = extractStart == target || extractStart.startsWith("$target ")

        // Do not accept a work merely because the artist name occurs in its
        // title. Example: "Lifetime (Swedish House Mafia song)" used to score
        // highly for Swedish House Mafia because the old scorer rewarded any
        // title containing the name. Artist biographies identify the subject at
        // the start of the title and/or the opening sentence.
        if (!titleIsArtist && !extractIntroducesArtist) return 0
        if (titleLooksLikeWork(title, target)) return 0

        var score = 0
        if (title == target) score += 160
        else if (title.startsWith("$target ")) score += 120
        if (extractIntroducesArtist) score += 70

        return score
    }

    private fun titleLooksLikeWork(title: String, target: String): Boolean {
        if (title == target || !title.startsWith("$target ")) return false
        val qualifier = title.removePrefix(target).trim()
        if (qualifier.isBlank()) return false
        val blocked = listOf(
            "song", "single", "album", "ep", "extended play", "discography",
            "filmography", "tour", "concert tour", "soundtrack", "remix",
            "cancao", "musica", "single", "album", "discografia", "turne",
            "cancion", "sencillo", "discografia", "gira",
        )
        val padded = " $qualifier "
        return blocked.any { term ->
            val normalized = normalizeWords(term)
            normalized.isNotBlank() && padded.contains(" $normalized ")
        }
    }

    private fun looksMusicRelated(title: String, extract: String, language: String): Boolean {
        val haystack = normalizeWords("$title ${extract.take(900)}")
        val terms = when (language) {
            "pt" -> listOf(
                "cantor", "cantora", "musico", "musicista", "banda", "rapper",
                "compositor", "compositora", "produtor musical", "produtora musical",
                "dupla musical", "grupo musical", "grupo de musica", "supergrupo",
                "artista musical", "disc jockey", "dj", "musica eletronica",
                "house music", "r&b", "musica pop", "rock",
            )
            "es" -> listOf(
                "cantante", "musico", "musica", "banda", "rapero", "rapera",
                "compositor", "compositora", "productor musical", "productora musical",
                "duo musical", "grupo musical", "grupo de musica", "supergrupo",
                "artista musical", "disc jockey", "dj", "musica electronica",
                "house music", "r&b", "pop", "rock",
            )
            else -> listOf(
                "singer", "musician", "band", "rapper", "songwriter", "composer",
                "record producer", "recording artist", "musical duo", "music duo",
                "musical group", "music group", "supergroup", "vocal group",
                "disc jockey", "dj", "electronic music", "house music", "r&b",
                "pop duo", "rock band",
            )
        }
        val padded = " $haystack "
        return terms.any { term ->
            val normalizedTerm = normalizeWords(term)
            normalizedTerm.isNotBlank() && padded.contains(" $normalizedTerm ")
        }
    }


    private fun wikipediaLanguage(locale: Locale): String {
        val language = locale.language.lowercase(Locale.ROOT)
        return when (language) {
            "nb", "nn" -> "no"
            else -> language.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        }
    }

    private fun translationLanguage(locale: Locale): String {
        val language = locale.language.lowercase(Locale.ROOT)
        if (language != "zh") return language.takeIf { it.isNotBlank() } ?: "en"
        return when (locale.country.uppercase(Locale.ROOT)) {
            "TW", "HK", "MO" -> "zh-TW"
            else -> "zh-CN"
        }
    }

    /**
     * Translate only fallback Wikipedia text. The preferred-language article,
     * when it exists, is always shown verbatim. Keeping translation here means
     * the UI never needs to know whether the source edition matched the device.
     */
    private fun translateExtract(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String? {
        if (text.isBlank() || targetLanguage.isBlank()) return null
        if (sourceLanguage.equals(targetLanguage, ignoreCase = true)) return text

        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx&sl=${encode(sourceLanguage)}&tl=${encode(targetLanguage)}" +
            "&dt=t&q=${encode(text)}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Orb/1.0 Android (artist information translation)")
            .build()

        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            val root = JSONArray(body)
            val segments = root.optJSONArray(0) ?: return null
            return buildString {
                for (index in 0 until segments.length()) {
                    val segment = segments.optJSONArray(index) ?: continue
                    append(segment.optString(0))
                }
            }.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
        }
    }

    private fun normalizeWords(value: String): String = Normalizer
        .normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /** Keep the panel brief: up to four complete sentences / roughly 900 chars. */
    private fun briefExtract(value: String): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= 900) return normalized
        val sentenceBoundary = Regex("(?<=[.!?])\\s+")
        val sentences = normalized.split(sentenceBoundary)
        val brief = sentences.take(4).joinToString(" ").trim()
        return when {
            brief.length in 160..950 -> brief
            else -> normalized.take(880).trimEnd().trimEnd(',', ';', ':') + "…"
        }
    }

    private const val MIN_SEARCH_SCORE = 70
}
