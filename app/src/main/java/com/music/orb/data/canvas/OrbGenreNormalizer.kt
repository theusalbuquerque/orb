package com.music.orb.data.canvas

import java.text.Normalizer
import java.util.Locale

/**
 * Canonical genre vocabulary shared by Stats, profiles and share stories.
 *
 * Providers use many spellings for the same family (for example `hip hop`,
 * `hip-hop` and `hiphop`). Every caller must receive the same display label so
 * one genre never splits into multiple Stats entries only because its provider
 * changed or an older cache used a different spelling.
 *
 * Electronic / electronica / dance are intentionally folded into EDM. Compound
 * labels are matched before their broad tokens, which prevents names such as
 * `dance pop` from being misclassified as EDM simply because they contain the
 * word `dance`.
 */
fun normalizeOrbGenre(raw: String): String? {
    val original = raw.trim()
    if (original.isBlank()) return null

    val lower = original.lowercase(Locale.ROOT)
    val plain = Normalizer.normalize(lower, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('&', ' ')
        .replace('_', ' ')
        .replace('-', ' ')
        .replace('/', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    fun has(vararg tokens: String): Boolean =
        tokens.any { token ->
            val normalizedToken = token
                .lowercase(Locale.ROOT)
                .replace('&', ' ')
                .replace('-', ' ')
                .replace('/', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            plain == normalizedToken ||
                plain.startsWith("$normalizedToken ") ||
                plain.endsWith(" $normalizedToken") ||
                " $normalizedToken " in plain
        }

    return when {
        // Compound/specific families first. These rules prevent a broad token
        // (dance, funk, rock, pop...) from stealing a more precise label.
        has("brega funk") -> "Brega Funk"
        has("brazilian bass", "brazil bass") -> "Brazilian Bass"
        has("heavy metal", "metalcore", "death metal", "black metal", "power metal") -> "Heavy Metal"
        has("punk rock", "pop punk", "post punk", "punk") -> "Punk Rock"
        has("synthpop", "synth pop") -> "Synthpop"
        has("kpop", "k pop", "korean pop") -> "K-Pop"
        has("tecnobrega", "techno brega") -> "Tecnobrega"
        has("megafunk", "mega funk") -> "Megafunk"
        has("manguebeat", "mangue beat") -> "Manguebeat"

        // Pop compounds need to beat the broad electronic/dance branch below.
        has("dance pop", "electropop", "electro pop") -> "Pop"
        has("indie pop") -> "Indie"
        has("alternative pop", "alt pop") -> "Alternative"
        has("pop rock") -> "Rock"

        has("trap") -> "Trap"
        has("drill") -> "Drill"
        has("psy", "psytrance", "psy trance", "psychedelic trance") -> "Psytrance"
        has("trance") -> "Trance"
        has("house", "tech house", "deep house", "progressive house") -> "House"

        // Orb intentionally exposes one stable broad electronic family.
        has("edm", "electronic", "electronica", "electro", "dance") -> "EDM"

        has("hip hop", "hiphop") -> "Hip-Hop"
        has("rap") -> "Rap"
        has("r b", "rnb", "rhythm and blues") -> "R&B"
        has("soul") -> "Soul"

        has("indie") -> "Indie"
        has("alternative") -> "Alternative"
        has("grunge") -> "Grunge"
        has("rock") -> "Rock"

        has("jazz") -> "Jazz"
        has("blues") -> "Blues"
        has("reggae") -> "Reggae"
        has("folk") -> "Folk"
        has("country") -> "Country"

        has("mpb", "musica popular brasileira") -> "MPB"
        has("latin", "latino", "latina", "latin pop") -> "Latin"
        has("forro") -> "Forró"
        has("frevo") -> "Frevo"
        has("sertanejo") -> "Sertanejo"
        has("brega") -> "Brega"
        has("funk carioca", "baile funk", "brazilian funk", "funk brasileiro", "funk") -> "Funk"

        has("60s", "60's", "60 s", "1960s", "1960's", "sixties") -> "60s"
        has("70s", "70's", "70 s", "1970s", "1970's", "seventies") -> "70s"
        has("80s", "80's", "80 s", "1980s", "1980's", "eighties") -> "80s"
        has("90s", "90's", "90 s", "1990s", "1990's", "nineties") -> "90s"

        has("pop") -> "Pop"
        else -> null
    }
}

/** Canonicalizes and deduplicates a provider/cache genre list in display order. */
fun normalizeOrbGenres(raw: Iterable<String>): List<String> = raw
    .mapNotNull(::normalizeOrbGenre)
    .distinctBy { it.lowercase(Locale.ROOT) }
