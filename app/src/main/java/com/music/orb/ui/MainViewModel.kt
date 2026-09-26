package com.music.orb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.orb.R
import com.music.orb.auth.AuthStore
import com.music.orb.auth.OrbGoogleAuth
import com.music.orb.auth.YouTubeBrowserSession
import com.music.orb.data.canvas.ArtistArtworkRepository
import com.music.orb.data.AppUpdateChecker
import com.music.orb.data.ArtistCreditResolver
import com.music.orb.data.LocalMediaRepository
import com.music.orb.data.library.OrbLibraryStore
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.lyrics.LyricLine
import com.music.orb.data.lyrics.LyricsRepository
import com.music.orb.data.lyrics.LyricsSource
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.ArtistPreference
import com.music.orb.data.settings.ArtistPreferenceStore
import com.music.orb.data.settings.LikeStatusStore
import com.music.orb.data.innertube.Innertube
import com.music.orb.data.innertube.PlaybackTracker
import com.music.orb.data.innertube.StreamResolver
import com.music.orb.data.model.Account
import com.music.orb.data.model.ArtistPage
import com.music.orb.data.model.ArtistLink
import com.music.orb.data.model.BrowseItem
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.DetailPage
import com.music.orb.data.model.HomeShelf
import com.music.orb.data.model.LibraryPage
import com.music.orb.data.model.LibraryState
import com.music.orb.data.model.LikeStatus
import com.music.orb.data.model.PlaylistPrivacy
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.model.ShelfItem
import com.music.orb.data.model.Song
import com.music.orb.data.model.SongMenu
import com.music.orb.data.model.UiState
import com.music.orb.data.model.UserPlaylist
import com.music.orb.data.settings.SearchHistory
import com.music.orb.data.settings.SearchHistoryEntry
import com.music.orb.data.social.SocialRepository
import com.music.orb.data.stats.TrackLanguageResolver
import com.music.orb.data.social.OrbSongRatingMutation
import com.music.orb.ui.notifications.OrbNotice
import com.music.orb.ui.notifications.OrbNoticeCenter
import com.music.orb.ui.notifications.OrbNoticeIcon
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.music.orb.data.sources.SourceKind
import com.music.orb.data.sources.SourceRegistry
import com.music.orb.data.sources.SourceResolver
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val authStore = AuthStore(app)

    private val _signedIn = MutableStateFlow(authStore.isSignedIn)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val home: StateFlow<UiState<List<HomeShelf>>> = _home.asStateFlow()

    // YouTube-owned shelves used only by the curated For You page. They are
    // independent from AppSettings.useYouTubeMusicLibrary: Home should still
    // show the linked YouTube account's saved songs and Recaps when Orb's own
    // library is selected as the app-wide save backend.
    private val _homeYouTubeSections = MutableStateFlow<List<HomeShelf>>(emptyList())
    val homeYouTubeSections: StateFlow<List<HomeShelf>> = _homeYouTubeSections.asStateFlow()
    private var homeYouTubeSectionsJob: Job? = null
    private var homeYouTubeSectionsLoaded = false

    /**
     * Token for the next page of Home shelves; null once there's nothing
     * more. Declared here rather than by [loadMoreHome] because [init] calls
     * [loadHome] synchronously up to its first suspension point — a property
     * declared after [init] would still be null when that runs.
     */
    private var homeContinuation: String? = null

    /** Titles already on screen, so a later page can't repeat a shelf. */
    private val homeSeenTitles = mutableSetOf<String>()

    private val _homeLoadingMore = MutableStateFlow(false)
    val homeLoadingMore: StateFlow<Boolean> = _homeLoadingMore.asStateFlow()

    /**
     * The priority Home pass (personalised shelves plus supplemental releases)
     * has finished. Explore and Library may fetch raw catalogue data earlier,
     * but their costly badge preflights wait for this or a safety timeout.
     */
    private val homePriorityPassReady = MutableStateFlow(false)

    /**
     * Number of remote detail pages currently on their latency-sensitive path.
     * Background Home/Search/artist-shelf enrichment waits while this is non-zero,
     * so a page the listener actually opened gets first claim on Innertube and
     * source work instead of sitting behind speculative prefetches.
     */
    private val detailBadgePriorityCount = MutableStateFlow(0)

    /**
     * Invalidates an older progressive Home pipeline without making network
     * cancellation part of correctness. A late page may finish its HTTP call,
     * but it is never allowed to publish over the newer request.
     */
    private val homeGeneration = AtomicLong(0L)

    /** Background badge/cache enrichment for the current Home generation. */
    private var homeBadgeWarmJob: Job? = null

    /** Prevents the four-minute listening-history timer from overlapping requests. */
    private var recentlyPlayedRefreshJob: Job? = null

    private val _explore = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val explore: StateFlow<UiState<List<HomeShelf>>> = _explore.asStateFlow()

    private val _releasesHub = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val releasesHub: StateFlow<UiState<List<HomeShelf>>> = _releasesHub.asStateFlow()

    private val _trendingHub = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val trendingHub: StateFlow<UiState<List<HomeShelf>>> = _trendingHub.asStateFlow()

    private val _discoverHub = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val discoverHub: StateFlow<UiState<List<HomeShelf>>> = _discoverHub.asStateFlow()

    private val _communityPlaylistsHub = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val communityPlaylistsHub: StateFlow<UiState<List<HomeShelf>>> = _communityPlaylistsHub.asStateFlow()

    // A completed page is session cache. Re-entering a tab/filter must never
    // make it fetch again by itself; force=true and pull-to-refresh are the
    // explicit invalidation paths.
    private var releasesHubRequested = false
    private var trendingHubRequested = false
    private var discoverHubRequested = false
    private var communityPlaylistsHubRequested = false

    private var releasesHubStale = true
    private var trendingHubStale = true
    private var discoverHubStale = true
    private var communityPlaylistsHubStale = true

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<UiState<List<SearchResult>>?>(null)
    val results: StateFlow<UiState<List<SearchResult>>?> = _results.asStateFlow()

    private val _searchRefreshing = MutableStateFlow(false)
    val searchRefreshing: StateFlow<Boolean> = _searchRefreshing.asStateFlow()

    /** Songs is the default tab; there is no "All" tab any more. */
    private val _filter = MutableStateFlow(SearchFilter.SONGS)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    /**
     * What the search page offers while a query is being typed, led by the
     * query itself.
     *
     * Non-empty *is* the signal that the field is mid-edit, so the screen
     * needs no second flag: these rows are shown in place of the results
     * whenever there are any, and cleared the moment a search is actually run
     * — see [submitSearch], [searchFor].
     *
     * Element 0 is always the raw text as typed. It's put there by the
     * keystroke itself rather than taken from the response, so the row the
     * thumb is already heading for is correct before the network answers, and
     * stays correct if it never does — YouTube's list never contains the
     * half-typed text, only completions of it.
     */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    /** The query whose typeahead response is still allowed to publish. */
    private var suggestionWantedFor: String? = null

    // The search pipeline's own state. Declared here, above [init], because
    // that is where the collector is started from and a property declared
    // below it would still be null when it runs. See [startSearchPipeline].

    /**
     * Buffered so an emission is never lost to a collector that happens to be
     * mid-search, and [BufferOverflow.DROP_OLDEST] because when two arrive
     * together the later one is the one meant.
     */
    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The same arrangement as [searchRequests], for the typeahead — where the
     * drop policy earns its keep rather than just being safe: this one really
     * does take a keystroke each, and a fast typist's backlog should collapse
     * to the prefix they ended on instead of being worked through a letter at
     * a time.
     */
    private val suggestRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val newestRequestId = AtomicLong(0L)

    /**
     * Results of recent searches, so a query searched before is answered
     * without asking again. That covers the two ways a query is repeated most:
     * a filter tab, which re-runs the same text against a different tab and
     * then usually goes back, and a term tapped out of the recent searches.
     *
     * Its other half is [prefixMatch], which is what the typeahead makes worth
     * keeping: picking "coldplay yellow" off a list is normally preceded by
     * having searched "coldplay", and those results are close enough to leave
     * up for the moment the narrower one takes rather than blanking the page
     * to a spinner.
     */
    private val searchCache = object : LruCache<String, List<SearchResult>>(SEARCH_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: List<SearchResult>): Int =
            1 + value.size / 50
    }

    /**
     * First-page detail data prepared while a collection is still only a card.
     *
     * Album/playlist headers need the first browse response for their real action
     * row, library state and track count. Keeping that response warm means opening
     * a card does not have to build the header in several visible stages.
     */
    private data class DetailWarmEntry(
        val page: YtMusicRepository.SongPage?,
        val pageError: Throwable?,
        val canonicalSubtitle: String?,
    )

    private val detailWarmCache = object : LruCache<String, DetailWarmEntry>(DETAIL_WARM_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: DetailWarmEntry): Int =
            1 + (value.page?.songs?.size ?: 0) / 50
    }
    private val detailWarmups = mutableMapOf<String, Deferred<DetailWarmEntry>>()

    /** Artist landing responses are small and ideal for card-time prewarming. */
    private val artistWarmCache = object : LruCache<String, ArtistPage>(ARTIST_WARM_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: ArtistPage): Int =
            1 + value.songs.size / 20 + value.sections.sumOf { it.items.size } / 40
    }
    private val artistWarmups = mutableMapOf<String, Deferred<ArtistPage?>>()
    private val backgroundArtistWarmupGate = Semaphore(ARTIST_WARMUP_CONCURRENCY)

    /**
     * Speculative card prefetch is intentionally much narrower than foreground
     * navigation. A feed may contain dozens of collections; letting all of them
     * browse at once can queue the album the user actually tapped behind work
     * nobody is looking at. Foreground detail requests do not use this gate.
     */
    private val backgroundDetailWarmupGate = Semaphore(DETAIL_WARMUP_CONCURRENCY)

    /**
     * Fully loaded pages retained as a small access-order working set. DetailPage can carry a
     * complete track list, header metadata and recommendations, so keeping every page opened in a
     * long session made RAM usage grow without bound. The weighted budget charges large track lists more than small ones;
     * older pages are fetched again if revisited.
     */
    private val detailPageCache = object : LruCache<String, DetailPage>(DETAIL_PAGE_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: DetailPage): Int =
            1 + ((value.songs as? UiState.Success)?.data?.size ?: 0) / 100
    }
    private val detailOpeningJobs = mutableMapOf<String, Job>()
    private val detailContinuationJobs = mutableMapOf<String, Job>()
    private val detailArtistVersionsJobs = mutableMapOf<String, Job>()

    private fun cancelDetailContinuations() {
        detailContinuationJobs.values.forEach { it.cancel() }
        detailContinuationJobs.clear()
    }
    private val _refreshingDetails = MutableStateFlow(emptySet<String>())
    val refreshingDetails: StateFlow<Set<String>> = _refreshingDetails.asStateFlow()

    /** Synced lyrics for whatever is playing; null while unknown or absent. */
    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()

    /** Which of the four databases [lyrics] came from, for the panel's credit. */
    private val _lyricsSource = MutableStateFlow<LyricsSource?>(null)
    val lyricsSource: StateFlow<LyricsSource?> = _lyricsSource.asStateFlow()

    /**
     * Whether the lookup for the current track has finished. [lyrics] alone
     * can't tell "still looking" apart from "looked, found nothing" — both
     * are null — and the player needs that distinction to show "Lyrics not
     * available" only once it actually means that.
     */
    private val _lyricsChecked = MutableStateFlow(false)
    val lyricsChecked: StateFlow<Boolean> = _lyricsChecked.asStateFlow()

    private var lyricsJob: Job? = null

    /**
     * What the loaded lyrics are for. Both the track *and* the settings that
     * chose them, so switching a source on or off re-runs the lookup rather
     * than leaving the last answer sitting on a player that would now find a
     * different one.
     */
    private var lyricsFor: Triple<String, Set<LyricsSource>, Boolean>? = null

    /** Called as the playing track changes; cheap no-op when already loaded. */
    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ) {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
        }
        val prioritizeSyllableSync = AppSettings.prioritizeSyllableSync.value
        val key = Triple(videoId, sources, prioritizeSyllableSync)
        if (lyricsFor == key) return
        lyricsFor = key
        _lyrics.value = null
        _lyricsSource.value = null
        lyricsJob?.cancel()
        if (sources.isEmpty()) {
            // Switched off, or every source unticked. Nothing to look up, and
            // nothing to say about it — the player drops the lyric strip
            // rather than reporting a track with no lyrics.
            _lyricsChecked.value = true
            return
        }
        _lyricsChecked.value = false
        if (durationMs <= 0L) {
            // Duration arrives a beat after the track does; wait for it.
            lyricsFor = null
            return
        }
        lyricsJob = viewModelScope.launch {
            val found =
                LyricsRepository.lyrics(
                    videoId, title, artist, durationMs, album, sources, prioritizeSyllableSync,
                )
            _lyrics.value = found?.lines
            _lyricsSource.value = found?.source
            found?.lines?.let { lines ->
                TrackLanguageResolver.rememberFromLyrics(videoId, lines)
            }
            _lyricsChecked.value = true
        }
    }

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _library = MutableStateFlow<UiState<LibraryPage>>(UiState.Loading)
    val library: StateFlow<UiState<LibraryPage>> = _library.asStateFlow()

    /**
     * Album / artist / playlist pages, as a stack — opening an artist from an
     * album page and pressing back returns to the album, not to search.
     */
    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()


    /** Set once per launch if GitHub has a release newer than this build. */
    val updateAvailable: StateFlow<AppUpdateChecker.UpdateInfo?> = AppUpdateChecker.available

    // ---- Ratings, library and playlists -------------------------------------

    /**
     * Ratings this session has set, which win over whatever the library feed
     * last said.
     *
     * Kept apart from the library rather than folded into it because the two
     * answer different questions: Liked Music is what YouTube knew when the
     * page was fetched, and this is what the user has done since. Layering
     * them ([likeStatuses]) means a tap shows immediately without the library
     * having to be re-fetched, and a later refresh can't undo it.
     */
    private val _likeOverrides = MutableStateFlow<Map<String, LikeStatus>>(emptyMap())

    /** Persistent positive/blocked artist choices from the artist info panel. */
    private val _artistPreferences = MutableStateFlow<Map<String, ArtistPreference>>(emptyMap())
    val artistPreferences: StateFlow<Map<String, ArtistPreference>> = _artistPreferences.asStateFlow()

    /** One serialized YouTube outbox worker per video id. */
    private val youtubeRatingSyncJobs = mutableMapOf<String, Job>()
    private var cloudRatingReconcileJob: Job? = null

    /** Every rating known for this account: the library's, then Orb's durable overlay. */
    val likeStatuses: StateFlow<Map<String, LikeStatus>> =
        combine(_library, _likeOverrides) { library, overrides ->
            val liked = (library as? UiState.Success)?.data?.likedSongs
                ?.associate { it.videoId to LikeStatus.LIKE }
                .orEmpty()
            liked + overrides
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun likeStatusOf(videoId: String): LikeStatus =
        _likeOverrides.value[videoId]
            ?: likeStatuses.value[videoId]
            ?: LikeStatus.INDIFFERENT

    /**
     * A recording alias lets the same catalogue recording keep its heart when
     * YouTube returns another video id (single/album result, re-indexed row,
     * etc.). Duration is bucketed only to avoid colliding same-title tracks by
     * the same artist while tolerating tiny source-duration differences.
     */
    fun likeStatusOf(
        song: Song,
        statuses: Map<String, LikeStatus> = likeStatuses.value,
    ): LikeStatus {
        val recordingKey = recordingLikeKey(song)
        return recordingKey?.let(statuses::get)
            ?: statuses[song.videoId]
            ?: LikeStatus.INDIFFERENT
    }

    fun artistPreferenceOf(artistName: String): ArtistPreference =
        _artistPreferences.value[ArtistPreferenceStore.artistKey(artistName)] ?: ArtistPreference.NEUTRAL

    fun toggleArtistLike(artistName: String) {
        val target = if (artistPreferenceOf(artistName) == ArtistPreference.LIKE) {
            ArtistPreference.NEUTRAL
        } else {
            ArtistPreference.LIKE
        }
        setArtistPreference(artistName, target)
    }

    fun toggleArtistBlock(artistName: String) {
        val target = if (artistPreferenceOf(artistName) == ArtistPreference.BLOCK) {
            ArtistPreference.NEUTRAL
        } else {
            ArtistPreference.BLOCK
        }
        setArtistPreference(artistName, target)
    }

    private fun setArtistPreference(artistName: String, preference: ArtistPreference) {
        val key = ArtistPreferenceStore.artistKey(artistName)
        if (key.isBlank()) return
        val updated = _artistPreferences.value.toMutableMap().apply {
            if (preference == ArtistPreference.NEUTRAL) remove(key) else put(key, preference)
        }
        _artistPreferences.value = updated
        ArtistPreferenceStore.set(artistPreferenceAccountKey(), artistName, preference)
        // The Albums tab's lead carousel is keyed to explicit artist favorites.
        // Re-entering the tab after changing this preference must rebuild it.
        releasesHubRequested = false
        releasesHubStale = true
        pruneExcludedRecommendations()
    }

    /**
     * One exclusion rule shared by AutoPlay, user queues and recommendation UI.
     * Search intentionally does not call this: disliked/blocked music remains
     * discoverable so the listener can inspect it or undo the preference.
     */
    fun shouldAvoidPlayback(song: Song): Boolean =
        isDislikedRecording(song) || isBlockedArtistCredit(song.artist)

    /**
     * Be stricter than the visual heart when guarding transport. Catalogue
     * surfaces can return the same recording under a different video id or
     * without a duration; a recorded dislike still wins for the same normalized
     * title + artist instead of letting that alternate upload re-enter AutoPlay.
     */
    private fun isDislikedRecording(song: Song): Boolean {
        if (likeStatusOf(song) == LikeStatus.DISLIKE) return true
        val title = normalizeRatingToken(song.title).takeIf { it.isNotBlank() } ?: return false
        val artist = normalizeRatingToken(song.artist).takeIf { it.isNotBlank() } ?: return false
        val prefix = "rec:v1:$title|$artist|"
        return likeStatuses.value.any { (key, status) ->
            key.startsWith(prefix) && status == LikeStatus.DISLIKE
        }
    }

    private fun isBlockedArtistCredit(credit: String): Boolean {
        val blocked = _artistPreferences.value
            .filterValues { it == ArtistPreference.BLOCK }
            .keys
        if (blocked.isEmpty() || credit.isBlank()) return false
        val resolved = ArtistCreditResolver.creditsForCounting(credit)
        return resolved.any { ArtistPreferenceStore.artistKey(it) in blocked } ||
            ArtistPreferenceStore.artistKey(credit) in blocked
    }

    /** Positive counterpart to the hard block: a mild local ranking signal. */
    fun shouldPreferPlayback(song: Song): Boolean = isLikedArtistCredit(song.artist)

    private fun isLikedArtistCredit(credit: String): Boolean {
        val liked = _artistPreferences.value
            .filterValues { it == ArtistPreference.LIKE }
            .keys
        if (liked.isEmpty() || credit.isBlank()) return false
        val resolved = ArtistCreditResolver.creditsForCounting(credit)
        return resolved.any { ArtistPreferenceStore.artistKey(it) in liked } ||
            ArtistPreferenceStore.artistKey(credit) in liked
    }

    private fun shouldAvoidRecommendation(item: ShelfItem): Boolean {
        if (item.type == BrowseType.ARTIST && isBlockedArtistCredit(item.title)) return true
        item.videoId?.takeIf { it.isNotBlank() }?.let { videoId ->
            val song = Song(
                videoId = videoId,
                title = item.title,
                artist = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
                durationText = item.durationText,
                artistId = item.artistId,
                albumId = item.albumId,
                albumName = item.albumName,
                isVideo = item.isVideo,
                isExplicit = item.isExplicit,
            )
            if (shouldAvoidPlayback(song)) return true
        }
        if (item.type == BrowseType.ALBUM) {
            val creditSegments = item.subtitle.split("•", "·")
                .map(String::trim)
                .filter(String::isNotBlank)
            if (creditSegments.any(::isBlockedArtistCredit)) return true
        }
        return false
    }

    private fun shouldPreferRecommendation(item: ShelfItem): Boolean = when {
        item.type == BrowseType.ARTIST -> isLikedArtistCredit(item.title)
        !item.videoId.isNullOrBlank() -> isLikedArtistCredit(item.subtitle)
        item.type == BrowseType.ALBUM -> item.subtitle
            .split("•", "·")
            .map(String::trim)
            .filter(String::isNotBlank)
            .any(::isLikedArtistCredit)
        else -> false
    }

    private fun filterPersonalizedShelves(shelves: List<HomeShelf>): List<HomeShelf> = shelves
        .map { shelf ->
            val visible = shelf.items.filterNot(::shouldAvoidRecommendation)
            // Keep YouTube's ordering intact except for a small explicit boost
            // to artists the listener marked as liked in Orb. Kotlin's sort is
            // stable, so equally ranked items stay in their original order.
            shelf.copy(items = visible.sortedByDescending(::shouldPreferRecommendation))
        }
        .filter { it.items.isNotEmpty() }

    private fun pruneExcludedRecommendations() {
        val home = (_home.value as? UiState.Success)?.data
        if (home != null) _home.value = UiState.Success(filterPersonalizedShelves(home))
        _detailStack.value = _detailStack.value.map { page ->
            if (page.suggestedSongs.isEmpty()) page
            else page.copy(suggestedSongs = page.suggestedSongs.filterNot(::shouldAvoidPlayback))
        }
    }

    private fun recordingLikeKey(song: Song): String? {
        val title = normalizeRatingToken(song.title).takeIf { it.isNotBlank() } ?: return null
        val artist = normalizeRatingToken(song.artist).takeIf { it.isNotBlank() } ?: return null
        val durationBucket = parseDurationSeconds(song.durationText)?.let { seconds ->
            ((seconds + 2) / 5) * 5
        }
        return buildString {
            append("rec:v1:")
            append(title)
            append('|')
            append(artist)
            append('|')
            append(durationBucket ?: "?")
        }
    }

    private fun normalizeRatingToken(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun parseDurationSeconds(value: String?): Int? {
        val parts = value?.trim()?.split(':') ?: return null
        if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
        val seconds = when (parts.size) {
            2 -> parts[0].toInt() * 60 + parts[1].toInt()
            3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
            else -> return null
        }
        return seconds.takeIf { it > 0 }
    }

    /**
     * Sets Orb state first, then persists to Supabase and immediately mirrors to
     * YouTube Music whenever that integration is active. Neither network target
     * is allowed to roll the heart back on a transient failure.
     */
    fun setLike(song: Song, status: LikeStatus) = setLikeInternal(song, song.videoId, status)

    /** Compatibility path for call sites that only have a video id. */
    fun setLike(videoId: String, status: LikeStatus) = setLikeInternal(null, videoId, status)

    private fun setLikeInternal(song: Song?, videoId: String, status: LikeStatus) {
        if (!requireSignIn() || videoId.isBlank()) return
        val previous = song?.let(::likeStatusOf) ?: likeStatusOf(videoId)
        if (previous == status) return

        val accountKey = likeAccountKey() ?: return
        val recordingKey = song?.let(::recordingLikeKey)
        val updatedAtMs = System.currentTimeMillis()
        val localUpdates = buildMap {
            put(videoId, status)
            if (recordingKey != null) put(recordingKey, status)
        }
        _likeOverrides.value = _likeOverrides.value + localUpdates
        LikeStatusStore.setMany(accountKey, localUpdates, updatedAtMs)

        releasesHubStale = true
        libraryStale = true
        if (status != LikeStatus.LIKE) dropFromLikedLists(videoId)
        if (status == LikeStatus.DISLIKE) pruneExcludedRecommendations()

        persistRatingToOrbCloud(
            song = song,
            videoId = videoId,
            recordingKey = recordingKey,
            status = status,
            updatedAtMs = updatedAtMs,
        )

        // The outbox is written before touching YouTube. If the integration is
        // connected, start the request immediately; if it is disconnected, the
        // same desired state is sent as soon as the integration becomes active.
        LikeStatusStore.queueYoutube(
            accountKey = accountKey,
            videoId = videoId,
            status = status,
            forgetLibraryOnSuccess = previous == LikeStatus.LIKE && status == LikeStatus.INDIFFERENT,
        )
        startYoutubeRatingSync(videoId, accountKey)
    }

    private fun persistRatingToOrbCloud(
        song: Song?,
        videoId: String,
        recordingKey: String?,
        status: LikeStatus,
        updatedAtMs: Long,
    ) {
        val mutation = OrbSongRatingMutation(
            videoId = videoId,
            recordingKey = recordingKey,
            status = status.name,
            title = song?.title,
            artist = song?.artist,
            album = song?.albumName,
            durationText = song?.durationText,
            updatedAt = Instant.ofEpochMilli(updatedAtMs).toString(),
        )
        viewModelScope.launch {
            var retryMs = 750L
            repeat(4) { attempt ->
                val saved = runCatching { SocialRepository.setMySongRating(mutation) }.isSuccess
                if (saved) return@launch
                if (attempt < 3) {
                    delay(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(6_000L)
                }
            }
            // The timestamped local copy remains authoritative. Startup/account
            // reconciliation will retry any local row newer than the cloud.
        }
    }

    /**
     * Per-video serialized YouTube worker. Multiple rapid taps cannot finish out
     * of order: while one request is in flight, a newer desired state replaces
     * the outbox value and is sent immediately after the current request ends.
     */
    private fun startYoutubeRatingSync(videoId: String, accountKey: String? = likeAccountKey()) {
        val syncAccountKey = accountKey ?: return
        if (!youtubeLibraryBackendAvailable()) return
        if (youtubeRatingSyncJobs[videoId]?.isActive == true) return

        val job = viewModelScope.launch {
            var retryMs = 1_000L
            while (youtubeLibraryBackendAvailable()) {
                val pending = LikeStatusStore.pendingYoutube(syncAccountKey)[videoId] ?: break
                val result = YtMusicRepository.rate(videoId, pending.status)
                if (result.isSuccess) {
                    LikeStatusStore.clearYoutubeIfMatches(syncAccountKey, videoId, pending)
                    if (
                        pending.status == LikeStatus.INDIFFERENT &&
                        pending.forgetLibraryOnSuccess
                    ) {
                        forgetFromLibrary(videoId)
                    }
                    retryMs = 1_000L
                } else {
                    delay(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(60_000L)
                }
            }
        }
        youtubeRatingSyncJobs[videoId] = job
        job.invokeOnCompletion {
            viewModelScope.launch {
                if (youtubeRatingSyncJobs[videoId] === job) {
                    youtubeRatingSyncJobs.remove(videoId)
                }
                if (
                    youtubeLibraryBackendAvailable() &&
                    LikeStatusStore.pendingYoutube(syncAccountKey).containsKey(videoId)
                ) {
                    startYoutubeRatingSync(videoId, syncAccountKey)
                }
            }
        }
    }

    private fun flushPendingYoutubeRatings() {
        if (!youtubeLibraryBackendAvailable()) return
        val accountKey = likeAccountKey() ?: return
        LikeStatusStore.pendingYoutube(accountKey).keys.forEach { videoId ->
            startYoutubeRatingSync(videoId, accountKey)
        }
    }

    /**
     * Pulls account ratings from Supabase and merges by timestamp. Old v1 local
     * hearts are bulk-seeded into an empty/newer cloud only when the cloud does
     * not already have a newer value for that exact video id.
     */
    private fun reconcileOrbRatingsFromCloud() {
        if (!_signedIn.value) return
        cloudRatingReconcileJob?.cancel()
        cloudRatingReconcileJob = viewModelScope.launch {
            val accountKey = likeAccountKey() ?: return@launch

            // Supabase session restore can trail the local Google session by a
            // fraction of a second after process start.
            var userReady = SocialRepository.currentUserId() != null
            var waitMs = 250L
            var attempts = 0
            while (!userReady && attempts < 6) {
                delay(waitMs)
                userReady = SocialRepository.currentUserId() != null
                waitMs = (waitMs * 2).coerceAtMost(2_000L)
                attempts++
            }
            if (!userReady) return@launch

            val localBefore = LikeStatusStore.loadRecords(accountKey)
            val remoteRows = runCatching { SocialRepository.mySongRatings() }.getOrNull()
                ?: return@launch

            fun parseUpdatedAt(value: String): Long =
                runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

            val remoteByKey = mutableMapOf<String, LikeStatusStore.StoredRating>()
            val remoteExactUpdatedAt = mutableMapOf<String, Long>()
            remoteRows.forEach { row ->
                val status = runCatching { LikeStatus.valueOf(row.status) }.getOrNull()
                    ?: return@forEach
                val timestamp = parseUpdatedAt(row.updatedAt)
                val stored = LikeStatusStore.StoredRating(status, timestamp)

                val previousExact = remoteByKey[row.videoId]
                if (previousExact == null || timestamp > previousExact.updatedAtMs) {
                    remoteByKey[row.videoId] = stored
                    remoteExactUpdatedAt[row.videoId] = timestamp
                }
                row.recordingKey?.takeIf { it.isNotBlank() }?.let { key ->
                    val previousAlias = remoteByKey[key]
                    if (previousAlias == null || timestamp > previousAlias.updatedAtMs) {
                        remoteByKey[key] = stored
                    }
                }
            }

            val localToSeed = localBefore
                .asSequence()
                .filter { (key, value) ->
                    !key.startsWith("rec:") &&
                        value.updatedAtMs > (remoteExactUpdatedAt[key] ?: Long.MIN_VALUE)
                }
                .map { (videoId, value) ->
                    OrbSongRatingMutation(
                        videoId = videoId,
                        status = value.status.name,
                        updatedAt = Instant.ofEpochMilli(value.updatedAtMs.coerceAtLeast(0L)).toString(),
                    )
                }
                .toList()

            if (localToSeed.isNotEmpty()) {
                runCatching { SocialRepository.setMySongRatings(localToSeed) }
            }

            LikeStatusStore.mergeRemote(accountKey, remoteByKey)
            _likeOverrides.value = LikeStatusStore.load(accountKey)
            pruneExcludedRecommendations()
        }
    }

    /**
     * Takes an un-liked track out of the library as well, and reports whether
     * it did.
     *
     * Liking and saving are two independent flags on YouTube's side, and
     * clearing only the first leaves the song saved — still feeding the
     * Library tab's Artists shelf, still in the library feeds, with nowhere
     * left in this app to reach it and finish the job. Clearing the heart
     * reads as "forget this song", so it clears both.
     *
     * The token is fetched here rather than taken from [songMenu] because the
     * heart in the player never opens a menu, so there is often nothing
     * cached to take. One extra request, on an action nobody performs in bulk.
     * A song that was never saved has no removal token and this is a no-op.
     */
    private suspend fun forgetFromLibrary(videoId: String): Boolean {
        val menu = YtMusicRepository.songMenu(videoId).getOrNull() ?: return false
        val token = menu.removeFromLibraryToken?.takeIf { menu.inLibrary } ?: return false
        if (YtMusicRepository.setLibraryStatus(token).isFailure) return false
        // The menu may be the one on screen; don't leave it offering a
        // removal that has already happened.
        _songMenu.value = _songMenu.value?.copy(inLibrary = false)
        return true
    }

    /**
     * Takes an un-liked track out of the lists that exist *because* it was
     * liked — the Library tab's Liked Music section, and the Liked Music page
     * itself if it happens to be open.
     *
     * Marking the library stale isn't enough on its own: that only acts when
     * the tab is next opened, and un-liking is nearly always done from inside
     * one of these two lists, looking straight at the row. Leaving it there
     * reads as the tap not having worked — the menu says "Like" again while
     * the song sits in Liked Music.
     *
     * Only ever removes. A track liked from somewhere else doesn't get spliced
     * into a list that YouTube orders for itself; the next fetch places it.
     */
    private fun dropFromLikedLists(videoId: String) {
        val library = (_library.value as? UiState.Success)?.data
        if (library != null && library.likedSongs.any { it.videoId == videoId }) {
            _library.value = UiState.Success(
                library.copy(likedSongs = library.likedSongs.filterNot { it.videoId == videoId }),
            )
        }
        _detailStack.value = _detailStack.value.map { page ->
            val songs = (page.songs as? UiState.Success)?.data
            if (page.browseId != YtMusicRepository.LIKED_MUSIC || songs == null) {
                page
            } else {
                page.copy(songs = UiState.Success(songs.filterNot { it.videoId == videoId }))
            }
        }
    }

    /** The heart: liked becomes neutral, anything else becomes liked. */
    fun toggleLike(song: Song) = setLike(
        song,
        if (likeStatusOf(song) == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE,
    )

    fun toggleLike(videoId: String) = setLike(
        videoId,
        if (likeStatusOf(videoId) == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE,
    )

    /** As [toggleLike], for the thumb-down. */
    fun toggleDislike(song: Song) = setLike(
        song,
        if (likeStatusOf(song) == LikeStatus.DISLIKE) {
            LikeStatus.INDIFFERENT
        } else {
            LikeStatus.DISLIKE
        },
    )

    fun toggleDislike(videoId: String) = setLike(
        videoId,
        if (likeStatusOf(videoId) == LikeStatus.DISLIKE) {
            LikeStatus.INDIFFERENT
        } else {
            LikeStatus.DISLIKE
        },
    )

    /**
     * Saves the album or playlist [browseId] to the library, or takes it out.
     *
     * Written to the screen first and rolled back if YouTube refuses, for the
     * same reason [setLike] is: it is one tap on a page the user is looking at,
     * and a control that waits on a round trip before it changes reads as a tap
     * that missed.
     *
     * A page with no [DetailPage.library] is one YouTube never offered to save
     * — a local page, an auto-playlist, a generated mix — and the UI has no
     * control on it to have been tapped, so this is a no-op rather than a guess.
     */
    fun toggleLibrary(browseId: String) {
        if (!requireSignIn()) return
        val page = _detailStack.value.firstOrNull { it.browseId == browseId } ?: return

        if (!youtubeLibraryBackendAvailable()) {
            if (page.type != BrowseType.ALBUM && page.type != BrowseType.PLAYLIST) return
            val context = getApplication<Application>()
            val currentSaved = OrbLibraryStore.containsCollection(context, browseId)
            val target = !currentSaved
            OrbLibraryStore.setCollection(
                context = context,
                item = ShelfItem(
                    title = page.title,
                    subtitle = page.subtitle,
                    thumbnailUrl = page.thumbnailUrl,
                    videoId = null,
                    browseId = page.browseId,
                    isExplicit = page.isExplicit,
                    type = page.type,
                ),
                saved = target,
            )
            _detailStack.value = _detailStack.value.map { current ->
                if (current.browseId == browseId) {
                    current.copy(library = LibraryState(playlistId = browseId, saved = target))
                } else {
                    current
                }
            }
            publishOrbLibrary()
            return
        }

        val current = page.library ?: return
        val target = !current.saved
        setSavedOnPage(browseId, target)
        viewModelScope.launch {
            if (YtMusicRepository.setSaved(current.playlistId, target).isSuccess) {
                // The Library tab's Albums/Playlists shelf is now out of date.
                libraryStale = true
            } else {
                setSavedOnPage(browseId, current.saved)
            }
        }
    }

    /**
     * Restates whether a page is saved. By id rather than by index: the user may
     * have pushed or popped pages while the write was in flight.
     */
    private fun setSavedOnPage(browseId: String, saved: Boolean) {
        _detailStack.value = _detailStack.value.map { page ->
            val library = page.library
            if (page.browseId != browseId || library == null) {
                page
            } else {
                page.copy(library = library.copy(saved = saved))
            }
        }
        // A warmed first page carries the old library toggle state.
        invalidateDetailWarm(browseId)
    }

    /**
     * Adds/removes one track from whichever library backend is selected.
     *
     * The YouTube backend is used whenever its private account session is available.
     * Otherwise the action falls back to Orb's on-device library, which deliberately
     * does not require a YouTube connection. YouTube writes still fetch a fresh menu
     * token because those opaque tokens are not reversible and become stale after a
     * successful write.
     */
    fun toggleSongLibrary(song: Song) {
        if (!youtubeLibraryBackendAvailable()) {
            val context = getApplication<Application>()
            val target = !OrbLibraryStore.containsSong(context, song.videoId)
            OrbLibraryStore.setSong(context, song, target)
            _songMenu.value = SongMenu(
                likeStatus = null,
                inLibrary = target,
                addToLibraryToken = null,
                removeFromLibraryToken = null,
            )
            songMenuFor = song.videoId
            publishOrbLibrary()
            OrbNoticeCenter.post(
                OrbNotice(
                    title = context.getString(
                        if (target) R.string.notice_added_to_library
                        else R.string.notice_removed_from_library,
                    ),
                    message = song.title,
                    icon = OrbNoticeIcon.LIBRARY,
                ),
            )
            return
        }

        // The sheet may still be finishing its initial menu lookup. Once the
        // user acts, that stale read must not land after the write and restore
        // the old library state in the UI.
        songMenuJob?.cancel()
        songMenuJob = null
        viewModelScope.launch {
            val menu = YtMusicRepository.songMenu(song.videoId).getOrNull() ?: return@launch
            val target = !menu.inLibrary
            val token = if (target) menu.addToLibraryToken else menu.removeFromLibraryToken
            token ?: return@launch
            if (YtMusicRepository.setLibraryStatus(token).isSuccess) {
                _songMenu.value = menu.copy(inLibrary = target)
                songMenuFor = song.videoId
                libraryStale = true
                val context = getApplication<Application>()
                OrbNoticeCenter.post(
                    OrbNotice(
                        title = context.getString(
                            if (target) R.string.notice_added_to_library
                            else R.string.notice_removed_from_library,
                        ),
                        message = song.title,
                        icon = OrbNoticeIcon.LIBRARY,
                    ),
                )
            }
        }
    }

    /**
     * The open track menu's account state, or null while it is still being
     * fetched. Only one menu can be open at a time, so one slot is enough.
     */
    private val _songMenu = MutableStateFlow<SongMenu?>(null)
    val songMenu: StateFlow<SongMenu?> = _songMenu.asStateFlow()

    private var songMenuJob: Job? = null
    private var songMenuFor: String? = null

    /**
     * Loads the account state behind an opening track menu — the library
     * tokens, and any rating the response happens to state.
     *
     * The rating is only ever taken when it *adds* something: a LIKE or a
     * DISLIKE the library couldn't have told us, such as a disliked track or
     * one liked past the tenth page of Liked Music. An INDIFFERENT is
     * discarded.
     *
     * That asymmetry is not fussiness. This lookup reads a watch queue, and a
     * watch queue routinely renders a liked track with no rating on it at all;
     * believing that silence downgraded songs sitting in Liked Music to
     * "not liked" a beat after their menu opened — the label changing under
     * the user, with no request sent and nothing removed.
     */
    fun loadSongMenu(videoId: String?) {
        songMenuJob?.cancel()
        _songMenu.value = null
        songMenuFor = videoId
        if (videoId == null) return

        if (!youtubeLibraryBackendAvailable()) {
            val saved = OrbLibraryStore.containsSong(getApplication<Application>(), videoId)
            _songMenu.value = SongMenu(
                likeStatus = null,
                inLibrary = saved,
                addToLibraryToken = null,
                removeFromLibraryToken = null,
            )
            return
        }

        songMenuJob = viewModelScope.launch {
            val menu = YtMusicRepository.songMenu(videoId).getOrNull() ?: return@launch
            if (songMenuFor != videoId) return@launch
            _songMenu.value = menu
            val stated = menu.likeStatus
            if (stated != null && stated != LikeStatus.INDIFFERENT &&
                videoId !in _likeOverrides.value
            ) {
                _likeOverrides.value += (videoId to stated)
                LikeStatusStore.set(likeAccountKey(), videoId, stated, updatedAtMs = 0L)
            }
        }
    }

    /** The account's own playlists, for the picker and the library tab. */
    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()

    private val _playlistsLoaded = MutableStateFlow(false)
    val playlistsLoaded: StateFlow<Boolean> = _playlistsLoaded.asStateFlow()

    /** Re-fetched rather than cached for the session: playlists are edited here. */
    fun loadPlaylists() {
        if (!_signedIn.value || _playlistsLoading.value) return
        _playlistsLoading.value = true
        viewModelScope.launch {
            YtMusicRepository.userPlaylists().onSuccess { _playlists.value = it }
            _playlistsLoaded.value = true
            _playlistsLoading.value = false
        }
    }

    /**
     * Adds [song] to a playlist.
     *
     * Not optimistic, unlike a rating: there is nothing on screen to update
     * ahead of the answer, and a "Added to X" that turns out to be untrue is
     * worse than one that arrives a moment late.
     */
    fun addToPlaylist(playlist: UserPlaylist, song: Song) {
        if (!requireSignIn()) return
        viewModelScope.launch {
            val context = getApplication<Application>()
            val alreadyInPlaylist = YtMusicRepository
                .playlistContains(playlist.browseId, song.videoId)
                .getOrDefault(false)
            if (alreadyInPlaylist) {
                OrbNoticeCenter.post(
                    OrbNotice(
                        title = context.getString(R.string.notice_already_in_playlist),
                        message = playlist.title,
                        icon = OrbNoticeIcon.INFO,
                    ),
                )
                return@launch
            }

            YtMusicRepository.addToPlaylist(playlist.playlistId, listOf(song.videoId)).fold(
                onSuccess = {
                    SourceResolver.invalidatePlaylistBadges(playlist.browseId)
                    invalidateDetailWarm(playlist.browseId)
                    libraryStale = true
                    OrbNoticeCenter.post(
                        OrbNotice(
                            title = context.getString(R.string.notice_added_to_playlist),
                            message = playlist.title,
                            icon = OrbNoticeIcon.PLAYLIST,
                        ),
                    )
                },
                onFailure = {},
            )
        }
    }

    /**
     * Creates a playlist, seeded with [song] when the flow started from a
     * track's menu — one request, so it can't half-succeed into an empty
     * playlist the user has to add to again.
     */
    fun createPlaylist(title: String, privacy: PlaylistPrivacy, song: Song? = null) {
        if (!requireSignIn()) return
        val name = title.trim().ifBlank { "New playlist" }
        viewModelScope.launch {
            YtMusicRepository.createPlaylist(
                title = name,
                privacy = privacy,
                videoIds = listOfNotNull(song?.videoId),
            ).fold(
                onSuccess = { playlistId ->
                    SourceResolver.invalidatePlaylistBadges("VL$playlistId")
                    invalidateDetailWarm("VL$playlistId")
                    libraryStale = true
                    if (song != null) {
                        val context = getApplication<Application>()
                        OrbNoticeCenter.post(
                            OrbNotice(
                                title = context.getString(R.string.notice_added_to_playlist),
                                message = name,
                                icon = OrbNoticeIcon.PLAYLIST,
                            ),
                        )
                    }
                    loadPlaylists()
                    refresh(Feed.LIBRARY)
                },
                onFailure = {},
            )
        }
    }

    /**
     * Drops [song] from the playlist page it is being read on, and takes the
     * row out from under the reader rather than waiting for a re-fetch.
     */
    fun removeFromPlaylist(browseId: String, song: Song) {
        val setVideoId = song.setVideoId ?: return
        if (!requireSignIn()) return
        val playlistId = browseId.removePrefix("VL")
        viewModelScope.launch {
            YtMusicRepository.removeFromPlaylist(
                playlistId,
                listOf(setVideoId to song.videoId),
            ).fold(
                onSuccess = {
                    SourceResolver.invalidatePlaylistBadges(browseId)
                    invalidateDetailWarm(browseId)
                    libraryStale = true
                    _detailStack.value = _detailStack.value.map { page ->
                        val songs = (page.songs as? UiState.Success)?.data
                        if (page.browseId != browseId || songs == null) {
                            page
                        } else {
                            val remaining =
                                songs.filterNot { it.setVideoId == setVideoId }
                            page.copy(
                                songs = UiState.Success(remaining),
                                isExplicit = remaining.any { it.isExplicit },
                            )
                        }
                    }
                },
                onFailure = {},
            )
        }
    }

    /**
     * Adds one of [DetailPage.suggestedSongs] to the playlist it was
     * suggested for, and drops it from that section — the playlist's own
     * track list only shows it correctly (with a working "remove") once the
     * page is reopened, so there is nothing on screen for it to move to yet.
     */
    fun addSuggestedSong(browseId: String, song: Song) {
        if (!requireSignIn()) return
        val playlistId = browseId.removePrefix("VL")
        viewModelScope.launch {
            YtMusicRepository.addToPlaylist(playlistId, listOf(song.videoId)).fold(
                onSuccess = {
                    SourceResolver.invalidatePlaylistBadges(browseId)
                    invalidateDetailWarm(browseId)
                    libraryStale = true
                    _detailStack.value = _detailStack.value.map { page ->
                        if (page.browseId != browseId) {
                            page
                        } else {
                            page.copy(
                                suggestedSongs = page.suggestedSongs
                                    .filterNot { it.videoId == song.videoId },
                                isExplicit = page.isExplicit || song.isExplicit,
                            )
                        }
                    }
                },
                onFailure = {},
            )
        }
    }

    fun renamePlaylist(playlist: UserPlaylist, title: String) {
        if (!requireSignIn()) return
        val name = title.trim()
        if (name.isBlank() || name == playlist.title) return
        viewModelScope.launch {
            YtMusicRepository.renamePlaylist(playlist.playlistId, name).fold(
                onSuccess = {
                    _playlists.value = _playlists.value.map {
                        if (it.playlistId == playlist.playlistId) it.copy(title = name) else it
                    }
                    libraryStale = true
                    refresh(Feed.LIBRARY)
                },
                onFailure = {},
            )
        }
    }

    fun deletePlaylist(playlist: UserPlaylist) {
        if (!requireSignIn()) return
        viewModelScope.launch {
            YtMusicRepository.deletePlaylist(playlist.playlistId).fold(
                onSuccess = {
                    SourceResolver.invalidatePlaylistBadges(playlist.browseId)
                    invalidateDetailWarm(playlist.browseId)
                    _playlists.value = _playlists.value
                        .filterNot { it.playlistId == playlist.playlistId }
                    // Its page may be the one open; a deleted playlist has
                    // nothing left to show.
                    _detailStack.value = _detailStack.value
                        .filterNot { it.browseId == playlist.browseId }
                    libraryStale = true
                    refresh(Feed.LIBRARY)
                },
                onFailure = {},
            )
        }
    }

    /** Whether [browseId] is a playlist this account can be asked to edit. */
    fun editablePlaylist(browseId: String?): UserPlaylist? {
        if (browseId == null) return null
        return _playlists.value.firstOrNull { it.browseId == browseId }
    }

    /**
     * Guards every account write. All of them are signed-in-only, and the UI
     * hides them for guests — this is the backstop for a session that expired
     * between the menu opening and the tap.
     */
    private fun likeAccountKey(): String? =
        OrbGoogleAuth.storedAccount()?.email
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: SocialRepository.currentUserId()

    private fun restorePersistedLikes() {
        _likeOverrides.value = LikeStatusStore.load(likeAccountKey())
    }

    private fun artistPreferenceAccountKey(): String = likeAccountKey() ?: "local-device"

    private fun restoreArtistPreferences() {
        _artistPreferences.value = ArtistPreferenceStore.load(artistPreferenceAccountKey())
    }

    private fun requireSignIn(): Boolean = _signedIn.value

    /**
     * YouTube Music library sync is usable only while a private account session
     * is actually available. If Google OAuth is rejected by WEB_REMIX, Orb keeps
     * working with its local library instead of forcing a browser login during
     * onboarding or leaving the Library tab stuck loading forever.
     */
    private fun youtubeLibraryBackendAvailable(): Boolean =
        AppSettings.useYouTubeMusicLibrary.value && Innertube.hasAccountSession

    /**
     * Whether the library needs re-fetching. Set by every write above and
     * acted on when the tab is next opened, for the same reason [homeStale]
     * exists: rearranging a page under whoever is reading it is worse than
     * showing it a moment out of date.
     */
    private var libraryStale = false

    /**
     * Call when the library tab becomes visible. Visibility is not an
     * invalidation event: once loaded, the page stays exactly as the listener
     * left it until a write updates it locally or the user pulls to refresh.
     */
    fun onLibraryShown() {
        if (!youtubeLibraryBackendAvailable()) {
            if (_library.value !is UiState.Success) publishOrbLibrary()
            return
        }
        if (!_signedIn.value) return
        if (_library.value is UiState.Loading) return
    }

    init {
        restorePersistedLikes()
        restoreArtistPreferences()
        if (_signedIn.value) {
            reconcileOrbRatingsFromCloud()
            flushPendingYoutubeRatings()
        }
        startSearchPipeline()
        startSuggestPipeline()

        // Home's curated sections are independent surfaces and must all begin
        // loading at cold start. Suggestions paints from its local/progressive
        // sources, while Listen Again/Recent arrive with Home and Library/Recaps
        // come from their account-owned feeds. Serialising these requests made
        // the lower shelves wait behind FEmusic_home even though none of them
        // depends on it, so start the fan-out immediately and let each section
        // publish as soon as its own response is ready.
        loadHome()
        if (Innertube.hasAccountSession) loadHomeYouTubeSections()
        if (_signedIn.value || !AppSettings.useYouTubeMusicLibrary.value) {
            loadLibrary()
        }
        if (_signedIn.value) {
            loadAccount()
            loadPlaylists()
        }
        viewModelScope.launch {
            AppSettings.useYouTubeMusicLibrary.drop(1).collectLatest { useYouTube ->
                songMenuJob?.cancel()
                songMenuFor = null
                _songMenu.value = null

                if (useYouTube) {
                    if (_signedIn.value) loadLibrary() else _library.value = UiState.Loading
                    flushPendingYoutubeRatings()
                } else {
                    publishOrbLibrary()
                }
                refreshOpenDetailLibraryStates(useYouTube && Innertube.hasAccountSession)
            }
        }
        viewModelScope.launch {
            // drop(1): the current value is just the count so far, not a play.
            PlaybackTracker.registeredPlays.drop(1).collect {
                homeStale = true
                releasesHubStale = true
                discoverHubStale = true
            }
        }
        viewModelScope.launch { AppUpdateChecker.check() }
    }

    /**
     * Whether a play has been registered since the home feed was last fetched.
     *
     * The feed leads with listening history, so it's out of date the moment a
     * track starts — but re-fetching there would rearrange the page under
     * whoever is reading it, and the tab is usually in the background anyway.
     * It's re-fetched when the tab is next opened instead.
     */
    private var homeStale = false

    /**
     * Call when Home becomes visible. Returning to a cached page must not
     * trigger a network request; pull-to-refresh is the explicit update path.
     */
    fun onHomeShown() {
        loadReleasesHub()
    }

    private fun loadAccount() {
        // The account shown by Orb is always the central Google identity. The
        // active YouTube channel is a linked profile and is surfaced separately
        // in Account & Integrations, so switching a Brand channel never changes
        // who the Orb/Supabase user is.
        _account.value = OrbGoogleAuth.storedAccount()
        viewModelScope.launch { OrbGoogleAuth.refreshYoutubeAccount() }
    }

    /**
     * A feed that can be pulled down to refresh. Tracked per feed rather than
     * as one flag: a pull on Library while Home is still refreshing in the
     * background shouldn't leave the wrong tab showing a loader.
     */
    enum class Feed { HOME, RELEASES, TRENDING, EXPLORE, DISCOVER, COMMUNITY_PLAYLISTS, LIBRARY }

    private val _refreshing = MutableStateFlow(emptySet<Feed>())
    val refreshing: StateFlow<Set<Feed>> = _refreshing.asStateFlow()

    /**
     * Re-fetches [feed] in place. Unlike the `load*` entry points this leaves
     * the current content on screen rather than dropping back to the loading
     * state — a refresh that swapped the page for a spinner would be a worse
     * experience than the stale content it replaces.
     */
    fun refresh(feed: Feed) {
        if (feed in _refreshing.value) return
        if (feed == Feed.LIBRARY && AppSettings.useYouTubeMusicLibrary.value && !_signedIn.value) return
        _refreshing.value = _refreshing.value + feed
        viewModelScope.launch {
            try {
                when (feed) {
                    Feed.HOME -> fetchHome(preserveExisting = true)
                    Feed.RELEASES -> fetchReleasesHub()
                    Feed.TRENDING -> fetchTrendingHub()
                    Feed.EXPLORE -> fetchExplore()
                    Feed.DISCOVER -> fetchDiscoverHub()
                    Feed.COMMUNITY_PLAYLISTS -> fetchCommunityPlaylistsHub()
                    Feed.LIBRARY -> fetchLibrary()
                }
            } finally {
                _refreshing.value = _refreshing.value - feed
            }
        }
    }

    /**
     * Starts (or joins) the first-page/header warm-up for one album/playlist.
     *
     * The same Deferred is shared by feed warm-up and openDetail(), so a tap
     * while the background request is still running never launches a duplicate
     * browse call. Crucially, this foreground path stops at the first browse
     * response: optional album metadata enrichment must never hold the track
     * list behind a second network lookup.
     */
    private fun detailWarmup(
        browseId: String,
        title: String,
        subtitle: String,
        type: BrowseType,
    ): Deferred<DetailWarmEntry> {
        detailWarmCache.get(browseId)?.let { cached ->
            return viewModelScope.async { cached }
        }

        synchronized(detailWarmups) {
            detailWarmCache.get(browseId)?.let { cached ->
                return viewModelScope.async { cached }
            }
            detailWarmups[browseId]?.let { return it }

            val started = viewModelScope.async {
                // Feed workers already start only while foreground detail
                // priority is idle. Do not re-check that priority from inside
                // the request itself: when the listener taps the exact card
                // being warmed we preserve and join this request instead of
                // cancelling a nearly-finished browse and starting from zero.
                backgroundDetailWarmupGate.withPermit {
                    coroutineScope {
                        val pageJob = async {
                            YtMusicRepository.browseSongs(browseId)
                        }
                        val pageResult = pageJob.await()
                        val page = pageResult.getOrNull()

                        // Publish whatever canonical metadata arrived in the
                        // same browse response. If an old response shape omitted a
                        // year/credit, openDetail() enriches that later, after the
                        // first tracks are already on screen.
                        val canonicalSubtitle = if (type == BrowseType.ALBUM) {
                            page?.releaseSubtitle?.takeIf { it.isNotBlank() }
                        } else {
                            null
                        }

                        DetailWarmEntry(
                            page = page,
                            pageError = pageResult.exceptionOrNull(),
                            canonicalSubtitle = canonicalSubtitle,
                        )
                    }
                }.also { warmed ->
                    if (warmed.page != null) {
                        detailWarmCache.put(browseId, warmed)
                    }
                }
            }
            detailWarmups[browseId] = started
            started.invokeOnCompletion {
                synchronized(detailWarmups) {
                    if (detailWarmups[browseId] === started) {
                        detailWarmups.remove(browseId)
                    }
                }
            }
            return started
        }
    }

    /**
     * Cancels speculative collection browses except the card the listener just
     * opened. Preserving that exact request matters on mobile data: cancelling
     * a browse after it already spent several seconds on the wire was the main
     * reason an album/playlist tap could restart the full timeout window.
     */
    private fun cancelSpeculativeDetailWarmups(exceptBrowseId: String? = null) {
        val pending = synchronized(detailWarmups) {
            val cancelled = detailWarmups
                .filterKeys { it != exceptBrowseId }
                .values
                .toList()
            detailWarmups.keys
                .filter { it != exceptBrowseId }
                .toList()
                .forEach(detailWarmups::remove)
            cancelled
        }
        pending.forEach { it.cancel() }
    }

    /** Same policy for artist landing-page warmups. */
    private fun cancelSpeculativeArtistWarmups(exceptBrowseId: String? = null) {
        val pending = synchronized(artistWarmups) {
            val cancelled = artistWarmups
                .filterKeys { it != exceptBrowseId }
                .values
                .toList()
            artistWarmups.keys
                .filter { it != exceptBrowseId }
                .toList()
                .forEach(artistWarmups::remove)
            cancelled
        }
        pending.forEach { it.cancel() }
    }

    /**
     * First-page load for a page the user actually opened. Unlike speculative
     * feed warm-up this never waits for the background semaphore. Any queued
     * prefetches are cancelled first so the tap cannot sit behind a wall of
     * album-card work. A completed warm cache still wins and costs no network.
     */
    private suspend fun foregroundDetailWarmup(
        browseId: String,
        title: String,
        subtitle: String,
        type: BrowseType,
    ): DetailWarmEntry {
        detailWarmCache.get(browseId)?.let { return it }

        // Never wait for/join speculative work here. The video that exposed
        // this regression showed a background card warm-up holding the detail
        // screen for several seconds before the real foreground request even
        // started. Cancel it and issue the short fast-read immediately.
        cancelSpeculativeDetailWarmups()

        val pageResult = YtMusicRepository.browseSongsForeground(browseId)
        val page = pageResult.getOrNull()
        val warmed = DetailWarmEntry(
            page = page,
            pageError = pageResult.exceptionOrNull(),
            canonicalSubtitle = if (type == BrowseType.ALBUM) {
                page?.releaseSubtitle?.takeIf { it.isNotBlank() }
            } else {
                null
            },
        )
        if (page != null) detailWarmCache.put(browseId, warmed)
        return warmed
    }

    private fun artistWarmup(browseId: String): Deferred<ArtistPage?> {
        artistWarmCache.get(browseId)?.let { cached ->
            return viewModelScope.async { cached }
        }
        synchronized(artistWarmups) {
            artistWarmCache.get(browseId)?.let { cached ->
                return viewModelScope.async { cached }
            }
            artistWarmups[browseId]?.let { return it }
            val started = viewModelScope.async {
                backgroundArtistWarmupGate.withPermit {
                    YtMusicRepository.artistLandingPage(browseId).getOrNull()
                }?.also { artistWarmCache.put(browseId, it) }
            }
            artistWarmups[browseId] = started
            started.invokeOnCompletion {
                synchronized(artistWarmups) {
                    if (artistWarmups[browseId] === started) artistWarmups.remove(browseId)
                }
            }
            return started
        }
    }

    /**
     * Foreground artist landing. Join an existing card warmup when possible;
     * otherwise use the short foreground browse policy so a dead mobile socket
     * does not hold the hero/header for the global 30-second timeout.
     */
    private suspend fun foregroundArtistLanding(browseId: String): Result<ArtistPage> {
        artistWarmCache.get(browseId)?.let { return Result.success(it) }

        // Same rule as album/playlist: a tap must not inherit the latency of a
        // background prefetch. Start the anonymous/public catalogue fast-read
        // immediately; repository code falls back to authenticated browse only
        // when the fast answer cannot describe this artist.
        cancelSpeculativeArtistWarmups()
        return YtMusicRepository.artistLandingPageForeground(browseId).onSuccess {
            artistWarmCache.put(browseId, it)
        }
    }

    /**
     * Collection headers are latency-sensitive, so they get their own queue
     * instead of waiting behind track Lossless probes from earlier shelves
     * (Recently played is commonly first on Home). The badge worker below joins
     * these same Deferreds when it reaches the collection, so this does not
     * duplicate the browse request.
     */
    private fun warmFeedDetails(shelves: List<HomeShelf>) {
        // Deliberately no speculative browse requests.
        //
        // BitChord's fast detail navigation comes from doing the browse the
        // user actually asked for, not from opening dozens of album/playlist/
        // artist pages behind Home. Orb's previous warm-up could occupy shared
        // sockets and, worse, an explicit tap could join a stalled warm request.
        // Keep image/card data already on the feed; detail catalogue I/O begins
        // only when the page is opened.
        @Suppress("UNUSED_VARIABLE")
        val keepSignatureStable = shelves
    }

    private fun invalidateDetailWarm(browseId: String) {
        detailWarmCache.remove(browseId)
        synchronized(detailWarmups) {
            detailWarmups.remove(browseId)?.cancel()
        }
    }

    /**
     * Resolves one feed card all the way to the badge state the Composable is
     * allowed to expose. Keeping this as the single primitive lets Home run it
     * in priority batches while Explore/Library can still warm whole shelves.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun prepareFeedItemBadges(item: ShelfItem, force: Boolean) {
        runCatching {
            // Feed enrichment must never browse a collection merely to discover
            // a badge. That turns every album/playlist card into hidden network
            // work competing with the page the listener actually opens.
            //
            // Explicit metadata already present on an album card is local.
            // Quality is learned from playback/download, never catalogue browsing.
            if (item.type == BrowseType.ALBUM && item.isExplicit) {
                SourceResolver.cacheReleaseExplicit(
                    title = item.title,
                    subtitle = item.subtitle,
                    browseId = item.browseId,
                    explicit = true,
                )
            }

            // Catalogue cards use cached quality only; audio resolves on playback/download.
        }
    }

    /** Background enrichment yields while an opened collection owns priority. */
    private suspend fun awaitForegroundDetailBadges() {
        if (detailBadgePriorityCount.value == 0) return
        // Safety ceiling: a broken source must never starve every background
        // feed forever. The foreground pass itself remains free to continue.
        withTimeoutOrNull(DETAIL_BADGE_PRIORITY_GRACE_MS) {
            detailBadgePriorityCount.first { it == 0 }
        }
    }

    /** Badge-capable cards in one small visual batch are prepared concurrently. */
    private suspend fun warmFeedBadgeBatch(
        items: List<ShelfItem>,
        force: Boolean = false,
    ) {
        awaitForegroundDetailBadges()
        val targets = items
            .filter { item ->
                !item.videoId.isNullOrBlank() ||
                        item.type == BrowseType.ALBUM ||
                        (item.type == BrowseType.PLAYLIST && !item.browseId.isNullOrBlank())
            }
            .distinctBy { item ->
                when {
                    !item.videoId.isNullOrBlank() -> "track:${item.videoId}"
                    item.type == BrowseType.PLAYLIST -> "playlist:${item.browseId}"
                    else -> "album:${item.title.lowercase()}|${item.subtitle.lowercase()}"
                }
            }
        if (targets.isEmpty()) return

        coroutineScope {
            targets.map { item ->
                async { prepareFeedItemBadges(item, force) }
            }.forEach { it.await() }
        }
    }

    /**
     * Non-Home feeds are useful to prewarm, but they should not consume the
     * same TIDAL/detail probes while the priority Home pass is still running.
     * The grace timeout prevents a broken Home request from starving Explore
     * or Library indefinitely.
     */
    private suspend fun awaitHomePriorityPass() {
        if (homePriorityPassReady.value || _home.value is UiState.Error) return
        withTimeoutOrNull(HOME_PRIORITY_PASS_GRACE_MS) {
            homePriorityPassReady.first { it }
        }
    }

    /**
     * Eager warm-up used outside Home. Home itself is deliberately progressive
     * and therefore calls [warmFeedBadgeBatch] directly in visual order.
     */
    private suspend fun warmFeedBadges(
        shelves: List<HomeShelf>,
        force: Boolean = false,
        yieldToHomePriority: Boolean = false,
    ) {
        if (yieldToHomePriority) awaitHomePriorityPass()

        // Once Home owns the critical path, other feeds can prepare collection
        // headers broadly again; taps then benefit from the larger warm cache.
        warmFeedDetails(shelves)

        val targets = shelves
            .asSequence()
            .flatMap { shelf -> shelf.items.asSequence() }
            .toList()
        targets.chunked(BADGE_WARMUP_CONCURRENCY).forEach { batch ->
            warmFeedBadgeBatch(batch, force = force)
        }
    }

    fun loadExplore() {
        _explore.value = UiState.Loading
        viewModelScope.launch { fetchExplore() }
    }

    private suspend fun fetchExplore() {
        _explore.value = YtMusicRepository.explore().fold(
            onSuccess = { shelves ->
                if (shelves.isEmpty()) {
                    UiState.Error("Nothing to explore right now")
                } else {
                    // Explore keeps its previous eager behavior; the strict
                    // content+badge publication rule is specific to Home.
                    viewModelScope.launch { warmFeedBadges(shelves, yieldToHomePriority = true) }
                    UiState.Success(shelves)
                }
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
    }

    fun loadReleasesHub(force: Boolean = false) {
        if (!force && releasesHubRequested && !releasesHubStale) return
        releasesHubRequested = true
        if (_releasesHub.value !is UiState.Success) _releasesHub.value = UiState.Loading
        viewModelScope.launch { fetchReleasesHub() }
    }

    private suspend fun fetchReleasesHub() {
        val favoriteArtists = _artistPreferences.value
            .filterValues { it == ArtistPreference.LIKE }
            .keys
        _releasesHub.value = YtMusicRepository.releasesHub(favoriteArtists).fold(
            onSuccess = { shelves ->
                releasesHubStale = false
                if (shelves.isNotEmpty()) {
                    warmFeedDetails(shelves)
                    viewModelScope.launch { warmFeedBadges(shelves, yieldToHomePriority = true) }
                }
                // The Albums page can still show the listener's locally ranked
                // favorite albums even when YouTube returns no network shelves.
                UiState.Success(shelves)
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
    }

    fun loadTrendingHub(force: Boolean = false) {
        if (!force && trendingHubRequested) return
        trendingHubRequested = true
        if (_trendingHub.value !is UiState.Success) _trendingHub.value = UiState.Loading
        viewModelScope.launch { fetchTrendingHub() }
    }

    private suspend fun fetchTrendingHub() {
        _trendingHub.value = YtMusicRepository.trendingHub().fold(
            onSuccess = { shelves ->
                trendingHubStale = false
                if (shelves.isEmpty()) {
                    UiState.Error("No chart data found right now")
                } else {
                    viewModelScope.launch { warmFeedBadges(shelves, yieldToHomePriority = true) }
                    UiState.Success(shelves)
                }
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
    }

    fun loadDiscoverHub(force: Boolean = false) {
        if (!force && discoverHubRequested) return
        discoverHubRequested = true
        if (_discoverHub.value !is UiState.Success) _discoverHub.value = UiState.Loading
        viewModelScope.launch { fetchDiscoverHub() }
    }

    private suspend fun fetchDiscoverHub() {
        _discoverHub.value = YtMusicRepository.discoverHub().fold(
            onSuccess = { shelves ->
                discoverHubStale = false
                if (shelves.isEmpty()) {
                    UiState.Error("Nothing new to discover right now")
                } else {
                    viewModelScope.launch { warmFeedBadges(shelves, yieldToHomePriority = true) }
                    UiState.Success(shelves)
                }
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
    }

    fun loadCommunityPlaylistsHub(force: Boolean = false) {
        if (!force && communityPlaylistsHubRequested) return
        communityPlaylistsHubRequested = true
        if (_communityPlaylistsHub.value !is UiState.Success) {
            _communityPlaylistsHub.value = UiState.Loading
        }
        viewModelScope.launch { fetchCommunityPlaylistsHub() }
    }

    private suspend fun fetchCommunityPlaylistsHub() {
        _communityPlaylistsHub.value = YtMusicRepository.communityPlaylistsHub().fold(
            onSuccess = { shelves ->
                communityPlaylistsHubStale = false
                if (shelves.isEmpty()) {
                    UiState.Error("No community playlists found right now")
                } else {
                    viewModelScope.launch { warmFeedBadges(shelves, yieldToHomePriority = true) }
                    UiState.Success(shelves)
                }
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
    }

    /** Orb v1.5.1: tapping a tab simply leaves every pushed detail page behind. */
    fun clearDetail() {
        if (_detailStack.value.isNotEmpty()) _detailStack.value = emptyList()
    }

    private fun cacheDetailPage(page: DetailPage) {
        if (page.songs is UiState.Success) detailPageCache.put(page.browseId, page)
    }

    fun loadHome() {
        _home.value = UiState.Loading
        homePriorityPassReady.value = false
        viewModelScope.launch { fetchHome(preserveExisting = false) }
    }

    /**
     * Refreshes only Home's listening-history shelf.
     *
     * The remaining Home shelves, their scroll state and their warmed quality
     * metadata stay untouched. A full Home refresh that starts while this
     * request is in flight wins through [homeGeneration].
     */
    fun refreshRecentlyPlayed() {
        if (recentlyPlayedRefreshJob?.isActive == true) return
        if (_home.value !is UiState.Success) return

        val generation = homeGeneration.get()
        recentlyPlayedRefreshJob = viewModelScope.launch {
            try {
                val refreshed = YtMusicRepository.recentlyPlayedShelf().getOrNull()
                    ?: return@launch
                if (generation != homeGeneration.get()) return@launch

                val current = (_home.value as? UiState.Success)?.data
                    ?: return@launch
                val existingIndex = current.indexOfFirst {
                    it.title.equals(refreshed.title, ignoreCase = true)
                }
                if (existingIndex >= 0 && current[existingIndex] == refreshed) {
                    return@launch
                }

                val updated = if (existingIndex >= 0) {
                    current.toMutableList().apply {
                        this[existingIndex] = refreshed
                    }
                } else {
                    listOf(refreshed) + current
                }

                homeSeenTitles.add(refreshed.title.lowercase())
                _home.value = UiState.Success(updated)

                // Only new history cards need quality/detail enrichment.
                warmHomeShelvesProgressively(
                    shelves = listOf(refreshed),
                    generation = generation,
                    force = false,
                )
            } finally {
                recentlyPlayedRefreshJob = null
            }
        }
    }

    /**
     * Warms Home badge/cache state in visual order without gating visibility.
     *
     * Home content is now catalogue-first: shelves paint as soon as YouTube
     * returns them, while Lossless is enrichment. The first visible cards still
     * get first claim on TIDAL/detail work, and everything proven here remains
     * in SourceResolver/detail caches for the rest of the process.
     */
    private suspend fun warmHomeShelvesProgressively(
        shelves: List<HomeShelf>,
        generation: Long,
        force: Boolean,
    ) {
        val usable = shelves.filter { it.items.isNotEmpty() }

        // Pass 1: vertical viewport priority. Resolve a few cards from each top
        // shelf before spending time on horizontally off-screen items.
        usable.forEachIndexed { index, shelf ->
            if (generation != homeGeneration.get()) return
            val firstSize = if (index == 0) HOME_TOP_HERO_BATCH_SIZE else HOME_SHELF_FIRST_BATCH_SIZE
            warmFeedBadgeBatch(
                shelf.items.take(firstSize),
                force = force,
            )
        }

        // Pass 2: fill the rest in bounded batches. No UI waits on this pass.
        usable.forEachIndexed { index, shelf ->
            var offset = if (index == 0) HOME_TOP_HERO_BATCH_SIZE else HOME_SHELF_FIRST_BATCH_SIZE
            offset = offset.coerceAtMost(shelf.items.size)
            while (offset < shelf.items.size) {
                if (generation != homeGeneration.get()) return
                val end = (offset + HOME_BADGE_BATCH_SIZE).coerceAtMost(shelf.items.size)
                warmFeedBadgeBatch(shelf.items.subList(offset, end), force = force)
                offset = end
            }
        }
    }

    /**
     * Fetches New Releases below the personalised feed. The shelves are appended
     * immediately; their Lossless enrichment continues afterward.
     */
    private suspend fun prefetchHomeSupplemental(
        current: List<HomeShelf>,
        generation: Long,
    ): List<HomeShelf> {
        if (generation != homeGeneration.get()) return current
        val fetched = YtMusicRepository.homeSupplemental().getOrDefault(emptyList())
        if (generation != homeGeneration.get()) return current
        val shelves = filterPersonalizedShelves(fetched)
            .filter { homeSeenTitles.add(it.title.lowercase()) }
        if (shelves.isEmpty()) return current

        val ready = current + shelves
        _home.value = UiState.Success(ready)
        warmHomeShelvesProgressively(shelves, generation, force = false)
        return ready
    }

    private suspend fun fetchHome(preserveExisting: Boolean = false) {
        val generation = homeGeneration.incrementAndGet()
        homeBadgeWarmJob?.cancel()
        if (!preserveExisting) homePriorityPassReady.value = false

        val existing = (_home.value as? UiState.Success)?.data.orEmpty()
        _homeLoadingMore.value = true
        try {
            val result = YtMusicRepository.home()
            if (generation != homeGeneration.get()) return

            result.fold(
                onSuccess = { feed ->
                    homeSeenTitles.clear()
                    var shelves = filterPersonalizedShelves(feed.shelves)
                        .filter { homeSeenTitles.add(it.title.lowercase()) }
                    homeContinuation = feed.continuation
                    var supplementalUsedAsFallback = false

                    if (shelves.isEmpty()) {
                        val fallback = YtMusicRepository.homeSupplemental().getOrDefault(emptyList())
                        if (generation != homeGeneration.get()) return@fold
                        shelves = filterPersonalizedShelves(fallback)
                            .filter { homeSeenTitles.add(it.title.lowercase()) }
                        supplementalUsedAsFallback = shelves.isNotEmpty()
                    }

                    if (shelves.isEmpty()) {
                        if (!preserveExisting || existing.isEmpty()) {
                            _home.value = UiState.Error("No results from YouTube Music")
                        }
                        homePriorityPassReady.value = true
                        return@fold
                    }

                    // Critical change: catalogue content paints immediately.
                    // Explicit metadata already present on the row paints with
                    // it; Lossless arrives whenever its asynchronous proof is
                    // ready and is then session-cached.
                    _home.value = UiState.Success(shelves)
                    homePriorityPassReady.value = true
                    // Detail first pages/photos are cheaper and more visible
                    // than quality badges. Start warming them immediately so a
                    // tap on an album, playlist or artist can open with content.
                    warmFeedDetails(shelves)

                    homeBadgeWarmJob = viewModelScope.launch {
                        warmHomeShelvesProgressively(
                            shelves = shelves,
                            generation = generation,
                            force = preserveExisting,
                        )
                        if (generation != homeGeneration.get()) return@launch

                        // Useful idle work survives navigation because it is
                        // ViewModel-owned, not tied to the Home Composable.
                        if (!supplementalUsedAsFallback) {
                            prefetchHomeSupplemental(shelves, generation)
                        }
                    }
                },
                onFailure = { failure ->
                    if (!preserveExisting || existing.isEmpty()) {
                        _home.value = UiState.Error(failure.friendly())
                    }
                    homePriorityPassReady.value = true
                },
            )
        } finally {
            if (generation == homeGeneration.get()) _homeLoadingMore.value = false
        }
    }

    private fun loadHomeYouTubeSections(force: Boolean = false) {
        if (!Innertube.hasAccountSession) {
            homeYouTubeSectionsJob?.cancel()
            homeYouTubeSectionsJob = null
            homeYouTubeSectionsLoaded = false
            _homeYouTubeSections.value = emptyList()
            return
        }
        if (!force && (homeYouTubeSectionsLoaded || homeYouTubeSectionsJob?.isActive == true)) return

        homeYouTubeSectionsJob?.cancel()
        if (force) homeYouTubeSectionsLoaded = false
        homeYouTubeSectionsJob = viewModelScope.launch {
            try {
                // Library and Recaps are independent endpoints. Publish each
                // shelf the instant it finishes instead of waiting for the
                // slower sibling request before either can appear on Home.
                supervisorScope {
                    launch {
                        YtMusicRepository.forYouYouTubeLibrarySection()
                            .getOrNull()
                            ?.let { shelf ->
                                _homeYouTubeSections.value =
                                    _homeYouTubeSections.value
                                        .filterNot { it.title.equals(shelf.title, ignoreCase = true) } + shelf
                                warmHomeShelvesProgressively(
                                    shelves = listOf(shelf),
                                    generation = homeGeneration.get(),
                                    force = false,
                                )
                            }
                    }
                    launch {
                        YtMusicRepository.forYouRecapsSection()
                            .getOrNull()
                            ?.let { shelf ->
                                _homeYouTubeSections.value =
                                    _homeYouTubeSections.value
                                        .filterNot { it.title.equals(shelf.title, ignoreCase = true) } + shelf
                                warmHomeShelvesProgressively(
                                    shelves = listOf(shelf),
                                    generation = homeGeneration.get(),
                                    force = false,
                                )
                            }
                    }
                }
                homeYouTubeSectionsLoaded = true
            } finally {
                homeYouTubeSectionsJob = null
            }
        }
    }

    /**
     * Continuation pages also paint immediately and warm their badges afterward.
     */
    fun loadMoreHome() {
        val token = homeContinuation ?: return
        if (_homeLoadingMore.value || Feed.HOME in _refreshing.value) return
        val generation = homeGeneration.get()
        _homeLoadingMore.value = true

        viewModelScope.launch {
            try {
                val feed = YtMusicRepository.moreHome(token).getOrNull() ?: return@launch
                if (generation != homeGeneration.get()) return@launch

                val added = filterPersonalizedShelves(feed.shelves)
                    .filter { homeSeenTitles.add(it.title.lowercase()) }
                homeContinuation = feed.continuation.takeIf { added.isNotEmpty() }
                if (added.isEmpty()) return@launch

                val existing = (_home.value as? UiState.Success)?.data.orEmpty()
                _home.value = UiState.Success(existing + added)
                viewModelScope.launch {
                    warmHomeShelvesProgressively(
                        shelves = added,
                        generation = generation,
                        force = false,
                    )
                }
            } finally {
                if (generation == homeGeneration.get()) _homeLoadingMore.value = false
            }
        }
    }

    fun loadLibrary() {
        if (AppSettings.useYouTubeMusicLibrary.value && !_signedIn.value) return
        _library.value = UiState.Loading
        viewModelScope.launch { fetchLibrary() }
    }

    private suspend fun fetchLibrary() {
        if (!youtubeLibraryBackendAvailable()) {
            publishOrbLibrary()
            return
        }
        if (!_signedIn.value) {
            _library.value = UiState.Loading
            return
        }
        _library.value = YtMusicRepository.library().fold(
            onSuccess = { page ->
                if (page.isEmpty) {
                    UiState.Error("Nothing in your library yet")
                } else {
                    viewModelScope.launch { warmFeedBadges(page.shelves, yieldToHomePriority = true) }
                    UiState.Success(page)
                }
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
    }

    /** Publishes the device-local Orb library immediately after every local write. */
    private fun publishOrbLibrary() {
        val context = getApplication<Application>()
        val page = OrbLibraryStore.page(context)
        if (!page.isEmpty) {
            viewModelScope.launch {
                warmFeedBadges(page.shelves, yieldToHomePriority = true)
            }
        }
        _library.value = UiState.Success(page)
    }

    /**
     * Rebinds save-state controls on already-open pages when the Settings switch
     * changes library backend. The Google account remains signed in; only the
     * destination represented by the +/check control changes.
     */
    private fun refreshOpenDetailLibraryStates(useYouTube: Boolean) {
        val context = getApplication<Application>()
        if (!useYouTube) {
            _detailStack.value = _detailStack.value.map { page ->
                if (page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST) {
                    page.copy(
                        library = LibraryState(
                            playlistId = page.browseId,
                            saved = OrbLibraryStore.containsCollection(context, page.browseId),
                        ),
                    )
                } else {
                    page
                }
            }
            return
        }

        if (!_signedIn.value) {
            _detailStack.value = _detailStack.value.map { page ->
                if (page.type == BrowseType.ALBUM || page.type == BrowseType.PLAYLIST) {
                    page.copy(library = null)
                } else page
            }
            return
        }

        _detailStack.value
            .filter { it.type == BrowseType.ALBUM || it.type == BrowseType.PLAYLIST }
            .map { it.browseId }
            .distinct()
            .forEach { browseId ->
                viewModelScope.launch {
                    val remote = YtMusicRepository.browseSongs(browseId).getOrNull()?.library ?: return@launch
                    _detailStack.value = _detailStack.value.map { page ->
                        if (page.browseId == browseId) page.copy(library = remote) else page
                    }
                }
            }
    }

    /** Recent searches, kept on device. */
    val searchHistory: StateFlow<List<SearchHistoryEntry>> = SearchHistory.recent

    fun onQueryChange(value: String) {
        val previous = _query.value
        _query.value = value
        if (value.isBlank()) {
            // Emptying the fixed Explore field returns to recent searches and
            // discovery. No second in-content search row is ever created.
            suggestionWantedFor = null
            newestRequestId.incrementAndGet()
            _results.value = null
            _suggestions.value = emptyList()
            return
        }

        suggestionWantedFor = value
        // Keep only real completions that are still plausible while the fresh
        // typeahead request runs. Older builds inserted the raw query itself as
        // row zero, which looked like a second search bar under the fixed one.
        val stale = if (value.startsWith(previous, true) || previous.startsWith(value, true)) {
            _suggestions.value
        } else {
            emptyList()
        }
        _suggestions.value = stale.filterNot { it.equals(value, true) }
        suggestRequests.tryEmit(value)
    }

    /**
     * Commits the current query to the history. Called when the user acts on
     * what they found — submitting from the keyboard, or opening a result —
     * rather than on every keystroke, which would fill the list with the
     * prefixes typed on the way to the real query.
     */
    fun recordSearch() = SearchHistory.record(_query.value)

    fun recordSearch(song: Song) = SearchHistory.record(
        query = _query.value,
        thumbnailUrl = song.thumbnailUrl,
    )

    fun recordSearch(item: BrowseItem) = SearchHistory.record(
        query = _query.value,
        thumbnailUrl = if (item.type == BrowseType.ALBUM || item.type == BrowseType.ARTIST) {
            item.thumbnailUrl
        } else {
            null
        },
    )

    /**
     * The search button — the keyboard's search action, or the magnifier in
     * the field. The only thing that runs a search for text the user typed:
     * keystrokes themselves ask for suggestions and nothing more, so a query
     * is fetched once, when they say it's finished, instead of once per
     * prefix on the way to it.
     */
    fun submitSearch() {
        recordSearch()
        suggestionWantedFor = null
        _suggestions.value = emptyList()
        runSearch()
    }

    /**
     * Runs a term the user picked out of a list rather than typed — a recent
     * search, or one of [suggestions] — and floats it to the top of the
     * history. Picking is as deliberate as submitting, so it searches on the
     * spot.
     */
    fun searchFor(term: String) {
        _query.value = term
        suggestionWantedFor = null
        _suggestions.value = emptyList()
        SearchHistory.record(term)
        runSearch()
    }

    fun removeSearch(term: String) = SearchHistory.remove(term)

    fun clearSearchHistory() = SearchHistory.clear()

    fun onFilterChange(value: SearchFilter) {
        if (_filter.value == value) return
        _filter.value = value
        runSearch()
    }

    /** Adds the first concrete result artwork to the recent-search entry. */
    private fun rememberSearchArtwork(
        query: String,
        filter: SearchFilter,
        rows: List<SearchResult>,
    ) {
        val thumbnail = when (filter) {
            SearchFilter.SONGS -> rows.filterIsInstance<SearchResult.Track>()
                .firstOrNull()?.song?.thumbnailUrl

            SearchFilter.ALBUMS -> rows.filterIsInstance<SearchResult.Browse>()
                .firstOrNull { it.item.type == BrowseType.ALBUM }?.item?.thumbnailUrl

            SearchFilter.ARTISTS -> rows.filterIsInstance<SearchResult.Browse>()
                .firstOrNull { it.item.type == BrowseType.ARTIST }?.item?.thumbnailUrl

            SearchFilter.PLAYLISTS -> null
        }
        if (!thumbnail.isNullOrBlank()) SearchHistory.record(query, thumbnail)
    }

    /**
     * A search asked for, as a request the pipeline below decides what to do
     * with.
     *
     * [requestId] is what makes a late answer harmless: a response is only
     * written to the screen if its id is still the newest one asked for.
     */
    private data class SearchRequest(
        val query: String,
        val filter: SearchFilter,
        val requestId: Long,
        val force: Boolean = false,
    )

    private fun cacheKey(query: String, filter: SearchFilter) = "${filter.name}:$query"

    /**
     * The results of the longest earlier query this one starts with — near
     * enough to leave up while the narrower search runs.
     */
    private fun prefixMatch(query: String, filter: SearchFilter): List<SearchResult>? {
        val prefix = "${filter.name}:"
        return searchCache.snapshot()
            .filterKeys { it.startsWith(prefix) && query.startsWith(it.removePrefix(prefix), true) }
            .maxByOrNull { it.key.length }
            ?.value
    }

    private fun runSearch() {
        val query = _query.value
        if (query.isBlank()) {
            // Nothing in flight can still be waiting to overwrite this: the
            // id it would be checked against has already moved past it.
            newestRequestId.incrementAndGet()
            _results.value = null
            return
        }
        val id = newestRequestId.incrementAndGet()
        searchRequests.tryEmit(SearchRequest(query, _filter.value, id))
    }

    /** Re-runs the visible search without clearing its cached rows first. */
    fun refreshSearch() {
        val query = _query.value.trim()
        if (query.isBlank() || _searchRefreshing.value) return
        val id = newestRequestId.incrementAndGet()
        _searchRefreshing.value = true
        searchRequests.tryEmit(SearchRequest(query, _filter.value, id, force = true))
    }

    /**
     * The search pipeline, started once and left running for the lifetime of
     * the view model.
     *
     * The point of it being one long-lived collector is that a new search no
     * longer cancels the request before it out of a fresh coroutine.
     * Cancelling a call mid-flight tears down its socket, and on a pooled HTTP
     * client that is felt by whatever picks that connection up next — which is
     * how one search could end in "Software caused connection abort" for a
     * request that was never itself in any trouble.
     *
     * There is no debounce here any more, and nothing to absorb: a search is
     * only ever asked for by a deliberate act — the search button, a
     * suggestion or history row, a filter tab — so the request that arrives is
     * already the one the user meant, and making them wait out a timer for it
     * would be a delay with nothing behind it. Typing asks
     * [startSuggestPipeline] for completions instead and leaves the results
     * alone.
     */
    private fun startSearchPipeline() = viewModelScope.launch {
        searchRequests
            .collectLatest { request ->
                try {
                    val key = cacheKey(request.query, request.filter)
                    // Something to look at immediately: the exact answer if this
                    // query has been run before, otherwise the closest earlier
                    // one. Manual refresh deliberately bypasses this early return.
                    val exact = searchCache.get(key)
                    if (exact != null && !request.force) {
                        // Search rows are cached as soon as YouTube returns them. Badge
                        // enrichment is deliberately a second, background phase, so
                        // switching Songs -> Artists -> Songs restores the already-found
                        // Songs page instantly instead of starting another network search.
                        if (request.requestId == newestRequestId.get()) {
                            _results.value = UiState.Success(exact)
                            rememberSearchArtwork(request.query, request.filter, exact)
                            prefetchTopResult(exact)
                            warmSearchRowsInBackground(exact)
                        }
                        return@collectLatest
                    }
                    // A manual refresh keeps the current page painted. Only a first
                    // uncached search falls back to the loading skeleton.
                    if (!request.force) _results.value = UiState.Loading

                    // Search is YouTube's alone. A module is a *substitution*
                    // layer, not a catalogue to browse: it never has cover art,
                    // radio, related tracks or an album page, so its rows arrived
                    // in the results list looking like YouTube's and then behaved
                    // nothing like them. Every track found here takes the ordinary
                    // YouTube path and is handed to the module at playback time.
                    val result = YtMusicRepository.search(request.query, request.filter)
                    // A search that has been superseded shouldn't land on screen,
                    // whether it succeeded or failed.
                    if (request.requestId != newestRequestId.get()) return@collectLatest
                    result.fold(
                        onSuccess = { rows ->
                            rememberSearchArtwork(request.query, request.filter, rows)
                            publishSearchProgressively(
                                rows = rows,
                                key = key,
                                requestId = request.requestId,
                            )
                        },
                        onFailure = { failure ->
                            // Refresh failure keeps the cached rows instead of
                            // replacing a usable page with an error state.
                            if (!request.force && request.requestId == newestRequestId.get()) {
                                _results.value = UiState.Error(failure.friendly())
                            }
                        },
                    )
                } finally {
                    if (request.force) _searchRefreshing.value = false
                }
            }
    }

    /**
     * The typeahead pipeline, alongside [startSearchPipeline] and for the same
     * structural reason — one long-lived collector rather than a coroutine per
     * keystroke, so a lookup the user has typed past doesn't take a pooled
     * socket down with it.
     *
     * This one *does* debounce, and that isn't the timer that was taken off the
     * search. It's two orders of magnitude shorter, and it's paid for by the
     * request behind it being a few hundred bytes rather than a full page of
     * results — a burst of keystrokes shouldn't each cost a round trip, but the
     * gap has to be short enough that the list is up before the next letter is
     * typed. Nothing is waiting on it either way: the row the user typed is
     * already on screen from the keystroke itself.
     *
     * A failure is left on the floor. There is no worthwhile way to report
     * "couldn't suggest anything" in a list of suggestions, and the typed text
     * is standing there as a working first row regardless.
     */
    @OptIn(FlowPreview::class)
    private fun startSuggestPipeline() = viewModelScope.launch {
        // Whether a list for [input] is still wanted. False once the field has
        // moved on: typed further, or searched — which empties [_suggestions],
        // and a late answer writing to it would reopen the suggestions over
        // the results the user is by then reading.
        fun stillWanted(input: String) =
            _query.value == input && suggestionWantedFor == input

        suggestRequests
            .debounce(SUGGEST_DEBOUNCE_MS)
            .collectLatest { input ->
                if (!stillWanted(input)) return@collectLatest
                val fetched = YtMusicRepository.searchSuggestions(input).getOrNull()
                    ?: return@collectLatest
                // Asked again on the way back; the field is live throughout.
                if (!stillWanted(input)) return@collectLatest
                _suggestions.value = fetched
                    .filterNot { it.equals(input, ignoreCase = true) }
                    .distinctBy { it.lowercase() }
            }
    }

    /**
     * Resolves only the badge work belonging to one search row. The search
     * pipeline publishes that row immediately after this returns instead of
     * waiting for the rest of the page.
     */
    private suspend fun prepareSearchRow(row: SearchResult) {
        awaitForegroundDetailBadges()
        runCatching {
            when (row) {
                is SearchResult.Track ->
                    SourceResolver.cachedTrackPlayableBadge(row.song)

                is SearchResult.Browse -> {
                    // Search already has everything required to paint a browse
                    // card. Never open that album/playlist invisibly just to
                    // validate a quality badge; the actual detail tap owns the
                    // first browse request. Album Explicit metadata from the
                    // search row itself remains cheap to cache.
                    if (row.item.type == BrowseType.ALBUM && row.item.isExplicit) {
                        SourceResolver.cacheReleaseExplicit(
                            title = row.item.title,
                            subtitle = row.item.subtitle,
                            browseId = row.item.browseId,
                            explicit = true,
                        )
                    }
                }
            }
        }
    }

    /**
     * Publishes search results progressively.
     *
     * YouTube returns a result page as one response, but the expensive part of
     * Orb's search is per-row enrichment (collection details + Explicit/Lossless).
     * Previously the whole page was hidden until the slowest row finished. Now
     * rows are prepared in small top-first batches and each completed row is
     * released immediately. The visible subset is kept in YouTube relevance
     * order, so a slower earlier row can slot into its proper place later.
     */
    private suspend fun publishSearchProgressively(
        rows: List<SearchResult>,
        key: String,
        requestId: Long,
    ) {
        if (rows.isEmpty()) {
            if (requestId == newestRequestId.get()) {
                _results.value = UiState.Error("No results")
            }
            return
        }
        if (requestId != newestRequestId.get()) return

        // Catalogue first, enrichment second. The YouTube response is already a
        // complete, useful search page, so publish and cache it immediately.
        // Explicit/Lossless then fill themselves from SourceResolver/detail caches
        // as the background pass completes. This removes the old wait where the
        // slowest badge probe decided when a result was allowed to appear.
        searchCache.put(key, rows)
        _results.value = UiState.Success(rows)
        prefetchTopResult(rows)
        warmSearchRowsInBackground(rows)
    }

    /**
     * Enriches search rows after they are already visible. This job belongs to
     * the ViewModel rather than the currently selected filter, so changing tabs
     * does not cancel the work and returning to a cached result keeps both the
     * rows and any badges already resolved.
     */
    private fun warmSearchRowsInBackground(rows: List<SearchResult>) {
        viewModelScope.launch {
            rows.chunked(SEARCH_PROGRESSIVE_BATCH_SIZE).forEach { batch ->
                coroutineScope {
                    batch.map { row -> async { prepareSearchRow(row) } }.awaitAll()
                }
            }
        }
    }

    /**
     * The enabled non-YouTube sources, asked at the same time and returned
     * split at YouTube's own place in the order.
     *
     * The split is what makes the Sources screen's ordering visible where it
     * matters most. A library server ranked above YouTube puts its own copies
     * at the top of the results — which is the whole point of ranking it there —
     * and one ranked below appears under them instead.
     *
     * Only the Songs filter fans out: albums, artists and playlists are
     * browse-shaped, and [MusicSource] deliberately answers for tracks only.
     */
    private suspend fun sourceResults(
        query: String,
        filter: SearchFilter,
    ): Pair<List<SearchResult>, List<SearchResult>> = coroutineScope {
        if (filter != SearchFilter.SONGS) return@coroutineScope emptyList<SearchResult>() to emptyList()
        val active = SourceRegistry.active()
        val youtubeRank = active.indexOfFirst { it.kind == SourceKind.YOUTUBE }
            .let { if (it < 0) active.size else it }

        val answers = active
            .filter { it.kind != SourceKind.YOUTUBE }
            .map { source ->
                source to async {
                    // Per-source, so one slow or unreachable server delays the
                    // results by at most this much rather than for as long as
                    // its socket takes to give up.
                    runCatching {
                        withTimeout(SOURCE_SEARCH_TIMEOUT_MS) { source.search(query, SOURCE_SEARCH_LIMIT) }
                    }.getOrDefault(emptyList())
                }
            }

        val above = mutableListOf<SearchResult>()
        val below = mutableListOf<SearchResult>()
        answers.forEach { (source, job) ->
            val rows = job.await().map { SearchResult.Track(it) }
            val rank = active.indexOfFirst { it.configId == source.configId }
            if (rank in 0 until youtubeRank) above += rows else below += rows
        }
        above to below
    }

    /**
     * Warms the stream URL for the top song result the instant results land,
     * not when it's tapped. [AudioCache] gives a head start to whatever's
     * already queued; a fresh search has nothing queued yet, and the top
     * result is overwhelmingly what gets tapped — see [play][MainActivity.play].
     * [resolveAudio][YtMusicRepository.resolveAudio] first, same as the tap
     * path itself, so a video-tagged result warms the catalogue audio's id
     * rather than one nothing will ever ask for.
     */
    private fun prefetchTopResult(rows: List<SearchResult>) {
        val song = rows.filterIsInstance<SearchResult.Track>().firstOrNull()?.song ?: return
        viewModelScope.launch {
            runCatching {
                StreamResolver.resolve(YtMusicRepository.resolveAudio(song).videoId)
            }
        }
    }

    private companion object {
        /** Maximum number of speculative badge/source probes prepared in parallel. */
        const val BADGE_WARMUP_CONCURRENCY = 2

        /**
         * Search rows are prepared top-first in small batches. Four keeps enough
         * I/O in flight for quick first paint without letting a long result page
         * flood TIDAL/detail requests and starve playback/Home work.
         */
        const val SEARCH_PROGRESSIVE_BATCH_SIZE = 4

        /** The hero row only exposes about one card at a time; warm two first. */
        const val HOME_TOP_HERO_BATCH_SIZE = 2

        /** Compact shelves expose roughly two-to-three cards on a phone. */
        const val HOME_SHELF_FIRST_BATCH_SIZE = 3

        /** Follow-up chunks stay small so the next shelf can begin promptly. */
        const val HOME_BADGE_BATCH_SIZE = 4

        /** Other feed badge work yields briefly while Home owns the startup I/O budget. */
        const val HOME_PRIORITY_PASS_GRACE_MS = 5_000L

        /** Weighted row budgets: large pages consume multiple units, not one slot. */
        const val DETAIL_WARM_CACHE_ENTRIES = 48
        const val ARTIST_WARM_CACHE_ENTRIES = 24
        const val DETAIL_PAGE_CACHE_ENTRIES = 24

        /** Background work may wait this long for an opened collection's badges. */
        const val DETAIL_BADGE_PRIORITY_GRACE_MS = 15_000L

        /** Collection first pages prepared at once, independently of badge probes. */
        // Two speculative browse calls are enough to make nearby cards warm
        // without competing with foreground navigation for the network.
        const val DETAIL_WARMUP_CONCURRENCY = 2
        const val ARTIST_WARMUP_CONCURRENCY = 2

        /**
         * How long a keystroke waits before the typeahead is asked about it.
         *
         * Not the search's timer — searches aren't on a timer any more. This
         * one only stops a fast typist spending a round trip per letter, so it
         * wants to be as short as it can be while still collapsing a burst:
         * long enough that "cold" isn't four lookups, short enough that the
         * list is up by the time the thumb has left the key.
         */
        const val SUGGEST_DEBOUNCE_MS = 180L

        const val SEARCH_CACHE_ENTRIES = 40

        /**
         * How long any one source gets to answer a search.
         *
         * Short on purpose: these run alongside the YouTube search, and their
         * only job is to be *there* when it lands. A home server reached over
         * a VPN that takes eight seconds has effectively not answered, and
         * holding the whole result list for it would make search feel worse
         * for the sake of results the user can still get by searching again.
         */
        const val SOURCE_SEARCH_TIMEOUT_MS = 4000L

        /** Enough to be worth scrolling, short enough not to bury YouTube's own rows. */
        const val SOURCE_SEARCH_LIMIT = 12
    }

    fun openDetail(
        browseId: String,
        title: String,
        subtitle: String = "",
        thumbnailUrl: String? = null,
        type: BrowseType = BrowseType.OTHER,
        isExplicit: Boolean = false,
    ) {
        _refreshingDetails.value = _refreshingDetails.value - browseId
        detailOpeningJobs.remove(browseId)?.cancel()
        detailContinuationJobs.remove(browseId)?.cancel()
        detailArtistVersionsJobs.remove(browseId)?.cancel()
        val resolved = typeOf(browseId, type)
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = resolved,
            // Compatibility-only field added after v1.5.1. It is carried from
            // the already-known card and performs no work during page opening.
            isExplicit = isExplicit,
        )
        val openedAt = android.os.SystemClock.elapsedRealtime()
        detailOpeningJobs[browseId] = viewModelScope.launch {
            var sections = emptyList<HomeShelf>()
            var artwork: String? = null
            var name: String? = null
            var credit = subtitle
            var audience: String? = null
            var artists = emptyList<ArtistLink>()
            var more: String? = null
            var suggested: List<Song> = emptyList()
            var library: LibraryState? = null
            val state = when {
                browseId == "local:downloads" -> {
                    val context = getApplication<Application>()
                    val songs = LocalMediaRepository.getDownloadedSongs(context)
                    if (songs.isEmpty()) UiState.Error(context.getString(R.string.library_downloads_empty))
                    else UiState.Success(songs)
                }
                browseId == "local:all" -> {
                    val context = getApplication<Application>()
                    if (!LocalMediaRepository.hasStoragePermission(context)) {
                        UiState.Error("Storage permission required to view local audio files")
                    } else {
                        val songs = LocalMediaRepository.getLocalMusic(context)
                        if (songs.isEmpty()) UiState.Error("No audio files found on device")
                        else UiState.Success(songs)
                    }
                }
                resolved == BrowseType.ARTIST -> {
                    YtMusicRepository.artistPageV151(browseId).fold(
                        onSuccess = { page ->
                            sections = page.sections
                            artwork = page.thumbnailUrl
                            name = page.name
                            audience = page.monthlyAudience
                            page.name?.let { artistName ->
                                page.thumbnailUrl?.let { ArtistArtworkRepository.remember(artistName, it) }
                            }
                            if (page.songs.isEmpty() && page.sections.isEmpty()) {
                                UiState.Error("No tracks here")
                            } else {
                                UiState.Success(page.songs.withArtwork(thumbnailUrl))
                            }
                        },
                        onFailure = { UiState.Error(it.friendly()) },
                    )
                }
                else -> {
                    YtMusicRepository.browseSongsV151(browseId).fold(
                        onSuccess = { page ->
                            if (page.songs.isEmpty()) {
                                UiState.Error("No tracks here")
                            } else {
                                // Some playlist cards arrive without artwork (notably restored/
                                // shared entries). The browse response has the authoritative
                                // collection header, so backfill it before songs are queued. This
                                // also makes Stats persist the playlist cover instead of a null.
                                artwork = page.thumbnailUrl ?: artwork
                                credit = page.releaseSubtitle?.takeIf { it.isNotBlank() } ?: credit
                                artists = page.headerArtists
                                if (resolved == BrowseType.ALBUM) sections = page.sections
                                more = page.continuation
                                suggested = page.suggested.withArtwork(thumbnailUrl)
                                library = page.library
                                UiState.Success(page.songs.withArtwork(thumbnailUrl))
                            }
                        },
                        onFailure = { UiState.Error(it.friendly()) },
                    )
                }
            }
            val validatedSongs = (state as? UiState.Success)?.data.orEmpty()
            val validatedExplicit = isExplicit || validatedSongs.any { it.isExplicit }
            if (validatedExplicit) {
                when (resolved) {
                    BrowseType.ALBUM -> SourceResolver.cacheReleaseExplicit(
                        title = name ?: title,
                        subtitle = credit,
                        browseId = browseId,
                        explicit = true,
                    )
                    BrowseType.PLAYLIST -> SourceResolver.playlistBadges(
                        browseId = browseId,
                        explicitHint = true,
                        knownSongs = validatedSongs,
                    )
                    else -> Unit
                }
            }

            _detailStack.value = _detailStack.value.map { current ->
                if (current.browseId == browseId && current.songs is UiState.Loading) {
                    current.copy(
                        songs = state,
                        sections = sections,
                        thumbnailUrl = artwork ?: current.thumbnailUrl,
                        title = name ?: current.title,
                        subtitle = credit,
                        monthlyAudience = audience,
                        headerArtists = artists,
                        suggestedSongs = suggested,
                        library = library,
                        isExplicit = current.isExplicit || validatedExplicit,
                    )
                } else {
                    current
                }
            }
            if (resolved == BrowseType.ARTIST && artwork.isNullOrBlank()) {
                val artistName = name ?: title
                launch {
                    val resolvedArtwork = ArtistArtworkRepository.resolve(artistName, browseId) ?: return@launch
                    _detailStack.value = _detailStack.value.map { current ->
                        if (current.browseId == browseId && current.thumbnailUrl.isNullOrBlank()) {
                            current.copy(thumbnailUrl = resolvedArtwork)
                        } else current
                    }
                }
            }
            android.util.Log.d("OrbDetail", "first result $browseId in ${android.os.SystemClock.elapsedRealtime() - openedAt}ms")
            if (resolved == BrowseType.ARTIST && state is UiState.Success) {
                enrichArtistReleaseVersions(browseId, sections)
            }
            more?.let { fillInV151(browseId, it, thumbnailUrl) }
        }
    }

    /**
     * Expands the artist's release shelves after the visible landing page is on screen.
     *
     * YouTube Music inlines only a carousel-sized preview in the artist browse. The
     * shelf header points at a fully paged Albums / Singles destination, so fetch that
     * destination in the background and replace the preview with the complete list.
     *
     * Artist pages intentionally show one canonical release only. Standard/deluxe/
     * extended/expanded siblings belong on the album page's existing "Other versions"
     * shelf. We use YouTube's own Other versions relation as the authority, then choose
     * the least edition-like / shortest title in each relation cluster. This also
     * catches renamed editions that do not literally contain the word "Deluxe".
     */
    private fun enrichArtistReleaseVersions(browseId: String, seedSections: List<HomeShelf>) {
        detailArtistVersionsJobs.remove(browseId)?.cancel()

        fun normalized(value: String): String =
            Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace('’', '\'')
                .replace(Regex("\\s+"), " ")
                .trim()

        val editionWords = listOf(
            "deluxe", "extended", "expanded", "anniversary", "special edition",
            "bonus track", "bonus tracks", "super deluxe", "collector edition",
            "collector's edition", "tour edition", "complete edition", "remaster",
            "remastered", "digital deluxe", "ultimate edition", "platinum edition",
            "legacy edition", "edition deluxe", "edicao deluxe", "edicion deluxe",
            "versao deluxe", "version deluxe", "edicao especial", "edicion especial",
        )

        fun editionPenalty(title: String): Int {
            val text = normalized(title)
            return editionWords.count { it in text }
        }

        fun releaseFamilyKey(title: String): String {
            var text = normalized(title)
            // Strip only explicit edition qualifiers here. YouTube's own Other
            // versions graph below handles aliases whose title changed entirely.
            val marker = "deluxe|extended|expanded|anniversary|special edition|bonus tracks?|super deluxe|digital deluxe|ultimate edition|platinum edition|legacy edition|collector(?:'s)? edition|tour edition|complete edition|remaster(?:ed)?|edicao deluxe|edicion deluxe|versao deluxe|version deluxe|edicao especial|edicion especial"
            text = Regex("\\s*[\\(\\[][^)\\]]*(?:$marker)[^)\\]]*[\\)\\]]\\s*").replace(text, " ")
            text = Regex("\\s*[-–—:]\\s*(?:$marker).*$").replace(text, "")
            return text
                .replace(Regex("[^a-z0-9]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { normalized(title) }
        }

        fun canonicalRelease(items: List<ShelfItem>): ShelfItem? = items
            .filter { it.type == BrowseType.ALBUM && !it.browseId.isNullOrBlank() }
            .minWithOrNull(
                compareBy<ShelfItem> { editionPenalty(it.title) }
                    .thenBy { normalized(it.title).length }
                    .thenBy { it.title },
            )

        fun canonicalizeByTitle(items: List<ShelfItem>): List<ShelfItem> {
            val groups = LinkedHashMap<String, MutableList<ShelfItem>>()
            items.forEach { item ->
                if (item.type != BrowseType.ALBUM || item.browseId.isNullOrBlank()) return@forEach
                groups.getOrPut(releaseFamilyKey(item.title)) { mutableListOf() }.add(item)
            }
            return groups.values.mapNotNull(::canonicalRelease)
        }

        fun isReleaseShelf(shelf: HomeShelf): Boolean {
            if (shelf.items.any { it.type == BrowseType.ALBUM }) return true
            val title = normalized(shelf.title)
            return title.contains("album") || title.contains("albun") ||
                title.contains("single") || title.contains(" ep") ||
                title.startsWith("ep") || title.contains("release") ||
                title.contains("lancamento") || title.contains("lanzamiento")
        }

        val releaseShelfIndexes = seedSections.indices.filter { isReleaseShelf(seedSections[it]) }
        val seedAlbums = releaseShelfIndexes.flatMap { seedSections[it].items }
            .filter { it.type == BrowseType.ALBUM && !it.browseId.isNullOrBlank() }
            .distinctBy { it.browseId }
        if (releaseShelfIndexes.isEmpty() && !AppSettings.prioritizeAlbumVersions.value) return

        detailArtistVersionsJobs[browseId] = viewModelScope.launch {
            val gate = Semaphore(3)

            // Phase 1: replace each carousel preview with the complete paged shelf.
            val expandedByIndex = coroutineScope {
                releaseShelfIndexes.map { index ->
                    async {
                        val shelf = seedSections[index]
                        val expanded = shelf.browseId?.let { shelfBrowseId ->
                            gate.withPermit {
                                YtMusicRepository.artistReleaseShelfItemsV151(
                                    shelfBrowseId,
                                    shelf.browseParams,
                                ).getOrDefault(emptyList())
                            }
                        }.orEmpty()
                        index to (expanded.takeIf { it.isNotEmpty() } ?: shelf.items)
                    }
                }.awaitAll().toMap()
            }

            val allAlbums = expandedByIndex.values.flatten()
                .filter { it.type == BrowseType.ALBUM && !it.browseId.isNullOrBlank() }
                .distinctBy { it.browseId }
                .ifEmpty { seedAlbums }

            // Phase 2: ask each release which ids YouTube itself considers alternate
            // editions. Instead of appending those siblings to the artist page, map
            // every sibling back to one canonical standard release.
            val canonicalById = coroutineScope {
                allAlbums.map { album ->
                    async {
                        val albumId = album.browseId!!
                        val variants = gate.withPermit {
                            YtMusicRepository.albumOtherVersionsV151(albumId)
                                .getOrDefault(emptyList())
                        }
                        val related = (listOf(album) + variants)
                            .filter { it.type == BrowseType.ALBUM && !it.browseId.isNullOrBlank() }
                            .distinctBy { it.browseId }
                        val canonical = canonicalRelease(related) ?: album
                        related.mapNotNull { sibling -> sibling.browseId?.let { it to canonical } }
                    }
                }.awaitAll().flatten().toMap()
            }

            val current = _detailStack.value.lastOrNull { it.browseId == browseId } ?: return@launch
            val currentSongs = (current.songs as? UiState.Success)?.data.orEmpty()
            val preferredTop = if (AppSettings.prioritizeAlbumVersions.value) {
                coroutineScope {
                    currentSongs.take(8).map { song ->
                        async {
                            gate.withPermit { YtMusicRepository.resolvePreferredPlaybackVersion(song) }
                        }
                    }.awaitAll()
                }
            } else {
                emptyList()
            }

            _detailStack.value = _detailStack.value.map { page ->
                if (page.browseId != browseId) return@map page

                val cleanedSections = page.sections
                    .filterNot { it.title.isOtherVersionsShelf() }
                    .mapIndexed { index, shelf ->
                        if (index !in releaseShelfIndexes && !isReleaseShelf(shelf)) return@mapIndexed shelf
                        val raw = expandedByIndex[index] ?: shelf.items
                        val replaced = raw.map { item ->
                            item.browseId?.let(canonicalById::get) ?: item
                        }.distinctBy { it.browseId ?: "${it.title}|${it.subtitle}" }
                        shelf.copy(items = canonicalizeByTitle(replaced))
                    }

                val songs = (page.songs as? UiState.Success)?.data
                val enrichedSongs = if (songs != null && preferredTop.isNotEmpty()) {
                    UiState.Success(
                        songs.mapIndexed { index, song -> preferredTop.getOrNull(index) ?: song },
                    )
                } else {
                    page.songs
                }
                page.copy(sections = cleanedSections, songs = enrichedSongs)
            }
        }
    }

    private fun String.isOtherVersionsShelf(): Boolean {
        val value = Normalizer.normalize(lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return value.contains("other version") || value.contains("outras vers") || value.contains("otras version")
    }

    /** Append metadata pages after the first visible response. */
    private fun fillInV151(browseId: String, token: String, artworkFallback: String?) {
        detailContinuationJobs.remove(browseId)?.cancel()
        detailContinuationJobs[browseId] = viewModelScope.launch {
            var next: String? = token
            var page = 1
            while (next != null && page++ < YtMusicRepository.MAX_PAGES) {
                val requested = next ?: return@launch
                val fetched = YtMusicRepository.moreSongsV151(requested).getOrNull()
                    ?: return@launch
                val stack = _detailStack.value
                val index = stack.indexOfFirst { it.browseId == browseId }
                if (index < 0) return@launch
                val current = stack[index]
                val existing = (current.songs as? UiState.Success)?.data ?: return@launch
                val known = existing.mapTo(HashSet()) { it.videoId }
                val added = fetched.songs
                    .filter { known.add(it.videoId) }
                    .withArtwork(artworkFallback)
                val knownSuggested = current.suggestedSongs.mapTo(HashSet()) { it.videoId }
                val addedSuggested = fetched.suggested
                    .filter { it.videoId !in known && knownSuggested.add(it.videoId) }
                    .withArtwork(artworkFallback)
                if (added.isEmpty() && addedSuggested.isEmpty()) return@launch
                _detailStack.value = stack.toMutableList().also { pages ->
                    pages[index] = current.copy(
                        songs = UiState.Success(existing + added),
                        suggestedSongs = current.suggestedSongs + addedSuggested,
                    )
                }
                next = fetched.continuation
            }
        }
    }

    /**
     * Explicit pull-to-refresh for an already loaded detail page. The old page
     * remains on screen while the request runs and is only replaced by a
     * successful response, so refresh never turns a usable page back into a
     * loading skeleton or an error card.
     */
    fun refreshDetail(browseId: String) {
        if (browseId in _refreshingDetails.value) return
        val existing = _detailStack.value.lastOrNull { it.browseId == browseId } ?: return
        detailOpeningJobs.remove(browseId)?.cancel()
        detailContinuationJobs.remove(browseId)?.cancel()
        detailArtistVersionsJobs.remove(browseId)?.cancel()
        _refreshingDetails.value = _refreshingDetails.value + browseId

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                val resolved = typeOf(browseId, existing.type)
                var sections = existing.sections
                var artwork = existing.thumbnailUrl
                var title = existing.title
                var subtitle = existing.subtitle
                var monthlyAudience = existing.monthlyAudience
                var headerArtists = existing.headerArtists
                var suggested = existing.suggestedSongs
                var library = existing.library
                var more: String? = null

                val refreshedSongs: List<Song>? = when {
                    browseId == "local:downloads" ->
                        LocalMediaRepository.getDownloadedSongs(getApplication<Application>())

                    browseId == "local:all" -> {
                        val context = getApplication<Application>()
                        if (!LocalMediaRepository.hasStoragePermission(context)) null
                        else LocalMediaRepository.getLocalMusic(context)
                    }

                    resolved == BrowseType.ARTIST -> {
                        YtMusicRepository.artistPageV151(browseId).getOrNull()?.let { artist ->
                            sections = artist.sections
                            artwork = artist.thumbnailUrl ?: artwork
                            title = artist.name ?: title
                            monthlyAudience = artist.monthlyAudience ?: monthlyAudience
                            artist.songs.withArtwork(artwork)
                        }
                    }

                    resolved == BrowseType.ALBUM || resolved == BrowseType.PLAYLIST -> {
                        YtMusicRepository.browseSongsV151(browseId).getOrNull()?.let { page ->
                            subtitle = page.releaseSubtitle?.takeIf { it.isNotBlank() } ?: subtitle
                            if (resolved == BrowseType.ALBUM) sections = page.sections
                            more = page.continuation
                            suggested = page.suggested.withArtwork(artwork).filterNot(::shouldAvoidPlayback)
                            headerArtists = page.headerArtists
                            library = if (youtubeLibraryBackendAvailable()) {
                                page.library
                            } else {
                                LibraryState(
                                    playlistId = browseId,
                                    saved = OrbLibraryStore.containsCollection(
                                        getApplication<Application>(),
                                        browseId,
                                    ),
                                )
                            }
                            page.songs.withArtwork(artwork)
                        }
                    }

                    else -> {
                        YtMusicRepository.browseSongsV151(browseId).getOrNull()?.let { page ->
                            more = page.continuation
                            suggested = page.suggested.withArtwork(artwork).filterNot(::shouldAvoidPlayback)
                            headerArtists = page.headerArtists
                            library = page.library
                            page.songs.withArtwork(artwork)
                        }
                    }
                }

                if (refreshedSongs != null) {
                    val refreshed = existing.copy(
                        title = title,
                        subtitle = subtitle,
                        thumbnailUrl = artwork,
                        songs = if (refreshedSongs.isEmpty()) {
                            existing.songs
                        } else {
                            UiState.Success(refreshedSongs)
                        },
                        sections = sections,
                        monthlyAudience = monthlyAudience,
                        headerArtists = headerArtists,
                        continuation = more,
                        suggestedSongs = suggested,
                        library = library,
                        isExplicit = existing.isExplicit || refreshedSongs.any { it.isExplicit },
                    )
                    _detailStack.value = _detailStack.value.map { page ->
                        if (page.browseId == browseId) refreshed else page
                    }
                    cacheDetailPage(refreshed)
                    if (resolved == BrowseType.ARTIST) {
                        enrichArtistReleaseVersions(browseId, sections)
                    }
                    val continuationAfterRefresh = more
                    if (
                        continuationAfterRefresh != null &&
                        (resolved == BrowseType.ALBUM || resolved == BrowseType.PLAYLIST)
                    ) {
                        fillInV151(
                            browseId = browseId,
                            token = continuationAfterRefresh,
                            artworkFallback = artwork,
                        )
                    }
                    // The refresh indicator ends with the first useful response.
                    _refreshingDetails.value = _refreshingDetails.value - browseId
                }
            } finally {
                if (detailOpeningJobs[browseId] === owner) {
                    _refreshingDetails.value = _refreshingDetails.value - browseId
                    detailOpeningJobs.remove(browseId)
                }
            }
        }
        detailOpeningJobs[browseId] = job
        job.start()
    }

    fun reloadLocalDetail(browseId: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val state: UiState<List<Song>> = when (browseId) {
                "local:downloads" -> {
                    val songs = LocalMediaRepository.getDownloadedSongs(context)
                    if (songs.isEmpty()) UiState.Error(context.getString(R.string.library_downloads_empty))
                    else UiState.Success(songs)
                }
                "local:all" -> {
                    if (!LocalMediaRepository.hasStoragePermission(context)) {
                        UiState.Error("Storage permission required to view local audio files")
                    } else {
                        val songs = LocalMediaRepository.getLocalMusic(context)
                        if (songs.isEmpty()) UiState.Error("No audio files found on device")
                        else UiState.Success(songs)
                    }
                }
                else -> return@launch
            }
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId) {
                    it.copy(songs = state)
                } else it
            }
        }
    }

    /**
     * Follows an album/playlist continuation only after its first page is
     * already visible. Each response is appended immediately, so a long
     * collection becomes complete underneath the reader without ever putting
     * the initial render behind several network round trips.
     *
     * This intentionally uses the ordinary continuation lane rather than the
     * foreground retry lane: after page one there is no UI waiting for the next
     * batch. A failed continuation leaves the already-visible rows untouched
     * and keeps its token cached, so reopening/refreshing can try again.
     */
    private fun fillInDetail(
        browseId: String,
        token: String,
        artworkFallback: String?,
    ) {
        if (detailContinuationJobs[browseId]?.isActive == true) return

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                var next: String? = token
                var page = 1
                while (next != null && page++ < YtMusicRepository.MAX_PAGES) {
                    val requestedToken = next
                    // The token came from a page that is already visible.
                    // Prefer the fast anonymous continuation so OAuth refresh
                    // cannot turn background list growth into more network
                    // contention; account auth is only a fallback.
                    val fetched = YtMusicRepository.moreSongs(requestedToken).getOrNull()
                        ?: return@launch
                    if (coroutineContext[Job]?.isActive != true) return@launch

                    val stack = _detailStack.value
                    val index = stack.indexOfFirst { it.browseId == browseId }
                    if (index < 0) return@launch
                    val current = stack[index]
                    val existing = (current.songs as? UiState.Success)?.data ?: return@launch

                    val known = existing.mapTo(HashSet()) { it.videoId }
                    val added = fetched.songs
                        .filter { known.add(it.videoId) }
                        .withArtwork(artworkFallback)

                    val knownSuggested = current.suggestedSongs.mapTo(HashSet()) { it.videoId }
                    val addedSuggested = fetched.suggested
                        .filter { it.videoId !in known && knownSuggested.add(it.videoId) }
                        .withArtwork(artworkFallback)
                        .filterNot(::shouldAvoidPlayback)

                    val nextToken = fetched.continuation
                        ?.takeUnless { it == requestedToken }

                    // A continuation that adds nothing has looped or reached a
                    // non-track tail. Mark it complete instead of spinning on
                    // tokens the user cannot see.
                    if (added.isEmpty() && addedSuggested.isEmpty()) {
                        val completed = current.copy(continuation = null)
                        _detailStack.value = stack.toMutableList().also { it[index] = completed }
                        cacheDetailPage(completed)
                        return@launch
                    }

                    val updated = current.copy(
                        songs = UiState.Success(existing + added),
                        continuation = nextToken,
                        suggestedSongs = current.suggestedSongs + addedSuggested,
                        isExplicit = current.isExplicit || added.any { it.isExplicit },
                    )
                    _detailStack.value = stack.toMutableList().also { it[index] = updated }
                    cacheDetailPage(updated)
                    next = nextToken
                }
            } finally {
                if (detailContinuationJobs[browseId] === owner) {
                    detailContinuationJobs.remove(browseId)
                }
            }
        }
        detailContinuationJobs[browseId] = job
        job.start()
    }

    /**
     * Fetches the signed-in save state after the detail page is already
     * paintable. The fast foreground browse is intentionally anonymous so a
     * token refresh/cookie compatibility retry can never put the screen back
     * behind a spinner. If account enrichment is slow or fails, the catalogue
     * page remains fully usable; only the save/check control waits.
     */
    private fun enrichDetailLibraryState(browseId: String) {
        viewModelScope.launch {
            delay(1_000)
            if (_detailStack.value.none { it.browseId == browseId }) return@launch

            val authenticated = YtMusicRepository.browseSongs(browseId).getOrNull()
                ?.library
                ?: return@launch

            var updatedPage: DetailPage? = null
            _detailStack.value = _detailStack.value.map { current ->
                if (current.browseId == browseId) {
                    current.copy(library = authenticated).also { updatedPage = it }
                } else {
                    current
                }
            }
            updatedPage?.let(::cacheDetailPage)
        }
    }

    /**
     * Compatibility entry point kept for the existing screen callback. Paging
     * is automatic now; if anything still asks for more explicitly, simply
     * ensure the same background fill job is running.
     */
    fun loadMoreDetail(browseId: String) {
        val visible = _detailStack.value.lastOrNull { it.browseId == browseId } ?: return
        val token = visible.continuation ?: return
        fillInDetail(
            browseId = browseId,
            token = token,
            artworkFallback = visible.thumbnailUrl,
        )
    }

    /**
     * An album's track listing doesn't repeat the cover on every row — the
     * page carries it once — so rows arrive with no artwork and stay blank
     * through to the queue and the notification. Fall back to the page's.
     */
    private fun List<Song>.withArtwork(fallback: String?): List<Song> {
        if (fallback == null) return this
        return map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = fallback) else it }
    }

    /**
     * True when an album subtitle is missing either the release year or the
     * artist/credit. Those are precisely the two fields that vary depending on
     * whether the page was opened from Home, Search or an artist shelf.
     */
    private fun albumMetadataIsIncomplete(subtitle: String): Boolean {
        val parts = subtitle
            .split("•", "·")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val hasYear = parts.any { it.length == 4 && it.all(Char::isDigit) }
        val hasCredit = parts.any { part ->
            val lower = part.lowercase()
            lower !in DETAIL_KIND_WORDS && !(part.length == 4 && part.all(Char::isDigit))
        }
        return !hasYear || !hasCredit
    }

    /**
     * Reuses the album-search parser as the canonical metadata source. Search
     * already returns the complete subtitle for the exact same browse id, which
     * avoids inventing a second parser in the ViewModel and keeps Home, artist
     * shelves and Search converging on one representation.
     */
    private suspend fun canonicalAlbumSubtitle(browseId: String, title: String): String? {
        val rows = YtMusicRepository.search(title, SearchFilter.ALBUMS).getOrNull() ?: return null
        val albums = rows.filterIsInstance<SearchResult.Browse>()

        val exactId = albums.firstOrNull { it.item.browseId == browseId }
        if (exactId != null) return exactId.item.subtitle.takeIf { it.isNotBlank() }

        // Some YouTube surfaces alias an album browse id. Fall back only when
        // there is exactly one same-title album, avoiding a random edition when
        // several releases share a name.
        val sameTitle = albums.filter { it.item.title.equals(title, ignoreCase = true) }
        return sameTitle.singleOrNull()?.item?.subtitle?.takeIf { it.isNotBlank() }
    }

    private val DETAIL_KIND_WORDS = setOf(
        "album", "single", "ep", "playlist", "artist", "podcast", "episode", "song", "video",
    )

    /**
     * Home and Explore cards don't say what they point at, and an artist
     * fetched as an album only yields the five songs on its landing page.
     * YouTube's browse ids are prefixed by kind, so use that.
     */
    private fun typeOf(browseId: String, fallback: BrowseType): BrowseType = when {
        browseId.startsWith("UC") -> BrowseType.ARTIST
        browseId.startsWith("MPREb") -> BrowseType.ALBUM
        browseId.startsWith("VL") || browseId.startsWith("PL") -> BrowseType.PLAYLIST
        else -> fallback
    }

    /** Pops one page; returns false when there was nothing to pop. */
    fun closeDetail(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        _refreshingDetails.value = _refreshingDetails.value - stack.last().browseId
        detailOpeningJobs.remove(stack.last().browseId)?.cancel()
        detailContinuationJobs.remove(stack.last().browseId)?.cancel()
        detailArtistVersionsJobs.remove(stack.last().browseId)?.cancel()
        _detailStack.value = stack.dropLast(1)
        return true
    }

    /**
     * Personalised hubs belong to the active YouTube identity. A channel switch
     * is a real invalidation event even though ordinary navigation is not.
     */
    private fun invalidatePersonalizedHubs() {
        releasesHubRequested = false
        discoverHubRequested = false
        communityPlaylistsHubRequested = false
        _releasesHub.value = UiState.Loading
        _discoverHub.value = UiState.Loading
        _communityPlaylistsHub.value = UiState.Loading
    }

    /** Called after Credential Manager + Supabase establish the Orb account. */
    fun onGoogleSignedIn() {
        // This flag gates the Orb account experience. It deliberately does not
        // imply that private YouTube Music endpoints accepted OAuth.
        _signedIn.value = true
        _account.value = OrbGoogleAuth.storedAccount()
        restorePersistedLikes()
        restoreArtistPreferences()
        reconcileOrbRatingsFromCloud()
        flushPendingYoutubeRatings()
        invalidatePersonalizedHubs()
        loadHome()
        if (Innertube.hasAccountSession) {
            loadHomeYouTubeSections(force = true)
            loadLibrary()
            loadAccount()
            loadPlaylists()
        } else {
            // Orb login is already complete. Use the local library until the
            // listener explicitly links YouTube Music compatibility later.
            publishOrbLibrary()
            refreshOpenDetailLibraryStates(useYouTube = false)
        }
    }

    /**
     * Browser login is no longer an Orb sign-in. It only attaches the legacy
     * YouTube Music session used when a private Innertube endpoint rejects
     * Google's OAuth bearer token.
     */
    fun onLegacyYoutubeLinked(
        session: YouTubeBrowserSession,
        onResult: (Result<Unit>) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching { OrbGoogleAuth.acceptLegacyYoutubeSession(session) }
                .onSuccess {
                    if (_signedIn.value) {
                        loadHomeYouTubeSections(force = true)
                        loadLibrary()
                        loadAccount()
                        loadPlaylists()
                        reconcileOrbRatingsFromCloud()
                        flushPendingYoutubeRatings()
                    }
                    onResult(Result.success(Unit))
                }
                .onFailure { onResult(Result.failure(it)) }
        }
    }

    @Deprecated("Use onLegacyYoutubeLinked; browser cookies are not Orb identity.")
    fun onSignedIn(cookie: String) =
        onLegacyYoutubeLinked(YouTubeBrowserSession(cookieHeader = cookie))

    /** Refresh account-scoped screens after the user changes YouTube channel. */
    fun onYoutubeIdentityChanged() {
        if (!_signedIn.value) return
        invalidatePersonalizedHubs()
        detailPageCache.evictAll()
        detailWarmCache.evictAll()
        artistWarmCache.evictAll()
        synchronized(artistWarmups) {
            artistWarmups.values.forEach { it.cancel() }
            artistWarmups.clear()
        }
        loadAccount()
        loadHome()
        if (Innertube.hasAccountSession) {
            loadHomeYouTubeSections(force = true)
            loadLibrary()
            loadPlaylists()
            flushPendingYoutubeRatings()
        }
        reconcileOrbRatingsFromCloud()
    }

    fun signOut() {
        // Clear UI/account state immediately; the credential-provider and
        // Supabase cleanup can complete off the main thread.
        viewModelScope.launch { OrbGoogleAuth.signOut() }
        authStore.signOut()
        Innertube.cookie = null
        Innertube.delegatedPageId = null
        Innertube.authUserIndex = 0
        Innertube.accountSignedIn = false
        _signedIn.value = false
        _account.value = null
        homeYouTubeSectionsJob?.cancel()
        homeYouTubeSectionsJob = null
        homeYouTubeSectionsLoaded = false
        _homeYouTubeSections.value = emptyList()
        if (AppSettings.useYouTubeMusicLibrary.value) {
            _library.value = UiState.Loading
        } else {
            publishOrbLibrary()
            refreshOpenDetailLibraryStates(useYouTube = false)
        }
        // Stop account-scoped sync workers before clearing the visible state.
        cloudRatingReconcileJob?.cancel()
        cloudRatingReconcileJob = null
        youtubeRatingSyncJobs.values.forEach { it.cancel() }
        youtubeRatingSyncJobs.clear()

        // Ratings and playlists belong to the account that just left; keeping
        // them would show the next signed-in user someone else's hearts.
        _likeOverrides.value = emptyMap()
        _artistPreferences.value = ArtistPreferenceStore.load("local-device")
        _playlists.value = emptyList()
        _playlistsLoaded.value = false
        _songMenu.value = null

        // Session caches are account-scoped. Never let the next Google/YouTube
        // identity inherit the previous listener's personalised pages.
        searchCache.evictAll()
        detailPageCache.evictAll()
        detailWarmCache.evictAll()
        artistWarmCache.evictAll()
        synchronized(artistWarmups) {
            artistWarmups.values.forEach { it.cancel() }
            artistWarmups.clear()
        }
        releasesHubRequested = false
        trendingHubRequested = false
        discoverHubRequested = false
        communityPlaylistsHubRequested = false
        _releasesHub.value = UiState.Loading
        _trendingHub.value = UiState.Loading
        _discoverHub.value = UiState.Loading
        _communityPlaylistsHub.value = UiState.Loading
        loadHome()
    }

    private fun Throwable.friendly(): String = when {
        message?.contains("resolve host", true) == true ||
                message?.contains("Unable to resolve", true) == true -> "No internet connection"
        message?.contains("401") == true || message?.contains("403") == true ->
            "YouTube Music rejected the request — try signing in again"
        else -> message ?: "Something went wrong"
    }
}
