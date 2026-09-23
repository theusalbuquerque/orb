package com.music.orb.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.orb.data.social.FriendNowPlaying
import com.music.orb.data.social.FriendNowPlayingChange
import com.music.orb.data.social.FollowStatsRow
import com.music.orb.data.social.ListeningActivity
import com.music.orb.data.social.ListeningActivityChange
import com.music.orb.data.social.NowPlayingReaction
import com.music.orb.data.social.NowPlayingReactionChange
import com.music.orb.data.social.NowPlayingReactionType
import com.music.orb.data.social.OrbProfile
import com.music.orb.data.social.SocialRepository
import com.music.orb.data.social.reachedListenThreshold
import com.music.orb.data.social.reachedSocialFeedThreshold
import com.music.orb.data.social.reactionSessionKey
import com.music.orb.data.YtMusicRepository
import com.music.orb.data.ArtistCreditResolver
import com.music.orb.data.Http
import com.music.orb.data.canvas.ArtistArtworkRepository
import com.music.orb.data.canvas.normalizeOrbGenres
import com.music.orb.data.model.BrowseType
import com.music.orb.data.model.SearchFilter
import com.music.orb.data.model.SearchResult
import com.music.orb.data.settings.AppSettings
import com.music.orb.data.settings.CachedListeningStats
import com.music.orb.data.settings.CachedStatsLeader
import com.music.orb.data.settings.ArtistRankingHistoryEntry
import com.music.orb.data.settings.ArtistRankingHistorySnapshot
import com.music.orb.data.settings.ArtistRankingHistoryStore
import com.music.orb.data.settings.PlaylistListeningEvent
import com.music.orb.data.settings.PlaylistListeningStore
import com.music.orb.data.settings.StatsSnapshotStore
import com.music.orb.data.stats.TrackLanguageResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

private const val STATS_TASTE_COUNT = 5
private const val HOME_TOP_ARTIST_COUNT = 10
private const val HOME_TOP_ALBUM_COUNT = 10
private const val LANGUAGE_ENRICHMENT_TRACK_LIMIT = 32
private const val LANGUAGE_ENRICHMENT_CONCURRENCY = 3

enum class UsernameSetupError {
    INVALID,
    TAKEN,
    SAVE_FAILED,
}

enum class StatsPeriod {
    WEEK,
    MONTH,
    QUARTER,
    SEMESTER,
    YEAR,
}

enum class ArtistKind { PERSON, BAND, UNKNOWN }

enum class ArtistRankMovement { NEW, PEAK, UP, DOWN, SAME, RETURNED }


data class StatsLeader(
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    /** Stable catalogue id when the leader represents a concrete collection. */
    val sourceId: String? = null,
    val listenedMs: Long = 0L,
    val plays: Int = 0,
    val kind: ArtistKind = ArtistKind.UNKNOWN,
)

data class ArtistRankEntry(
    val leader: StatsLeader,
    val position: Int,
    val previousPosition: Int? = null,
    val peakPosition: Int? = null,
    val movement: ArtistRankMovement = ArtistRankMovement.SAME,
    val movementAmount: Int = 0,
    val movementSinceMs: Long = 0L,
) {
    val stableKey: String = leader.title.normalizeArtistMatchKey()
}

data class ListeningStats(
    val totalListenedMs: Long = 0L,
    val trackCount: Int = 0,
    val uniqueTracks: Int = 0,
    val uniqueArtists: Int = 0,
    val uniqueAlbums: Int = 0,
    val activeDays: Int = 0,
    val peakDate: LocalDate? = null,
    val peakDayOfWeek: Int? = null,
    val peakHour: Int? = null,
    val averageTrackMs: Long = 0L,
    val comparisonPercent: Int? = null,
    val topGenre: String? = null,
    val topGenres: List<String> = emptyList(),
    val topLanguage: String? = null,
    val topLanguages: List<String> = emptyList(),
    val newFollowersCount: Int = 0,
    val topArtist: StatsLeader? = null,
    val topSong: StatsLeader? = null,
    val topAlbum: StatsLeader? = null,
    val topPlaylist: StatsLeader? = null,
    val rhythmSeries: List<Int> = emptyList(),
    val previousRhythmSeries: List<Int> = emptyList(),
)


data class StatsShareSnapshot(
    val period: StatsPeriod,
    val stats: ListeningStats,
    val topArtists: List<StatsLeader>,
    val topAlbums: List<StatsLeader>,
    val topSongs: List<StatsLeader>,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
)


sealed interface StatsNotification {
    val stableKey: String
    val happenedAt: String
}

data class ReactionNotification(
    val reaction: NowPlayingReaction,
    val reactor: OrbProfile? = null,
    val activity: ListeningActivity? = null,
) : StatsNotification {
    override val stableKey: String = "reaction:${reaction.sessionKey()}|${reaction.reactorId}"
    override val happenedAt: String = reaction.reactedAt
}

data class FollowNotification(
    val follow: FollowStatsRow,
    val follower: OrbProfile? = null,
) : StatsNotification {
    override val stableKey: String = "follow:${follow.followerId}:${follow.createdAt}"
    override val happenedAt: String = follow.createdAt
}

/**
 * Session-retained state for the Friends tab.
 *
 * All database work stays in SocialRepository. The ViewModel only coordinates
 * cached UI state, optimistic follow actions, refresh and Realtime lifecycle.
 */
class FriendsViewModel : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults = MutableStateFlow<List<OrbProfile>>(emptyList())
    val searchResults: StateFlow<List<OrbProfile>> = _searchResults.asStateFlow()

    private val _userSearchOpen = MutableStateFlow(false)
    val userSearchOpen: StateFlow<Boolean> = _userSearchOpen.asStateFlow()

    private val _recentSearchProfiles = MutableStateFlow<List<OrbProfile>>(emptyList())
    val recentSearchProfiles: StateFlow<List<OrbProfile>> = _recentSearchProfiles.asStateFlow()

    private val _recommendedProfiles = MutableStateFlow<List<OrbProfile>>(emptyList())
    val recommendedProfiles: StateFlow<List<OrbProfile>> = _recommendedProfiles.asStateFlow()

    private val _recommendationsLoading = MutableStateFlow(false)
    val recommendationsLoading: StateFlow<Boolean> = _recommendationsLoading.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _myProfile = MutableStateFlow<OrbProfile?>(null)
    val myProfile: StateFlow<OrbProfile?> = _myProfile.asStateFlow()

    private val _profileChecked = MutableStateFlow(false)
    val profileChecked: StateFlow<Boolean> = _profileChecked.asStateFlow()

    private val _usernameDraft = MutableStateFlow("")
    val usernameDraft: StateFlow<String> = _usernameDraft.asStateFlow()

    private val _usernameSaving = MutableStateFlow(false)
    val usernameSaving: StateFlow<Boolean> = _usernameSaving.asStateFlow()

    private val _usernameError = MutableStateFlow<UsernameSetupError?>(null)
    val usernameError: StateFlow<UsernameSetupError?> = _usernameError.asStateFlow()

    private val _following = MutableStateFlow<List<OrbProfile>>(emptyList())
    val following: StateFlow<List<OrbProfile>> = _following.asStateFlow()

    private val _followingIds = MutableStateFlow<Set<String>>(emptySet())
    val followingIds: StateFlow<Set<String>> = _followingIds.asStateFlow()

    private val _nowPlaying = MutableStateFlow<Map<String, FriendNowPlaying>>(emptyMap())
    val nowPlaying: StateFlow<Map<String, FriendNowPlaying>> = _nowPlaying.asStateFlow()

    private val _socialFeed = MutableStateFlow<List<ListeningActivity>>(emptyList())
    val socialFeed: StateFlow<List<ListeningActivity>> = _socialFeed.asStateFlow()

    private val _newSocialFeedSessions = MutableStateFlow<Set<String>>(emptySet())
    val newSocialFeedSessions: StateFlow<Set<String>> = _newSocialFeedSessions.asStateFlow()

    private val _notificationsOpen = MutableStateFlow(false)
    val notificationsOpen: StateFlow<Boolean> = _notificationsOpen.asStateFlow()

    private val _statsNotifications = MutableStateFlow<List<StatsNotification>>(emptyList())
    val statsNotifications: StateFlow<List<StatsNotification>> = _statsNotifications.asStateFlow()

    private val _notificationUnreadCount = MutableStateFlow(0)
    val notificationUnreadCount: StateFlow<Int> = _notificationUnreadCount.asStateFlow()

    private val _nowPlayingReactions = MutableStateFlow<Map<String, List<NowPlayingReaction>>>(emptyMap())
    val nowPlayingReactions: StateFlow<Map<String, List<NowPlayingReaction>>> =
        _nowPlayingReactions.asStateFlow()

    private val _newNowPlayingSessions = MutableStateFlow<Set<String>>(emptySet())
    val newNowPlayingSessions: StateFlow<Set<String>> = _newNowPlayingSessions.asStateFlow()

    private val _reactionBusySessions = MutableStateFlow<Set<String>>(emptySet())
    val reactionBusySessions: StateFlow<Set<String>> = _reactionBusySessions.asStateFlow()

    private val _reactionErrorToken = MutableStateFlow(0)
    val reactionErrorToken: StateFlow<Int> = _reactionErrorToken.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _busyUsers = MutableStateFlow<Set<String>>(emptySet())
    val busyUsers: StateFlow<Set<String>> = _busyUsers.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    private val _followErrorToken = MutableStateFlow(0)
    val followErrorToken: StateFlow<Int> = _followErrorToken.asStateFlow()

    private val _statsPeriod = MutableStateFlow(StatsPeriod.WEEK)
    val statsPeriod: StateFlow<StatsPeriod> = _statsPeriod.asStateFlow()

    private val _listeningStats = MutableStateFlow(ListeningStats())
    val listeningStats: StateFlow<ListeningStats> = _listeningStats.asStateFlow()

    // Home uses the longest built-in Stats window so this shelf represents the
    // listener's established taste rather than whichever Stats filter happened
    // to be selected last. It is populated asynchronously and never blocks Home.
    private val _homeTopArtists = MutableStateFlow<List<StatsLeader>>(emptyList())
    val homeTopArtists: StateFlow<List<StatsLeader>> = _homeTopArtists.asStateFlow()

    private val _homeTopAlbums = MutableStateFlow<List<StatsLeader>>(emptyList())
    val homeTopAlbums: StateFlow<List<StatsLeader>> = _homeTopAlbums.asStateFlow()

    // Albums Home waits for this once so favorites/new/suggestions publish as
    // one first frame instead of inserting the favorites shelf later.
    private val _homeStatsReady = MutableStateFlow(false)
    val homeStatsReady: StateFlow<Boolean> = _homeStatsReady.asStateFlow()

    private val _statsLoading = MutableStateFlow(false)
    val statsLoading: StateFlow<Boolean> = _statsLoading.asStateFlow()

    private val _statsLoadFailed = MutableStateFlow(false)
    val statsLoadFailed: StateFlow<Boolean> = _statsLoadFailed.asStateFlow()

    private val _artistRanking = MutableStateFlow<List<ArtistRankEntry>>(emptyList())
    val artistRanking: StateFlow<List<ArtistRankEntry>> = _artistRanking.asStateFlow()

    private val _artistRankingOpen = MutableStateFlow(false)
    val artistRankingOpen: StateFlow<Boolean> = _artistRankingOpen.asStateFlow()

    private val _artistRankingLoading = MutableStateFlow(false)
    val artistRankingLoading: StateFlow<Boolean> = _artistRankingLoading.asStateFlow()

    private var searchJob: Job? = null
    private var recommendationsJob: Job? = null
    private var loadJob: Job? = null
    private var realtimeJob: Job? = null
    private var activityRealtimeJob: Job? = null
    private var statsRealtimeJob: Job? = null
    private var reactionRealtimeJob: Job? = null
    private var notificationRealtimeJob: Job? = null
    private var socialFeedPollJob: Job? = null
    private var socialFeedPushSyncJob: Job? = null
    private var pruneJob: Job? = null
    private var statsJob: Job? = null
    private var statsPeriodSwitchJob: Job? = null
    private var statsArtworkJob: Job? = null
    private var statsAuxiliaryJob: Job? = null
    private var statsLanguageJob: Job? = null
    private var statsArtistPrefetchJob: Job? = null
    private var statsCreditResolveJob: Job? = null
    private var artistRankingJob: Job? = null
    private var artistRankingRequestId = 0L
    private var activeUserId: String? = null
    private var loadedForAccount = false
    private var statsLoadedForAccount = false
    private var activityCache: List<ListeningActivity> = emptyList()
    private var playlistActivityCache: List<PlaylistListeningEvent> = emptyList()
    private val cachedStatsByPeriod = mutableMapOf<StatsPeriod, ListeningStats>()
    private val statsArtworkCache = mutableMapOf<String, String?>()
    private val statsArtistKindCache = mutableMapOf<String, ArtistKind>()
    private val rankingSnapshots = mutableMapOf<StatsPeriod, List<ArtistRankEntry>>()
    private val socialProfileCache = mutableMapOf<String, OrbProfile>()
    private var visible = false

    init {
        viewModelScope.launch {
            SocialRepository.myProfileState.collect { profile ->
                _myProfile.value = profile
            }
        }
    }

    fun ensureLoaded() {
        val current = SocialRepository.currentUserId()
        if (current != activeUserId) resetForAccount(current)
        if (!loadedForAccount && loadJob?.isActive != true) {
            loadJob = viewModelScope.launch { loadInitial() }
        }
    }

    /** Loads only the listening data Home needs, without turning the social tab visible. */
    fun ensureHomeStatsLoaded() {
        val current = SocialRepository.currentUserId()
        if (current != activeUserId) resetForAccount(current)
        ensureStatsLoaded()
    }

    fun onVisible() {
        visible = true
        ensureLoaded()
        ensureStatsLoaded()
        refreshReactionNotifications()
        restartRealtime()
        restartOwnStatsRealtime()
        restartNotificationRealtime()
        if (pruneJob?.isActive != true) {
            pruneJob = viewModelScope.launch {
                while (isActive) {
                    delay(STALE_PRUNE_INTERVAL_MS)
                    pruneSocialFeed()
                }
            }
        }
    }

    fun onHidden() {
        visible = false
        realtimeJob?.cancel()
        realtimeJob = null
        activityRealtimeJob?.cancel()
        activityRealtimeJob = null
        statsRealtimeJob?.cancel()
        statsRealtimeJob = null
        reactionRealtimeJob?.cancel()
        reactionRealtimeJob = null
        notificationRealtimeJob?.cancel()
        notificationRealtimeJob = null
        socialFeedPollJob?.cancel()
        socialFeedPollJob = null
        socialFeedPushSyncJob?.cancel()
        socialFeedPushSyncJob = null
        pruneJob?.cancel()
        pruneJob = null
        // Username may be created from Account & Integrations while this
        // ViewModel stays alive. Recheck the profile gate next time Friends opens.
        if (_myProfile.value?.username.isNullOrBlank()) loadedForAccount = false
    }

    private fun ensureStatsLoaded() {
        if (statsLoadedForAccount) {
            _homeStatsReady.value = true
            return
        }
        if (currentUserIdMissing()) {
            _homeStatsReady.value = true
            return
        }
        if (statsJob?.isActive == true) return
        val hadCachedSnapshot = hydrateStatsSnapshotFromDisk()
        statsJob = viewModelScope.launch {
            // Cached cards stay visible while Supabase refreshes in the background.
            // Only show the blocking Stats loader on a true first visit.
            _statsLoading.value = !hadCachedSnapshot
            runCatching { loadStatsSnapshot() }
                .onSuccess {
                    statsLoadedForAccount = true
                    _statsLoadFailed.value = false
                }
                .onFailure { _statsLoadFailed.value = !hadCachedSnapshot }
            _statsLoading.value = false
            // Ready means the one Home-specific attempt completed, even if it
            // produced no favorites. That prevents Albums from waiting forever
            // on an empty/new account or a transient stats failure.
            _homeStatsReady.value = true
        }
    }

    private fun hydrateStatsSnapshotFromDisk(): Boolean {
        val userId = activeUserId ?: SocialRepository.currentUserId() ?: return false
        if (cachedStatsByPeriod.isEmpty()) {
            StatsSnapshotStore.load(userId).forEach { (periodName, cached) ->
                val period = runCatching { StatsPeriod.valueOf(periodName) }.getOrNull() ?: return@forEach
                cachedStatsByPeriod[period] = cached.toListeningStats()
            }
        }

        // Home's Artists shelf should not wait for the Supabase history query on
        // every cold start. The ranking page already persists a durable YEAR
        // snapshot with plays/time, so reuse it as an immediate first paint and
        // replace it with the freshly aggregated ranking when Stats finishes.
        if (_homeTopArtists.value.isEmpty()) {
            val cachedYearArtists = ArtistRankingHistoryStore.load(userId)
                .periods[StatsPeriod.YEAR.name]
                .orEmpty()
                .values
                .asSequence()
                .filter { it.active }
                .sortedBy { it.position }
                .take(HOME_TOP_ARTIST_COUNT)
                .map { entry ->
                    StatsLeader(
                        title = entry.artistName,
                        artworkUrl = ArtistArtworkRepository.cached(entry.artistName),
                        listenedMs = entry.listenedMs,
                        plays = entry.plays,
                    )
                }
                .toList()
            if (cachedYearArtists.isNotEmpty()) _homeTopArtists.value = cachedYearArtists
        }

        val cached = cachedStatsByPeriod[_statsPeriod.value] ?: return _homeTopArtists.value.isNotEmpty()
        _listeningStats.value = cached
        return true
    }

    private fun persistStatsSnapshot(period: StatsPeriod, snapshot: ListeningStats) {
        val userId = activeUserId ?: SocialRepository.currentUserId() ?: return
        cachedStatsByPeriod[period] = snapshot
        StatsSnapshotStore.save(userId, period.name, snapshot.toCachedStats())
    }

    fun setStatsPeriod(period: StatsPeriod) {
        if (_statsPeriod.value == period) return

        // Match Home's filter feel: commit the selected pill and the already
        // persisted snapshot synchronously, before any expensive aggregation.
        _statsPeriod.value = period
        cachedStatsByPeriod[period]?.let { _listeningStats.value = it }

        if (_artistRankingOpen.value) {
            // Ranking positions are a page-open snapshot. Switching the period
            // only reveals the snapshot captured when this ranking page opened;
            // it must never silently recalculate positions in the background.
            _artistRanking.value = rankingSnapshots[period].orEmpty()
        }

        statsPeriodSwitchJob?.cancel()
        if (activityCache.isEmpty() && !statsLoadedForAccount) {
            if (cachedStatsByPeriod[period] == null) {
                _listeningStats.value = ListeningStats()
            }
            return
        }

        val accountId = activeUserId
        val activity = activityCache
        val playlistActivity = playlistActivityCache
        val previous = cachedStatsByPeriod[period] ?: _listeningStats.value
        val now = ZonedDateTime.now()

        statsPeriodSwitchJob = viewModelScope.launch {
            val fresh = withContext(Dispatchers.Default) {
                aggregateListeningStats(
                    activity = activity,
                    playlistActivity = playlistActivity,
                    period = period,
                    now = now,
                )
            }
            if (_statsPeriod.value != period || activeUserId != accountId) return@launch

            val merged = fresh.copy(
                topGenre = fresh.topGenre ?: previous.topGenre,
                topGenres = fresh.topGenres.ifEmpty { previous.topGenres },
                topLanguage = fresh.topLanguage ?: previous.topLanguage,
                topLanguages = fresh.topLanguages.ifEmpty { previous.topLanguages },
                newFollowersCount = previous.newFollowersCount,
                topArtist = fresh.topArtist.preserveArtistPresentationFrom(previous.topArtist),
                topSong = fresh.topSong.preservePresentationFrom(previous.topSong),
                topAlbum = fresh.topAlbum.preservePresentationFrom(previous.topAlbum),
                topPlaylist = fresh.topPlaylist.preservePresentationFrom(previous.topPlaylist),
            )

            _listeningStats.value = merged
            persistStatsSnapshot(period, merged)
            restartStatsEnrichment(period, now)
        }
    }

    suspend fun statsShareSnapshot(
        period: StatsPeriod,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): StatsShareSnapshot {
        val window = statsWindow(period, now)
        val currentRows = activityCache
            .asSequence()
            .filter { it.reachedListenThreshold() }
            .filter { row -> row.startedInstant()?.let { it >= window.currentStart && it < window.currentEnd } == true }
            .toList()

        val rawStats = if (activityCache.isNotEmpty() || statsLoadedForAccount) {
            aggregateListeningStats(
                activity = activityCache,
                playlistActivity = playlistActivityCache,
                period = period,
                now = now,
            )
        } else {
            cachedStatsByPeriod[period] ?: ListeningStats()
        }
        val cached = cachedStatsByPeriod[period]
        val stats = rawStats.copy(
            topGenre = rawStats.topGenre ?: cached?.topGenre,
            topGenres = rawStats.topGenres.ifEmpty { cached?.topGenres.orEmpty() },
            topLanguage = rawStats.topLanguage ?: cached?.topLanguage,
            topLanguages = rawStats.topLanguages.ifEmpty { cached?.topLanguages.orEmpty() },
            newFollowersCount = cached?.newFollowersCount ?: rawStats.newFollowersCount,
            topArtist = rawStats.topArtist.preserveArtistPresentationFrom(cached?.topArtist),
            topSong = rawStats.topSong.preservePresentationFrom(cached?.topSong),
            topAlbum = rawStats.topAlbum.preservePresentationFrom(cached?.topAlbum),
            topPlaylist = rawStats.topPlaylist.preservePresentationFrom(cached?.topPlaylist),
        )

        val topArtists = if (currentRows.isNotEmpty()) {
            artistLeadersForPeriod(period, now).take(STATS_SHARE_TOP_COUNT)
        } else {
            listOfNotNull(stats.topArtist).take(STATS_SHARE_TOP_COUNT)
        }

        // Stories must use the exact same taste-ranking rules as the Stats page.
        // In particular, genres are resolved from the same eight ranked artists
        // used by Stats (not merely the five artists rendered in the Story), and
        // both genre/language rankings expose the same five-entry canonical list.
        val tasteArtistLeaders = if (currentRows.isNotEmpty()) {
            artistLeadersForPeriod(period, now).take(TOP_GENRE_ARTIST_LIMIT)
        } else {
            emptyList()
        }
        val shareGenres = if (tasteArtistLeaders.isNotEmpty()) {
            resolveRankedGenres(
                leaders = tasteArtistLeaders,
                limit = STATS_TASTE_COUNT,
            )
        } else {
            stats.topGenres
                .ifEmpty { listOfNotNull(stats.topGenre) }
                .take(STATS_TASTE_COUNT)
        }
        val shareLanguages = if (currentRows.isNotEmpty()) {
            rankedListeningLanguages(currentRows, STATS_TASTE_COUNT)
        } else {
            stats.topLanguages
                .ifEmpty { listOfNotNull(stats.topLanguage) }
                .take(STATS_TASTE_COUNT)
        }
        val shareStats = stats.copy(
            topGenre = shareGenres.firstOrNull() ?: stats.topGenre,
            topGenres = shareGenres.ifEmpty { stats.topGenres },
            topLanguage = shareLanguages.firstOrNull() ?: stats.topLanguage,
            topLanguages = shareLanguages.ifEmpty { stats.topLanguages },
        )

        val topSongs = rankListeningTracks(currentRows, limit = STATS_SHARE_SONG_COUNT)
            .map { ranked ->
                StatsLeader(
                    title = ranked.title,
                    subtitle = ranked.subtitle,
                    artworkUrl = ranked.artworkUrl,
                    listenedMs = ranked.listenedMs,
                    plays = ranked.plays,
                )
            }
            .ifEmpty { listOfNotNull(stats.topSong) }

        val topAlbums = rankListeningAlbums(currentRows, limit = STATS_SHARE_TOP_COUNT)
            .map { ranked ->
                StatsLeader(
                    title = ranked.title,
                    subtitle = ranked.subtitle,
                    artworkUrl = ranked.artworkUrl,
                    listenedMs = ranked.listenedMs,
                    plays = ranked.plays,
                )
            }
            .ifEmpty { listOfNotNull(stats.topAlbum) }

        return StatsShareSnapshot(
            period = period,
            stats = shareStats,
            topArtists = topArtists,
            topAlbums = topAlbums,
            topSongs = topSongs,
            periodStart = window.currentStart.atZone(now.zone).toLocalDate(),
            periodEnd = window.currentEnd.atZone(now.zone).toLocalDate(),
        )
    }

    private fun currentUserIdMissing(): Boolean = SocialRepository.currentUserId() == null

    private fun isArtistRankingRequestCurrent(requestId: Long): Boolean =
        requestId == artistRankingRequestId && _artistRankingOpen.value

    fun openNotifications() {
        _notificationsOpen.value = true
        _userSearchOpen.value = false
        _artistRankingOpen.value = false
        SocialRepository.currentUserId()?.let { AppSettings.markStatsNotificationsSeen(it) }
        _notificationUnreadCount.value = 0
        refreshReactionNotifications()
    }

    fun closeNotifications() {
        _notificationsOpen.value = false
    }

    fun openUserSearch() {
        _notificationsOpen.value = false
        _userSearchOpen.value = true
        refreshRecommendedProfilesIfNeeded()
    }

    private fun refreshRecommendedProfilesIfNeeded() {
        if (_followingIds.value.isNotEmpty()) return
        if (_recommendedProfiles.value.size >= RECOMMENDED_PROFILE_COUNT) return
        if (recommendationsJob?.isActive == true) return

        recommendationsJob = viewModelScope.launch {
            _recommendationsLoading.value = true
            _recommendedProfiles.value = runCatching {
                SocialRepository.recommendedProfilesForEmptyNetwork(RECOMMENDED_PROFILE_COUNT)
            }.getOrDefault(emptyList())
            _recommendationsLoading.value = false
        }
    }

    fun closeUserSearch() {
        _userSearchOpen.value = false
        _query.value = ""
        _searchResults.value = emptyList()
        _searching.value = false
        searchJob?.cancel()
    }

    fun openArtistRanking() {
        _notificationsOpen.value = false
        _artistRankingOpen.value = true

        // Always begin from the last committed ranking, including after an app
        // restart. Positions AND play counts stay frozen at that snapshot until
        // the fresh ranking has fully loaded and its 2-second reveal delay ends.
        val accountId = activeUserId ?: SocialRepository.currentUserId()
        if (rankingSnapshots.isEmpty() && accountId != null) {
            val durableHistory = ArtistRankingHistoryStore.load(accountId)
            if (durableHistory.seeded) {
                rankingSnapshots.putAll(snapshotsFromHistory(durableHistory))
            }
        }
        _artistRanking.value = rankingSnapshots[_statsPeriod.value].orEmpty()

        // Opening this page is the only event that is allowed to advance the
        // durable ranking snapshot.
        refreshArtistRanking()
    }

    fun closeArtistRanking() {
        _artistRankingOpen.value = false
        artistRankingRequestId += 1
        artistRankingJob?.cancel()
        artistRankingJob = null
        _artistRankingLoading.value = false
    }

    /** Loads a fresh ranking once per page opening, then reveals it 2s after preparation completes. */
    private fun refreshArtistRanking() {
        if (!_artistRankingOpen.value || currentUserIdMissing()) return
        artistRankingJob?.cancel()
        val requestId = ++artistRankingRequestId
        artistRankingJob = viewModelScope.launch {
            _artistRankingLoading.value = true
            try {
                // Show the durable previous order immediately, but always
                // reconcile listening history before committing this opening's
                // new positions. Otherwise a long-lived Stats screen keeps
                // ranking from an old activityCache and every row appears "=".
                statsJob?.takeIf { it.isActive }?.join()
                runCatching { loadStatsSnapshot() }
                if (!isArtistRankingRequestCurrent(requestId)) return@launch

                val accountId = activeUserId ?: SocialRepository.currentUserId() ?: return@launch
                val now = ZonedDateTime.now()
                val nowMs = System.currentTimeMillis()
                val leadersByPeriod = StatsPeriod.values().associateWith { period ->
                    artistLeadersForPeriod(period, now)
                }
                var history = ArtistRankingHistoryStore.load(accountId)
                history = migrateSeenArtistCredits(history)

                // One-time migration for installs that predate durable ranking
                // history. Existing artists are seeded as already known rather
                // than incorrectly showing "New" simply because the app updated.
                if (!history.seeded) {
                    history = seedArtistRankingHistory(history, leadersByPeriod, nowMs)
                    ArtistRankingHistoryStore.save(accountId, history)
                    val seededSnapshots = snapshotsFromHistory(history)
                    rankingSnapshots.clear()
                    rankingSnapshots.putAll(seededSnapshots)
                    _artistRanking.value = seededSnapshots[_statsPeriod.value].orEmpty()
                } else {
                    // On a fresh process there is no in-memory list to animate
                    // from, so reconstruct the last positions from durable state.
                    if (rankingSnapshots.isEmpty()) {
                        val previousSnapshots = snapshotsFromHistory(history)
                        rankingSnapshots.putAll(previousSnapshots)
                        _artistRanking.value = previousSnapshots[_statsPeriod.value].orEmpty()
                    }

                    // Compute the complete next snapshot first. Nothing visible
                    // changes yet: the page continues to show the remembered
                    // positions and play counts from the previous opening.
                    val committed = commitArtistRankingHistory(history, leadersByPeriod, nowMs)
                    history = committed.first
                    val nextSnapshots = committed.second

                    if (!isArtistRankingRequestCurrent(requestId)) return@launch

                    val hasRememberedRanking = _artistRanking.value.isNotEmpty()
                    if (hasRememberedRanking) {
                        // Fresh ranking data is fully prepared now. Keep the
                        // previous snapshot visible for exactly two more seconds
                        // before the update is revealed.
                        _artistRankingLoading.value = false
                        delay(RANKING_POST_LOAD_REVEAL_DELAY_MS)
                    }
                    if (!isArtistRankingRequestCurrent(requestId)) return@launch

                    // Persist and publish as one visible update: position, play
                    // count and movement indicator change together.
                    ArtistRankingHistoryStore.save(accountId, history)
                    rankingSnapshots.clear()
                    rankingSnapshots.putAll(nextSnapshots)
                    _artistRanking.value = nextSnapshots[_statsPeriod.value].orEmpty()
                }

                val uniqueEntries = rankingSnapshots.values
                    .flatten()
                    .distinctBy { it.stableKey }
                enrichArtistRankingArtwork(uniqueEntries, requestId)
            } finally {
                if (isArtistRankingRequestCurrent(requestId)) {
                    _artistRankingLoading.value = false
                    artistRankingJob = null
                }
            }
        }
    }

    fun onUsernameDraftChange(value: String) {
        _usernameDraft.value = value
            .trimStart()
            .removePrefix("@")
            .lowercase()
            .take(30)
        _usernameError.value = null
    }

    fun saveUsername() {
        if (_usernameSaving.value) return
        val candidate = _usernameDraft.value.trim().removePrefix("@").lowercase()
        if (!USERNAME.matches(candidate) || candidate.length !in 3..30) {
            _usernameError.value = UsernameSetupError.INVALID
            return
        }

        viewModelScope.launch {
            _usernameSaving.value = true
            _usernameError.value = null
            val result = SocialRepository.setUsername(candidate)
            result.onSuccess { normalized ->
                val current = _myProfile.value
                _myProfile.value = current?.copy(username = normalized)
                    ?: runCatching { SocialRepository.myProfile() }.getOrNull()
                _usernameDraft.value = ""
                loadedForAccount = true
                runCatching { loadFollowingSnapshot() }
                    .onFailure { _loadFailed.value = true }
            }.onFailure { error ->
                _usernameError.value = if (error.looksLikeUniqueConflict()) {
                    UsernameSetupError.TAKEN
                } else {
                    UsernameSetupError.SAVE_FAILED
                }
            }
            _usernameSaving.value = false
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
        searchJob?.cancel()
        if (value.trim().removePrefix("@").isBlank()) {
            _searchResults.value = emptyList()
            _searching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            _searching.value = true
            delay(SEARCH_DEBOUNCE_MS)
            val result = runCatching { SocialRepository.searchProfiles(value) }
            val profiles = result.getOrDefault(emptyList())
            _searchResults.value = profiles
            rememberSearchProfiles(profiles)
            _searching.value = false
        }
    }

    /** Exact IME-search first, then fall back to the incremental prefix list. */
    fun submitSearch() {
        val value = _query.value
        if (value.trim().removePrefix("@").isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searching.value = true
            val me = SocialRepository.currentUserId()
            val exact = runCatching { SocialRepository.profileByUsername(value) }.getOrNull()
            val profiles = if (me != null && exact != null && exact.id != me) {
                listOf(exact)
            } else {
                runCatching { SocialRepository.searchProfiles(value) }.getOrDefault(emptyList())
            }
            _searchResults.value = profiles
            rememberSearchProfiles(profiles)
            _searching.value = false
        }
    }

    fun refreshAll() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val refreshedProfile = runCatching { SocialRepository.myProfile() }
            refreshedProfile.onSuccess { profile ->
                _myProfile.value = profile
                _profileChecked.value = true
                if (profile?.username.isNullOrBlank()) {
                    _following.value = emptyList()
                    _followingIds.value = emptySet()
                    _nowPlaying.value = emptyMap()
                    _socialFeed.value = emptyList()
                    _newSocialFeedSessions.value = emptySet()
                    _nowPlayingReactions.value = emptyMap()
                    _newNowPlayingSessions.value = emptySet()
                    _reactionBusySessions.value = emptySet()
                    restartRealtime()
                } else {
                    runCatching { loadFollowingSnapshot() }
                        .onFailure { _loadFailed.value = true }
                }
            }.onFailure {
                _loadFailed.value = true
            }

            val activeQuery = _query.value
            if (activeQuery.trim().removePrefix("@").isNotBlank()) {
                runCatching { SocialRepository.searchProfiles(activeQuery) }
                    .onSuccess {
                        _searchResults.value = it
                        rememberSearchProfiles(it)
                    }
            }

            runCatching { loadStatsSnapshot() }
                .onSuccess {
                    statsLoadedForAccount = true
                    _statsLoadFailed.value = false
                }
                .onFailure { _statsLoadFailed.value = true }

            // Stats data may refresh here, but ranking positions intentionally
            // remain frozen until the ranking page is closed and opened again.
            refreshReactionNotifications()

            _refreshing.value = false
        }
    }

    fun toggleFollow(profile: OrbProfile) {
        if (profile.id in _busyUsers.value) return
        rememberSearchProfiles(listOf(profile))
        val wasFollowing = profile.id in _followingIds.value
        val previousFollowing = _following.value
        val previousIds = _followingIds.value
        val previousNowPlaying = _nowPlaying.value
        val previousSocialFeed = _socialFeed.value
        val previousReactions = _nowPlayingReactions.value
        val previousNewSessions = _newNowPlayingSessions.value
        val previousNewFeedSessions = _newSocialFeedSessions.value

        // Optimistic state: the UI reacts immediately. RLS/database remain the
        // authority, and any failure restores the exact previous snapshot.
        if (wasFollowing) {
            _followingIds.value = previousIds - profile.id
            _following.value = previousFollowing.filterNot { it.id == profile.id }
            _nowPlaying.value = previousNowPlaying - profile.id
            _socialFeed.value = previousSocialFeed.filterNot { it.userId == profile.id }
            _nowPlayingReactions.value = previousReactions.filterValues { reactions ->
                reactions.none { it.listenerId == profile.id }
            }
            _newNowPlayingSessions.value = previousNewSessions.filterNotTo(linkedSetOf()) { key ->
                key.startsWith("${profile.id}|")
            }
            _newSocialFeedSessions.value = previousNewFeedSessions.filterNotTo(linkedSetOf()) { key ->
                key.startsWith("${profile.id}|")
            }
        } else {
            _followingIds.value = previousIds + profile.id
            _following.value = if (previousFollowing.any { it.id == profile.id }) {
                previousFollowing
            } else {
                previousFollowing + profile
            }
        }
        restartRealtime()

        viewModelScope.launch {
            _busyUsers.value = _busyUsers.value + profile.id
            val result = runCatching {
                if (wasFollowing) SocialRepository.unfollow(profile.id)
                else SocialRepository.follow(profile.id)
            }

            if (result.isFailure) {
                _following.value = previousFollowing
                _followingIds.value = previousIds
                _nowPlaying.value = previousNowPlaying
                _socialFeed.value = previousSocialFeed
                _nowPlayingReactions.value = previousReactions
                _newNowPlayingSessions.value = previousNewSessions
                _newSocialFeedSessions.value = previousNewFeedSessions
                _followErrorToken.value += 1
                restartRealtime()
            } else if (!wasFollowing) {
                socialProfileCache[profile.id] = profile
                runCatching { SocialRepository.nowPlayingForUsers(listOf(profile.id)) }
                    .onSuccess { live ->
                        live[profile.id]?.takeIf { it.isFresh() }?.let { item ->
                            if (profile.id in _followingIds.value) {
                                _nowPlaying.value = _nowPlaying.value + (profile.id to item)
                            }
                        }
                    }
                runCatching {
                    SocialRepository.recentListeningActivityForUsers(
                        userIds = listOf(profile.id),
                        since = Instant.now().minusSeconds(SOCIAL_FEED_RETENTION_SECONDS),
                    )
                }.onSuccess { rows ->
                    if (profile.id in _followingIds.value) {
                        val existingKeys = _socialFeed.value.mapTo(hashSetOf()) { it.reactionSessionKey() }
                        _socialFeed.value = (_socialFeed.value + rows)
                            .distinctBy { it.reactionSessionKey() }
                            .sortedByDescending { it.startedInstant() ?: Instant.EPOCH }
                        _newSocialFeedSessions.value = _newSocialFeedSessions.value +
                            rows.mapNotNull { row -> row.reactionSessionKey().takeIf { it !in existingKeys } }
                        runCatching { SocialRepository.reactionsForListeningActivity(rows) }
                            .onSuccess(::mergeReactionSnapshot)
                    }
                }
            }
            _busyUsers.value = _busyUsers.value - profile.id
        }
    }

    fun reactToNowPlaying(
        session: FriendNowPlaying,
        reaction: NowPlayingReactionType,
    ) {
        val reactorId = SocialRepository.currentUserId() ?: return
        val sessionKey = session.reactionSessionKey()
        if (reactorId == session.userId || sessionKey in _reactionBusySessions.value) return
        if (_socialFeed.value.none { it.reactionSessionKey() == sessionKey }) return

        val previous = _nowPlayingReactions.value
        val previousOwn = previous[sessionKey]
            ?.firstOrNull { it.reactorId == reactorId }
            ?.type()
        // Pressing the already-selected emoji removes it; choosing another one
        // changes the single reaction attached to this listening session.
        val next = reaction.takeUnless { it == previousOwn }
        _nowPlayingReactions.value = replaceOwnReaction(
            source = previous,
            session = session,
            reactorId = reactorId,
            reaction = next,
        )
        _reactionBusySessions.value = _reactionBusySessions.value + sessionKey

        viewModelScope.launch {
            val result = runCatching {
                SocialRepository.setNowPlayingReaction(session, next)
            }
            val sessionStillVisible = _socialFeed.value.any { it.reactionSessionKey() == sessionKey }
            if (result.isFailure && sessionStillVisible) {
                _nowPlayingReactions.value = replaceOwnReaction(
                    source = _nowPlayingReactions.value,
                    session = session,
                    reactorId = reactorId,
                    reaction = previousOwn,
                )
                _reactionErrorToken.value += 1
            }
            _reactionBusySessions.value = _reactionBusySessions.value - sessionKey
        }
    }

    fun consumeNowPlayingEntrance(sessionKey: String) {
        _newNowPlayingSessions.value = _newNowPlayingSessions.value - sessionKey
    }

    private fun replaceOwnReaction(
        source: Map<String, List<NowPlayingReaction>>,
        session: FriendNowPlaying,
        reactorId: String,
        reaction: NowPlayingReactionType?,
    ): Map<String, List<NowPlayingReaction>> {
        val key = session.reactionSessionKey()
        val withoutMine = source[key].orEmpty().filterNot { it.reactorId == reactorId }
        val updated = reaction?.let { type ->
            withoutMine + NowPlayingReaction(
                listenerId = session.userId,
                reactorId = reactorId,
                videoId = session.videoId,
                activityStartedAt = session.startedAt,
                reaction = type.wireValue,
                reactedAt = Instant.now().toString(),
            )
        } ?: withoutMine
        return if (updated.isEmpty()) source - key else source + (key to updated)
    }

    private fun rememberSearchProfiles(profiles: List<OrbProfile>) {
        if (profiles.isEmpty()) return
        val merged = profiles + _recentSearchProfiles.value
        _recentSearchProfiles.value = merged.distinctBy { it.id }.take(6)
    }

    private fun resetForAccount(userId: String?) {
        activeUserId = userId
        loadedForAccount = false
        loadJob?.cancel()
        searchJob?.cancel()
        realtimeJob?.cancel()
        activityRealtimeJob?.cancel()
        statsRealtimeJob?.cancel()
        reactionRealtimeJob?.cancel()
        notificationRealtimeJob?.cancel()
        socialFeedPollJob?.cancel()
        socialFeedPushSyncJob?.cancel()
        realtimeJob = null
        activityRealtimeJob = null
        statsRealtimeJob = null
        reactionRealtimeJob = null
        notificationRealtimeJob = null
        socialFeedPollJob = null
        socialFeedPushSyncJob = null
        pruneJob?.cancel()
        statsJob?.cancel()
        statsPeriodSwitchJob?.cancel()
        statsPeriodSwitchJob = null
        statsArtworkJob?.cancel()
        statsAuxiliaryJob?.cancel()
        statsLanguageJob?.cancel()
        statsArtistPrefetchJob?.cancel()
        statsCreditResolveJob?.cancel()
        statsAuxiliaryJob = null
        statsLanguageJob = null
        statsArtistPrefetchJob = null
        statsCreditResolveJob = null
        artistRankingRequestId += 1
        artistRankingJob?.cancel()
        artistRankingJob = null
        statsLoadedForAccount = false
        activityCache = emptyList()
        playlistActivityCache = emptyList()
        cachedStatsByPeriod.clear()
        statsArtworkCache.clear()
        statsArtistKindCache.clear()
        rankingSnapshots.clear()
        socialProfileCache.clear()
        _statsPeriod.value = StatsPeriod.WEEK
        _listeningStats.value = ListeningStats()
        _homeTopArtists.value = emptyList()
        _homeTopAlbums.value = emptyList()
        _homeStatsReady.value = false
        _statsLoading.value = false
        _statsLoadFailed.value = false
        _artistRanking.value = emptyList()
        _artistRankingOpen.value = false
        _artistRankingLoading.value = false
        _query.value = ""
        _searchResults.value = emptyList()
        _userSearchOpen.value = false
        _recentSearchProfiles.value = emptyList()
        _searching.value = false
        _myProfile.value = null
        _profileChecked.value = false
        _usernameDraft.value = ""
        _usernameSaving.value = false
        _usernameError.value = null
        _following.value = emptyList()
        _followingIds.value = emptySet()
        _recommendedProfiles.value = emptyList()
        _recommendationsLoading.value = false
        recommendationsJob?.cancel()
        recommendationsJob = null
        _nowPlaying.value = emptyMap()
        _socialFeed.value = emptyList()
        _newSocialFeedSessions.value = emptySet()
        _nowPlayingReactions.value = emptyMap()
        _notificationsOpen.value = false
        _statsNotifications.value = emptyList()
        _notificationUnreadCount.value = 0
        _newNowPlayingSessions.value = emptySet()
        _reactionBusySessions.value = emptySet()
        _reactionErrorToken.value = 0
        _busyUsers.value = emptySet()
        _loadFailed.value = false
    }

    private suspend fun loadInitial() {
        _loading.value = true
        val profileResult = runCatching { SocialRepository.myProfile() }
        profileResult.onSuccess { profile ->
            _myProfile.value = profile
            _profileChecked.value = true
            loadedForAccount = true
            if (profile?.username.isNullOrBlank()) {
                _following.value = emptyList()
                _followingIds.value = emptySet()
                _nowPlaying.value = emptyMap()
                _socialFeed.value = emptyList()
                _newSocialFeedSessions.value = emptySet()
                _nowPlayingReactions.value = emptyMap()
                _newNowPlayingSessions.value = emptySet()
                _reactionBusySessions.value = emptySet()
                _loadFailed.value = profile == null
                restartRealtime()
            } else {
                runCatching { loadFollowingSnapshot() }
                    .onFailure { _loadFailed.value = true }
            }
        }.onFailure {
            _profileChecked.value = true
            _loadFailed.value = true
        }
        _loading.value = false
    }

    private suspend fun loadFollowingSnapshot() {
        val profiles = SocialRepository.followingProfiles()
        val ids = profiles.mapTo(linkedSetOf()) { it.id }
        profiles.forEach { socialProfileCache[it.id] = it }
        val live = SocialRepository.nowPlayingForUsers(ids)
        val feed = SocialRepository.recentListeningActivityForUsers(
            userIds = ids,
            since = Instant.now().minusSeconds(SOCIAL_FEED_RETENTION_SECONDS),
        ).collapseConsecutiveSocialRepeats()
        _following.value = profiles
        _followingIds.value = ids
        if (ids.isEmpty() && _userSearchOpen.value) {
            refreshRecommendedProfilesIfNeeded()
        }
        _nowPlaying.value = live
        _socialFeed.value = feed
        _nowPlayingReactions.value = emptyMap()
        _newNowPlayingSessions.value = emptySet()
        _newSocialFeedSessions.value = emptySet()
        _loadFailed.value = false
        restartRealtime()

        // Reactions are loaded for all visible four-hour feed sessions, not just
        // the one track that still happens to be in now_playing.
        val reactions = runCatching {
            SocialRepository.reactionsForListeningActivity(feed)
        }.getOrDefault(emptyMap())
        val requestedSessions = feed.mapTo(linkedSetOf()) { it.reactionSessionKey() }
        val currentSessions = _socialFeed.value.mapTo(linkedSetOf()) { it.reactionSessionKey() }
        if (_followingIds.value == ids && currentSessions == requestedSessions) {
            mergeReactionSnapshot(reactions)
        }
    }

    private suspend fun loadStatsSnapshot() {
        val since = statsRetentionStart()
        val fetchedActivity = SocialRepository.myListeningActivitySince(since, maxRows = 20_000)
        val fetchedPlaylistActivity = PlaylistListeningStore.eventsSince(since.toEpochMilli())
        activityCache = fetchedActivity
        playlistActivityCache = fetchedPlaylistActivity

        // Publish Stats from already-known/cached artist-credit decisions first.
        // Catalogue validation for ambiguous comma/& credits is network work and
        // must never hold the entire Stats page behind it.
        val now = ZonedDateTime.now()
        refreshHomeTopArtists(now)
        refreshHomeTopAlbums(now)
        val snapshots = withContext(Dispatchers.Default) {
            StatsPeriod.values().associateWith { period ->
                aggregateListeningStats(
                    activity = fetchedActivity,
                    playlistActivity = fetchedPlaylistActivity,
                    period = period,
                    now = now,
                )
            }
        }

        val mergedSnapshots = snapshots.mapValues { (period, fresh) ->
            val previous = cachedStatsByPeriod[period]
            fresh.copy(
                topGenre = fresh.topGenre ?: previous?.topGenre,
                topGenres = fresh.topGenres.ifEmpty { previous?.topGenres.orEmpty() },
                newFollowersCount = previous?.newFollowersCount ?: fresh.newFollowersCount,
                topArtist = fresh.topArtist.preserveArtistPresentationFrom(previous?.topArtist),
                topSong = fresh.topSong.preservePresentationFrom(previous?.topSong),
                topAlbum = fresh.topAlbum.preservePresentationFrom(previous?.topAlbum),
                topPlaylist = fresh.topPlaylist.preservePresentationFrom(previous?.topPlaylist),
            )
        }
        mergedSnapshots.forEach { (period, snapshot) -> persistStatsSnapshot(period, snapshot) }
        val selectedPeriod = _statsPeriod.value
        _listeningStats.value = mergedSnapshots[selectedPeriod] ?: ListeningStats()
        restartStatsEnrichment(selectedPeriod, now)
        prefetchAllStatsArtistArtwork()

        // Resolve any still-ambiguous artist credits after the page is usable.
        // When the catalogue learns a better split, recompute silently and keep
        // the existing artwork/metadata presentation where possible.
        resolveStatsArtistCreditsInBackground(fetchedActivity)
    }

    private fun resolveStatsArtistCreditsInBackground(rows: List<ListeningActivity>) {
        statsCreditResolveJob?.cancel()
        val accountId = activeUserId
        if (rows.isEmpty()) {
            statsCreditResolveJob = null
            return
        }

        val names = rows.asSequence()
            .map { it.artist }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        statsCreditResolveJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ArtistCreditResolver.resolveAll(names)
            }
            if (activeUserId != accountId) return@launch

            val currentActivity = activityCache
            val currentPlaylistActivity = playlistActivityCache
            val now = ZonedDateTime.now()
            val refreshed = withContext(Dispatchers.Default) {
                StatsPeriod.values().associateWith { period ->
                    aggregateListeningStats(
                        activity = currentActivity,
                        playlistActivity = currentPlaylistActivity,
                        period = period,
                        now = now,
                    )
                }
            }
            if (activeUserId != accountId) return@launch

            refreshed.forEach { (period, fresh) ->
                val previous = cachedStatsByPeriod[period]
                val merged = fresh.copy(
                    topGenre = fresh.topGenre ?: previous?.topGenre,
                    topGenres = fresh.topGenres.ifEmpty { previous?.topGenres.orEmpty() },
                    topLanguage = fresh.topLanguage ?: previous?.topLanguage,
                    topLanguages = fresh.topLanguages.ifEmpty { previous?.topLanguages.orEmpty() },
                    newFollowersCount = previous?.newFollowersCount ?: fresh.newFollowersCount,
                    topArtist = fresh.topArtist.preserveArtistPresentationFrom(previous?.topArtist),
                    topSong = fresh.topSong.preservePresentationFrom(previous?.topSong),
                    topAlbum = fresh.topAlbum.preservePresentationFrom(previous?.topAlbum),
                    topPlaylist = fresh.topPlaylist.preservePresentationFrom(previous?.topPlaylist),
                )
                persistStatsSnapshot(period, merged)
            }

            val selectedPeriod = _statsPeriod.value
            cachedStatsByPeriod[selectedPeriod]?.let { _listeningStats.value = it }
            restartStatsEnrichment(selectedPeriod, now)
            prefetchAllStatsArtistArtwork()
        }
    }

    private fun restartStatsEnrichment(
        period: StatsPeriod,
        now: ZonedDateTime = ZonedDateTime.now(),
    ) {
        statsArtworkJob?.cancel()
        statsArtworkJob = viewModelScope.launch { enrichStatsArtwork() }
        statsAuxiliaryJob?.cancel()
        statsAuxiliaryJob = viewModelScope.launch {
            enrichStatsAuxiliary(period, now)
        }
        statsLanguageJob?.cancel()
        statsLanguageJob = viewModelScope.launch {
            enrichStatsLanguages(period, now)
        }
    }

    /**
     * Backfills language for the most influential old listening rows using only
     * exact-video YouTube lyrics/captions. New rows already persist language_code,
     * so this is bounded migration work rather than a permanent per-Stats cost.
     */
    private suspend fun enrichStatsLanguages(
        period: StatsPeriod,
        now: ZonedDateTime,
    ) {
        val accountId = activeUserId
        val window = statsWindow(period, now)
        val currentRows = activityCache
            .asSequence()
            .filter { it.reachedListenThreshold() }
            .filter { row ->
                row.startedInstant()?.let { it >= window.currentStart && it < window.currentEnd } == true
            }
            .toList()
        if (currentRows.isEmpty()) return

        val candidates = currentRows
            .filter { row ->
                // Re-check legacy Russian assignments as well as missing ones.
                // Older builds classified a track as Russian when *any* single
                // Cyrillic character appeared in the lyric/caption payload.
                row.languageCode.isNullOrBlank() ||
                    row.languageCode.equals("ru", ignoreCase = true)
            }
            .groupBy { it.videoId }
            .values
            .sortedWith(
                compareByDescending<List<ListeningActivity>> { it.size }
                    .thenByDescending { rows -> rows.sumOf { it.effectivePlayedMs() } },
            )
            .take(LANGUAGE_ENRICHMENT_TRACK_LIMIT)

        if (candidates.isNotEmpty()) {
            supervisorScope {
                val semaphore = Semaphore(LANGUAGE_ENRICHMENT_CONCURRENCY)
                candidates.map { plays ->
                    launch {
                        semaphore.withPermit {
                            if (activeUserId != accountId) return@withPermit
                            val sample = plays.first()
                            TrackLanguageResolver.resolveWithExactLyrics(
                                videoId = sample.videoId,
                                title = sample.title,
                                artist = sample.artist,
                                album = sample.album,
                                durationMs = sample.durationMs,
                            )?.let { languageCode ->
                                runCatching {
                                    SocialRepository.backfillListeningLanguage(
                                        videoId = sample.videoId,
                                        languageCode = languageCode,
                                    )
                                }
                            }
                        }
                    }
                }.joinAll()
            }
        }

        if (activeUserId != accountId || _statsPeriod.value != period) return
        val languages = rankedListeningLanguages(currentRows, STATS_TASTE_COUNT)
        _listeningStats.value = _listeningStats.value.copy(
            topLanguage = languages.firstOrNull(),
            topLanguages = languages,
        )
        persistStatsSnapshot(period, _listeningStats.value)
    }

    /** Prepares every Stats period together and publishes each portrait as it resolves. */
    private fun prefetchAllStatsArtistArtwork() {
        statsArtistPrefetchJob?.cancel()
        val accountId = activeUserId
        val activitySnapshot = activityCache
        val kindSnapshot = statsArtistKindCache.toMap()
        if (activitySnapshot.isEmpty()) {
            statsArtistPrefetchJob = null
            return
        }

        statsArtistPrefetchJob = viewModelScope.launch {
            val now = ZonedDateTime.now()
            val artists = withContext(Dispatchers.Default) {
                StatsPeriod.values()
                    .flatMap { period -> artistLeadersForPeriod(period, now, activitySnapshot, kindSnapshot) }
                    .distinctBy { it.title.normalizeArtistMatchKey() }
            }
            if (activeUserId != accountId || artists.isEmpty()) return@launch

            supervisorScope {
                val semaphore = Semaphore(ARTIST_ARTWORK_CONCURRENCY)
                artists.map { leader ->
                    launch {
                        semaphore.withPermit {
                            if (activeUserId != accountId) return@withPermit
                            val artwork = resolveArtistArtwork(leader)
                            if (activeUserId != accountId) return@withPermit
                            val initialKind = statsArtistKindCache[leader.title.normalizeArtistMatchKey()]
                                ?: heuristicArtistKind(leader.title)
                            applyArtistMetadata(leader.title, artwork, initialKind)

                            val kind = resolveArtistKind(leader.title)
                            if (activeUserId == accountId) {
                                applyArtistMetadata(leader.title, artwork, kind)
                            }
                        }
                    }
                }.joinAll()
            }
        }
    }

    private fun recomputeStats(now: ZonedDateTime = ZonedDateTime.now()) {
        val periodSnapshot = _statsPeriod.value
        val previous = cachedStatsByPeriod[periodSnapshot] ?: _listeningStats.value
        val fresh = aggregateListeningStats(
            activity = activityCache,
            playlistActivity = playlistActivityCache,
            period = periodSnapshot,
            now = now,
        )
        _listeningStats.value = fresh.copy(
            topGenre = fresh.topGenre ?: previous.topGenre,
            topGenres = fresh.topGenres.ifEmpty { previous.topGenres },
            newFollowersCount = previous.newFollowersCount,
            topArtist = fresh.topArtist.preserveArtistPresentationFrom(previous.topArtist),
            topSong = fresh.topSong.preservePresentationFrom(previous.topSong),
            topAlbum = fresh.topAlbum.preservePresentationFrom(previous.topAlbum),
            topPlaylist = fresh.topPlaylist.preservePresentationFrom(previous.topPlaylist),
        )
        persistStatsSnapshot(periodSnapshot, _listeningStats.value)
        refreshHomeTopArtists(now)
        refreshHomeTopAlbums(now)
        restartStatsEnrichment(periodSnapshot, now)
    }

    private suspend fun enrichStatsAuxiliary(period: StatsPeriod, now: ZonedDateTime) {
        val accountId = activeUserId
        val leaders = artistLeadersForPeriod(period, now).take(TOP_GENRE_ARTIST_LIMIT)
        val topGenres = resolveRankedGenres(
            leaders = leaders,
            limit = STATS_TASTE_COUNT,
        )

        val window = statsWindow(period, now)
        val newFollowers = runCatching {
            SocialRepository.newFollowersCountSince(window.currentStart)
        }.getOrDefault(0)

        if (activeUserId != accountId || _statsPeriod.value != period) return
        val current = _listeningStats.value
        // A transient catalogue outage must not overwrite a valid persistent
        // genre snapshot with an empty list. Fresh non-empty results always win.
        val effectiveGenres = topGenres.ifEmpty { current.topGenres }
        _listeningStats.value = current.copy(
            topGenre = effectiveGenres.firstOrNull() ?: current.topGenre,
            topGenres = effectiveGenres,
            newFollowersCount = newFollowers,
        )
        persistStatsSnapshot(period, _listeningStats.value)
    }

    private suspend fun enrichStatsArtwork() {
        val snapshot = _listeningStats.value
        val artist = snapshot.topArtist
        val song = snapshot.topSong
        val album = snapshot.topAlbum
        val playlist = snapshot.topPlaylist

        // Resolve the playlist first and publish it immediately. Artist metadata
        // can require multiple catalogue calls; making playlist artwork wait for
        // those calls was enough to leave the final Stats card blank for a long
        // time on a slow connection even though its exact playlist id was known.
        val playlistArtwork: String? = if (playlist != null) {
            resolvePlaylistArtwork(playlist)
        } else {
            null
        }
        if (!playlistArtwork.isNullOrBlank()) {
            val current = _listeningStats.value
            _listeningStats.value = current.copy(
                topPlaylist = current.topPlaylist?.copy(artworkUrl = playlistArtwork),
            )
        }

        artist?.let { leader ->
            val artwork = resolveArtistArtwork(leader)
            val initialKind = statsArtistKindCache[leader.title.normalizeArtistMatchKey()]
                ?: heuristicArtistKind(leader.title)
            applyArtistMetadata(leader.title, artwork, initialKind)

            val kind = resolveArtistKind(leader.title)
            applyArtistMetadata(leader.title, artwork, kind)
        }
        val songArtwork = song?.takeIf { it.artworkUrl.isNullOrBlank() }?.let { leader ->
            resolveSongArtwork(leader)
        }
        val albumArtwork = album?.takeIf { it.artworkUrl.isNullOrBlank() }?.let { leader ->
            resolveStatsArtwork(
                cacheKey = "album:${leader.title.lowercase()}:${leader.subtitle.orEmpty().lowercase()}",
                query = listOfNotNull(leader.title, leader.subtitle).joinToString(" "),
                filter = SearchFilter.ALBUMS,
                type = BrowseType.ALBUM,
                preferExactTitle = leader.title,
            )
        }

        val current = _listeningStats.value
        _listeningStats.value = current.copy(
            topSong = current.topSong?.let { leader ->
                if (!songArtwork.isNullOrBlank()) leader.copy(artworkUrl = songArtwork) else leader
            },
            topAlbum = current.topAlbum?.let { leader ->
                if (!albumArtwork.isNullOrBlank()) leader.copy(artworkUrl = albumArtwork) else leader
            },
            topPlaylist = current.topPlaylist?.let { leader ->
                if (!playlistArtwork.isNullOrBlank()) leader.copy(artworkUrl = playlistArtwork) else leader
            },
        )
        persistStatsSnapshot(_statsPeriod.value, _listeningStats.value)
    }

    private fun refreshHomeTopArtists(now: ZonedDateTime = ZonedDateTime.now()) {
        _homeTopArtists.value = artistLeadersForPeriod(
            period = StatsPeriod.YEAR,
            now = now,
        ).take(HOME_TOP_ARTIST_COUNT)
    }

    private fun refreshHomeTopAlbums(now: ZonedDateTime = ZonedDateTime.now()) {
        val window = statsWindow(StatsPeriod.YEAR, now)
        val rows = activityCache.filter { row ->
            row.startedInstant()?.let {
                it >= window.currentStart && it < window.currentEnd
            } == true
        }
        _homeTopAlbums.value = rankListeningAlbums(rows, limit = HOME_TOP_ALBUM_COUNT).map { ranked ->
            StatsLeader(
                title = ranked.title,
                subtitle = ranked.subtitle,
                artworkUrl = ranked.artworkUrl,
                listenedMs = ranked.listenedMs,
                plays = ranked.plays,
            )
        }
    }

    private fun artistLeadersForPeriod(
        period: StatsPeriod,
        now: ZonedDateTime = ZonedDateTime.now(),
        activity: List<ListeningActivity> = activityCache,
        artistKinds: Map<String, ArtistKind> = statsArtistKindCache,
    ): List<StatsLeader> {
        val window = statsWindow(period, now)
        val periodRows = activity.filter { row ->
            row.startedInstant()?.let { it >= window.currentStart && it < window.currentEnd } == true
        }
        return rankListeningArtists(periodRows, limit = MAX_ARTIST_RANKING).map { ranked ->
            StatsLeader(
                title = ranked.title,
                artworkUrl = ArtistArtworkRepository.cached(ranked.title),
                listenedMs = ranked.listenedMs,
                plays = ranked.plays,
                kind = artistKinds[ranked.identity] ?: ArtistKind.UNKNOWN,
            )
        }
    }

    /**
     * Migrates ranking history created before artist-credit splitting was wired
     * back into Stats. If the old ranking had already seen a combined credit,
     * its now-resolved component artists must not suddenly appear as "New".
     */
    private fun migrateSeenArtistCredits(
        history: ArtistRankingHistorySnapshot,
    ): ArtistRankingHistorySnapshot {
        if (history.seenArtists.isEmpty()) return history
        val seen = history.seenArtists.toMutableSet()
        val oldSeen = history.seenArtists
        activityCache.asSequence()
            .filter { it.reachedListenThreshold() }
            .filter { row -> row.artist.normalizeArtistMatchKey() in oldSeen }
            .flatMap { row -> ArtistCreditResolver.creditsForCounting(row.artist).asSequence() }
            .map { it.normalizeArtistMatchKey() }
            .filter { it.isNotBlank() }
            .forEach(seen::add)
        return if (seen == history.seenArtists) history else history.copy(seenArtists = seen)
    }

    private fun seedArtistRankingHistory(
        previous: ArtistRankingHistorySnapshot,
        leadersByPeriod: Map<StatsPeriod, List<StatsLeader>>,
        nowMs: Long,
    ): ArtistRankingHistorySnapshot {
        val seen = previous.seenArtists.toMutableSet()
        // Migration is deliberately conservative: any artist present in the
        // retained listening history counts as already known. We cannot
        // reconstruct page-open snapshots from older app versions, and a false
        // "New" badge on a long-standing artist is worse than omitting "New"
        // once during this one-time migration.
        activityCache.asSequence()
            .filter { it.reachedListenThreshold() }
            .flatMap { row -> ArtistCreditResolver.creditsForCounting(row.artist).asSequence() }
            .map { it.normalizeArtistMatchKey() }
            .filter { it.isNotBlank() }
            .forEach(seen::add)
        val periods = previous.periods.mapValues { (_, entries) ->
            entries.mapValues { (_, entry) -> entry.copy(active = false) }.toMutableMap()
        }.toMutableMap()

        leadersByPeriod.forEach { (period, leaders) ->
            val saved = periods.getOrPut(period.name) { linkedMapOf() }.toMutableMap()
            leaders.forEachIndexed { index, leader ->
                val key = leader.title.normalizeArtistMatchKey()
                val position = index + 1
                seen += key
                saved[key] = ArtistRankingHistoryEntry(
                    artistName = leader.title,
                    position = position,
                    peakPosition = saved[key]?.peakPosition?.let { minOf(it, position) } ?: position,
                    movement = ArtistRankMovement.SAME.name,
                    movementAmount = 0,
                    movementSinceMs = nowMs,
                    listenedMs = leader.listenedMs,
                    plays = leader.plays,
                    active = true,
                )
            }
            periods[period.name] = saved
        }

        return ArtistRankingHistorySnapshot(
            seeded = true,
            seenArtists = seen,
            periods = periods,
        )
    }

    private fun snapshotsFromHistory(
        history: ArtistRankingHistorySnapshot,
    ): Map<StatsPeriod, List<ArtistRankEntry>> = StatsPeriod.values().associateWith { period ->
        history.periods[period.name].orEmpty().values
            .asSequence()
            .filter { it.active }
            .sortedWith(compareBy<ArtistRankingHistoryEntry> { it.position }.thenBy { it.artistName.lowercase() })
            .take(MAX_ARTIST_RANKING)
            .map { old ->
                val leader = StatsLeader(
                    title = old.artistName,
                    artworkUrl = ArtistArtworkRepository.cached(old.artistName),
                    listenedMs = old.listenedMs,
                    plays = old.plays,
                    kind = statsArtistKindCache[old.artistName.normalizeArtistMatchKey()] ?: ArtistKind.UNKNOWN,
                )
                ArtistRankEntry(
                    leader = leader,
                    position = old.position,
                    previousPosition = old.position,
                    peakPosition = old.peakPosition,
                    movement = old.movement.toArtistRankMovement(),
                    movementAmount = old.movementAmount,
                    movementSinceMs = old.movementSinceMs,
                )
            }
            .toList()
    }

    private fun commitArtistRankingHistory(
        previous: ArtistRankingHistorySnapshot,
        leadersByPeriod: Map<StatsPeriod, List<StatsLeader>>,
        nowMs: Long,
    ): Pair<ArtistRankingHistorySnapshot, Map<StatsPeriod, List<ArtistRankEntry>>> {
        val seen = previous.seenArtists.toMutableSet()
        val periods = previous.periods.mapValues { (_, entries) ->
            entries.mapValues { (_, entry) -> entry.copy(active = false) }.toMutableMap()
        }.toMutableMap()
        val snapshots = linkedMapOf<StatsPeriod, List<ArtistRankEntry>>()

        StatsPeriod.values().forEach { period ->
            val leaders = leadersByPeriod[period].orEmpty()
            val previousEntries = previous.periods[period.name].orEmpty()
            val saved = periods.getOrPut(period.name) { linkedMapOf() }.toMutableMap()
            val entries = leaders.mapIndexed { index, leader ->
                val key = leader.title.normalizeArtistMatchKey()
                val position = index + 1
                val old = previousEntries[key]
                val everSeen = key in seen
                val oldPeak = old?.peakPosition?.coerceAtLeast(1)
                val nextPeak = oldPeak?.let { minOf(it, position) } ?: position

                val movement = when {
                    !everSeen -> ArtistRankMovement.NEW
                    old == null -> ArtistRankMovement.SAME

                    // The artist existed in this period's ranking history but
                    // was outside the previous Top 30 snapshot. Re-entry is a
                    // distinct event and must never look unchanged.
                    !old.active -> ArtistRankMovement.RETURNED

                    position < old.position && position < old.peakPosition -> ArtistRankMovement.PEAK
                    position < old.position -> ArtistRankMovement.UP
                    position > old.position -> ArtistRankMovement.DOWN
                    else -> {
                        val carried = old.movement.toArtistRankMovement()
                        // "New" is a first-appearance event only. Direction and
                        // peak badges may persist across unchanged openings so
                        // the listener has time to notice what moved.
                        val stillFresh = carried in setOf(
                            ArtistRankMovement.PEAK,
                            ArtistRankMovement.UP,
                            ArtistRankMovement.DOWN,
                            ArtistRankMovement.RETURNED,
                        ) && old.movementSinceMs > 0L &&
                            nowMs - old.movementSinceMs < RANKING_MOVEMENT_RETENTION_MS
                        if (stillFresh) carried else ArtistRankMovement.SAME
                    }
                }

                val movementAmount = when (movement) {
                    ArtistRankMovement.UP -> old?.position?.minus(position)?.coerceAtLeast(1) ?: 0
                    ArtistRankMovement.DOWN -> position.minus(old?.position ?: position).coerceAtLeast(1)
                    else -> 0
                }
                val movementSinceMs = when {
                    movement == ArtistRankMovement.SAME -> old?.movementSinceMs ?: nowMs
                    old != null && position == old.position && movement == old.movement.toArtistRankMovement() ->
                        old.movementSinceMs
                    else -> nowMs
                }

                seen += key
                saved[key] = ArtistRankingHistoryEntry(
                    artistName = leader.title,
                    position = position,
                    peakPosition = nextPeak,
                    movement = movement.name,
                    movementAmount = movementAmount,
                    movementSinceMs = movementSinceMs,
                    listenedMs = leader.listenedMs,
                    plays = leader.plays,
                    active = true,
                )

                ArtistRankEntry(
                    leader = leader,
                    position = position,
                    previousPosition = old?.position,
                    peakPosition = nextPeak,
                    movement = movement,
                    movementAmount = movementAmount,
                    movementSinceMs = movementSinceMs,
                )
            }
            periods[period.name] = saved
            snapshots[period] = entries
        }

        return ArtistRankingHistorySnapshot(
            seeded = true,
            seenArtists = seen,
            periods = periods,
        ) to snapshots
    }

    private fun String?.toArtistRankMovement(): ArtistRankMovement =
        runCatching { ArtistRankMovement.valueOf(this.orEmpty()) }.getOrDefault(ArtistRankMovement.SAME)

    private suspend fun enrichArtistRankingArtwork(entries: List<ArtistRankEntry>, requestId: Long) {
        if (entries.isEmpty()) return
        supervisorScope {
            val semaphore = Semaphore(ARTIST_ARTWORK_CONCURRENCY)
            entries.map { entry ->
                launch {
                    semaphore.withPermit {
                        if (!isArtistRankingRequestCurrent(requestId)) return@withPermit
                        val artwork = resolveArtistArtwork(entry.leader)
                        if (!isArtistRankingRequestCurrent(requestId)) return@withPermit

                        // Paint the portrait immediately. Artist/band metadata
                        // may require another service, so it must not hold the
                        // image back.
                        val cachedKind = statsArtistKindCache[entry.leader.title.normalizeArtistMatchKey()]
                            ?: heuristicArtistKind(entry.leader.title)
                        applyArtistMetadata(entry.leader.title, artwork, cachedKind)

                        val kind = resolveArtistKind(entry.leader.title)
                        if (isArtistRankingRequestCurrent(requestId)) {
                            applyArtistMetadata(entry.leader.title, artwork, kind)
                        }
                    }
                }
            }.joinAll()
        }
    }

    private fun applyArtistMetadata(artistName: String, artwork: String?, kind: ArtistKind) {
        val key = artistName.normalizeArtistMatchKey()
        StatsPeriod.values().forEach { period ->
            val snapshot = rankingSnapshots[period] ?: return@forEach
            rankingSnapshots[period] = snapshot.map { entry ->
                if (entry.leader.title.normalizeArtistMatchKey() == key) {
                    entry.copy(leader = entry.leader.copy(artworkUrl = artwork, kind = kind))
                } else {
                    entry
                }
            }
        }
        if (_artistRankingOpen.value) {
            _artistRanking.value = rankingSnapshots[_statsPeriod.value].orEmpty()
        }
        _homeTopArtists.value = _homeTopArtists.value.map { leader ->
            if (leader.title.normalizeArtistMatchKey() == key) {
                leader.copy(artworkUrl = artwork, kind = kind)
            } else {
                leader
            }
        }

        val currentStats = _listeningStats.value
        val topArtist = currentStats.topArtist
        if (topArtist?.title?.normalizeArtistMatchKey() == key) {
            _listeningStats.value = currentStats.copy(
                topArtist = topArtist.copy(artworkUrl = artwork, kind = kind),
            )
            persistStatsSnapshot(_statsPeriod.value, _listeningStats.value)
        }
    }

    private suspend fun resolveArtistArtwork(leader: StatsLeader): String? =
        ArtistArtworkRepository.resolve(leader.title)

    private suspend fun resolveArtistKind(artistName: String): ArtistKind {
        val key = artistName.normalizeArtistMatchKey()
        statsArtistKindCache[key]?.let { return it }
        val kind = fetchMusicBrainzArtistKind(artistName) ?: heuristicArtistKind(artistName)
        statsArtistKindCache[key] = kind
        return kind
    }

    private suspend fun fetchMusicBrainzArtistKind(artistName: String): ArtistKind? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode("artist:\"$artistName\"", StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url("https://musicbrainz.org/ws/2/artist/?query=$query&fmt=json&limit=5")
            .header("User-Agent", "Orb/1.0 (music app)")
            .get()
            .build()
        runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val artists = JSONObject(response.body?.string().orEmpty()).optJSONArray("artists") ?: return@use null
                val wanted = artistName.normalizeArtistMatchKey()
                val match = (0 until artists.length())
                    .mapNotNull { artists.optJSONObject(it) }
                    .firstOrNull { it.optString("name").normalizeArtistMatchKey() == wanted }
                    ?: artists.optJSONObject(0)
                    ?: return@use null
                when (match.optString("type").lowercase()) {
                    "person" -> ArtistKind.PERSON
                    "group", "orchestra", "choir", "character" -> ArtistKind.BAND
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun heuristicArtistKind(name: String): ArtistKind {
        val normalized = name.trim().lowercase()
        return if (normalized.startsWith("the ") || normalized.contains(" & ") ||
            normalized.contains(" and ") || normalized.contains(" band") ||
            normalized.contains(" orchestra") || normalized.endsWith("s")
        ) ArtistKind.BAND else ArtistKind.PERSON
    }

    private suspend fun resolveSongArtwork(leader: StatsLeader): String? {
        val key = "song:${leader.title.lowercase()}:${leader.subtitle.orEmpty().lowercase()}"
        if (statsArtworkCache.containsKey(key)) return statsArtworkCache[key]
        val query = listOfNotNull(leader.title, leader.subtitle).joinToString(" ")
        val artwork = YtMusicRepository.search(query, SearchFilter.SONGS)
            .getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.map { it.song }
            ?.let { songs ->
                songs.firstOrNull { it.title.equals(leader.title, ignoreCase = true) }
                    ?: songs.firstOrNull()
            }
            ?.thumbnailUrl
        statsArtworkCache[key] = artwork
        return artwork
    }

    private suspend fun resolveStatsArtwork(
        cacheKey: String,
        query: String,
        filter: SearchFilter,
        type: BrowseType,
        preferExactTitle: String,
    ): String? {
        if (statsArtworkCache.containsKey(cacheKey)) return statsArtworkCache[cacheKey]
        val artwork = YtMusicRepository.search(query, filter)
            .getOrNull()
            ?.filterIsInstance<SearchResult.Browse>()
            ?.map { it.item }
            ?.filter { it.type == type && !it.thumbnailUrl.isNullOrBlank() }
            ?.let { items ->
                items.firstOrNull { it.title.equals(preferExactTitle, ignoreCase = true) }
                    ?: items.firstOrNull()
            }
            ?.thumbnailUrl
        // Cache only positive hits. A temporary search/catalogue miss must not
        // make a blank Stats card permanent for the rest of the process.
        if (!artwork.isNullOrBlank()) statsArtworkCache[cacheKey] = artwork
        return artwork
    }

    /**
     * Playlist artwork is identity-sensitive. Search-by-title alone can miss a
     * private/restored playlist or choose a same-name public playlist, so use
     * the playlist id captured by listening_activity first and search only as a
     * fallback for older rows that predate that id in the Stats snapshot.
     */
    private suspend fun resolvePlaylistArtwork(leader: StatsLeader): String? {
        val stableId = leader.sourceId?.trim()?.takeIf(String::isNotBlank)
        val cacheKey = "playlist:${stableId ?: leader.title.lowercase(Locale.ROOT)}"
        statsArtworkCache[cacheKey]?.takeIf(String::isNotBlank)?.let { return it }

        var firstTrackFallback: String? = null
        if (stableId != null) {
            val browseIds = buildList {
                add(stableId)
                // Older rows may store either the editable raw playlist id or
                // YouTube Music's browse-page `VL` id. Always try both forms.
                if (!stableId.startsWith("VL") && ':' !in stableId) add("VL$stableId")
            }.distinct()
            for (browseId in browseIds) {
                val page = runCatching { YtMusicRepository.browseSongs(browseId).getOrNull() }
                    .getOrNull() ?: continue
                page.thumbnailUrl?.takeIf(String::isNotBlank)?.let { exact ->
                    statsArtworkCache[cacheKey] = exact
                    return exact
                }
                if (firstTrackFallback == null) {
                    firstTrackFallback = page.songs
                        .firstNotNullOfOrNull { it.thumbnailUrl?.takeIf(String::isNotBlank) }
                }
            }
        }

        // Private/account playlists are not guaranteed to appear in public
        // search. Ask the signed-in library by exact id before trying title
        // search so the Stats card uses the user's real playlist cover.
        val ownLibrary = runCatching { YtMusicRepository.userPlaylists().getOrNull() }
            .getOrNull()
            ?.let { playlists ->
                val rawKey = stableId?.removePrefix("VL")
                playlists.firstOrNull { playlist ->
                    (!rawKey.isNullOrBlank() && playlist.playlistId == rawKey) ||
                        (!stableId.isNullOrBlank() && playlist.browseId == stableId) ||
                        playlist.title.equals(leader.title, ignoreCase = true)
                }
            }
            ?.thumbnailUrl
            ?.takeIf(String::isNotBlank)
        if (ownLibrary != null) {
            statsArtworkCache[cacheKey] = ownLibrary
            return ownLibrary
        }

        // A cover captured when the playlist was originally opened is still a
        // stronger fallback than a same-name public search result. This keeps
        // private/restored playlists visually correct even during a catalogue
        // outage, while exact-id resolution above can replace stale metadata.
        leader.artworkUrl?.takeIf(String::isNotBlank)?.let { return it }

        val searched = resolveStatsArtwork(
            cacheKey = "$cacheKey:search",
            query = leader.title,
            filter = SearchFilter.PLAYLISTS,
            type = BrowseType.PLAYLIST,
            preferExactTitle = leader.title,
        )
        if (!searched.isNullOrBlank()) {
            statsArtworkCache[cacheKey] = searched
            return searched
        }

        // Last-resort visual for playlist pages whose server response contains
        // no header image at all. Prefer a real playlist/search cover above;
        // only then use the first authored tile so the Stats card is never blank.
        firstTrackFallback?.let { fallback ->
            statsArtworkCache[cacheKey] = fallback
            return fallback
        }
        return null
    }

    private fun statsRetentionStart(now: ZonedDateTime = ZonedDateTime.now()): Instant {
        // Every Stats period compares against the same elapsed point in the
        // immediately previous period. Keeping history from the start of last
        // year guarantees the YEAR graph/ranking has the full comparison window
        // instead of silently truncating it to the recent quarter cache.
        return yearStart(now).minusYears(1).toInstant()
    }

    private fun restartOwnStatsRealtime() {
        statsRealtimeJob?.cancel()
        statsRealtimeJob = null
        if (!visible) return
        val userId = activeUserId ?: SocialRepository.currentUserId() ?: return

        statsRealtimeJob = viewModelScope.launch {
            SocialRepository.listeningActivityChangesForUsers(listOf(userId))
                .retryWhen { _, attempt ->
                    if (!visible || activeUserId != userId) return@retryWhen false
                    delay((1_000L * (attempt + 1)).coerceAtMost(10_000L))
                    true
                }
                .catch {
                    // Stats keeps its cached snapshot if Realtime is unavailable;
                    // reopening/pull-to-refresh still reconciles from Supabase.
                }
                .collect { change ->
                    if (activeUserId != userId) return@collect
                    when (change) {
                        is ListeningActivityChange.Upsert -> {
                            val item = change.value
                            if (item.userId != userId) return@collect
                            val key = item.videoId to item.startedAt
                            var replaced = false
                            val updated = activityCache.map { existing ->
                                if ((existing.videoId to existing.startedAt) == key) {
                                    replaced = true
                                    item
                                } else {
                                    existing
                                }
                            }
                            activityCache = if (replaced) updated else updated + item

                            // New listens arrive through Realtime after the cold-start
                            // resolver has already run. Resolve a fresh comma/& credit
                            // before recomputing or it would stay grouped until the next
                            // manual refresh/relaunch.
                            ArtistCreditResolver.resolveAll(listOf(item.artist))

                            val period = _statsPeriod.value
                            val now = ZonedDateTime.now()
                            val fresh = withContext(Dispatchers.Default) {
                                aggregateListeningStats(
                                    activity = activityCache,
                                    playlistActivity = playlistActivityCache,
                                    period = period,
                                    now = now,
                                )
                            }
                            if (activeUserId == userId && _statsPeriod.value == period) {
                                val existing = _listeningStats.value
                                _listeningStats.value = fresh.copy(
                                    topGenre = existing.topGenre,
                                    topGenres = existing.topGenres,
                                    newFollowersCount = existing.newFollowersCount,
                                    topArtist = fresh.topArtist.preserveArtistPresentationFrom(existing.topArtist),
                                    topSong = fresh.topSong.preservePresentationFrom(existing.topSong),
                                    topAlbum = fresh.topAlbum.preservePresentationFrom(existing.topAlbum),
                                    topPlaylist = fresh.topPlaylist.preservePresentationFrom(existing.topPlaylist),
                                )
                                persistStatsSnapshot(period, _listeningStats.value)
                            }
                        }
                        is ListeningActivityChange.Remove -> Unit
                    }
                }
        }
    }

    private fun restartRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        activityRealtimeJob?.cancel()
        activityRealtimeJob = null
        reactionRealtimeJob?.cancel()
        reactionRealtimeJob = null
        socialFeedPollJob?.cancel()
        socialFeedPollJob = null
        if (!visible) return
        val ids = _followingIds.value
        if (ids.isEmpty()) return

        realtimeJob = viewModelScope.launch {
            SocialRepository.nowPlayingChangesForUsers(ids)
                .retryWhen { _, attempt ->
                    if (!visible) return@retryWhen false
                    delay((1_000L * (attempt + 1)).coerceAtMost(10_000L))
                    true
                }
                .catch {
                    // The automatic snapshot loop below keeps the feed live even
                    // when an OEM/network temporarily drops the websocket.
                }
                .collect { change ->
                    if (change.userId !in _followingIds.value) return@collect
                    when (change) {
                        is FriendNowPlayingChange.Upsert -> {
                            applyNowPlayingUpsert(change.value)
                            scheduleSocialFeedPushSync()
                        }
                        is FriendNowPlayingChange.Remove -> {
                            removeNowPlayingForUser(change.userId)
                            scheduleSocialFeedPushSync()
                        }
                    }
                }
        }

        activityRealtimeJob = viewModelScope.launch {
            SocialRepository.listeningActivityChangesForUsers(ids)
                .retryWhen { _, attempt ->
                    if (!visible) return@retryWhen false
                    delay((1_000L * (attempt + 1)).coerceAtMost(10_000L))
                    true
                }
                .catch {
                    // A lightweight delta poll below remains active even if the
                    // websocket cannot be established on this network/session.
                }
                .collect { change ->
                    when (change) {
                        is ListeningActivityChange.Upsert -> {
                            applyListeningActivityUpsert(change.value)
                            scheduleSocialFeedPushSync()
                        }
                        is ListeningActivityChange.Remove -> scheduleSocialFeedPushSync()
                    }
                }
        }

        reactionRealtimeJob = viewModelScope.launch {
            SocialRepository.reactionChangesForUsers(ids)
                .catch {
                    // The feed remains functional when the optional reaction
                    // migration/channel is unavailable.
                }
                .collect { change -> applyReactionChange(change) }
        }

        // Realtime remains the zero-latency path, but Stats must never require a
        // manual refresh if Postgres Changes is unavailable. While Stats is visible,
        // this lightweight live loop reads only the followed users' now_playing and
        // newest listening_activity rows. It does not reload cards/rankings/the page.
        // The playback service writes listening_activity only after 30 seconds of
        // real forward playback. Realtime events reconcile immediately; this slower
        // safety poll only catches events that the websocket/network actually dropped.
        socialFeedPollJob = viewModelScope.launch {
            while (isActive && visible) {
                syncNowPlayingSnapshot()
                syncSocialFeedDelta()
                delay(SOCIAL_FEED_LIVE_SYNC_MS)
            }
        }
    }

    private suspend fun syncNowPlayingSnapshot() {
        if (!visible) return
        val ids = _followingIds.value
        if (ids.isEmpty()) return
        runCatching { SocialRepository.nowPlayingForUsers(ids) }
            .onSuccess { snapshot ->
                // Replace only after a successful server read; a transient request
                // failure must never make everybody suddenly look offline.
                _nowPlaying.value = snapshot
            }
    }

    private fun scheduleSocialFeedPushSync() {
        socialFeedPushSyncJob?.cancel()
        socialFeedPushSyncJob = viewModelScope.launch {
            // Realtime already updates local state instantly. Give companion writes a fraction
            // of a second to commit, then reconcile both lightweight server views once. This keeps
            // the 6-second fallback truly a fallback instead of the primary live-update path.
            delay(SOCIAL_FEED_PUSH_SETTLE_MS)
            syncNowPlayingSnapshot()
            syncSocialFeedDelta()
        }
    }

    private suspend fun syncSocialFeedDelta() {
        if (!visible) return
        val ids = _followingIds.value
        if (ids.isEmpty()) return

        val retentionStart = Instant.now().minusSeconds(SOCIAL_FEED_RETENTION_SECONDS)
        val latestVisibleStart = _socialFeed.value
            .mapNotNull { it.startedInstant() }
            .maxOrNull()
        val candidate = latestVisibleStart?.minusSeconds(SOCIAL_FEED_DELTA_OVERLAP_SECONDS)
        val since = if (candidate != null && candidate > retentionStart) candidate else retentionStart

        val rows = runCatching {
            SocialRepository.recentListeningActivityForUsers(
                userIds = ids,
                since = since,
                maxRows = SOCIAL_FEED_DELTA_MAX_ROWS,
            )
        }.getOrDefault(emptyList())

        rows.forEach(::applyListeningActivityUpsert)
        pruneSocialFeed()
    }

    private fun applyNowPlayingUpsert(item: FriendNowPlaying) {
        if (item.userId !in _followingIds.value) return
        if (!item.isFresh()) {
            removeNowPlayingForUser(item.userId)
            return
        }
        _nowPlaying.value = _nowPlaying.value + (item.userId to item)
    }

    private fun removeNowPlayingForUser(userId: String) {
        // Removing now_playing only changes the label on the newest feed card.
        // The listening_activity row remains visible for up to four hours.
        _nowPlaying.value = _nowPlaying.value - userId
    }

    private fun applyListeningActivityUpsert(item: ListeningActivity) {
        if (item.userId !in _followingIds.value) return
        if (!item.reachedSocialFeedThreshold()) return
        val finished = runCatching { Instant.parse(item.finishedAt) }.getOrNull()
            ?: runCatching { Instant.parse(item.startedAt) }.getOrNull()
            ?: return
        if (finished < Instant.now().minusSeconds(SOCIAL_FEED_RETENTION_SECONDS)) return

        val key = item.reactionSessionKey()
        val existed = _socialFeed.value.any { it.reactionSessionKey() == key }
        val merged = (_socialFeed.value.filterNot { it.reactionSessionKey() == key } + item)
            .sortedByDescending { row ->
                runCatching { Instant.parse(row.startedAt) }.getOrNull() ?: Instant.EPOCH
            }
            .collapseConsecutiveSocialRepeats()
        _socialFeed.value = merged

        // A repeated play replaces the previous card for the same uninterrupted
        // run, so keep transient state only for sessions that remain visible.
        val visibleKeys = merged.mapTo(linkedSetOf()) { it.reactionSessionKey() }
        _nowPlayingReactions.value = _nowPlayingReactions.value.filterKeys { it in visibleKeys }
        _newSocialFeedSessions.value = _newSocialFeedSessions.value.intersect(visibleKeys)
        _reactionBusySessions.value = _reactionBusySessions.value.intersect(visibleKeys)
        if (!existed) {
            _newSocialFeedSessions.value = _newSocialFeedSessions.value + key
            viewModelScope.launch {
                runCatching { SocialRepository.reactionsForListeningActivity(listOf(item)) }
                    .onSuccess(::mergeReactionSnapshot)
            }
        }
    }

    fun consumeSocialFeedEntrance(sessionKey: String) {
        _newSocialFeedSessions.value = _newSocialFeedSessions.value - sessionKey
    }

    private fun applyReactionChange(change: NowPlayingReactionChange) {
        val reaction = change.value
        if (reaction.listenerId !in _followingIds.value) return
        val key = reaction.sessionKey()
        if (_socialFeed.value.none { it.reactionSessionKey() == key }) return

        val current = _nowPlayingReactions.value[key].orEmpty()
        val updated = when (change) {
            is NowPlayingReactionChange.Upsert -> {
                current.filterNot { it.reactorId == reaction.reactorId } + reaction
            }
            is NowPlayingReactionChange.Remove -> {
                current.filterNot { it.reactorId == reaction.reactorId }
            }
        }.sortedBy { it.reactedAt }
        _nowPlayingReactions.value = if (updated.isEmpty()) {
            _nowPlayingReactions.value - key
        } else {
            _nowPlayingReactions.value + (key to updated)
        }
    }

    private fun mergeReactionSnapshot(snapshot: Map<String, List<NowPlayingReaction>>) {
        if (snapshot.isEmpty()) return
        val activeKeys = _socialFeed.value.mapTo(linkedSetOf()) { it.reactionSessionKey() }
        var merged = _nowPlayingReactions.value
        snapshot.forEach { (key, reactions) ->
            if (key in activeKeys) {
                val byReactor = (merged[key].orEmpty() + reactions)
                    .groupBy { it.reactorId }
                    .mapNotNull { (_, values) -> values.maxByOrNull { it.reactedAt } }
                    .sortedBy { it.reactedAt }
                merged = merged + (key to byReactor)
            }
        }
        _nowPlayingReactions.value = merged
    }

    private fun pruneSocialFeed() {
        val now = System.currentTimeMillis()
        _nowPlaying.value = _nowPlaying.value.filterValues { it.isFresh(now) }
        val cutoff = Instant.now().minusSeconds(SOCIAL_FEED_RETENTION_SECONDS)
        _socialFeed.value = _socialFeed.value.filter { row ->
            val finished = runCatching { Instant.parse(row.finishedAt) }.getOrNull()
            val started = runCatching { Instant.parse(row.startedAt) }.getOrNull()
            (finished ?: started ?: Instant.EPOCH) >= cutoff
        }.collapseConsecutiveSocialRepeats()
        val retainedKeys = _socialFeed.value.mapTo(linkedSetOf()) { it.reactionSessionKey() }
        _nowPlayingReactions.value = _nowPlayingReactions.value.filterKeys { it in retainedKeys }
        _newSocialFeedSessions.value = _newSocialFeedSessions.value.intersect(retainedKeys)
        _reactionBusySessions.value = _reactionBusySessions.value.intersect(retainedKeys)
    }

    private fun restartNotificationRealtime() {
        notificationRealtimeJob?.cancel()
        notificationRealtimeJob = null
        if (!visible) return
        val me = SocialRepository.currentUserId() ?: return
        notificationRealtimeJob = viewModelScope.launch {
            launch {
                SocialRepository.reactionChangesForUsers(listOf(me))
                    .catch { }
                    .collect { change ->
                        if (change.value.listenerId != me) return@collect
                        refreshReactionNotifications()
                    }
            }
            launch {
                SocialRepository.followChangesForMe()
                    .catch { }
                    .collect {
                        refreshReactionNotifications()
                    }
            }
        }
    }

    private fun refreshReactionNotifications() {
        val me = SocialRepository.currentUserId() ?: return
        viewModelScope.launch {
            val since = Instant.now().minusSeconds(NOTIFICATION_RETENTION_SECONDS)
            val reactions = runCatching { SocialRepository.recentReactionsForMe(since) }
                .getOrDefault(emptyList())
            val follows = runCatching { SocialRepository.recentFollowersForMe(since) }
                .getOrDefault(emptyList())

            val activitySince = since.minusSeconds(SOCIAL_FEED_RETENTION_SECONDS)
            val ownActivity = runCatching { SocialRepository.myListeningActivitySince(activitySince) }
                .getOrDefault(emptyList())
            val activityByKey = ownActivity.associateBy { it.reactionSessionKey() }

            val actorIds = buildList {
                addAll(reactions.map { it.reactorId })
                addAll(follows.map { it.followerId })
            }.distinct()
            val missingIds = actorIds.filterNot { it in socialProfileCache }
            if (missingIds.isNotEmpty()) {
                runCatching { SocialRepository.profilesForUserIds(missingIds) }
                    .getOrDefault(emptyList())
                    .forEach { socialProfileCache[it.id] = it }
            }
            if (SocialRepository.currentUserId() != me) return@launch

            val notifications = buildList<StatsNotification> {
                reactions.forEach { reaction ->
                    add(
                        ReactionNotification(
                            reaction = reaction,
                            reactor = socialProfileCache[reaction.reactorId],
                            activity = activityByKey[reaction.sessionKey()],
                        ),
                    )
                }
                follows.forEach { follow ->
                    add(
                        FollowNotification(
                            follow = follow,
                            follower = socialProfileCache[follow.followerId],
                        ),
                    )
                }
            }.sortedByDescending { notification ->
                runCatching { Instant.parse(notification.happenedAt).toEpochMilli() }
                    .getOrDefault(0L)
            }

            _statsNotifications.value = notifications

            if (_notificationsOpen.value) {
                AppSettings.markStatsNotificationsSeen(me)
            }
            val seenAt = AppSettings.statsNotificationsSeenAtMs(me)
            _notificationUnreadCount.value = if (_notificationsOpen.value) {
                0
            } else {
                notifications.count { notification ->
                    runCatching { Instant.parse(notification.happenedAt).toEpochMilli() }
                        .getOrDefault(0L) > seenAt
                }
            }
        }
    }

    private fun Throwable.looksLikeUniqueConflict(): Boolean {
        val text = buildString {
            append(message.orEmpty())
            cause?.message?.let { append(' ').append(it) }
        }.lowercase()
        return "23505" in text || "duplicate" in text || "unique" in text || "already exists" in text
    }

    /**
     * The social feed represents listening changes, not a raw scrobble log.
     * Keep only the newest card in each uninterrupted run of the same song for
     * one user. If the user listens to another song and later returns, that
     * later run remains visible as a separate event.
     */
    private fun List<ListeningActivity>.collapseConsecutiveSocialRepeats(): List<ListeningActivity> {
        if (size < 2) return this

        val ordered = sortedByDescending { row ->
            runCatching { Instant.parse(row.startedAt) }.getOrNull() ?: Instant.EPOCH
        }
        val newestKeptByUser = HashMap<String, ListeningActivity>()
        val result = ArrayList<ListeningActivity>(ordered.size)

        ordered.forEach { row ->
            val newer = newestKeptByUser[row.userId]
            if (newer != null && row.isSameSocialRecordingAs(newer)) {
                return@forEach
            }
            result += row
            newestKeptByUser[row.userId] = row
        }
        return result
    }

    private fun ListeningActivity.isSameSocialRecordingAs(other: ListeningActivity): Boolean {
        if (userId != other.userId) return false
        if (videoId.isNotBlank() && videoId == other.videoId) return true

        fun String.socialTrackKey(): String = lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

        if (title.socialTrackKey() != other.title.socialTrackKey()) return false
        if (artist.socialTrackKey() != other.artist.socialTrackKey()) return false

        val firstDuration = durationMs?.takeIf { it > 0L }
        val secondDuration = other.durationMs?.takeIf { it > 0L }
        return firstDuration == null ||
            secondDuration == null ||
            kotlin.math.abs(firstDuration - secondDuration) <= 3_000L
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 320L
        const val STALE_PRUNE_INTERVAL_MS = 30_000L
        const val SOCIAL_FEED_RETENTION_SECONDS = 4L * 60L * 60L
        const val SOCIAL_FEED_LIVE_SYNC_MS = 6_000L
        const val SOCIAL_FEED_PUSH_SETTLE_MS = 300L
        const val SOCIAL_FEED_DELTA_OVERLAP_SECONDS = 8L
        const val SOCIAL_FEED_DELTA_MAX_ROWS = 80
        const val NOTIFICATION_RETENTION_SECONDS = 6L * 60L * 60L
        const val RANKING_POST_LOAD_REVEAL_DELAY_MS = 2_000L
        const val RANKING_MOVEMENT_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
        const val MAX_ARTIST_RANKING = 30
        const val STATS_SHARE_TOP_COUNT = 5
        const val STATS_SHARE_SONG_COUNT = 10
        const val RECOMMENDED_PROFILE_COUNT = 4
        const val TOP_GENRE_ARTIST_LIMIT = 8
        const val ARTIST_ARTWORK_CONCURRENCY = 4
        val USERNAME = Regex("^[a-z0-9._]+$")
    }
}


private fun StatsLeader?.preserveArtistPresentationFrom(previous: StatsLeader?): StatsLeader? {
    val current = this ?: return null
    if (previous == null || !current.title.equals(previous.title, ignoreCase = true)) return current
    return current.copy(
        // Never carry an old portrait forward. Artist artwork is identity-sensitive
        // and must be revalidated by ArtistArtworkRepository.
        artworkUrl = current.artworkUrl,
        sourceId = current.sourceId ?: previous.sourceId,
        kind = if (current.kind == ArtistKind.UNKNOWN) previous.kind else current.kind,
    )
}

private fun StatsLeader?.preservePresentationFrom(previous: StatsLeader?): StatsLeader? {
    val current = this ?: return null
    if (previous == null || !current.title.equals(previous.title, ignoreCase = true)) return current
    return current.copy(
        artworkUrl = current.artworkUrl ?: previous.artworkUrl,
        sourceId = current.sourceId ?: previous.sourceId,
        kind = if (current.kind == ArtistKind.UNKNOWN) previous.kind else current.kind,
    )
}

private fun StatsLeader.toCachedLeader(): CachedStatsLeader = CachedStatsLeader(
    title = title,
    subtitle = subtitle,
    artworkUrl = artworkUrl,
    sourceId = sourceId,
    listenedMs = listenedMs,
    plays = plays,
    kind = kind.name,
)

private fun CachedStatsLeader.toStatsLeader(): StatsLeader = StatsLeader(
    title = title,
    subtitle = subtitle,
    artworkUrl = artworkUrl,
    sourceId = sourceId,
    listenedMs = listenedMs,
    plays = plays,
    kind = runCatching { ArtistKind.valueOf(kind) }.getOrDefault(ArtistKind.UNKNOWN),
)

private fun ListeningStats.toCachedStats(): CachedListeningStats = CachedListeningStats(
    totalListenedMs = totalListenedMs,
    trackCount = trackCount,
    uniqueTracks = uniqueTracks,
    uniqueArtists = uniqueArtists,
    uniqueAlbums = uniqueAlbums,
    activeDays = activeDays,
    peakDate = peakDate?.toString(),
    peakDayOfWeek = peakDayOfWeek,
    peakHour = peakHour,
    averageTrackMs = averageTrackMs,
    comparisonPercent = comparisonPercent,
    topGenre = topGenre,
    topGenres = topGenres,
    topLanguage = topLanguage,
    topLanguages = topLanguages,
    newFollowersCount = newFollowersCount,
    topArtist = topArtist?.toCachedLeader(),
    topSong = topSong?.toCachedLeader(),
    topAlbum = topAlbum?.toCachedLeader(),
    topPlaylist = topPlaylist?.toCachedLeader(),
    rhythmSeries = rhythmSeries,
    previousRhythmSeries = previousRhythmSeries,
)

private fun CachedListeningStats.toListeningStats(): ListeningStats {
    // Old Stats snapshots may contain provider-specific labels (for example
    // "Kpop", "hip hop" or "electropop"). Canonicalise them while
    // hydrating so the page is consistent even before the next network refresh.
    val canonicalGenres = normalizeOrbGenres(
        topGenres.ifEmpty { listOfNotNull(topGenre) },
    )
    return ListeningStats(
        totalListenedMs = totalListenedMs,
        trackCount = trackCount,
        uniqueTracks = uniqueTracks,
        uniqueArtists = uniqueArtists,
        uniqueAlbums = uniqueAlbums,
        activeDays = activeDays,
        peakDate = peakDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        peakDayOfWeek = peakDayOfWeek,
        peakHour = peakHour,
        averageTrackMs = averageTrackMs,
        comparisonPercent = comparisonPercent,
        topGenre = canonicalGenres.firstOrNull(),
        topGenres = canonicalGenres,
        topLanguage = topLanguage,
        topLanguages = topLanguages,
        newFollowersCount = newFollowersCount,
        // Artist portraits are revalidated against the current YouTube Music
        // artist identity on every process start. Old builds could persist a
        // same-name portrait from another catalogue, so never paint that stale
        // URL before the verifier has run.
        topArtist = topArtist?.toStatsLeader()?.copy(artworkUrl = null),
        topSong = topSong?.toStatsLeader(),
        topAlbum = topAlbum?.toStatsLeader(),
        topPlaylist = topPlaylist?.toStatsLeader(),
        rhythmSeries = rhythmSeries,
        previousRhythmSeries = previousRhythmSeries,
    )
}

private data class StatsWindow(
    val currentStart: Instant,
    val currentEnd: Instant,
    val previousStart: Instant,
    val previousEnd: Instant,
)

private data class PlaylistStatSample(
    val playlistId: String,
    val title: String,
    val artworkUrl: String?,
    val listenedMs: Long,
)

private fun aggregateListeningStats(
    activity: List<ListeningActivity>,
    playlistActivity: List<PlaylistListeningEvent>,
    period: StatsPeriod,
    now: ZonedDateTime,
): ListeningStats {
    val window = statsWindow(period, now)
    val qualifiedActivity = activity.filter { it.reachedListenThreshold() }
    val current = qualifiedActivity.filter { row -> row.startedInstant()?.let { it >= window.currentStart && it < window.currentEnd } == true }
    val previous = qualifiedActivity.filter { row -> row.startedInstant()?.let { it >= window.previousStart && it < window.previousEnd } == true }
    val currentPlaylist = buildList {
        // New rows carry playlist attribution in Supabase itself. Existing local
        // events remain as a migration bridge for listens recorded before those
        // columns existed, so no previously collected Stats disappear.
        current.forEach { row ->
            val playlistId = row.sourcePlaylistId?.takeIf { it.isNotBlank() } ?: return@forEach
            val title = row.sourcePlaylistTitle?.takeIf { it.isNotBlank() } ?: return@forEach
            add(
                PlaylistStatSample(
                    playlistId = playlistId,
                    title = title,
                    artworkUrl = row.sourcePlaylistArtworkUrl,
                    listenedMs = row.effectivePlayedMs(),
                ),
            )
        }
        playlistActivity.forEach { event ->
            val instant = Instant.ofEpochMilli(event.finishedAtMs)
            if (instant >= window.currentStart && instant < window.currentEnd) {
                add(
                    PlaylistStatSample(
                        playlistId = event.playlistId,
                        title = event.title,
                        artworkUrl = event.artworkUrl,
                        listenedMs = event.listenedMs,
                    ),
                )
            }
        }
    }

    val currentMs = current.sumOf { it.effectivePlayedMs() }
    val previousMs = previous.sumOf { it.effectivePlayedMs() }
    val comparison = when {
        previousMs <= 0L -> null
        else -> (((currentMs - previousMs).toDouble() / previousMs.toDouble()) * 100.0).roundToInt()
    }

    val zone = now.zone
    val datedCurrent = current.mapNotNull { row ->
        row.startedInstant()?.atZone(zone)?.let { moment -> moment to row }
    }
    val activeDays = datedCurrent.map { it.first.toLocalDate() }.distinct().size
    val peakDate = datedCurrent
        .groupBy { it.first.toLocalDate() }
        .maxWithOrNull(
            compareBy<Map.Entry<LocalDate, List<Pair<ZonedDateTime, ListeningActivity>>>> { (_, rows) ->
                rows.sumOf { (_, row) -> row.effectivePlayedMs() }
            }.thenBy { it.key },
        )
        ?.key
    val peakDay = peakDate?.dayOfWeek
    val peakHour = peakDate?.let { date ->
        datedCurrent
            .asSequence()
            .filter { (moment, _) -> moment.toLocalDate() == date }
            .groupBy { (moment, _) -> moment.hour }
            .maxWithOrNull(
                compareBy<Map.Entry<Int, List<Pair<ZonedDateTime, ListeningActivity>>>> { (_, rows) ->
                    rows.sumOf { (_, row) -> row.effectivePlayedMs() }
                }.thenBy { it.key },
            )
            ?.key
    }
    val rhythmBucketCount = rhythmBucketCount(
        period = period,
        now = now,
    )
    val rhythmSeries = listeningSeriesForPeriod(
        rows = current,
        start = window.currentStart,
        period = period,
        zone = now.zone,
        buckets = rhythmBucketCount,
    )
    val previousRhythmSeries = listeningSeriesForPeriod(
        rows = previous,
        start = window.previousStart,
        period = period,
        zone = now.zone,
        buckets = rhythmBucketCount,
    )
    val topLanguages = rankedListeningLanguages(current, limit = STATS_TASTE_COUNT)
    val topLanguage = topLanguages.firstOrNull()

    return ListeningStats(
        totalListenedMs = currentMs,
        trackCount = current.size,
        uniqueTracks = current.map { it.videoId }.filter { it.isNotBlank() }.distinct().size,
        uniqueArtists = current
            .flatMap { ArtistCreditResolver.creditsForCounting(it.artist) }
            .map { it.normalizeArtistMatchKey() }
            .filter { it.isNotBlank() }
            .distinct()
            .size,
        uniqueAlbums = current.mapNotNull { it.album?.trim()?.takeIf(String::isNotBlank)?.lowercase() }.distinct().size,
        activeDays = activeDays,
        peakDate = peakDate,
        peakDayOfWeek = peakDay?.value,
        peakHour = peakHour,
        averageTrackMs = if (current.isEmpty()) 0L else currentMs / current.size,
        comparisonPercent = comparison,
        topLanguage = topLanguage,
        topLanguages = topLanguages,
        topArtist = rankListeningArtists(current, limit = 1).firstOrNull()?.let { ranked ->
            StatsLeader(
                title = ranked.title,
                listenedMs = ranked.listenedMs,
                plays = ranked.plays,
            )
        },
        topSong = rankListeningTracks(current, limit = 1).firstOrNull()?.let { ranked ->
            StatsLeader(
                title = ranked.title,
                subtitle = ranked.subtitle,
                artworkUrl = ranked.artworkUrl,
                listenedMs = ranked.listenedMs,
                plays = ranked.plays,
            )
        },
        topAlbum = rankListeningAlbums(current, limit = 1).firstOrNull()?.let { ranked ->
            StatsLeader(
                title = ranked.title,
                subtitle = ranked.subtitle,
                artworkUrl = ranked.artworkUrl,
                listenedMs = ranked.listenedMs,
                plays = ranked.plays,
            )
        },
        topPlaylist = currentPlaylist
            .groupBy { it.playlistId }
            .map { (_, rows) ->
                val lead = rows.last()
                StatsLeader(
                    title = lead.title,
                    artworkUrl = rows.asReversed().firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) },
                    sourceId = lead.playlistId,
                    listenedMs = rows.sumOf { it.listenedMs },
                    plays = rows.size,
                )
            }
            .sortedWith(
                compareByDescending<StatsLeader> { it.plays }
                    .thenByDescending { it.listenedMs }
                    .thenBy { it.title.lowercase(Locale.ROOT) },
            )
            .firstOrNull(),
        rhythmSeries = rhythmSeries,
        previousRhythmSeries = previousRhythmSeries,
    )
}

private fun rhythmBucketCount(
    period: StatsPeriod,
    now: ZonedDateTime,
): Int {
    val start = when (period) {
        StatsPeriod.WEEK -> now
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .atStartOfDay(now.zone)

        StatsPeriod.MONTH -> now
            .withDayOfMonth(1)
            .toLocalDate()
            .atStartOfDay(now.zone)

        StatsPeriod.QUARTER -> quarterStart(now)
        StatsPeriod.SEMESTER -> semesterStart(now)
        StatsPeriod.YEAR -> yearStart(now)
    }

    return when (period) {
        // One visible point for each day that has actually happened this week.
        StatsPeriod.WEEK -> now.dayOfWeek.value.coerceIn(1, 7)

        // Monthly graph uses clearly readable seven-day blocks:
        // 1–7, 8–14, 15–21, 22–28 and 29–end.
        StatsPeriod.MONTH -> (((now.dayOfMonth - 1) / 7) + 1).coerceIn(1, 5)

        // Quarter is shown week-by-week from the first day of the quarter.
        StatsPeriod.QUARTER -> {
            val days = java.time.temporal.ChronoUnit.DAYS.between(
                start.toLocalDate(),
                now.toLocalDate(),
            )
            (days / 7L + 1L).toInt().coerceIn(1, 14)
        }

        // Semester and year use calendar months rather than abstract buckets.
        StatsPeriod.SEMESTER -> {
            val firstMonth = start.monthValue
            (now.monthValue - firstMonth + 1).coerceIn(1, 6)
        }

        StatsPeriod.YEAR -> now.monthValue.coerceIn(1, 12)
    }
}

private fun listeningSeriesForPeriod(
    rows: List<ListeningActivity>,
    start: Instant,
    period: StatsPeriod,
    zone: ZoneId,
    buckets: Int,
): List<Int> {
    if (buckets <= 0) return emptyList()

    val values = IntArray(buckets)
    val startMoment = start.atZone(zone)
    val startDate = startMoment.toLocalDate()

    rows.forEach { row ->
        val instant = row.startedInstant() ?: return@forEach
        val moment = instant.atZone(zone)
        if (instant < start) return@forEach

        val index = when (period) {
            StatsPeriod.WEEK -> {
                java.time.temporal.ChronoUnit.DAYS.between(
                    startDate,
                    moment.toLocalDate(),
                ).toInt()
            }

            StatsPeriod.MONTH -> {
                val dayOffset = moment.dayOfMonth - 1
                dayOffset / 7
            }

            StatsPeriod.QUARTER -> {
                val dayOffset = java.time.temporal.ChronoUnit.DAYS.between(
                    startDate,
                    moment.toLocalDate(),
                )
                (dayOffset / 7L).toInt()
            }

            StatsPeriod.SEMESTER -> {
                (moment.year - startMoment.year) * 12 +
                    (moment.monthValue - startMoment.monthValue)
            }

            StatsPeriod.YEAR -> {
                moment.monthValue - 1
            }
        }

        if (index !in values.indices) return@forEach

        val playedSeconds = (row.effectivePlayedMs().coerceAtLeast(0L) / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        values[index] = (values[index].toLong() + playedSeconds.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    return values.toList()
}

private fun statsWindow(period: StatsPeriod, now: ZonedDateTime): StatsWindow {
    val start = when (period) {
        StatsPeriod.WEEK -> now
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .atStartOfDay(now.zone)
        StatsPeriod.MONTH -> now
            .withDayOfMonth(1)
            .toLocalDate()
            .atStartOfDay(now.zone)
        StatsPeriod.QUARTER -> quarterStart(now)
        StatsPeriod.SEMESTER -> semesterStart(now)
        StatsPeriod.YEAR -> yearStart(now)
    }
    val previousStart = when (period) {
        StatsPeriod.WEEK -> start.minusWeeks(1)
        StatsPeriod.MONTH -> start.minusMonths(1)
        StatsPeriod.QUARTER -> start.minusMonths(3)
        StatsPeriod.SEMESTER -> start.minusMonths(6)
        StatsPeriod.YEAR -> start.minusYears(1)
    }
    val previousPeriodEnd = start
    val elapsed = Duration.between(start.toInstant(), now.toInstant()).coerceAtLeast(Duration.ZERO)
    val comparablePreviousEnd = previousStart.toInstant().plus(elapsed)
        .let { if (it > previousPeriodEnd.toInstant()) previousPeriodEnd.toInstant() else it }

    return StatsWindow(
        currentStart = start.toInstant(),
        currentEnd = now.toInstant(),
        previousStart = previousStart.toInstant(),
        previousEnd = comparablePreviousEnd,
    )
}

private fun semesterStart(now: ZonedDateTime): ZonedDateTime {
    val firstMonth = if (now.monthValue <= 6) 1 else 7
    return LocalDate.of(now.year, firstMonth, 1).atStartOfDay(now.zone)
}

private fun yearStart(now: ZonedDateTime): ZonedDateTime =
    LocalDate.of(now.year, 1, 1).atStartOfDay(now.zone)

private fun quarterStart(now: ZonedDateTime): ZonedDateTime {
    val firstMonth = ((now.monthValue - 1) / 3) * 3 + 1
    return LocalDate.of(now.year, firstMonth, 1).atStartOfDay(now.zone)
}

private fun ListeningActivity.startedInstant(): Instant? =
    runCatching { Instant.parse(startedAt) }.getOrNull()


private fun rankedListeningLanguages(
    rows: List<ListeningActivity>,
    limit: Int = STATS_TASTE_COUNT,
): List<String> {
    if (rows.isEmpty() || limit <= 0) return emptyList()

    // Every valid play contributes exactly one primary vote. Listening time is
    // intentionally retained only as a tie-breaker between equal play counts.
    val scoreboard = linkedMapOf<String, Pair<Int, Long>>()
    rows.forEach { row ->
        val storedCode = row.languageCode
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("^[a-z]{2,3}$")) && it != "und" }
        val correctedCached = TrackLanguageResolver.cached(row.videoId)
        val code = if (storedCode == "ru" && correctedCached != null && correctedCached != "ru") {
            correctedCached
        } else {
            storedCode ?: TrackLanguageResolver.knownOrMetadata(
                videoId = row.videoId,
                title = row.title,
                album = row.album,
            )
        } ?: return@forEach
        val previous = scoreboard[code] ?: (0 to 0L)
        scoreboard[code] = (previous.first + 1) to (previous.second + row.effectivePlayedMs())
    }

    return scoreboard.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Pair<Int, Long>>> { it.value.first }
                .thenByDescending { it.value.second }
                .thenBy { it.key },
        )
        .take(limit)
        .map { languageDisplayName(it.key) }
}

private data class GenreRankScore(
    val name: String,
    val playWeight: Double,
    val listenedMsWeight: Double,
)

private suspend fun resolveRankedGenres(
    leaders: List<StatsLeader>,
    limit: Int = STATS_TASTE_COUNT,
): List<String> {
    if (leaders.isEmpty() || limit <= 0) return emptyList()

    // Genre providers are network-backed. Resolve the ranked artists together
    // so one slow catalogue cannot make the entire Stats card wait serially.
    val resolved = coroutineScope {
        leaders.map { leader ->
            async {
                val genres = runCatching { ArtistArtworkRepository.resolveGenres(leader.title) }
                    .getOrDefault(emptyList())
                    .let(::normalizeOrbGenres)
                leader to genres
            }
        }.awaitAll()
    }

    val scores = linkedMapOf<String, GenreRankScore>()
    resolved.forEach { (leader, genres) ->
        if (genres.isEmpty()) return@forEach

        // Multi-genre artists split one play vote across their genre tags. The
        // ranking remains play-count based; listened time only resolves ties.
        val divisor = genres.size.toDouble().coerceAtLeast(1.0)
        val playWeight = leader.plays.coerceAtLeast(0).toDouble() / divisor
        val timeWeight = leader.listenedMs.coerceAtLeast(0L).toDouble() / divisor
        genres.forEach { genre ->
            val key = genre.lowercase(Locale.ROOT)
            val previous = scores[key]
            scores[key] = GenreRankScore(
                name = previous?.name ?: genre,
                playWeight = (previous?.playWeight ?: 0.0) + playWeight,
                listenedMsWeight = (previous?.listenedMsWeight ?: 0.0) + timeWeight,
            )
        }
    }

    return scores.values
        .sortedWith(
            compareByDescending<GenreRankScore> { it.playWeight }
                .thenByDescending { it.listenedMsWeight }
                .thenBy { it.name.lowercase(Locale.ROOT) },
        )
        .take(limit)
        .map { it.name }
}

private fun languageDisplayName(code: String): String =
    runCatching {
        Locale(code).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }.getOrDefault(code.uppercase(Locale.getDefault()))


