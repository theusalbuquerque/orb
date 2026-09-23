package com.music.orb.data

import android.content.Context
import android.content.SharedPreferences
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a display credit into the real artist entities used by Stats/counts.
 *
 * Ampersands and commas are deliberately not treated as separators on their
 * own: both are common inside real artist names ("Mumford & Sons",
 * "Earth, Wind & Fire", "Tyler, The Creator"). Ambiguous credits are split
 * only when the YouTube Music artist catalogue can validate every resulting
 * piece as an artist entity. Artist names already observed behind an ARTIST
 * browse endpoint are registered locally and need no extra lookup.
 */
object ArtistCreditResolver {
    private const val LOOKUP_CONCURRENCY = 4
    private const val MAX_SPLIT_DEPTH = 6
    private const val PREFS_NAME = "orb_artist_credit_resolver"
    private const val KEY_RESOLVED = "resolved_credits_v1"
    private const val KEY_VERIFIED = "verified_artists_v1"

    @Volatile
    private lateinit var prefs: SharedPreferences
    private val verifiedArtists = ConcurrentHashMap.newKeySet<String>()
    private val verifiedDisplayNames = ConcurrentHashMap<String, String>()
    private val exactLookupCache = ConcurrentHashMap<String, Boolean>()
    private val resolvedCredits = ConcurrentHashMap<String, List<String>>()

    // One application-lifetime writer. Catalogue readers only enqueue a signal;
    // they never serialize JSON, access preferences or wait for this writer.
    private val persistenceRequests = Channel<Unit>(Channel.CONFLATED)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceWorker = persistenceScope.launch {
        for (request in persistenceRequests) {
            // Fixed batching window (not debounce): continuous parsing cannot
            // keep postponing persistence indefinitely.
            delay(500L)
            persistenceRequests.tryReceive() // At most one conflated pending signal.
            if (!::prefs.isInitialized) continue
            try {
                writeStateSnapshot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DebugLog.w("OrbArtistCache", "Artist cache write failed; retrying", failure)
                delay(2_000L)
                persistenceRequests.trySend(Unit)
            }
        }
    }

    /** Strong collaboration separators are safe even if the catalogue is offline. */
    private val strongSeparator = Regex(
        """\s+(?:feat\.?|ft\.?|featuring|with|vs\.?|x)\s+""",
        setOf(RegexOption.IGNORE_CASE),
    )

    /**
     * Ambiguous separators require entity validation before they are split.
     * The combined `, &` form must be matched first so credits like
     * `Nu Aspect, Arkaden, & Sam Welch` can become three validated artists
     * instead of leaving a leading ampersand attached to the last name.
     */
    private val ambiguousSeparator = Regex(
        """\s*,\s*&\s*|\s*&\s*|\s*,\s*|\s*;\s*""",
    )

    /**
     * Restores artist-credit decisions learned from authoritative catalogue
     * links and previous collaboration resolutions. This makes Stats keep the
     * split after a process restart instead of re-learning the same credit.
     */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        runCatching {
            val verified = JSONObject(prefs.getString(KEY_VERIFIED, "{}") ?: "{}")
            verified.keys().forEach { key ->
                val display = verified.optString(key).cleanArtistCredit()
                if (key.isNotBlank() && display.isNotBlank()) {
                    verifiedArtists += key
                    verifiedDisplayNames[key] = display
                    exactLookupCache[key] = true
                    resolvedCredits[key] = listOf(display)
                }
            }
            val resolved = JSONObject(prefs.getString(KEY_RESOLVED, "{}") ?: "{}")
            resolved.keys().forEach { key ->
                val array = resolved.optJSONArray(key) ?: return@forEach
                val artists = buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).cleanArtistCredit()
                            .takeIf(String::isNotBlank)
                            ?.let(::add)
                    }
                }.distinctBy(String::artistIdentityKey)
                if (artists.isNotEmpty() && key !in verifiedArtists) {
                    resolvedCredits[key] = artists
                }
            }
        }
    }

    fun registerVerifiedArtist(name: String?) {
        val display = name?.cleanArtistCredit().orEmpty()
        if (display.isBlank()) return
        val key = display.artistIdentityKey()
        if (key.isBlank()) return
        val newlyVerified = verifiedArtists.add(key)
        val previousDisplay = verifiedDisplayNames.putIfAbsent(key, display)
        val canonical = previousDisplay ?: display
        exactLookupCache[key] = true
        // An authoritative ARTIST endpoint overrides an earlier split, while
        // repeated rows for an unchanged artist do not schedule a disk write.
        val previousCredit = resolvedCredits.put(key, listOf(canonical))
        if (newlyVerified || previousDisplay == null || previousCredit != listOf(canonical)) {
            persistState()
        }
    }

    /**
     * Synchronous view used by aggregators after [resolveAll] has warmed the
     * ambiguous names. When no decision is cached, only explicit collaboration
     * markers are split; comma/& remain intact rather than risking a fake artist.
     */
    fun creditsForCounting(raw: String): List<String> {
        val display = raw.cleanArtistCredit()
        if (display.isBlank()) return emptyList()
        val key = display.artistIdentityKey()
        resolvedCredits[key]?.let { return it }
        if (key in verifiedArtists) {
            return listOf(verifiedDisplayNames[key] ?: display)
        }

        // The parser registers every linked ARTIST run independently. If a
        // display credit later arrives as "A, B & C", use those authoritative
        // entities immediately instead of waiting for another network lookup.
        cachedVerifiedAmbiguousSplit(display)?.let { split ->
            resolvedCredits[key] = split
            persistState()
            return split
        }

        val strongParts = display.split(strongSeparator)
            .map(String::cleanArtistCredit)
            .filter(String::isNotBlank)
        return if (strongParts.size > 1) {
            strongParts.distinctBy(String::artistIdentityKey)
        } else {
            obviousMultiArtistList(display)
                ?: listOf(display)
        }
    }

    /** Resolve only the artist strings that actually contain collaboration punctuation. */
    suspend fun resolveAll(rawNames: Collection<String>) = supervisorScope {
        val names = rawNames
            .map(String::cleanArtistCredit)
            .filter { it.isNotBlank() && it.needsArtistResolution() }
            .distinctBy(String::artistIdentityKey)
        if (names.isEmpty()) return@supervisorScope

        val semaphore = Semaphore(LOOKUP_CONCURRENCY)
        names.map { name ->
            launch {
                semaphore.withPermit { runCatching { resolve(name) } }
            }
        }.joinAll()
    }

    suspend fun resolve(raw: String): List<String> {
        val display = raw.cleanArtistCredit()
        if (display.isBlank()) return emptyList()
        val key = display.artistIdentityKey()
        resolvedCredits[key]?.let { return it }
        if (key in verifiedArtists) {
            return listOf(verifiedDisplayNames[key] ?: display)
        }

        cachedVerifiedAmbiguousSplit(display)?.let { split ->
            resolvedCredits[key] = split
            persistState()
            return split
        }

        // The whole name is checked first. This single rule is what protects
        // real acts such as Angus & Julia Stone, Mumford & Sons, Earth, Wind &
        // Fire, Simon & Garfunkel, Tyler, The Creator, etc.
        when (isExactArtist(display)) {
            true -> return listOf(display)
            null -> {
                // No trustworthy catalogue answer: split only explicit markers.
                // Ambiguous punctuation stays atomic until a later successful lookup.
                return creditsForCounting(display)
            }
            false -> Unit
        }

        val strongParts = display.split(strongSeparator)
            .map(String::cleanArtistCredit)
            .filter(String::isNotBlank)
        val resolved = if (strongParts.size > 1) {
            strongParts.flatMap { part ->
                splitAmbiguousIntoVerifiedArtists(part, depth = 0) ?: listOf(part)
            }
        } else {
            splitAmbiguousIntoVerifiedArtists(display, depth = 0)
                // Once the catalogue has explicitly rejected the entire credit
                // as one artist, a 3+-name comma/& list is safe to interpret as
                // a collaboration even if one leaf lookup is temporarily flaky.
                ?: obviousMultiArtistList(display)
                ?: listOf(display)
        }
            .map(String::cleanArtistCredit)
            .filter(String::isNotBlank)
            .distinctBy(String::artistIdentityKey)

        // Do not freeze an unresolved comma/& credit as atomic. A later source
        // parse may register its component artists, or a future catalogue lookup
        // may succeed after a transient failure.
        if (resolved.size > 1 || key in verifiedArtists) {
            resolvedCredits[key] = resolved
            persistState()
        }
        return resolved
    }

    /**
     * Finds a partition where every leaf is a verified artist entity. Among all
     * valid partitions we keep the one with the fewest artists, which prevents
     * over-splitting names that contain punctuation internally.
     */
    private suspend fun splitAmbiguousIntoVerifiedArtists(
        value: String,
        depth: Int,
    ): List<String>? {
        val display = value.cleanArtistCredit()
        if (display.isBlank() || depth > MAX_SPLIT_DEPTH) return null

        when (isExactArtist(display)) {
            true -> return listOf(display)
            null -> return null
            false -> Unit
        }

        val matches = ambiguousSeparator.findAll(display).toList()
        if (matches.isEmpty()) return null

        var best: List<String>? = null
        for (match in matches) {
            val left = display.substring(0, match.range.first).cleanArtistCredit()
            val right = display.substring(match.range.last + 1).cleanArtistCredit()
            if (left.isBlank() || right.isBlank()) continue

            val leftParts = splitAmbiguousIntoVerifiedArtists(left, depth + 1) ?: continue
            val rightParts = splitAmbiguousIntoVerifiedArtists(right, depth + 1) ?: continue
            val candidate = (leftParts + rightParts).distinctBy(String::artistIdentityKey)
            if (candidate.size < 2) continue
            if (best == null || candidate.size < best.size) best = candidate
        }
        return best
    }

    private fun cachedVerifiedAmbiguousSplit(value: String): List<String>? {
        val parts = value.split(ambiguousSeparator)
            .map(String::cleanArtistCredit)
            .filter(String::isNotBlank)
            .distinctBy(String::artistIdentityKey)
        if (parts.size < 2) return null
        if (parts.any { it.artistIdentityKey() !in verifiedArtists }) return null
        return parts.map { part ->
            verifiedDisplayNames[part.artistIdentityKey()] ?: part
        }
    }

    private fun obviousMultiArtistList(value: String): List<String>? {
        val hasComma = ',' in value
        val hasJoiner = '&' in value || ';' in value
        if (!hasComma && !hasJoiner) return null
        val parts = value.split(ambiguousSeparator)
            .map(String::cleanArtistCredit)
            .filter(String::isNotBlank)
            .distinctBy(String::artistIdentityKey)
        return parts.takeIf {
            it.size >= 3 || (it.size >= 2 && hasComma && hasJoiner)
        }
    }

    /** true/false for a completed catalogue search; null when the lookup itself failed. */
    private suspend fun isExactArtist(name: String): Boolean? {
        val display = name.cleanArtistCredit()
        val key = display.artistIdentityKey()
        if (key.isBlank()) return false
        if (key in verifiedArtists) return true
        exactLookupCache[key]?.let { return it }

        val result = YtMusicRepository.search(display, SearchFilter.ARTISTS)
        if (result.isFailure) return null
        val exact = result.getOrNull()
            .orEmpty()
            .filterIsInstance<SearchResult.Browse>()
            .map { it.item }
            .any { item ->
                item.type == BrowseType.ARTIST && item.title.artistIdentityKey() == key
            }
        exactLookupCache[key] = exact
        return exact
    }

    private fun persistState() {
        persistenceRequests.trySend(Unit)
    }

    /** Only the single IO worker calls this; no lock is shared with readers. */
    private fun writeStateSnapshot() {
        val startedAt = System.nanoTime()
        // Concurrent maps tolerate updates while iterating. Every update made
        // during this write queues another pass, so the latest state is retained.
        val target = prefs
        val verified = JSONObject()
        verifiedDisplayNames.forEach { (key, display) ->
            if (key.isNotBlank() && display.isNotBlank()) verified.put(key, display)
        }
        val resolved = JSONObject()
        resolvedCredits.forEach { (key, artists) ->
            if (key.isNotBlank() && artists.isNotEmpty() && key !in verifiedArtists) {
                resolved.put(key, JSONArray(artists))
            }
        }
        val saved = target.edit()
            .putString(KEY_VERIFIED, verified.toString())
            .putString(KEY_RESOLVED, resolved.toString())
            .commit()
        check(saved) { "Could not persist artist credits" }
        DebugLog.d("OrbArtistCache", "saved batch in ${(System.nanoTime() - startedAt) / 1_000_000L}ms")
    }

    private fun String.needsArtistResolution(): Boolean =
        strongSeparator.containsMatchIn(this) || ambiguousSeparator.containsMatchIn(this)
}

private fun String.cleanArtistCredit(): String =
    trim().replace(Regex("""\s+"""), " ")

private fun String.artistIdentityKey(): String =
    Normalizer.normalize(cleanArtistCredit(), Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}+"""), "")
        .replace('’', '\'')
        .lowercase(Locale.ROOT)
        .trim()
