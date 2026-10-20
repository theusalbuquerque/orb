package com.music.orb.data

import java.text.Normalizer
import java.util.Locale

/**
 * Identity check for artist navigation. A credit/collaboration may contain the
 * requested name without being that artist's own page. Only a complete name
 * match is safe when accepting a returned YouTube Music artist browse page.
 */
object ArtistProfileIdentity {
    fun matches(expected: String, returned: String?): Boolean {
        if (returned.isNullOrBlank()) return false
        val requested = key(expected)
        return requested.isNotEmpty() && requested == key(returned)
    }

    /**
     * A mixed-search card may display a collaborator's name while pointing at
     * another artist's real UC channel. Compare both names to the *search*
     * rather than blindly accepting either the card label or the browse ID.
     * This decision applies only to search-originated artist rows: credits
     * opened from songs still require a strict name/id match.
     */
    enum class SearchMismatch { NONE, USE_LANDING_IDENTITY, RECOVER_CARD_IDENTITY }

    fun searchMismatch(query: String, cardTitle: String, landingTitle: String?): SearchMismatch {
        if (landingTitle.isNullOrBlank() || matches(cardTitle, landingTitle)) return SearchMismatch.NONE
        val cardScore = queryRelevance(query, cardTitle)
        val landingScore = queryRelevance(query, landingTitle)
        return if (cardScore > landingScore) SearchMismatch.RECOVER_CARD_IDENTITY
        else SearchMismatch.USE_LANDING_IDENTITY
    }

    /**
     * Some YouTube Music top-result responses attribute the first UC artist
     * page to a collaborator, sometimes *even in its landing header*. A plain
     * comparison against that header cannot catch the error.
     *
     * Rename the featured channel only with independent evidence from the
     * same result set: a SECOND, distinct artist channel billed under the
     * erroneous label, or at least two track credits whose primary artist is
     * the exact multiword search term. Never rename arbitrary top results
     * merely because their names differ from a query.
     *
     * The returned name is a display label, NOT a channel-ID reassignment.
     */
    fun featuredQueryCorrection(
        query: String,
        featuredName: String,
        featuredId: String,
        otherArtists: List<Pair<String, String>>,
        trackCredits: List<String>,
    ): String? {
        val wanted = key(query)
        // Exact full-name queries only. A generic search such as 'Ben',
        // 'Brazilian music', or a partial query must not rename an artist.
        if (wanted.split(' ').size < 3 || wanted.length < 9 || wanted.length > 80 ||
            matches(query, featuredName) || !featuredId.startsWith("UC")) return null

        val distinctSameNameChannel = otherArtists.any { (name, id) ->
            id.startsWith("UC") && id != featuredId && matches(featuredName, name)
        }
        val matchingPrimaryCredits = trackCredits.count { credit ->
            val normalized = key(credit)
            normalized == wanted ||
                normalized.startsWith("$wanted feat ") ||
                normalized.startsWith("$wanted ft ") ||
                normalized.startsWith("$wanted featuring ")
        }
        if (!distinctSameNameChannel && matchingPrimaryCredits < 2) return null

        val particles = setOf("da", "de", "do", "das", "dos", "e")
        return query.trim().split(Regex("\\s+")).mapIndexed { index, part ->
            if (index > 0 && part.lowercase(Locale.ROOT) in particles) {
                part.lowercase(Locale.ROOT)
            } else {
                part.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
        }.joinToString(" ")
    }

    private fun queryRelevance(query: String, artistName: String): Int {
        val term = key(query)
        val name = key(artistName)
        if (term.length < 3 || name.isBlank()) return 0
        if (term == name) return 3
        if ((" $name ").contains(" $term ")) return 2
        // Short queries such as "vanessa da" should still match "Vanessa da Mata".
        if (name.startsWith("$term ")) return 1
        return 0
    }

    private fun key(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}
