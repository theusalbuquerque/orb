package com.music.orb.data.stats

import android.content.Context
import android.content.SharedPreferences
import com.music.orb.data.lyrics.LyricLine
import com.music.orb.data.lyrics.LyricsRepository
import com.music.orb.data.lyrics.LyricsSource
import java.text.Normalizer
import java.util.Locale

/**
 * Language resolver for Stats.
 *
 * The old Stats implementation guessed from a short song title/album. That is
 * too little text for neighbouring Latin-script languages and can easily turn
 * Italian into German or German into French. This resolver gives full lyrics
 * priority, persists the result by video id, and uses metadata only when there
 * is strong, language-specific evidence. Ambiguous metadata stays unknown
 * instead of being counted as the wrong language.
 */
object TrackLanguageResolver {
    private const val PREFS = "orb_track_languages_v2"
    private const val MAX_CACHED_TRACKS = 2_000

    @Volatile
    private var preferences: SharedPreferences? = null

    fun init(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        trimIfNeeded()
    }

    fun cached(videoId: String): String? {
        if (videoId.isBlank()) return null
        return preferences?.getString(videoId, null)?.takeIf(::validCode)
    }

    fun remember(videoId: String, languageCode: String?) {
        if (videoId.isBlank()) return
        val code = languageCode?.lowercase(Locale.ROOT)?.takeIf(::validCode) ?: return
        preferences?.edit()?.putString(videoId, code)?.apply()
    }

    fun rememberFromLyrics(videoId: String, lines: List<LyricLine>): String? {
        val code = detectFromLyrics(lines) ?: return null
        remember(videoId, code)
        return code
    }

    /** Returns a cached lyric-derived answer, then a conservative metadata answer. */
    fun knownOrMetadata(videoId: String, title: String, album: String?): String? =
        cached(videoId) ?: detectFromMetadata(title, album)

    /**
     * Background Stats enrichment for older listening rows that predate
     * language_code. Only exact-video YouTube lyric/caption sources are used so
     * a fuzzy title match can never assign another recording's language.
     */
    suspend fun resolveWithExactLyrics(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
    ): String? {
        cached(videoId)?.let { return it }
        detectFromMetadata(title, album)?.let { return it }
        val duration = durationMs?.takeIf { it > 0L } ?: return null
        // Prefer the exact YouTube Music Lyrics tab. Captions/transcripts can
        // be localized or auto-translated by YouTube and therefore are a poor
        // primary language signal (this was the source of Spanish tracks being
        // persisted as Russian when a translated caption won the race).
        val youtubeMusic = runCatching {
            LyricsRepository.lyrics(
                videoId = videoId,
                title = title,
                artist = artist,
                durationMs = duration,
                album = album,
                sources = setOf(LyricsSource.YOUTUBE_MUSIC),
                prioritizeSyllableSync = false,
            )
        }.getOrNull()
        youtubeMusic?.let { result ->
            detectFromLyrics(result.lines)?.let { code ->
                remember(videoId, code)
                return code
            }
        }

        // Exact-video captions are only a fallback. The detector below now
        // requires a script to dominate the lyric sample before assigning a
        // non-Latin language, so a stray Cyrillic glyph cannot turn Spanish
        // lyrics into Russian.
        val transcript = runCatching {
            LyricsRepository.lyrics(
                videoId = videoId,
                title = title,
                artist = artist,
                durationMs = duration,
                album = album,
                sources = setOf(LyricsSource.YOUTUBE_TRANSCRIPT),
                prioritizeSyllableSync = false,
            )
        }.getOrNull() ?: return null
        return rememberFromLyrics(videoId, transcript.lines)
    }

    /**
     * Full-lyrics detection. Function words dominate the score because they are
     * repeated naturally in lyrics and discriminate neighbouring languages far
     * better than artist names or isolated song-title words.
     */
    fun detectFromLyrics(lines: List<LyricLine>): String? {
        val text = lines.asSequence()
            .map { it.text.trim() }
            .filter { it.isNotBlank() && !(it.startsWith("[") && it.endsWith("]")) }
            .take(160)
            .joinToString(" ")
        return detectText(text, strictMetadata = false)
    }

    /**
     * Metadata fallback. This deliberately refuses weak/common words. A missing
     * language is preferable to polluting Stats with a confidently wrong one.
     */
    fun detectFromMetadata(title: String, album: String?): String? {
        val sample = listOf(title, album.orEmpty())
            .joinToString(" ")
            .replace(Regex("(?i)\\b(remaster(?:ed)?|version|edit|mix|live|single|album|deluxe|explicit)\\b"), " ")
            .trim()
        return detectText(sample, strictMetadata = true)
    }

    private fun detectText(sample: String, strictMetadata: Boolean): String? {
        if (sample.isBlank()) return null

        val letterScripts = sample.asSequence()
            .filter { it.isLetter() }
            .map { Character.UnicodeScript.of(it.code) }
            .toList()
        val letterCount = letterScripts.size.coerceAtLeast(1)
        fun scriptShare(vararg scripts: Character.UnicodeScript): Double {
            val wanted = scripts.toSet()
            return letterScripts.count { it in wanted }.toDouble() / letterCount.toDouble()
        }
        fun dominant(script: Character.UnicodeScript, minimum: Double = 0.55): Boolean =
            scriptShare(script) >= minimum

        // Non-Latin languages require the corresponding script to dominate
        // the lyric sample. One artist-name character, emoji-adjacent glyph or
        // localized caption fragment is not evidence of the sung language.
        when {
            dominant(Character.UnicodeScript.HANGUL, 0.45) -> return "ko"
            scriptShare(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA) >= 0.30 -> return "ja"
            dominant(Character.UnicodeScript.HAN, 0.45) -> return "zh"
            dominant(Character.UnicodeScript.CYRILLIC) -> return "ru"
            dominant(Character.UnicodeScript.ARABIC) -> return "ar"
            dominant(Character.UnicodeScript.DEVANAGARI) -> return "hi"
            dominant(Character.UnicodeScript.THAI) -> return "th"
            dominant(Character.UnicodeScript.HEBREW) -> return "he"
            dominant(Character.UnicodeScript.GREEK) -> return "el"
            dominant(Character.UnicodeScript.BENGALI) -> return "bn"
            dominant(Character.UnicodeScript.TAMIL) -> return "ta"
            dominant(Character.UnicodeScript.TELUGU) -> return "te"
            dominant(Character.UnicodeScript.KANNADA) -> return "kn"
            dominant(Character.UnicodeScript.MALAYALAM) -> return "ml"
            dominant(Character.UnicodeScript.GURMUKHI) -> return "pa"
            dominant(Character.UnicodeScript.GUJARATI) -> return "gu"
        }

        val raw = sample.lowercase(Locale.ROOT)
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^a-z0-9'\s]+"""), " ")
        val tokens = normalized.split(Regex("""\s+"""))
            .map { it.trim('\'', '’') }
            .filter { it.length > 1 }
        if (tokens.isEmpty()) return null

        data class Lexicon(
            val code: String,
            val functionWords: Set<String>,
            val strongWords: Set<String>,
            val rawHints: List<String> = emptyList(),
        )

        val lexicons = listOf(
            Lexicon(
                "it",
                functionWords = setOf(
                    "che", "non", "per", "una", "uno", "sono", "sei", "siamo", "mi", "ti",
                    "io", "tu", "lui", "lei", "noi", "voi", "con", "senza", "questo", "questa",
                    "come", "quando", "dove", "ancora", "sempre", "nel", "nella", "della", "degli",
                    "delle", "alla", "alle", "sul", "sulla", "ma", "poi", "qui", "cosi", "perche",
                ),
                strongWords = setOf(
                    "amore", "cuore", "notte", "vita", "sogno", "sogni", "bacio", "baci", "bella",
                    "bello", "ragazza", "ragazzo", "insieme", "solitudine", "vorrei", "voglio",
                ),
                rawHints = listOf(" perché ", " più ", " così ", " però "),
            ),
            Lexicon(
                "de",
                functionWords = setOf(
                    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "einen",
                    "und", "ich", "du", "er", "sie", "wir", "ihr", "nicht", "mit", "fur", "von",
                    "zum", "zur", "im", "ins", "auf", "ist", "bist", "sind", "mein", "meine", "dein",
                    "deine", "kein", "keine", "aber", "wie", "was", "wenn", "dann", "hier", "noch",
                ),
                strongWords = setOf(
                    "liebe", "nacht", "immer", "ohne", "herz", "allein", "traum", "traume", "leben",
                    "sehnsucht", "madchen", "junge", "zeit", "welt", "wieder", "bleib", "bleiben",
                ),
                rawHints = listOf("ß", "ä", "ö", "ü"),
            ),
            Lexicon(
                "fr",
                functionWords = setOf(
                    "le", "la", "les", "un", "une", "des", "et", "je", "tu", "il", "elle", "nous",
                    "vous", "pas", "ne", "que", "qui", "du", "dans", "avec", "pour", "sans", "est",
                    "suis", "sont", "mon", "ma", "mes", "ton", "ta", "tes", "mais", "comme", "quand",
                    "ou", "ici", "encore", "jamais", "toujours",
                ),
                strongWords = setOf(
                    "amour", "coeur", "nuit", "bonjour", "revoir", "ensemble", "fille", "garcon", "reve",
                    "reves", "seul", "seule", "vie", "envie", "aime", "aimer", "veux", "voudrais",
                ),
                rawHints = listOf("œ", "ç", " l'", " d'", " j'", " qu'"),
            ),
            Lexicon(
                "es",
                functionWords = setOf(
                    "el", "la", "los", "las", "un", "una", "unos", "unas", "que", "yo", "tu", "ella",
                    "nosotros", "ustedes", "con", "sin", "para", "por", "del", "al", "como", "cuando",
                    "donde", "eres", "soy", "somos", "son", "pero", "aqui", "siempre", "nunca", "porque",
                ),
                strongWords = setOf(
                    "corazon", "noche", "contigo", "beso", "besos", "miedo", "suenos", "sueno", "amor",
                    "vida", "quiero", "quieres", "puedo", "puedes", "solo", "sola", "juntos", "juntas",
                ),
                rawHints = listOf("ñ", "¿", "¡"),
            ),
            Lexicon(
                "pt",
                functionWords = setOf(
                    "os", "as", "um", "uma", "uns", "umas", "eu", "voce", "voces", "ele", "ela", "nos",
                    "com", "sem", "para", "por", "que", "do", "da", "dos", "das", "no", "na", "nos",
                    "nas", "meu", "minha", "meus", "minhas", "seu", "sua", "mas", "aqui", "sempre", "nunca",
                    "nao", "tambem", "porque", "quando", "onde",
                ),
                strongWords = setOf(
                    "saudade", "coracao", "paixao", "cancao", "amor", "vida", "quero", "sozinho", "sozinha",
                    "beijo", "beijos", "sonho", "sonhos", "gente", "voce", "viver", "ficar",
                ),
                rawHints = listOf("ã", "õ", "ção", "ções"),
            ),
            Lexicon(
                "en",
                functionWords = setOf(
                    "the", "and", "you", "your", "yours", "my", "mine", "me", "we", "our", "ours", "they",
                    "their", "this", "that", "these", "those", "with", "without", "for", "from", "into", "are",
                    "is", "was", "were", "have", "has", "dont", "can't", "cant", "wont", "not", "but", "when",
                    "where", "how", "what", "why", "here", "there",
                ),
                strongWords = setOf(
                    "love", "heart", "night", "forever", "dream", "dreams", "lonely", "baby", "girl", "boy",
                    "home", "again", "tonight", "never", "always", "want", "need", "feel", "feeling",
                ),
            ),
            Lexicon(
                "tr",
                functionWords = setOf("bir", "ben", "sen", "biz", "siz", "icin", "ile", "ama", "degil", "bu", "ne"),
                strongWords = setOf("ask", "gece", "seni", "benim", "hayat", "kalp", "yalniz"),
                rawHints = listOf("ı", "ş", "ğ"),
            ),
            Lexicon(
                "pl",
                functionWords = setOf("jest", "nie", "tak", "jak", "dla", "bez", "moja", "moje", "twoja", "twoje", "ale"),
                strongWords = setOf("milosc", "noc", "serce", "zawsze", "nigdy", "zycie"),
                rawHints = listOf("ł", "ą", "ę", "ś", "ź", "ż", "ć", "ń"),
            ),
        )

        val tokenCounts = tokens.groupingBy { it }.eachCount()
        data class Score(val code: String, val points: Int, val distinctHits: Int, val strongHits: Int, val hintHits: Int)
        val scores = lexicons.map { lexicon ->
            val functionHits = lexicon.functionWords.sumOf { tokenCounts[it] ?: 0 }
            val strongHits = lexicon.strongWords.sumOf { tokenCounts[it] ?: 0 }
            val distinct = (lexicon.functionWords + lexicon.strongWords).count { (tokenCounts[it] ?: 0) > 0 }
            val hints = lexicon.rawHints.count { it in raw }
            Score(
                code = lexicon.code,
                points = functionHits + strongHits * 3 + hints * 4,
                distinctHits = distinct,
                strongHits = strongHits,
                hintHits = hints,
            )
        }.sortedWith(compareByDescending<Score> { it.points }.thenByDescending { it.distinctHits })

        val winner = scores.firstOrNull() ?: return null
        val runner = scores.getOrNull(1)
        if (winner.points <= 0) return null

        if (strictMetadata) {
            // A title/album needs either orthographic evidence, two independent
            // high-signal words, or one strong word plus a clear lead.
            val enoughEvidence = winner.hintHits > 0 ||
                winner.strongHits >= 2 ||
                (winner.strongHits >= 1 && winner.points >= 4 && winner.distinctHits >= 2)
            if (!enoughEvidence) return null
            if (runner != null && winner.points <= runner.points + 1) return null
            return winner.code
        }

        // Full lyrics should contain several independent grammatical signals.
        // Require both density and separation from the nearest language.
        val minimum = if (tokens.size >= 30) 5 else 4
        if (winner.points < minimum || winner.distinctHits < 3) return null
        if (runner != null) {
            val margin = winner.points - runner.points
            val ratio = winner.points.toDouble() / runner.points.coerceAtLeast(1).toDouble()
            if (margin < 3 && ratio < 1.35) return null
        }
        return winner.code
    }

    private fun validCode(value: String): Boolean =
        value.matches(Regex("^[a-z]{2,3}$")) && value != "und"

    private fun trimIfNeeded() {
        val prefs = preferences ?: return
        val keys = prefs.all.keys
        if (keys.size <= MAX_CACHED_TRACKS) return
        // SharedPreferences has no reliable per-key timestamp; keep a bounded
        // cache by removing an arbitrary overflow. Fresh plays repopulate it.
        val overflow = keys.size - MAX_CACHED_TRACKS
        val editor = prefs.edit()
        keys.take(overflow).forEach(editor::remove)
        editor.apply()
    }
}
