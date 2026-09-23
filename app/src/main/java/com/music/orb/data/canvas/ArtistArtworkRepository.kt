package com.music.orb.data.canvas

import com.music.orb.data.YtMusicRepository
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.settings.ArtistGenreStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-wide artist portrait resolver shared by Stats, rankings and profiles.
 *
 * A per-artist mutex collapses simultaneous requests from different periods or
 * screens into one lookup. Positive results live for the app session; misses
 * expire so a temporary provider outage does not hide a portrait indefinitely.
 */
object ArtistArtworkRepository {
    private data class CacheEntry(val url: String?, val expiresAtMs: Long)

    private const val NEGATIVE_CACHE_MS = 60_000L
    private const val PROVIDER_TIMEOUT_MS = 4_000L

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    // Portraits observed on YT Music surfaces are kept only as the second
    // fallback. They must never pre-empt Apple Music or Spotify.
    private val ytFallbackCache = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val genreCache = ConcurrentHashMap<String, List<String>>()
    private val genreMisses = ConcurrentHashMap<String, Long>()
    private val genreLocks = ConcurrentHashMap<String, Mutex>()

    fun cached(artistName: String): String? {
        val key = artistName.normalizeForMatch()
        if (key.isBlank()) return null
        val entry = cache[key] ?: return null
        if (entry.expiresAtMs < System.currentTimeMillis()) {
            cache.remove(key, entry)
            return null
        }
        return entry.url
    }

    suspend fun resolve(artistName: String, browseId: String? = null): String? {
        val key = artistName.normalizeForMatch()
        if (key.isBlank()) return null

        cache[key]?.takeIf { it.expiresAtMs >= System.currentTimeMillis() }?.let { return it.url }

        val candidate = Mutex()
        val lock = locks.putIfAbsent(key, candidate) ?: candidate
        try {
            return lock.withLock {
                cache[key]?.takeIf { it.expiresAtMs >= System.currentTimeMillis() }?.let {
                    return@withLock it.url
                }

                val artwork = fetchCatalogArtwork(
                    artistName = artistName,
                    browseId = browseId,
                    rememberedYtFallback = ytFallbackCache[key],
                )

                val expiresAt = if (artwork == null) {
                    System.currentTimeMillis() + NEGATIVE_CACHE_MS
                } else {
                    Long.MAX_VALUE
                }
                cache[key] = CacheEntry(artwork, expiresAt)
                artwork
            }
        } finally {
            locks.remove(key, lock)
        }
    }



    /**
     * Remembers a portrait already supplied by a YT Music artist surface, but
     * only as the second fallback. This deliberately does not seed [cache],
     * because Apple Music must remain the primary source and Spotify the first
     * fallback even when a YT thumbnail is already available locally.
     */
    fun remember(artistName: String, url: String?) {
        val key = artistName.normalizeForMatch()
        val usable = url.usableArtworkUrl() ?: return
        if (key.isBlank() || usable.looksLikeTrackThumbnail()) return
        ytFallbackCache[key] = usable
    }


    /**
     * Global artist-portrait priority used by Stats, Home, Search and profiles:
     * Apple Music first, Spotify as the first fallback, and YouTube Music as the
     * second fallback. Apple Music and Spotify require an exact normalized-name
     * match; when a concrete YT browse id exists, its landing page name is also
     * verified before that portrait can be accepted.
     */
    private suspend fun fetchCatalogArtwork(
        artistName: String,
        browseId: String? = null,
        rememberedYtFallback: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        // 1) Apple Music — primary source.
        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            AppleMusicCanvas.searchArtistArtwork(artistName)
        }.usableArtworkUrl()?.let { return@withContext it }

        // 2) Spotify — first fallback.
        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            SpotifyArtistArtwork.search(artistName)
        }.usableArtworkUrl()?.let { return@withContext it }

        // 3) YouTube Music — second fallback. Prefer a concrete browse id when
        // one is available; the landing page name is verified before its image
        // can be accepted.
        if (!browseId.isNullOrBlank()) {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                fetchYtMusicArtistPageArtwork(artistName, browseId)
            }.usableArtworkUrl()?.let { return@withContext it }
        }

        rememberedYtFallback.usableArtworkUrl()?.let { return@withContext it }

        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            fetchExactYtMusicArtistArtwork(artistName)
        }.usableArtworkUrl()
    }

    /** Only remote HTTPS portraits are persisted in the session cache. */
    private fun String?.usableArtworkUrl(): String? =
        this?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) }

    private fun String.looksLikeTrackThumbnail(): Boolean =
        contains("i.ytimg.com/vi/", ignoreCase = true) ||
            contains("img.youtube.com/vi/", ignoreCase = true)

    suspend fun resolveGenres(artistName: String): List<String> {
        val key = artistName.normalizeForMatch()
        if (key.isBlank()) return emptyList()
        genreCache[key]?.let { cached ->
            val canonical = normalizeOrbGenres(cached)
            if (canonical.isNotEmpty()) {
                if (canonical != cached) genreCache[key] = canonical
                return canonical
            }
        }

        // Genre knowledge must survive process death. Stats used to keep this
        // only in a ConcurrentHashMap, so a provider hiccup after a cold start
        // could make an already-known genre disappear until the next session.
        ArtistGenreStore.load(key).takeIf { it.isNotEmpty() }?.let { cached ->
            val canonical = normalizeOrbGenres(cached)
            if (canonical.isNotEmpty()) {
                genreCache[key] = canonical
                if (canonical != cached) ArtistGenreStore.save(key, canonical)
                return canonical
            }
        }
        genreMisses[key]?.takeIf { it > System.currentTimeMillis() }?.let {
            return emptyList()
        }

        val candidate = Mutex()
        val lock = genreLocks.putIfAbsent(key, candidate) ?: candidate
        try {
            return lock.withLock {
                genreCache[key]?.let { cached ->
                    val canonical = normalizeOrbGenres(cached)
                    if (canonical.isNotEmpty()) {
                        if (canonical != cached) genreCache[key] = canonical
                        return@withLock canonical
                    }
                }
                ArtistGenreStore.load(key).takeIf { it.isNotEmpty() }?.let { cached ->
                    val canonical = normalizeOrbGenres(cached)
                    if (canonical.isNotEmpty()) {
                        genreCache[key] = canonical
                        if (canonical != cached) ArtistGenreStore.save(key, canonical)
                        return@withLock canonical
                    }
                }

                // Apple and Spotify fail independently (anonymous token expiry,
                // storefront gaps, regional catalog differences). Ask both in
                // parallel and merge their exact-name artist genre labels.
                val genres = coroutineScope {
                    val apple = async(Dispatchers.IO) {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            AppleMusicCanvas.searchArtistGenres(artistName)
                        }.orEmpty()
                    }
                    val spotify = async(Dispatchers.IO) {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                            SpotifyArtistArtwork.searchGenres(artistName)
                        }.orEmpty()
                    }
                    normalizeOrbGenres(apple.await() + spotify.await())
                }

                if (genres.isNotEmpty()) {
                    genreCache[key] = genres
                    genreMisses.remove(key)
                    ArtistGenreStore.save(key, genres)
                } else {
                    // Do not persist misses. A temporary catalogue outage must
                    // never become a durable empty Stats cache.
                    genreMisses[key] = System.currentTimeMillis() + NEGATIVE_CACHE_MS
                }
                genres
            }
        } finally {
            genreLocks.remove(key, lock)
        }
    }

    private suspend fun fetchExactYtMusicArtistArtwork(artistName: String): String? {
        val wanted = artistName.normalizeForMatch()
        val matches = YtMusicRepository.search(artistName, SearchFilter.ARTISTS)
            .getOrNull()
            ?.filterIsInstance<SearchResult.Browse>()
            ?.map { it.item }
            ?.filter {
                it.type == BrowseType.ARTIST && it.title.normalizeForMatch() == wanted
            }
            .orEmpty()

        // Do not trust the search-card thumbnail alone. Follow the concrete
        // artist browse id and verify that the landing-page name still matches.
        // This eliminates same-name catalogue collisions and stray album/video
        // thumbnails from every ArtistArtworkRepository consumer.
        for (match in matches) {
            fetchYtMusicArtistPageArtwork(artistName, match.browseId)?.let { return it }
        }
        return null
    }

    private suspend fun fetchYtMusicArtistPageArtwork(artistName: String, browseId: String): String? {
        if (browseId.isBlank()) return null
        val wanted = artistName.normalizeForMatch()
        val page = YtMusicRepository.artistLandingPageV151(browseId).getOrNull() ?: return null
        val pageName = page.name?.normalizeForMatch().orEmpty()
        if (pageName.isBlank() || pageName != wanted) return null
        return page.thumbnailUrl
            .usableArtworkUrl()
            ?.takeUnless { it.looksLikeTrackThumbnail() }
    }
}
