package com.music.orb.data

import com.music.orb.data.DebugLog as Log
import com.music.orb.data.innertube.Innertube
import com.music.orb.data.innertube.InnertubeParser
import com.music.orb.data.model.Account
import com.music.orb.data.model.ArtistPage
import com.music.orb.data.model.ArtistLink
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.HomeFeed
import com.music.orb.data.model.HomeShelf
import com.music.orb.data.model.LibraryPage
import com.music.orb.data.model.LibraryState
import com.music.orb.data.model.LikeStatus
import com.music.orb.data.model.PlaylistPrivacy
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.Song
import com.music.orb.data.model.SongCredits
import com.music.orb.data.model.SongMenu
import com.music.orb.data.model.UserPlaylist
import com.music.orb.data.model.YouTubeAccountIdentity
import com.music.orb.data.sources.TrackMatcher
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.RecentPlaybackStore
import com.music.orb.data.social.ListeningActivity
import com.music.orb.data.social.SocialRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Suspend API over Innertube. Every call returns a Result so the UI can show a real error. */
object YtMusicRepository {

    private const val TAG = "BitChord"
    private val resolvedArtistLinksCache = ConcurrentHashMap<String, List<ArtistLink>>()
    private val preferredAlbumVersionCache = ConcurrentHashMap<String, Song>()

    /**
     * The personalised feed, led by what was actually just played and padded
     * out with new releases.
     *
     * FEmusic_home alone is thin when signed out (three shelves), so extra
     * rows are pulled from FEmusic_new_releases, which carries genuinely
     * different content. Charts (Daily/Weekly, Trending) live under Explore
     * in the real app — see [explore] — not here. Titles are de-duped in
     * case the home feed already surfaced the same shelf.
     *
     * FEmusic_home's own continuation token comes back, for [moreHome] —
     * signed in, it keeps paging into mood mixes and more personalised
     * shelves the same way the official app does as you scroll; signed out
     * it's empty and there's nothing more to fetch.
     */
    suspend fun home(): Result<HomeFeed> = call("home") {
        // Suggestions is the first visible surface. Do not hold FEmusic_home
        // behind the Orb/Supabase cross-device recent-listening request: local
        // qualified recents are already on disk and the merged remote history
        // is refreshed independently once Home is visible.
        val localRecent = localRecentlyPlayed()
        val home = Innertube.browse("FEmusic_home")

        // Do not hold the top of Home hostage to FEmusic_new_releases either.
        // That endpoint only contributes shelves below the personalised first
        // page and is fetched later by [homeSupplemental].
        val shelves = listOfNotNull(localRecent) + InnertubeParser.parseHome(home)
        HomeFeed(shelves, InnertubeParser.continuationToken(home))
    }

    /**
     * Lower-priority Home shelves. Kept separate so Listen Now can paint and
     * preflight its visible cards before spending a round trip on content the
     * user cannot see without scrolling.
     */
    suspend fun homeSupplemental(): Result<List<HomeShelf>> = call("home:supplemental") {
        shelvesOf("FEmusic_new_releases")
    }

    /**
     * Account-owned sections required by Orb's curated For You page.
     *
     * These deliberately bypass the app's selectable Library backend: the Home
     * shelves named "Da sua biblioteca" and "Recaps" are YouTube Music surfaces
     * and therefore must reflect the linked YouTube account even when Orb's own
     * library remains the authoritative save destination elsewhere in the app.
     */
    suspend fun forYouYouTubeLibrarySection(): Result<HomeShelf?> = call("home:for-you-library") {
        if (!Innertube.hasAccountSession) return@call null

        coroutineScope {
            val likedDeferred = async {
                runCatching { songsPaged(LIKED_MUSIC) }.getOrDefault(emptyList())
            }
            val addedDeferred = async {
                runCatching { songsPaged(LIBRARY_SONGS) }.getOrDefault(emptyList())
            }
            val liked = likedDeferred.await()
            val likedIds = liked.mapTo(HashSet()) { it.videoId }
            val added = addedDeferred.await().filterNot { it.videoId in likedIds }
            val songs = distinctShelfItems((liked + added).map { it.asShelfItem() })
                .take(FOR_YOU_LIBRARY_LIMIT)
            songs.takeIf { it.isNotEmpty() }?.let { HomeShelf("Da sua biblioteca", it) }
        }
    }

    suspend fun forYouRecapsSection(): Result<HomeShelf?> = call("home:for-you-recaps") {
        if (!Innertube.hasAccountSession) return@call null

        coroutineScope {
            val savedPlaylistsDeferred = async {
                runCatching {
                    InnertubeParser.parseLibraryItems(Innertube.browse(LIBRARY_PLAYLISTS))
                }.getOrDefault(emptyList())
            }
            val recapHomeDeferred = async {
                val recapItems = mutableListOf<ShelfItem>()
                val first = runCatching { Innertube.browse("FEmusic_home") }.getOrNull()
                    ?: return@async emptyList()

                fun normalized(value: String): String = value
                    .lowercase(Locale.ROOT)
                    .replace('&', ' ')
                    .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
                    .trim()

                fun isRecapText(value: String): Boolean {
                    val valueNormalized = normalized(value)
                    return valueNormalized.contains("recap") ||
                        valueNormalized.contains("retrospectiva") ||
                        valueNormalized.contains("resumen")
                }

                fun collect(shelves: List<HomeShelf>) {
                    shelves.forEach { shelf ->
                        val shelfIsRecap = isRecapText(shelf.title) || isRecapText(shelf.subtitle)
                        if (shelfIsRecap) {
                            recapItems += shelf.items
                        } else {
                            recapItems += shelf.items.filter { item ->
                                isRecapText(item.title) || isRecapText(item.subtitle)
                            }
                        }
                    }
                }

                collect(InnertubeParser.parseHome(first))
                var continuation = InnertubeParser.continuationToken(first)
                var page = 0
                // Stop as soon as the visible shelf is full. Walking all six
                // continuation pages before publishing a recap made an otherwise
                // independent Home section wait for content nobody could see.
                while (
                    continuation != null &&
                    recapItems.size < FOR_YOU_RECAP_LIMIT &&
                    page++ < FOR_YOU_RECAP_HOME_PAGES
                ) {
                    val response = runCatching { Innertube.browseContinuation(continuation) }
                        .getOrNull() ?: break
                    collect(InnertubeParser.parseHomeContinuation(response))
                    continuation = InnertubeParser.continuationToken(response)
                }
                distinctShelfItems(recapItems)
            }

            val savedRecaps = savedPlaylistsDeferred.await().filter { item ->
                val text = "${item.title} ${item.subtitle}".lowercase(Locale.ROOT)
                text.contains("recap") || text.contains("retrospectiva") || text.contains("resumen")
            }
            val recaps = distinctShelfItems(recapHomeDeferred.await() + savedRecaps)
                .take(FOR_YOU_RECAP_LIMIT)
            recaps.takeIf { it.isNotEmpty() }?.let { HomeShelf("Recaps", it) }
        }
    }

    /**
     * Compatibility aggregate for callers that still want both account-owned
     * shelves in one result. The Home ViewModel uses the split functions above
     * so Library and Recaps can paint independently as soon as each finishes.
     */
    suspend fun forYouYouTubeSections(): Result<List<HomeShelf>> = call("home:for-you-youtube-sections") {
        if (!Innertube.hasAccountSession) return@call emptyList()
        coroutineScope {
            val library = async { forYouYouTubeLibrarySection().getOrNull() }
            val recaps = async { forYouRecapsSection().getOrNull() }
            listOfNotNull(library.await(), recaps.await())
        }
    }

    /**
     * More Home shelves past [home]'s first page, following FEmusic_home's
     * own continuation — the lever the official app pulls as you scroll
     * rather than a fixed one-shot page. "Recently played" and
     * FEmusic_new_releases are one-shot and don't participate.
     */
    suspend fun moreHome(token: String): Result<HomeFeed> = call("home:more") {
        val response = Innertube.browseContinuation(token)
        HomeFeed(
            shelves = InnertubeParser.parseHomeContinuation(response),
            continuation = InnertubeParser.continuationToken(response),
        )
    }

    /** Fast device-local recents used on Home's latency-critical first pass. */
    private fun localRecentlyPlayed(): HomeShelf? {
        val songs = RecentPlaybackStore.snapshotEntries()
            .sortedByDescending { it.playedAtMs }
            .take(RECENT_LIMIT)
            .map { it.song }
        if (songs.isEmpty()) return null
        return HomeShelf(
            title = RECENT_TITLE,
            items = songs.map { it.asShelfItem() },
        )
    }

    /** Ten qualified playback sessions, merged with the signed-in Orb account. */
    private suspend fun recentlyPlayed(): HomeShelf? {
        val local = RecentPlaybackStore.snapshotEntries().map { entry ->
            RecentItem(
                song = entry.song,
                playedAtMs = entry.playedAtMs,
                sessionKey = entry.sessionKey(),
            )
        }
        val remote = if (SocialRepository.currentUserId() == null) {
            emptyList()
        } else {
            runCatching { SocialRepository.myRecentListeningActivity(RECENT_LIMIT) }
                .getOrDefault(emptyList())
                .mapNotNull { it.asRecentItem() }
        }
        val songs = (local + remote)
            .distinctBy(RecentItem::sessionKey)
            .sortedByDescending(RecentItem::playedAtMs)
            .take(RECENT_LIMIT)
            .map(RecentItem::song)
        if (songs.isEmpty()) return null
        return HomeShelf(
            title = RECENT_TITLE,
            items = songs.map { it.asShelfItem() },
        )
    }

    /** Refreshes only Home's listening-history shelf, leaving every other shelf intact. */
    suspend fun recentlyPlayedShelf(): Result<HomeShelf?> = call("home:recently-played") {
        recentlyPlayed()
    }

    /**
     * Account history with the full Song links intact. Home's visible
     * "Recently played" shelf deliberately strips those links down to cards,
     * but Discover and Releases need artist/album ids to know what the listener
     * has genuinely heard before.
     */
    suspend fun historySongs(): Result<List<Song>> = call("history:songs") {
        if (!Innertube.hasAccountSession) emptyList() else historySongsRaw()
    }

    private suspend fun historySongsRaw(): List<Song> = songsPaged(HISTORY)

    private const val HISTORY = "FEmusic_history"
    private const val RECENT_TITLE = "Recently played"

    private const val RECENT_LIMIT = 10

    private data class RecentItem(
        val song: Song,
        val playedAtMs: Long,
        val sessionKey: String,
    )

    private fun ListeningActivity.asRecentItem(): RecentItem? {
        val startedMs = runCatching { Instant.parse(startedAt).toEpochMilli() }.getOrNull()
            ?: return null
        val playedAtMs = runCatching { Instant.parse(finishedAt).toEpochMilli() }.getOrNull()
            ?: startedMs
        val duration = durationMs?.takeIf { it > 0L }?.div(1000L)?.let { seconds ->
            "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
        return RecentItem(
            song = Song(
                videoId = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = artworkUrl,
                durationText = duration,
                albumName = album,
                sourcePlaylistId = sourcePlaylistId,
                sourcePlaylistTitle = sourcePlaylistTitle,
                sourcePlaylistArtworkUrl = sourcePlaylistArtworkUrl,
            ),
            playedAtMs = playedAtMs,
            sessionKey = "$videoId|$startedMs",
        )
    }

    private suspend fun shelvesOf(
        browseId: String,
        region: String? = null,
    ): List<HomeShelf> =
        InnertubeParser.parseHome(Innertube.browse(browseId, regionOverride = region))

    /**
     * Explore: moods & genres from FEmusic_explore, plus the Daily/Weekly/
     * Trending charts, which YouTube Music serves from a separate browse id
     * and surfaces under Explore rather than Home.
     */
    suspend fun explore(): Result<List<HomeShelf>> = call("explore") {
        coroutineScope {
            val feeds = listOf("FEmusic_explore", "FEmusic_charts")
                .map { id -> async { runCatching { shelvesOf(id) }.getOrDefault(emptyList()) } }
                .awaitAll()
            val seen = mutableSetOf<String>()
            feeds.flatten().filter { seen.add(it.title.lowercase()) }
        }
    }

    /**
     * Album-focused hub used by the expressive Home header.
     *
     * The first shelf is a compact notification carousel containing only new
     * full albums by artists the listener explicitly marked as favorites in
     * Orb. The remaining shelves stay album-only: personalised new releases
     * and catalogue recommendations that exclude albums already represented in
     * listening history, Liked Music or the YouTube Music library.
     */
    suspend fun releasesHub(
        favoriteArtists: Set<String> = emptySet(),
    ): Result<List<HomeShelf>> = call("home:albums-hub") {
        coroutineScope {
            val region = deviceCountry()
            val releasesDeferred = async {
                runCatching { shelvesOf("FEmusic_new_releases", region) }
                    .getOrDefault(emptyList())
            }
            val homeDeferred = async {
                runCatching { shelvesOf("FEmusic_home", region) }
                    .getOrDefault(emptyList())
            }
            val exploreDeferred = async {
                runCatching { shelvesOf("FEmusic_explore", region) }
                    .getOrDefault(emptyList())
            }
            val historyDeferred = async {
                if (!Innertube.hasAccountSession) emptyList()
                else runCatching { historySongsRaw() }.getOrDefault(emptyList())
            }
            val likedDeferred = async {
                if (!Innertube.hasAccountSession) emptyList()
                else runCatching { songsPaged(LIKED_MUSIC) }.getOrDefault(emptyList())
            }
            val libraryDeferred = async {
                if (!Innertube.hasAccountSession) emptyList()
                else runCatching { songsPaged(LIBRARY_SONGS) }.getOrDefault(emptyList())
            }
            val releaseShelves = releasesDeferred.await()
            val homeShelves = homeDeferred.await()
            val exploreShelves = exploreDeferred.await()
            val knownSongs = distinctSongs(
                historyDeferred.await() + likedDeferred.await() + libraryDeferred.await(),
            )

            val normalizedFavorites = favoriteArtists
                .map(::normalizedArtist)
                .filter { it.isNotBlank() }
                .toSet()

            val catalogueNewAlbums = distinctShelfItems(
                releaseShelves.flatMap { it.items },
            ).filter { it.isLikelyFullAlbum() }

            // FEmusic_home is account-personalised; release shelves found here
            // therefore make a better "similar to your taste" source than the
            // generic regional new-release page.
            val personalisedNewAlbums = distinctShelfItems(
                homeShelves
                    .filter { it.title.isReleaseShelfLabel() }
                    .flatMap { it.items },
            ).filter { it.isLikelyFullAlbum() }

            val newAlbumPool = distinctShelfItems(personalisedNewAlbums + catalogueNewAlbums)
            val favoriteArtistNewAlbums = newAlbumPool.filter { item ->
                item.matchesAnyArtist(normalizedFavorites)
            }
            // Prefer account-personalised releases, but never let the Albums
            // page lose its New albums shelf just because YouTube omitted that
            // carousel from this particular Home response. FEmusic_new_releases
            // is YouTube Music's rolling recent-release catalogue (the source
            // used for the requested ~last-two-month window), so it is the
            // authoritative fallback rather than an empty shelf.
            val similarNewAlbums = distinctShelfItems(
                (personalisedNewAlbums + catalogueNewAlbums)
                    .filterNot { it.matchesAnyArtist(normalizedFavorites) },
            )

            val knownAlbumIds = knownSongs.mapNotNullTo(hashSetOf()) { it.albumId }
            val knownAlbumNames = knownSongs.mapNotNullTo(hashSetOf()) { song ->
                song.albumName?.normalizeIdentity()?.takeIf { it.isNotBlank() }
            }.apply {
                addAll(
                    RecentPlaybackStore.snapshotEntries().mapNotNull { entry ->
                        entry.song.albumName?.normalizeIdentity()?.takeIf { it.isNotBlank() }
                    },
                )
            }

            val recommendationCandidates = distinctShelfItems(
                homeShelves.flatMap { it.items } +
                    exploreShelves.flatMap { it.items } +
                    catalogueNewAlbums,
            ).filter { it.isLikelyFullAlbum() }

            val recommendationPool = recommendationCandidates.filter { item ->
                val browseId = item.browseId ?: return@filter false
                browseId !in knownAlbumIds &&
                    item.title.normalizeIdentity() !in knownAlbumNames
            }

            val alreadyShown = (favoriteArtistNewAlbums + similarNewAlbums)
                .mapNotNullTo(hashSetOf()) { it.browseId }
            val freshSuggestions = recommendationPool.filter { item ->
                val browseId = item.browseId ?: return@filter false
                browseId !in alreadyShown
            }
            // History/library exclusion is a preference, not a reason for an
            // entire section to disappear. If the personalised pool is already
            // familiar, fall back to other account/explore albums while still
            // excluding cards already used by New albums.
            val albumSuggestions = distinctShelfItems(
                freshSuggestions + recommendationCandidates.filter { item ->
                    val browseId = item.browseId ?: return@filter false
                    browseId !in alreadyShown
                },
            )

            buildList {
                addIfItems(
                    FAVORITE_ALBUM_RELEASES_TITLE,
                    favoriteArtistNewAlbums.take(ALBUM_ALERT_LIMIT),
                )
                addIfItems("New albums", similarNewAlbums.take(ALBUM_SHELF_LIMIT))
                addIfItems("Albums you may like", albumSuggestions.take(ALBUM_SHELF_LIMIT))
            }
        }
    }

    /**
     * Dedicated Em alta feed. Current YouTube Music charts expose country
     * selection through `formData.selectedValues`; Global is the special ZZ
     * country. Authenticated accounts can receive separate Daily and Weekly
     * playlist carousels. Orb reads the ranked Top Songs shelf directly for
     * the two Top 10 lists; Daily/Weekly playlist carousels remain separate
     * because those are YouTube's music-video chart playlists.
     */
    suspend fun trendingHub(): Result<List<HomeShelf>> = call("home:trending-hub") {
        coroutineScope {
            val region = deviceCountry()
            val localChartsDeferred = async {
                runCatching {
                    Innertube.browse(
                        browseId = "FEmusic_charts",
                        regionOverride = region,
                        selectedCountry = region,
                    )
                }.getOrNull()
            }
            val globalChartsDeferred = async {
                // ZZ is the special *chart selection* for Global, but it is not
                // a valid client geolocation (`context.client.gl`). Keep a real
                // country in the client context and send ZZ only in formData.
                // Sending gl=ZZ makes Innertube reject the browse request with
                // HTTP 400 INVALID_ARGUMENT.
                runCatching {
                    Innertube.browse(
                        browseId = "FEmusic_charts",
                        regionOverride = region,
                        selectedCountry = "ZZ",
                    )
                }.getOrNull()
            }
            val localResponse = localChartsDeferred.await()
            val globalResponse = globalChartsDeferred.await()
            val localGroups = localResponse
                ?.let { InnertubeParser.parseChartPlaylistCarousels(it) }
                .orEmpty()
            val globalGroups = globalResponse
                ?.let { InnertubeParser.parseChartPlaylistCarousels(it) }
                .orEmpty()

            fun rankedTopTen(response: kotlinx.serialization.json.JsonObject?): List<ShelfItem> =
                response
                    ?.let(InnertubeParser::parseChartSongs)
                    .orEmpty()
                    .distinctBy { it.videoId }
                    .take(10)
                    .mapIndexed { index, song ->
                        song.asShelfItem().copy(rank = index + 1)
                    }

            // IMPORTANT: Top Songs comes from the ranked musicShelfRenderer in
            // FEmusic_charts itself. The chart carousels below are Daily/Weekly
            // *music-video* playlists and must never be used as the Top 10 song
            // source.
            val localTop = rankedTopTen(localResponse)
            val globalTop = rankedTopTen(globalResponse)
            // YouTube currently uses carousel order to distinguish Daily from
            // Weekly when both are present. If only one carousel is returned,
            // keep it under Daily rather than pretending a Weekly feed exists.
            val dailyPlaylists = distinctShelfItems(
                localGroups.getOrNull(0).orEmpty() + globalGroups.getOrNull(0).orEmpty(),
            )
            val weeklyPlaylists = distinctShelfItems(
                localGroups.getOrNull(1).orEmpty() + globalGroups.getOrNull(1).orEmpty(),
            )

            buildList {
                addIfItems("Top 10 global", globalTop)
                addIfItems("Top 10 nacional", localTop)
                addIfItems("Daily Charts", dailyPlaylists)
                addIfItems("Weekly Charts", weeklyPlaylists)
            }
        }
    }

    /**
     * Public playlists authored by other YouTube Music users.
     *
     * YouTube does not expose one stable dedicated browse id for this surface:
     * it is mixed into Home and its continuations, and the shelf wording changes
     * with locale/A-B tests. Walk a few cached-size Home pages, keep only playlist
     * cards that either live in an explicitly community/user-made shelf or look
     * like ordinary public PL playlists with a non-YouTube creator, and exclude
     * the currently selected channel by name/handle when that metadata is known.
     */
    suspend fun communityPlaylistsHub(): Result<List<HomeShelf>> = call("home:community-playlists") {
        coroutineScope {
            val accountDeferred = async {
                if (!Innertube.hasAccountSession) null else account().getOrNull()
            }
            val first = Innertube.browse("FEmusic_home")
            val shelves = InnertubeParser.parseHome(first).toMutableList()
            var continuation = InnertubeParser.continuationToken(first)
            var page = 0
            while (continuation != null && page++ < COMMUNITY_HOME_PAGES) {
                val response = runCatching { Innertube.browseContinuation(continuation) }
                    .getOrNull() ?: break
                shelves += InnertubeParser.parseHomeContinuation(response)
                continuation = InnertubeParser.continuationToken(response)
            }

            val account = accountDeferred.await()
            val ownIdentities = listOfNotNull(
                account?.name?.normalizeIdentity()?.takeIf { it.isNotBlank() },
                account?.handle?.removePrefix("@")?.normalizeIdentity()?.takeIf { it.isNotBlank() },
            ).toSet()
            val playlists = distinctShelfItems(
                shelves.flatMap { shelf ->
                    val explicitCommunityShelf = shelf.title.isCommunityPlaylistShelfLabel()
                    shelf.items.filter { item ->
                        item.type == BrowseType.PLAYLIST &&
                            item.browseId != null &&
                            (explicitCommunityShelf || item.looksLikeCommunityPlaylist(ownIdentities))
                    }
                },
            ).take(COMMUNITY_PLAYLIST_LIMIT)

            if (playlists.isEmpty()) emptyList()
            else listOf(HomeShelf(title = "Community playlists", items = playlists))
        }
    }

    /**
     * Discover is exclusion-based. History alone is insufficient: a liked or
     * library track can be absent from the visible history feed, and YouTube
     * may expose another video id for the same recording. The exclusion set
     * therefore unions history + Liked Music + library songs and also compares
     * normalized title/artist signatures.
     */
    suspend fun discoverHub(): Result<List<HomeShelf>> = call("home:discover-hub") {
        coroutineScope {
            val historyDeferred = async {
                if (!Innertube.hasAccountSession) emptyList()
                else runCatching { historySongsRaw() }.getOrDefault(emptyList())
            }
            val likedDeferred = async {
                if (!Innertube.hasAccountSession) emptyList()
                else runCatching { songsPaged(LIKED_MUSIC) }.getOrDefault(emptyList())
            }
            val libraryDeferred = async {
                if (!Innertube.hasAccountSession) emptyList()
                else runCatching { songsPaged(LIBRARY_SONGS) }.getOrDefault(emptyList())
            }
            val candidatesDeferred = listOf(
                "FEmusic_home",
                "FEmusic_explore",
                "FEmusic_new_releases",
            ).map { browseId ->
                async { runCatching { shelvesOf(browseId) }.getOrDefault(emptyList()) }
            }

            val knownSongs = (historyDeferred.await() + likedDeferred.await() + libraryDeferred.await())
                .distinctBy { it.videoId }
            val candidates = distinctShelfItems(
                candidatesDeferred.awaitAll().flatten().flatMap { it.items },
            )

            val knownVideoIds = knownSongs.mapTo(hashSetOf()) { it.videoId }
            val knownSignatures = knownSongs.mapTo(hashSetOf()) { it.recordingSignature() }
            val knownArtistIds = knownSongs.mapNotNullTo(hashSetOf()) { it.artistId }
            val knownAlbumIds = knownSongs.mapNotNullTo(hashSetOf()) { it.albumId }
            val knownAlbumNames = knownSongs.mapNotNullTo(hashSetOf()) { song ->
                song.albumName?.normalizeIdentity()?.takeIf { it.isNotBlank() }
            }
            val knownArtistNames = knownSongs.mapTo(hashSetOf()) { normalizedArtist(it.artist) }

            val unheardSongs = candidates.filter { item ->
                val videoId = item.videoId ?: return@filter false
                videoId !in knownVideoIds && item.recordingSignature() !in knownSignatures
            }
            val unheardArtists = candidates.filter { item ->
                item.type == BrowseType.ARTIST &&
                    !item.browseId.isNullOrBlank() &&
                    item.browseId !in knownArtistIds &&
                    normalizedArtist(item.title) !in knownArtistNames
            }
            val unheardAlbums = candidates.filter { item ->
                item.type == BrowseType.ALBUM &&
                    !item.browseId.isNullOrBlank() &&
                    item.browseId !in knownAlbumIds &&
                    item.title.normalizeIdentity() !in knownAlbumNames
            }

            val discoveryPools = listOf(
                unheardSongs.take(DISCOVER_SHELF_LIMIT),
                unheardArtists.take(DISCOVER_SHELF_LIMIT),
                unheardAlbums.take(DISCOVER_SHELF_LIMIT),
            )
            val mixedSuggestions = buildList {
                val longest = discoveryPools.maxOfOrNull { it.size } ?: 0
                repeat(longest) { index ->
                    discoveryPools.forEach { pool -> pool.getOrNull(index)?.let(::add) }
                }
            }

            buildList {
                addIfItems("You may like this", distinctShelfItems(mixedSuggestions))
            }
        }
    }

    private fun deviceCountry(): String = Locale.getDefault().country
        .uppercase(Locale.ROOT)
        .takeIf { it.length == 2 }
        ?: "US"

    private fun String.normalizeIdentity(): String = lowercase(Locale.ROOT)
        .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun Song.recordingSignature(): String =
        "${title.normalizeIdentity()}|${normalizedArtist(artist)}"

    private fun ShelfItem.recordingSignature(): String =
        "${title.normalizeIdentity()}|${normalizedArtist(subtitle)}"

    private fun ShelfItem.releaseIdentity(): String = browseId
        ?: "${title.normalizeIdentity()}|${normalizedArtist(subtitle)}"

    private fun MutableList<HomeShelf>.addIfItems(title: String, items: List<ShelfItem>) {
        if (items.isNotEmpty()) add(HomeShelf(title = title, items = items))
    }

    private fun Song.asShelfItem() = ShelfItem(
        title = title,
        subtitle = artist,
        thumbnailUrl = thumbnailUrl,
        videoId = videoId,
        browseId = null,
        isExplicit = isExplicit,
        localUri = localUri,
        localPath = localPath,
        sourceQuality = sourceQuality,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        isVideo = isVideo,
        setVideoId = setVideoId,
        fromAutoplay = fromAutoplay,
        queuePinned = queuePinned,
        releaseYear = releaseYear,
        sourceExplicitKnown = sourceExplicitKnown,
        sourcePlaylistId = sourcePlaylistId,
        sourcePlaylistTitle = sourcePlaylistTitle,
        sourcePlaylistArtworkUrl = sourcePlaylistArtworkUrl,
    )

    private fun distinctSongs(items: List<Song>): List<Song> {
        val seen = HashSet<String>()
        return items.filter { song ->
            val key = song.videoId.takeIf { it.isNotBlank() }
                ?: "${song.title.lowercase(Locale.ROOT)}|${song.artist.lowercase(Locale.ROOT)}"
            seen.add(key)
        }
    }

    /** Album browse cards also represent singles and EPs in YouTube Music. */
    private fun ShelfItem.isLikelyFullAlbum(): Boolean {
        if (type != BrowseType.ALBUM || browseId.isNullOrBlank()) return false
        val metadata = subtitle.lowercase(Locale.ROOT)
        val nonAlbumKind = Regex("(^|[•·|\\s])(single|ep)([•·|\\s]|$)")
        return !nonAlbumKind.containsMatchIn(metadata)
    }

    private fun distinctShelfItems(items: List<ShelfItem>): List<ShelfItem> {
        val seen = HashSet<String>()
        return items.filter { item ->
            val key = item.videoId ?: item.browseId ?:
                "${item.title.lowercase(Locale.ROOT)}|${item.subtitle.lowercase(Locale.ROOT)}"
            seen.add(key)
        }
    }

    private fun normalizedArtist(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("""\s*[•·|].*$"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun ShelfItem.matchesAnyArtist(artists: Set<String>): Boolean {
        if (artists.isEmpty()) return false
        val metadata = subtitle
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
        return artists.any { artist ->
            artist.length >= 2 &&
                (metadata == artist || metadata.contains(artist))
        }
    }

    private fun String.isReleasedLabel(): Boolean {
        val value = lowercase(Locale.ROOT).trim()
        return value == "released" || value.startsWith("released ")
    }

    private fun String.isReleaseShelfLabel(): Boolean {
        val value = Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return value.contains("new release") ||
            value.contains("new album") ||
            value.contains("albums singles") ||
            value.contains("albums and singles") ||
            value.contains("singles eps") ||
            value.contains("singles and eps") ||
            value.contains("novos lancamentos") ||
            value.contains("novo album") ||
            value.contains("novos albuns") ||
            value.contains("albuns e singles") ||
            value.contains("nuevos lanzamientos") ||
            value.contains("nuevo album") ||
            value.contains("nuevos albumes") ||
            value.contains("albumes y singles") ||
            isReleasedLabel()
    }

    private fun String.isCommunityPlaylistShelfLabel(): Boolean {
        val value = normalizeIdentity()
        return value.contains("community") ||
            value.contains("comunidade") ||
            value.contains("comunidad") ||
            value.contains("user playlist") ||
            value.contains("user made") ||
            value.contains("made by listeners") ||
            value.contains("feito por usuarios") ||
            value.contains("hecho por usuarios")
    }

    private fun ShelfItem.looksLikeCommunityPlaylist(ownIdentities: Set<String>): Boolean {
        val rawId = browseId.orEmpty().removePrefix("VL")
        // Public/user playlists overwhelmingly use PL ids. Generated mixes,
        // radio, Liked Music and album-backed playlists use other prefixes.
        if (!rawId.startsWith("PL")) return false

        val meta = subtitle.normalizeIdentity()
        if (meta.isBlank()) return false
        if (COMMUNITY_SYSTEM_CREATORS.any { marker -> meta.contains(marker) }) return false
        if (ownIdentities.any { identity -> identity.isNotBlank() && meta.contains(identity) }) return false
        return true
    }

    private val COMMUNITY_SYSTEM_CREATORS = listOf(
        "youtube music",
        "youtube",
        "official",
        "album",
        "single",
        "ep",
        "radio",
        "mix",
    )

    private val SINGLE_RELEASE_WORD = Regex("(^| )(single|ep)( |$)")
    private val REMIX_RELEASE_WORD = Regex("(^| )(remix|remixes|rmx|refix|bootleg|mashup|rework|flip)( |$)")
    private const val ALBUM_VERSION_CONFIDENT_SCORE = 10

    private const val COMMUNITY_HOME_PAGES = 5
    private const val COMMUNITY_PLAYLIST_LIMIT = 60
    private const val FOR_YOU_RECAP_HOME_PAGES = 6
    private const val FOR_YOU_LIBRARY_LIMIT = 30
    private const val FOR_YOU_RECAP_LIMIT = 30

    private const val FAVORITE_ALBUM_RELEASES_TITLE = "__orb_favorite_album_releases__"
    private const val ALBUM_ALERT_LIMIT = 12
    private const val ALBUM_SHELF_LIMIT = 30
    private const val DISCOVER_SHELF_LIMIT = 30

    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> =
        call("search:${filter.name}") {
            val parsed = InnertubeParser.parseSearch(Innertube.search(query, filter.params))
            if (filter == SearchFilter.SONGS && AppSettings.prioritizeAlbumVersions.value) {
                preferAlbumVersionsInSearch(parsed)
            } else {
                parsed
            }
        }

    /** Raw Songs search for identity-sensitive internal resolvers. User-facing search may
     * collapse single/remix duplicates when the album-version preference is enabled; a resolver
     * must still see every candidate before it decides which recording is valid. */
    private suspend fun rawSongSearch(query: String): List<Song> =
        InnertubeParser.parseSearch(Innertube.search(query, SearchFilter.SONGS.params))
            .filterIsInstance<SearchResult.Track>()
            .map { it.song }

    /**
     * What YouTube Music would suggest completing [input] to, for the search
     * field's typeahead. Unfiltered on purpose: a suggestion is a query, and
     * which tab it is then run against is the user's to pick afterwards.
     */
    suspend fun searchSuggestions(input: String): Result<List<String>> =
        call("suggest") {
            InnertubeParser.parseSearchSuggestions(Innertube.searchSuggestions(input))
        }

    /**
     * The catalogue (audio-only) release of a music-video upload, found the
     * same way the "Switch to audio" toggle in the real app would land on
     * it: searching the title and artist and taking the closest song match.
     * Called before a video-tagged [Song] ever reaches the queue, so
     * playback, the mini player/notification, and YouTube's own history all
     * see the audio track — never the video upload's title, art or id.
     *
     * Matched through [TrackMatcher] rather than a bare title compare, for
     * the same reason [SourceResolver][com.music.orb.data.sources.SourceResolver]
     * does: a query for a niche title can come back with nothing that is
     * really the recording, and taking the first row regardless was landing
     * on a same-language, wrong-song hit — a Telugu folk video resolving to
     * an unrelated devotional track was reported from exactly this path.
     * [TrackMatcher.best] returning null is a normal answer, not a failure to
     * work around.
     *
     * Returns [song] unchanged when it isn't a video, or when nothing better
     * turns up — playing the video's own audio track beats guessing at a
     * substitute, and [song] is what a queue restore or offline retry falls
     * back to as well.
     *
     * [search] already drops video rows from its results (see
     * [InnertubeParser.parseSearch]), so every candidate here is audio-only
     * without a second check.
     */
    suspend fun resolveAudio(song: Song): Song {
        if (!song.isVideo) return song
        val target = TrackMatcher.targetOf(song)
        for (query in TrackMatcher.queries(target)) {
            val candidates = runCatching { rawSongSearch(query) }.getOrDefault(emptyList())
            TrackMatcher.best(candidates, target)?.let { return it }
        }
        return song
    }

    /**
     * Playback-facing edition resolver for the optional "Prioritize album versions" setting.
     *
     * Ordinary album cuts take the zero-cost path. A catalogue lookup is only performed when
     * the selected row looks like a single/alternate take (or does not state a release at all).
     * That keeps the preference from adding latency to every tap while still replacing a single
     * or remix with the canonical album recording when YouTube Music exposes one.
     */
    suspend fun resolvePreferredPlaybackVersion(song: Song): Song {
        val audio = resolveAudio(song)
        if (!AppSettings.prioritizeAlbumVersions.value || audio.localUri != null) return audio
        if (!audio.needsAlbumVersionLookup()) return audio

        val key = audio.albumPreferenceCacheKey()
        preferredAlbumVersionCache[key]?.let { return it.withPlaybackContextFrom(song) }

        val parsed = TrackMatcher.parseTitle(audio.title, audio.artist)
        val baseTitle = parsed.words.joinToString(" ").ifBlank { audio.title }
        val sourceTarget = TrackMatcher.targetOf(audio)
        val target = TrackMatcher.Target(
            title = baseTitle,
            artist = audio.artist,
            durationSec = sourceTarget.durationSec,
            isExplicit = sourceTarget.isExplicit,
            // Deliberately drop release identity here: the whole point of this
            // lookup is to move from the single/remix package to the album package.
            albumName = null,
            releaseYear = null,
            sourceVideoId = sourceTarget.sourceVideoId,
        )

        val candidates = LinkedHashMap<String, Song>()
        val artist = TrackMatcher.primaryArtist(audio.artist)
        val queries = buildList {
            if (artist.isNotBlank()) add("$baseTitle $artist")
            add(baseTitle)
        }.distinct().take(2)

        for (query in queries) {
            val found = runCatching { rawSongSearch(query) }.getOrDefault(emptyList())
            TrackMatcher.ranked(found, target).forEach { candidates.putIfAbsent(it.videoId, it) }
            if (candidates.values.any { it.albumPreferenceScore() >= ALBUM_VERSION_CONFIDENT_SCORE }) break
        }

        val preferred = candidates.values
            .maxWithOrNull(compareBy<Song> { it.albumPreferenceScore() }
                .thenBy { TrackMatcher.score(it, target) ?: Int.MIN_VALUE })
            ?.takeIf { it.albumPreferenceScore() > audio.albumPreferenceScore() }
            ?: audio

        preferredAlbumVersionCache[key] = preferred
        return preferred.withPlaybackContextFrom(song)
    }

    /** Replace duplicate single/remix rows with the best album cut already present in a list. */
    private fun preferAvailableAlbumVersions(songs: List<Song>): List<Song> {
        if (songs.size < 2) return songs
        val best = songs.groupBy { it.albumRecordingKey() }
            .mapValues { (_, group) -> group.maxByOrNull { it.albumPreferenceScore() } ?: group.first() }
        val emitted = HashSet<String>()
        return buildList(songs.size) {
            songs.forEach { song ->
                val key = song.albumRecordingKey()
                if (emitted.add(key)) add(best[key] ?: song)
            }
        }
    }

    private fun preferAlbumVersionsInSearch(rows: List<SearchResult>): List<SearchResult> {
        val tracks = rows.filterIsInstance<SearchResult.Track>().map { it.song }
        if (tracks.size < 2) return rows
        val best = tracks.groupBy { it.albumRecordingKey() }
            .mapValues { (_, group) -> group.maxByOrNull { it.albumPreferenceScore() } ?: group.first() }
        val emitted = HashSet<String>()
        return rows.mapNotNull { row ->
            when (row) {
                is SearchResult.Track -> {
                    val key = row.song.albumRecordingKey()
                    if (!emitted.add(key)) null else SearchResult.Track(best[key] ?: row.song)
                }
                else -> row
            }
        }
    }

    private fun Song.albumRecordingKey(): String {
        val titleKey = TrackMatcher.parseTitle(title, artist).core
        val artistKey = TrackMatcher.primaryArtist(artist)
        return "$titleKey|$artistKey|${if (isExplicit) 1 else 0}"
    }

    private fun Song.albumPreferenceCacheKey(): String =
        "${albumRecordingKey()}|${durationText.orEmpty()}"

    private fun Song.needsAlbumVersionLookup(): Boolean {
        val normalizedTitle = title.albumPreferenceText()
        val titleParts = TrackMatcher.parseTitle(title, artist)
        if (REMIX_RELEASE_WORD.containsMatchIn(normalizedTitle)) return true

        val album = albumName?.trim().orEmpty()
        if (album.isBlank()) {
            // Unknown release metadata is worth resolving for a plain catalogue track,
            // but never silently turn a deliberately selected live/acoustic/etc. take
            // into the studio album cut. The setting promises single/remix preference.
            return titleParts.versions.isEmpty()
        }
        val normalizedAlbum = album.albumPreferenceText()
        if (normalizedAlbum == normalizedTitle) return true
        if (SINGLE_RELEASE_WORD.containsMatchIn(normalizedAlbum)) return true
        if (REMIX_RELEASE_WORD.containsMatchIn(normalizedAlbum)) return true
        return false
    }

    private fun Song.albumPreferenceScore(): Int {
        var score = 0
        if (!albumId.isNullOrBlank()) score += 3
        val album = albumName?.trim().orEmpty()
        if (album.isBlank()) return score - 6

        val normalizedAlbum = album.albumPreferenceText()
        val normalizedTitle = title.albumPreferenceText()
        if (normalizedAlbum != normalizedTitle) score += 12 else score -= 8
        if (SINGLE_RELEASE_WORD.containsMatchIn(normalizedAlbum)) score -= 10
        if (REMIX_RELEASE_WORD.containsMatchIn(title.albumPreferenceText())) score -= 24
        if (REMIX_RELEASE_WORD.containsMatchIn(normalizedAlbum)) score -= 18
        return score
    }

    private fun String.albumPreferenceText(): String =
        Normalizer.normalize(this, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun Song.withPlaybackContextFrom(source: Song): Song = copy(
        setVideoId = source.setVideoId,
        fromAutoplay = source.fromAutoplay,
        queuePinned = source.queuePinned,
        sourcePlaylistId = source.sourcePlaylistId,
        sourcePlaylistTitle = source.sourcePlaylistTitle,
        sourcePlaylistArtworkUrl = source.sourcePlaylistArtworkUrl,
    )

    /** Signed-in profile for the settings header. Null when signed out. */
    suspend fun account(): Result<Account> = call("account") {
        InnertubeParser.parseAccount(Innertube.accountMenu())
            ?: error("No account details")
    }


    /** YouTube identities/channels available under the current Google account. */
    suspend fun accounts(): Result<List<YouTubeAccountIdentity>> = call("accounts") {
        InnertubeParser.parseAccountIdentities(Innertube.accountsList())
    }

    /**
     * The whole library in one shot — requires a signed-in session.
     *
     * YouTube Music has no single "my library" feed: Liked Music is the `LM`
     * auto-playlist, the songs added to the library are a separate feed, and
     * every saved collection has its own browse id. They're fetched in
     * parallel and a feed that fails or is simply empty (a fresh account has
     * no saved albums) is dropped rather than failing the whole page.
     */
    suspend fun library(): Result<LibraryPage> = call("library") {
        coroutineScope {
            val liked = async { runCatching { songsPaged(LIKED_MUSIC) }.getOrDefault(emptyList()) }
            val added = async { runCatching { songsPaged(LIBRARY_SONGS) }.getOrDefault(emptyList()) }
            val shelves = LIBRARY_FEEDS
                .map { (title, browseId) ->
                    async {
                        val items = runCatching {
                            InnertubeParser.parseLibraryItems(Innertube.browse(browseId))
                        }.getOrDefault(emptyList())
                        HomeShelf(title, items)
                    }
                }
                .awaitAll()
                .filter { it.items.isNotEmpty() }

            val likedSongs = liked.await()
            val likedIds = likedSongs.mapTo(HashSet()) { it.videoId }
            LibraryPage(
                likedSongs = likedSongs,
                // Thumbs-up'd tracks are also in the library feed; only what
                // the "Liked Music" list doesn't already cover is worth a
                // second section.
                librarySongs = added.await().filterNot { it.videoId in likedIds },
                shelves = shelves,
            )
        }
    }

    /**
     * What YouTube Music would play on after [videoId]. Feeds AutoPlay; the
     * seed track itself comes back first, so callers filter what they have.
     */
    suspend fun radio(videoId: String): Result<List<Song>> = call("radio:$videoId") {
        val songs = InnertubeParser.parseWatchQueue(Innertube.next(videoId))
        if (AppSettings.prioritizeAlbumVersions.value) preferAvailableAlbumVersions(songs) else songs
    }

    /**
     * Fast first-screen preview for a playlist.
     *
     * Uses the smaller watch-queue payload instead of the full browse page so
     * the first media rows can appear quickly on mobile data. The canonical
     * browse request still runs in parallel and replaces this preview with the
     * complete first page plus continuation token when it arrives.
     */
    suspend fun playlistPreview(browseId: String, limit: Int = 8): Result<List<Song>> =
        call("playlist:preview:$browseId") {
            InnertubeParser.parseWatchQueue(
                Innertube.playlistQueue(browseId.removePrefix("VL"))
            )
                .distinctBy { it.videoId }
                .take(limit.coerceAtLeast(1))
        }

    /**
     * The artist and album pages a track links out to.
     *
     * Search rows carry them, but home cards and anything already sitting in a
     * queue often don't — and the credits in the player have to lead somewhere
     * either way. A track's own watch queue entry always names both.
     */
    suspend fun trackLinks(videoId: String): Result<Song> = call("links:$videoId") {
        InnertubeParser.parseWatchQueue(Innertube.next(videoId))
            .firstOrNull { it.videoId == videoId }
            ?: error("no watch entry for $videoId")
    }

    /**
     * Resolves one visible artist name to its canonical YouTube Music ARTIST
     * browse endpoint. Used as a lazy fallback when a queue/watch renderer
     * exposes only the primary artist of a collaboration.
     */
    suspend fun resolveArtistLink(name: String): ArtistLink? {
        val clean = name.trim().replace(Regex("""\s+"""), " ")
        if (clean.isBlank()) return null
        val cacheKey = "single:${clean.artistLinkKey()}"
        resolvedArtistLinksCache[cacheKey]?.firstOrNull()?.let { return it.copy(name = clean) }

        val wanted = clean.artistLinkKey()
        val searched = search(clean, SearchFilter.ARTISTS)
            .getOrNull()
            .orEmpty()
            .filterIsInstance<SearchResult.Browse>()
            .map { it.item }
            .firstOrNull {
                it.type == BrowseType.ARTIST && it.title.artistLinkKey() == wanted
            }
            ?: return null

        ArtistCreditResolver.registerVerifiedArtist(searched.title)
        return ArtistLink(searched.browseId, clean).also {
            resolvedArtistLinksCache[cacheKey] = listOf(it)
        }
    }

    /**
     * Resolves every visible artist in a collaboration credit to its own artist
     * browse endpoint. Queue/watch rows are not consistent here: some expose
     * only the primary artist, and a few renderer variants attach a browseId to
     * a shortened text run. Navigation must therefore be rebuilt from the
     * complete visible credit rather than trusting one partial run.
     *
     * [ArtistCreditResolver] protects real group names containing punctuation
     * (for example Mumford & Sons), while exact ARTISTS searches give each
     * resolved name its canonical YouTube Music browseId. Seed links are kept
     * as a network-failure fallback, but never prevent missing collaborators
     * from being looked up.
     */
    suspend fun resolveArtistLinks(
        credit: String,
        seedLinks: List<ArtistLink> = emptyList(),
    ): List<ArtistLink> {
        val cleanCredit = credit.trim().replace(Regex("""\s+"""), " ")
        if (cleanCredit.isBlank()) return seedLinks.distinctArtistLinks()

        val cacheKey = cleanCredit.artistLinkKey()
        resolvedArtistLinksCache[cacheKey]?.let { cached ->
            return (cached + seedLinks).distinctArtistLinksForCredit(cleanCredit)
        }

        val seeds = seedLinks.distinctArtistLinks()
        val creditKey = cleanCredit.artistLinkKey()

        // If a renderer already linked the complete visible credit, it is one
        // artist/group even if its name contains '&' or commas.
        seeds.firstOrNull { it.name.artistLinkKey() == creditKey }?.let { whole ->
            return listOf(whole.copy(name = cleanCredit)).also {
                resolvedArtistLinksCache[cacheKey] = it
            }
        }

        // Resolve the complete string before attempting punctuation splits.
        // This is the guard that keeps real groups such as "Mumford & Sons"
        // and "Angus & Julia Stone" atomic.
        resolveArtistLink(cleanCredit)?.let { whole ->
            return listOf(whole.copy(name = cleanCredit)).also {
                resolvedArtistLinksCache[cacheKey] = it
            }
        }

        val resolverNames = runCatching { ArtistCreditResolver.resolve(cleanCredit) }
            .getOrElse { emptyList() }
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotBlank() }
            .distinctBy { it.artistLinkKey() }

        val names = if (resolverNames.size > 1) {
            resolverNames
        } else {
            navigationArtistNames(cleanCredit, seeds)
        }

        if (names.size <= 1) {
            return seeds.distinctArtistLinksForCredit(cleanCredit)
        }

        val resolved = coroutineScope {
            names.map { name ->
                async {
                    val wanted = name.artistLinkKey()
                    seeds.firstOrNull { it.name.artistLinkKey() == wanted }
                        ?.copy(name = name)
                        ?: resolveArtistLink(name)
                }
            }.awaitAll()
        }

        // Only cache the collaboration when every visible piece resolved.
        // Partial/malformed renderer links (including one-character labels)
        // remain merely a fallback and cannot poison subsequent tap ranges.
        val complete = resolved.filterNotNull()
        if (complete.size == names.size) {
            val final = complete.distinctArtistLinksForCredit(cleanCredit)
            if (final.size >= 2) resolvedArtistLinksCache[cacheKey] = final
            return final
        }

        return (complete + seeds).distinctArtistLinksForCredit(cleanCredit)
    }

    private val navigationArtistSeparator = Regex(
        """\s*,\s*&\s*|\s*&\s*|\s*,\s*|\s*;\s*|\s+(?:feat\.?|ft\.?|featuring|with|vs\.?|x)\s+""",
        setOf(RegexOption.IGNORE_CASE),
    )

    private fun navigationArtistNames(
        credit: String,
        seeds: List<ArtistLink>,
    ): List<String> {
        val creditKey = credit.artistLinkKey()
        if (seeds.any { it.name.artistLinkKey() == creditKey }) {
            return listOf(credit)
        }

        val parts = navigationArtistSeparator
            .split(credit)
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotBlank() }
            .distinctBy { it.artistLinkKey() }
        if (parts.size < 2) return emptyList()

        // The complete credit has already failed an exact ARTIST lookup
        // before this helper is used, so these visible pieces are safe
        // navigation candidates. Each still has to resolve exactly before the
        // collaboration is cached.
        return parts
    }

    private fun List<ArtistLink>.distinctArtistLinks(): List<ArtistLink> =
        filter { it.artistId.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.artistId }

    private fun List<ArtistLink>.distinctArtistLinksForCredit(credit: String): List<ArtistLink> {
        val creditKey = credit.artistLinkKey()
        return distinctArtistLinks()
            .sortedBy { link ->
                val pos = creditKey.indexOf(link.name.artistLinkKey())
                if (pos >= 0) pos else Int.MAX_VALUE
            }
    }

    private fun String.artistLinkKey(): String =
        Normalizer.normalize(this, Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "")
            .replace('’', '\'')
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * One page of a browse feed's tracks, and the token for the page after
     * it — null once there is nothing more. [suggested] is only ever
     * non-empty for a playlist page — see [InnertubeParser.parsePlaylistShelf].
     */
    data class SongPage(
        val songs: List<Song>,
        val continuation: String?,
        val suggested: List<Song> = emptyList(),
        /** Collection artwork from the page header, never inferred from row 1. */
        val thumbnailUrl: String? = null,
        /**
         * Whether the release this page describes is in the library. Only the
         * first page can answer — a continuation carries rows and nothing else
         * — so it is null from [moreSongs] and must not overwrite what
         * [browseSongs] already established.
         */
        val library: LibraryState? = null,
        /** Album/single/EP kind + artist + release year from the page header. */
        val releaseSubtitle: String? = null,
        /** Artist links explicitly present in the page header. */
        val headerArtists: List<ArtistLink> = emptyList(),
        /** Alternate editions exposed by an album page, normally the Other versions shelf. */
        val sections: List<HomeShelf> = emptyList(),
    )

    /**
     * The first page of an album/playlist's tracks, and nothing more.
     *
     * Deliberately not the whole list. Following every continuation before
     * returning meant a long playlist spent up to ten round trips showing a
     * spinner, when every row needed to fill the first screenful was in the
     * first response. The rest arrives behind a page that is by then already
     * being read — see [moreSongs].
     */
    suspend fun browseSongs(browseId: String): Result<SongPage> = call("browse:$browseId") {
        pageOf(Innertube.browse(browseId))
    }

    /** One catalogue response, including its local header metadata (BitChord v1.5.2). */
    suspend fun browseSongsV151(browseId: String): Result<SongPage> = call("browse:v151:$browseId") {
        val networkStarted = System.nanoTime()
        val response = Innertube.browseV151(browseId)
        val networkMs = (System.nanoTime() - networkStarted) / 1_000_000L
        val parseStarted = System.nanoTime()
        val page = pageOf(response)
        val parseMs = (System.nanoTime() - parseStarted) / 1_000_000L
        Log.d("OrbDetail", "catalogue $browseId network_decode=${networkMs}ms parse=${parseMs}ms songs=${page.songs.size}")
        page
    }

    /**
     * First page for a collection the listener explicitly opened.
     * Uses Innertube's short foreground timeout/retry policy; background feed
     * warming keeps using [browseSongs] so it never burns duplicate requests.
     */
    suspend fun browseSongsForeground(browseId: String): Result<SongPage> =
        browseSongs(browseId)

    /** The page [SongPage.continuation] points at. */
    suspend fun moreSongs(token: String): Result<SongPage> = call("browse:more") {
        pageOf(Innertube.browseContinuation(token))
    }

    /** Exact Orb v1.5.1 continuation path for detail-page background fill. */
    suspend fun moreSongsV151(token: String): Result<SongPage> = call("browse:v151:more") {
        pageOfV151(Innertube.browseContinuationV151(token))
    }

    /** One continuation page explicitly requested because the reader neared the end. */
    suspend fun moreSongsForeground(token: String): Result<SongPage> =
        moreSongs(token)

    /** Exact Orb v1.5.1 page parser: no newer release/header enrichment is done here. */
    private fun pageOfV151(response: JsonObject): SongPage {
        val library = InnertubeParser.parseLibraryState(response)
        val thumbnailUrl = InnertubeParser.parsePageThumbnail(response)
        InnertubeParser.parsePlaylistShelf(response)?.let { shelf ->
            return SongPage(
                songs = shelf.songs,
                continuation = shelf.continuation,
                suggested = shelf.suggested,
                thumbnailUrl = thumbnailUrl,
                library = library,
                sections = InnertubeParser.parseOtherVersionsShelf(response)?.let(::listOf).orEmpty(),
            )
        }
        return SongPage(
            songs = InnertubeParser.collectSongsDeep(response).distinctBy { it.videoId },
            continuation = InnertubeParser.continuationToken(response),
            thumbnailUrl = thumbnailUrl,
            library = library,
            sections = InnertubeParser.parseOtherVersionsShelf(response)?.let(::listOf).orEmpty(),
        )
    }

    private fun pageOf(response: JsonObject): SongPage {
        val library = InnertubeParser.parseLibraryState(response)
        val thumbnailUrl = InnertubeParser.parsePageThumbnail(response)
        val releaseSubtitle = InnertubeParser.parseReleaseSubtitle(response)
        val releaseYear = releaseSubtitle
            ?.let { Regex("(?:19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
        fun withReleaseYear(songs: List<Song>): List<Song> = if (releaseYear == null) {
            songs
        } else {
            songs.map { song ->
                if (song.releaseYear == null) song.copy(releaseYear = releaseYear) else song
            }
        }
        // A playlist page is scoped to its own shelf so its "Suggested
        // tracks" never read as songs the user added — see
        // parsePlaylistShelf. Anything else (album, library, history) has no
        // such shelf, and falls back to the layout-agnostic walk.
        InnertubeParser.parsePlaylistShelf(response)?.let { shelf ->
            return SongPage(
                songs = withReleaseYear(shelf.songs),
                continuation = shelf.continuation,
                suggested = shelf.suggested,
                thumbnailUrl = thumbnailUrl,
                library = library,
                releaseSubtitle = releaseSubtitle,
                headerArtists = InnertubeParser.parseHeaderArtists(response),
                sections = InnertubeParser.parseOtherVersionsShelf(response)?.let(::listOf).orEmpty(),
            )
        }
        return SongPage(
            // One response can name the same track twice — an album page that
            // also carries a "you might also like" shelf, say. Collecting into a
            // map used to take care of that; paging by hand means saying so.
            songs = withReleaseYear(InnertubeParser.collectSongsDeep(response).distinctBy { it.videoId }),
            continuation = InnertubeParser.continuationToken(response),
            thumbnailUrl = thumbnailUrl,
            library = library,
            releaseSubtitle = releaseSubtitle,
            headerArtists = InnertubeParser.parseHeaderArtists(response),
            sections = InnertubeParser.parseOtherVersionsShelf(response)?.let(::listOf).orEmpty(),
        )
    }

    /**
     * Every track behind a browse id, following continuations.
     *
     * A playlist page returns its first ~100 rows and a token for the rest, so
     * a long list otherwise arrives silently truncated. Capped at
     * [MAX_PAGES] so a runaway feed can't hold the UI open forever, and a
     * failed page keeps whatever was already collected.
     *
     * Holds its caller until the last page lands, so it belongs behind things
     * nobody is watching — the library sync, an artist's back catalogue. For
     * anything a screen is waiting on, use [browseSongs] and [moreSongs].
     */
    private suspend fun songsPaged(browseId: String): List<Song> {
        val out = LinkedHashMap<String, Song>()
        var response = Innertube.browse(browseId)
        var page = 1
        while (true) {
            // Same shelf-scoping as pageOf: a playlist (Liked Music and the
            // Library Songs auto-playlist included) is read from its own
            // shelf so a trailing "Suggested tracks" shelf never joins in.
            val shelf = InnertubeParser.parsePlaylistShelf(response)
            (shelf?.songs ?: InnertubeParser.collectSongsDeep(response)).forEach { out[it.videoId] = it }
            val token = shelf?.continuation ?: InnertubeParser.continuationToken(response)
            if (token == null || page++ >= MAX_PAGES) break
            response = runCatching { Innertube.browseContinuation(token) }.getOrNull() ?: break
        }
        return out.values.toList()
    }

    const val MAX_PAGES = 10

    /**
     * Liked Music: the `LM` auto-playlist, addressed as a playlist browse id.
     * Public because it is also the page a track has to disappear from the
     * moment it stops being liked — see MainViewModel's `dropFromLikedLists`.
     */
    const val LIKED_MUSIC = "VLLM"

    /** Songs explicitly added to the library — distinct from Liked Music. */
    private const val LIBRARY_SONGS = "FEmusic_liked_videos"

    /** Saved and own playlists; also what the "add to playlist" picker lists. */
    private const val LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"

    private val LIBRARY_FEEDS = listOf(
        "Playlists" to LIBRARY_PLAYLISTS,
        "Albums" to "FEmusic_liked_albums",
        "Artists" to "FEmusic_library_corpus_track_artists",
        "Subscriptions" to "FEmusic_library_corpus_artists",
    )

    // ---- Writes -------------------------------------------------------------

    /**
     * The account's own state for one track — rating and library membership.
     *
     * Deliberately a lookup rather than something cached with the [Song]: a
     * track reaching the player through the queue has been round-tripped
     * through a MediaItem, which carries an id and little else, and the
     * feedback tokens are per-row anyway. Fetched when a menu is opened, which
     * is the only moment the answer is looked at.
     */
    suspend fun songMenu(videoId: String): Result<SongMenu> = call("menu:$videoId") {
        InnertubeParser.parseSongMenu(Innertube.next(videoId), videoId)
            ?: error("no menu for $videoId")
    }

    /**
     * Public song credits exposed by YouTube Music when the label/distributor
     * delivered contributor metadata for this recording. The watch menu yields
     * a stable TRACK_CREDITS browse id; the dedicated browse page then carries
     * performed/written/produced/metadata-provider sections.
     */
    suspend fun songCredits(videoId: String): Result<SongCredits> = call("credits:$videoId") {
        val next = Innertube.next(videoId)
        val browseId = InnertubeParser.parseSongCreditsBrowseId(next, videoId)
            ?: error("credits unavailable for $videoId")
        InnertubeParser.parseSongCredits(Innertube.browse(browseId))
            ?: error("empty credits for $videoId")
    }

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> =
        call("rate:$videoId") { Innertube.rate(videoId, status) }

    /** Adds or removes a track from the library; [token] says which. */
    suspend fun setLibraryStatus(token: String): Result<Unit> =
        call("library:feedback") { Innertube.sendFeedback(token) }

    /**
     * Saves an album or playlist to the library, or removes it. [playlistId] is
     * the one the page named — see [LibraryState].
     */
    suspend fun setSaved(playlistId: String, saved: Boolean): Result<Unit> =
        call("library:$playlistId") { Innertube.ratePlaylist(playlistId, saved) }

    /**
     * The playlists a track can be added to. Not paged: an account with more
     * than one page of playlists is rare, and the picker is a list to scroll
     * rather than a feed to follow.
     */
    suspend fun userPlaylists(): Result<List<UserPlaylist>> = call("playlists") {
        InnertubeParser.parseUserPlaylists(Innertube.browse(LIBRARY_PLAYLISTS))
    }

    /** Creates a playlist, optionally seeded with [videoIds]; returns its id. */
    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        videoIds: List<String> = emptyList(),
    ): Result<String> = call("playlist:create") {
        Innertube.createPlaylist(title, privacy, videoIds = videoIds)
    }

    /** Checks the whole playlist, following continuations only until the track is found. */
    suspend fun playlistContains(browseId: String, videoId: String): Result<Boolean> =
        call("playlist:contains:$browseId:$videoId") {
            var response = Innertube.browse(browseId)
            var page = 1
            var found = false
            while (true) {
                val shelf = InnertubeParser.parsePlaylistShelf(response)
                val songs = shelf?.songs ?: InnertubeParser.collectSongsDeep(response)
                if (songs.any { it.videoId == videoId }) {
                    found = true
                    break
                }
                val token = shelf?.continuation ?: InnertubeParser.continuationToken(response)
                if (token == null || page++ >= MAX_PAGES) break
                response = runCatching { Innertube.browseContinuation(token) }.getOrNull() ?: break
            }
            found
        }

    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): Result<Unit> =
        call("playlist:add") { Innertube.addToPlaylist(playlistId, videoIds) }

    /** [entries] are (setVideoId, videoId) pairs — see [Song.setVideoId]. */
    suspend fun removeFromPlaylist(
        playlistId: String,
        entries: List<Pair<String, String>>,
    ): Result<Unit> = call("playlist:remove") {
        Innertube.removeFromPlaylist(playlistId, entries)
    }

    suspend fun renamePlaylist(playlistId: String, title: String): Result<Unit> =
        call("playlist:rename") { Innertube.renamePlaylist(playlistId, title) }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        call("playlist:delete") { Innertube.deletePlaylist(playlistId) }

    /** Full paged song walk using only the exact v1.5.1 detail browse path. */
    private suspend fun songsPagedV151(browseId: String): List<Song> {
        val out = LinkedHashMap<String, Song>()
        var response = Innertube.browseV151(browseId)
        var page = 1
        while (true) {
            val shelf = InnertubeParser.parsePlaylistShelf(response)
            (shelf?.songs ?: InnertubeParser.collectSongsDeep(response)).forEach {
                out[it.videoId] = it
            }
            val token = shelf?.continuation ?: InnertubeParser.continuationToken(response)
            if (token == null || page++ >= MAX_PAGES) break
            response = runCatching { Innertube.browseContinuationV151(token) }.getOrNull() ?: break
        }
        return out.values.toList()
    }

    /** Alternate standard/deluxe/expanded editions linked from one album page. */
    suspend fun albumOtherVersionsV151(browseId: String): Result<List<ShelfItem>> =
        call("album:versions:v151:$browseId") {
            InnertubeParser.parseOtherVersionsShelf(Innertube.browseV151(browseId))
                ?.items
                .orEmpty()
        }

    /**
     * Fully pages the destination behind an artist's release shelf header.
     *
     * The artist landing response intentionally contains only a carousel-sized
     * preview. Its header links to a dedicated Albums / Singles browse page;
     * follow that page and every continuation in the background so horizontal
     * scrolling is not silently capped at the preview count.
     */
    suspend fun artistReleaseShelfItemsV151(
        browseId: String,
        params: String? = null,
    ): Result<List<ShelfItem>> =
        call("artist:release-shelf:v151:$browseId:${params.orEmpty()}") {
            val out = LinkedHashMap<String, ShelfItem>()
            var response = Innertube.browseV151(browseId, params)
            var page = 0
            while (page++ < MAX_PAGES) {
                InnertubeParser.parseLibraryItems(response)
                    .filter { it.type == BrowseType.ALBUM && !it.browseId.isNullOrBlank() }
                    .forEach { item -> out.putIfAbsent(item.browseId!!, item) }

                val token = InnertubeParser.continuationToken(response) ?: break
                response = runCatching { Innertube.browseContinuationV151(token) }.getOrNull() ?: break
            }
            out.values.toList()
        }

    /** Visible artist metadata requires only the landing browse. */
    suspend fun artistLandingPageV151(browseId: String): Result<ArtistPage> = call("artist:landing:v151:$browseId") {
        InnertubeParser.parseArtistPage(Innertube.browseV151(browseId))
    }

    /**
     * Exact Orb v1.5.1 artist rule: the landing browse is parsed, then the linked
     * Top songs playlist is fully paged before ArtistPage is returned to the screen.
     */
    suspend fun artistPageV151(browseId: String): Result<ArtistPage> = call("artist:v151:$browseId") {
        val page = InnertubeParser.parseArtistPage(Innertube.browseV151(browseId))
        val fullSongs = page.moreSongsBrowseId?.let { playlistId ->
            runCatching { songsPagedV151(playlistId) }.getOrNull()
        }
        val songs = if (!fullSongs.isNullOrEmpty()) fullSongs else page.songs
        if (AppSettings.prioritizeAlbumVersions.value) {
            page.copy(songs = preferAvailableAlbumVersions(songs))
        } else if (!fullSongs.isNullOrEmpty()) {
            page.copy(songs = fullSongs)
        } else {
            page
        }
    }

    /**
     * Fast artist landing response: header/photo, shelves and the handful of
     * songs YouTube includes on the first page. This is the call a foreground
     * artist screen should wait for; the full Top songs catalogue is a second,
     * progressive phase via [artistTopSongs].
     */
    suspend fun artistLandingPage(browseId: String): Result<ArtistPage> = call("artist:landing:$browseId") {
        InnertubeParser.parseArtistPage(Innertube.browse(browseId))
    }

    /** Foreground artist landing with the same stalled-mobile-socket escape hatch as collection pages. */
    suspend fun artistLandingPageForeground(browseId: String): Result<ArtistPage> =
        artistLandingPage(browseId)

    /** Result of the latency-sensitive Top songs phase for an artist. */
    data class ArtistTopSongsPreview(
        /** Everything already received while establishing the first visible eight rows. */
        val songs: List<Song>,
        /** Next page after [songs], so the background phase never re-fetches page one. */
        val continuation: String?,
    )

    /**
     * Small Top songs preview. Artist landing responses usually expose five
     * rows, so follow only the continuation pages needed to reach [limit]
     * without waiting for the complete paginated catalogue. The continuation
     * is returned with the rows so phase two can continue from exactly where
     * this request stopped instead of issuing the first browse all over again.
     */
    suspend fun artistTopSongsPreview(
        browseId: String,
        limit: Int = 8,
        foreground: Boolean = false,
    ): Result<ArtistTopSongsPreview> = call("artist:top:preview:$browseId") {
        val target = limit.coerceAtLeast(1)
        val out = LinkedHashMap<String, Song>()
        // The v1.5.1 path uses the ordinary shared catalogue client. Keep
        // [foreground] only for call-site compatibility; it no longer selects
        // a separate connection pool or retry lane.
        @Suppress("UNUSED_VARIABLE")
        val legacyForegroundFlag = foreground
        var page = pageOf(Innertube.browse(browseId))
        var pagesRead = 1

        while (true) {
            page.songs.forEach { song -> out.putIfAbsent(song.videoId, song) }
            if (out.size >= target) break
            val token = page.continuation ?: break
            if (pagesRead++ >= 3) break
            // Follow only enough continuation pages to establish the eight
            // visible preview rows. Never walk the full catalogue just to
            // render the artist landing page.
            page = pageOf(Innertube.browseContinuation(token))
        }
        ArtistTopSongsPreview(
            songs = out.values.toList(),
            continuation = page.continuation,
        )
    }

    /** Full paged Top songs list linked from [ArtistPage.moreSongsBrowseId]. */
    suspend fun artistTopSongs(browseId: String): Result<List<Song>> = call("artist:top:$browseId") {
        songsPaged(browseId)
    }

    /**
     * Fully expanded artist page for background/refresh callers that explicitly
     * prefer completeness over first-content latency.
     */
    suspend fun artistPage(browseId: String): Result<ArtistPage> = call("artist:$browseId") {
        val page = InnertubeParser.parseArtistPage(Innertube.browse(browseId))
        val fullSongs = page.moreSongsBrowseId?.let { playlistId ->
            runCatching { songsPaged(playlistId) }.getOrNull()
        }
        if (!fullSongs.isNullOrEmpty()) page.copy(songs = fullSongs) else page
    }

    private suspend fun <T> call(label: String, block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            fun elapsedMs(): Long = (System.nanoTime() - startedAt) / 1_000_000L
            runCatching { block() }
                // runCatching catches Throwable, cancellation included, which
                // would turn "the user typed another letter" into a failed
                // Result and put the abandoned request's error on screen.
                // Cancellation isn't this call's to answer for.
                .onFailure { if (it is CancellationException) throw it }
                .onSuccess { Log.d(TAG, "$label ok in ${elapsedMs()}ms") }
                .onFailure { Log.w(TAG, "$label failed in ${elapsedMs()}ms: ${it.message}") }
        }
}
